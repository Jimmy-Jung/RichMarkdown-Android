// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import io.github.jimmyjung.richmarkdown.compose.BlockColumn
import io.github.jimmyjung.richmarkdown.compose.RenderContext
import io.github.jimmyjung.richmarkdown.compose.ResolvedTheme
import io.github.jimmyjung.richmarkdown.compose.StreamingTailContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Markdown + LaTeX 렌더 컴포저블. iOS `RichMarkdownView` (계약 §5).
 *
 * ```kotlin
 * RichMarkdown(markdown = message, dollarMath = LatexDollarMathOptions.Single)
 * ```
 *
 * - 스트리밍은 누적 전체 문자열을 계속 넘긴다. append(이전 문자열의 확장)에서는 새 parse가 게시될 때까지
 *   이전 렌더를 유지하고, 전혀 다른 문자열(셀 재사용)은 즉시 원문 fallback으로 넘어간다.
 * - 파서 실패(D3a)·아직 parse 전이면 bounded 원문을 `bodyFont`로 표시한다 (계약 §7 fail-open).
 * - 메시지 전체의 세로 스크롤·목록 virtualization은 소비 앱 책임이다. 뷰는 주어진 폭을 채운다.
 *
 * @param isDarkTheme 테마 색 `RichMarkdownColor.resolve(isDark)`의 기준. 기본은 시스템 다크 모드.
 * @param onOpenLink null이면 `LocalUriHandler`가 링크를 연다. `https`·`http`·`mailto`만 링크가 된다.
 */
@Composable
fun RichMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    dollarMath: LatexDollarMathOptions = LatexDollarMathOptions.None,
    theme: RichMarkdownTheme = RichMarkdownTheme.Default,
    streaming: RichMarkdownStreamingOptions? = null,
    codeBlocks: RichMarkdownCodeBlockOptions = RichMarkdownCodeBlockOptions.None,
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    onOpenLink: ((Uri) -> Unit)? = null,
) {
    val context = LocalContext.current
    // 멱등·아무 스레드 (계약 §1). 첫 프레임을 폰트 로드(~9ms)로 막지 않도록 Default에서 한 번 부른다.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { MathRenderService.shared.ensureFontsLoaded(context) }
    }

    val scope = rememberCoroutineScope()
    val model = remember { RichMarkdownRenderModel(scope) }
    val density = LocalDensity.current
    // 수식 기준 크기 = bodyFont sp × fontScale × density (iOS `mathPointSize` × displayScale).
    val fontSizePx = with(density) { theme.bodyFont.unscaledSizeSp.sp.toPx() }
    val colorArgb = theme.textColor.resolve(isDarkTheme)
    // 요청을 한 번만 canonicalize한다 (InputLimits.bound). body와 submit이 같은 값을 써야
    // 대형 원문의 bounded fallback에서 이전 문서가 한 프레임 되살아나지 않는다.
    val request = remember(markdown, dollarMath, fontSizePx, colorArgb, theme.mathFont) {
        RichMarkdownRenderModel.Request.of(
            markdown = markdown,
            dollarMath = dollarMath,
            fontSizePx = fontSizePx,
            colorArgb = colorArgb,
            mathFont = theme.mathFont,
            // 블록 수식은 벡터 레이아웃으로 그리므로 display raster는 만들지 않는다.
            rastersDisplayMath = false,
        )
    }
    LaunchedEffect(request) { model.submit(request) }
    val state by model.state.collectAsState()
    val styles = remember(theme, isDarkTheme) { ResolvedTheme(theme, isDarkTheme) }

    // 모델 문서가 현재 요청과 같은 parse identity거나 그 스트리밍 prefix면 표시한다 (iOS `content(for:)`).
    val document = state.document
    val identity = state.parseIdentity
    val showsDocument = document != null && identity != null &&
        (identity == request.parseIdentity || identity.isStreamingPrefix(request.parseIdentity))

    SelectionContainer(modifier) {
        if (showsDocument) {
            // 색·크기가 바뀐 새 raster가 준비되기 전에는 이전 bitmap을 섞지 않고 source fallback을 유지한다.
            // markdown만 다른 stale 이미지는 계속 쓴다 — 수식 raster는 문서 안 위치와 무관하다.
            val images = if (state.imageRequest?.matchesRasterConfiguration(request) == true) {
                state.mathImages
            } else {
                emptyMap()
            }
            val ctx = RenderContext(styles, images, codeBlocks, fontSizePx, colorArgb, onOpenLink)
            val tail = streaming?.let { StreamingTailContext(it, dollarMath.toCore()) }
            BlockColumn(document.blocks, ctx, tail, Modifier.fillMaxWidth())
        } else {
            // Request가 입력을 제한해 보관하므로 raw markdown을 UI에 직접 전달하지 않는다.
            BasicText(request.markdown, Modifier.fillMaxWidth(), style = styles.body)
        }
    }
}
