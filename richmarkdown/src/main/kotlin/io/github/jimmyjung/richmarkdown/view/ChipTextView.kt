// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Spannable
import android.text.Spanned
import android.text.method.ArrowKeyMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlin.math.max
import kotlin.math.min

/**
 * 문단·헤딩·표 셀·코드 본문·원문 fallback이 공유하는 텍스트 뷰 (iOS `RichMarkdownTextView`).
 *
 * `InlineCodeChipSpan` 범위 뒤에 둥근 칩을 **그린다** — span으로 배경을 칠하지 않으므로
 * 텍스트 선택이 그대로 동작한다 (iOS `InlineCodeDecorationView` 기하 이식).
 */
internal class ChipTextView(context: Context) : TextView(context) {
    private val density = context.resources.displayMetrics.density
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

    /** 코드 블록 색 범위를 기다리는 작업. 새 코드를 실으면 이전 작업을 취소한다 (iOS `highlightTask`). */
    var highlightJob: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    init {
        setTextIsSelectable(true)
        // `LinkMovementMethod`는 `canSelectArbitrarily() == false`라 선택이 죽는다. 선택 이동 방식 위에 링크 탭만 얹는다.
        movementMethod = LinkTouchMovementMethod()
    }

    fun setChipColors(fill: Int, border: Int) {
        fillPaint.color = fill
        borderPaint.color = border
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawChips(canvas)
        super.onDraw(canvas)
    }

    /** 칩을 전부 깐 뒤 텍스트를 그려 글자가 항상 칩 위에 온다. 줄마다 rect 하나. */
    private fun drawChips(canvas: Canvas) {
        val spanned = text as? Spanned ?: return
        val layout = layout ?: return
        val chips = spanned.getSpans(0, spanned.length, InlineCodeChipSpan::class.java)
        if (chips.isEmpty()) return

        val save = canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
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
                chipRect.set(
                    min(left, right) - horizontalOutset,
                    layout.getLineTop(line).toFloat(),
                    max(left, right) + horizontalOutset,
                    layout.getLineBottom(line).toFloat(),
                )
                if (chipRect.width() <= 0f || chipRect.height() <= 0f) continue
                val radius = min(cornerRadius, min(chipRect.width(), chipRect.height()) / 2f)
                canvas.drawRoundRect(chipRect, radius, radius, fillPaint)
                canvas.drawRoundRect(chipRect, radius, radius, borderPaint)
            }
        }
        canvas.restoreToCount(save)
    }
}

/**
 * 선택 가능한 텍스트 위에서 `ClickableSpan` 탭을 처리한다.
 * DOWN과 UP이 같은 링크 위여야 하고, 롱프레스로 선택이 생겼으면 탭으로 보지 않는다.
 */
private class LinkTouchMovementMethod : ArrowKeyMovementMethod() {
    private var pressedLink: ClickableSpan? = null

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressedLink = linkAt(widget, buffer, event)
            MotionEvent.ACTION_UP -> {
                val pressed = pressedLink
                pressedLink = null
                if (pressed != null && pressed === linkAt(widget, buffer, event) && !widget.hasSelection()) {
                    pressed.onClick(widget)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> pressedLink = null
        }
        return super.onTouchEvent(widget, buffer, event)
    }

    private fun linkAt(widget: TextView, buffer: Spannable, event: MotionEvent): ClickableSpan? {
        val layout = widget.layout ?: return null
        val x = event.x - widget.totalPaddingLeft + widget.scrollX
        val y = event.y - widget.totalPaddingTop + widget.scrollY
        val line = layout.getLineForVertical(y.toInt())
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        return buffer.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }
}
