// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.benchmark.macro

import android.os.SystemClock
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SSE 데모 자동 재생(엔드포인트 비움 → 로컬 fixture 20 Hz, 네트워크 없음) 중 프레임 타이밍.
 * `autoplay=true`로 열고 10초 동안 스트리밍 갱신 프레임을 기록한다.
 */
@RunWith(AndroidJUnit4::class)
class SseStreamingBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun sse_streaming() = rule.measureRepeated(
        packageName = DemoApp.PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = DemoApp.partial,
        startupMode = StartupMode.WARM,
        iterations = DemoApp.ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait(DemoApp.intent("SseStreamingActivity", "autoplay" to true))
        SystemClock.sleep(STREAM_MILLIS)
    }

    private companion object {
        const val STREAM_MILLIS = 10_000L
    }
}
