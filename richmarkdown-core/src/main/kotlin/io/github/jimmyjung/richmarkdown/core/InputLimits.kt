// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

import java.text.BreakIterator
import java.util.Locale

/**
 * 입력 보호 (iOS DEVELOPMENT.md §6).
 * 원문 UTF-8 byte·block quote 깊이 상한은 첫 파싱 전에 검사한다.
 * 초과 시 내부 표시 상한까지 grapheme 경계로 자르고 marker를 붙인다.
 *
 * 상한은 iOS와 같은 UTF-8 byte 단위다(계약 동일성). 위치 계산만 UTF-16 code unit이다.
 */
@InternalRichMarkdownApi
public object InputLimits {
    // ponytail: 상한 수치는 P0 adversarial fixture/측정 전 잠정값. v1 공개 API로 고정하지 않는다.
    public const val MAX_INPUT_UTF8_BYTES: Int = 262_144 // 256 KiB
    public const val DISPLAY_PREFIX_UTF8_BYTES: Int = 65_536 // 초과 시 표시 상한 64 KiB
    public const val MAX_BLOCK_QUOTE_DEPTH: Int = 64
    public const val MAX_MATH_SOURCE_UTF8_BYTES: Int = 4_096 // 수식 엔진 호출 전 수식 source 상한
    public const val MAX_TABLE_COLUMNS: Int = 32
    public const val MAX_TABLE_CELLS: Int = 512
    public const val TRUNCATION_MARKER: String = "… [입력 제한 초과]"

    public data class BoundedInput(val text: String, val wasTruncated: Boolean)

    /** 상한 초과 입력을 grapheme 경계의 bounded prefix + 명시적 생략 marker로 바꾼다. */
    public fun bound(input: String): BoundedInput {
        val byteBounded = boundUtf8Bytes(input)
        val lineStart = firstLineExceedingBlockQuoteDepth(byteBounded.text) ?: return byteBounded
        return BoundedInput(
            text = truncatedText(byteBounded.text.substring(0, lineStart)),
            wasTruncated = true,
        )
    }

    private fun boundUtf8Bytes(input: String): BoundedInput {
        if (utf8Length(input, 0, input.length) <= MAX_INPUT_UTF8_BYTES) {
            return BoundedInput(text = input, wasTruncated = false)
        }

        val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT)
        graphemes.setText(input)
        var end = 0
        var byteCount = 0
        var start = graphemes.first()
        var next = graphemes.next()
        while (next != BreakIterator.DONE) {
            val charBytes = utf8Length(input, start, next)
            if (byteCount + charBytes > DISPLAY_PREFIX_UTF8_BYTES) break
            byteCount += charBytes
            end = next
            start = next
            next = graphemes.next()
        }
        return BoundedInput(text = truncatedText(input.substring(0, end)), wasTruncated = true)
    }

    /** `[start, end)` 구간의 UTF-8 byte 수. surrogate pair는 4 byte로 센다. */
    private fun utf8Length(text: CharSequence, start: Int, end: Int): Int {
        var bytes = 0
        var i = start
        while (i < end) {
            val c = text[i]
            when {
                c.code < 0x80 -> bytes += 1
                c.code < 0x800 -> bytes += 2
                c.isHighSurrogate() && i + 1 < end && text[i + 1].isLowSurrogate() -> {
                    bytes += 4
                    i += 1
                }
                else -> bytes += 3
            }
            i += 1
        }
        return bytes
    }

    /** Block quote는 파서와 내부 모델 모두 재귀로 처리하므로 parse 전에 제한한다. */
    private fun firstLineExceedingBlockQuoteDepth(input: String): Int? {
        var lineStart = 0
        var openFence: CodeFence? = null

        while (lineStart < input.length) {
            val lineEnd = lineTerminator(input, lineStart) ?: input.length
            val line = input.subSequence(lineStart, lineEnd)
            val quotePrefix = quotePrefix(line)

            val fence = openFence
            if (fence != null) {
                if (quotePrefix.depth < fence.quoteDepth) {
                    // quote container를 벗어나면 그 안에서 열었던 fence도 끝난다. 같은 줄을
                    // 일반 Markdown으로 재처리해 이후 깊은 quote 제한을 우회하지 못하게 한다.
                    openFence = null
                } else {
                    // fenced code 안의 `>`는 코드 문자일 뿐이라 block quote 재귀를 만들지 않는다.
                    // 같은 quote container에서만 닫는 fence로 인정해 코드 본문의 `> ``` `를
                    // 조기 종료로 오인하지 않는다.
                    if (quotePrefix.depth == fence.quoteDepth &&
                        closesFence(line.subSequence(quotePrefix.contentStart, line.length), fence)
                    ) {
                        openFence = null
                    }
                    lineStart = nextLineStart(input, lineEnd)
                    continue
                }
            }

            // opening fence가 깊은 quote 안에 있더라도 파서는 먼저 quote container를
            // 재귀로 만든다. 따라서 fence 판정보다 앞에서 depth를 제한해야 한다.
            if (quotePrefix.depth > MAX_BLOCK_QUOTE_DEPTH) return lineStart
            openingFence(line.subSequence(quotePrefix.contentStart, line.length), quotePrefix.depth)
                ?.let { openFence = it }
            lineStart = nextLineStart(input, lineEnd)
        }
        return null
    }

    /**
     * CR, LF, CRLF를 모두 한 줄 종료로 취급한다(iOS `Character.isNewline`과 같은 집합).
     * LF만 찾으면 depth 제한과 fence 종료가 우회될 수 있다.
     */
    private fun lineTerminator(input: String, start: Int): Int? {
        for (i in start until input.length) {
            if (isNewline(input[i])) return i
        }
        return null
    }

    private fun isNewline(c: Char): Boolean = when (c) {
        '\n', '\r', '', '', '', ' ', ' ' -> true
        else -> false
    }

    private fun nextLineStart(input: String, terminator: Int): Int {
        if (terminator >= input.length) return input.length
        // CRLF는 하나의 줄 종료다.
        if (input[terminator] == '\r' && terminator + 1 < input.length && input[terminator + 1] == '\n') {
            return terminator + 2
        }
        return terminator + 1
    }

    private class QuotePrefix(val depth: Int, val contentStart: Int)

    private class CodeFence(val character: Char, val length: Int, val quoteDepth: Int)

    private class FenceMarker(val character: Char, val length: Int, val suffixStart: Int)

    private fun quotePrefix(line: CharSequence): QuotePrefix {
        var index = 0
        var depth = 0

        while (index < line.length) {
            var candidate = index
            var spaces = 0
            while (candidate < line.length && line[candidate] == ' ' && spaces < 4) {
                spaces += 1
                candidate += 1
            }
            if (spaces >= 4 || candidate >= line.length || line[candidate] != '>') break

            depth += 1
            index = candidate + 1
            if (index < line.length && (line[index] == ' ' || line[index] == '\t')) index += 1
        }

        return QuotePrefix(depth = depth, contentStart = index)
    }

    private fun openingFence(content: CharSequence, quoteDepth: Int): CodeFence? {
        val marker = fenceMarker(content) ?: return null

        // CommonMark의 backtick info string에는 backtick을 다시 쓸 수 없다. 이 경우를
        // fence로 오인해 남은 입력의 depth 검사를 건너뛰면 안 된다.
        if (marker.character == '`' && content.subSequence(marker.suffixStart, content.length).contains('`')) {
            return null
        }
        return CodeFence(character = marker.character, length = marker.length, quoteDepth = quoteDepth)
    }

    private fun closesFence(content: CharSequence, fence: CodeFence): Boolean {
        val marker = fenceMarker(content) ?: return false
        if (marker.character != fence.character || marker.length < fence.length) return false
        return content.subSequence(marker.suffixStart, content.length).all { it == ' ' || it == '\t' }
    }

    /** 최대 3 spaces 뒤에 오는 3개 이상 backtick/tilde fence만 인식한다. */
    private fun fenceMarker(content: CharSequence): FenceMarker? {
        var index = 0
        var indentation = 0
        while (index < content.length && content[index] == ' ' && indentation < 3) {
            indentation += 1
            index += 1
        }

        if (index >= content.length) return null
        val character = content[index]
        if (character != '`' && character != '~') return null

        var length = 0
        while (index < content.length && content[index] == character) {
            length += 1
            index += 1
        }
        if (length < 3) return null
        return FenceMarker(character = character, length = length, suffixStart = index)
    }

    private fun truncatedText(prefix: String): String = prefix + "\n\n" + TRUNCATION_MARKER
}
