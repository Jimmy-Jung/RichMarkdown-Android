// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.view

import android.content.Context
import android.graphics.Canvas
import android.text.Spannable
import android.text.method.ArrowKeyMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.widget.TextView
import kotlinx.coroutines.Job

/**
 * 문단·헤딩·표 셀·코드 본문·원문 fallback이 공유하는 텍스트 뷰 (iOS `RichMarkdownTextView`).
 *
 * `InlineCodeChipSpan` 범위 뒤에 둥근 칩을 **그린다** — span으로 배경을 칠하지 않으므로
 * 텍스트 선택이 그대로 동작한다 (iOS `InlineCodeDecorationView` 기하 이식).
 */
internal class ChipTextView(context: Context) : TextView(context) {
    private val chipPainter = InlineCodeChipPainter(context.resources.displayMetrics.density)
    private var chipFill = 0
    private var chipBorder = 0

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
        chipFill = fill
        chipBorder = border
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        chipPainter.draw(canvas, this, chipFill, chipBorder)
        super.onDraw(canvas)
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
