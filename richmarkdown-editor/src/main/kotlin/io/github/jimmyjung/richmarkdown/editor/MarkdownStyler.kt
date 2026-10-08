// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.TtsSpan
import android.util.LruCache
import androidx.annotation.ColorInt
import io.github.jimmyjung.richmarkdown.MathRenderKey
import io.github.jimmyjung.richmarkdown.MathRenderService
import io.github.jimmyjung.richmarkdown.RenderedMath
import io.github.jimmyjung.richmarkdown.RichMarkdownFont
import io.github.jimmyjung.richmarkdown.RichMarkdownTheme
import io.github.jimmyjung.richmarkdown.core.DollarMathOptions
import io.github.jimmyjung.richmarkdown.core.InputLimits
import io.github.jimmyjung.richmarkdown.core.ProtectedMathSpan
import io.github.jimmyjung.richmarkdown.core.RichMarkdownParser
import io.github.jimmyjung.richmarkdown.core.Utf16Range
import io.github.jimmyjung.richmarkdown.resolveTypeface
import io.github.jimmyjung.richmarkdown.resolvedTextColor
import io.github.jimmyjung.richmarkdown.textSizePx
import io.github.jimmyjung.richmarkdown.view.InlineCodeChipSpan
import io.github.jimmyjung.richmarkdown.view.MathAttachmentSpan
import io.github.jimmyjung.richmarkdown.view.TypefaceStyleSpan
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** 블록 종류별 문단 정렬. 소비 앱이 주입해 기본값을 바꾼다. iOS `BlockAlignmentConfiguration`. */
data class BlockAlignmentConfiguration(
    /** 코드 블록 정렬. 기본 [Layout.Alignment.ALIGN_NORMAL](iOS `.natural` — RTL 문단은 오른쪽). */
    val code: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    /** 수식 블록 정렬. 기본 가운데. */
    val equation: Layout.Alignment = Layout.Alignment.ALIGN_CENTER,
) {
    companion object {
        val Default: BlockAlignmentConfiguration = BlockAlignmentConfiguration()
    }
}

/**
 * 논리 블록을 연속 문서 하나의 서식 있는 문자열로 투영한다. iOS `MarkdownStyler`.
 *
 * 블록 마커는 모델에 두고 화면에는 span만 입힌다. 글꼴·색은 전부 [RichMarkdownTheme]에서 해석한다:
 * 본문·목록·할 일·인용은 `bodyFont`, 제목은 `headingFont(level)`, 코드·수식·인라인 코드는 `codeFont`.
 *
 * iOS와 다른 점: 결과의 `toString()`은 [BlockEditorModel.documentText]와 **정확히 같다**. iOS는 수식 attachment 한 글자 +
 * U+2063 보충 문자로 길이만 맞추지만, 여기서는 원문 위에 `ReplacementSpan`을 덮어 문자열을 바꾸지 않는다.
 * 그래서 수식 표시/원문 전환이 span 교체만으로 끝나고 IME 상태가 유지된다.
 */
object MarkdownStyler {

    /**
     * @param editingEquationIds 원문으로 보일 수식 블록. 그 밖의 비어 있지 않은 수식 블록은 이미지가 있으면 이미지로 보인다.
     * @param selection 문서 기준 선택. 겹치는 인라인 수식은 원문으로 둔다(caret은 `start <= loc < end`). 문서 밖 선택은 무시한다.
     * @param isDark 색 해석 기준. 기본은 [context]의 `uiMode`.
     * @param contentWidthPx 텍스트 영역 폭. 0보다 크면 넓은 블록 수식을 이 폭(들여쓰기 제외)에 맞춰 줄인다.
     * @param mathImage 수식 raster 조회. 없으면 null — 원문이 그대로 보이고 호출자가 렌더를 요청한다.
     */
    fun styledDocument(
        context: Context,
        blocks: List<EditorBlock>,
        editingEquationIds: Set<UUID> = emptySet(),
        parsesDollarMath: Boolean = false,
        theme: RichMarkdownTheme = RichMarkdownTheme.Default,
        alignment: BlockAlignmentConfiguration = BlockAlignmentConfiguration.Default,
        selection: EditorRange? = null,
        isDark: Boolean = context.isNightMode(),
        contentWidthPx: Int = 0,
        mathImage: (MathRenderKey) -> RenderedMath? = MathRenderService.shared::cachedImage,
    ): SpannableStringBuilder {
        val validSelection = selection?.takeIf { it.isValid(documentLength(blocks)) }
        val style = StyleContext(context, theme, isDark, parsesDollarMath, mathImage)
        val result = SpannableStringBuilder(blocks.joinToString("\n") { it.text })
        val numberedCounts = HashMap<Int, Int>()
        val previousKinds = HashMap<Int, EditorBlockKind>()
        var start = 0
        for ((index, block) in blocks.withIndex()) {
            // 번호는 BlockEditorModel.markdown과 같은 규칙: 같은 깊이의 연속 번호 항목마다 다시 센다.
            val depth = block.indentLevel
            numberedCounts.keys.removeAll { it > depth }
            previousKinds.keys.removeAll { it > depth }
            val ordinal: Int
            if (block.kind == EditorBlockKind.NumberedList) {
                ordinal = if (previousKinds[depth] == EditorBlockKind.NumberedList) (numberedCounts[depth] ?: 0) + 1 else 1
                numberedCounts[depth] = ordinal
            } else {
                ordinal = 1
                numberedCounts[depth] = 0
            }
            previousKinds[depth] = block.kind

            val paragraphEnd = if (index < blocks.lastIndex) start + block.text.length + 1 else start + block.text.length
            val isEditingEquation = block.id in editingEquationIds
            style.applyBlock(result, block, start, paragraphEnd, ordinal, blocks.getOrNull(index + 1), alignment, contentWidthPx, isEditingEquation)
            if (!block.kind.preservesLineBreaks) {
                style.applyInlineMath(result, block, start, validSelection?.let { selectionWithinBlock(it, start, block.text.length) })
            }
            start = paragraphEnd
        }
        return result
    }

    /** 선택과 겹치는 인라인 수식의 문서 범위. 이 범위는 원문으로 보인다. iOS `inlineMathRanges(in:intersecting:parsesDollarMath:)`. */
    fun inlineMathRanges(blocks: List<EditorBlock>, selection: EditorRange, parsesDollarMath: Boolean): List<EditorRange> {
        if (!selection.isValid(documentLength(blocks))) return emptyList()
        val result = mutableListOf<EditorRange>()
        var blockStart = 0
        for ((index, block) in blocks.withIndex()) {
            if (intersects(EditorRange(blockStart, block.text.length), selection)) {
                for (span in inlineMathSpans(block, parsesDollarMath)) {
                    val range = EditorRange(blockStart + span.originalRange.start, span.originalRange.length)
                    if (intersects(range, selection)) result += range
                }
            }
            blockStart += block.text.length
            if (index < blocks.lastIndex) blockStart += 1
        }
        return result
    }

    // MARK: - 내부

    private class ResolvedFont(val typeface: Typeface, val sizePx: Float) {
        val sizeSpanPx: Int = sizePx.roundToInt()
    }

    /** 한 번의 스타일링 동안 테마를 한 번만 해석해 블록마다 재사용한다. */
    private class StyleContext(
        private val context: Context,
        private val theme: RichMarkdownTheme,
        isDark: Boolean,
        private val parsesDollarMath: Boolean,
        private val mathImage: (MathRenderKey) -> RenderedMath?,
    ) {
        private val density = context.resources.displayMetrics.density

        @ColorInt private val textColor = theme.resolvedTextColor(isDark)

        /** iOS `secondaryLabel`(본문 색 60% 투명도에 가깝다)을 본문 색에서 유도한다. 완료 할 일 글자·미완료 체크박스 테두리. */
        @ColorInt private val secondaryColor = (0x99 shl 24) or (textColor and 0xFFFFFF)

        @ColorInt private val inlineCodeForeground = theme.inlineCodeForeground.resolve(isDark)
        private val body = resolve(theme.bodyFont)
        private val code = resolve(theme.codeFont)
        private val headings = HashMap<Int, ResolvedFont>()

        /** iOS 수식 블록 문단 간격에 더하는 `codeFont.ascender`. */
        private val codeAscentPx = -TextPaint().apply {
            typeface = code.typeface
            textSize = code.sizePx
        }.fontMetrics.ascent

        // 체크 완료 박스 채움은 iOS `tintColor`(시스템 파랑)에 해당하는 테마 링크 색을 쓴다.
        private val painter = BlockDecorationPainter(
            density = density,
            markerTypeface = body.typeface,
            markerSizePx = body.sizePx,
            textColor = textColor,
            secondaryColor = secondaryColor,
            accentColor = theme.linkColor.resolve(isDark),
            quoteBarColor = theme.quoteBar.resolve(isDark),
        )

        private fun resolve(font: RichMarkdownFont) = ResolvedFont(font.resolveTypeface(), font.textSizePx(context))

        private fun font(kind: EditorBlockKind): ResolvedFont = when (kind) {
            is EditorBlockKind.Code, EditorBlockKind.Equation -> code
            is EditorBlockKind.Heading -> headings.getOrPut(kind.level) { resolve(theme.headingFont(kind.level)) }
            else -> body
        }

        fun applyBlock(
            out: SpannableStringBuilder,
            block: EditorBlock,
            start: Int,
            paragraphEnd: Int,
            ordinal: Int,
            next: EditorBlock?,
            alignment: BlockAlignmentConfiguration,
            contentWidthPx: Int,
            isEditingEquation: Boolean,
        ) {
            val end = start + block.text.length
            val font = font(block.kind)
            val kind = block.kind
            val isChecked = kind is EditorBlockKind.ToDo && kind.isChecked

            // 기본 글꼴·색은 뒤 구분 개행까지 덮고 시작을 포함(INCLUSIVE)해 블록 앞·끝에서 조합 중인 글자도 같은 모양을 받는다.
            out.characterSpan(TypefaceStyleSpan(font.typeface, Typeface.NORMAL), start, paragraphEnd, BASE_FLAGS)
            out.characterSpan(AbsoluteSizeSpan(font.sizeSpanPx), start, paragraphEnd, BASE_FLAGS)
            out.characterSpan(ForegroundColorSpan(if (isChecked) secondaryColor else textColor), start, paragraphEnd, BASE_FLAGS)
            // Notion처럼 완료 항목은 흐린 색 + 취소선.
            if (isChecked) out.characterSpan(StrikethroughSpan(), start, end, BASE_FLAGS)
            if (!kind.preservesLineBreaks) applyInlineMarks(out, block, start)

            val nesting = block.indentLevel * dp(20)
            val margin = nesting + when (kind) {
                EditorBlockKind.BulletedList, EditorBlockKind.NumberedList, is EditorBlockKind.ToDo -> dp(28)
                EditorBlockKind.Quote -> dp(16)
                is EditorBlockKind.Code, EditorBlockKind.Equation -> dp(12)
                else -> 0
            }
            var paragraphSpacing = dp(if (kind == EditorBlockKind.Paragraph) 8 else 10)
            if (kind == EditorBlockKind.Equation) paragraphSpacing += codeAscentPx.roundToInt()

            if (kind == EditorBlockKind.Equation && !isEditingEquation && block.text.isNotEmpty()) {
                val image = mathImage(MathRenderKey(block.text, code.sizePx, textColor, isDisplay = true))
                if (image != null) {
                    val maxWidth = if (contentWidthPx > 0) max(contentWidthPx - margin, 1) else 0
                    out.setSpan(DisplayMathSpan(image, maxWidth), start, paragraphEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(TtsSpan.TextBuilder("수식: ${block.text}").build(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            val decoration = when (kind) {
                EditorBlockKind.BulletedList -> BlockParagraphSpan.Decoration.Bullet
                EditorBlockKind.NumberedList -> BlockParagraphSpan.Decoration.Number(ordinal)
                is EditorBlockKind.ToDo -> BlockParagraphSpan.Decoration.Checkbox(kind.isChecked)
                EditorBlockKind.Quote -> BlockParagraphSpan.Decoration.QuoteBar(continuesBelow = next?.kind == EditorBlockKind.Quote)
                else -> BlockParagraphSpan.Decoration.None
            }
            val paragraphAlignment = when (kind) {
                is EditorBlockKind.Code -> alignment.code
                EditorBlockKind.Equation -> alignment.equation
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            // ponytail: 빈 마지막 블록(끝 sentinel)은 문단 범위가 0이라 span을 걸 수 없다 — 들여쓰기·장식 없이 EditText 기본 모양을 쓴다.
            if (paragraphEnd > start) {
                out.setSpan(
                    BlockParagraphSpan(decoration, margin, paragraphSpacing, paragraphAlignment, painter),
                    start,
                    paragraphEnd,
                    Spanned.SPAN_PARAGRAPH,
                )
            }
        }

        /** 굵게·기울임은 겹치는 구간마다 서체 스타일 하나로 합치고, 취소선·코드는 각 범위에 건다(iOS `InlineFormat` 순서). */
        private fun applyInlineMarks(out: SpannableStringBuilder, block: EditorBlock, offset: Int) {
            val emphasis = block.inlineMarks.filter { it.format == InlineFormat.Bold || it.format == InlineFormat.Italic }
            val boundaries = emphasis.flatMap { listOf(it.range.location, it.range.end) }.toSortedSet().toList()
            for (i in 0 until boundaries.lastIndex) {
                val segmentStart = boundaries[i]
                val segmentEnd = boundaries[i + 1]
                var styleBits = Typeface.NORMAL
                for (mark in emphasis) {
                    if (mark.range.location <= segmentStart && segmentEnd <= mark.range.end) {
                        styleBits = styleBits or if (mark.format == InlineFormat.Bold) Typeface.BOLD else Typeface.ITALIC
                    }
                }
                if (styleBits != Typeface.NORMAL) {
                    out.characterSpan(TypefaceStyleSpan(font(block.kind).typeface, styleBits), offset + segmentStart, offset + segmentEnd)
                }
            }
            for (mark in block.inlineMarks) {
                val start = offset + mark.range.location
                val end = offset + mark.range.end
                when (mark.format) {
                    InlineFormat.Strikethrough -> out.characterSpan(StrikethroughSpan(), start, end)
                    InlineFormat.Code -> {
                        out.characterSpan(TypefaceStyleSpan(code.typeface, Typeface.NORMAL), start, end)
                        out.characterSpan(AbsoluteSizeSpan(code.sizeSpanPx), start, end)
                        out.characterSpan(ForegroundColorSpan(inlineCodeForeground), start, end)
                        // 칩(둥근 배경 + 테두리)은 span이 아니라 편집 뷰가 이 마커를 읽어 그린다.
                        out.characterSpan(InlineCodeChipSpan(), start, end)
                    }
                    InlineFormat.Bold, InlineFormat.Italic -> Unit
                }
            }
        }

        /** 선택과 겹치지 않고 이미지가 있는 인라인 수식만 원문 전체 범위를 이미지로 덮는다. 크기는 블록 기본 글꼴 크기. */
        fun applyInlineMath(out: SpannableStringBuilder, block: EditorBlock, offset: Int, localSelection: EditorRange?) {
            for (span in inlineMathSpans(block, parsesDollarMath)) {
                val range = EditorRange(span.originalRange.start, span.originalRange.length)
                if (intersects(range, localSelection)) continue
                val image = mathImage(MathRenderKey(span.latex, font(block.kind).sizePx, textColor, isDisplay = false)) ?: continue
                out.setSpan(MathAttachmentSpan(image), offset + range.location, offset + range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(TtsSpan.TextBuilder("수식: ${span.latex}").build(), offset + range.location, offset + range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        private fun dp(value: Int): Int = (value * density).roundToInt()
    }

    private const val BASE_FLAGS = Spanned.SPAN_INCLUSIVE_EXCLUSIVE

    private fun SpannableStringBuilder.characterSpan(span: Any, start: Int, end: Int, flags: Int = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) {
        if (end > start) setSpan(span, start, end, flags)
    }

    private class ScanKey(val text: String, val parsesDollarMath: Boolean, val codeRanges: List<Utf16Range>) {
        override fun equals(other: Any?): Boolean = other is ScanKey && text == other.text &&
            parsesDollarMath == other.parsesDollarMath && codeRanges == other.codeRanges

        override fun hashCode(): Int = (text.hashCode() * 31 + parsesDollarMath.hashCode()) * 31 + codeRanges.hashCode()
    }

    /** 편집마다 문서 전체를 다시 스타일링하므로 블록별 수식 스캔(commonmark 1차 파싱)을 기억한다. main thread 전용. */
    private val scanCache = LruCache<ScanKey, List<ProtectedMathSpan>>(512)

    /** 렌더러와 같은 규칙(`RichMarkdownParser.scanInlineMathSpans`)으로 인라인 수식을 찾는다. 인라인 코드 안은 제외. iOS `LatexInlineMathScanner`. */
    private fun inlineMathSpans(block: EditorBlock, parsesDollarMath: Boolean): List<ProtectedMathSpan> {
        if (block.kind.preservesLineBreaks || block.text.isEmpty()) return emptyList()
        // iOS와 같은 입력 상한. UTF-16 한 단위는 UTF-8 3 byte 이하라 짧은 본문은 인코딩 없이 통과한다.
        if (block.text.length * 3 > InputLimits.MAX_INPUT_UTF8_BYTES &&
            block.text.encodeToByteArray().size > InputLimits.MAX_INPUT_UTF8_BYTES
        ) {
            return emptyList()
        }
        val codeRanges = block.inlineMarks
            .filter { it.format == InlineFormat.Code }
            .map { Utf16Range(it.range.location, it.range.end) }
        val key = ScanKey(block.text, parsesDollarMath, codeRanges)
        scanCache.get(key)?.let { return it }
        val spans = RichMarkdownParser.scanInlineMathSpans(block.text, DollarMathOptions.fromParsesDollarMath(parsesDollarMath), codeRanges)
        scanCache.put(key, spans)
        return spans
    }

    /** 문서 선택을 블록 안 좌표로 옮긴다. caret은 블록 끝 경계를 포함한다. iOS `selectionWithinBlock`. */
    private fun selectionWithinBlock(selection: EditorRange, blockStart: Int, blockLength: Int): EditorRange? {
        val blockEnd = blockStart + blockLength
        if (selection.length == 0) {
            if (selection.location < blockStart || selection.location > blockEnd) return null
            return EditorRange(selection.location - blockStart, 0)
        }
        val start = max(selection.location, blockStart)
        val end = minOf(selection.end, blockEnd)
        return if (end > start) EditorRange(start - blockStart, end - start) else null
    }

    /** caret은 `start <= loc < end`(끝 제외), 범위는 교집합 길이 > 0. iOS `intersects(_:selection:)`. */
    private fun intersects(range: EditorRange, selection: EditorRange?): Boolean {
        if (selection == null) return false
        if (selection.length == 0) return selection.location >= range.location && selection.location < range.end
        return minOf(range.end, selection.end) > max(range.location, selection.location)
    }
}

/** 블록 본문 길이 합 + 구분 개행 수. */
internal fun documentLength(blocks: List<EditorBlock>): Int = blocks.sumOf { it.text.length } + max(blocks.size - 1, 0)

internal fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
