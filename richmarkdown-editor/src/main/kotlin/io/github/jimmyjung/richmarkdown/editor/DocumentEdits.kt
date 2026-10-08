// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import java.util.UUID
import kotlin.math.max
import kotlin.math.min

// 편집 뷰가 쓰는 Android 비의존 순수 규칙. JVM 단위 테스트(DocumentEditsTest)로 고정한다.

// MARK: - 문서 좌표 도우미 (iOS BlockDocumentTextEditor private static)

/** 선택 시작이 속한 블록(블록 끝 경계 포함), 없으면 마지막 블록. iOS `block(at:)`. */
internal fun blockAt(blocks: List<EditorBlock>, offset: Int): EditorBlock? {
    var location = 0
    for ((index, block) in blocks.withIndex()) {
        if (offset <= location + block.text.length) return block
        location += block.text.length
        if (index < blocks.lastIndex) location += 1
    }
    return blocks.lastOrNull()
}

/** 선택이 닿은 수식 블록. caret은 블록 양끝을 포함하고 범위는 교집합 길이 > 0이다. iOS `equationBlockIDs(in:selection:)`. */
internal fun equationBlockIds(blocks: List<EditorBlock>, selection: EditorRange): Set<UUID> {
    if (!selection.isValid(documentLength(blocks))) return emptySet()
    val result = HashSet<UUID>()
    var location = 0
    for ((index, block) in blocks.withIndex()) {
        val end = location + block.text.length
        val intersects = if (selection.length == 0) {
            selection.location in location..end
        } else {
            min(selection.end, end) > max(selection.location, location)
        }
        if (block.kind == EditorBlockKind.Equation && intersects) result += block.id
        location = end
        if (index < blocks.lastIndex) location += 1
    }
    return result
}

/** 원문으로 전환되는 수식 블록·인라인 수식 안의 선택을 문자 경계로 맞춘다. iOS `sourceAlignedSelection`. */
internal fun sourceAlignedSelection(
    selection: EditorRange,
    blocks: List<EditorBlock>,
    editingEquationIds: Set<UUID>,
    editingInlineMathRanges: List<EditorRange>,
): EditorRange {
    val clamped = DocumentEdits.clamped(selection, documentLength(blocks))
    val start = sourceAlignedOffset(clamped.location, blocks, editingEquationIds, editingInlineMathRanges, preferUpperBoundary = false)
    val end = sourceAlignedOffset(clamped.end, blocks, editingEquationIds, editingInlineMathRanges, preferUpperBoundary = clamped.length > 0)
    return EditorRange(start, max(end - start, 0))
}

private fun sourceAlignedOffset(
    offset: Int,
    blocks: List<EditorBlock>,
    editingEquationIds: Set<UUID>,
    editingInlineMathRanges: List<EditorRange>,
    preferUpperBoundary: Boolean,
): Int {
    var blockStart = 0
    for ((index, block) in blocks.withIndex()) {
        val blockEnd = blockStart + block.text.length
        val editsInlineMath = editingInlineMathRanges.any { min(it.end, blockEnd) > max(it.location, blockStart) }
        if ((block.id in editingEquationIds || editsInlineMath) && offset in blockStart..blockEnd) {
            var local = offset - blockStart
            while (!block.text.isGraphemeBoundary(local)) local += if (preferUpperBoundary) 1 else -1
            return blockStart + local
        }
        blockStart = blockEnd
        if (index < blocks.lastIndex) blockStart += 1
    }
    return offset
}

/** 사용자 편집을 baseline 기준 범위 교체 하나로 환원하는 순수 함수. iOS Coordinator `replacement`·`singleReplacement`. */
internal object DocumentEdits {
    data class Replacement(val range: EditorRange, val replacement: String)

    /** 범위를 문서 안으로 제한한다. overflow 값도 안전하다. iOS `clamped`. */
    fun clamped(range: EditorRange, length: Int): EditorRange {
        val location = range.location.coerceIn(0, length)
        return EditorRange(location, range.length.coerceIn(0, length - location))
    }

    /**
     * baseline의 [range]가 바뀌어 [new]가 됐다고 보고 교체 문자열을 구한다. 앞뒤가 그대로가 아니면 null.
     * 반복 문자 앞 입력처럼 diff가 모호해도 실제 편집 위치를 지킨다. surrogate pair를 가르는 범위도 null(모델이 거절하므로).
     */
    fun anchored(old: String, new: String, range: EditorRange): Replacement? {
        if (!old.containsScalarAligned(range)) return null
        val replacementLength = new.length - (old.length - range.length)
        if (replacementLength < 0) return null
        val newEnd = range.location + replacementLength
        if (!old.regionMatches(0, new, 0, range.location)) return null
        if (!old.regionMatches(range.end, new, newEnd, old.length - range.end)) return null
        return Replacement(range, new.substring(range.location, newEnd))
    }

    /**
     * 공통 앞·뒤를 뺀 나머지를 교체 하나로 본다. 편집 범위를 모를 때만 쓴다(O(n)).
     * iOS는 Character 단위로 비교하지만 여기서는 UTF-16 단위로 비교한 뒤 surrogate pair를 가르지 않게 경계를 물린다.
     */
    fun diff(old: String, new: String): Replacement {
        val shorter = min(old.length, new.length)
        var prefix = 0
        while (prefix < shorter && old[prefix] == new[prefix]) prefix += 1
        if (prefix > 0 && old[prefix - 1].isHighSurrogate()) prefix -= 1
        var suffix = 0
        while (suffix < shorter - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix += 1
        if (suffix > 0 && old[old.length - suffix].isLowSurrogate()) suffix -= 1
        return Replacement(EditorRange(prefix, old.length - prefix - suffix), new.substring(prefix, new.length - suffix))
    }
}
