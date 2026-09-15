// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.mermaid.spike

import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P0 spike: iOS와 공유하는 mermaid/index.html이 Android WebView에서 그대로 동작하는지 실측한다.
 * - WebViewAssetLoader가 assets를 https 오리진으로 서빙해 CSP `'self'`를 통과하는가
 * - `window.renderDiagram` Promise 결과가 JavascriptInterface로 돌아오는가 (cold/warm ms 로그)
 * - 잘못된 원문이 reject → onError로 오는가 (fail-open 계약)
 *
 * WebView는 Activity에 붙인다. 창에 붙지 않은 WebView는 hidden 상태라
 * index.html이 기다리는 requestAnimationFrame이 발화하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class MermaidWebViewSpikeTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(SpikeHostActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: WebView

    // WebView는 브리지를 약참조로 잡으므로 테스트가 강참조를 유지해야 한다.
    private val bridge = Bridge()

    /** JS → Kotlin 브리지. 호출마다 reset()으로 latch를 새로 건다. */
    class Bridge {
        @Volatile var result: String? = null
        @Volatile var error: String? = null
        @Volatile var latch = CountDownLatch(1)

        fun reset() {
            result = null
            error = null
            latch = CountDownLatch(1)
        }

        @JavascriptInterface
        fun onResult(json: String) {
            result = json
            latch.countDown()
        }

        @JavascriptInterface
        fun onError(message: String) {
            error = message
            latch.countDown()
        }
    }

    private data class Outcome(val json: String?, val error: String?, val elapsedMs: Double)

    @Before
    fun loadPage() {
        val pageLoaded = CountDownLatch(1)
        val start = SystemClock.elapsedRealtimeNanos()
        activityRule.scenario.onActivity { activity ->
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
                .build()
            webView = WebView(activity).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)

                    override fun onPageFinished(view: WebView, url: String) {
                        pageLoaded.countDown()
                    }
                }
                addJavascriptInterface(bridge, "Android")
            }
            activity.setContentView(webView)
            webView.loadUrl(INDEX_URL)
        }
        assertTrue("index.html이 30초 안에 로드되지 않았다", pageLoaded.await(30, TimeUnit.SECONDS))
        log("[spike] mermaid page load: ${fmt((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)} ms")
    }

    @After
    fun destroyWebView() {
        if (!::webView.isInitialized) return
        instrumentation.runOnMainSync {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    @Test
    fun flowchart가_렌더되고_cold_warm_시간을_남긴다() {
        val cold = renderDiagram("graph TD; A-->B; B-->C;", dark = false)
        assertNull("cold 렌더 오류: ${cold.error}", cold.error)
        val json = JSONObject(requireNotNull(cold.json) { "onResult JSON이 없다" })
        assertEquals(360, json.getInt("width"))
        assertTrue("height=${json.optDouble("height")}", json.getDouble("height") > 0.0)
        log("[spike] mermaid cold: ${fmt(cold.elapsedMs)} ms, result=$json")

        // 같은 WebView에서 두 번째 렌더. mermaid 초기화 비용이 빠진 값이다.
        val warm = renderDiagram("graph LR; X-->Y;", dark = false)
        assertNull("warm 렌더 오류: ${warm.error}", warm.error)
        assertTrue(JSONObject(requireNotNull(warm.json)).getDouble("height") > 0.0)
        log("[spike] mermaid warm: ${fmt(warm.elapsedMs)} ms")
    }

    @Test
    fun 잘못된_원문은_onError로_온다() {
        val outcome = renderDiagram("graph TD; A--->", dark = false)
        log("[spike] mermaid error: ${outcome.error}")
        assertNull("잘못된 원문이 onResult로 왔다: ${outcome.json}", outcome.json)
        assertTrue("onError 메시지가 비어 있다", !outcome.error.isNullOrEmpty())
    }

    @Test
    fun dark_테마로_렌더된다() {
        val outcome = renderDiagram("graph TD; A-->B;", dark = true)
        assertNull("dark 렌더 오류: ${outcome.error}", outcome.error)
        assertTrue(JSONObject(requireNotNull(outcome.json)).getDouble("height") > 0.0)
    }

    /** renderDiagram(source, dark, 360, 16)을 호출하고 onResult/onError 중 하나가 올 때까지 기다린다. */
    private fun renderDiagram(source: String, dark: Boolean): Outcome {
        bridge.reset()
        // 원문은 인자로만 넘긴다. renderDiagram 자체가 없으면(페이지 미로드) sync 오류로 알린다.
        val script = """
            try {
              window.renderDiagram(${JSONObject.quote(source)}, $dark, 360, 16).then(
                function (r) { Android.onResult(JSON.stringify(r)); },
                function (e) { Android.onError(String(e && e.message || e)); });
            } catch (e) { Android.onError('sync: ' + String(e && e.message || e)); }
            'started'
        """.trimIndent()
        val start = SystemClock.elapsedRealtimeNanos()
        instrumentation.runOnMainSync { webView.evaluateJavascript(script, null) }
        assertTrue("renderDiagram 응답이 30초 안에 오지 않았다: $source", bridge.latch.await(30, TimeUnit.SECONDS))
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        return Outcome(bridge.result, bridge.error, elapsedMs)
    }

    private fun fmt(ms: Double): String = "%.1f".format(ms)

    private fun log(message: String) {
        Log.i(TAG, message)
        println(message)
    }

    private companion object {
        const val TAG = "RichMarkdownSpike"
        const val INDEX_URL = "https://appassets.androidplatform.net/assets/mermaid/index.html"
    }
}
