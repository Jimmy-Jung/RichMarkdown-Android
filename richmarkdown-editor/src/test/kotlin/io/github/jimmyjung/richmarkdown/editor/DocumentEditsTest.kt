// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import io.github.jimmyjung.richmarkdown.editor.DocumentEdits.Replacement
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Equation
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 편집 뷰가 사용자 편집을 baseline 기준 교체 하나로 환원하는 순수 규칙 (iOS Coordinator `replacement`·`singleReplacement`·`clamped`). */
class DocumentEditsTest {

    /** iOS markedTextPreservesAmbiguousInsertionRange의 순수 부분: 반복 문자 앞 입력은 diff가 아니라 실제 시작 범위로 환원한다. */
    @Test
    fun anchoredReplacementKeepsAmbiguousInsertionPoint() {
        assertEquals(Replacement(r(0, 0), "가"), DocumentEdits.anchored("가가", "가가가", r(0, 0)))
        assertEquals(Replacement(r(2, 0), "가"), DocumentEdits.diff("가가", "가가가"))
        assertEquals(Replacement(r(1, 1), "xy"), DocumentEdits.anchored("abc", "axyc", r(1, 1)))
        // 앞뒤가 그대로가 아니면(다른 곳이 바뀐 경우) 기준 범위를 믿지 않는다.
        assertNull(DocumentEdits.anchored("abc", "abd", r(0, 0)))
        // surrogate pair를 가르는 범위는 모델이 거절하므로 쓰지 않는다.
        assertNull(DocumentEdits.anchored("😀", "😀x", r(1, 0)))
    }

    @Test
    fun diffNeverSplitsSurrogatePairs() {
        // 같은 high surrogate를 공유하는 두 이모지: UTF-16 공통 앞부분은 1이지만 pair 앞으로 물린다.
        assertEquals(Replacement(r(0, 2), "😃"), DocumentEdits.diff("😀", "😃"))
        // low surrogate만 같은 경우: 공통 뒷부분도 pair 뒤로 물린다.
        assertEquals(Replacement(r(0, 2), "🨀"), DocumentEdits.diff("😀", "🨀"))
        assertEquals(Replacement(r(1, 1), ""), DocumentEdits.diff("a\nb", "ab"))
    }

    @Test
    fun clampedHandlesOverflowingRanges() {
        assertEquals(r(2, 0), DocumentEdits.clamped(r(Int.MAX_VALUE, 1), 2))
        assertEquals(r(1, 1), DocumentEdits.clamped(r(1, Int.MAX_VALUE), 2))
        assertEquals(r(0, 2), DocumentEdits.clamped(r(0, Int.MAX_VALUE), 2))
        assertEquals(r(0, 1), DocumentEdits.clamped(r(-1, 1), 2))
    }

    /** iOS BD:610 — caret은 수식 블록 양끝을 포함하고, 범위 선택은 교집합이 있어야 한다. 원문 전환 시 caret은 문자 경계로 내린다. */
    @Test
    fun equationEditingRangeAndSourceAlignment() {
        val paragraph = EditorBlock(kind = Paragraph, text = "ab")
        val equation = EditorBlock(kind = Equation, text = "😀x")
        val blocks = listOf(paragraph, equation)

        assertEquals(emptySet<Any>(), equationBlockIds(blocks, r(2, 0)))
        assertEquals(setOf(equation.id), equationBlockIds(blocks, r(3, 0)))
        assertEquals(setOf(equation.id), equationBlockIds(blocks, r(6, 0)))
        assertEquals(emptySet<Any>(), equationBlockIds(blocks, r(1, 2)))
        assertEquals(setOf(equation.id), equationBlockIds(blocks, r(2, 2)))
        assertEquals(emptySet<Any>(), equationBlockIds(blocks, r(Int.MAX_VALUE, 1)))

        val editing = setOf(equation.id)
        assertEquals(r(3, 0), sourceAlignedSelection(r(4, 0), blocks, editing, emptyList()))
        assertEquals(r(3, 2), sourceAlignedSelection(r(4, 1), blocks, editing, emptyList()))
        assertEquals(r(4, 0), sourceAlignedSelection(r(4, 0), blocks, emptySet(), emptyList()))
    }
}
