// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import java.util.UUID

/**
 * Notion 스타일 블록 문서의 순수 로직 엔진. iOS `BlockEditorModel`.
 *
 * 블록↔문서 UTF-16 좌표 변환, split/merge/indent/종류 변환, 인라인 서식 토글, undo/redo(상한 100, 병합 없음)를
 * 제공한다. UI 의존이 없어 Markdown 구조 조작 파이프라인에도 단독으로 쓸 수 있다.
 *
 * 문서 끝에는 항상 빈 문단(sentinel)이 있다. 연속 문서([documentText])는 블록 본문을 `\n` 하나로 잇는다.
 * 블록 단위 API가 돌려준 [BlockSelection]은 앱이 [updateSelection]으로 적용해야 한다.
 *
 * iOS는 값 타입(struct)이지만 이 클래스는 가변 참조 타입이며 동기화하지 않는다. 한 스레드(보통 main)에서만 쓴다.
 * 복사가 필요하면 `BlockEditorModel(model.blocks)`로 만든다(history는 복사되지 않는다).
 */
class BlockEditorModel(blocks: List<EditorBlock>) {
    private class HistoryEntry(val blocks: List<EditorBlock>, val documentSelection: EditorRange?)

    private class Position(val index: Int, val offset: Int)

    /** Markdown 문서를 블록으로 나눠 파싱한다. iOS `init(markdown:)`. 편차 3: CRLF·CR을 LF로 맞춘다. */
    constructor(markdown: String) : this(splitMarkdown(markdown.normalizingLineEndings()).map(EditorBlock::fromMarkdown))

    var blocks: List<EditorBlock> = normalized(blocks)
        private set

    /** 선택이 시작하는 블록 안의 범위. 교차 블록 선택은 [currentDocumentSelection]을 쓴다. */
    var currentSelection: BlockSelection? = null
        private set

    /** 여러 블록에 걸칠 수 있는 연속 문서 기준 선택. */
    var currentDocumentSelection: EditorRange? = null
        private set

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val documentText: String get() = blocks.joinToString("\n") { it.text }

    /** 저장·보내기용 canonical Markdown. 번호 목록은 같은 깊이의 연속 항목마다 다시 센다. */
    val markdown: String
        get() {
            val numberedCounts = HashMap<Int, Int>()
            val previousKinds = HashMap<Int, EditorBlockKind>()
            return blocks.joinToString("\n") { block ->
                val depth = block.indentLevel
                numberedCounts.keys.removeAll { it > depth }
                previousKinds.keys.removeAll { it > depth }
                val ordinal: Int
                if (block.kind == EditorBlockKind.NumberedList) {
                    ordinal = if (previousKinds[depth] == EditorBlockKind.NumberedList) (numberedCounts[depth] ?: 0) + 1 else 1
                    numberedCounts[depth] = ordinal
                } else {
                    ordinal = 1
                    numberedCounts[depth] = 0
                }
                previousKinds[depth] = block.kind
                block.markdown(ordinal)
            }
        }

    fun block(id: UUID): EditorBlock? = blocks.firstOrNull { it.id == id }

    // MARK: - 선택·좌표

    fun updateSelection(selection: BlockSelection?) {
        val validated = validated(selection, blocks)
        currentSelection = validated
        currentDocumentSelection = validated?.let { documentRange(it) }
    }

    /** null은 선택 해제, 잘못된 범위는 무시한다(기존 선택 유지). */
    fun updateDocumentSelection(selection: EditorRange?) {
        if (selection == null) {
            currentSelection = null
            currentDocumentSelection = null
            return
        }
        if (!documentText.containsScalarAligned(selection)) return
        currentDocumentSelection = selection
        currentSelection = blockSelection(selection)
    }

    fun documentRange(blockId: UUID): EditorRange? {
        val index = blocks.indexOfFirst { it.id == blockId }
        return if (index < 0) null else documentBlockRanges()[index]
    }

    fun documentRange(selection: BlockSelection): EditorRange? {
        if (validated(selection, blocks) == null) return null
        val blockRange = documentRange(selection.blockId) ?: return null
        return EditorRange(blockRange.location + selection.range.location, selection.range.length)
    }

    /** 선택이 시작하는 블록 안의 범위만 돌려준다. 블록 끝을 넘는 길이는 잘린다. */
    fun blockSelection(documentSelection: EditorRange): BlockSelection? {
        if (!documentText.containsScalarAligned(documentSelection)) return null
        val position = documentPosition(documentSelection.location) ?: return null
        val block = blocks[position.index]
        val available = block.text.length - position.offset
        return BlockSelection(block.id, EditorRange(position.offset, minOf(documentSelection.length, available)))
    }

    /** 같은 깊이의 직전 번호 항목을 거슬러 센다. 더 얕은 블록이나 같은 깊이의 다른 종류에서 멈춘다. */
    fun numberedListOrdinal(id: UUID): Int? {
        val index = blocks.indexOfFirst { it.id == id }
        if (index < 0 || blocks[index].kind != EditorBlockKind.NumberedList) return null
        val indentLevel = blocks[index].indentLevel
        var ordinal = 1
        for (cursor in index - 1 downTo 0) {
            val candidate = blocks[cursor]
            if (candidate.indentLevel < indentLevel) break
            if (candidate.indentLevel == indentLevel) {
                if (candidate.kind != EditorBlockKind.NumberedList) break
                ordinal += 1
            }
        }
        return ordinal
    }

    // MARK: - 텍스트 편집

    /** 블록 본문 전체를 바꾸고 선택을 함께 갱신한다. iOS `updateText(id:text:)`·`updateText(id:text:selection:)`. */
    fun updateText(id: UUID, text: String, selection: BlockSelection? = currentSelection) {
        apply(blocks.map { block ->
            if (block.id != id) block else block.replacingText(EditorRange(0, block.text.length), text) ?: block
        })
        updateSelection(selection)
    }

    /**
     * 연속 문서의 UTF-16 변경을 논리 블록 변경으로 환원하고 새 caret을 돌려준다.
     * 코드·수식 내부 개행은 같은 블록에 남고 일반 개행은 새 블록을 만든다. 전체 선택 교체는 Markdown을 해석하지 않는다.
     * 편차 3: `replacement`의 CRLF·CR을 LF로 맞춘다.
     */
    fun replaceDocumentText(range: EditorRange, replacement: String): EditorRange? {
        val document = documentText
        if (!document.containsScalarAligned(range)) return null
        val text = replacement.normalizingLineEndings()
        val nextSelection = EditorRange(range.location + text.length, 0)

        if (range.location == 0 && range.length == document.length) {
            return replaceDocumentBlocks(range, text.split('\n').map { EditorBlock(text = it) })
        }

        val start = documentPosition(range.location) ?: return null
        val end = documentPosition(range.end) ?: return null
        val startBlock = blocks[start.index]
        val endBlock = blocks[end.index]

        if (start.index == end.index && text == "\n" && !startBlock.kind.preservesLineBreaks) {
            val selection = splitBlock(startBlock.id, replacing = EditorRange(start.offset, end.offset - start.offset))
            val documentSelection = selection?.let { documentRange(it) }
            if (documentSelection != null) {
                updateDocumentSelection(documentSelection)
                return currentDocumentSelection
            }
        }

        if (text.isEmpty() && range.length == 1 && start.index + 1 == end.index &&
            start.offset == startBlock.text.length && end.offset == 0
        ) {
            val documentSelection = backspaceAtStart(endBlock.id)?.let { documentRange(it) }
            if (documentSelection != null) {
                updateDocumentSelection(documentSelection)
                return currentDocumentSelection
            }
        }

        if (start.index == end.index && startBlock.kind.preservesLineBreaks) {
            val edited = startBlock.replacingText(EditorRange(start.offset, end.offset - start.offset), text) ?: return null
            apply(blocks.mapIndexed { index, block -> if (index == start.index) edited else block })
            updateDocumentSelection(nextSelection)
            return currentDocumentSelection
        }

        val parts = text.split('\n')
        val replacementBlocks: List<EditorBlock>
        if (parts.size == 1) {
            replacementBlocks = if (start.index == end.index) {
                listOf(startBlock.replacingText(EditorRange(start.offset, end.offset - start.offset), parts[0]) ?: return null)
            } else {
                val first = startBlock.replacingText(
                    EditorRange(start.offset, startBlock.text.length - start.offset), parts[0],
                ) ?: return null
                val last = endBlock.replacingText(EditorRange(0, end.offset), "") ?: return null
                listOf(first.merged(last))
            }
        } else {
            val continuation = startBlock.kind.continuationKind
            val continuationIndent = if (continuation.supportsIndentation) startBlock.indentLevel else 0
            val first = startBlock.replacingText(
                EditorRange(start.offset, startBlock.text.length - start.offset), parts[0],
            ) ?: return null
            val lastSource = endBlock.replacingText(EditorRange(0, end.offset), parts.last()) ?: return null
            val preservesEndBlock = end.index != start.index
            replacementBlocks = listOf(first) +
                parts.subList(1, parts.size - 1).map {
                    EditorBlock(kind = continuation, text = it, indentLevel = continuationIndent)
                } +
                EditorBlock(
                    id = if (preservesEndBlock) endBlock.id else UUID.randomUUID(),
                    kind = if (preservesEndBlock) endBlock.kind else continuation,
                    text = lastSource.text,
                    inlineMarks = lastSource.inlineMarks,
                    indentLevel = if (preservesEndBlock) endBlock.indentLevel else continuationIndent,
                )
        }

        apply(blocks.subList(0, start.index) + replacementBlocks + blocks.subList(end.index + 1, blocks.size))
        updateDocumentSelection(nextSelection)
        return currentDocumentSelection
    }

    /** 앱 전용 pasteboard 표현으로 전달된 논리 블록 전체를 복원한다. 전체 문서 범위만 받는다. */
    fun replaceDocumentBlocks(range: EditorRange, replacement: List<EditorBlock>): EditorRange? {
        if (range != EditorRange(0, documentText.length)) return null
        apply(normalized(replacement))
        val trailingEmptyBlockLength = if (blocks.size > 1 && blocks.last().text.isEmpty()) 1 else 0
        updateDocumentSelection(EditorRange(maxOf(0, documentText.length - trailingEmptyBlockLength), 0))
        return currentDocumentSelection
    }

    fun splitBlock(id: UUID, atUtf16Offset: Int): BlockSelection? = splitBlock(id, replacing = EditorRange(atUtf16Offset, 0))

    /** 선택한 텍스트를 지운 뒤 한 번 나눈다. 빈 블록에서는 같은 ID의 문단으로 바꾼다(빈 문단이면 변경 없음). */
    fun splitBlock(id: UUID, replacing: EditorRange): BlockSelection? {
        val index = blocks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = blocks[index]
        val edited = current.replacingText(replacing, "") ?: return null
        val (left, right) = edited.split(atUtf16Offset = replacing.location) ?: return null
        if (edited.text.isEmpty() && current.kind.supportsIndentation) {
            transform(id, EditorBlockKind.Paragraph)
            return BlockSelection(id, EditorRange(0, 0))
        }
        apply(blocks.subList(0, index) + left + right + blocks.subList(index + 1, blocks.size))
        return BlockSelection(right.id, EditorRange(0, 0))
    }

    fun insertSoftBreak(id: UUID, atUtf16Offset: Int): BlockSelection? =
        insertSoftBreak(id, replacing = EditorRange(atUtf16Offset, 0))

    /** 코드·수식은 블록 안 개행, 그 외 블록은 [splitBlock]. */
    fun insertSoftBreak(id: UUID, replacing: EditorRange): BlockSelection? {
        val block = block(id) ?: return null
        return if (block.kind.preservesLineBreaks) replaceText(id, replacing, "\n") else splitBlock(id, replacing)
    }

    /** 서식 블록은 문단으로 바꾸고, 문단은 앞 블록과 합친다. 앞이 코드·수식이면 caret만 앞 블록 끝으로 옮긴다. */
    fun backspaceAtStart(id: UUID): BlockSelection? {
        val index = blocks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = blocks[index]
        if (current.kind != EditorBlockKind.Paragraph) {
            transform(id, EditorBlockKind.Paragraph)
            return BlockSelection(id, EditorRange(0, 0))
        }
        if (index == 0) return null

        val previous = blocks[index - 1]
        if (previous.kind.preservesLineBreaks) {
            return BlockSelection(previous.id, EditorRange(previous.text.length, 0))
        }
        val boundary = previous.text.length
        apply(blocks.subList(0, index - 1) + previous.merged(current) + blocks.subList(index + 1, blocks.size))
        return BlockSelection(previous.id, EditorRange(boundary, 0))
    }

    fun replaceText(id: UUID, range: EditorRange, replacement: String): BlockSelection? {
        val edited = block(id)?.replacingText(range, replacement) ?: return null
        apply(blocks.map { if (it.id == id) edited else it })
        return BlockSelection(id, EditorRange(range.location + replacement.length, 0))
    }

    // MARK: - 블록 명령

    /** `after`가 없거나 찾지 못하면 끝 sentinel 앞에 넣는다. */
    fun insert(after: UUID?, kind: EditorBlockKind = EditorBlockKind.Paragraph): BlockSelection {
        val found = if (after == null) -1 else blocks.indexOfFirst { it.id == after }
        val index = if (found >= 0) found + 1 else maxOf(blocks.size - 1, 0)
        val inserted = EditorBlock(kind = kind, text = "")
        apply(blocks.subList(0, index) + inserted + blocks.subList(index, blocks.size))
        return BlockSelection(inserted.id, EditorRange(0, 0))
    }

    /** 내용·서식·들여쓰기를 새 ID로 복제해 바로 뒤에 넣는다. */
    fun duplicate(id: UUID): BlockSelection? {
        val index = blocks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val source = blocks[index]
        val copy = source.copy(id = UUID.randomUUID())
        apply(blocks.subList(0, index + 1) + copy + blocks.subList(index + 1, blocks.size))
        return BlockSelection(copy.id, EditorRange(copy.text.length, 0))
    }

    fun delete(id: UUID): BlockSelection? {
        val index = blocks.indexOfFirst { it.id == id }
        if (index < 0) return null
        apply(blocks.filter { it.id != id }.ifEmpty { listOf(EditorBlock(text = "")) })
        val target = blocks[minOf(index, blocks.size - 1)]
        return BlockSelection(target.id, EditorRange(target.text.length, 0))
    }

    fun transform(id: UUID, kind: EditorBlockKind) {
        val current = block(id) ?: return
        if (current.kind == kind) return
        apply(blocks.map { if (it.id == id) it.changingKind(kind) else it })
    }

    fun indent(id: UUID): Boolean = changeIndent(id, delta = 1)

    fun outdent(id: UUID): Boolean = changeIndent(id, delta = -1)

    /** 끝 sentinel 문단은 이동 대상·목적지가 아니다. */
    fun moveUp(id: UUID): Boolean {
        val index = contentIndex(id) ?: return false
        return index > 0 && move(index, index - 1)
    }

    fun moveDown(id: UUID): Boolean {
        val index = contentIndex(id) ?: return false
        return index < contentBlockCount - 1 && move(index, index + 1)
    }

    fun move(id: UUID, toPositionOf: UUID): Boolean {
        val from = contentIndex(id) ?: return false
        val target = contentIndex(toPositionOf) ?: return false
        return from != target && move(from, target)
    }

    // MARK: - 서식·바로가기

    /** 범위 전체가 이미 같은 서식이면 그 하위 범위만 해제하고, 아니면 추가한다. 코드·수식 블록은 null. */
    fun applyInlineFormat(format: InlineFormat, id: UUID, range: EditorRange): BlockSelection? {
        val current = block(id) ?: return null
        if (current.kind.preservesLineBreaks || range.length <= 0 || !current.text.containsScalarAligned(range)) return null
        val marks = current.inlineMarks.filter { it.format != format }.toMutableList()
        val formatMarks = current.inlineMarks.filter { it.format == format }
        if (covers(range, formatMarks.map { it.range })) {
            for (mark in formatMarks) {
                val leftLength = maxOf(minOf(range.location, mark.range.end) - mark.range.location, 0)
                if (leftLength > 0) marks += InlineMark(format, EditorRange(mark.range.location, leftLength))
                val rightStart = maxOf(range.end, mark.range.location)
                val rightLength = maxOf(mark.range.end - rightStart, 0)
                if (rightLength > 0) marks += InlineMark(format, EditorRange(rightStart, rightLength))
            }
        } else {
            marks += formatMarks
            marks += InlineMark(format, range)
        }
        val edited = current.copy(inlineMarks = marks)
        apply(blocks.map { if (it.id == id) edited else it })
        return BlockSelection(id, range)
    }

    /** `- ` 같은 접두어를 지우고 종류를 바꾼다. 들여쓰기는 0이 된다. */
    fun applyShortcut(id: UUID, kind: EditorBlockKind, prefixUtf16Length: Int): BlockSelection? {
        val current = block(id) ?: return null
        if (!current.text.isGraphemeBoundary(prefixUtf16Length)) return null
        val withoutPrefix = current.replacingText(EditorRange(0, prefixUtf16Length), "") ?: return null
        apply(blocks.map { if (it.id == id) withoutPrefix.changingKind(kind, indentLevel = 0) else it })
        return BlockSelection(id, EditorRange(0, 0))
    }

    // MARK: - undo/redo

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(HistoryEntry(blocks, currentDocumentSelection))
        blocks = previous.blocks
        setDocumentSelection(previous.documentSelection)
        return true
    }

    /** 현재 선택을 먼저 기록해 redo가 그 선택으로 돌아오게 한다. */
    fun undo(currentSelection: BlockSelection?): Boolean {
        updateSelection(currentSelection)
        return undo()
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(HistoryEntry(blocks, currentDocumentSelection))
        trimHistory()
        blocks = next.blocks
        setDocumentSelection(next.documentSelection)
        return true
    }

    fun redo(currentSelection: BlockSelection?): Boolean {
        updateSelection(currentSelection)
        return redo()
    }

    // MARK: - 내부

    private val contentBlockCount: Int
        get() {
            val last = blocks.lastOrNull()
            return if (last != null && last.text.isEmpty() && last.kind == EditorBlockKind.Paragraph) {
                maxOf(blocks.size - 1, 0)
            } else {
                blocks.size
            }
        }

    private fun contentIndex(id: UUID): Int? = blocks.indexOfFirst { it.id == id }.takeIf { it in 0 until contentBlockCount }

    private fun changeIndent(id: UUID, delta: Int): Boolean {
        val current = block(id) ?: return false
        if (!current.kind.supportsIndentation) return false
        val nextLevel = (current.indentLevel + delta).coerceIn(0, 3)
        if (nextLevel == current.indentLevel) return false
        apply(blocks.map { if (it.id == id) it.copy(indentLevel = nextLevel) else it })
        return true
    }

    private fun move(from: Int, to: Int): Boolean {
        if (from == to || from !in blocks.indices || to !in blocks.indices) return false
        val updated = blocks.toMutableList()
        updated.add(to, updated.removeAt(from))
        apply(updated)
        return true
    }

    /** 정규화 결과가 현재와 다를 때만 history에 기록하고 redo 분기를 버린다. */
    private fun apply(updated: List<EditorBlock>) {
        val normalized = normalized(updated)
        if (normalized == blocks) return
        undoStack.addLast(HistoryEntry(blocks, currentDocumentSelection))
        trimHistory()
        redoStack.clear()
        blocks = normalized
    }

    private fun trimHistory() {
        while (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
    }

    private fun setDocumentSelection(selection: EditorRange?) {
        currentDocumentSelection = selection?.takeIf { documentText.containsScalarAligned(it) }
        currentSelection = currentDocumentSelection?.let { blockSelection(it) }
    }

    private fun documentBlockRanges(): List<EditorRange> {
        var location = 0
        return blocks.map { block ->
            EditorRange(location, block.text.length).also { location += block.text.length + 1 }
        }
    }

    private fun documentPosition(offset: Int): Position? {
        if (offset < 0 || offset > documentText.length) return null
        val ranges = documentBlockRanges()
        for ((index, range) in ranges.withIndex()) {
            if (offset <= range.end) return Position(index, offset - range.location)
        }
        return ranges.lastOrNull()?.let { Position(ranges.lastIndex, it.length) }
    }

    private companion object {
        const val HISTORY_LIMIT = 100

        fun validated(selection: BlockSelection?, blocks: List<EditorBlock>): BlockSelection? {
            if (selection == null) return null
            val block = blocks.firstOrNull { it.id == selection.blockId } ?: return null
            return selection.takeIf { block.text.containsScalarAligned(it.range) }
        }

        fun covers(target: EditorRange, ranges: List<EditorRange>): Boolean {
            var cursor = target.location
            for (range in ranges.sortedBy { it.location }) {
                if (range.end <= cursor) continue
                if (range.location > cursor) return false
                cursor = maxOf(cursor, range.end)
                if (cursor >= target.end) return true
            }
            return false
        }

        /** 일반 블록의 개행을 블록 경계로 펼치고 문서 끝 빈 문단(sentinel)을 보장한다. */
        fun normalized(source: List<EditorBlock>): List<EditorBlock> {
            val expanded = source.flatMap { block ->
                if (block.kind.preservesLineBreaks || '\n' !in block.text) return@flatMap listOf(block)
                var location = 0
                block.text.split('\n').mapIndexedNotNull { index, line ->
                    val kind = if (index == 0) block.kind else block.kind.continuationKind
                    val range = EditorRange(location, line.length)
                    location += line.length + 1
                    block.subblock(
                        range,
                        id = if (index == 0) block.id else UUID.randomUUID(),
                        kind = kind,
                        indentLevel = if (kind.supportsIndentation) block.indentLevel else 0,
                    )
                }
            }
            val last = expanded.lastOrNull() ?: return listOf(EditorBlock(text = ""))
            if (last.text.isEmpty() && last.kind == EditorBlockKind.Paragraph) return expanded
            return expanded + EditorBlock(text = "")
        }

        /** Markdown 문서를 블록 단위 Markdown 조각으로 나눈다. 코드 fence 안의 줄은 닫힐 때까지 한 조각이다. */
        fun splitMarkdown(markdown: String): List<String> {
            val result = mutableListOf<String>()
            val current = mutableListOf<String>()
            var openFenceLength: Int? = null
            fun flush() {
                if (current.isNotEmpty()) {
                    result += current.joinToString("\n")
                    current.clear()
                }
            }

            for (line in markdown.split('\n')) {
                val trimmed = line.trimmingSwiftWhitespaces()
                val fence = openFenceLength
                if (fence != null) {
                    current += line
                    if (closesCodeFence(trimmed, fence)) {
                        openFenceLength = null
                        flush()
                    }
                    continue
                }
                val fenceLength = codeFenceLength(trimmed)
                if (fenceLength != null) {
                    flush()
                    current += line
                    openFenceLength = fenceLength
                    continue
                }
                if (trimmed.isEmpty()) {
                    flush()
                    continue
                }
                if (startsStandaloneBlock(line)) {
                    flush()
                    current += line
                    continue
                }
                val first = current.firstOrNull()
                if (first != null && startsStandaloneBlock(first) &&
                    !(startsListItem(first) && line.firstOrNull()?.let { isSwiftWhitespace(it.code) } == true)
                ) {
                    flush()
                }
                current += line
            }
            flush()
            return result
        }

        fun startsStandaloneBlock(line: String): Boolean {
            val trimmed = line.trimmingSwiftWhitespaces()
            if (trimmed.startsWith("# ") || trimmed.startsWith("## ") || trimmed.startsWith("### ")) return true
            if (trimmed.startsWith("> ") || startsListItem(line)) return true
            return trimmed.startsWith("\\[") && trimmed.endsWith("\\]")
        }

        fun startsListItem(line: String): Boolean {
            val trimmed = line.trimmingSwiftWhitespaces()
            return trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") ||
                NUMBERED_LIST_MARKER.containsMatchIn(trimmed)
        }
    }
}
