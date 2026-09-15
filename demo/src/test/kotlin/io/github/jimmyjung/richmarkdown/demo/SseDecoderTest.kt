// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import org.junit.Assert.assertEquals
import org.junit.Test

/** SSE 프레임 경계·줄 끝·BOM·주석 처리. JSON 갈래는 android.jar stub이라 순수 텍스트 payload로만 확인한다. */
class SseDecoderTest {

    private fun decode(raw: String, finish: Boolean = false): List<SseDecoder.Event> {
        val splitter = SseLineSplitter()
        val decoder = SseDecoder()
        val events = mutableListOf<SseDecoder.Event>()
        for (byte in raw.toByteArray()) splitter.consume(byte)?.let(decoder::consume)?.let(events::add)
        if (finish) {
            splitter.flush()?.let(decoder::consume)?.let(events::add)
            decoder.finish()?.let(events::add)
        }
        return events
    }

    @Test
    fun blankLineIsTheEventBoundary_acrossCrlfCommentsAndBom() {
        val events = decode("﻿: ping\r\ndata: hello\r\ndata: world\n\ndata:  spaced\r\rdata: [DONE] \n\n")
        assertEquals(
            listOf(
                SseDecoder.Event.Text("hello\nworld"),
                SseDecoder.Event.Text(" spaced"),
                SseDecoder.Event.Done,
            ),
            events,
        )
    }

    @Test
    fun finishFlushesTheLastEventWithoutTrailingBlankLine() {
        assertEquals(emptyList<SseDecoder.Event>(), decode("data: tail"))
        assertEquals(listOf(SseDecoder.Event.Text("tail")), decode("data: tail", finish = true))
    }

    @Test
    fun graphemeChunksKeepFlagEmojiIntact() {
        assertEquals(listOf("ab", "🇰🇷c", "d"), SampleMarkdown.graphemeChunks("ab🇰🇷cd", size = 2))
    }
}
