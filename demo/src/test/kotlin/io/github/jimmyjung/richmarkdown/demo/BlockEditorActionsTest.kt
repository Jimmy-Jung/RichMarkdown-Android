// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.demo

import io.github.jimmyjung.richmarkdown.editor.BlockEditorModel
import io.github.jimmyjung.richmarkdown.editor.BlockSelection
import io.github.jimmyjung.richmarkdown.editor.EditorBlock
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind
import io.github.jimmyjung.richmarkdown.editor.EditorRange
import io.github.jimmyjung.richmarkdown.editor.EditorToolbarAction
import io.github.jimmyjung.richmarkdown.editor.InlineFormat
import io.github.jimmyjung.richmarkdown.editor.InlineMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 블록 편집 데모의 명령 연결(iOS `BlockEditorDemoView.perform`)과 첫 문서. */
class BlockEditorActionsTest {

    private fun r(location: Int, length: Int) = EditorRange(location, length)

    @Test
    fun sampleSeedsEveryToolbarKindAndInlineFormat() {
        val blocks = BlockEditorModel(BLOCK_EDITOR_SAMPLE).blocks
        for (kind in BLOCK_KINDS) assertTrue(kind.title, blocks.any { it.kind.isSameKind(kind) })
        assertTrue(blocks.any { it.kind == EditorBlockKind.ToDo(isChecked = true) })
        assertTrue(blocks.any { it.kind == EditorBlockKind.Code("kotlin") })
        assertEquals(1, blocks.single { it.text == "들여쓴 하위 항목" }.indentLevel)
        val formats = blocks.single { it.text.startsWith("오늘 목표") }.inlineMarks.map { it.format }.toSet()
        assertEquals(InlineFormat.entries.toSet(), formats)
        assertTrue(blocks.any { "\\( a^2 + b^2 = c^2 \\)" in it.text })
    }

    @Test
    fun formatUsesClippedRangeAndMovesSelectionOnlyWhenNotClipped() {
        val model = BlockEditorModel(listOf(EditorBlock(text = "abc"), EditorBlock(text = "def")))
        model.perform(EditorToolbarAction.Format(InlineFormat.Bold), r(0, 2))
        assertEquals(listOf(InlineMark(InlineFormat.Bold, r(0, 2))), model.blocks[0].inlineMarks)
        assertEquals(r(0, 2), model.currentDocumentSelection)

        // 블록 경계를 넘는 선택은 시작 블록 끝에서 잘려 적용되고, 문서 선택은 그대로 남는다.
        model.perform(EditorToolbarAction.Format(InlineFormat.Italic), r(1, 5))
        assertEquals(InlineMark(InlineFormat.Italic, r(1, 2)), model.blocks[0].inlineMarks.single { it.format == InlineFormat.Italic })
        assertEquals(r(1, 5), model.currentDocumentSelection)
    }

    @Test
    fun moveRestoresCaretInsideMovedBlockButNotRangeSelection() {
        val first = EditorBlock(text = "가나")
        val model = BlockEditorModel(listOf(first, EditorBlock(text = "다")))
        model.perform(EditorToolbarAction.MoveDown, r(1, 0))
        assertEquals(listOf("다", "가나"), model.blocks.take(2).map { it.text })
        assertEquals(BlockSelection(first.id, r(1, 0)), model.currentSelection)
        assertEquals(r(3, 0), model.currentDocumentSelection)

        model.perform(EditorToolbarAction.MoveUp, r(2, 2))
        assertEquals(listOf("가나", "다"), model.blocks.take(2).map { it.text })
        assertEquals(r(2, 2), model.currentDocumentSelection)
    }

    @Test
    fun insertDuplicateDeleteUndoAndDone() {
        val model = BlockEditorModel(listOf(EditorBlock(text = "본문")))
        model.perform(EditorToolbarAction.Insert(EditorBlockKind.Quote), r(1, 0))
        assertEquals(EditorBlockKind.Quote, model.blocks[1].kind)
        assertEquals(BlockSelection(model.blocks[1].id, r(0, 0)), model.currentSelection)

        model.perform(EditorToolbarAction.Duplicate, r(0, 0))
        assertEquals(listOf("본문", "본문", ""), model.blocks.take(3).map { it.text })
        assertEquals(r(5, 0), model.currentDocumentSelection)

        model.perform(EditorToolbarAction.Delete, r(0, 0))
        model.perform(EditorToolbarAction.Undo, r(0, 0))
        assertEquals(listOf("본문", "본문"), model.blocks.take(2).map { it.text })

        // 완료는 모델을 건드리지 않는다(선택 반영도 하지 않는다).
        val untouched = BlockEditorModel(listOf(EditorBlock(text = "본문")))
        untouched.perform(EditorToolbarAction.Done, r(1, 0))
        assertNull(untouched.currentDocumentSelection)
    }
}
