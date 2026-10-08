// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

/**
 * 인라인 Markdown(`**`, `*`, `_`, `<em>`, `~~`, `` ` ``)을 plain text + [InlineMark] 목록으로 분리하는 코덱.
 * iOS `InlineMarkdownCodec`.
 *
 * LaTeX-aware: `\(...\)`, `$...$`, `$$...$$` 구간 안의 `*`, `_`는 서식 구분자로 해석하지 않는다.
 * 코드 마크와 겹치는 다른 서식은 정규화 단계에서 잘린다. `*`는 flanking 검사가 없고 `_`만 단어 내부 규칙을 쓴다(iOS 동일).
 */
object InlineMarkdownCodec {
    /** [parse] 결과. iOS `(text:, marks:)` 튜플. */
    data class Parsed(val text: String, val marks: List<InlineMark>)

    fun parse(source: String): Parsed {
        val result = Parser(source).parse(closing = null)
        val text = result.text.toString()
        return Parsed(text, normalized(result.marks, text))
    }

    /** 서식을 정식 구분자(italic은 `<em>`)로 직렬화하고 리터럴 구분자는 escape한다. */
    fun serialize(text: String, marks: List<InlineMark>): String {
        val active = normalized(marks, text).filter { it.range.length > 0 }
        if (active.isEmpty()) return escaped(text, insideCode = false)

        val offsets = sortedSetOf(0, text.length).apply {
            for (mark in active) {
                add(mark.range.location)
                add(mark.range.end)
            }
        }.toList()
        val result = StringBuilder()
        var current = emptyList<InlineFormat>()
        for (i in 0 until offsets.lastIndex) {
            current = transition(current, activeFormats(offsets[i], active), result)
            result.append(escaped(text.substring(offsets[i], offsets[i + 1]), insideCode = InlineFormat.Code in current))
        }
        transition(current, emptyList(), result)
        return result.toString()
    }

    /**
     * 잘못된 범위·빈 범위를 버리고, 인라인 LaTeX 구간을 비코드 서식에서 빼고, 같은 서식의 겹침·인접을 합치고,
     * 코드 범위를 다른 서식에서 뺀 뒤 (위치 오름차순, 길이 내림차순, 서식 순서)로 정렬한다.
     */
    fun normalized(marks: List<InlineMark>, text: String): List<InlineMark> {
        if (marks.isEmpty()) return emptyList()
        val latexRanges = inlineLatexRanges(text)
        val valid = marks
            .filter { it.range.length > 0 && text.containsScalarAligned(it.range) }
            .flatMap { mark -> mark.excluding(if (mark.format == InlineFormat.Code) emptyList() else latexRanges) }
            .sortedWith(MARK_ORDER)

        val merged = mutableListOf<InlineMark>()
        for (format in InlineFormat.entries) {
            for (mark in valid) {
                if (mark.format != format) continue
                val last = merged.lastOrNull()
                if (last != null && last.format == format && mark.range.location <= last.range.end) {
                    val location = minOf(last.range.location, mark.range.location)
                    merged[merged.lastIndex] =
                        InlineMark(format, EditorRange(location, maxOf(last.range.end, mark.range.end) - location))
                } else {
                    merged += mark
                }
            }
        }
        val codeRanges = merged.filter { it.format == InlineFormat.Code }.map { it.range }
        return merged
            .flatMap { mark -> mark.excluding(if (mark.format == InlineFormat.Code) emptyList() else codeRanges) }
            .sortedWith(MARK_ORDER)
    }

    // MARK: - 정규화 도우미

    private val MARK_ORDER = compareBy<InlineMark> { it.range.location }
        .thenByDescending { it.range.length }
        .thenBy { it.format.ordinal }

    private fun InlineMark.excluding(exclusions: List<EditorRange>): List<InlineMark> {
        var segments = listOf(range)
        for (exclusion in exclusions) {
            segments = segments.flatMap { segment ->
                val start = maxOf(segment.location, exclusion.location)
                val end = minOf(segment.end, exclusion.end)
                if (end <= start) return@flatMap listOf(segment)
                buildList {
                    if (segment.location < start) add(EditorRange(segment.location, start - segment.location))
                    if (end < segment.end) add(EditorRange(end, segment.end - end))
                }
            }
        }
        return segments.map { InlineMark(format, it) }
    }

    private fun inlineLatexRanges(text: String): List<EditorRange> {
        val ranges = mutableListOf<EditorRange>()
        var index = 0
        while (index < text.length) {
            val end = inlineLatexEnd(text, index)
            if (end != null) {
                ranges += EditorRange(index, end - index)
                index = end
            } else {
                index += 1
            }
        }
        return ranges
    }

    /**
     * `index`에서 시작하는 한 줄짜리 인라인 LaTeX(`\(`, `$$`, `$`)의 끝 offset. 닫는 구분자가 없거나
     * 구간에 개행이 있으면 null. `$$`가 닫히지 않으면 `$`로 다시 시도하지 않는다(iOS 동일).
     */
    private fun inlineLatexEnd(source: String, index: Int): Int? {
        val (opening, closing) = when {
            source.startsWith("\\(", index) -> "\\(" to "\\)"
            source.startsWith("$$", index) -> "$$" to "$$"
            source.startsWith("$", index) -> "$" to "$"
            else -> return null
        }
        val closingStart = source.indexOf(closing, index + opening.length)
        if (closingStart < 0) return null
        val end = closingStart + closing.length
        for (i in index until end) if (source[i].isSwiftNewline()) return null
        return end
    }

    // MARK: - 직렬화 도우미

    private fun activeFormats(offset: Int, marks: List<InlineMark>): List<InlineFormat> {
        val active = marks.filter { it.range.location <= offset && offset < it.range.end }.map { it.format }.toSet()
        if (InlineFormat.Code in active) return listOf(InlineFormat.Code)
        return listOf(InlineFormat.Bold, InlineFormat.Italic, InlineFormat.Strikethrough).filter { it in active }
    }

    private fun transition(active: List<InlineFormat>, desired: List<InlineFormat>, result: StringBuilder): List<InlineFormat> {
        val common = active.zip(desired).takeWhile { (a, b) -> a == b }.size
        for (format in active.drop(common).asReversed()) result.append(format.closing)
        for (format in desired.drop(common)) result.append(format.opening)
        return desired
    }

    private fun escaped(source: String, insideCode: Boolean): String {
        val targets = if (insideCode) "`" else "*_~`<"
        val result = StringBuilder()
        var index = 0
        while (index < source.length) {
            if (!insideCode) {
                val latexEnd = inlineLatexEnd(source, index)
                if (latexEnd != null) {
                    result.append(source, index, latexEnd)
                    index = latexEnd
                    continue
                }
            }
            val character = source[index]
            val next = source.getOrNull(index + 1)
            if (character == '\\' && next != null && (next in targets || next == '\\' || !insideCode && isEscapable(next))) {
                result.append("\\\\")
            } else {
                if (character in targets) result.append('\\')
                result.append(character)
            }
            index += 1
        }
        return result.toString()
    }

    private fun isEscapable(character: Char): Boolean = character in "\\*_~`<#-+>."

    // MARK: - 파서

    private class Token(val format: InlineFormat, val opening: String, val closing: String)

    private val CODE_TOKEN = Token(InlineFormat.Code, "`", "`")
    private val BOLD_TOKEN = Token(InlineFormat.Bold, "**", "**")
    private val STRIKETHROUGH_TOKEN = Token(InlineFormat.Strikethrough, "~~", "~~")
    private val EM_TOKEN = Token(InlineFormat.Italic, "<em>", "</em>")
    private val UNDERSCORE_TOKEN = Token(InlineFormat.Italic, "_", "_")
    private val STAR_TOKEN = Token(InlineFormat.Italic, "*", "*")

    /** iOS 검사 순서: code → bold → strikethrough → `<em>` → `_` → `*`. */
    private val DELIMITED_TOKENS = listOf(CODE_TOKEN, BOLD_TOKEN, STRIKETHROUGH_TOKEN, EM_TOKEN)

    private class Result {
        val text = StringBuilder()
        val marks = mutableListOf<InlineMark>()
        var closed = false
    }

    /**
     * iOS `parse(_:index:closing:)`의 재귀 하강 파서. 중첩 서식마다 재귀하고 여는 구분자마다 닫는 구분자를 끝까지 찾는다.
     * ponytail: iOS와 같이 중첩 깊이 제한이 없고(`<em>` 수만 개 중첩 시 스택 한계) 미닫힘 opener가 많으면 O(n²)다. 입력 상한이 필요해지면 재검토.
     */
    private class Parser(private val source: String) {
        private var index = 0

        fun parse(closing: String?): Result {
            val result = Result()
            while (index < source.length) {
                val latexEnd = inlineLatexEnd(source, index)
                if (latexEnd != null) {
                    result.text.append(source, index, latexEnd)
                    index = latexEnd
                    continue
                }
                if (source[index] == '\\' && index + 1 < source.length && isEscapable(source[index + 1])) {
                    result.text.append(source[index + 1])
                    index += 2
                    continue
                }
                if (closing != null && source.startsWith(closing, index)) {
                    index += closing.length
                    result.closed = true
                    return result
                }
                val token = openingToken(index)
                if (token == null || closingStart(token.closing, index + token.opening.length) == null) {
                    result.text.append(source[index])
                    index += 1
                    continue
                }
                index += token.opening.length
                val start = result.text.length
                val codeEnd = if (token.format == InlineFormat.Code) closingStart(token.closing, index) else null
                if (codeEnd != null) {
                    result.text.append(decodedCodeEscapes(source.substring(index, codeEnd)))
                    index = codeEnd + token.closing.length
                } else {
                    val nested = parse(token.closing)
                    if (!nested.closed) {
                        result.text.append(token.opening).append(nested.text)
                        nested.marks.mapTo(result.marks) { it.shifted(start + token.opening.length) }
                        continue
                    }
                    result.text.append(nested.text)
                    nested.marks.mapTo(result.marks) { it.shifted(start) }
                }
                result.marks += InlineMark(token.format, EditorRange(start, result.text.length - start))
            }
            return result
        }

        private fun openingToken(at: Int): Token? {
            for (token in DELIMITED_TOKENS) {
                if (source.startsWith(token.opening, at)) return token
            }
            return when (source[at]) {
                '_' -> {
                    if (at + 1 >= source.length) return null
                    val next = source.codePointAt(at + 1)
                    if (isSwiftWhitespace(next)) return null
                    // ponytail: Swift는 앞 Character의 첫 scalar를 보지만 여기서는 바로 앞 code point를 본다(결합 문자에서만 차이).
                    if (at > 0 && isSwiftWord(source.codePointBefore(at)) && isSwiftWord(next)) return null
                    UNDERSCORE_TOKEN
                }
                '*' -> STAR_TOKEN
                else -> null
            }
        }

        private fun closingStart(delimiter: String, from: Int): Int? {
            var cursor = from
            while (cursor < source.length) {
                if (source[cursor] == '\\' && cursor + 1 < source.length && isEscapable(source[cursor + 1])) {
                    cursor += 2
                    continue
                }
                if (source.startsWith(delimiter, cursor)) return cursor
                cursor += 1
            }
            return null
        }

        private fun decodedCodeEscapes(code: String): String {
            val result = StringBuilder()
            var i = 0
            while (i < code.length) {
                if (code[i] == '\\' && i + 1 < code.length && (code[i + 1] == '`' || code[i + 1] == '\\')) {
                    result.append(code[i + 1])
                    i += 2
                } else {
                    result.append(code[i])
                    i += 1
                }
            }
            return result.toString()
        }
    }
}
