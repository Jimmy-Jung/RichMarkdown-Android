// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.mermaid

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jimmyjung.richmarkdown.RichMarkdownCodeBlockOptions
import io.github.jimmyjung.richmarkdown.mermaid.spike.SpikeHostActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * iOS `MermaidRendererTests.swift` 포팅. 실제 WebView에서 번들 Mermaid를 실행하므로 에뮬레이터에서만 돈다.
 * 창에 붙지 않은 WebView는 rAF가 멈추므로 [SpikeHostActivity]에 뷰를 붙인다 (iOS는 UIWindow를 직접 만든다).
 */
@RunWith(AndroidJUnit4::class)
class MermaidDiagramViewTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(SpikeHostActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    // MARK: - 번들

    @Test
    fun assets에_Mermaid_번들이_있고_외부를_향하지_않는다() {
        val files = context.assets.list("mermaid")!!.toSet()
        assertTrue(files.toString(), files.containsAll(setOf("index.html", "mermaid.bundle.js", "MERMAID-THIRD-PARTY-NOTICES.txt")))

        val html = context.assets.open("mermaid/index.html").bufferedReader().readText()
        assertTrue(html.contains("connect-src 'none'"))
        assertFalse("런타임 다운로드는 하지 않는 계약이다", html.contains("https://"))
    }

    // MARK: - 실제 렌더

    @Test
    fun flowchart가_렌더되어_높이가_확정되고_onSizeChange가_온다() {
        val sizeChanged = CountDownLatch(1)
        val view = newView("graph TD; A-->B;") { onSizeChange = { sizeChanged.countDown() } }
        val placeholder = view.contentHeightPx

        attach(view)
        awaitRender(view, previous = null)

        assertTrue("onSizeChange가 오지 않았다", sizeChanged.await(5, TimeUnit.SECONDS))
        assertTrue("height=${view.contentHeightPx}", view.contentHeightPx > placeholder)
        assertEquals(View.GONE, onMain { view.fallback.visibility })
        assertEquals("true", evaluate(view, "!!document.querySelector('#diagram svg')"))
    }

    @Test
    fun 잘못된_원문은_오류_한_줄과_원문으로_되돌린다() {
        val source = "flowchart LR\n  A --> (((("
        val view = newView(source)

        attach(view)
        awaitRender(view, previous = null)

        assertEquals(View.VISIBLE, onMain { view.fallback.visibility })
        assertEquals(View.INVISIBLE, onMain { view.renderer.webView.visibility })
        assertEquals(source, onMain { view.sourceText.text.toString() })
        assertTrue(onMain { view.statusText.text.toString() }.contains("표시하지 못했습니다"))
    }

    @Test
    fun 상한을_넘는_원문은_WebView를_건드리지_않고_되돌린다() {
        val source = "flowchart LR\n  A --> B\n".repeat(2_000)
        assertTrue(source.toByteArray().size > MermaidWebRenderer.MAX_SOURCE_UTF8_BYTES)
        val view = newView(source)

        attach(view)
        awaitRender(view, previous = null)

        val status = onMain { view.statusText.text.toString() }
        assertTrue(status, status.contains("상한 ${MermaidWebRenderer.MAX_SOURCE_UTF8_BYTES}바이트"))
        assertFalse("크기 검사는 로드 전에 끝나야 한다", onMain { view.renderer.hasLoadStarted })
        assertEquals(source, onMain { view.sourceText.text.toString() })
    }

    @Test
    fun 다크_전환은_같은_WebView에서_다시_그린다() {
        val view = newView("graph TD; A-->B;")
        attach(view)
        awaitRender(view, previous = null)
        assertEquals("richmarkdown-mermaid-1", evaluate(view, "document.querySelector('#diagram svg').id"))

        val first = onMain { view.renderJob }
        onMain { view.isDark = true }
        awaitRender(view, previous = first)

        assertEquals(View.GONE, onMain { view.fallback.visibility })
        // index.html은 호출마다 serial을 올린다. 2번이면 같은 WebView에서 두 번째 렌더가 끝난 것이다.
        assertEquals("richmarkdown-mermaid-2", evaluate(view, "document.querySelector('#diagram svg').id"))
    }

    @Test
    fun 같은_뷰의_두_번째_렌더는_초기화_비용이_빠진다_로그만() {
        val view = newView("graph TD; A-->B;")
        val coldStart = SystemClock.elapsedRealtimeNanos()
        attach(view)
        awaitRender(view, previous = null)
        val coldMs = (SystemClock.elapsedRealtimeNanos() - coldStart) / 1_000_000.0

        val first = onMain { view.renderJob }
        val warmStart = SystemClock.elapsedRealtimeNanos()
        onMain { view.source = "graph LR; X-->Y;" }
        awaitRender(view, previous = first)
        val warmMs = (SystemClock.elapsedRealtimeNanos() - warmStart) / 1_000_000.0

        Log.i(TAG, "[mermaid] cold %.1f ms, warm %.1f ms".format(coldMs, warmMs))
        assertEquals(View.GONE, onMain { view.fallback.visibility })
    }

    // MARK: - RichMarkdown 확장점 연결

    @Test
    fun 렌더러는_mermaid_언어만_담당하고_옵션은_인스턴스로_비교한다() {
        val renderer = MermaidDiagramRenderer.shared
        assertEquals(setOf("mermaid"), renderer.languages)
        assertEquals(RichMarkdownCodeBlockOptions(diagram = renderer), RichMarkdownCodeBlockOptions(diagram = renderer))
        assertNotEquals(RichMarkdownCodeBlockOptions(diagram = renderer), RichMarkdownCodeBlockOptions.None)
    }

    @Test
    fun 뷰는_placeholder_높이로_시작한다() {
        val view = newView("graph TD; A-->B;")
        val expected = (MermaidDiagramView.PLACEHOLDER_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
        assertEquals(expected.toFloat(), view.contentHeightPx.toFloat(), 1f)
    }

    // MARK: - helpers

    /** WebView는 Activity context로 만든다 (spike와 같다). */
    private fun newView(source: String, configure: MermaidDiagramView.() -> Unit = {}): MermaidDiagramView {
        lateinit var view: MermaidDiagramView
        activityRule.scenario.onActivity { activity ->
            view = MermaidDiagramView(activity).apply {
                this.source = source
                configure()
            }
        }
        return view
    }

    private fun attach(view: MermaidDiagramView) {
        activityRule.scenario.onActivity { activity ->
            activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** 레이아웃 뒤에 시작되는 렌더 Job을 기다린다. [previous]와 다른 새 Job이 잡히면 끝날 때까지 join한다. */
    private fun awaitRender(view: MermaidDiagramView, previous: Job?) = runBlocking {
        withTimeout(30_000) {
            var job: Job? = null
            while (job == null) {
                job = onMain { view.renderJob }?.takeIf { it !== previous }
                if (job == null) delay(50)
            }
            job.join()
        }
    }

    private fun evaluate(view: MermaidDiagramView, expression: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        onMain {
            view.renderer.webView.evaluateJavascript("String($expression)") {
                result = it.trim('"')
                latch.countDown()
            }
        }
        assertTrue("evaluateJavascript 응답이 없다", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        const val TAG = "RichMarkdownMermaid"
    }
}
