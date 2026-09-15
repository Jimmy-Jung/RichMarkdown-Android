// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.mermaid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Size
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 공식 Mermaid JavaScript를 `WebView` 안에서 실행하고, 만들어진 SVG를 그 WebView에 그대로 표시한다
 * (iOS `MermaidWebRenderer.swift`).
 *
 * assets만 로드한다(`WebViewAssetLoader` → `https://appassets.androidplatform.net/assets/`, index.html의
 * CSP `'self'` 유지). 런타임 다운로드가 없고, 원문은 실행할 코드가 아니라 `renderDiagram`의 **인자**로만
 * 전달한다(`JSONObject.quote`). 페이지 안에서의 이동과 링크 실행은 막는다 (Docs §3.4).
 *
 * 창에 붙지 않은 WebView는 `requestAnimationFrame`이 멈춰 `renderDiagram`이 끝나지 않는다(P0 spike 실측).
 * 호출자는 attach 이후에만 [render]를 부른다 (iOS §3.5).
 */
class MermaidWebRenderer(context: Context) {
    val webView: WebView = WebView(context)

    /** `onRenderProcessGone` 이후 true. 이 WebView는 다시 쓸 수 없으니 호출자가 새로 만든다. */
    var isDead: Boolean = false
        private set

    private var isLoaded = false
    private var loadDeferred: CompletableDeferred<Unit>? = null

    /** 테스트 확인용: 크기 검사가 로드보다 먼저 끝났는지. */
    internal val hasLoadStarted: Boolean get() = isLoaded || loadDeferred != null
    private var serial = 0
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val bridge = Bridge()

    init {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        configureSettings()
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.webViewClient = Client(loader)
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureSettings() = with(webView.settings) {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        // 확대는 1~5배 (iOS scrollView zoomScale 1~5). viewport meta의 maximum-scale=5를 적용하려면 wide viewport가 필요하다.
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        useWideViewPort = true
        // WebView는 기본으로 시스템 fontScale을 textZoom에 반영한다. fontSize를 sp로 이미 환산해 넘기므로 이중 확대를 막는다.
        textZoom = 100
    }

    /**
     * 원문을 그려 필요한 표시 크기(px)를 돌려준다.
     * @param widthPx 사용 가능한 폭(px). CSS px = dp이므로 density로 나눠 넘긴다. 이보다 넓은 다이어그램은 축소하고, 좁으면 확대하지 않는다.
     * @param fontSizePx 본문 글자 크기(px, sp 환산 포함). Mermaid 라벨 크기에 반영한다.
     */
    suspend fun render(source: String, dark: Boolean, widthPx: Int, fontSizePx: Float): Size =
        withContext(Dispatchers.Main.immediate) {
            if (source.isBlank()) throw MermaidError.EmptySource
            val bytes = source.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_SOURCE_UTF8_BYTES) throw MermaidError.SourceTooLarge(bytes, MAX_SOURCE_UTF8_BYTES)
            if (isDead) throw MermaidError.WebContentTerminated

            val density = webView.resources.displayMetrics.density
            val safeWidthDp = floor(widthPx / density).coerceIn(120f, 2_000f)
            val fontSizeDp = fontSizePx / density

            loadIfNeeded()

            val result = evaluateRender(source, dark, safeWidthDp, fontSizeDp)
            val heightDp = result.optDouble("height", Double.NaN)
            if (!heightDp.isFinite() || heightDp <= 0 || heightDp > MAX_HEIGHT_DP) throw MermaidError.InvalidSize

            Size((safeWidthDp * density).roundToInt(), ceil(heightDp * density).toInt())
        }

    /** 렌더러 프로세스 종료 뒤 교체할 때만 부른다. 이후 이 인스턴스는 쓰지 않는다. */
    @MainThread
    fun destroy() {
        isDead = true
        webView.destroy()
    }

    // MARK: - 로드

    private suspend fun loadIfNeeded() {
        if (isLoaded) return
        val deferred = loadDeferred ?: CompletableDeferred<Unit>().also {
            loadDeferred = it
            webView.loadUrl(INDEX_URL)
        }
        try {
            withTimeout(TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            deferred.completeExceptionally(MermaidError.LoadTimeout)
            throw MermaidError.LoadTimeout
        } finally {
            // 실패한 로드는 버린다. 다음 render가 loadUrl을 다시 부른다 (iOS finishLoading 뒤 재시도와 같다).
            if (deferred.isCompleted && loadDeferred === deferred) loadDeferred = null
        }
    }

    private fun finishLoading(error: Throwable?) {
        val deferred = loadDeferred ?: return
        if (error == null) {
            if (deferred.complete(Unit)) isLoaded = true
        } else {
            deferred.completeExceptionally(error)
        }
    }

    // MARK: - renderDiagram 호출

    private suspend fun evaluateRender(source: String, dark: Boolean, widthDp: Float, fontSizeDp: Float): JSONObject {
        val id = ++serial
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        // 원문은 JSONObject.quote로 문자열 리터럴이 되어 인자로만 들어간다. renderDiagram이 없으면(페이지 손상) sync 예외로 알린다.
        val script = """
            (function () {
              try {
                window.renderDiagram(${JSONObject.quote(source)}, $dark, $widthDp, $fontSizeDp).then(
                  function (r) { $BRIDGE_NAME.onResult($id, JSON.stringify(r)); },
                  function (e) { $BRIDGE_NAME.onError($id, String(e && e.message || e)); });
              } catch (e) { $BRIDGE_NAME.onError($id, String(e && e.message || e)); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
        return try {
            withTimeout(TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            // iOS callAsyncJavaScript에는 timeout이 없다. Android는 rAF가 멈추는 경우가 있어 load와 같은 상한을 건다.
            throw MermaidError.RenderFailed("Mermaid 렌더 응답이 ${TIMEOUT_MS / 1_000}초 안에 오지 않았습니다.")
        } finally {
            pending.remove(id)
        }
    }

    /** JS → Kotlin. WebView의 "JavaBridge" 스레드에서 호출되므로 CompletableDeferred로만 넘긴다. */
    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: Int, json: String) {
            val deferred = pending.remove(id) ?: return
            runCatching { JSONObject(json) }
                .onSuccess { deferred.complete(it) }
                .onFailure { deferred.completeExceptionally(MermaidError.InvalidSize) }
        }

        @JavascriptInterface
        fun onError(id: Int, message: String) {
            pending.remove(id)?.completeExceptionally(MermaidError.RenderFailed(message))
        }
    }

    private inner class Client(private val loader: WebViewAssetLoader) : WebViewClientCompat() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            loader.shouldInterceptRequest(request.url)

        /** loadUrl로 시작한 최초 로드에는 호출되지 않는다. 다이어그램 안의 링크·외부 이동은 전부 막는다. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        override fun onPageFinished(view: WebView, url: String) = finishLoading(null)

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
            if (request.isForMainFrame) finishLoading(MermaidError.ResourceMissing)
        }

        /** assets에 index.html이 없으면 AssetLoader가 404를 돌려주고 onPageFinished도 온다. 먼저 실패로 확정한다. */
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) finishLoading(MermaidError.ResourceMissing)
        }

        /** iOS `webViewWebContentProcessDidTerminate`. true를 돌려 앱 종료를 막고, 호출자가 WebView를 새로 만든다. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            isDead = true
            isLoaded = false
            finishLoading(MermaidError.WebContentTerminated)
            loadDeferred = null
            pending.values.forEach { it.completeExceptionally(MermaidError.WebContentTerminated) }
            pending.clear()
            return true
        }
    }

    companion object {
        /** 샘플이 아니라 채팅 본문에 섞이는 다이어그램을 전제로 한 상한 (iOS `maxSourceUTF8Bytes`). */
        const val MAX_SOURCE_UTF8_BYTES: Int = 20_000

        /** index.html이 4,000pt + 패딩 32를 돌려줄 수 있으므로 iOS와 같은 4,032. maxEdges 200·maxTextSize 20,000은 index.html에 있다. */
        private const val MAX_HEIGHT_DP = 4_032.0

        /** iOS loadTimeout 15초. */
        private const val TIMEOUT_MS = 15_000L

        private const val BRIDGE_NAME = "RichMarkdownMermaid"
        private const val INDEX_URL = "https://appassets.androidplatform.net/assets/mermaid/index.html"
    }
}
