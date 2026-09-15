// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.widget.LinearLayout
import io.github.jimmyjung.richmarkdown.core.InputLimits
import io.github.jimmyjung.richmarkdown.view.AppearanceKey
import io.github.jimmyjung.richmarkdown.view.BlockViewBuilder
import io.github.jimmyjung.richmarkdown.view.IncrementalRebuild
import io.github.jimmyjung.richmarkdown.view.setSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 네이티브 View 렌더러 (iOS `RichMarkdownUIView`). Compose 호스팅 래퍼가 아니다.
 *
 * 파싱·수식 raster·generation 관리는 [RichMarkdownRenderModel]을 Compose 렌더러와 공유하고
 * 이 타입은 뷰 계층만 담당한다. 블록은 세로 `LinearLayout` 자식이며 rebuild는 증분이다.
 *
 * ```kotlin
 * val view = RichMarkdownView(context)
 * view.markdown = message
 * view.onContentSizeChange = { adapter.notifyItemChanged(position) }
 * ```
 */
class RichMarkdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** public ingress에서 한 번 제한한 canonical 입력. getter도 실제로 렌더링되는 텍스트만 반환한다. */
    private var boundedMarkdown: InputLimits.BoundedInput = InputLimits.bound("")

    var markdown: String
        get() = boundedMarkdown.text
        set(value) {
            val bounded = InputLimits.bound(value)
            if (bounded == boundedMarkdown) return
            boundedMarkdown = bounded
            submit()
        }

    /** opt-in dollar 수식 범위. 바뀌면 다시 파싱한다. */
    var dollarMath: LatexDollarMathOptions = LatexDollarMathOptions.None
        set(value) {
            if (field == value) return
            field = value
            submit()
        }

    var theme: RichMarkdownTheme = RichMarkdownTheme.Default
        set(value) {
            if (field == value) return
            field = value
            // Request에 실리지 않는 변경(인용 바 색 등)은 model이 dedupe해 게시가 없다. rebuild를 직접 예약한다.
            scheduleRebuild()
            submit()
        }

    /** 스트리밍 표시 옵션. parse 요청은 바꾸지 않는다. 스트림이 끝나면 `null`로 되돌린다. */
    var streaming: RichMarkdownStreamingOptions? = null
        set(value) {
            if (field == value) return
            field = value
            scheduleRebuild()
        }

    /** 코드 블록 확장(하이라이터·다이어그램). parse 요청은 바꾸지 않는다. */
    var codeBlocks: RichMarkdownCodeBlockOptions = RichMarkdownCodeBlockOptions.None
        set(value) {
            if (field == value) return
            field = value
            scheduleRebuild()
        }

    /** `null`이면 `Configuration.uiMode`를 따른다. */
    var isDarkTheme: Boolean? = null
        set(value) {
            if (field == value) return
            field = value
            scheduleRebuild()
            submit()
        }

    /** `null`이면 `Intent.ACTION_VIEW`. 링크 span은 탭 시점의 값을 읽는다. */
    var onOpenLink: ((Uri) -> Unit)? = null

    /**
     * 블록 재구성으로 높이가 바뀌면 다음 프레임에 호출된다. 수식 hydration·다이어그램 렌더는 최초 레이아웃 뒤에
     * 오므로, RecyclerView 셀에서는 여기서 self-sizing 재측정을 요청해야 한다.
     */
    var onContentSizeChange: (() -> Unit)? = null

    /** 뷰 수명 동안 유지한다. 게시 수집만 attach 구간에 한정해 RecyclerView 재사용에서 재수집한다. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 테스트에서 idle 판정(`hasOutstandingWork`)에 쓴다. */
    internal val model = RichMarkdownRenderModel(scope)
    private val reconciler = IncrementalRebuild(this)
    private var collectJob: Job? = null
    private var rebuildScheduled = false
    private var contentSizeChangeScheduled = false

    init {
        orientation = VERTICAL
        setSpacing((12f * resources.displayMetrics.density).roundToInt()) // iOS blockStack.spacing 12
        MathRenderService.shared.ensureFontsLoaded(context)
        submit()
    }

    // MARK: - Lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 첫 프레임이 비지 않도록 즉시 그리고, 이후 게시는 한 hop 뒤로 미뤄 같은 요청의 게시
        // 3회(document, mathImages 초기화, hydration)를 rebuild 1회로 합친다 (iOS objectWillChange hop).
        rebuild()
        collectJob?.cancel()
        collectJob = scope.launch { model.state.collect { scheduleRebuild() } }
    }

    override fun onDetachedFromWindow() {
        collectJob?.cancel()
        collectJob = null
        super.onDetachedFromWindow()
    }

    /** 다크 모드·fontScale·density 변화. 겉모습 키가 바뀌어 전량 재생성된다 (iOS trait change). */
    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        scheduleRebuild()
        submit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) scheduleContentSizeChange()
    }

    // MARK: - Render request

    private fun submit() {
        model.submit(currentRequest())
    }

    private fun currentRequest(): RichMarkdownRenderModel.Request = RichMarkdownRenderModel.Request(
        boundedInput = boundedMarkdown,
        dollarMath = dollarMath,
        fontSizePx = theme.bodyFont.textSizePx(context),
        colorArgb = theme.textColor.resolve(resolvedIsDark()),
        mathFont = theme.mathFont,
        // 블록 수식은 벡터로 그린다 — raster를 요청하지 않는다.
        rastersDisplayMath = false,
    )

    private fun resolvedIsDark(): Boolean = isDarkTheme
        ?: ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)

    private fun scheduleRebuild() {
        if (rebuildScheduled) return
        rebuildScheduled = true
        post {
            rebuildScheduled = false
            if (isAttachedToWindow) rebuild()
        }
    }

    private fun scheduleContentSizeChange() {
        if (contentSizeChangeScheduled) return
        contentSizeChangeScheduled = true
        // 레이아웃 pass 안에서 소비자가 어댑터를 건드리지 않도록 다음 메시지로 미룬다.
        post {
            contentSizeChangeScheduled = false
            onContentSizeChange?.invoke()
        }
    }

    private fun rebuild() {
        val state = model.state.value
        val isDark = resolvedIsDark()
        val builder = BlockViewBuilder(
            context = context,
            theme = theme,
            isDark = isDark,
            dollarMath = dollarMath.toCore(),
            codeBlocks = codeBlocks,
            scope = scope,
            openLink = ::openLink,
            onSizeChange = ::requestLayout,
        )
        val document = state.document
        if (document != null) {
            // 문서는 같아도 색·폰트 요청이 바뀌면 새 raster가 필요하다. `imageRequest`가 일치할 때만 이전 bitmap을 쓴다.
            // markdown만 다른 stale 이미지(스트리밍 append)는 계속 쓴다 — raster key는 latex 기준이다.
            val images = if (state.imageRequest?.matchesRasterConfiguration(currentRequest()) == true) {
                state.mathImages
            } else {
                emptyMap()
            }
            reconciler.renderBlocks(
                blocks = document.blocks,
                images = images,
                appearance = AppearanceKey(theme, isDark, builder.bodyPx, codeBlocks),
                streaming = streaming,
                builder = builder,
            )
        } else {
            // 최신 원문 fallback 즉시 표시 (iOS DEVELOPMENT.md §4).
            reconciler.renderFallback(state.fallbackMarkdown, builder)
        }
        requestLayout()
    }

    // MARK: - Links

    private fun openLink(uri: Uri) {
        val handler = onOpenLink
        if (handler != null) {
            handler(uri)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "링크를 열 앱이 없다: $uri")
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private companion object {
        const val TAG = "RichMarkdownView"
    }
}
