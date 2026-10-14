// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.core.fixtures

/**
 * 테스트 전용 최소 JSON 읽기·쓰기. 테스트 classpath에 JSON 라이브러리가 없어(commonmark·coroutines·junit뿐)
 * 의존성을 늘리지 않으려고 직접 둔다. 값 표현: `Map<String, Any?>`(삽입 순서 유지)·`List<Any?>`·`String`·
 * 정수는 `Long`·실수는 `Double`·`Boolean`·`null`. 같은 문서를 다시 파싱하면 `==`로 구조 비교가 된다.
 */
internal object MiniJson {

    fun parse(text: String): Any? = Reader(text).document()

    fun write(value: Any?): String = buildString { writeValue(value) }

    // MARK: - 쓰기 (RFC 8259 이스케이프)

    private fun StringBuilder.writeValue(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean, is Int, is Long, is Double -> append(value.toString())
            is Number -> append(value.toString())
            is String -> writeString(value)
            is Map<*, *> -> writeObject(value)
            is List<*> -> writeArray(value)
            else -> error("JSON으로 쓸 수 없는 값: ${value::class}")
        }
    }

    private fun StringBuilder.writeObject(map: Map<*, *>) {
        append('{')
        var first = true
        for ((key, value) in map) {
            if (!first) append(',')
            first = false
            writeString(key as String)
            append(':')
            writeValue(value)
        }
        append('}')
    }

    private fun StringBuilder.writeArray(list: List<*>) {
        append('[')
        list.forEachIndexed { index, value ->
            if (index > 0) append(',')
            writeValue(value)
        }
        append(']')
    }

    private fun StringBuilder.writeString(text: String) {
        append('"')
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c.code < 0x20 -> appendUnicodeEscape(c)
                // 짝 없는 surrogate는 UTF-8로 쓸 수 없으므로 이스케이프한다.
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    append(c).append(text[i + 1])
                    i += 1
                }
                c.isSurrogate() -> appendUnicodeEscape(c)
                else -> append(c)
            }
            i += 1
        }
        append('"')
    }

    private fun StringBuilder.appendUnicodeEscape(c: Char) {
        append("\\u").append(c.code.toString(16).padStart(4, '0'))
    }

    // MARK: - 읽기 (재귀 하강)

    private class Reader(private val text: String) {
        private var pos = 0

        fun document(): Any? {
            val value = value()
            skipWhitespace()
            check(pos == text.length) { "JSON 끝에 남은 문자: ${describe()}" }
            return value
        }

        private fun value(): Any? {
            skipWhitespace()
            check(pos < text.length) { "JSON 값이 없다 (${describe()})" }
            return when (val c = text[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> number()
                else -> error("예상 밖 문자 '$c' (${describe()})")
            }
        }

        private fun obj(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            pos += 1
            skipWhitespace()
            if (peek() == '}') { pos += 1; return result }
            while (true) {
                skipWhitespace()
                check(peek() == '"') { "객체 키는 문자열이어야 한다 (${describe()})" }
                val key = string()
                skipWhitespace()
                expect(':')
                result[key] = value()
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> error("객체 안에서 ',' 또는 '}' 기대 (${describe()})")
                }
            }
        }

        private fun array(): List<Any?> {
            val result = ArrayList<Any?>()
            pos += 1
            skipWhitespace()
            if (peek() == ']') { pos += 1; return result }
            while (true) {
                result.add(value())
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> error("배열 안에서 ',' 또는 ']' 기대 (${describe()})")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                check(pos < text.length) { "닫히지 않은 문자열" }
                when (val c = text[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> out.append(escape())
                    else -> out.append(c)
                }
            }
        }

        private fun escape(): Char = when (val c = next()) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                check(pos + 4 <= text.length) { "\\u 이스케이프가 짧다" }
                val code = text.substring(pos, pos + 4).toInt(16)
                pos += 4
                code.toChar()
            }
            else -> error("알 수 없는 이스케이프 '\\$c' (${describe()})")
        }

        private fun number(): Number {
            val start = pos
            if (peek() == '-') pos += 1
            while (pos < text.length && text[pos].isDigit()) pos += 1
            var isDouble = false
            if (peek() == '.') { isDouble = true; pos += 1; while (pos < text.length && text[pos].isDigit()) pos += 1 }
            if (peek() == 'e' || peek() == 'E') {
                isDouble = true
                pos += 1
                if (peek() == '+' || peek() == '-') pos += 1
                while (pos < text.length && text[pos].isDigit()) pos += 1
            }
            val literal = text.substring(start, pos)
            return if (isDouble) literal.toDouble() else literal.toLong()
        }

        private fun literal(word: String, value: Any?): Any? {
            check(text.startsWith(word, pos)) { "리터럴 '$word' 기대 (${describe()})" }
            pos += word.length
            return value
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos += 1
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun next(): Char {
            check(pos < text.length) { "JSON이 갑자기 끝났다" }
            return text[pos++]
        }

        private fun expect(c: Char) {
            check(next() == c) { "'$c' 기대 (${describe()})" }
        }

        private fun describe(): String = "offset $pos"
    }
}
