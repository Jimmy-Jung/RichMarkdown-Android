// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * iOS `MathScannerFixtureTests`의 delimiter 규칙을 스캐너 직접 호출로 고정한다.
 * iOS 픽스처는 전부 `RichMarkdownParser`를 거치므로 1차 파싱 범위(hard/soft/paragraph)를
 * 여기서는 손으로 준다. 파서 경유 픽스처는 `DocumentBuilder` 테스트가 담당한다.
 */
class MathScannerFixtureTest {

    // MARK: - Helpers

    private fun scan(
        text: String,
        dollarMath: DollarMathOptions = DollarMathOptions.None,
        hard: List<Utf16Range> = emptyList(),
        soft: List<Utf16Range> = emptyList(),
        paragraphs: List<Utf16Range> = listOf(Utf16Range(0, text.length)),
    ): MathScanner.Result = MathScanner(
        text = text,
        forbiddenRanges = hard,
        softRanges = soft,
        paragraphRanges = paragraphs,
        dollarMath = dollarMath,
    ).scan()

    /** `\n\n`으로 나눈 단순 paragraph 범위. 픽스처 문자열에만 쓴다. */
    private fun paragraphsOf(text: String): List<Utf16Range> {
        val result = ArrayList<Utf16Range>()
        var start = 0
        while (start < text.length) {
            val sep = text.indexOf("\n\n", start)
            val end = if (sep < 0) text.length else sep
            if (end > start) result.add(Utf16Range(start, end))
            if (sep < 0) break
            start = sep + 2
        }
        return result
    }

    private fun rangeOf(text: String, needle: String): Utf16Range {
        val index = text.indexOf(needle)
        require(index >= 0) { "픽스처 오류: '$needle' 없음" }
        return Utf16Range(index, index + needle.length)
    }

    private fun MathScanner.Result.kinds(): List<MathKind> = spans.map { it.kind }
    private fun MathScanner.Result.latex(): List<String> = spans.map { it.latex }
    private fun MathScanner.Result.has(kind: MathDiagnostic.Kind): Boolean = diagnostics.any { it.kind == kind }

    private val single = DollarMathOptions.Single
    private val singleAndInlineDouble = DollarMathOptions.Single + DollarMathOptions.InlineDouble

    // MARK: - 기본 delimiter

    @Test
    fun inlineParenMath() {
        val text = """원의 넓이는 \( A = \pi r^2 \)입니다."""
        val result = scan(text)
        assertEquals(1, result.spans.size)
        val span = result.spans.first()
        assertEquals(MathKind.InlineParen, span.kind)
        assertEquals("""\( A = \pi r^2 \)""", span.source)
        assertEquals("""A = \pi r^2""", span.latex)
        assertEquals(rangeOf(text, """\( A = \pi r^2 \)"""), span.originalRange)
    }

    @Test
    fun displayBracketWholeParagraphIsBlockMath() {
        val text = "본문\n\n\\[ E = mc^2 \\]\n\n다음"
        val result = scan(text, paragraphs = paragraphsOf(text))
        assertEquals(listOf(MathKind.DisplayBracket), result.kinds())
        assertEquals(listOf("E = mc^2"), result.latex())
        assertEquals(rangeOf(text, "\\[ E = mc^2 \\]"), result.spans.single().originalRange)
    }

    @Test
    fun midParagraphDisplayBracketStaysPlainText() {
        val result = scan("""이건 \[x+y\] 인라인 위치다.""")
        assertTrue(result.spans.isEmpty())
        assertTrue(result.has(MathDiagnostic.Kind.NonParagraphDisplayDelimiter))
    }

    // MARK: - inline $$ ... $$ (opt-in InlineDouble)

    @Test
    fun inlineDoubleDollarStaysPlainWithoutOptIn() {
        val result = scan("총합(\$\$f(1)\$\$) 및 상수항(\$\$f(0)\$\$)", dollarMath = single)
        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun inlineDoubleDollarParsesMidParagraphWhenOptedIn() {
        val result = scan("총합(\$\$f(1)\$\$) 및 상수항(\$\$f(0)\$\$)을 구한다.", dollarMath = singleAndInlineDouble)
        assertEquals(listOf(MathKind.InlineDoubleDollar, MathKind.InlineDoubleDollar), result.kinds())
        assertEquals(listOf("f(1)", "f(0)"), result.latex())
        assertTrue(result.spans.none { it.kind.isDisplay })
    }

    @Test
    fun inlineDoubleDollarWorksWithoutSingleDollar() {
        val result = scan("가격은 \$5, 식은 \$\$x^2\$\$", dollarMath = DollarMathOptions.InlineDouble)
        assertEquals(listOf("x^2"), result.latex())
    }

    @Test
    fun inlineDoubleDollarFollowsSpacingDigitAndLineRules() {
        val options = singleAndInlineDouble
        assertTrue(scan("\$\$5 and \$\$6", dollarMath = options).spans.isEmpty())
        assertTrue(scan("값 \$\$ x \$\$ 값", dollarMath = options).spans.isEmpty())
        assertTrue(scan("값 \$\$x\$\$5", dollarMath = options).spans.isEmpty())
        assertTrue(scan("열림 \$\$a\nb\$\$ 닫힘", dollarMath = options).spans.isEmpty())
        // 원문: 이스케이프 \$$x$$
        assertTrue(scan("이스케이프 \\\$\$x\$\$", dollarMath = options).spans.isEmpty())
    }

    @Test
    fun paragraphWideDoubleDollarStaysDisplayWithInlineDoubleEnabled() {
        val text = "본문\n\n\$\$ E = mc^2 \$\$\n\n다음"
        val result = scan(text, dollarMath = singleAndInlineDouble, paragraphs = paragraphsOf(text))
        assertEquals(listOf(MathKind.DisplayDollar), result.kinds())
        assertEquals(listOf("E = mc^2"), result.latex())
    }

    @Test
    fun inlineDoubleDollarRespectsCodeBarrierAndSingleDollarCoexists() {
        val code = "코드 `\$\$x\$\$` 안"
        assertTrue(scan(code, dollarMath = singleAndInlineDouble, hard = listOf(rangeOf(code, "`\$\$x\$\$`"))).spans.isEmpty())

        val mixed = scan("단일 \$a\$ 와 이중 \$\$b\$\$", dollarMath = singleAndInlineDouble)
        assertEquals(listOf(MathKind.InlineDollar, MathKind.InlineDoubleDollar), mixed.kinds())
        assertEquals(listOf("a", "b"), mixed.latex())
    }

    // MARK: - escape (연속 backslash 홀짝)

    @Test
    fun escapedBackslashBeforeParenIsNotDelimiter() {
        assertTrue(scan("""\\(x\\)""").spans.isEmpty())
    }

    @Test
    fun tripleBackslashParenIsDelimiter() {
        assertEquals(listOf("x"), scan("""\\\(x\)""").latex())
    }

    // MARK: - 빈/미완성/중첩 delimiter

    @Test
    fun unterminatedInlineMathStaysPlain() {
        val result = scan("""열림만 \(x + y 끝""")
        assertTrue(result.spans.isEmpty())
        assertTrue(result.has(MathDiagnostic.Kind.UnterminatedDelimiter))
    }

    @Test
    fun emptyInlineMathStaysPlain() {
        val result = scan("""빈 수식 \(\) 이다""")
        assertTrue(result.spans.isEmpty())
        assertTrue(result.has(MathDiagnostic.Kind.EmptyMath))
    }

    @Test
    fun nestedDelimiterStaysPlainTextEntirely() {
        val result = scan("""중첩 \(a \(b\) c\) 다""")
        assertTrue(result.has(MathDiagnostic.Kind.NestedDelimiter))
        // 안쪽 \(b\)도 수식으로 승격되지 않는다: 중첩 구간 전체가 plain text다.
        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun openerBeforeCloseIsNestedNotUnterminated() {
        val result = scan("""미완성 \(a 그리고 \(x+y\) 끝""")
        assertTrue(result.spans.isEmpty())
        assertTrue(result.has(MathDiagnostic.Kind.NestedDelimiter))
    }

    @Test
    fun unterminatedOpenerDoesNotHideMathOnNextLine() {
        // \(...\)는 한 logical line 안에서만 닫힌다. 다음 줄의 수식은 정상 인식한다.
        val result = scan("미완성 \\(a 끝\n정상 \\(x+y\\) 끝")
        assertEquals(listOf("x+y"), result.latex())
        assertTrue(result.has(MathDiagnostic.Kind.UnterminatedDelimiter))
    }

    @Test
    fun emptyMathDoesNotSwallowFollowingMath() {
        val result = scan("""빈 \(\) 뒤 \(z\)""")
        assertEquals(listOf("z"), result.latex())
        assertTrue(result.has(MathDiagnostic.Kind.EmptyMath))
    }

    // MARK: - Dollar math (opt-in)

    @Test
    fun dollarMathDisabledByDefault() {
        assertTrue(scan("가격 \$a+b\$ 이다").spans.isEmpty())
    }

    @Test
    fun dollarInlineMathWhenEnabled() {
        val result = scan("수식 \$a+b\$ 이다", dollarMath = single)
        assertEquals(1, result.spans.size)
        assertEquals(MathKind.InlineDollar, result.spans.first().kind)
        assertEquals("a+b", result.spans.first().latex)
    }

    @Test
    fun currencyAndSpacingRulesStayPlain() {
        assertTrue(scan("\$5", dollarMath = single).spans.isEmpty(), "\$5")
        assertTrue(scan("\$5 and \$10", dollarMath = single).spans.isEmpty(), "\$5 and \$10")
        assertTrue(scan("\$x\$5", dollarMath = single).spans.isEmpty(), "닫는 \$ 뒤 숫자")
        // 원문: \$x\$
        assertTrue(scan("\\\$x\\\$", dollarMath = single).spans.isEmpty(), "escape된 \$")
        assertTrue(scan("a \$\$b\$\$ c", dollarMath = single).spans.isEmpty(), "inline \$\$ (InlineDouble 없음)")
        assertTrue(scan("\$ x\$", dollarMath = single).spans.isEmpty(), "여는 \$ 뒤 공백")
        assertTrue(scan("\$x \$", dollarMath = single).spans.isEmpty(), "닫는 \$ 앞 공백")
        assertTrue(scan("\$a\nb\$", dollarMath = single).spans.isEmpty(), "줄바꿈")
    }

    @Test
    fun wholeParagraphDollarDollarIsBlockMath() {
        val result = scan("\$\$ x^2 \$\$", dollarMath = single)
        assertEquals(listOf(MathKind.DisplayDollar), result.kinds())
        assertEquals(listOf("x^2"), result.latex())
    }

    // MARK: - Markdown 기호가 든 수식

    @Test
    fun mathWithMarkdownSymbolsSurvives() {
        assertEquals(listOf("a * b", "c * d"), scan("""곱 \(a * b\) 과 \(c * d\)""").latex())
        assertEquals(listOf("x_[i]"), scan("""첨자 \(x_[i]\) 이다""").latex())
        assertEquals(listOf("[a](b)"), scan("""수식 \([a](b)\) 이다""").latex())
    }

    // MARK: - 금지 문맥: hard barrier

    @Test
    fun hardBarrierProtectsDelimiters() {
        val text = """코드 `\(x\)` 는 수식이 아니다"""
        assertTrue(scan(text, hard = listOf(rangeOf(text, """`\(x\)`"""))).spans.isEmpty())
    }

    @Test
    fun hardBarrierDisablesBlockMathInThatParagraph() {
        val text = "```\n\\[x\\]\n```"
        val result = scan(text, hard = listOf(Utf16Range(0, text.length)))
        assertTrue(result.spans.isEmpty())
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun matchDoesNotCrossHardBarrier() {
        // 배리어 앞의 opener와 뒤의 closer는 짝이 되지 않는다.
        val text = """앞 \(a `code` b\) 뒤"""
        val result = scan(text, hard = listOf(rangeOf(text, "`code`")))
        assertTrue(result.spans.isEmpty())
        assertTrue(result.has(MathDiagnostic.Kind.UnterminatedDelimiter))
    }

    // MARK: - 금지 문맥: soft range (link/image)

    @Test
    fun delimiterInsideSoftRangeIsNotMath() {
        val text = """[\(x\)](https://example.com)"""
        assertTrue(scan(text, soft = listOf(Utf16Range(0, text.length))).spans.isEmpty())
    }

    @Test
    fun softRangeFullyInsideMathContentIsMath() {
        val text = """수식 \([a](b)\) 이다"""
        val result = scan(text, soft = listOf(rangeOf(text, "[a](b)")))
        assertEquals(listOf("[a](b)"), result.latex())
    }

    @Test
    fun softRangePartiallyOverlappingMathIsNotMath() {
        // link 범위가 수식 span과 부분적으로 겹치면 수식이 아니다.
        val text = """앞 \(a [b\) c](https://e.com)"""
        val result = scan(text, soft = listOf(rangeOf(text, """[b\) c](https://e.com)""")))
        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun dollarOpenerInsideSoftRangeIsNotMath() {
        val text = "[가격 \$a\$ 링크](https://e.com)"
        assertTrue(scan(text, dollarMath = single, soft = listOf(Utf16Range(0, text.length))).spans.isEmpty())
    }

    // MARK: - 정렬·범위

    @Test
    fun spansAreSortedByStartWithBlockAndInlineMixed() {
        val text = "앞 \\(a\\) 뒤\n\n\\[ b \\]\n\n끝 \\(c\\)"
        val result = scan(text, paragraphs = paragraphsOf(text))
        assertEquals(listOf(MathKind.InlineParen, MathKind.DisplayBracket, MathKind.InlineParen), result.kinds())
        assertEquals(listOf("a", "b", "c"), result.latex())
        assertContentEquals(result.spans.map { it.originalRange.start }.sorted(), result.spans.map { it.originalRange.start })
    }

    @Test
    fun blockMathParagraphTrimsSurroundingWhitespace() {
        val text = "  \\[ x \\]  \n"
        val result = scan(text)
        val span = result.spans.singleOrNull()
        assertNotNull(span)
        assertEquals(MathKind.DisplayBracket, span.kind)
        assertEquals("\\[ x \\]", span.source)
        assertEquals("x", span.latex)
    }

    @Test
    fun blockBracketWithEarlyCloserIsNested() {
        val result = scan("\\[ a \\] b \\]")
        assertTrue(result.spans.isEmpty())
        assertTrue(result.has(MathDiagnostic.Kind.NestedDelimiter))
    }

    @Test
    fun blockBracketWithoutCloserIsUnterminated() {
        val result = scan("\\[ a + b")
        assertTrue(result.spans.isEmpty())
        assertTrue(result.has(MathDiagnostic.Kind.UnterminatedDelimiter))
        // inline 단계의 NonParagraphDisplayDelimiter도 함께 기록된다 (paragraph 전체가 아니므로).
        assertTrue(result.has(MathDiagnostic.Kind.NonParagraphDisplayDelimiter))
    }

    @Test
    fun emptyBlockBracketIsEmptyMath() {
        val result = scan("\\[ \\]")
        assertTrue(result.spans.isEmpty())
        assertTrue(result.has(MathDiagnostic.Kind.EmptyMath))
    }

    // MARK: - 다국어 UTF-16

    @Test
    fun koreanEmojiCombiningRTLOffsets() {
        val text = "한글🙂 e\u0301 עברית \\(x+y\\) 뒤"
        val result = scan(text)
        assertEquals(listOf("x+y"), result.latex())
        val span = result.spans.single()
        assertEquals(rangeOf(text, "\\(x+y\\)"), span.originalRange)
        assertEquals("\\(x+y\\)", text.substring(span.originalRange.start, span.originalRange.end))
        // surrogate pair(🙂)가 앞에 있어도 code unit 범위로 mask 왕복이 성립한다.
        val masked = MathProtector.protect(text, result.spans)
        assertEquals(text.length, masked.length)
        assertFalse(masked.contains("x+y"))
    }
}
