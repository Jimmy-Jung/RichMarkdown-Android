// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jimmyjung.richmarkdown.core.ParsedBlock

/** iOS `VStack(alignment: .leading, spacing: 12)` — 최상위 블록 간격. */
internal val BLOCK_SPACING: Dp = 12.dp

/** iOS 인용: `RoundedRectangle(cornerRadius: 2).frame(width: 4)`, 바-본문·자식 간 `spacing 8`. */
private val QUOTE_BAR_WIDTH = 4.dp
private val QUOTE_BAR_RADIUS = 2.dp
private val QUOTE_SPACING = 8.dp

/** iOS 리스트: 항목 간 `spacing 4`, 마커-본문 `spacing 8`. */
private val LIST_ITEM_SPACING = 4.dp
private val LIST_MARKER_SPACING = 8.dp

/** 깊이별 비순서 마커. 0단계 `•`는 iOS와 같고 하위 단계는 Android 관례(◦ ▪)를 따른다. */
private val BULLETS = listOf("•", "◦", "▪")

/** 블록 열. 스트리밍 tail 문맥은 마지막 블록에만 내려간다 (iOS `offset == lastIndex ? tail : nil`). */
@Composable
internal fun BlockColumn(
    blocks: List<ParsedBlock>,
    ctx: RenderContext,
    tail: StreamingTailContext?,
    modifier: Modifier = Modifier,
    spacing: Dp = BLOCK_SPACING,
    listDepth: Int = 0,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        blocks.forEachIndexed { index, block ->
            BlockView(block, ctx, tail.takeIf { index == blocks.lastIndex }, listDepth)
        }
    }
}

/** iOS `RichMarkdownBlockView`. */
@Composable
internal fun BlockView(block: ParsedBlock, ctx: RenderContext, tail: StreamingTailContext?, listDepth: Int) {
    when (block) {
        is ParsedBlock.Paragraph -> InlineRunsText(block.runs, ctx.styles.body, ctx, tail)
        is ParsedBlock.Heading -> InlineRunsText(
            block.runs,
            ctx.styles.heading(block.level),
            ctx,
            tail,
            modifier = Modifier.semantics { heading() },
        )
        is ParsedBlock.CodeBlock -> CodeBlockView(block.language, block.code, ctx)
        is ParsedBlock.BlockMath -> BlockMathView(block.segment, ctx)
        is ParsedBlock.BlockQuote -> BlockQuoteView(block.children, ctx, tail, listDepth)
        is ParsedBlock.UnorderedList ->
            ListBlockView(block.items, ctx, tail, listDepth) { BULLETS[listDepth % BULLETS.size] }
        is ParsedBlock.OrderedList ->
            ListBlockView(block.items, ctx, tail, listDepth) { index -> "${block.start + index}." }
        is ParsedBlock.Table -> TableBlock(block.table, ctx)
        // iOS `Divider()`. 색은 기본 테마 `inlineCodeBorder`(= iOS separator)와 같다.
        ParsedBlock.ThematicBreak ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(ctx.styles.inlineCodeBorder))
    }
}

@Composable
private fun BlockQuoteView(
    children: List<ParsedBlock>,
    ctx: RenderContext,
    tail: StreamingTailContext?,
    listDepth: Int,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(QUOTE_SPACING),
    ) {
        Box(
            Modifier
                .width(QUOTE_BAR_WIDTH)
                .fillMaxHeight()
                .background(ctx.styles.quoteBar, RoundedCornerShape(QUOTE_BAR_RADIUS)),
        )
        BlockColumn(children, ctx, tail, Modifier.weight(1f), QUOTE_SPACING, listDepth)
    }
}

/**
 * 리스트. 마커와 첫 줄은 first baseline 정렬 (iOS `HStack(alignment: .firstTextBaseline)`).
 * Column은 자식의 `FirstBaseline`을 위로 전파하므로 `alignByBaseline`이 첫 문단 baseline을 잡고,
 * 첫 블록이 코드·표처럼 baseline이 없으면 top 정렬로 물러난다.
 */
@Composable
private fun ListBlockView(
    items: List<List<ParsedBlock>>,
    ctx: RenderContext,
    tail: StreamingTailContext?,
    listDepth: Int,
    marker: (Int) -> String,
) {
    // iOS `monospacedDigit()`: 자릿수가 달라도 마커 폭이 흔들리지 않게 tabular figures.
    val markerStyle = ctx.styles.body.copy(fontFeatureSettings = "tnum")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LIST_ITEM_SPACING)) {
        items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LIST_MARKER_SPACING)) {
                BasicText(marker(index), Modifier.alignByBaseline(), style = markerStyle)
                BlockColumn(
                    item,
                    ctx,
                    tail.takeIf { index == items.lastIndex },
                    Modifier.alignByBaseline().weight(1f),
                    LIST_ITEM_SPACING,
                    listDepth + 1,
                )
            }
        }
    }
}
