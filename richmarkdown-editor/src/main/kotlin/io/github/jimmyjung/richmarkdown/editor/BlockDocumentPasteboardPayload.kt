// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/**
 * 블록 구조를 손실 없이 복사·붙여넣기하기 위한 클립보드 표현 (UTF-8 JSON, version 1). iOS `BlockDocumentPasteboardPayload`.
 *
 * 형태: `{"version":1,"blocks":[{"kind":{"heading":{"level":2}},"text":"…","inlineMarks":[{"format":1,"range":[0,1]}],"indentLevel":0}]}`.
 * 종류는 Swift Codable 합성 형태(`{"paragraph":{}}`, `{"code":{}}`는 언어 없음)이고 블록 UUID는 싣지 않는다.
 * 최대 256 KiB이며 [decode]는 제목 1...3·들여쓰기 0...3으로 clamp하고 서식을 정규화해 조작된 payload를 방어한다.
 * 저장 포맷이나 서버 API가 아니다.
 */
object BlockDocumentPasteboardPayload {
    private const val VERSION = 1
    private const val MAXIMUM_BYTES = 256 * 1024

    /** 스키마 깊이는 6이다. 더 깊은 미지 값은 거절해 재귀 파서의 스택을 보호한다. */
    private const val MAXIMUM_DEPTH = 16

    fun encode(blocks: List<EditorBlock>): ByteArray? {
        if (blocks.isEmpty()) return null
        val json = buildString {
            append("{\"version\":$VERSION,\"blocks\":[")
            blocks.forEachIndexed { index, block ->
                if (index > 0) append(',')
                append("{\"kind\":")
                appendKind(block.kind)
                append(",\"text\":")
                appendJsonString(block.text)
                append(",\"inlineMarks\":[")
                block.inlineMarks.forEachIndexed { markIndex, mark ->
                    if (markIndex > 0) append(',')
                    append("{\"format\":${mark.format.ordinal},\"range\":[${mark.range.location},${mark.range.length}]}")
                }
                append("],\"indentLevel\":${block.indentLevel}}")
            }
            append("]}")
        }
        return json.toByteArray(Charsets.UTF_8).takeIf { it.size <= MAXIMUM_BYTES }
    }

    /** 크기·UTF-8·JSON 문법·version·필수 필드 중 하나라도 어긋나면 null. 블록마다 새 UUID를 발급한다. */
    fun decode(data: ByteArray): List<EditorBlock>? {
        if (data.size > MAXIMUM_BYTES) return null
        return try {
            val json = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString()
            val root = JsonReader(json).document() as? Map<*, *> ?: return null
            if (root["version"].asInt() != VERSION) return null
            val blocks = root["blocks"] as? List<*> ?: return null
            if (blocks.isEmpty()) return null
            blocks.map { decodeBlock(it) ?: return null }
        } catch (_: CharacterCodingException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // MARK: - 인코딩

    private fun StringBuilder.appendKind(kind: EditorBlockKind) {
        when (kind) {
            EditorBlockKind.Paragraph -> append("{\"paragraph\":{}}")
            is EditorBlockKind.Heading -> append("{\"heading\":{\"level\":${kind.level}}}")
            EditorBlockKind.BulletedList -> append("{\"bulletedList\":{}}")
            EditorBlockKind.NumberedList -> append("{\"numberedList\":{}}")
            is EditorBlockKind.ToDo -> append("{\"toDo\":{\"isChecked\":${kind.isChecked}}}")
            EditorBlockKind.Quote -> append("{\"quote\":{}}")
            is EditorBlockKind.Code -> {
                append("{\"code\":{")
                kind.language?.let { append("\"language\":").appendJsonString(it) }
                append("}}")
            }
            EditorBlockKind.Equation -> append("{\"equation\":{}}")
        }
    }

    /** 제어 문자·따옴표·역슬래시와 짝 없는 surrogate만 escape한다. 나머지는 UTF-8 그대로 싣는다. */
    private fun StringBuilder.appendJsonString(value: String): StringBuilder {
        append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index]
            val pairs = character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()
            when {
                pairs -> append(character).append(value[++index])
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character < ' ' || character.isSurrogate() -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
            index += 1
        }
        return append('"')
    }

    // MARK: - 디코딩 (Swift JSONDecoder 규칙: 필수 필드 누락·타입 불일치는 전체 실패, 미지 키는 무시)

    private val KIND_KEYS = setOf("paragraph", "heading", "bulletedList", "numberedList", "toDo", "quote", "code", "equation")

    private fun decodeBlock(value: Any?): EditorBlock? {
        val block = value as? Map<*, *> ?: return null
        val kind = decodeKind(block["kind"]) ?: return null
        val text = block["text"] as? String ?: return null
        val marks = (block["inlineMarks"] as? List<*> ?: return null).map { decodeMark(it) ?: return null }
        val indentLevel = block["indentLevel"].asInt() ?: return null
        val safeKind = if (kind is EditorBlockKind.Heading) EditorBlockKind.Heading(kind.level.coerceIn(1, 3)) else kind
        return EditorBlock(
            kind = safeKind,
            text = text,
            inlineMarks = marks,
            indentLevel = if (safeKind.supportsIndentation) indentLevel.coerceIn(0, 3) else 0,
        )
    }

    /** Swift 합성 enum Codable: 알려진 case 키가 정확히 하나이고 값은 객체여야 한다. */
    private fun decodeKind(value: Any?): EditorBlockKind? {
        val container = value as? Map<*, *> ?: return null
        val key = container.keys.filterIsInstance<String>().singleOrNull { it in KIND_KEYS } ?: return null
        val fields = container[key] as? Map<*, *> ?: return null
        return when (key) {
            "paragraph" -> EditorBlockKind.Paragraph
            "heading" -> EditorBlockKind.Heading(fields["level"].asInt() ?: return null)
            "bulletedList" -> EditorBlockKind.BulletedList
            "numberedList" -> EditorBlockKind.NumberedList
            "toDo" -> EditorBlockKind.ToDo(fields["isChecked"] as? Boolean ?: return null)
            "quote" -> EditorBlockKind.Quote
            "code" -> {
                val language = fields["language"]
                if (language != null && language !is String) return null
                EditorBlockKind.Code(language)
            }
            else -> EditorBlockKind.Equation
        }
    }

    /** `range`는 Foundation `NSRange` Codable 형태 `[location, length]`. 범위 검증은 정규화가 맡는다. */
    private fun decodeMark(value: Any?): InlineMark? {
        val mark = value as? Map<*, *> ?: return null
        val format = InlineFormat.entries.getOrNull(mark["format"].asInt() ?: return null) ?: return null
        val range = mark["range"] as? List<*> ?: return null
        if (range.size < 2) return null
        return InlineMark(format, EditorRange(range[0].asInt() ?: return null, range[1].asInt() ?: return null))
    }

    /**
     * JSON 정수(정수 값 실수 포함)를 Int로 읽는다. Int 범위를 넘는 값은 경계로 clamp한다 —
     * 범위 밖 offset은 어차피 무효라 정규화가 버리고, 제목·들여쓰기는 clamp되므로 iOS(64-bit Int) 결과와 같다.
     */
    private fun Any?.asInt(): Int? {
        val long = when (this) {
            is Long -> this
            is Double -> if (this % 1.0 == 0.0 && this >= Long.MIN_VALUE && this <= Long.MAX_VALUE) toLong() else return null
            else -> return null
        }
        return long.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** RFC 8259 문법만 받는 최소 JSON 리더. 오류는 IllegalArgumentException. 객체는 Map, 배열은 List, 정수는 Long. */
    private class JsonReader(private val source: String) {
        private var index = 0

        fun document(): Any? = value(depth = 1).also {
            skipWhitespace()
            require(index == source.length)
        }

        private fun value(depth: Int): Any? {
            require(depth <= MAXIMUM_DEPTH)
            skipWhitespace()
            require(index < source.length)
            return when (source[index]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            index += 1
            val result = HashMap<String, Any?>()
            skipWhitespace()
            if (consume('}')) return result
            do {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                require(consume(':'))
                result[key] = value(depth + 1)
                skipWhitespace()
            } while (consume(','))
            require(consume('}'))
            return result
        }

        private fun arrayValue(depth: Int): List<Any?> {
            index += 1
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (consume(']')) return result
            do {
                result += value(depth + 1)
                skipWhitespace()
            } while (consume(','))
            require(consume(']'))
            return result
        }

        private fun string(): String {
            require(consume('"'))
            val result = StringBuilder()
            while (true) {
                require(index < source.length)
                val character = source[index++]
                when {
                    character == '"' -> return result.toString()
                    character < ' ' -> throw IllegalArgumentException("escape되지 않은 제어 문자")
                    character != '\\' -> result.append(character)
                    else -> {
                        require(index < source.length)
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                var code = 0
                                repeat(4) {
                                    require(index < source.length)
                                    val digit = Character.digit(source[index++], 16)
                                    require(digit >= 0)
                                    code = code * 16 + digit
                                }
                                result.append(code.toChar())
                            }
                            else -> throw IllegalArgumentException("알 수 없는 escape")
                        }
                    }
                }
            }
        }

        private fun number(): Any {
            val start = index
            consume('-')
            if (!consume('0')) require(digits() > 0)
            var integral = true
            if (consume('.')) {
                integral = false
                require(digits() > 0)
            }
            if (consume('e') || consume('E')) {
                integral = false
                if (!consume('+')) consume('-')
                require(digits() > 0)
            }
            val text = source.substring(start, index)
            return if (integral) requireNotNull(text.toLongOrNull()) else text.toDouble()
        }

        private fun digits(): Int {
            val start = index
            while (index < source.length && source[index] in '0'..'9') index += 1
            return index - start
        }

        private fun literal(text: String, value: Boolean?): Boolean? {
            require(source.startsWith(text, index))
            index += text.length
            return value
        }

        private fun consume(character: Char): Boolean {
            if (index < source.length && source[index] == character) {
                index += 1
                return true
            }
            return false
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in " \t\n\r") index += 1
        }
    }
}
