// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.benchmark.macro

import android.os.SystemClock
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 챗 화면 스크롤 프레임 타이밍. 대화 fixture 전체가 진입 시 바로 표시되므로 autoplay 없이 연다.
 * 6페이지 내려갔다가 6페이지 올라온다 (iOS xctrace 시나리오와 동일).
 *
 * 제스처는 shell `input swipe`(화면 높이 50% 이동, 300 ms)다. UiAutomator `By.scrollable` 조회 +
 * `UiObject2.scroll`은 SM-G988N(Android 13)에서 Compose 챗 스크롤 첫 웜업 중 커널 패닉
 * (`Oops PC = kmem_cache_free+0xf0`)으로 기기를 재부팅시켰다(2회 재현, 같은 화면의 수동 input swipe는 정상).
 * 접근성 트리를 건드리지 않는 같은 제스처를 View·Compose 양쪽에 써 비교 조건을 맞춘다.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scroll_viewChat() = scroll("ViewChatActivity")

    @Test
    fun scroll_composeChat() = scroll("ComposeChatActivity")

    private fun scroll(activity: String) = rule.measureRepeated(
        packageName = DemoApp.PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = DemoApp.partial,
        startupMode = StartupMode.WARM,
        iterations = DemoApp.ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait(DemoApp.intent(activity))
            SystemClock.sleep(SETTLE_MILLIS) // 수식 raster·코드 하이라이트 비동기 게시 대기
        },
    ) {
        pages(down = true)
        pages(down = false)
    }

    private fun MacrobenchmarkScope.pages(down: Boolean) {
        val x = device.displayWidth / 2
        val low = device.displayHeight * 3 / 4
        val high = device.displayHeight / 4
        val (from, to) = if (down) low to high else high to low
        repeat(PAGES) {
            device.executeShellCommand("input swipe $x $from $x $to $SWIPE_MILLIS")
            SystemClock.sleep(FLING_SETTLE_MILLIS)
        }
    }

    private companion object {
        const val PAGES = 6
        const val SWIPE_MILLIS = 300
        const val FLING_SETTLE_MILLIS = 700L
        const val SETTLE_MILLIS = 1_500L
    }
}
