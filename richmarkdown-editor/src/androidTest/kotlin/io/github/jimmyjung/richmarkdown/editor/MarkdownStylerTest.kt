// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import android.content.res.Configuration
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.StrikethroughSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jimmyjung.richmarkdown.MathRenderKey
import io.github.jimmyjung.richmarkdown.RenderedMath
import io.github.jimmyjung.richmarkdown.RichMarkdownTheme
import io.github.jimmyjung.richmarkdown.editor.BlockParagraphSpan.Decoration
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.BulletedList
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Code
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Equation
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Heading
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.NumberedList
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Paragraph
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Quote
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.ToDo
import io.github.jimmyjung.richmarkdown.textSizePx
import io.github.jimmyjung.richmarkdown.view.InlineCodeChipSpan
import io.github.jimmyjung.richmarkdown.view.MathAttachmentSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** iOS `BlockEditorModelTests`의 스타일러 테스트 이식. iOS attribute 검사는 Android span 검사로 바꿨다. */
@RunWith(AndroidJUnit4::class)
class MarkdownStylerTest {

    private fun styled(
        blocks: List<EditorBlock>,
        editingEquationIds: Set<java.util.UUID> = emptySet(),
        parsesDollarMath: Boolean = false,
        theme: RichMarkdownTheme = RichMarkdownTheme.Default,
        alignment: BlockAlignmentConfiguration = BlockAlignmentConfiguration.Default,
        selection: EditorRange? = null,
        mathImage: (MathRenderKey) -> RenderedMath? = { FAKE_MATH },
    ): SpannableStringBuilder = MarkdownStyler.styledDocument(
        targetContext, blocks, editingEquationIds, parsesDollarMath, theme, alignment, selection, mathImage = mathImage,
    )

    @Test
    fun invalidStylerSelectionsAreIgnored() {
        val block = EditorBlock(text = "앞 \\(x\\) 뒤")
        val baseline = styled(listOf(block))
        assertEquals(1, baseline.getSpans(0, baseline.length, MathAttachmentSpan::class.java).size)

        for (selection in OVERFLOW_SELECTIONS) {
            val document = styled(listOf(block), selection = selection)
            assertEquals(baseline.toString(), document.toString())
            assertEquals(1, document.getSpans(0, document.length, MathAttachmentSpan::class.java).size)
            assertTrue(MarkdownStyler.inlineMathRanges(listOf(block), selection, parsesDollarMath = false).isEmpty())
        }
    }

    /** iOS `NSTextList` 검사 → 문단 span의 마커 장식·순번·들여쓰기 검사. */
    @Test
    fun styledDocumentPreservesOffsetsAndUsesTextLists() {
        val bullet = EditorBlock(kind = BulletedList, text = "항목", indentLevel = 1)
        val firstNumber = EditorBlock(kind = NumberedList, text = "첫째")
        val secondNumber = EditorBlock(kind = NumberedList, text = "둘째")
        val unchecked = EditorBlock(kind = ToDo(false), text = "할 일")
        val checked = EditorBlock(kind = ToDo(true), text = "완료")
        val model = BlockEditorModel(
            listOf(
                EditorBlock(kind = Heading(1), text = "제목"),
                bullet, firstNumber, secondNumber, unchecked, checked,
                EditorBlock(kind = Code("swift"), text = "let\nx"),
            ),
        )

        val styled = styled(model.blocks)
        assertEquals(model.documentText, styled.toString())

        fun location(block: EditorBlock) = model.documentRange(block.id)!!.location
        assertEquals(Decoration.Bullet, styled.paragraphAt(location(bullet)).decoration)
        assertEquals(dp(28) + dp(20), styled.paragraphAt(location(bullet)).leadingMarginPx)
        assertEquals(Decoration.Number(1), styled.paragraphAt(location(firstNumber)).decoration)
        assertEquals(Decoration.Number(2), styled.paragraphAt(location(secondNumber)).decoration)
        assertEquals(Decoration.Checkbox(false), styled.paragraphAt(location(unchecked)).decoration)
        assertEquals(Decoration.Checkbox(true), styled.paragraphAt(location(checked)).decoration)
        assertTrue(styled.paragraphAt(location(unchecked)).leadingMarginPx > 0)
        // 완료 항목은 흐린 색 + 취소선, 미완료는 그대로.
        assertTrue(styled.spansAt<StrikethroughSpan>(location(checked)).isNotEmpty())
        assertTrue(styled.spansAt<StrikethroughSpan>(location(unchecked)).isEmpty())
        assertNotEquals(styled.paintAt(location(unchecked)).color, styled.paintAt(location(checked)).color)
    }

    /** 중첩 번호는 깊이마다 따로 세고, 얕은 블록이 끼면 다시 1부터 센다 (model.markdown과 같은 규칙). */
    @Test
    fun nestedNumberedListsShowTheirOwnOrdinals() {
        val blocks = listOf(
            EditorBlock(kind = NumberedList, text = "a"),
            EditorBlock(kind = NumberedList, text = "b", indentLevel = 1),
            EditorBlock(kind = NumberedList, text = "c", indentLevel = 1),
            EditorBlock(kind = NumberedList, text = "d"),
            EditorBlock(kind = Paragraph, text = "e"),
            EditorBlock(kind = NumberedList, text = "f"),
        )
        val styled = styled(blocks)
        val ordinals = blocks.indices.mapNotNull { (styled.paragraphAt(it * 2).decoration as? Decoration.Number)?.ordinal }
        assertEquals(listOf(1, 1, 2, 2, 1), ordinals)
    }

    @Test
    fun semanticInlineMarksDriveAttributes() {
        val block = EditorBlock.fromMarkdown("**굵게** *기울임* ~~취소~~ `코드`")
        val styled = styled(listOf(block))

        assertEquals("굵게 기울임 취소 코드", styled.toString())
        assertTrue(styled.paintAt(0).isBold)
        assertTrue(styled.paintAt(3).isItalic)
        assertTrue(styled.spansAt<StrikethroughSpan>(7).isNotEmpty())
        // 인라인 코드는 칩 마커 + 본문과 다른 강조색을 받는다.
        assertTrue(styled.spansAt<InlineCodeChipSpan>(10).isNotEmpty())
        assertNotEquals(styled.paintAt(0).color, styled.paintAt(10).color)
    }

    @Test
    fun dollarMathRenderingIsOptInAndPreservesOffsets() {
        val block = EditorBlock(text = "\$x^2\$ 그리고 \\(y\\)")
        val parenthesized = block.text.indexOf("\\(y\\)")

        val optOut = styled(listOf(block), parsesDollarMath = false)
        assertEquals(block.text, optOut.toString())
        assertTrue(optOut.spansAt<MathAttachmentSpan>(0).isEmpty())
        assertTrue(optOut.spansAt<MathAttachmentSpan>(parenthesized).isNotEmpty())

        val optIn = styled(listOf(block), parsesDollarMath = true)
        assertEquals(block.text, optIn.toString())
        val dollar = optIn.spansAt<MathAttachmentSpan>(0).single()
        assertEquals(0, optIn.getSpanStart(dollar))
        assertEquals(5, optIn.getSpanEnd(dollar))
        assertTrue(optIn.spansAt<MathAttachmentSpan>(parenthesized).isNotEmpty())

        val editing = styled(listOf(block), parsesDollarMath = true, selection = r(2, 0))
        assertTrue(editing.spansAt<MathAttachmentSpan>(0).isEmpty())
        assertEquals("\$x^2\$", editing.substring(0, 5))
        // caret이 수식 끝(끝 제외 규칙)이면 이미지로 남는다.
        assertTrue(styled(listOf(block), parsesDollarMath = true, selection = r(5, 0)).spansAt<MathAttachmentSpan>(0).isNotEmpty())
    }

    @Test
    fun inlineMathUsesCanonicalScannerAndSurroundingFont() {
        val block = EditorBlock.fromMarkdown("`\$code\$` \\(x\\) \$y\$ \\\$escaped\\\$ \$5 and \$10").copy(kind = Heading(1))
        val requested = mutableListOf<MathRenderKey>()
        val styled = styled(listOf(block), parsesDollarMath = true, theme = TEST_LARGE, mathImage = {
            requested += it
            FAKE_MATH
        })

        fun location(needle: String) = block.text.indexOf(needle).also { assertTrue(needle, it >= 0) }
        assertTrue(styled.spansAt<MathAttachmentSpan>(location("\$code\$")).isEmpty())
        assertTrue(styled.spansAt<MathAttachmentSpan>(location("\\\$escaped\\\$")).isEmpty())
        assertTrue(styled.spansAt<MathAttachmentSpan>(location("\$5 and \$10")).isEmpty())

        val paren = styled.spansAt<MathAttachmentSpan>(location("\\(x\\)")).single()
        assertEquals(location("\\(x\\)"), styled.getSpanStart(paren))
        assertEquals(location("\\(x\\)") + "\\(x\\)".length, styled.getSpanEnd(paren))
        val dollar = styled.spansAt<MathAttachmentSpan>(location("\$y\$")).single()
        assertEquals(location("\$y\$") + 3, styled.getSpanEnd(dollar))

        val parenKey = requested.single { it.latex == "x" }
        assertFalse(parenKey.isDisplay)
        assertEquals(TEST_LARGE.heading1Font.textSizePx(targetContext), parenKey.fontSizePx)
    }

    @Test
    fun themeChangesEditorTypographyAndColor() {
        val block = listOf(EditorBlock(text = "본문"))
        val standard = styled(block)
        val large = styled(block, theme = TEST_LARGE)
        val serif = styled(block, theme = TEST_SERIF)
        val tinted = styled(block, theme = TEST_TINTED)

        assertTrue(large.spansAt<AbsoluteSizeSpan>(0).last().size > standard.spansAt<AbsoluteSizeSpan>(0).last().size)
        assertTrue(serif.paintAt(0).isSerif)
        assertFalse(standard.paintAt(0).isSerif)
        assertNotEquals(standard.paintAt(0).color, tinted.paintAt(0).color)
    }

    @Test
    fun blockAlignmentIsInjectable() {
        val code = EditorBlock(kind = Code("swift"), text = "let x = 1")
        val equation = EditorBlock(kind = Equation, text = "x + y")
        val model = BlockEditorModel(listOf(code, equation))
        fun alignment(document: SpannableStringBuilder, block: EditorBlock): Layout.Alignment =
            document.paragraphAt(model.documentRange(block.id)!!.location).getAlignment()

        val defaults = styled(model.blocks, editingEquationIds = setOf(equation.id))
        assertEquals(Layout.Alignment.ALIGN_NORMAL, alignment(defaults, code))
        assertEquals(Layout.Alignment.ALIGN_CENTER, alignment(defaults, equation))

        val injected = styled(
            model.blocks,
            editingEquationIds = setOf(equation.id),
            alignment = BlockAlignmentConfiguration(code = Layout.Alignment.ALIGN_CENTER, equation = Layout.Alignment.ALIGN_NORMAL),
        )
        assertEquals(Layout.Alignment.ALIGN_CENTER, alignment(injected, code))
        assertEquals(Layout.Alignment.ALIGN_NORMAL, alignment(injected, equation))
    }

    @Test
    fun quoteKeepsBodyColorAndGetsBarAttribute() {
        val styled = styled(listOf(EditorBlock(kind = Quote, text = "인용"), EditorBlock(text = "본문")))
        // "인용\n본문" — 인용 0..<2, 개행 2, 문단 3부터.
        assertEquals("Notion처럼 인용문도 본문 색을 유지한다", styled.paintAt(3).color, styled.paintAt(0).color)
        assertEquals(Decoration.QuoteBar(continuesBelow = false), styled.paragraphAt(0).decoration)
        assertEquals(Decoration.None, styled.paragraphAt(3).decoration)
        val joined = styled(listOf(EditorBlock(kind = Quote, text = "a"), EditorBlock(kind = Quote, text = "b")))
        assertEquals(Decoration.QuoteBar(continuesBelow = true), joined.paragraphAt(0).decoration)
    }

    @Test
    fun codeStaysLiteralAndEquationUsesAttachment() {
        val code = EditorBlock(kind = Code("swift"), text = "let value = \"**literal** `code` \$x\$\"")
        val equation = EditorBlock(kind = Equation, text = "**literal** + \$x\$")

        val styledCode = styled(listOf(code), parsesDollarMath = true)
        assertEquals(code.text, styledCode.toString())
        assertTrue(styledCode.spansAt<AbsoluteSizeSpan>(0).last().size > 1)
        assertFalse(styledCode.paintAt(0).isBold)
        assertTrue(styledCode.getSpans(0, styledCode.length, BackgroundColorSpan::class.java).isEmpty())
        assertTrue(styledCode.getSpans(0, styledCode.length, InlineCodeChipSpan::class.java).isEmpty())
        assertTrue(styledCode.getSpans(0, styledCode.length, MathAttachmentSpan::class.java).isEmpty())

        val styledEquation = styled(listOf(equation))
        assertEquals(equation.text, styledEquation.toString())
        val display = styledEquation.spansAt<DisplayMathSpan>(0).single()
        assertEquals(0, styledEquation.getSpanStart(display))
        assertEquals(equation.text.length, styledEquation.getSpanEnd(display))

        val editingEquation = styled(listOf(equation), editingEquationIds = setOf(equation.id))
        assertEquals(equation.text, editingEquation.toString())
        assertTrue(editingEquation.getSpans(0, editingEquation.length, DisplayMathSpan::class.java).isEmpty())
        // 이미지가 아직 없으면 원문 그대로 둔다(편집 뷰가 raster를 요청한다).
        assertTrue(styled(listOf(equation), mathImage = { null }).getSpans(0, equation.text.length, DisplayMathSpan::class.java).isEmpty())
    }

    /** iOS Dynamic Type → Android 시스템 글꼴 배율(fontScale). */
    @Test
    fun dynamicTypeScalesMonospacedFonts() {
        val code = EditorBlock(kind = Code("swift"), text = "let x = 1")
        val equation = EditorBlock(kind = Equation, text = "x + y")
        val paragraph = EditorBlock.fromMarkdown("`inline`")
        val model = BlockEditorModel(listOf(code, equation, paragraph))
        val scaledContext = targetContext.createConfigurationContext(
            Configuration(targetContext.resources.configuration).apply { fontScale = 2f },
        )
        val normal = MarkdownStyler.styledDocument(targetContext, model.blocks, editingEquationIds = setOf(equation.id))
        val accessibility = MarkdownStyler.styledDocument(scaledContext, model.blocks, editingEquationIds = setOf(equation.id))

        for ((block, offset) in listOf(code to 0, equation to 0, paragraph to 1)) {
            val location = model.documentRange(block.id)!!.location + offset
            assertTrue(
                accessibility.spansAt<AbsoluteSizeSpan>(location).last().size > normal.spansAt<AbsoluteSizeSpan>(location).last().size,
            )
        }
    }

    /** iOS directIndentValuesAreClamped·paragraphIndentUpdatesLayout의 스타일러 부분. */
    @Test
    fun indentLevelsDriveLeadingMargin() {
        for (input in listOf(Int.MIN_VALUE, -1, 0, 3, 4, Int.MAX_VALUE)) {
            val expected = input.coerceIn(0, 3)
            val styled = styled(listOf(EditorBlock(kind = BulletedList, text = "항목", indentLevel = input)))
            assertEquals(dp(28) + expected * dp(20), styled.paragraphAt(0).leadingMarginPx)
        }

        val id = java.util.UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id = id, kind = Paragraph, text = "본문")))
        assertTrue(model.indent(id))
        assertTrue(styled(model.blocks).paragraphAt(0).leadingMarginPx > 0)
        assertTrue(model.outdent(id))
        assertEquals(0, styled(model.blocks).paragraphAt(0).leadingMarginPx)
    }

    /** 표시 문자열 == documentText (iOS U+2063 보충 문자 대신 원문 위 span). 모든 종류·이모지·수식·선택 조합. */
    @Test
    fun styledStringEqualsDocumentTextForVariedDocument() {
        val model = BlockEditorModel(
            listOf(
                EditorBlock(kind = Heading(2), text = "제목 😀 \\(a\\)"),
                EditorBlock.fromMarkdown("**굵게** `코드` \$b\$ 👩‍👩‍👧"),
                EditorBlock(kind = BulletedList, text = "항목 \\(c^2\\)", indentLevel = 2),
                EditorBlock(kind = NumberedList, text = "번호"),
                EditorBlock(kind = ToDo(true), text = "완료 😀"),
                EditorBlock(kind = Quote, text = "인용"),
                EditorBlock(kind = Code(null), text = "a\n\n`b`"),
                EditorBlock(kind = Equation, text = "\\frac{1}{2}\n+ 😀"),
                EditorBlock(kind = Equation, text = ""),
            ),
        )
        val document = model.documentText
        val equationIds = model.blocks.filter { it.kind == Equation }.map { it.id }.toSet()
        for (selection in listOf(null, r(0, 0), r(10, 0), r(3, 20), r(document.length, 0), r(-1, 1))) {
            for (images in listOf<(MathRenderKey) -> RenderedMath?>({ FAKE_MATH }, { null })) {
                for (editing in listOf(emptySet(), equationIds)) {
                    val styled = styled(model.blocks, editing, parsesDollarMath = true, selection = selection, mathImage = images)
                    assertEquals(document, styled.toString())
                }
            }
        }
    }
}
