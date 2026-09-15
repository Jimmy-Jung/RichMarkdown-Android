// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import io.github.jimmyjung.richmarkdown.core.InputLimits
import io.github.jimmyjung.richmarkdown.core.ParsedBlock
import io.github.jimmyjung.richmarkdown.core.ParsedDocument
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * 렌더 모델의 순수 로직 (JVM). Android 런타임이 필요한 raster·게시 경로는
 * `androidTest/MathRenderServiceTest`와 렌더러 instrumented 테스트가 맡는다.
 */
class RenderModelLogicTest {

    private fun identity(
        markdown: String,
        dollarMath: LatexDollarMathOptions = LatexDollarMathOptions.None,
        wasTruncated: Boolean = false,
    ) = RichMarkdownRenderModel.ParseIdentity(markdown, dollarMath, wasTruncated)

    private fun request(
        markdown: String = "x",
        fontSizePx: Float = 34f,
        colorArgb: Int = 0xFF000000.toInt(),
        rastersDisplayMath: Boolean = true,
    ) = RichMarkdownRenderModel.Request(
        boundedInput = InputLimits.BoundedInput(markdown, wasTruncated = false),
        dollarMath = LatexDollarMathOptions.None,
        fontSizePx = fontSizePx,
        colorArgb = colorArgb,
        rastersDisplayMath = rastersDisplayMath,
    )

    private fun key(latex: String, fontSizePx: Float) =
        MathRenderKey(latex, LatexMathFont.KaTeX, fontSizePx, 0xFF000000.toInt(), isDisplay = false)

    // MARK: - ParseIdentity.isStreamingPrefix

    @Test
    fun streamingPrefix_sameSettingsAndExtendedMarkdown() {
        assertTrue(identity("Hello").isStreamingPrefix(identity("Hello world")))
        assertTrue("같은 문자열도 prefix", identity("Hello").isStreamingPrefix(identity("Hello")))
        assertFalse("확장이 아니면 거짓", identity("Hello world").isStreamingPrefix(identity("Hello")))
    }

    @Test
    fun streamingPrefix_rejectsDifferentDollarMathOrTruncation() {
        assertFalse(identity("a").isStreamingPrefix(identity("ab", dollarMath = LatexDollarMathOptions.Single)))
        assertFalse(identity("a", wasTruncated = true).isStreamingPrefix(identity("ab")))
        assertFalse(identity("a").isStreamingPrefix(identity("ab", wasTruncated = true)))
    }

    // MARK: - Request

    @Test
    fun matchesRasterConfiguration_ignoresMarkdownOnly() {
        val base = request(markdown = "a")
        assertTrue(base.matchesRasterConfiguration(request(markdown = "a b")))
        assertFalse(base.matchesRasterConfiguration(request(fontSizePx = 35f)))
        assertFalse(base.matchesRasterConfiguration(request(colorArgb = 0xFFFFFFFF.toInt())))
        assertFalse(base.matchesRasterConfiguration(request(rastersDisplayMath = false)))
    }

    @Test
    fun requestOf_appliesInputLimits() {
        val oversized = "a".repeat(InputLimits.MAX_INPUT_UTF8_BYTES + 1)
        val request = RichMarkdownRenderModel.Request.of(oversized, LatexDollarMathOptions.None, 34f, 0)
        assertTrue(request.wasTruncated)
        assertTrue(request.markdown.endsWith(InputLimits.TRUNCATION_MARKER))
        assertEquals(request.parseIdentity, RichMarkdownRenderModel.ParseIdentity(request.markdown, LatexDollarMathOptions.None, true))
    }

    // MARK: - ParseCache

    @Test
    fun parseCache_keyEncodesOptionsAndTruncation() {
        val key = ParseCache.key("한글 **b**", LatexDollarMathOptions.Single + LatexDollarMathOptions.InlineDouble, wasTruncated = true)
        assertEquals("3T한글 **b**", key.value)
        assertEquals("한글 **b**".toByteArray(Charsets.UTF_8).size, key.sourceByteCount)
        assertEquals("0-x", ParseCache.key("x", LatexDollarMathOptions.None, wasTruncated = false).value)
    }

    @Test
    fun parseCache_storeAndLookup() {
        ParseCache.removeAll()
        val document = ParsedDocument(blocks = listOf(ParsedBlock.ThematicBreak))
        val key = ParseCache.key("---", LatexDollarMathOptions.None, wasTruncated = false)
        assertNull(ParseCache.document(key))

        ParseCache.store(key, document)
        assertSame(document, ParseCache.document(key))
        assertNull("dollar 옵션이 다르면 다른 key", ParseCache.document(ParseCache.key("---", LatexDollarMathOptions.Single, wasTruncated = false)))

        ParseCache.removeAll()
        assertNull(ParseCache.document(key))
    }

    @Test
    fun parseCache_estimatedCost() {
        val document = ParsedDocument(blocks = listOf(ParsedBlock.ThematicBreak, ParsedBlock.ThematicBreak))
        assertEquals(10 * 3 + 2 * 512, ParseCache.estimatedCost(document, sourceByteCount = 10))
        assertEquals("빈 원문도 최소 1 byte", 3, ParseCache.estimatedCost(ParsedDocument(emptyList()), sourceByteCount = 0))
        assertEquals("포화", Int.MAX_VALUE, ParseCache.estimatedCost(document, sourceByteCount = Int.MAX_VALUE))
    }

    // MARK: - MathRenderService.preflightAllows

    @Test
    fun preflight_fontSizeBoundaries() {
        assertTrue(MathRenderService.preflightAllows(key("x", 1f)))
        assertFalse(MathRenderService.preflightAllows(key("x", 0.99f)))
        assertTrue(MathRenderService.preflightAllows(key("x", 1024f)))
        assertFalse(MathRenderService.preflightAllows(key("x", 1024.5f)))
        assertFalse(MathRenderService.preflightAllows(key("x", Float.NaN)))
        assertFalse(MathRenderService.preflightAllows(key("x", Float.POSITIVE_INFINITY)))
    }

    @Test
    fun preflight_sourceLengthBoundaries() {
        val max = InputLimits.MAX_MATH_SOURCE_UTF8_BYTES
        // 4096 × 2 = 8192 (edge 한계), 4096 × 2² = 16384 (count 여유).
        assertTrue(MathRenderService.preflightAllows(key("x".repeat(max), 2f)))
        assertFalse(MathRenderService.preflightAllows(key("x".repeat(max + 1), 2f)))
        assertFalse("UTF-8 byte 기준", MathRenderService.preflightAllows(key("한".repeat(max / 3 + 1), 1f)))
    }

    @Test
    fun preflight_pixelEstimateBoundaries() {
        // edge: 8 × 1024 = 8192 통과지만 count 8 × 1024² > 4,194,304 → 거절.
        assertFalse(MathRenderService.preflightAllows(key("x".repeat(8), 1024f)))
        // count: 4 × 1024² = 4,194,304 정확히 한계 → 통과.
        assertTrue(MathRenderService.preflightAllows(key("x".repeat(4), 1024f)))
        // edge: 9 × 1024 = 9216 > 8192 → 거절 (count는 무관).
        assertFalse(MathRenderService.preflightAllows(key("x".repeat(9), 1024f)))
        // 일상 값 17sp@3x = 51 px: edge 한계는 8192 / 51 → 160 byte (iOS 17pt@3x와 같은 상한).
        assertTrue(MathRenderService.preflightAllows(key("x".repeat(160), 51f)))
        assertFalse(MathRenderService.preflightAllows(key("x".repeat(161), 51f)))
    }

    // MARK: - RichMarkdownStreamingTextBuffer

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun streamingBuffer_latestWinsWithTrailingPublish() = runTest {
        val buffer = RichMarkdownStreamingTextBuffer(backgroundScope, 100.milliseconds, "", testScheduler.timeSource)

        buffer.submit("a")
        assertEquals("첫 제출은 즉시", "a", buffer.text.value)

        buffer.submit("ab")
        buffer.submit("abc")
        assertEquals("간격 안은 보류", "a", buffer.text.value)
        advanceTimeBy(100)
        runCurrent()
        assertEquals("간격 끝 trailing 게시는 마지막 값", "abc", buffer.text.value)

        advanceTimeBy(300)
        buffer.submit("abcd")
        assertEquals("간격 경과 뒤 제출은 즉시", "abcd", buffer.text.value)

        buffer.submit("abcde")
        buffer.flush()
        assertEquals("flush는 pending 즉시 게시", "abcde", buffer.text.value)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("flush 뒤 trailing 없음", "abcde", buffer.text.value)
    }
}
