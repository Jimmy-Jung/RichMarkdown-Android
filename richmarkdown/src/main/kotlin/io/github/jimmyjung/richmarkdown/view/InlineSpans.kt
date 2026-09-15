// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TtsSpan
import android.view.View
import io.github.jimmyjung.richmarkdown.RenderedMath
import io.github.jimmyjung.richmarkdown.RichMarkdownTheme
import io.github.jimmyjung.richmarkdown.core.InlineContent
import io.github.jimmyjung.richmarkdown.core.InlineRun
import io.github.jimmyjung.richmarkdown.core.MathSegment
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 인라인 코드 구간 마커. 아무것도 그리지 않는다 — `ChipTextView`가 이 범위 뒤에 칩을 칠한다
 * (iOS `NSAttributedString.Key.inlineCodeChip` + `InlineCodeDecorationView`).
 */
internal class InlineCodeChipSpan

/**
 * 서체와 굵기·기울임을 한 span에서 정한다. `TypefaceSpan(Typeface)`는 API 28+라 대신 쓰고,
 * `StyleSpan`과 적용 순서가 엇갈려 굵기를 잃는 문제도 피한다 (iOS `styled(_:bold:italic:)`).
 */
internal class TypefaceStyleSpan(private val typeface: Typeface, private val style: Int) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) = apply(paint)
    override fun updateDrawState(paint: TextPaint) = apply(paint)

    private fun apply(paint: TextPaint) {
        val resolved = if (style == Typeface.NORMAL) typeface else Typeface.create(typeface, style)
        paint.typeface = resolved
        // 서체에 없는 변형은 StyleSpan과 같은 규칙으로 합성한다.
        val missing = style and resolved.style.inv()
        if (missing and Typeface.BOLD != 0) paint.isFakeBoldText = true
        if (missing and Typeface.ITALIC != 0) paint.textSkewX = -0.25f
    }
}

/**
 * 인라인 수식 raster attachment (iOS `MathTextAttachment`, 계약 §6).
 *
 * `getSize`가 `ascent = -ceil(ascentPx)`, `descent = ceil(descentPx)`를 보고해 줄 높이를 늘리고,
 * `draw`는 baseline `y`에서 `ascentPx`만큼 위에 bitmap 상단을 놓는다. 색은 raster에 이미 들어 있다.
 */
internal class MathAttachmentSpan(private val rendered: RenderedMath) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            fm.ascent = -ceil(rendered.ascentPx).toInt()
            fm.top = fm.ascent
            fm.descent = ceil(rendered.descentPx).toInt()
            fm.bottom = fm.descent
        }
        return ceil(rendered.widthPx).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        canvas.drawBitmap(rendered.bitmap, x, y - rendered.ascentPx, null)
    }
}

/** 링크. 색·밑줄은 여기서 정하고 탭은 뷰가 준 opener로 보낸다 (iOS `.link` + `linkColor` + underline). */
private class LinkSpan(
    private val uri: Uri,
    private val color: Int,
    private val open: (Uri) -> Unit,
) : ClickableSpan() {
    override fun onClick(widget: View) = open(uri)

    override fun updateDrawState(ds: TextPaint) {
        ds.color = color
        ds.isUnderlineText = true
    }
}

/**
 * `List<InlineRun>` → `SpannableStringBuilder` (iOS `RichMarkdownUIView.attributed(_:images:font:alpha:)`).
 *
 * 폰트 크기·기본 서체·기본 색은 TextView가 들고 있으므로 span은 차이만 싣는다:
 * 굵게/기울임, 취소선, 코드(서체·크기·색·칩 마커), 링크, 수식 attachment, 페이드 alpha.
 */
internal class InlineSpanBuilder(
    theme: RichMarkdownTheme,
    isDark: Boolean,
    codePx: Float,
    private val codeTypeface: Typeface,
    private val openLink: (Uri) -> Unit,
) {
    private val textColor = theme.textColor.resolve(isDark)
    private val linkColor = theme.linkColor.resolve(isDark)
    private val inlineCodeForeground = theme.inlineCodeForeground.resolve(isDark)
    private val codeSizePx = codePx.roundToInt()

    fun build(plan: StreamingDisplay.Plan, images: Map<MathSegment, RenderedMath>): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        for (run in plan.head) append(out, run, images, alpha = 1.0)
        for (piece in plan.tail) {
            append(out, piece.run.copy(content = InlineContent.Text(piece.text)), images, piece.alpha)
        }
        return out
    }

    private fun append(out: SpannableStringBuilder, run: InlineRun, images: Map<MathSegment, RenderedMath>, alpha: Double) {
        val start = out.length
        when (val content = run.content) {
            is InlineContent.Text -> {
                out.append(content.text)
                out.styleText(start, run)
                if (alpha < 1.0) out.span(start, ForegroundColorSpan(StreamingDisplay.withAlpha(textColor, alpha)))
            }

            is InlineContent.Code -> {
                out.append(content.code)
                out.span(start, TypefaceStyleSpan(codeTypeface, styleOf(run)))
                out.span(start, AbsoluteSizeSpan(codeSizePx))
                out.span(start, ForegroundColorSpan(inlineCodeForeground))
                if (run.strikethrough) out.span(start, StrikethroughSpan())
                out.span(start, InlineCodeChipSpan())
            }

            is InlineContent.Math -> {
                val segment = content.segment
                // 선택·복사는 구분자 포함 원문을 준다. 낭독은 TtsSpan이 "수식: latex"로 바꾼다 (iOS spokenText).
                out.append(segment.source)
                val rendered = images[segment]
                if (rendered != null) {
                    out.span(start, MathAttachmentSpan(rendered))
                } else {
                    // 1단계 게시(raster 전) 또는 raster 실패: 원문을 codeFont로 표시 (계약 §7 fail-open).
                    out.span(start, TypefaceStyleSpan(codeTypeface, Typeface.NORMAL))
                    out.span(start, AbsoluteSizeSpan(codeSizePx))
                }
                out.span(start, TtsSpan.TextBuilder("수식: ${segment.latex}").build())
            }

            is InlineContent.Link -> {
                out.append(content.text)
                out.styleText(start, run)
                out.span(start, LinkSpan(Uri.parse(content.destination.toString()), linkColor, openLink))
            }

            InlineContent.HardBreak -> out.append("\n")
            InlineContent.SoftBreak -> out.append(" ")
        }
    }

    private fun SpannableStringBuilder.styleText(start: Int, run: InlineRun) {
        val style = styleOf(run)
        if (style != Typeface.NORMAL) span(start, StyleSpan(style))
        if (run.strikethrough) span(start, StrikethroughSpan())
    }

    private fun styleOf(run: InlineRun): Int = when {
        run.bold && run.italic -> Typeface.BOLD_ITALIC
        run.bold -> Typeface.BOLD
        run.italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }

    /** `[start, length)`에 span을 건다. 빈 범위는 EXCLUSIVE 규칙 위반이라 건너뛴다. */
    private fun SpannableStringBuilder.span(start: Int, what: Any) {
        if (length > start) setSpan(what, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
