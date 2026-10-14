// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.mermaid

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import io.github.jimmyjung.richmarkdown.mermaid.spike.SpikeHostActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Mermaid 보안 페이로드(`fixtures/security/mermaid`, Tests/Fixtures/README.md §8)를 production
 * [MermaidWebRenderer]로 그린다 (iOS `MermaidSecurityPayloadTests.swift`).
 *
 * 페이로드는 실행되면 `window.__richmarkdownPwned`를 1로 만든다. 통과 조건은 렌더 완료(또는 거부) 뒤 값이 0,
 * 최초 로컬 로드 외 네비게이션 0건, 자산 오리진 밖 요청 0건이다. 렌더 뒤 다이어그램의 모든 요소에 click을
 * 흘려 `click` 콜백·링크 페이로드도 시도한다.
 */
@RunWith(Parameterized::class)
class MermaidSecurityPayloadTest(private val file: String) {

    @get:Rule
    val activityRule = ActivityScenarioRule(SpikeHostActivity::class.java)

    private lateinit var renderer: MermaidWebRenderer
    private lateinit var recorder: RecordingClient

    @After
    fun tearDown() {
        if (::renderer.isInitialized) onMain { renderer.destroy() }
    }

    @Test
    fun payloadNeitherExecutesNorLeavesTheBundle() {
        val source = readAsset("$PAYLOAD_DIR/$file")
        val expected = expectation(source)
        attachRenderer()

        // 페이지를 먼저 로드해 감시 장치를 건다. `__richmarkdownPwn`은 click callback 페이로드가 부르는 이름이다.
        runBlocking { renderer.render(WARM_UP_SOURCE, dark = false, widthPx = px(320), fontSizePx = px(17).toFloat()) }
        val initialHref = JSONArray("[${evaluate(webView(), ARM_SCRIPT)}]").getString(0)
        val initialUrl = onMain { webView().url }

        val outcome = runCatching {
            runBlocking { renderer.render(source, dark = false, widthPx = px(320), fontSizePx = px(17).toFloat()) }
        }
        assertOutcome(expected, outcome)

        // 사용자가 다이어그램을 누른 것처럼 모든 요소에 click을 보낸다(링크 기본 동작 포함).
        evaluate(webView(), CLICK_SWEEP_SCRIPT)
        SystemClock.sleep(CLICK_SETTLE_MS)
        val state = JSONObject(evaluate(webView(), STATE_SCRIPT))
        Log.i(TAG, "$file: outcome=${outcome.exceptionOrNull() ?: outcome.getOrNull()} state=$state " +
            "requests=${recorder.requests} overrides=${recorder.overrides} pageStarts=${recorder.pageStarts}")

        assertNoEscape(state, initialHref, initialUrl)
        if (source.contains("click A href")) {
            // 링크가 SVG `<a>`로 남으면 click sweep이 실제 이동을 시도하고 판정 함수가 막아야 한다.
            assertTrue("외부 링크 이동 시도가 기록되지 않았다: ${recorder.overrides}", recorder.overrides.isNotEmpty())
        }
    }

    // MARK: - 판정

    private fun assertOutcome(expected: Expectation, outcome: Result<android.util.Size>) {
        val error = outcome.exceptionOrNull()
        when (expected) {
            Expectation.Rendered -> {
                assertNull("$file: rendered 예상인데 실패했다: $error", error)
                assertTrue("$file: 높이가 0이다", outcome.getOrThrow().height > 0)
            }
            Expectation.Rejected -> {
                assertTrue("$file: rejected 예상인데 그려졌다: ${outcome.getOrNull()}", error != null)
                assertTrue("$file: 거부는 렌더러 오류여야 한다: $error", isRejection(error!!))
            }
        }
    }

    private fun assertNoEscape(state: JSONObject, initialHref: String, initialUrl: String?) {
        assertEquals("$file: 페이로드가 실행됐다", 0, state.getInt("pwned"))
        assertEquals("$file: 페이지가 다른 곳으로 이동했다", initialHref, state.getString("href"))
        assertEquals("$file: WebView URL이 바뀌었다", initialUrl, onMain { webView().url })

        val resources = state.getJSONArray("resources").strings()
        assertTrue("$file: 자산 오리진 밖 리소스: $resources", resources.all { it.startsWith(ASSET_ORIGIN) })
        assertEquals("$file: 네비게이션은 최초 로드 1건이다", 1, state.getJSONArray("navigations").length())
        val violations = state.getJSONArray("violations").strings()
        assertTrue("$file: 살균을 통과해 CSP에서야 막힌 시도가 있다: $violations", violations.isEmpty())

        assertTrue("$file: 자산 오리진 밖 요청: ${recorder.requests}", recorder.requests.all { it.startsWith(ASSET_ORIGIN) })
        assertEquals("$file: 최초 로드 외 페이지 시작: ${recorder.pageStarts}", 1, recorder.pageStarts.size)
        assertTrue("$file: 허용된 이동이 있다: ${recorder.overrides}", recorder.overrides.all { it.second })
    }

    /** 렌더러가 원문을 거부한 오류인가. 로드·응답 시간 초과나 프로세스 종료는 인프라 실패다. */
    private fun isRejection(error: Throwable): Boolean = when (error) {
        is MermaidError.SourceTooLarge, is MermaidError.InvalidSize -> true
        is MermaidError.RenderFailed -> !error.reason.contains("안에 오지 않았습니다")
        else -> false
    }

    private fun expectation(source: String): Expectation {
        val firstLine = source.lineSequence().first()
        assertTrue("$file: 첫 줄에 `$EXPECT_MARKER` 주석이 있어야 한다", firstLine.startsWith(EXPECT_MARKER))
        return when (val value = firstLine.removePrefix(EXPECT_MARKER).trim()) {
            "rendered" -> Expectation.Rendered
            "rejected" -> Expectation.Rejected
            else -> throw AssertionError("$file: 알 수 없는 expect 값 $value")
        }
    }

    private enum class Expectation { Rendered, Rejected }

    // MARK: - 하네스

    /** 창에 붙지 않은 WebView는 rAF가 멈춰 렌더가 끝나지 않는다. 기록기는 최초 로드 전에 끼운다. */
    private fun attachRenderer() {
        activityRule.scenario.onActivity { activity ->
            renderer = MermaidWebRenderer(activity)
            recorder = RecordingClient(renderer.webView.webViewClient)
            renderer.webView.webViewClient = recorder
            activity.setContentView(renderer.webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun webView(): WebView = renderer.webView

    private fun px(dp: Int): Int = (dp * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density).toInt()

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

    companion object {
        const val PAYLOAD_DIR = "fixtures/security/mermaid"
        const val ASSET_ORIGIN = "https://appassets.androidplatform.net/assets/"
        private const val TAG = "RichMarkdownMermaidSec"
        private const val EXPECT_MARKER = "%% expect: "
        private const val WARM_UP_SOURCE = "graph TD\n  A --> B"
        private const val CLICK_SETTLE_MS = 300L

        private val ARM_SCRIPT = """
            (function () {
              window.__richmarkdownPwned = 0;
              window.__richmarkdownPwn = function () { window.__richmarkdownPwned = 1; };
              window.__richmarkdownViolations = [];
              document.addEventListener('securitypolicyviolation', function (event) {
                window.__richmarkdownViolations.push(event.violatedDirective + ' ' + event.blockedURI);
              });
              return location.href;
            })()
        """.trimIndent()

        private val CLICK_SWEEP_SCRIPT = """
            (function () {
              document.querySelectorAll('#diagram *').forEach(function (element) {
                element.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, view: window}));
              });
              return true;
            })()
        """.trimIndent()

        private val STATE_SCRIPT = """
            (function () {
              return {
                pwned: window.__richmarkdownPwned,
                href: location.href,
                resources: performance.getEntriesByType('resource').map(function (e) { return e.name; }),
                navigations: performance.getEntriesByType('navigation').map(function (e) { return e.name; }),
                violations: window.__richmarkdownViolations
              };
            })()
        """.trimIndent()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun payloads(): List<String> = InstrumentationRegistry.getInstrumentation().context.assets
            .list(PAYLOAD_DIR).orEmpty().filter { it.endsWith(".mmd") }.sorted()
    }
}

/**
 * WebView 보안 설정 기준표 고정 (`p3/notes-webview-hardening.md` Android 열, iOS
 * `webViewConfigurationMatchesHardeningTable`·`contentSecurityPolicyMatchesTheTableAndHashesTheInlineScript`).
 */
@RunWith(AndroidJUnit4::class)
class MermaidWebViewHardeningTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(SpikeHostActivity::class.java)

    private val renderers = ArrayList<MermaidWebRenderer>()
    private val plainWebViews = ArrayList<WebView>()

    @After
    fun tearDown() = onMain {
        renderers.forEach { it.destroy() }
        plainWebViews.forEach { it.destroy() }
    }

    @Test
    fun payloadFixturesArePresent() {
        val payloads = MermaidSecurityPayloadTest.payloads()
        assertEquals("security/mermaid 페이로드 18종을 읽어야 한다: $payloads", 18, payloads.size)
    }

    @Suppress("DEPRECATION")
    @Test
    fun webViewSettingsMatchHardeningTable() {
        val renderer = newRenderer(attach = false)
        onMain {
            val webView = renderer.webView
            with(webView.settings) {
                assertTrue("다이어그램 실행에 JS가 필요하다", javaScriptEnabled)
                assertFalse("파일 접근 금지", allowFileAccess)
                assertFalse("content:// 접근 금지", allowContentAccess)
                assertFalse("file URL 간 접근 금지", allowFileAccessFromFileURLs)
                assertFalse("file URL universal 접근 금지", allowUniversalAccessFromFileURLs)
                assertFalse("새 창 자동 열기 금지", javaScriptCanOpenWindowsAutomatically)
                assertFalse("다중 창 미지원", supportMultipleWindows())
                assertEquals("혼합 콘텐츠 금지", WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
                assertFalse("DOM storage 미사용(비영속 저장소 대응)", domStorageEnabled)
            }
            assertNull("새 창·alert를 받아 줄 WebChromeClient가 없다", webView.webChromeClient)
        }
    }

    /** 공개 API로 JS 인터페이스를 열거할 수 없어, 같은 페이지를 띄운 일반 WebView와 window 전역을 비교한다. */
    @Test
    fun exactlyOneJavascriptInterfaceWithTwoMethods() {
        val renderer = newRenderer(attach = true)
        runBlocking { renderer.render("graph TD\n  A --> B", dark = false, widthPx = 640, fontSizePx = 34f) }
        val rendererGlobals = globals(renderer.webView)

        val plain = loadPlainPage()
        evaluate(plain, "(window.renderDiagram('graph TD\\n  A --> B', false, 320, 17, null).then(function () { document.body.dataset.done = '1'; }), true)")
        awaitTrue(plain, "document.body.dataset.done === '1'")
        val plainGlobals = globals(plain)

        assertEquals("주입된 전역은 브리지 하나다", setOf(BRIDGE_NAME), rendererGlobals - plainGlobals)
        val methods = JSONArray(evaluate(renderer.webView, "(function () { var k = []; for (var p in $BRIDGE_NAME) k.push(p); return k.sort(); })()"))
        assertEquals("브리지 메서드는 onError·onResult 둘이다", listOf("onError", "onResult"), List(methods.length()) { methods.getString(it) })
        assertEquals("\"undefined\"", evaluate(renderer.webView, "typeof $BRIDGE_NAME.getClass"))
    }

    @Test
    fun everyUrlLoadingRequestIsOverridden() {
        val renderer = newRenderer(attach = false)
        val urls = listOf(
            "https://evil.example/exfil", "http://evil.example/exfil", "javascript:window.__richmarkdownPwned=1",
            "data:text/html,<script>1</script>", "file:///etc/passwd", "content://settings/system", "about:blank",
            "intent://evil#Intent;scheme=https;end", "${MermaidSecurityPayloadTest.ASSET_ORIGIN}mermaid/index.html",
        )
        onMain {
            val client = renderer.webView.webViewClient
            for (url in urls) for (gesture in listOf(true, false)) {
                assertTrue("$url [gesture=$gesture]은 막혀야 한다", client.shouldOverrideUrlLoading(renderer.webView, StubRequest(url, gesture)))
            }
        }
    }

    @Test
    fun contentSecurityPolicyMatchesTheTableAndHashesTheInlineScript() {
        val html = readAsset("mermaid/index.html")
        val metaPrefix = "<meta http-equiv=\"Content-Security-Policy\" content=\""
        val policy = html.substringAfter(metaPrefix).substringBefore('"')
        assertEquals(EXPECTED_CSP, policy)
        assertEquals("CSP meta는 하나다", 2, html.split("Content-Security-Policy").size)

        // 정책의 sha256은 유일한 인라인 스크립트의 해시여야 한다.
        val inline = html.substringAfter("<script>").substringBefore("</script>")
        val digest = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(inline.toByteArray()), Base64.NO_WRAP)
        assertTrue("인라인 스크립트 해시 $digest 가 정책에 없다", EXPECTED_CSP.contains("'sha256-$digest'"))
        assertEquals("외부 번들 1개 + 인라인 1개", 3, html.split("<script").size)
    }

    // MARK: - 도움 함수

    private fun newRenderer(attach: Boolean): MermaidWebRenderer {
        lateinit var renderer: MermaidWebRenderer
        activityRule.scenario.onActivity { activity ->
            renderer = MermaidWebRenderer(activity)
            if (attach) activity.addFullScreen(renderer.webView)
        }
        return renderer.also { renderers += it }
    }

    /** 브리지 없이 같은 index.html을 같은 오리진에서 띄운 기준 WebView. */
    private fun loadPlainPage(): WebView {
        val loaded = CountDownLatch(1)
        lateinit var webView: WebView
        activityRule.scenario.onActivity { activity ->
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
                .build()
            webView = WebView(activity)
            @Suppress("SetJavaScriptEnabled")
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = loader.shouldInterceptRequest(request.url)
                override fun onPageFinished(view: WebView, url: String) = loaded.countDown()
            }
            activity.addFullScreen(webView)
            webView.loadUrl("${MermaidSecurityPayloadTest.ASSET_ORIGIN}mermaid/index.html")
        }
        plainWebViews += webView
        assertTrue("기준 페이지 로드 시간 초과", loaded.await(15, TimeUnit.SECONDS))
        return webView
    }

    private fun globals(webView: WebView): Set<String> {
        val names = JSONArray(evaluate(webView, "Object.getOwnPropertyNames(window)"))
        return List(names.length()) { names.getString(it) }.toSet()
    }

    private fun awaitTrue(webView: WebView, expression: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (evaluate(webView, "String($expression)") != "\"true\"") {
            assertTrue("$expression 대기 시간 초과", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50)
        }
    }

    private fun Activity.addFullScreen(webView: WebView) {
        addContentView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private class StubRequest(private val url: String, private val gesture: Boolean) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = gesture
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private companion object {
        /** `MermaidWebRenderer.BRIDGE_NAME`(private)과 같아야 한다. */
        const val BRIDGE_NAME = "RichMarkdownMermaid"

        /** 기준표의 CSP. iOS `expectedContentSecurityPolicy`와 같은 문자열이다. */
        const val EXPECTED_CSP = "default-src 'none'; " +
            "script-src 'self' 'sha256-owS6vspznh+XfsKdMf/uPhpgCs0AR1cHc7Qqzp3EtJI='; " +
            "style-src 'self' 'unsafe-inline'; img-src data:; font-src 'self' data:; " +
            "connect-src 'none'; object-src 'none'; base-uri 'none'"
    }
}

/** 렌더러의 WebViewClient 앞에 끼워 요청·이동·페이지 시작을 기록한다. 판정은 렌더러가 내린다. */
internal class RecordingClient(private val renderer: WebViewClient) : WebViewClient() {
    /** `shouldInterceptRequest`는 WebView IO 스레드에서 온다. */
    val requests = CopyOnWriteArrayList<String>()
    val overrides = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val pageStarts = CopyOnWriteArrayList<String>()

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        requests += request.url.toString()
        return renderer.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        renderer.shouldOverrideUrlLoading(view, request).also { overrides += request.url.toString() to it }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        pageStarts += url
        renderer.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String) = renderer.onPageFinished(view, url)

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) =
        renderer.onReceivedError(view, request, error)

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) =
        renderer.onReceivedHttpError(view, request, errorResponse)

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
        renderer.onRenderProcessGone(view, detail)
}

private fun readAsset(path: String): String =
    InstrumentationRegistry.getInstrumentation().context.assets.open(path).bufferedReader().use { it.readText() }

/** evaluateJavascript 결과(JSON 직렬화 문자열)를 그대로 돌려준다. */
private fun evaluate(webView: WebView, script: String): String {
    val latch = CountDownLatch(1)
    var result = "null"
    onMain {
        webView.evaluateJavascript(script) {
            result = it
            latch.countDown()
        }
    }
    assertTrue("evaluateJavascript 응답이 없다", latch.await(10, TimeUnit.SECONDS))
    return result
}

private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
}
