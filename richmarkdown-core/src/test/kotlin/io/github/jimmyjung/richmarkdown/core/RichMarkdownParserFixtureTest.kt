// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS `MathScannerFixtureTests.swift` 중 파서 경유 fixture 이식 (DEVELOPMENT.md §8 parser/보안 fixture).
 * "구현한 규칙과 fixture가 실제 계약이다." 스캐너 단위 케이스는 `MathScannerFixtureTest`,
 * README smoke는 `RichMarkdownParserTest`에 있다.
 */
class RichMarkdownParserFixtureTest {

    // MARK: - Helpers

    private fun parse(markdown: String, dollar: Boolean = false): ParsedDocument =
        RichMarkdownParser.parse(markdown, DollarMathOptions.fromParsesDollarMath(dollar))

    private fun firstParagraphRuns(document: ParsedDocument): List<InlineRun> =
        document.blocks.filterIsInstance<ParsedBlock.Paragraph>().firstOrNull()?.runs ?: emptyList()

    private fun plainText(runs: List<InlineRun>): String = runs.joinToString("") { run ->
        when (val content = run.content) {
            is InlineContent.Text -> content.text
            is InlineContent.Code -> content.code
            is InlineContent.Math -> content.segment.source
            is InlineContent.Link -> content.text
            InlineContent.HardBreak -> "\n"
            InlineContent.SoftBreak -> " "
        }
    }

    private fun codeBlocks(document: ParsedDocument): List<ParsedBlock.CodeBlock> =
        document.blocks.filterIsInstance<ParsedBlock.CodeBlock>()

    // MARK: - plainText 왕복

    @Test
    fun inlineParenMathRoundTripsPlainText() {
        val markdown = "원의 넓이는 \\( A = \\pi r^2 \\)입니다."
        val doc = parse(markdown)
        assertEquals(listOf("A = \\pi r^2"), doc.allMathSegments.map { it.latex })
        assertEquals(markdown, plainText(firstParagraphRuns(doc)))
    }

    @Test
    fun nestedDelimiterKeepsTrailingTextVisible() {
        val doc = parse("중첩 \\(a \\(b\\) c\\) 다")
        assertTrue(doc.allMathSegments.isEmpty())
        assertContains(plainText(firstParagraphRuns(doc)), "c")
    }

    @Test
    fun mathWithAsteriskDoesNotBecomeEmphasis() {
        val doc = parse("곱 \\(a * b\\) 과 \\(c * d\\)")
        assertEquals(listOf("a * b", "c * d"), doc.allMathSegments.map { it.latex })
        assertFalse(firstParagraphRuns(doc).any { it.italic || it.bold })
    }

    @Test
    fun inlineDoubleDollarPlainTextRoundTrips() {
        val single = RichMarkdownParser.parse("총합(\$\$f(1)\$\$) 및 상수항(\$\$f(0)\$\$)", DollarMathOptions.Single)
        assertTrue(single.allMathSegments.isEmpty())
        assertEquals("총합(\$\$f(1)\$\$) 및 상수항(\$\$f(0)\$\$)", plainText(firstParagraphRuns(single)))

        val both = RichMarkdownParser.parse(
            "총합(\$\$f(1)\$\$) 및 상수항(\$\$f(0)\$\$)을 구한다.",
            DollarMathOptions.Single + DollarMathOptions.InlineDouble,
        )
        assertEquals(listOf("f(1)", "f(0)"), both.allMathSegments.map { it.latex })
        assertEquals("총합(\$\$f(1)\$\$) 및 상수항(\$\$f(0)\$\$)을 구한다.", plainText(firstParagraphRuns(both)))
    }

    // MARK: - 금지 문맥 자동 인식 (code/HTML/image)

    @Test
    fun fencedCodeBlockProtectsDelimiters() {
        val doc = parse("```\n\\(x\\)\n```")
        assertTrue(doc.allMathSegments.isEmpty())
        assertEquals(listOf("\\(x\\)"), codeBlocks(doc).map { it.code })
    }

    @Test
    fun tildeFenceProtectsDelimiters() {
        assertTrue(parse("~~~\n\\(x\\)\n~~~").allMathSegments.isEmpty())
    }

    @Test
    fun longBacktickFenceProtectsDelimiters() {
        assertTrue(parse("`````\n\\(x\\)\n`````").allMathSegments.isEmpty())
    }

    @Test
    fun indentedCodeProtectsDelimiters() {
        val doc = parse("본문\n\n    \\(x\\)\n")
        assertTrue(doc.allMathSegments.isEmpty())
        assertEquals(listOf("\\(x\\)"), codeBlocks(doc).map { it.code })
    }

    @Test
    fun htmlBlockProtectsDelimitersAndShowsLiteral() {
        val doc = parse("<div>\n\\(x\\)\n</div>")
        assertTrue(doc.allMathSegments.isEmpty())
        assertTrue(doc.blocks.filterIsInstance<ParsedBlock.Paragraph>().any { plainText(it.runs).contains("<div>") })
    }

    @Test
    fun imageAltOnlyAndInternalDelimiterProtected() {
        val doc = parse("![대체 \\(x\\) 텍스트](https://example.com/i.png)")
        assertTrue(doc.allMathSegments.isEmpty())
        val text = plainText(firstParagraphRuns(doc))
        assertContains(text, "대체")
        assertFalse(text.contains("example.com"))
    }

    // MARK: - 링크 allowlist

    @Test
    fun allowedSchemesBecomeLinks() {
        for (destination in listOf("https://a.com", "http://a.com", "mailto:a@b.com")) {
            val runs = firstParagraphRuns(parse("[라벨]($destination)"))
            assertTrue(
                runs.any { (it.content as? InlineContent.Link)?.text == "라벨" },
                "링크가 되어야 한다: $destination",
            )
        }
    }

    @Test
    fun disallowedSchemeAndRelativeUrlStayPlainText() {
        for (destination in listOf("ftp://a.com", "javascript:alert(1)", "/relative/path", "tel:12345")) {
            val runs = firstParagraphRuns(parse("[라벨]($destination)"))
            assertFalse(runs.any { it.content is InlineContent.Link }, "링크가 되면 안 된다: $destination")
            assertContains(plainText(runs), "라벨")
        }
    }

    // MARK: - Markdown escape·entity

    @Test
    fun escapedPunctuationLosesBackslashInDisplayText() {
        // 2차 파싱 Text 노드를 원문 slice로 되돌리므로 escape 해제를 직접 해야 한다.
        val doc = parse("이스케이프한 \\\$100 과 \\*강조 아님\\* 과 \\_밑줄\\_")
        assertEquals("이스케이프한 \$100 과 *강조 아님* 과 _밑줄_", plainText(firstParagraphRuns(doc)))
    }

    @Test
    fun htmlEntitiesUseMarkdownDecodedText() {
        val doc = parse("A &amp; B &#169; &NotEqualTilde; &NotAnEntity;")
        assertEquals("A & B © ≂\u0338 &NotAnEntity;", plainText(firstParagraphRuns(doc)))
    }

    @Test
    fun decodedEntityCannotCollideWithOpaqueMathMarker() {
        val doc = parse("&#xE000;richmarkdown-0&#xE001; &amp; \\(x\\)")
        assertEquals("\uE000richmarkdown-0\uE001 & \\(x\\)", plainText(firstParagraphRuns(doc)))
        assertEquals(listOf("x"), doc.allMathSegments.map { it.latex })
    }

    @Test
    fun escapedMathDelimitersKeepBackslash() {
        // 수식으로 인식되지 않은 구분자는 backslash를 유지해야 한다 (fail-open 계약).
        val doc = parse("미완성 \\(x + y 와 빈 \\(\\) 와 인라인 위치 \\[x+y\\]")
        val text = plainText(firstParagraphRuns(doc))
        assertContains(text, "\\(x + y")
        assertContains(text, "\\(\\)")
        assertContains(text, "\\[x+y\\]")
    }

    @Test
    fun escapedBackslashCollapsesToSingle() {
        val doc = parse("경로 C:\\\\temp 와 \\\\(x\\\\)")
        val text = plainText(firstParagraphRuns(doc))
        assertContains(text, "C:\\temp")
        assertContains(text, "\\(x\\)")
        assertTrue(doc.allMathSegments.isEmpty())
    }

    @Test
    fun mathSourceKeepsItsBackslashes() {
        val doc = parse("수식 \\(\\frac{a}{b}\\) 끝")
        assertEquals(listOf("\\(\\frac{a}{b}\\)"), doc.allMathSegments.map { it.source })
        assertEquals(listOf("\\frac{a}{b}"), doc.allMathSegments.map { it.latex })
    }

    @Test
    fun codeSpanKeepsBackslashesLiterally() {
        val doc = parse("코드 `\\\$100` 와 `\\(x\\)`")
        val codes = firstParagraphRuns(doc).mapNotNull { (it.content as? InlineContent.Code)?.code }
        assertEquals(listOf("\\\$100", "\\(x\\)"), codes)
    }

    // MARK: - 인라인 강조

    @Test
    fun emphasisFlagsAreCarriedOnRuns() {
        val runs = firstParagraphRuns(parse("**bold** 와 *italic* 와 ~~strike~~"))
        assertTrue(runs.any { it.bold && !it.italic && !it.strikethrough })
        assertTrue(runs.any { it.italic && !it.bold })
        assertTrue(runs.any { it.strikethrough })
    }

    // MARK: - 다국어 (UTF-16 위치)

    @Test
    fun koreanEmojiCombiningRtlOffsets() {
        val markdown = "한글🙂 e\u0301 עברית \\(x+y\\) 뒤"
        val doc = parse(markdown)
        assertEquals(listOf("x+y"), doc.allMathSegments.map { it.latex })
        assertEquals(markdown, plainText(firstParagraphRuns(doc)))
    }

    // MARK: - 입력 제한

    @Test
    fun oversizedInputIsTruncatedWithMarker() {
        val big = "한".repeat(120_000) // 360,000 bytes > 256 KiB
        val doc = parse(big)
        assertTrue(doc.wasTruncated)
        val all = doc.blocks.filterIsInstance<ParsedBlock.Paragraph>().joinToString("") { plainText(it.runs) }
        assertContains(all, InputLimits.TRUNCATION_MARKER)
    }

    @Test
    fun deepMarkdownDoesNotCrash() {
        val deep = "> ".repeat(InputLimits.MAX_BLOCK_QUOTE_DEPTH) + "깊다"
        val doc = parse(deep)
        assertFalse(doc.wasTruncated)
        assertTrue(doc.blocks.isNotEmpty())
    }

    @Test
    fun excessiveBlockQuoteDepthIsTruncatedBeforeParsing() {
        val deep = "> ".repeat(InputLimits.MAX_BLOCK_QUOTE_DEPTH + 1) + "깊다"
        val bounded = InputLimits.bound(deep)
        assertTrue(bounded.wasTruncated)
        assertContains(bounded.text, InputLimits.TRUNCATION_MARKER)

        val doc = parse(deep)
        assertTrue(doc.wasTruncated)
        assertContains(plainText(firstParagraphRuns(doc)), InputLimits.TRUNCATION_MARKER)
    }

    @Test
    fun quoteLikeTextInsideFencedCodeDoesNotTriggerDepthLimit() {
        val quoteLikeCode = "> ".repeat(InputLimits.MAX_BLOCK_QUOTE_DEPTH + 1) + "코드"
        val markdown = "```text\n$quoteLikeCode\n```"

        assertFalse(InputLimits.bound(markdown).wasTruncated)

        val doc = parse(markdown)
        assertFalse(doc.wasTruncated)
        assertEquals(listOf(quoteLikeCode), codeBlocks(doc).map { it.code })
    }

    @Test
    fun deepBlockQuoteAfterCrLineEndingIsTruncated() {
        val deep = "> ".repeat(InputLimits.MAX_BLOCK_QUOTE_DEPTH + 1) + "깊다"
        assertTrue(InputLimits.bound("첫 줄\r$deep").wasTruncated)
    }

    @Test
    fun crlfFenceClosingDoesNotBypassFollowingDepthLimit() {
        val deep = "> ".repeat(InputLimits.MAX_BLOCK_QUOTE_DEPTH + 1) + "깊다"
        assertTrue(InputLimits.bound("```\r\ncode\r\n```\r\n$deep").wasTruncated)
    }

    @Test
    fun exitingQuotedFenceRestoresDepthLimit() {
        val deep = "> ".repeat(InputLimits.MAX_BLOCK_QUOTE_DEPTH + 1) + "깊다"
        assertTrue(InputLimits.bound("> ```\noutside\n$deep").wasTruncated)
    }

    @Test
    fun manyNodesDoesNotCrash() {
        val many = List(500) { "- 항목 \\(x\\)" }.joinToString("\n")
        assertTrue(parse(many).blocks.isNotEmpty())
    }
}
