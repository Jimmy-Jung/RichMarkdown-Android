// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import io.github.jimmyjung.richmarkdown.core.InputLimits
import io.github.jimmyjung.richmarkdown.core.ParsedBlock
import io.github.jimmyjung.richmarkdown.core.ParsedDocument
import io.github.jimmyjung.richmarkdown.core.RichMarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
        MathRenderKey(latex, fontSizePx, 0xFF000000.toInt(), isDisplay = false)

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
        // 원문 byte 제한은 폰트 크기와 무관하게 parse 전에 적용한다.
        assertTrue(MathRenderService.preflightAllows(key("x".repeat(max), 2f)))
        assertFalse(MathRenderService.preflightAllows(key("x".repeat(max + 1), 2f)))
        assertFalse("UTF-8 byte 기준", MathRenderService.preflightAllows(key("한".repeat(max / 3 + 1), 1f)))
    }

    @Test
    fun preflightDoesNotTreatCommandBytesAsPixelWidth() {
        val latex = "\\underbrace{\\begin{array}{cc}" + "a&b\\\\".repeat(32) + "\\end{array}}_{q}"
        assertTrue(latex.toByteArray().size > 160)
        assertTrue(MathRenderService.preflightAllows(key(latex, 51f)))
        // 실제 크기 상한은 Android MathRenderServiceTest에서 renderer 결과로 검사한다.
        assertTrue(MathRenderService.preflightAllows(key("x".repeat(161), 51f)))
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

    // MARK: - 렌더 모델 게시 (Main = 테스트 dispatcher, parse는 실제 Default 스레드)

    /** [body]에 모델과 게시 기록(초기값 제외)을 넘긴다. 수집기는 Unconfined라 `_state` 대입 순간 기록된다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.withModel(
        body: suspend (RichMarkdownRenderModel, MutableList<RichMarkdownRenderModel.State>) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = RichMarkdownRenderModel(backgroundScope)
            val published = ArrayList<RichMarkdownRenderModel.State>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { published += it } }
            published.clear()
            body(model, published)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * 스트리밍 append는 parse가 게시될 때까지 상태를 내보내지 않는다. 문서가 있는 동안에는 어느 렌더러도
     * fallback 원문을 읽지 않는다 — fallback만 바꾼 게시는 tick마다 재구성·rebuild를 한 번 더 일으킨다.
     */
    @Test
    fun streamingAppend_publishesNothingUntilParseLands() = runTest {
        withModel { model, published ->
            model.submit(request("# 제목"))
            model.awaitIdle()
            val shown = model.state.value
            assertEquals(identity("# 제목"), shown.parseIdentity)

            published.clear()
            model.submit(request("# 제목\n\n본문"))
            assertSame("append 직후 상태는 그대로다", shown, model.state.value)
            assertTrue("append 직후 게시가 없어야 한다: $published", published.isEmpty())

            model.awaitIdle()
            assertEquals("parse 게시 1회", 1, published.size)
            assertEquals(identity("# 제목\n\n본문"), published.single().parseIdentity)
        }
    }

    /**
     * parse 게시는 이전 문서의 같은 위치·같은 값 블록을 이전 인스턴스로 바꿔 끼운다(인용·리스트 자식까지).
     * Compose strong skipping(===)과 StateFlow 동등 비교가 바뀌지 않은 블록에서 바로 끝난다. 값은 새 parse와 같다.
     */
    @Test
    fun streamingAppend_reusesUnchangedBlockInstances() = runTest {
        withModel { model, _ ->
            suspend fun publish(markdown: String): ParsedDocument {
                model.submit(request(markdown))
                model.awaitIdle()
                val document = model.state.value.document!!
                assertEquals("새 parse와 같아야 한다", RichMarkdownParser.parse(InputLimits.bound(markdown), LatexDollarMathOptions.None.toCore()), document)
                return document
            }
            val a = publish("# 제목\n\n> 인용\n>\n> 둘")
            val b = publish("# 제목\n\n> 인용\n>\n> 둘째\n\n- 하나\n- 둘")
            val full = "# 제목\n\n> 인용\n>\n> 둘째\n\n- 하나\n- 둘\n- 셋"
            val c = publish(full)

            assertSame("바뀌지 않은 헤딩", a.blocks[0], b.blocks[0])
            assertSame("바뀐 인용의 바뀌지 않은 자식", quote(a).children[0], quote(b).children[0])
            assertSame("바뀌지 않은 인용 전체", b.blocks[1], c.blocks[1])
            val before = (b.blocks[2] as ParsedBlock.UnorderedList).items
            val after = (c.blocks[2] as ParsedBlock.UnorderedList).items
            assertSame("바뀐 리스트의 바뀌지 않은 항목", before[0], after[0])
            assertSame(before[1], after[1])

            model.submit(request(full, fontSizePx = 40f))
            model.awaitIdle()
            assertSame("같은 문서(수식 설정만 변경)는 이전 문서 그대로", c, model.state.value.document)
        }
    }

    private fun quote(document: ParsedDocument) = document.blocks[1] as ParsedBlock.BlockQuote
}
