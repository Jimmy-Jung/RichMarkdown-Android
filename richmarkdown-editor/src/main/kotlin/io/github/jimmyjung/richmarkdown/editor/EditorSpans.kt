// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.ReplacementSpan
import android.text.style.UpdateLayout
import androidx.annotation.ColorInt
import io.github.jimmyjung.richmarkdown.RenderedMath
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - 문단 스타일 + 장식

/**
 * 블록 하나의 문단 스타일과 장식. iOS `NSParagraphStyle`(들여쓰기·간격·정렬·`NSTextList`)과
 * `QuoteBarDecorationView`·`ToDoCheckboxDecorationView`를 span 하나로 옮긴다.
 *
 * - 들여쓰기: 첫 줄과 나머지 줄이 같다(iOS `firstLineHeadIndent == headIndent`).
 * - 간격: 모든 줄 descent에 줄 간격, 문단 마지막 줄에만 문단 간격을 더한다.
 * - 장식: 목록 마커·체크박스는 블록 첫 줄에만, 인용 바는 모든 줄에 그려 연속 인용 블록이 한 줄기로 이어진다.
 *
 * [UpdateLayout]을 구현해 span을 바꿔 끼울 때 `DynamicLayout`이 해당 문단을 다시 배치한다.
 * ponytail: Android에는 trailing indent span이 없어 iOS `tailIndent`(인용 16pt, 코드·수식 12pt)는 적용하지 않는다.
 * ponytail: 문단 간격이 줄 descent에 붙어 있어 문단 마지막 줄의 caret이 그만큼 길다. 거슬리면 간격을 다음 문단 ascent로 옮겨도 같은 문제라 커스텀 caret이 필요하다.
 */
internal class BlockParagraphSpan(
    val decoration: Decoration,
    val leadingMarginPx: Int,
    val paragraphSpacingPx: Int,
    private val alignment: Layout.Alignment,
    private val painter: BlockDecorationPainter,
) : LeadingMarginSpan, LineHeightSpan, AlignmentSpan, UpdateLayout {

    sealed interface Decoration {
        data object None : Decoration
        data object Bullet : Decoration
        data class Number(val ordinal: Int) : Decoration
        data class Checkbox(val isChecked: Boolean) : Decoration

        /** [continuesBelow]: 다음 블록도 인용이면 마지막 줄의 문단 간격까지 바를 이어 그린다. */
        data class QuoteBar(val continuesBelow: Boolean) : Decoration
    }

    private val marker: String? = when (decoration) {
        Decoration.Bullet -> "•"
        is Decoration.Number -> "${decoration.ordinal}."
        else -> null
    }

    override fun getAlignment(): Layout.Alignment = alignment

    override fun getLeadingMargin(first: Boolean): Int = leadingMarginPx

    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        var extra = painter.lineSpacingPx
        if (isLastLine(text, end)) extra += paragraphSpacingPx
        fm.descent += extra
        fm.bottom += extra
    }

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout?,
    ) {
        val spanned = text as? Spanned ?: return
        when (decoration) {
            Decoration.None -> Unit
            is Decoration.QuoteBar -> {
                val trimsSpacing = isLastLine(text, end) && !decoration.continuesBelow
                val barBottom = if (trimsSpacing) bottom - paragraphSpacingPx - painter.lineSpacingPx else bottom
                painter.drawQuoteBar(c, x, dir, top, barBottom)
            }
            is Decoration.Checkbox -> if (start == spanned.getSpanStart(this)) {
                painter.drawCheckbox(c, decoration.isChecked, x, dir, leadingMarginPx, baseline)
            }
            else -> if (start == spanned.getSpanStart(this)) {
                painter.drawMarker(c, marker.orEmpty(), x, dir, leadingMarginPx, baseline)
            }
        }
    }

    private fun isLastLine(text: CharSequence, lineEnd: Int): Boolean = (text as? Spanned)?.getSpanEnd(this) == lineEnd
}

/**
 * 한 번의 스타일링에서 모든 [BlockParagraphSpan]이 공유하는 그리기 도구. 그리기 중 할당을 피하려고 paint·path를 재사용한다
 * (그리기는 main thread 하나에서만 일어난다).
 *
 * 규격: 체크박스 18dp·모서리 4dp·텍스트까지 6dp·테두리 1.5dp·체크 2dp(iOS `ToDoCheckboxMetrics`),
 * 인용 바 폭 3dp·모서리 1.5dp·왼쪽 여백 2dp(iOS `QuoteBarMetrics`).
 */
internal class BlockDecorationPainter(
    density: Float,
    markerTypeface: Typeface,
    markerSizePx: Float,
    @ColorInt textColor: Int,
    @ColorInt secondaryColor: Int,
    @ColorInt accentColor: Int,
    @ColorInt quoteBarColor: Int,
) {
    /** iOS `lineSpacing = 2`. */
    val lineSpacingPx: Int = (2f * density).roundToInt()

    private val checkboxSide = 18f * density
    private val checkboxMinimumSide = 8f * density
    private val checkboxCornerRadius = 4f * density
    private val markerGap = 6f * density
    private val quoteBarOffset = 2f * density
    private val quoteBarWidth = 3f * density
    private val twoDp = 2f * density

    private val markerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = markerTypeface
        textSize = markerSizePx
        color = textColor
    }
    private val markerAscent: Float
    private val markerDescent: Float

    init {
        val metrics = markerPaint.fontMetrics
        markerAscent = metrics.ascent
        markerDescent = metrics.descent
    }

    private val uncheckedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = secondaryColor
    }
    private val checkedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentColor
    }
    private val checkMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val quoteBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = quoteBarColor
    }
    private val rect = RectF()
    private val checkMark = Path()

    /** 목록 마커를 텍스트 시작 [markerGap] 앞에 끝맞춤한다. ponytail: 네 자리 이상 번호는 들여쓰기 영역 왼쪽으로 넘칠 수 있다. */
    fun drawMarker(canvas: Canvas, marker: String, x: Int, dir: Int, leadingMargin: Int, baseline: Int) {
        val textStart = x + dir * leadingMargin
        markerPaint.textAlign = if (dir >= 0) Paint.Align.RIGHT else Paint.Align.LEFT
        canvas.drawText(marker, textStart - dir * markerGap, baseline.toFloat(), markerPaint)
    }

    /** iOS: 한 변 `min(18, max(lineHeight - 2, 8))`, 첫 줄 글자 높이의 가운데, 텍스트 시작 6pt 앞. */
    fun drawCheckbox(canvas: Canvas, isChecked: Boolean, x: Int, dir: Int, leadingMargin: Int, baseline: Int) {
        val side = min(checkboxSide, max(markerDescent - markerAscent - twoDp, checkboxMinimumSide))
        val centerY = baseline + (markerAscent + markerDescent) / 2f
        val textStart = x + dir * leadingMargin
        val near = textStart - dir * markerGap
        val far = near - dir * side
        rect.set(min(near, far), centerY - side / 2f, max(near, far), centerY + side / 2f)
        val radius = min(checkboxCornerRadius, side / 2f)
        if (!isChecked) {
            canvas.drawRoundRect(rect, radius, radius, uncheckedPaint)
            return
        }
        canvas.drawRoundRect(rect, radius, radius, checkedPaint)
        checkMark.reset()
        checkMark.moveTo(rect.left + rect.width() * 0.26f, rect.top + rect.height() * 0.53f)
        checkMark.lineTo(rect.left + rect.width() * 0.43f, rect.top + rect.height() * 0.70f)
        checkMark.lineTo(rect.left + rect.width() * 0.75f, rect.top + rect.height() * 0.32f)
        canvas.drawPath(checkMark, checkMarkPaint)
    }

    /** iOS처럼 들여쓰기와 무관하게 텍스트 영역 앞끝에서 2pt 안쪽에 그린다. */
    fun drawQuoteBar(canvas: Canvas, x: Int, dir: Int, top: Int, bottom: Int) {
        if (bottom <= top) return
        val near = x + dir * quoteBarOffset
        val far = near + dir * quoteBarWidth
        rect.set(min(near, far), top.toFloat(), max(near, far), bottom.toFloat())
        val radius = quoteBarWidth / 2f
        canvas.drawRoundRect(rect, radius, radius, quoteBarPaint)
    }
}

// MARK: - 블록 수식

/**
 * 블록 수식 이미지. iOS `EquationTextAttachment(isDisplay: true)` 대응으로 수식 원문 전체(+ 뒤 구분 개행)를 덮는다.
 *
 * 원문을 바꾸지 않으므로 표시 문자열이 문서 문자열과 같다(iOS U+2063 보충 문자 불필요). 원문에 개행이 있으면
 * StaticLayout이 줄을 나누므로 첫 조각만 이미지 크기를 보고하고 그리며, 나머지 조각은 폭·높이 0이다.
 * 이미지가 [maxWidthPx]보다 넓으면 비율을 유지해 줄인다(0이면 제한 없음). 정렬은 문단의 `AlignmentSpan`이 맡는다.
 *
 * ponytail: raster(`MathRenderService`)로 그린다. 확대·색 변경마다 다시 raster한다 — 선명도·메모리가 문제되면
 * 뷰어 블록 수식처럼 벡터 `MathVectorLayout`으로 바꾼다.
 */
internal class DisplayMathSpan(private val rendered: RenderedMath, maxWidthPx: Int) : ReplacementSpan() {
    private val scale: Float = if (maxWidthPx > 0 && rendered.widthPx > maxWidthPx) maxWidthPx / rendered.widthPx else 1f
    val widthPx: Int = ceil(rendered.widthPx * scale).toInt().coerceAtLeast(1)
    val ascentPx: Int = ceil(rendered.ascentPx * scale).toInt()
    val descentPx: Int = ceil(rendered.descentPx * scale).toInt()
    private val destination = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val first = isFirstPiece(text, start)
        if (fm != null) {
            fm.ascent = if (first) -ascentPx else 0
            fm.top = fm.ascent
            fm.descent = if (first) descentPx else 0
            fm.bottom = fm.descent
        }
        return if (first) widthPx else 0
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        if (!isFirstPiece(text, start)) return
        destination.set(x, y - rendered.ascentPx * scale, x + rendered.widthPx * scale, y + rendered.descentPx * scale)
        canvas.drawBitmap(rendered.bitmap, null, destination, bitmapPaint)
    }

    private fun isFirstPiece(text: CharSequence, start: Int): Boolean {
        val spanStart = (text as? Spanned)?.getSpanStart(this) ?: return true
        return spanStart < 0 || spanStart == start
    }
}
