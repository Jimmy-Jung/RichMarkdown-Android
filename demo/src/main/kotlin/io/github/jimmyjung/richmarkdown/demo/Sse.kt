// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 바이트 스트림을 SSE 줄로 나눈다. LF·CRLF·CR을 처리하고 **빈 줄을 보존**한다
 * (iOS `SSEDemo.swift` `SSELineSplitter`).
 *
 * `BufferedReader.readLine()`류로 대체하면 안 된다 — CR/LF 조합만 처리하고 빈 줄 판별이 흐려지며,
 * iOS `AsyncLineSequence`처럼 빈 줄을 건너뛰는 reader는 SSE 이벤트 경계를 잃어 이벤트가 한 번도
 * dispatch되지 않는다. 선두 BOM은 제거한다 (HTML Living Standard 9.2).
 */
class SseLineSplitter {
    private val buffer = ByteArrayOutputStream()
    private var pendingCarriageReturn = false
    private var strippedByteOrderMark = false

    /** 바이트 하나를 넣는다. 줄이 끝나면 그 줄을 돌려준다. */
    fun consume(byte: Byte): String? {
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false
            // CRLF의 LF는 이미 끝낸 줄의 일부다.
            if (byte == LF) return null
        }
        return when (byte) {
            LF -> takeLine()
            CR -> {
                pendingCarriageReturn = true
                takeLine()
            }
            else -> {
                buffer.write(byte.toInt())
                // 개행 없이 계속 보내는 엔드포인트에서 버퍼가 무한히 자라지 않게 한다.
                if (buffer.size() >= MAX_LINE_BYTES) takeLine() else null
            }
        }
    }

    /** 스트림이 끝났을 때 남은 조각. 마지막 줄에 개행이 없을 수 있다. */
    fun flush(): String? = if (buffer.size() == 0) null else takeLine()

    private fun takeLine(): String {
        var line = buffer.toString("UTF-8")
        buffer.reset()
        if (!strippedByteOrderMark) {
            strippedByteOrderMark = true
            line = line.removePrefix("\uFEFF")
        }
        return line
    }

    private companion object {
        const val LF: Byte = 0x0A
        const val CR: Byte = 0x0D
        const val MAX_LINE_BYTES = 1 shl 16
    }
}

/**
 * `text/event-stream` 프레임 디코더 (W3C EventSource의 부분집합, iOS `SSEDecoder`).
 * `data` 필드만 모으고 `event`/`id`/`retry`는 무시한다. 줄 단위 동기 상태 머신이라 네트워크 없이 테스트한다.
 */
class SseDecoder {
    sealed interface Event {
        /** 누적 답변에 이어 붙일 텍스트 조각. */
        data class Text(val delta: String) : Event

        /** 서버가 보낸 오류 payload. 스트림을 끝내고 이유를 보여준다. */
        data class Failure(val message: String) : Event

        /** `data: [DONE]` 종료 신호. */
        data object Done : Event
    }

    private val dataLines = mutableListOf<String>()

    /** 한 줄을 넣는다. 이벤트가 완성되면 돌려주고, 아직이면 `null`이다. */
    fun consume(line: String): Event? {
        if (line.isEmpty()) return dispatch()
        // 콜론으로 시작하는 줄은 주석(heartbeat)이다.
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        if (colon < 0) return null // 필드 이름만 있는 줄 — data가 아니다.
        val name = line.substring(0, colon)
        // 값 앞의 공백은 **하나만** 제거한다 (W3C).
        val value = line.substring(colon + 1).removePrefix(" ")
        if (name == "data") dataLines += value
        return null
    }

    /** 마지막 blank line 없이 연결이 끊긴 스트림에서 남은 이벤트를 꺼낸다. */
    fun finish(): Event? = dispatch()

    private fun dispatch(): Event? {
        if (dataLines.isEmpty()) return null
        val payload = dataLines.joinToString("\n")
        dataLines.clear()
        // 종료 표식은 게이트웨이마다 공백이 붙는다.
        if (payload.trim() == "[DONE]") return Event.Done
        return eventFromPayload(payload)
        // ponytail: 규격은 값이 빈 이벤트도 발행하지만 렌더에 영향이 없어 만들지 않는다.
    }

    companion object {
        /**
         * payload에서 텍스트 델타나 오류를 뽑는다. 게이트웨이마다 모양이 달라 여러 갈래를 받는다.
         * - OpenAI chat completions — `choices[0].delta.content` (문자열 또는 content-part 배열)
         * - OpenAI completions — `choices[0].text`
         * - OpenAI Responses — `delta` (문자열)
         * - Anthropic Messages — `delta.text`
         * - 오류 — `error.message` 또는 `error` 문자열
         * - JSON이 아닌 순수 텍스트 SSE — payload 자체
         */
        fun eventFromPayload(payload: String): Event? {
            val looksLikeJson = payload.startsWith("{") || payload.startsWith("[")
            if (!looksLikeJson) return if (payload.isEmpty()) null else Event.Text(payload)
            // JSON처럼 생겼는데 파싱에 실패했다면 잘린 조각이다. 답변에 섞지 않는다.
            val json = try {
                JSONObject(payload)
            } catch (_: JSONException) {
                return null
            }
            json.opt("error")?.let { error ->
                val message = (error as? JSONObject)?.optString("message")?.takeIf { it.isNotEmpty() }
                    ?: (error as? String)
                    ?: "알 수 없는 오류"
                return Event.Failure(message)
            }
            val text = deltaText(json) ?: return null
            return if (text.isEmpty()) null else Event.Text(text)
        }

        private fun deltaText(json: JSONObject): String? {
            json.optJSONArray("choices")?.optJSONObject(0)?.let { choice ->
                choice.optJSONObject("delta")?.let { delta -> content(delta)?.let { return it } }
                if (choice.has("text")) return choice.optString("text")
            }
            json.optJSONObject("delta")?.let { delta -> return if (delta.has("text")) delta.optString("text") else null }
            (json.opt("delta") as? String)?.let { return it }
            return null
        }

        /** `content`는 문자열이거나 content-part 배열이다. */
        private fun content(delta: JSONObject): String? {
            val content = delta.opt("content")
            if (content is String) return content
            if (content !is JSONArray) return null
            return buildString {
                for (i in 0 until content.length()) content.optJSONObject(i)?.optString("text")?.let(::append)
            }.ifEmpty { null }
        }
    }
}

/** 로컬 시뮬레이션 fixture (iOS `SSEDemoFixtures`). 네트워크 없이 같은 디코더 경로를 통과시킨다. */
object SseFixtures {
    /** 본문을 `chunkSize` grapheme씩 OpenAI 호환 프레임 `data: {…}\n\n`으로 만들고 `data: [DONE]\n\n`으로 끝낸다. */
    fun frames(text: String = SampleMarkdown.streamingAnswer, chunkSize: Int = 6): List<String> =
        SampleMarkdown.graphemeChunks(text, chunkSize).map { "data: ${jsonPayload(it)}\n\n" } + "data: [DONE]\n\n"

    private fun jsonPayload(delta: String): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", delta))))
        .toString()
}
