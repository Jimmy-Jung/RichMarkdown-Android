// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.mermaid

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jimmyjung.richmarkdown.RichMarkdownTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mermaid 코드 블록을 대체하는 View (iOS `MermaidDiagramUIView.swift`).
 *
 * 렌더가 끝나면 높이를 [contentHeightPx]로 확정하고 [onSizeChange]를 호출한다.
 * 실패하면 **오류 한 줄 + 원문 코드**로 되돌린다 — 일부만 그려진 다이어그램을 남기지 않는다 (Docs §3.3).
 *
 * 창에 붙지 않은 WebView는 rAF가 멈춰 렌더가 끝나지 않으므로 attach 이후에만 렌더를 시작한다 (iOS §3.5).
 * 폭·다크·글자 크기·원문 중 하나라도 바뀌면 다시 그리고, 같은 조합은 다시 그리지 않는다.
 */
class MermaidDiagramView(context: Context) : FrameLayout(context) {

    var source: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidateRender()
        }

    var theme: RichMarkdownTheme = RichMarkdownTheme.Default
        set(value) {
            if (field == value) return
            field = value
            invalidateRender()
        }

    /** 기본은 `uiMode`의 시스템 다크 여부. 한 번 지정하면 이후 configuration 변화를 따르지 않는다. */
    var isDark: Boolean
        get() = overrideDark ?: systemDark
        set(value) {
            if (overrideDark == value) return
            overrideDark = value
            invalidateRender()
        }

    /** 높이가 확정·변경될 때 호출된다. RecyclerView 셀 self-sizing 재측정에 쓴다. */
    var onSizeChange: (() -> Unit)? = null

    /** 확정된 표시 높이(px). 첫 렌더 전에는 [PLACEHOLDER_HEIGHT_DP]. */
    var contentHeightPx: Int = dp(PLACEHOLDER_HEIGHT_DP)
        private set

    internal var renderer = MermaidWebRenderer(context)
        private set
    internal val fallback = LinearLayout(context)
    internal val statusText = TextView(context)
    internal val sourceText = TextView(context)
    internal var renderJob: Job? = null
        private set

    // immediate가 아니다: onSizeChanged 안에서 시작한 렌더가 동기 실패(SourceTooLarge)해도 레이아웃 도중 onSizeChange를 부르지 않는다.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val renderRunnable = Runnable { renderIfNeeded() }
    private var systemDark = context.resources.configuration.isNight()
    private var overrideDark: Boolean? = null
    /** 마지막으로 요청한 (원문, 다크, 폭, 글자 크기). 같은 조합은 다시 그리지 않는다. */
    private var renderedKey: String? = null
    /** 렌더러 프로세스 종료 뒤 재시도를 한 번으로 제한한다 (무제한 재시도는 15초 timeout 무한 루프). */
    private var retriedAfterTermination = false

    init {
        addWebView()

        statusText.typeface = Typeface.MONOSPACE
        sourceText.typeface = Typeface.MONOSPACE
        sourceText.setTextIsSelectable(true)

        fallback.orientation = LinearLayout.VERTICAL
        fallback.setPadding(dp(12), dp(12), dp(12), dp(12))
        fallback.addView(statusText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        fallback.addView(
            sourceText,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        addView(fallback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        showStatus("Mermaid 다이어그램을 그리는 중입니다.", showsSource = false)
    }

    /** 높이는 렌더 결과를 따른다 (iOS intrinsicContentSize). 부모가 EXACTLY로 주면 그 값을 쓴다. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            contentHeightPx
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) renderIfNeeded()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderIfNeeded()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(renderRunnable)
        // 진행 중이던 렌더는 rAF가 멈춰 끝나지 않는다. 취소하고 다음 attach에서 다시 시작한다.
        renderJob?.let { if (it.isActive) renderedKey = null; it.cancel() }
        super.onDetachedFromWindow()
    }

    /** iOS trait 변경(userInterfaceStyle·contentSizeCategory) 대응. 키에 다크·글자 크기가 들어 있어 바뀐 경우만 다시 그린다. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        systemDark = newConfig.isNight()
        invalidateRender()
    }

    // MARK: - 렌더

    private fun invalidateRender() {
        renderedKey = null
        removeCallbacks(renderRunnable)
        post(renderRunnable)
    }

    private fun renderIfNeeded() {
        val widthPx = width
        // attach 전에 시작하면 rAF가 멈춰 15초 뒤 timeout으로 실패한다 (spike 실측, iOS §3.5).
        if (widthPx <= 1 || !isAttachedToWindow) return
        val dark = isDark
        val fontPx = bodyFontPx()
        // U+0001은 Mermaid 원문에 나타나지 않아 구분자로 안전하다.
        val key = "$source$dark$widthPx$fontPx"
        if (key == renderedKey) return
        renderedKey = key

        renderJob?.cancel()
        val source = source
        // INVISIBLE인 WebView는 rAF가 발화하지 않는다. 그리는 동안은 보이게 두고(투명), 실패 시에만 숨긴다.
        renderer.webView.visibility = View.VISIBLE
        renderJob = scope.launch {
            try {
                val size = renderer.render(source, dark, widthPx, fontPx)
                showDiagram(size.height)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MermaidError.WebContentTerminated) {
                // 렌더러 프로세스가 죽은 경우는 입력 문제가 아니다. WebView를 새로 만들어 한 번만 다시 그린다.
                if (retriedAfterTermination) {
                    showStatus("Mermaid 다이어그램을 표시하지 못했습니다 · ${e.message}", showsSource = true)
                } else {
                    retriedAfterTermination = true
                    replaceWebView()
                    invalidateRender()
                }
            } catch (e: Exception) {
                showStatus("Mermaid 다이어그램을 표시하지 못했습니다 · ${e.message}", showsSource = true)
            }
        }
    }

    private fun addWebView() {
        renderer.webView.contentDescription = "Mermaid 다이어그램"
        addView(renderer.webView, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** `onRenderProcessGone` 이후의 WebView는 다시 쓸 수 없다. 떼어내 destroy하고 새로 만든다. */
    private fun replaceWebView() {
        removeView(renderer.webView)
        renderer.destroy()
        renderer = MermaidWebRenderer(context)
        addWebView()
    }

    private fun showDiagram(heightPx: Int) {
        renderer.webView.visibility = View.VISIBLE
        fallback.visibility = View.GONE
        setContentHeight(heightPx)
    }

    /** 렌더 전·실패 상태. 실패면 원문을 그대로 보여 준다. */
    private fun showStatus(message: String, showsSource: Boolean) {
        renderer.webView.visibility = View.INVISIBLE
        fallback.visibility = View.VISIBLE
        val color = theme.textColor.resolve(isDark)
        statusText.setTextColor(color)
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.codeLabelFont.unscaledSizeSp)
        statusText.text = message
        sourceText.visibility = if (showsSource) View.VISIBLE else View.GONE
        sourceText.setTextColor(color)
        sourceText.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.codeFont.unscaledSizeSp)
        sourceText.text = source

        val widthPx = if (width > 1) width else dp(320)
        fallback.measure(
            MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        setContentHeight(max(dp(PLACEHOLDER_HEIGHT_DP), fallback.measuredHeight))
    }

    private fun setContentHeight(heightPx: Int) {
        if (heightPx == contentHeightPx) return
        contentHeightPx = heightPx
        requestLayout()
        onSizeChange?.invoke()
    }

    /** iOS `bodyFont.resolvedUIFont.pointSize` — sp에 시스템 fontScale이 반영된 px. */
    private fun bodyFontPx(): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, theme.bodyFont.unscaledSizeSp, resources.displayMetrics)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun Configuration.isNight(): Boolean =
        (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    companion object {
        /** 첫 렌더가 끝나기 전, 그리고 폭을 재기 위해 필요한 최소 높이 (iOS `placeholderHeight`). */
        const val PLACEHOLDER_HEIGHT_DP: Int = 64
    }
}
