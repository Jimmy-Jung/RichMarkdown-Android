// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Spanned
import android.widget.TextView
import androidx.annotation.ColorInt
import io.github.jimmyjung.richmarkdown.core.InternalRichMarkdownApi
import kotlin.math.max
import kotlin.math.min

/**
 * [InlineCodeChipSpan] 범위 뒤에 둥근 칩(배경 + 테두리)을 그린다 (iOS `InlineCodeDecorationView` 기하 이식).
 * span으로 배경을 칠하지 않으므로 텍스트 선택이 그대로 동작한다. 뷰어 `ChipTextView`와 편집기가 같은 규격을 쓴다.
 *
 * 편집기 모듈이 쓰는 내부 API — 예고 없이 바뀔 수 있음.
 */
@InternalRichMarkdownApi
class InlineCodeChipPainter(density: Float) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // iOS `1 / displayScale` pt = 기기 픽셀 1개.
        strokeWidth = 1f
    }

    /** iOS `InlineCodeChipMetrics.cornerRadius` 4pt. */
    private val cornerRadius = 4f * density

    /** iOS `InlineCodeChipMetrics.horizontalOutset` 2pt. 레이아웃 공간은 차지하지 않는다 — 행 맨 앞 칩은 그만큼 잘릴 수 있다(허용). */
    private val horizontalOutset = 2f * density

    private val chipRect = RectF()

    /**
     * 칩을 전부 깐다. `onDraw`에서 `super.onDraw` **전에** 불러 글자가 항상 칩 위에 오게 한다. 줄마다 rect 하나.
     *
     * @param textBounds 주면 칩 세로 범위를 줄 baseline 기준 이 글꼴의 top..bottom으로 줄인다(줄 안에서만).
     *   null이면 줄 전체 높이(뷰어 동작). 편집기는 문단 간격이 줄 높이에 붙어 있어 이 값을 넘긴다.
     */
    fun draw(
        canvas: Canvas,
        textView: TextView,
        @ColorInt fill: Int,
        @ColorInt border: Int,
        textBounds: Paint.FontMetricsInt? = null,
    ) {
        val spanned = textView.text as? Spanned ?: return
        val layout = textView.layout ?: return
        val chips = spanned.getSpans(0, spanned.length, InlineCodeChipSpan::class.java)
        if (chips.isEmpty()) return

        fillPaint.color = fill
        borderPaint.color = border
        val save = canvas.save()
        canvas.translate(textView.totalPaddingLeft.toFloat(), textView.totalPaddingTop.toFloat())
        for (chip in chips) {
            val start = spanned.getSpanStart(chip)
            val end = spanned.getSpanEnd(chip)
            if (end <= start) continue
            val firstLine = layout.getLineForOffset(start)
            val lastLine = layout.getLineForOffset(end - 1)
            for (line in firstLine..lastLine) {
                val segmentStart = max(start, layout.getLineStart(line))
                val segmentEnd = min(end, layout.getLineEnd(line))
                // ponytail: LTR 기준. 줄 끝(개행·공백 포함)에 닿으면 다음 줄 좌표가 나오므로 줄 오른끝을 쓴다.
                val left = layout.getPrimaryHorizontal(segmentStart)
                val right = if (segmentEnd >= layout.getLineVisibleEnd(line)) {
                    layout.getLineRight(line)
                } else {
                    layout.getPrimaryHorizontal(segmentEnd)
                }
                var top = layout.getLineTop(line)
                var bottom = layout.getLineBottom(line)
                if (textBounds != null) {
                    val baseline = layout.getLineBaseline(line)
                    top = max(top, baseline + textBounds.top)
                    bottom = min(bottom, baseline + textBounds.bottom)
                }
                chipRect.set(min(left, right) - horizontalOutset, top.toFloat(), max(left, right) + horizontalOutset, bottom.toFloat())
                if (chipRect.width() <= 0f || chipRect.height() <= 0f) continue
                val radius = min(cornerRadius, min(chipRect.width(), chipRect.height()) / 2f)
                canvas.drawRoundRect(chipRect, radius, radius, fillPaint)
                canvas.drawRoundRect(chipRect, radius, radius, borderPaint)
            }
        }
        canvas.restoreToCount(save)
    }
}
