// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.compose

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import io.github.jimmyjung.richmarkdown.RichMarkdownHighlightSpan
import io.github.jimmyjung.richmarkdown.RichMarkdownSyntaxHighlighting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** iOS `CodeBlockView`: 모서리 8, 본문 패딩 12, 헤더 좌우 패딩 12, 라벨-버튼 최소 간격 8. */
private val CODE_CORNER_RADIUS = 8.dp
private val CODE_BODY_PADDING = 12.dp
private val CODE_HEADER_HORIZONTAL_PADDING = 12.dp
private val CODE_HEADER_MIN_GAP = 8.dp

/** iOS 44pt hit target → 48dp (치환표 §4). 고정 크기 — 아이콘 교체로 헤더 폭이 흔들리지 않는다. */
private val COPY_BUTTON_SIZE = 48.dp
private val COPY_ICON_SIZE = 16.dp

/**
 * 코드 블록. 헤더(언어 라벨 + 복사) 아래에 가로 스크롤 monospace 본문.
 * 다이어그램 렌더러가 담당하는 언어면 본문만 대체하고 헤더는 남겨 원문을 항상 가져갈 수 있다.
 */
@Composable
internal fun CodeBlockView(language: String?, code: String, ctx: RenderContext) {
    val styles = ctx.styles
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(CODE_CORNER_RADIUS))) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(styles.codeHeaderBackground)
                .padding(horizontal = CODE_HEADER_HORIZONTAL_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // secondary 회색은 밝은 헤더 배경에서 작은 텍스트 대비(4.5:1) 미달 — 테마 텍스트 색을 쓴다 (iOS 동일).
            BasicText(language ?: "code", style = styles.codeLabel)
            Spacer(Modifier.weight(1f).widthIn(min = CODE_HEADER_MIN_GAP))
            CopyButton(code, contentDescription = "코드 복사", tint = styles.textColor)
        }
        val diagram = ctx.codeBlocks.diagramRenderer(language)
        if (diagram != null) {
            Box(Modifier.fillMaxWidth().background(styles.codeBlockBackground)) {
                diagram.Content(code, ctx.theme)
            }
        } else {
            CodeBody(language, code, ctx)
        }
    }
}

@Composable
private fun CodeBody(language: String?, code: String, ctx: RenderContext) {
    val highlighter = ctx.codeBlocks.highlighter
    // 색은 늦게 와도 된다: plain으로 먼저 그리고 범위가 도착하면 색만 바꾼다. 언어·원문이 바뀌면 initialValue로
    // 되돌아가 이전 범위를 즉시 버린다 — 위치가 밀린 색을 한 프레임도 보이지 않는다 (iOS `HighlightState`).
    val spans by produceState(emptyList<RichMarkdownHighlightSpan>(), highlighter, language, code) {
        value = if (highlighter == null || language == null || code.isEmpty()) {
            emptyList()
        } else {
            highlightSpans(highlighter, code, language)
        }
    }
    val styles = ctx.styles
    val text = remember(code, spans, styles) { highlightedCode(code, spans, styles) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(styles.codeBlockBackground)
            .horizontalScroll(rememberScrollState()),
    ) {
        BasicText(text, Modifier.padding(CODE_BODY_PADDING), style = styles.code)
    }
}

/** 하이라이트 실패는 plain (계약 §7). 구현체는 예외를 던지지 않기로 했지만 fail-open을 여기서도 지킨다. */
private suspend fun highlightSpans(
    highlighter: RichMarkdownSyntaxHighlighting,
    code: String,
    language: String,
): List<RichMarkdownHighlightSpan> = try {
    highlighter.spans(code, language)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    emptyList()
}

/** iOS `RichMarkdownHighlightSegments`: 시작 순 정렬, 겹침·빈 범위·원문 밖 범위는 건너뛴다. 원문은 바꾸지 않는다. */
private fun highlightedCode(
    code: String,
    spans: List<RichMarkdownHighlightSpan>,
    styles: ResolvedTheme,
): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(code)
    return buildAnnotatedString {
        append(code)
        var cursor = 0
        for (span in spans.sortedBy { it.range.start }) {
            val range = span.range
            if (range.start < cursor || range.isEmpty || range.end > code.length) continue
            addStyle(SpanStyle(color = styles.syntaxColor(span.kind)), range.start, range.end)
            cursor = range.end
        }
    }
}

/**
 * 코드·수식 원문 복사 버튼. iOS `CopyButton`. 아이콘은 Material 의존 없이 Canvas로 그린다.
 * ponytail: 체크 표시는 다시 누를 때까지 유지한다 — 타이머 복귀는 UI 테스트 idle을 붙잡는다 (iOS 동일).
 */
@Composable
internal fun CopyButton(text: String, contentDescription: String, tint: Color) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(text) { mutableStateOf(false) }
    Box(
        Modifier
            .size(COPY_BUTTON_SIZE)
            .clickable(role = Role.Button) {
                // 원래 구분자를 포함한 원문 source를 그대로 넣는다.
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
                copied = true
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(COPY_ICON_SIZE)) { if (copied) drawCheckmark(tint) else drawDocOnDoc(tint) }
    }
}

/** SF Symbol `doc.on.doc` 대응: 겹친 둥근 사각 둘. */
private fun DrawScope.drawDocOnDoc(tint: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.1f)
    val radius = CornerRadius(s * 0.12f)
    val doc = Size(s * 0.62f, s * 0.72f)
    drawRoundRect(tint, topLeft = Offset(s * 0.05f, s * 0.28f), size = doc, cornerRadius = radius, style = stroke)
    drawRoundRect(tint, topLeft = Offset(s * 0.33f, 0f), size = doc, cornerRadius = radius, style = stroke)
}

/** SF Symbol `checkmark` 대응. */
private fun DrawScope.drawCheckmark(tint: Color) {
    val s = size.minDimension
    val path = Path().apply {
        moveTo(s * 0.15f, s * 0.52f)
        lineTo(s * 0.4f, s * 0.78f)
        lineTo(s * 0.86f, s * 0.24f)
    }
    drawPath(path, tint, style = Stroke(width = s * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
