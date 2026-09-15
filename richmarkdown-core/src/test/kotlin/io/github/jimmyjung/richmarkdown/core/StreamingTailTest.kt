// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 스트리밍 tail 표시 변환 계약. iOS `StreamingTailTests`를 이식했다.
 *
 * iOS 테스트는 `RichMarkdownParser`로 run을 만든다. 여기서는 파서가 내놓는 것과 같은 run을
 * 직접 구성한다 — 미닫힌 마크는 한 `Text` run에 literal로 남고, 닫힌 강조·코드·수식은 별도 run이다.
 */
class StreamingTailTest {

    private fun text(s: String, bold: Boolean = false) = InlineRun(InlineContent.Text(s), bold = bold)

    private fun plainText(runs: List<InlineRun>): String = runs.joinToString("") { run ->
        when (val c = run.content) {
            is InlineContent.Text -> c.text
            is InlineContent.Code -> c.code
            is InlineContent.Math -> c.segment.source
            is InlineContent.Link -> c.text
            InlineContent.HardBreak -> "\n"
            InlineContent.SoftBreak -> " "
        }
    }

    /** 단일 literal 문단: 파서는 이스케이프를 해제한 한 `Text` run을 내놓는다. */
    private fun hidden(paragraphText: String, dollar: Boolean = false): String =
        plainText(StreamingTail.hidingUnclosedOpeners(listOf(text(paragraphText)), parsesDollarMath = dollar))

    // MARK: - opener 억제

    @Test
    fun stripsUnclosedOpenersAtTail() {
        assertEquals("다음 단계로는 제가 선생님처", hidden("다음 단계로는 **제가 선생님처"))
        assertEquals("기울임 시작", hidden("기울임 *시작"))
        assertEquals("셋 강조", hidden("셋 ***강조"))
        assertEquals("취소 선", hidden("취소 ~~선"))
        assertEquals("코드 foo", hidden("코드 `foo"))
        assertEquals("코드 foo", hidden("코드 ``foo"))
        assertEquals("수식 a+b", hidden("""수식 \(a+b"""))
    }

    @Test
    fun keepsMatchedOrNonFlankingMarks() {
        assertEquals("5 * 3", hidden("5 * 3"))
        assertEquals("5*3", hidden("5*3"))
        assertEquals("""빈 수식 \(\) 뒤""", hidden("""빈 수식 \(\) 뒤"""))
        assertEquals("물결 ~10% 상승", hidden("물결 ~10% 상승"))
        assertEquals("snake_case 유지", hidden("snake_case 유지"))
        // 닫힌 강조는 별도 run이라 마지막 run에 opener가 없다.
        val closedBold = listOf(text("굵게 "), text("완료", bold = true))
        assertEquals("굵게 완료", plainText(StreamingTail.hidingUnclosedOpeners(closedBold, parsesDollarMath = false)))
    }

    @Test
    fun dollarOpenerOnlyWhenDollarMathIsEnabledAndLooksLikeMath() {
        assertEquals("수식 x^2", hidden("수식 \$x^2", dollar = true))
        assertEquals("""수식 \frac""", hidden("""수식 ${'$'}\frac""", dollar = true))
        assertEquals("수식 \$x^2", hidden("수식 \$x^2", dollar = false))
        assertEquals("가격 \$5", hidden("가격 \$5", dollar = true))
        assertEquals("가격 \$ x", hidden("가격 \$ x", dollar = true))
        assertEquals("\$5 and \$10", hidden("\$5 and \$10", dollar = true))
    }

    @Test
    fun doubleDollarOpenerIsHiddenOnlyWithInlineDoubleOption() {
        val both = DollarMathOptions.Single + DollarMathOptions.InlineDouble
        val single = DollarMathOptions.Single

        fun hiddenText(runs: List<InlineRun>, options: DollarMathOptions): String =
            plainText(StreamingTail.hidingUnclosedOpeners(runs, options))

        assertEquals("총합(f(1", hiddenText(listOf(text("총합(\$\$f(1")), both))
        assertEquals("총합(\$\$f(1", hiddenText(listOf(text("총합(\$\$f(1")), single))
        assertEquals("가격 \$\$5", hiddenText(listOf(text("가격 \$\$5")), both))
        assertEquals("공백 \$\$ x", hiddenText(listOf(text("공백 \$\$ x")), both))
        // 닫힌 `$$x$$`는 파서가 math run으로 바꾼다.
        val closed = listOf(
            text("닫힘 "),
            InlineRun(InlineContent.Math(MathSegment(source = "\$\$x\$\$", latex = "x", kind = MathKind.InlineDoubleDollar))),
            text(" 뒤"),
        )
        assertEquals("닫힘 \$\$x\$\$ 뒤", hiddenText(closed, both))
        assertEquals(
            "앞 \$\$a\$\$ 뒤 b",
            StreamingTail.strippingUnclosedOpeners("앞 \$\$a\$\$ 뒤 \$\$b", DollarMathOptions.InlineDouble),
        )
    }

    @Test
    fun escapesAndLoneBackslash() {
        // 파서는 `\$`를 `$`로 해제해 넘긴다. 숫자 앞 `$`는 opener가 아니다.
        assertTrue(hidden("이스케이프 \$5 유지", dollar = true).contains("\$5"))
        assertEquals("끝에 백슬래시 ", hidden("""끝에 백슬래시 \"""))
        // 파서는 `\\`를 `\` 하나로 디코딩해 넘기므로 run 수준에서는 부분 `\(`와 구분되지 않는다.
        // raw 문자열에 두 개가 남아 있으면 이스케이프로 보고 유지한다.
        assertEquals("""두 개 \\""", StreamingTail.strippingUnclosedOpeners("""두 개 \\""", parsesDollarMath = false))
    }

    @Test
    fun emptyParagraphGuardAndRunRemoval() {
        val lone = listOf(text("**"))
        assertEquals(lone, StreamingTail.hidingUnclosedOpeners(lone, parsesDollarMath = false), "문단 전체가 비는 억제는 건너뛴다")

        // "**굵게**\n**" → [굵게(bold), softBreak, "**"]
        val bolded = listOf(text("굵게", bold = true), InlineRun(InlineContent.SoftBreak), text("**"))
        val result = StreamingTail.hidingUnclosedOpeners(bolded, parsesDollarMath = false)
        assertEquals(3, bolded.size)
        assertEquals(2, result.size, "마지막 run만 비면 그 run을 지운다")
        assertEquals(true, result.first().bold)
        assertEquals(InlineContent.SoftBreak, result.last().content)
    }

    // MARK: - 꼬리 페이드

    @Test
    fun fadeAnchorsToTheEndRegardlessOfLength() {
        val source = "가나다라마바사아자차카타파하 가나다라마바사아자차카타파하"
        val long = StreamingTail.fadePlan(listOf(text(source)), graphemeCount = 12)
        assertEquals(12, long.tail.size)
        assertEquals(StreamingTail.MINIMUM_ALPHA, long.tail.last().alpha)
        assertTrue(long.tail.first().alpha > long.tail.last().alpha)
        assertEquals(source, plainText(long.head) + long.tail.joinToString("") { it.text })

        val short = StreamingTail.fadePlan(listOf(text("가나다")), graphemeCount = 12)
        assertTrue(short.head.isEmpty())
        assertEquals(3, short.tail.size)
        assertEquals(StreamingTail.MINIMUM_ALPHA, short.tail.last().alpha)
        assertTrue(short.tail.first().alpha > short.tail.last().alpha)
    }

    @Test
    fun fadeSpansTrailingTextRunsAndKeepsStyle() {
        // "굵은텍스트"(5) + "끝"(1) = 6 grapheme가 페이드 예산을 채우고 "앞 "은 head에 남는다.
        val runs = listOf(text("앞 "), text("굵은텍스트", bold = true), text("끝"))
        val plan = StreamingTail.fadePlan(runs, graphemeCount = 6)
        assertEquals(6, plan.tail.size)
        assertTrue(plan.tail.take(5).all { it.run.bold })
        assertEquals(false, plan.tail.last().run.bold)
        assertEquals(1, plan.head.size)
        assertEquals("앞 ", plainText(plan.head))
    }

    @Test
    fun fadeStopsAtNonTextRuns() {
        val math = InlineRun(InlineContent.Math(MathSegment(source = """\(x^2\)""", latex = "x^2", kind = MathKind.InlineParen)))
        val mathTail = StreamingTail.fadePlan(listOf(text("본문 "), math), graphemeCount = 12)
        assertTrue(mathTail.tail.isEmpty())
        assertEquals(2, mathTail.head.size)

        val codeTail = StreamingTail.fadePlan(listOf(text("본문 "), InlineRun(InlineContent.Code("code"))), graphemeCount = 12)
        assertTrue(codeTail.tail.isEmpty())

        // "첫 줄\n**" → [첫 줄, softBreak, "**"]; 억제 뒤 마지막은 break다.
        val breakThenEmpty = StreamingTail.hidingUnclosedOpeners(
            listOf(text("첫 줄"), InlineRun(InlineContent.SoftBreak), text("**")),
            parsesDollarMath = false,
        )
        val plan = StreamingTail.fadePlan(breakThenEmpty, graphemeCount = 12)
        assertTrue(plan.tail.isEmpty(), "break 뒤가 비면 앞 줄을 페이드하지 않는다")
    }

    @Test
    fun fadeCountsGraphemeClusters() {
        val plan = StreamingTail.fadePlan(listOf(text("가나다👨‍👩‍👧")), graphemeCount = 12)
        assertEquals(4, plan.tail.size)
        assertEquals("👨‍👩‍👧", plan.tail.last().text)
    }

    @Test
    fun zeroCountDisablesFade() {
        val original = listOf(text("텍스트"))
        val plan = StreamingTail.fadePlan(original, graphemeCount = 0)
        assertEquals(original, plan.head)
        assertTrue(plan.tail.isEmpty())
    }

    @Test
    fun alphaRampIsMonotonic() {
        val alphas = (0 until 12).map { StreamingTail.alpha(it, count = 12) }
        assertEquals(StreamingTail.MINIMUM_ALPHA, alphas.first())
        assertTrue(alphas.zipWithNext().all { (a, b) -> a < b })
        assertEquals(1.0, StreamingTail.alpha(12, count = 12))
    }
}
