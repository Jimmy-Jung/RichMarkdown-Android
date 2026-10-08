// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Equation
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Heading
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 블록 Markdown 직렬화·파싱과 [InlineMarkdownCodec] 왕복 계약. iOS `Tests/RichMarkdownBlockEditorTests/BlockEditorModelTests.swift`
 * 중 Markdown·코덱 테스트를 같은 이름으로 이식했다. 매개변수화 테스트는 한 메서드 안에서 입력을 순회한다.
 */
class BlockMarkdownCodecTest {

    // MARK: - 인라인 마크 정규화

    /** iOS: 범위를 넘거나 합계가 overflow하는 인라인 마크는 원문을 바꾸지 않고 무시한다. */
    @Test
    fun invalidInlineMarkRangesAreIgnored() {
        for (range in OVERFLOW_RANGES) {
            val valid = InlineMark(InlineFormat.Bold, r(0, 1))
            val invalid = InlineMark(InlineFormat.Code, range)
            val initialized = EditorBlock(text = "본문", inlineMarks = listOf(invalid, valid))
            val assigned = EditorBlock(text = "본문").copy(inlineMarks = listOf(invalid, valid))

            assertEquals("$range", listOf(valid), InlineMarkdownCodec.normalized(listOf(invalid, valid), "본문"))
            assertEquals("$range", listOf(valid), initialized.inlineMarks)
            assertEquals("$range", listOf(valid), assigned.inlineMarks)
            assertEquals("본문", assigned.text)
            assertEquals("**본**문", assigned.markdown)
        }
    }

    /** iOS: 인라인 코드는 겹친 일반 서식보다 우선한다. */
    @Test
    fun inlineCodeClipsOverlappingMarks() {
        val block = EditorBlock(
            text = "abcd",
            inlineMarks = listOf(InlineMark(InlineFormat.Bold, r(0, 4)), InlineMark(InlineFormat.Code, r(1, 2))),
        )

        assertEquals(
            listOf(
                InlineMark(InlineFormat.Bold, r(0, 1)),
                InlineMark(InlineFormat.Code, r(1, 2)),
                InlineMark(InlineFormat.Bold, r(3, 1)),
            ),
            block.inlineMarks,
        )
        assertEquals(block.inlineMarks, EditorBlock.fromMarkdown(block.markdown).inlineMarks)
    }

    // MARK: - 블록 마커

    /** iOS: 구조 마커로 시작하는 문단은 인라인 서식과 원문을 Markdown 왕복에서 보존한다. */
    @Test
    fun literalParagraphMarkersRoundTrip() {
        val sources = listOf(
            "# 리터럴 제목", "### 리터럴 제목", "###### 리터럴 제목",
            "- 리터럴 목록", "+ 리터럴 목록", "* 리터럴 목록",
            "1. 리터럴 번호", "2026. 리터럴 번호", "> 리터럴 인용",
            "  - 들여쓴 리터럴", "\\# 리터럴 escape",
        )
        for (source in sources) {
            val original = EditorBlock(
                text = source,
                inlineMarks = listOf(InlineMark(InlineFormat.Bold, r(source.indexOf("리터럴"), 3))),
            )
            val reparsed = EditorBlock.fromMarkdown(original.markdown)

            assertEquals(source, Paragraph, reparsed.kind)
            assertEquals(source, original.text, reparsed.text)
            assertEquals(source, original.inlineMarks, reparsed.inlineMarks)
        }
    }

    /** iOS: 블록 수식처럼 생긴 문단도 Markdown 왕복에서 원문과 인라인 서식을 보존한다. */
    @Test
    fun literalEquationParagraphRoundTrip() {
        val original = EditorBlock(text = "\\[리터럴\\]", inlineMarks = listOf(InlineMark(InlineFormat.Bold, r(2, 3))))
        val reparsed = EditorBlock.fromMarkdown(original.markdown)
        val model = BlockEditorModel(listOf(original))
        val document = BlockEditorModel(model.markdown)

        assertEquals(Paragraph, reparsed.kind)
        assertEquals(original.text, reparsed.text)
        assertEquals(original.inlineMarks, reparsed.inlineMarks)
        assertEquals(model.kinds, document.kinds)
        assertEquals(model.texts, document.texts)
        assertEquals(model.blocks.map { it.inlineMarks }, document.blocks.map { it.inlineMarks })
    }

    /** iOS: 모호한 문단과 내부 fence가 있는 코드는 문서 Markdown 왕복에서 경계를 보존한다. */
    @Test
    fun ambiguousDocumentMarkdownRoundTrip() {
        val model = BlockEditorModel(listOf(
            EditorBlock(text = "# 제목 모양", inlineMarks = listOf(InlineMark(InlineFormat.Bold, r(2, 2)))),
            EditorBlock(text = "- 목록 모양"),
            EditorBlock(text = "1. 번호 모양"),
            EditorBlock(kind = EditorBlockKind.Code("swift"), text = "첫 줄\n```\n````\n마지막"),
            EditorBlock(text = "뒤 문단"),
        ))
        val reparsed = BlockEditorModel(model.markdown)

        assertEquals(model.kinds, reparsed.kinds)
        assertEquals(model.texts, reparsed.texts)
        assertEquals(model.blocks.map { it.inlineMarks }, reparsed.blocks.map { it.inlineMarks })
    }

    /** iOS: 블록 종류는 Markdown 마커와 본문을 분리한다. */
    @Test
    fun parseAndRenderTypedBlocks() {
        val heading = EditorBlock.fromMarkdown("## 제목")
        val code = EditorBlock.fromMarkdown("```swift\nlet answer = 42\n```")
        val equation = EditorBlock.fromMarkdown("\\[x^2 + y^2\\]")

        assertEquals(Heading(2), heading.kind)
        assertEquals("제목", heading.text)
        assertEquals("## 제목", heading.markdown)
        assertEquals(EditorBlockKind.Code("swift"), code.kind)
        assertEquals("let answer = 42", code.text)
        assertEquals("```swift\nlet answer = 42\n```", code.markdown)
        assertEquals(Equation, equation.kind)
        assertEquals("x^2 + y^2", equation.text)
        assertEquals("\\[x^2 + y^2\\]", equation.markdown)
    }

    /** iOS: 수식 블록은 구분자 안의 source를 손실 없이 보존한다. */
    @Test
    fun preservesEquationBlockSource() {
        val source = "\\[\n  x^2 + y^2  \n\\]"

        val block = EditorBlock.fromMarkdown(source)

        assertEquals(Equation, block.kind)
        assertEquals("\n  x^2 + y^2  \n", block.text)
        assertEquals(source, block.markdown)
    }

    // MARK: - 코드 fence

    /** iOS: 코드 fence는 본문 안의 backtick보다 길게 출력해 원문을 보존한다. */
    @Test
    fun codeFenceContentsRoundTrip() {
        val sources = listOf(
            "첫 줄\n```\n마지막",
            "````\n# 리터럴\n````",
            "let value = \"`````\"\n```suffix\n  ```\n마지막",
            "``````",
            "",
        )
        for (source in sources) {
            val original = EditorBlock(kind = EditorBlockKind.Code("swift"), text = source)
            val reparsed = EditorBlock.fromMarkdown(original.markdown)
            val model = BlockEditorModel(original.markdown)

            assertEquals(source, original.kind, reparsed.kind)
            assertEquals(source, original.text, reparsed.text)
            assertTrue(source, reparsed.inlineMarks.isEmpty())
            assertEquals(source, listOf(original.kind, Paragraph), model.kinds)
            assertEquals(source, listOf(source, ""), model.texts)
        }
    }

    /** iOS: 짧은 fence와 suffix가 있는 줄은 코드를 종료하지 않고 충분히 긴 fence만 종료한다. */
    @Test
    fun codeFenceClosingRequiresLengthAndEmptySuffix() {
        val model = BlockEditorModel("````swift\n첫 줄\n```\n````suffix\n마지막\n`````\n# 뒤 제목")

        assertEquals(listOf(EditorBlockKind.Code("swift"), Heading(1), Paragraph), model.kinds)
        assertEquals(listOf("첫 줄\n```\n````suffix\n마지막", "뒤 제목", ""), model.texts)
    }

    // MARK: - 인라인 Markdown

    /** iOS: Markdown 인라인 마커는 plain text와 의미 범위로 파싱된다. */
    @Test
    fun parsesMarkdownInlineMarkersIntoSemanticRanges() {
        val source = "**굵게** *기울임* ~~취소~~ `코드`"

        val block = EditorBlock.fromMarkdown(source)
        val model = BlockEditorModel(source)

        assertEquals(Paragraph, block.kind)
        assertEquals("굵게 기울임 취소 코드", block.text)
        assertFalse(block.text.contains("**"))
        assertEquals(
            listOf(InlineFormat.Bold, InlineFormat.Italic, InlineFormat.Strikethrough, InlineFormat.Code),
            block.inlineMarks.map { it.format },
        )
        assertEquals(listOf(r(0, 2), r(3, 3), r(7, 2), r(10, 2)), block.inlineMarks.map { it.range })
        assertEquals("굵게 기울임 취소 코드\n", model.documentText)
    }

    /** iOS: 인라인 마크 범위는 Character가 아닌 UTF-16 offset을 사용한다. */
    @Test
    fun storesInlineMarkRangesAsUTF16Offsets() {
        val block = EditorBlock.fromMarkdown("😀 **굵게**")

        assertEquals("😀 굵게", block.text)
        assertEquals(listOf(r(3, 2)), block.inlineMarks.map { it.range })
    }

    /** iOS: 의미 인라인 마크는 canonical Markdown으로 재직렬화된다. */
    @Test
    fun reserializesSemanticInlineMarksToMarkdown() {
        val block = EditorBlock.fromMarkdown("**굵게** *기울임* ~~취소~~ `코드`")

        assertEquals("**굵게** <em>기울임</em> ~~취소~~ `코드`", block.markdown)
    }

    /** iOS: 교차 인라인 마크도 Markdown 왕복 시 의미 범위를 보존한다. */
    @Test
    fun crossingInlineMarksRoundTrip() {
        val original = EditorBlock(
            text = "abcd",
            inlineMarks = listOf(InlineMark(InlineFormat.Bold, r(0, 3)), InlineMark(InlineFormat.Italic, r(1, 3))),
        )

        val reparsed = EditorBlock.fromMarkdown(original.markdown)

        assertEquals(original.text, reparsed.text)
        assertEquals(original.inlineMarks, reparsed.inlineMarks)
    }

    /** iOS: plain Markdown delimiter와 인라인 LaTeX underscore를 리터럴로 보존한다. */
    @Test
    fun literalDelimitersRoundTrip() {
        val plain = EditorBlock(text = "a_b_c **literal** ~~literal~~ `literal` <em>literal</em>")
        val plainRoundTrip = EditorBlock.fromMarkdown(plain.markdown)
        assertEquals(plain.text, plainRoundTrip.text)
        assertTrue(plainRoundTrip.inlineMarks.isEmpty())

        val latex = "앞 \$x_{i} + y_{j}\$, \$a*b + c*d\$ 뒤 \\(a_b * c_d\\)"
        val latexBlock = EditorBlock.fromMarkdown(latex)
        assertEquals(latex, latexBlock.text)
        assertTrue(latexBlock.inlineMarks.isEmpty())
        assertEquals(latex, EditorBlock.fromMarkdown(latexBlock.markdown).text)

        val markedLatex = EditorBlock(text = "앞 \$x_i\$ 뒤", inlineMarks = listOf(InlineMark(InlineFormat.Bold, r(3, 3))))
        assertTrue(markedLatex.inlineMarks.isEmpty())
        assertEquals(markedLatex.text, EditorBlock.fromMarkdown(markedLatex.markdown).text)
    }

    /** iOS: 인라인 코드 안의 backtick을 escape해 왕복한다. */
    @Test
    fun inlineCodeBacktickRoundTrip() {
        val block = EditorBlock(text = "a`b", inlineMarks = listOf(InlineMark(InlineFormat.Code, r(0, 3))))
        val reparsed = EditorBlock.fromMarkdown(block.markdown)
        assertEquals(block.text, reparsed.text)
        assertEquals(block.inlineMarks, reparsed.inlineMarks)

        val literalLatexCode = EditorBlock.fromMarkdown("`\$x_i\$`")
        assertEquals("\$x_i\$", literalLatexCode.text)
        assertEquals(listOf(InlineMark(InlineFormat.Code, r(0, 5))), literalLatexCode.inlineMarks)
        val reparsedLatexCode = EditorBlock.fromMarkdown(literalLatexCode.markdown)
        assertEquals(literalLatexCode.kind, reparsedLatexCode.kind)
        assertEquals(literalLatexCode.text, reparsedLatexCode.text)
        assertEquals(literalLatexCode.inlineMarks, reparsedLatexCode.inlineMarks)
    }

    /** iOS: 인라인 LaTeX는 수식 노드가 아닌 일반 텍스트로 보존된다. */
    @Test
    fun keepsInlineLatexAsPlainText() {
        val source = "앞 \$x^2\$ 뒤 \\(\\alpha + \\beta\\)"

        val block = EditorBlock.fromMarkdown(source)

        assertEquals(Paragraph, block.kind)
        assertEquals(source, block.text)
        assertTrue(block.inlineMarks.isEmpty())
        assertEquals(source, block.markdown)
    }
}
