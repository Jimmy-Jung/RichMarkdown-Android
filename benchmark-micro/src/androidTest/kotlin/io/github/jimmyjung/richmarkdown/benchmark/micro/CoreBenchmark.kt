// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.benchmark.micro

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jimmyjung.richmarkdown.core.DollarMathOptions
import io.github.jimmyjung.richmarkdown.core.MathProtector
import io.github.jimmyjung.richmarkdown.core.MathScanner
import io.github.jimmyjung.richmarkdown.core.RichMarkdownParser
import io.github.jimmyjung.richmarkdown.core.Utf16Range
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `richmarkdown-core` 핫 패스. 전체 파싱 4크기 × dollar math, 수식 스캐너 단독, 길이 보존 mask 왕복.
 * 스캐너·mask 입력은 50 KiB(`parse50k`)와 같고 dollar math는 꺼진 상태다(`parse_50k`와 동일 조건).
 */
@RunWith(AndroidJUnit4::class)
class CoreBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val dollar = DollarMathOptions.Single + DollarMathOptions.InlineDouble

    @Before
    fun warmInputs() {
        BenchmarkInputs.parse256k
        check(!RichMarkdownParser.parse(BenchmarkInputs.parse256k).wasTruncated) { "256 KiB 입력이 잘렸다" }
    }

    @Test
    fun parse_1k() = parse(BenchmarkInputs.parse1k, DollarMathOptions.None)

    @Test
    fun parse_10k() = parse(BenchmarkInputs.parse10k, DollarMathOptions.None)

    @Test
    fun parse_50k() = parse(BenchmarkInputs.parse50k, DollarMathOptions.None)

    @Test
    fun parse_256k() = parse(BenchmarkInputs.parse256k, DollarMathOptions.None)

    @Test
    fun parse_50k_dollar() = parse(BenchmarkInputs.parse50k, dollar)

    private fun parse(markdown: String, options: DollarMathOptions) {
        benchmarkRule.measureRepeated { RichMarkdownParser.parse(markdown, options) }
    }

    @Test
    fun mathScanner_50k() {
        val text = BenchmarkInputs.parse50k
        val ranges = Pass1Ranges.collect(text)
        benchmarkRule.measureRepeated { ranges.scanner(text, DollarMathOptions.None).scan() }
    }

    @Test
    fun protectRestore_50k() {
        val text = BenchmarkInputs.parse50k
        val spans = Pass1Ranges.collect(text).scanner(text, DollarMathOptions.None).scan().spans
        val chars = text.toCharArray()
        check(spans.isNotEmpty()) { "보호할 수식 span이 없다" }
        benchmarkRule.measureRepeated { MathProtector.restore(MathProtector.protect(chars, spans), chars, spans) }
    }

    /**
     * `RichMarkdownParser` 1차 패스와 같은 범위 수집(code/HTML hard, link/image soft, paragraph).
     * 파서 내부 collector가 private이라 스캐너 단독 측정을 위해 같은 규칙을 여기서 반복한다.
     */
    private class Pass1Ranges(private val textLength: Int) : AbstractVisitor() {
        val forbidden = ArrayList<Utf16Range>()
        val soft = ArrayList<Utf16Range>()
        val paragraphs = ArrayList<Utf16Range>()

        override fun visit(paragraph: Paragraph) {
            paragraph.utf16Range()?.let(paragraphs::add)
            visitChildren(paragraph)
        }

        override fun visit(fencedCodeBlock: FencedCodeBlock) = addForbidden(fencedCodeBlock)
        override fun visit(indentedCodeBlock: IndentedCodeBlock) = addForbidden(indentedCodeBlock)
        override fun visit(code: Code) = addForbidden(code)
        override fun visit(htmlBlock: HtmlBlock) = addForbidden(htmlBlock)
        override fun visit(htmlInline: HtmlInline) = addForbidden(htmlInline)
        override fun visit(link: Link) = addSoft(link)
        override fun visit(image: Image) = addSoft(image)

        private fun addForbidden(node: Node) {
            node.utf16Range()?.let(forbidden::add)
        }

        private fun addSoft(node: Node) {
            node.utf16Range()?.let(soft::add)
        }

        private fun Node.utf16Range(): Utf16Range? {
            var start = Int.MAX_VALUE
            var end = Int.MIN_VALUE
            for (span in sourceSpans) {
                if (span.inputIndex < 0) continue
                start = minOf(start, span.inputIndex)
                end = maxOf(end, span.inputIndex + span.length)
            }
            if (start > end) return null
            return Utf16Range(start, minOf(end, textLength))
        }

        fun scanner(text: String, dollarMath: DollarMathOptions) =
            MathScanner(text, forbidden, soft, paragraphs, dollarMath)

        companion object {
            private val parser: Parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()

            fun collect(text: String): Pass1Ranges = Pass1Ranges(text.length).also { parser.parse(text).accept(it) }
        }
    }
}
