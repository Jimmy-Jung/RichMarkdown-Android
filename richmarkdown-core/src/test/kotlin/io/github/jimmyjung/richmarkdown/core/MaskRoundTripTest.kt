// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 길이 보존 mask와 protect/restore round-trip (iOS DEVELOPMENT.md §3, §8). iOS `MaskRoundTripTests` 이식. */
class MaskRoundTripTest {

    private fun scan(text: String, dollar: Boolean = false): List<ProtectedMathSpan> =
        MathScanner(
            text = text,
            forbiddenRanges = emptyList(),
            paragraphRanges = listOf(Utf16Range(0, text.length)),
            dollarMath = DollarMathOptions.fromParsesDollarMath(dollar),
        ).scan().spans

    @Test
    fun maskPreservesLengthAndNewlines() {
        val text = "앞 \\(a+b\\) 뒤\n\\(c\\) 끝"
        val chars = text.toCharArray()
        val spans = scan(text)
        assertTrue(spans.isNotEmpty())
        val masked = MathProtector.protect(chars, spans)
        assertEquals(chars.size, masked.size)
        for ((i, c) in chars.withIndex()) {
            if (c == '\n' || c == '\r') assertEquals(c, masked[i], "newline은 유지되어야 한다")
        }
        for (span in spans) {
            for (i in span.originalRange.start until span.originalRange.end) {
                if (chars[i] != '\n' && chars[i] != '\r') assertEquals('x', masked[i])
            }
        }
    }

    @Test
    fun restoreProtectRoundTripIsExact() {
        val text = "한글 \\(x_[i]\\) 🙂 \\[전체\\] 아님 \$a\$"
        val chars = text.toCharArray()
        val spans = scan(text, dollar = true)
        val masked = MathProtector.protect(chars, spans)
        val restored = MathProtector.restore(masked, chars, spans)
        assertContentEquals(chars, restored, "restore(protect(source)) == source")
    }

    @Test
    fun spanSourceMatchesOriginalSlice() {
        val text = "이모지🙂와 결합é 문자 \\(f(x) = x^2\\) RTLעברית"
        for (span in scan(text)) {
            assertEquals(text.substring(span.originalRange.start, span.originalRange.end), span.source)
        }
    }

    /** seeded fuzz: 임의 다국어 문자열에서 mask 길이 보존과 round-trip을 확인한다. */
    @Test
    fun fuzzRoundTrip() {
        val rng = Random(0x5EED)
        val alphabet = listOf(
            "a", "한", "🙂", "\\", "(", ")", "[", "]", "$", " ", "\n", "*", "_", "`", "é", "ע",
        )
        repeat(300) {
            val length = rng.nextInt(60) + 1
            val text = buildString { repeat(length) { append(alphabet[rng.nextInt(alphabet.size)]) } }
            val chars = text.toCharArray()
            val spans = scan(text, dollar = true)
            val masked = MathProtector.protect(chars, spans)
            assertEquals(chars.size, masked.size)
            assertContentEquals(chars, MathProtector.restore(masked, chars, spans))
            val sorted = spans.map { it.originalRange }.sortedBy { it.start }
            sorted.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start, "span은 겹치지 않는다") }
        }
    }

    /** 다국어 수식 앞뒤의 source range가 그대로 유지되는 property test (§3 필수). */
    @Test
    fun multilingualSurroundingRangesPreserved() {
        val prefix = "한글🙂 앞부분 "
        val math = "\\(x+y\\)"
        val suffix = " עברית 뒤"
        val text = prefix + math + suffix
        val spans = scan(text)
        assertEquals(1, spans.size)
        val span = spans.single()
        assertEquals(prefix.length, span.originalRange.start)
        assertEquals(prefix.length + math.length, span.originalRange.end)
        val chars = text.toCharArray()
        val masked = MathProtector.protect(chars, spans)
        assertContentEquals(chars.copyOfRange(0, span.originalRange.start), masked.copyOfRange(0, span.originalRange.start))
        assertContentEquals(chars.copyOfRange(span.originalRange.end, chars.size), masked.copyOfRange(span.originalRange.end, chars.size))
    }
}
