// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.demo

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * 데모 전 화면의 ATF 접근성 검사 (iOS `AccessibilityAuditUITests.swift`, DEVELOPMENT.md §5 접근성).
 *
 * 화면마다 Activity를 띄우고 표시가 안정되면 decor view 전체에 [AccessibilityValidator]를 돌린다.
 * 대기 조건이 ATF hierarchy 안의 화면 표식 글자라서 Compose 가상 노드가 검사 대상에 없으면(ATF 3.x) 시간 초과로 실패한다.
 * SSE 화면은 로컬 시뮬레이션 스트림이 끝난 뒤의 최종 화면을 검사한다.
 *
 * ERROR는 실패, WARNING은 로그로만 남긴다(Espresso `AccessibilityChecks` 기본값과 같다). 제외 항목은 없다 —
 * 생기면 ATF 결과 문구 그대로 이유를 적는다. 한 화면의 실패가 나머지 화면 검사를 막지 않도록 모든 화면을 돈 뒤
 * 한 번에 실패한다.
 */
@RunWith(AndroidJUnit4::class)
class DemoAccessibilityTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation = instrumentation.uiAutomation
    private val originalNightMode = shell("cmd uimode night").trim()

    /**
     * API 33 미만에서는 돌리지 않는다(2026-10-14 API 30 에뮬레이터 실측). ATF 4.1.1이 Compose 화면의 글자를 hierarchy에
     * 담지 못해 화면 표식 대기가 모두 시간 초과되고, View 화면의 선택 가능한 문단(길게 눌러 선택하는 글자)을 탭 대상으로 보고
     * 48dp 미만이라고 지적한다. 문장 안 글자는 터치 크기 기준의 예외이고 API 37에서는 같은 화면에서 지적되지 않는다.
     */
    @Before
    fun requireApi33() {
        assumeTrue("ATF가 API 33 미만에서 Compose 글자를 읽지 못한다", Build.VERSION.SDK_INT >= 33)
    }

    @After
    fun restoreNightMode() {
        shell("cmd uimode night ${if (originalNightMode.endsWith("yes")) "yes" else "no"}")
    }

    @Test
    fun auditEveryScreenInLightMode() = auditEveryScreen(night = false)

    @Test
    fun auditEveryScreenInDarkMode() = auditEveryScreen(night = true)

    // MARK: - 화면 목록

    /**
     * @param marker 화면이 그려졌고 검사 hierarchy에 들어왔음을 알리는 글자.
     * @param ready 검사할 상태가 되었음을 알리는 글자. 없으면 marker만 기다린다.
     */
    private class Screen(
        val name: String,
        val activity: Class<out Activity>,
        val marker: String,
        val ready: String? = null,
        val extras: Intent.() -> Unit = {},
    )

    private val screens = listOf(
        Screen("홈", MainActivity::class.java, marker = "AI 챗봇 (Compose)"),
        Screen("샘플 쇼케이스 (Compose)", ShowcaseActivity::class.java, marker = "샘플 쇼케이스"),
        Screen("샘플 쇼케이스 (View)", ShowcaseActivity::class.java, marker = "샘플 쇼케이스") {
            putExtra(ShowcaseActivity.EXTRA_RENDERER, "view")
        },
        Screen("코드 블록 확장", ShowcaseActivity::class.java, marker = "코드 블록 확장") {
            putExtra(ShowcaseActivity.EXTRA_SECTION, "code")
        },
        Screen("AI 챗봇 (Compose)", ComposeChatActivity::class.java, marker = "AI 챗봇"),
        Screen("AI 챗봇 (View)", ViewChatActivity::class.java, marker = "AI 챗봇"),
        Screen("SSE 실시간 렌더링", SseStreamingActivity::class.java, marker = "SSE 실시간 렌더링", ready = "완료 ([DONE])") {
            putExtra(SseStreamingActivity.EXTRA_AUTOPLAY, true)
        },
        Screen("블록 편집", BlockEditorActivity::class.java, marker = "블록 편집"),
    )

    // MARK: - 실행

    private fun auditEveryScreen(night: Boolean) {
        shell("cmd uimode night ${if (night) "yes" else "no"}")
        val mode = if (night) "dark" else "light"
        val failures = ArrayList<String>()
        // `-e screen <이름 일부>`로 한 화면만 다시 돌릴 수 있다.
        val only = InstrumentationRegistry.getArguments().getString("screen")
        for (screen in screens.filter { only == null || it.name.contains(only) }) {
            val intent = Intent(instrumentation.targetContext, screen.activity).apply(screen.extras)
            ActivityScenario.launch<Activity>(intent).use { scenario ->
                val shown = awaitStableScreen(screen) {
                    var texts: List<String> = emptyList()
                    scenario.onActivity { texts = atfHierarchyTexts(it) }
                    texts
                }
                if (shown == null) {
                    failures += "[$mode] ${screen.name}: 화면 표식 '${screen.marker}'/'${screen.ready}'이 나타나지 않았다"
                    return@use
                }
                var results: List<AccessibilityViewCheckResult> = emptyList()
                scenario.onActivity { results = validator().checkAndReturnResults(it.window.decorView) }
                failures += report("[$mode] ${screen.name} (ATF hierarchy 글자 ${shown.size}개)", results)
            }
        }
        assertTrue("접근성 검사 실패 ${failures.size}건:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** ERROR를 실패 문구로 바꾼다. WARNING은 로그로 남긴다. */
    private fun report(label: String, results: List<AccessibilityViewCheckResult>): List<String> {
        val failures = ArrayList<String>()
        val contrast = results.filter { it.sourceCheckClass.simpleName == "TextContrastCheck" }.groupingBy { it.type }.eachCount()
        Log.i(TAG, "$label: 결과 ${results.groupingBy { it.type }.eachCount()}, TextContrastCheck $contrast")
        for (result in results) {
            val line = "$label: ${result.type} ${result.sourceCheckClass.simpleName} \"${result.getMessage(Locale.ENGLISH)}\" @ ${element(result)}"
            when {
                result.type == AccessibilityCheckResultType.WARNING -> Log.w(TAG, line)
                result.type != AccessibilityCheckResultType.ERROR -> Unit
                else -> {
                    Log.e(TAG, line)
                    failures += line
                }
            }
        }
        return failures
    }

    private fun validator() = AccessibilityValidator()
        .setRunChecksFromRootView(true)
        .setCaptureScreenshots(true)
        .setThrowExceptionFor(null)

    private fun element(result: AccessibilityViewCheckResult): String {
        val element = result.element ?: return "view=${result.view?.javaClass?.simpleName}"
        return "class=${element.className} text=${element.text} description=${element.contentDescription} " +
            "id=${element.resourceName} bounds=${element.boundsInScreen}"
    }

    /** ATF가 실제로 검사하는 hierarchy의 글자·설명. */
    private fun atfHierarchyTexts(activity: Activity): List<String> =
        com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
            .newBuilder(activity.window.decorView).build()
            .activeWindow.allViews
            .flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }

    // MARK: - 대기

    /**
     * 표식이 나타난 뒤 ATF hierarchy 글자가 [STABLE_SNAPSHOTS]번 연속 같아질 때까지 기다린다. 시간 초과면 null.
     * UiAutomation `rootInActiveWindow`는 노드 캐시가 Compose 갱신을 놓쳐 낡은 트리를 돌려준 적이 있어(SSE 상태 글자 누락,
     * 2026-10-14 실측) 매번 View 트리에서 새로 만드는 ATF hierarchy를 쓴다.
     */
    private fun awaitStableScreen(screen: Screen, snapshot: () -> List<String>): List<String>? {
        val deadline = SystemClock.uptimeMillis() + SCREEN_TIMEOUT_MS
        var previous: List<String>? = null
        var streak = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val texts = snapshot()
            val ready = texts.any { it.contains(screen.marker) } && (screen.ready == null || texts.any { it.contains(screen.ready) })
            streak = if (ready && texts == previous) streak + 1 else 0
            if (streak >= STABLE_SNAPSHOTS) return texts
            previous = texts
            SystemClock.sleep(SNAPSHOT_INTERVAL_MS)
        }
        Log.e(TAG, "${screen.name}: 시간 초과. 마지막 접근성 글자 ${previous?.size}개: ${previous?.take(40)}")
        return null
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private companion object {
        const val TAG = "RichMarkdownA11y"
        const val SCREEN_TIMEOUT_MS = 120_000L
        const val SNAPSHOT_INTERVAL_MS = 500L
        const val STABLE_SNAPSHOTS = 3
    }
}
