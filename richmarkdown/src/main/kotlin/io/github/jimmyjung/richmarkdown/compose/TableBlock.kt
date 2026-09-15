// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.jimmyjung.richmarkdown.core.InlineRun
import io.github.jimmyjung.richmarkdown.core.ParsedTable
import kotlin.math.max

/** iOS `TableCellView`: `frame(minWidth: 96, maxWidth: 240)`, 패딩 가로 10·세로 8, 테두리 textColor 20%. */
private val CELL_MIN_WIDTH = 96.dp
private val CELL_MAX_WIDTH = 240.dp
private val CELL_HORIZONTAL_PADDING = 10.dp
private val CELL_VERTICAL_PADDING = 8.dp

/** iOS 0.5pt → 1dp. 셀마다 그리므로 인접 테두리는 겹친다 (iOS overlay stroke와 같은 결과). */
private val CELL_BORDER_WIDTH = 1.dp
private const val CELL_BORDER_ALPHA = 0.2f

/**
 * GFM 표. iOS `TableBlockView`(`Grid` + 가로 `ScrollView`).
 * 열 폭은 열 안 가장 넓은 셀, 행 높이는 행 안 가장 높은 셀. 헤더는 굵게 + 헤더 배경.
 */
@Composable
internal fun TableBlock(table: ParsedTable, ctx: RenderContext) {
    val columnCount = table.header.size
    if (columnCount == 0) return
    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = "표" },
    ) {
        TableLayout(columnCount) {
            for (column in 0 until columnCount) {
                // iOS `boldened`: 헤더 run은 전부 굵게.
                val runs = table.header[column].map { it.copy(bold = true) }
                TableCell(runs, table.alignment(column), isHeader = true, ctx)
            }
            for (row in table.rows) {
                for (column in 0 until columnCount) {
                    TableCell(row.getOrElse(column) { emptyList() }, table.alignment(column), isHeader = false, ctx)
                }
            }
        }
    }
}

private fun ParsedTable.alignment(column: Int): ParsedTable.ColumnAlignment? = columnAlignments.getOrNull(column)

@Composable
private fun TableCell(
    runs: List<InlineRun>,
    alignment: ParsedTable.ColumnAlignment?,
    isHeader: Boolean,
    ctx: RenderContext,
) {
    val styles = ctx.styles
    val boxAlignment = when (alignment) {
        ParsedTable.ColumnAlignment.Center -> Alignment.Center
        ParsedTable.ColumnAlignment.Right -> Alignment.CenterEnd
        ParsedTable.ColumnAlignment.Left, null -> Alignment.CenterStart
    }
    val textAlign = when (alignment) {
        ParsedTable.ColumnAlignment.Center -> TextAlign.Center
        ParsedTable.ColumnAlignment.Right -> TextAlign.End
        ParsedTable.ColumnAlignment.Left, null -> TextAlign.Start
    }
    val headerModifier = if (isHeader) {
        Modifier.background(styles.codeHeaderBackground).semantics { heading() }
    } else {
        Modifier
    }
    Box(
        headerModifier
            .border(CELL_BORDER_WIDTH, styles.textColor.copy(alpha = CELL_BORDER_ALPHA))
            .padding(horizontal = CELL_HORIZONTAL_PADDING, vertical = CELL_VERTICAL_PADDING),
        contentAlignment = boxAlignment,
    ) {
        InlineRunsText(runs, styles.body, ctx, textAlign = textAlign)
    }
}

/**
 * 단순 격자. 각 셀은 한 번만 측정한다(Compose 규칙): 열 폭은 `maxIntrinsicWidth`를 96~240dp로 자른 값의
 * 열 최대, 행 높이는 그 폭에서의 `minIntrinsicHeight`의 행 최대. 그다음 셀을 `Constraints.fixed`로
 * 측정해 배경·테두리가 격자를 꽉 채운다.
 */
@Composable
private fun TableLayout(columnCount: Int, content: @Composable () -> Unit) {
    Layout(content) { measurables, _ ->
        val minWidth = CELL_MIN_WIDTH.roundToPx()
        val maxWidth = CELL_MAX_WIDTH.roundToPx()
        val rows = measurables.chunked(columnCount)

        val columnWidths = IntArray(columnCount) { minWidth }
        for (row in rows) {
            row.forEachIndexed { column, cell ->
                val intrinsic = cell.maxIntrinsicWidth(Constraints.Infinity).coerceIn(minWidth, maxWidth)
                columnWidths[column] = max(columnWidths[column], intrinsic)
            }
        }
        val rowHeights = rows.map { row ->
            row.withIndex().maxOf { (column, cell) -> cell.minIntrinsicHeight(columnWidths[column]) }
        }
        val placeables = rows.mapIndexed { rowIndex, row ->
            row.mapIndexed { column, cell ->
                cell.measure(Constraints.fixed(columnWidths[column], rowHeights[rowIndex]))
            }
        }

        layout(columnWidths.sum(), rowHeights.sum()) {
            var y = 0
            placeables.forEachIndexed { rowIndex, row ->
                var x = 0
                row.forEachIndexed { column, placeable ->
                    placeable.place(x, y)
                    x += columnWidths[column]
                }
                y += rowHeights[rowIndex]
            }
        }
    }
}
