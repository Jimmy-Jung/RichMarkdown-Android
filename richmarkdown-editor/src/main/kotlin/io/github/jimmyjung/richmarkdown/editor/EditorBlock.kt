// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import java.util.UUID

// MARK: - 범위·선택

/**
 * 블록 원문 또는 연속 문서 안의 UTF-16 code unit 범위. iOS `NSRange` 대응.
 *
 * 공개 API가 잘못된 입력을 거절해야 하므로 음수·문서 밖 값도 표현한다. [end]는 검증을 통과한
 * 범위에서만 의미가 있다(검증 전 큰 값은 overflow할 수 있다). 코어의 `Utf16Range`는 내부 API라 쓰지 않는다.
 */
data class EditorRange(val location: Int, val length: Int) {
    /** iOS `NSMaxRange`. */
    val end: Int get() = location + length
}

/** 블록 내부 UTF-16 range 기반 선택. iOS `BlockSelection`. */
data class BlockSelection(val blockId: UUID, val range: EditorRange)

// MARK: - 블록 종류

/**
 * Notion 스타일 블록의 종류. iOS `EditorBlockKind`.
 *
 * 표시용 문자열(제목·아이콘)은 UI 레이어의 책임이므로 여기에 두지 않는다.
 */
sealed interface EditorBlockKind {
    data object Paragraph : EditorBlockKind

    /** 제목. 파싱·직렬화는 1...3 레벨만 지원하며 [EditorBlock]이 범위를 맞춘다. */
    data class Heading(val level: Int) : EditorBlockKind
    data object BulletedList : EditorBlockKind
    data object NumberedList : EditorBlockKind
    data class ToDo(val isChecked: Boolean) : EditorBlockKind
    data object Quote : EditorBlockKind
    data class Code(val language: String? = null) : EditorBlockKind
    data object Equation : EditorBlockKind

    /** Enter로 블록을 나눌 때 뒤쪽 블록이 이어받는 종류. */
    val continuationKind: EditorBlockKind
        get() = when (this) {
            BulletedList, NumberedList, is ToDo -> this
            else -> Paragraph
        }

    val supportsIndentation: Boolean get() = true

    val supportsRenderedCaret: Boolean get() = this == Paragraph || this is Heading

    /** 코드·수식은 블록 내부 개행을 보존한다. 그 외 개행은 블록 경계다. */
    val preservesLineBreaks: Boolean get() = this is Code || this == Equation
}

// MARK: - 인라인 서식

/**
 * 인라인 서식 종류. iOS `InlineFormat`.
 *
 * 선언 순서가 iOS rawValue(0...3)이고 pasteboard JSON의 `format` 값이며 정규화 정렬 키다. 순서를 바꾸지 않는다.
 * 직렬화 구분자는 italic만 `<em>`이 정식이고 `*`·`_`는 파싱만 지원한다.
 */
enum class InlineFormat(internal val opening: String, internal val closing: String) {
    Bold("**", "**"),
    Italic("<em>", "</em>"),
    Strikethrough("~~", "~~"),
    Code("`", "`"),
}

/** 인라인 서식 하나. `range`는 블록 원문 기준 UTF-16 offset이다. iOS `InlineMark`. */
data class InlineMark(val format: InlineFormat, val range: EditorRange)

internal fun InlineMark.shifted(offset: Int): InlineMark =
    InlineMark(format, EditorRange(range.location + offset, range.length))

// MARK: - 블록

/**
 * 블록 하나의 불변 값. iOS `EditorBlock`.
 *
 * 생성과 [copy] 모두 제목 레벨 1...3, 들여쓰기 0...3을 맞추고 [inlineMarks]를 [InlineMarkdownCodec.normalized]로
 * 정리한다. iOS는 `text` 대입 시 서식을 다시 정리하지 않지만 이 포트는 값이 불변이라 [copy]가 항상 다시 정리한다.
 * [fromMarkdown]이 한 블록 분량의 Markdown을 파싱하고 [markdown]이 직렬화한다. 지원하는 본문과 인라인 서식의
 * 의미를 보존하며, 원본 마커 표기·빈 문단 개수·UUID의 동일성은 보장하지 않는다.
 * 같은 문서에서 ID가 중복되지 않게 하는 일은 앱 책임이다.
 */
class EditorBlock(
    val id: UUID = UUID.randomUUID(),
    kind: EditorBlockKind = EditorBlockKind.Paragraph,
    val text: String,
    inlineMarks: List<InlineMark> = emptyList(),
    indentLevel: Int = 0,
) {
    val kind: EditorBlockKind = if (kind is EditorBlockKind.Heading) EditorBlockKind.Heading(kind.level.coerceIn(1, 3)) else kind
    val inlineMarks: List<InlineMark> = InlineMarkdownCodec.normalized(inlineMarks, text)

    /** 들여쓰기 깊이. 0...3으로 clamp된다. */
    val indentLevel: Int = indentLevel.coerceIn(0, 3)

    val markdown: String get() = markdown(numberedListOrdinal = 1)

    fun markdown(numberedListOrdinal: Int): String {
        val indent = "  ".repeat(indentLevel)
        val inline = InlineMarkdownCodec.serialize(text, inlineMarks)
        return when (val kind = kind) {
            EditorBlockKind.Paragraph -> escapedParagraph(inline)
            is EditorBlockKind.Heading -> "#".repeat(kind.level.coerceIn(1, 3)) + " " + inline
            EditorBlockKind.BulletedList -> "$indent- $inline"
            EditorBlockKind.NumberedList -> "$indent${maxOf(numberedListOrdinal, 1)}. $inline"
            is EditorBlockKind.ToDo -> indent + (if (kind.isChecked) "- [x] " else "- [ ] ") + inline
            EditorBlockKind.Quote -> "> $inline"
            is EditorBlockKind.Code -> fencedCode(text, kind.language)
            EditorBlockKind.Equation -> "\\[$text\\]"
        }
    }

    /** 정규화를 다시 거치는 복사. iOS의 프로퍼티 대입(`didSet`)에 대응한다. */
    fun copy(
        id: UUID = this.id,
        kind: EditorBlockKind = this.kind,
        text: String = this.text,
        inlineMarks: List<InlineMark> = this.inlineMarks,
        indentLevel: Int = this.indentLevel,
    ): EditorBlock = EditorBlock(id, kind, text, inlineMarks, indentLevel)

    override fun equals(other: Any?): Boolean = this === other || other is EditorBlock &&
        id == other.id && kind == other.kind && text == other.text &&
        inlineMarks == other.inlineMarks && indentLevel == other.indentLevel

    override fun hashCode(): Int = listOf(id, kind, text, inlineMarks, indentLevel).hashCode()

    override fun toString(): String =
        "EditorBlock(id=$id, kind=$kind, text=$text, inlineMarks=$inlineMarks, indentLevel=$indentLevel)"

    // MARK: - 편집 연산 (모델 내부)

    /** 범위를 교체하고 서식 범위를 따라 옮긴다. 잘못된 범위는 null. */
    internal fun replacingText(range: EditorRange, replacement: String): EditorBlock? {
        if (!text.containsScalarAligned(range)) return null
        val updatedText = text.substring(0, range.location) + replacement + text.substring(range.end)
        val marks = inlineMarks.mapNotNull { transformed(it, range, replacement.length) }
        return copy(text = updatedText, inlineMarks = marks)
    }

    /** 왼쪽은 ID·종류를 유지하고 오른쪽은 새 ID와 [EditorBlockKind.continuationKind]를 받는다. */
    internal fun split(atUtf16Offset: Int): Pair<EditorBlock, EditorBlock>? {
        val offset = atUtf16Offset
        if (!text.isGraphemeBoundary(offset)) return null
        val leftMarks = mutableListOf<InlineMark>()
        val rightMarks = mutableListOf<InlineMark>()
        for (mark in inlineMarks) {
            val start = mark.range.location
            val end = mark.range.end
            val leftLength = maxOf(0, minOf(end, offset) - start)
            if (leftLength > 0 || mark.range.length == 0 && start < offset) {
                leftMarks += InlineMark(mark.format, EditorRange(start, leftLength))
            }
            val rightStart = maxOf(start, offset)
            val rightLength = maxOf(0, end - rightStart)
            if (rightLength > 0 || mark.range.length == 0 && start >= offset) {
                rightMarks += InlineMark(mark.format, EditorRange(rightStart - offset, rightLength))
            }
        }
        val continuation = kind.continuationKind
        return copy(text = text.substring(0, offset), inlineMarks = leftMarks) to EditorBlock(
            kind = continuation,
            text = text.substring(offset),
            inlineMarks = rightMarks,
            indentLevel = if (continuation.supportsIndentation) indentLevel else 0,
        )
    }

    internal fun merged(following: EditorBlock): EditorBlock = copy(
        text = text + following.text,
        inlineMarks = inlineMarks + following.inlineMarks.map { it.shifted(text.length) },
    )

    internal fun changingKind(kind: EditorBlockKind, indentLevel: Int? = null): EditorBlock = copy(
        kind = kind,
        inlineMarks = if (kind.preservesLineBreaks) emptyList() else inlineMarks,
        indentLevel = indentLevel ?: if (kind.supportsIndentation) this.indentLevel else 0,
    )

    internal fun subblock(range: EditorRange, id: UUID, kind: EditorBlockKind, indentLevel: Int): EditorBlock? {
        if (!text.containsScalarAligned(range)) return null
        val marks = inlineMarks.mapNotNull { mark ->
            val start = maxOf(mark.range.location, range.location)
            val end = minOf(mark.range.end, range.end)
            if (end > start) InlineMark(mark.format, EditorRange(start - range.location, end - start)) else null
        }
        return EditorBlock(id, kind, text.substring(range.location, range.end), marks, indentLevel)
    }

    companion object {
        /**
         * 한 블록 분량의 Markdown을 파싱한다. iOS `EditorBlock(markdown:)`. 매번 새 UUID를 발급한다.
         * 편차 3: CRLF·CR을 LF로 맞춘 뒤 파싱한다.
         */
        fun fromMarkdown(markdown: String): EditorBlock {
            val normalized = markdown.normalizingLineEndings()
            val leadingSpaces = normalized.takeWhile { it == ' ' }.length
            val deindented = normalized.substring(leadingSpaces)
            val listMarker = hasListMarker(deindented)
            val source = if (listMarker) deindented else normalized
            val indentLevel = if (listMarker) minOf(leadingSpaces / 2, 3) else 0

            val lines = source.split('\n')
            val fenceLength = if (lines.size >= 2) codeFenceLength(lines.first()) else null
            if (fenceLength != null && closesCodeFence(lines.last(), fenceLength)) {
                val language = lines.first().trimmingSwiftWhitespaces().substring(fenceLength).trimmingSwiftWhitespaces()
                return EditorBlock(
                    kind = EditorBlockKind.Code(language.ifEmpty { null }),
                    text = lines.subList(1, lines.size - 1).joinToString("\n"),
                    indentLevel = indentLevel,
                )
            }
            if (source.startsWith("\\[") && source.endsWith("\\]") && source.length >= 4) {
                return EditorBlock(
                    kind = EditorBlockKind.Equation,
                    text = source.substring(2, source.length - 2),
                    indentLevel = indentLevel,
                )
            }

            val markerCount = source.takeWhile { it == '#' }.length
            val numbered = NUMBERED_LIST_MARKER.find(source)
            val (kind, body) = when {
                markerCount in 1..3 && source.startsWith(" ", markerCount) ->
                    EditorBlockKind.Heading(markerCount) to source.substring(markerCount + 1)
                source.startsWith("- [ ] ") || source.startsWith("- [x] ") ->
                    EditorBlockKind.ToDo(source.startsWith("- [x] ")) to source.substring(6)
                source.startsWith("- ") || source.startsWith("* ") || source.startsWith("+ ") ->
                    EditorBlockKind.BulletedList to source.substring(2)
                numbered != null -> EditorBlockKind.NumberedList to source.substring(numbered.range.last + 1)
                source.startsWith("> ") -> EditorBlockKind.Quote to source.substring(2)
                else -> EditorBlockKind.Paragraph to source
            }
            val parsed = InlineMarkdownCodec.parse(body)
            return EditorBlock(kind = kind, text = parsed.text, inlineMarks = parsed.marks, indentLevel = indentLevel)
        }

        private fun hasListMarker(source: String): Boolean =
            source.startsWith("- ") || source.startsWith("* ") || source.startsWith("+ ") ||
                NUMBERED_LIST_MARKER.containsMatchIn(source)

        /** 문단 출력이 블록 문법으로 다시 읽히지 않게 첫 마커를 escape한다 (iOS `escapedParagraph`). */
        private fun escapedParagraph(markdown: String): String {
            if (markdown.startsWith("\\[") && markdown.endsWith("\\]")) {
                return "\\" + markdown.dropLast(2) + "\\\\]"
            }
            val match = LITERAL_BLOCK_MARKER.find(markdown) ?: return markdown
            val matched = match.value.dropLast(1)
            val leading = matched.takeWhile { it == ' ' || it == '\t' }.length
            val marker = matched.substring(leading)
            val escapeIndex = match.range.first + leading + if (marker.endsWith(".")) marker.length - 1 else 0
            return markdown.substring(0, escapeIndex) + "\\" + markdown.substring(escapeIndex)
        }

        /** fence는 본문 안 가장 긴 backtick 연속보다 길게(최소 3) 출력한다. */
        private fun fencedCode(text: String, language: String?): String {
            var longestRun = 0
            var run = 0
            for (character in text) {
                run = if (character == '`') run + 1 else 0
                longestRun = maxOf(longestRun, run)
            }
            val fence = "`".repeat(maxOf(3, longestRun + 1))
            return "$fence${language.orEmpty()}\n$text\n$fence"
        }

        private fun transformed(mark: InlineMark, range: EditorRange, replacementLength: Int): InlineMark? {
            val markStart = mark.range.location
            val markEnd = mark.range.end
            val changeStart = range.location
            val changeEnd = range.end
            val delta = replacementLength - range.length

            if (range.length == 0) {
                if (changeStart < markStart) return mark.shifted(delta)
                if (changeStart > markEnd) return mark
                return InlineMark(mark.format, EditorRange(markStart, mark.range.length + replacementLength))
            }
            if (markEnd <= changeStart) return mark
            if (markStart >= changeEnd) return mark.shifted(delta)

            val updatedLength = maxOf(0, changeStart - markStart) + replacementLength + maxOf(0, markEnd - changeEnd)
            if (updatedLength <= 0) return null
            return InlineMark(mark.format, EditorRange(minOf(markStart, changeStart), updatedLength))
        }
    }
}

// MARK: - 블록 문법 공유 규칙 (EditorBlock·BlockEditorModel)

/** iOS NSRegularExpression `^\d+\. ` — ICU `\d`는 Unicode Nd라 `\p{Nd}`로 옮긴다. */
internal val NUMBERED_LIST_MARKER = Regex("^\\p{Nd}+\\. ")

private val LITERAL_BLOCK_MARKER = Regex("^[ \\t]*(?:#{1,6}|[-+>]|\\p{Nd}+\\.) ")

/** 여는 fence 길이. backtick 3개 이상이고 info string에 backtick이 없어야 한다. */
internal fun codeFenceLength(line: String): Int? {
    val trimmed = line.trimmingSwiftWhitespaces()
    val length = trimmed.takeWhile { it == '`' }.length
    return if (length >= 3 && trimmed.indexOf('`', length) < 0) length else null
}

/** 닫는 fence: 앞뒤 공백을 뺀 줄이 여는 fence 이상 길이의 backtick만 포함한다. */
internal fun closesCodeFence(line: String, length: Int): Boolean {
    val trimmed = line.trimmingSwiftWhitespaces()
    return trimmed.length >= length && trimmed.all { it == '`' }
}
