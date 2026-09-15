// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.compose

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.jimmyjung.richmarkdown.RenderedMath
import io.github.jimmyjung.richmarkdown.core.InlineContent
import io.github.jimmyjung.richmarkdown.core.InlineRun
import io.github.jimmyjung.richmarkdown.core.MathSegment
import kotlin.math.max
import kotlin.math.min

/** iOS `InlineCodeChipMetrics`: 모서리 4, 좌우 outset 2. 테두리 0.5pt는 mdpi에서 사라지므로 1dp. */
private val CHIP_CORNER_RADIUS = 4.dp
private val CHIP_HORIZONTAL_OUTSET = 2.dp
private val CHIP_BORDER_WIDTH = 1.dp

/**
 * 문단·헤딩·표 셀의 인라인 run을 하나의 `BasicText`로 그린다. iOS `InlineRunsText`.
 *
 * - 굵게·기울임·취소선은 `SpanStyle`, 링크는 `LinkAnnotation.Url`, 인라인 코드는 `codeFont` span +
 *   뒤에 그리는 둥근 칩, 인라인 수식은 `InlineTextContent`(계약 §6).
 * - 스트리밍 tail이면 미닫힌 opener를 숨기고 끝 grapheme의 alpha를 낮춘다.
 * - 수식이 있고 링크가 없는 문단만 "수식: LaTeX" 합성 라벨을 준다 (iOS `MathAccessibilityLabel`).
 */
@Composable
internal fun InlineRunsText(
    runs: List<InlineRun>,
    style: TextStyle,
    ctx: RenderContext,
    tail: StreamingTailContext? = null,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    val displayRuns = tail?.displayRuns(runs) ?: runs
    val fadeCount = tail?.options?.tailFadeGraphemeCount ?: 0
    val density = LocalDensity.current
    val styles = ctx.styles
    val built = remember(displayRuns, styles, ctx.images, ctx.onOpenLink, fadeCount, density) {
        buildInlineText(displayRuns, ctx, fadeCount, density)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val spokenModifier = built.spokenOverride
        ?.let { spoken -> Modifier.semantics { contentDescription = spoken } }
        ?: Modifier

    BasicText(
        text = built.text,
        modifier = modifier
            .then(spokenModifier)
            .drawBehind { drawInlineCodeChips(layout, built.codeRanges, styles) },
        style = if (textAlign == TextAlign.Unspecified) style else style.copy(textAlign = textAlign),
        onTextLayout = { layout = it },
        inlineContent = built.inlineContent,
    )
}

/** 굵게·기울임·취소선 span. Android는 italic 변형이 없는 서체(한글)에 합성 기울임을 건다 — iOS와 다른 플랫폼 동작. */
internal fun emphasisStyle(run: InlineRun): SpanStyle = SpanStyle(
    fontWeight = if (run.bold) FontWeight.Bold else null,
    fontStyle = if (run.italic) FontStyle.Italic else null,
    textDecoration = if (run.strikethrough) TextDecoration.LineThrough else null,
)

/** 한 문단의 빌드 결과. */
private class InlineTextBuild(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
    /** 인라인 코드 문자 범위 — 칩 드로잉 대상. */
    val codeRanges: List<IntRange>,
    /** 수식 있음·링크 없음일 때만 문단 전체를 읽는 합성 라벨. */
    val spokenOverride: String?,
)

private fun buildInlineText(
    runs: List<InlineRun>,
    ctx: RenderContext,
    fadeCount: Int,
    density: Density,
): InlineTextBuild {
    val plan = fadePlan(runs, fadeCount)
    val styles = ctx.styles
    val inlineContent = LinkedHashMap<String, InlineTextContent>()
    val codeRanges = ArrayList<IntRange>()
    var hasMath = false
    var hasLink = false

    val text = buildAnnotatedString {
        for (run in plan.head) {
            when (val content = run.content) {
                is InlineContent.Text -> withStyle(emphasisStyle(run)) { append(content.text) }
                is InlineContent.Code -> {
                    val start = length
                    withStyle(styles.inlineCode.merge(emphasisStyle(run))) { append(content.code) }
                    if (length > start) codeRanges += start until length
                }
                is InlineContent.Math -> {
                    hasMath = true
                    appendMath(content.segment, run, ctx, density, inlineContent)
                }
                is InlineContent.Link -> {
                    hasLink = true
                    appendLink(content, run, ctx)
                }
                InlineContent.HardBreak -> append('\n')
                InlineContent.SoftBreak -> append(' ')
            }
        }
        // 꼬리 조각은 grapheme 하나씩 alpha만 다르고 나머지 스타일은 원 run을 따른다.
        for (piece in plan.tail) {
            withStyle(fadedStyle(piece, styles.textColor)) { append(piece.text) }
        }
    }
    val spoken = if (hasMath && !hasLink) spokenText(runs) else null
    return InlineTextBuild(text, inlineContent, codeRanges, spoken)
}

private fun AnnotatedString.Builder.appendMath(
    segment: MathSegment,
    run: InlineRun,
    ctx: RenderContext,
    density: Density,
    inlineContent: MutableMap<String, InlineTextContent>,
) {
    val rendered = ctx.images[segment]
    if (rendered == null) {
        // raster 전·실패: 원래 구분자를 포함한 source를 codeFont로 표시 (계약 §7, iOS 1단계 게시 동작).
        withStyle(ctx.styles.mathFallback.merge(emphasisStyle(run))) { append(segment.source) }
        return
    }
    val id = "math:${inlineContent.size}"
    inlineContent[id] = mathInlineContent(rendered, segment, density)
    // 대체 텍스트는 원문 source — 선택·복사하면 구분자 포함 원문이 나온다.
    appendInlineContent(id, alternateText = segment.source)
}

/**
 * 인라인 수식 placeholder (계약 §6): 폭 `widthPx`, 높이 `ascent + descent`, `AboveBaseline`.
 * `AboveBaseline`은 박스 하단이 baseline이다. bitmap의 baseline은 상단에서 `ascentPx` 아래이므로
 * `descentPx`만큼 내려 그리면 두 baseline이 겹친다. 박스 밖으로 descent만큼 나가며 클립하지 않는다.
 */
private fun mathInlineContent(rendered: RenderedMath, segment: MathSegment, density: Density): InlineTextContent {
    val placeholder = with(density) {
        Placeholder(
            width = rendered.widthPx.toSp(),
            height = (rendered.ascentPx + rendered.descentPx).toSp(),
            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
        )
    }
    val description = "수식: ${segment.latex}"
    return InlineTextContent(placeholder) {
        val image = remember(rendered) { rendered.bitmap.asImageBitmap() }
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
            drawImage(image, topLeft = Offset(0f, rendered.descentPx))
        }
    }
}

private fun AnnotatedString.Builder.appendLink(content: InlineContent.Link, run: InlineRun, ctx: RenderContext) {
    val url = content.destination.toString()
    // null이면 Compose가 `LocalUriHandler`로 연다 (계약 §5).
    val listener = ctx.onOpenLink?.let { open -> LinkInteractionListener { open(Uri.parse(url)) } }
    val link = LinkAnnotation.Url(
        url = url,
        // 대비 기준을 넘는 링크 색 + 밑줄(색 외 구분 수단) — iOS와 같은 규칙.
        styles = TextLinkStyles(style = SpanStyle(color = ctx.styles.linkColor, textDecoration = TextDecoration.Underline)),
        linkInteractionListener = listener,
    )
    withLink(link) { withStyle(emphasisStyle(run)) { append(content.text) } }
}

/** iOS `accessibilityText(for:)`: 텍스트·코드·"수식: LaTeX"·링크 라벨을 읽기 순서대로 잇는다. */
private fun spokenText(runs: List<InlineRun>): String = buildString {
    for (run in runs) {
        when (val content = run.content) {
            is InlineContent.Text -> append(content.text)
            is InlineContent.Code -> append(content.code)
            is InlineContent.Math -> append("수식: ").append(content.segment.latex)
            is InlineContent.Link -> append(content.text)
            InlineContent.HardBreak, InlineContent.SoftBreak -> append(' ')
        }
    }
}

/**
 * 인라인 코드 뒤에 둥근 칩(배경 + 테두리)을 그린다. iOS `InlineCodeChipTextRenderer`.
 * 줄바꿈으로 갈라진 코드는 줄마다 하나의 rect다.
 * ponytail: 칩 높이는 줄 박스(line top~bottom)를 쓴다 — `TextLayoutResult`는 run별 폰트 메트릭을 노출하지 않는다.
 */
private fun DrawScope.drawInlineCodeChips(layout: TextLayoutResult?, ranges: List<IntRange>, styles: ResolvedTheme) {
    if (layout == null || ranges.isEmpty()) return
    val outset = CHIP_HORIZONTAL_OUTSET.toPx()
    val maxRadius = CHIP_CORNER_RADIUS.toPx()
    val stroke = Stroke(CHIP_BORDER_WIDTH.toPx())
    for (range in ranges) {
        val firstLine = layout.getLineForOffset(range.first)
        val lastLine = layout.getLineForOffset(range.last)
        for (line in firstLine..lastLine) {
            val start = max(range.first, layout.getLineStart(line))
            val end = min(range.last + 1, layout.getLineEnd(line, visibleEnd = true))
            if (start >= end) continue
            val first = layout.getBoundingBox(start)
            val last = layout.getBoundingBox(end - 1)
            val left = min(first.left, last.left) - outset
            val right = max(first.right, last.right) + outset
            val top = layout.getLineTop(line)
            val bottom = layout.getLineBottom(line)
            if (right <= left || bottom <= top) continue
            val topLeft = Offset(left, top)
            val size = Size(right - left, bottom - top)
            val radius = CornerRadius(min(maxRadius, min(size.width, size.height) / 2))
            drawRoundRect(styles.inlineCodeBackground, topLeft, size, radius)
            drawRoundRect(styles.inlineCodeBorder, topLeft, size, radius, style = stroke)
        }
    }
}
