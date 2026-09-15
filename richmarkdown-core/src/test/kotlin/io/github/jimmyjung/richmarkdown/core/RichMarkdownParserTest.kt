// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** README 「렌더 계약」 smoke test. 2-pass 파이프라인이 commonmark-java 위에서 iOS와 같은 결정을 내리는지 본다. */
class RichMarkdownParserTest {

    private fun paragraphRuns(markdown: String, dollar: DollarMathOptions = DollarMathOptions.None): List<InlineRun> {
        val block = RichMarkdownParser.parse(markdown, dollar).blocks.single()
        return assertIs<ParsedBlock.Paragraph>(block).runs
    }

    private fun List<InlineRun>.mathSegments(): List<MathSegment> =
        mapNotNull { (it.content as? InlineContent.Math)?.segment }

    @Test
    fun inlineParenBecomesMathRun() {
        val runs = paragraphRuns("원의 넓이는 \\( A = \\pi r^2 \\)입니다.")
        val math = runs.mathSegments().single()
        assertEquals("\\( A = \\pi r^2 \\)", math.source)
        assertEquals("A = \\pi r^2", math.latex)
        assertEquals(MathKind.InlineParen, math.kind)
        assertEquals(InlineContent.Text("원의 넓이는 "), runs.first().content)
        assertEquals(InlineContent.Text("입니다."), runs.last().content)
    }

    @Test
    fun paragraphWideBracketBecomesBlockMath() {
        val block = RichMarkdownParser.parse("\\[\n\\int_0^1 x^2 \\, dx\n\\]").blocks.single()
        val math = assertIs<ParsedBlock.BlockMath>(block).segment
        assertEquals(MathKind.DisplayBracket, math.kind)
        assertEquals("\\int_0^1 x^2 \\, dx", math.latex)
    }

    @Test
    fun dollarPricesStayText() {
        val runs = paragraphRuns("\$5 and \$10", DollarMathOptions.Single)
        assertTrue(runs.mathSegments().isEmpty())
        assertEquals("\$5 and \$10", runs.joinToString("") { (it.content as InlineContent.Text).text })
    }

    @Test
    fun dollarMathIsOptIn() {
        assertTrue(paragraphRuns("\$x\$").mathSegments().isEmpty())
        assertEquals("x", paragraphRuns("\$x\$", DollarMathOptions.Single).mathSegments().single().latex)
    }

    @Test
    fun inlineCodeIsHardBarrier() {
        val runs = paragraphRuns("`\\(x\\)`")
        assertEquals(InlineContent.Code("\\(x\\)"), runs.single().content)
    }

    @Test
    fun mathInsideLinkStaysLink() {
        val runs = paragraphRuns("[\\(x\\)](https://a.b)")
        assertTrue(runs.mathSegments().isEmpty())
        val link = assertIs<InlineContent.Link>(runs.single().content)
        assertEquals("https://a.b", link.destination.toString())
    }

    @Test
    fun linkInsideMathIsMath() {
        val math = paragraphRuns("\\([a](b)\\)").mathSegments().single()
        assertEquals("[a](b)", math.latex)
    }

    @Test
    fun disallowedSchemeBecomesText() {
        val runs = paragraphRuns("[x](javascript:alert(1)) [y](/relative) [z](mailto:a@b.c)")
        assertFalse(runs.any { it.content.let { c -> c is InlineContent.Link && c.text == "x" } })
        assertFalse(runs.any { it.content.let { c -> c is InlineContent.Link && c.text == "y" } })
        val mail = runs.mapNotNull { it.content as? InlineContent.Link }.single()
        assertEquals("mailto", mail.destination.scheme)
    }

    @Test
    fun gfmTableParsesAlignments() {
        val block = RichMarkdownParser.parse("| a | b | c |\n|:--|:-:|--:|\n| 1 | **2** | \\(x\\) |").blocks.single()
        val table = assertIs<ParsedBlock.Table>(block).table
        assertEquals(
            listOf(ParsedTable.ColumnAlignment.Left, ParsedTable.ColumnAlignment.Center, ParsedTable.ColumnAlignment.Right),
            table.columnAlignments,
        )
        assertEquals(3, table.header.size)
        assertEquals(1, table.rows.size)
        assertTrue(table.rows[0][1].single().bold)
        assertEquals("x", table.rows[0][2].mathSegments().single().latex)
    }

    @Test
    fun htmlIsShownLiterally() {
        val block = RichMarkdownParser.parse("<div>hi</div>").blocks.single()
        assertEquals(InlineContent.Text("<div>hi</div>"), assertIs<ParsedBlock.Paragraph>(block).runs.single().content)
        assertEquals(InlineContent.Text("<b>"), paragraphRuns("a <b> b").first { it.content.let { c -> c is InlineContent.Text && c.text == "<b>" } }.content)
    }

    @Test
    fun headingLevelAndStyles() {
        val heading = assertIs<ParsedBlock.Heading>(RichMarkdownParser.parse("## 제목 **굵게** ~~취소~~").blocks.single())
        assertEquals(2, heading.level)
        assertTrue(heading.runs.any { it.bold && it.content == InlineContent.Text("굵게") })
        assertTrue(heading.runs.any { it.strikethrough && it.content == InlineContent.Text("취소") })
    }

    @Test
    fun escapesAreUnescapedButMathDelimitersKept() {
        assertEquals(InlineContent.Text("*별표*"), paragraphRuns("\\*별표\\*").single().content)
        // 미완성 `\(`는 fail-open — 원래 구분자를 포함한 원문을 보인다.
        val runs = paragraphRuns("\\(x + y")
        assertTrue(runs.mathSegments().isEmpty())
        assertEquals("\\(x + y", runs.joinToString("") { (it.content as InlineContent.Text).text })
    }

    @Test
    fun entityNextToMathKeepsDecoding() {
        val runs = paragraphRuns("&amp; \\(x\\) &lt;")
        assertEquals("x", runs.mathSegments().single().latex)
        // commonmark가 인접 Text를 병합하든 않든 수식 앞뒤 텍스트는 entity가 해제된 상태여야 한다.
        val mathIndex = runs.indexOfFirst { it.content is InlineContent.Math }
        fun text(range: IntRange) = range.joinToString("") { (runs[it].content as InlineContent.Text).text }
        assertEquals("& ", text(0 until mathIndex))
        assertEquals(" <", text(mathIndex + 1 until runs.size))
    }

    @Test
    fun codeBlockLanguageAndLiteral() {
        val block = RichMarkdownParser.parse("```kotlin\nval x = 1\n```").blocks.single()
        val code = assertIs<ParsedBlock.CodeBlock>(block)
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
    }

    @Test
    fun inputLimitsBoundOversizedAndDeepQuotes() {
        assertFalse(InputLimits.bound("작은 입력").wasTruncated)

        val huge = "가".repeat(InputLimits.MAX_INPUT_UTF8_BYTES) // 3 byte × N > 상한
        val bounded = InputLimits.bound(huge)
        assertTrue(bounded.wasTruncated)
        assertTrue(bounded.text.endsWith(InputLimits.TRUNCATION_MARKER))
        assertTrue(bounded.text.toByteArray(Charsets.UTF_8).size <= InputLimits.DISPLAY_PREFIX_UTF8_BYTES + 64)

        val deep = ">".repeat(InputLimits.MAX_BLOCK_QUOTE_DEPTH + 1) + " 깊다"
        assertTrue(InputLimits.bound("정상\n$deep").wasTruncated)
        assertFalse(InputLimits.bound("```\n$deep\n```").wasTruncated, "fenced code 안의 `>`는 quote가 아니다")
        assertNull(RichMarkdownParser.parse("정상\n$deep").blocks.firstOrNull { it is ParsedBlock.BlockQuote })
    }
}
