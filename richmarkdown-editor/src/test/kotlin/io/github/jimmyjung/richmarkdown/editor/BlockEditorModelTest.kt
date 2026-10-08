// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.BulletedList
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Heading
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.NumberedList
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Paragraph
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * 블록 편집 모델의 선택·편집·history·연속 문서 계약. iOS `Tests/RichMarkdownBlockEditorTests/BlockEditorModelTests.swift`
 * 중 모델 테스트를 같은 이름으로 이식했다. 매개변수화 테스트는 한 메서드 안에서 입력을 순회한다.
 * 스타일러·TextKit 뷰가 필요한 단언은 Phase 2(EditText 편집기)에서 이식한다.
 */
class BlockEditorModelTest {

    // MARK: - 범위 검증

    /** iOS: overflow하거나 잘못된 공개 선택·편집 범위는 문서를 바꾸지 않고 거절한다. */
    @Test
    fun invalidPublicEditRangesAreRejected() {
        for (range in OVERFLOW_RANGES) {
            val block = EditorBlock(text = "본문")
            val model = BlockEditorModel(listOf(block))
            val original = model.blocks
            val selection = BlockSelection(block.id, range)
            val validSelection = r(0, 0)

            assertNull("$range", model.documentRange(selection))
            assertNull("$range", model.blockSelection(range))
            model.updateDocumentSelection(validSelection)
            model.updateDocumentSelection(range)
            assertEquals("$range", validSelection, model.currentDocumentSelection)
            assertNull("$range", model.replaceDocumentText(range, "새"))
            assertNull("$range", model.replaceText(block.id, range, "새"))
            assertNull("$range", model.splitBlock(block.id, replacing = range))
            assertNull("$range", model.applyInlineFormat(InlineFormat.Italic, block.id, range))
            model.updateSelection(selection)
            assertNull("$range", model.currentSelection)
            assertEquals("$range", original, model.blocks)
        }
    }

    /** iOS: 들여쓰기는 직접 생성과 대입 모두 0...3으로 제한한다. (스타일러 textLists 단언은 Phase 2) */
    @Test
    fun directIndentValuesAreClamped() {
        for (input in listOf(Int.MIN_VALUE, -1, 0, 3, 4, Int.MAX_VALUE)) {
            val expected = input.coerceIn(0, 3)
            val id = UUID.randomUUID()
            val initialized = EditorBlock(id, BulletedList, "항목", indentLevel = input)
            val assigned = EditorBlock(id, BulletedList, "항목").copy(indentLevel = input)

            assertEquals("$input", expected, initialized.indentLevel)
            assertEquals("$input", expected, assigned.indentLevel)
            assertEquals("$input", initialized, assigned)
            assertEquals(id, assigned.id)
            assertEquals("항목", assigned.text)
            assertEquals("  ".repeat(expected) + "- 항목", assigned.markdown)
        }
    }

    /** iOS: 제목 레벨은 직접 생성·대입·모델 변환 모두 1...3으로 제한한다. */
    @Test
    fun directHeadingValuesAreClamped() {
        for (input in listOf(Int.MIN_VALUE, 0, 1, 3, 4, Int.MAX_VALUE)) {
            val expectedKind = Heading(input.coerceIn(1, 3))
            val initialized = EditorBlock(kind = Heading(input), text = "제목")
            val assigned = EditorBlock(text = "제목").copy(kind = Heading(input))
            val model = BlockEditorModel(listOf(EditorBlock(text = "제목")))
            model.transform(model.blocks[0].id, Heading(input))

            assertEquals("$input", expectedKind, initialized.kind)
            assertEquals("$input", expectedKind, assigned.kind)
            assertEquals("$input", expectedKind, model.blocks[0].kind)
            assertEquals("$input", expectedKind, EditorBlock.fromMarkdown(initialized.markdown).kind)
        }
    }

    /** iOS: 잘못된 UTF-16 범위는 문서를 바꾸지 않는다. */
    @Test
    fun invalidRangesAreRejected() {
        val id = UUID.randomUUID()
        val original = EditorBlock(id, Paragraph, "본문")
        val model = BlockEditorModel(listOf(original))

        assertNull(model.splitBlock(id, replacing = r(-1, 0)))
        assertNull(model.applyInlineFormat(InlineFormat.Bold, id, r(0, 3)))
        assertNull(model.applyShortcut(id, Quote, prefixUtf16Length = -1))
        assertEquals(original, model.blocks.first())

        val emojiId = UUID.randomUUID()
        val emojiModel = BlockEditorModel(listOf(EditorBlock(emojiId, Paragraph, "가😀나")))
        assertNull(emojiModel.splitBlock(emojiId, atUtf16Offset = 2))
        assertEquals("가😀나", emojiModel.blocks[0].text)
    }

    // MARK: - 분할·병합

    /** iOS: Enter는 UTF-16 커서 위치에서 블록을 나누고 ID를 보존한다. */
    @Test
    fun splitBlockAtCaret() {
        val firstId = UUID.randomUUID()
        val followingId = UUID.randomUUID()
        val model = BlockEditorModel(listOf(
            EditorBlock(firstId, Paragraph, "가😀나"),
            EditorBlock(followingId, Paragraph, "다음"),
        ))

        val selection = model.splitBlock(firstId, atUtf16Offset = 3)!!

        assertEquals(listOf("가😀", "나", "다음", ""), model.texts)
        assertEquals(firstId, model.blocks[0].id)
        assertEquals(followingId, model.blocks[2].id)
        assertEquals(model.blocks[1].id, selection.blockId)
        assertEquals(r(0, 0), selection.range)
    }

    /** iOS: Enter는 선택한 텍스트를 지운 뒤 한 번만 분할한다. */
    @Test
    fun splitReplacingSelection() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, Paragraph, "가나다라")))

        val selection = model.splitBlock(id, replacing = r(1, 2))!!

        assertEquals(listOf("가", "라", ""), model.texts)
        assertEquals(model.blocks[1].id, selection.blockId)
    }

    /** iOS: 일반 블록의 줄바꿈 요청도 새 논리 블록을 만든다. */
    @Test
    fun softBreakRequestSplitsRegularBlock() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, Paragraph, "앞뒤")))

        val selection = model.insertSoftBreak(id, atUtf16Offset = 1)!!

        assertEquals(listOf("앞", "뒤", ""), model.texts)
        assertEquals(id, model.blocks[0].id)
        assertEquals(BlockSelection(model.blocks[1].id, r(0, 0)), selection)
    }

    /** iOS: 초기 일반 텍스트의 줄바꿈은 블록으로 나누고 코드 줄바꿈은 보존한다. */
    @Test
    fun initialTextNormalizesLineBreaksByBlockKind() {
        val model = BlockEditorModel(listOf(
            EditorBlock(kind = Paragraph, text = "첫 줄\n둘째"),
            EditorBlock(kind = EditorBlockKind.Code("swift"), text = "let\nx"),
        ))

        assertEquals(listOf("첫 줄", "둘째", "let\nx", ""), model.texts)
        assertEquals(Paragraph, model.blocks[0].kind)
        assertEquals(Paragraph, model.blocks[1].kind)
        assertEquals(EditorBlockKind.Code("swift"), model.blocks[2].kind)
    }

    /** iOS: 일반 블록 시작의 Backspace는 이전 블록과 합친다. */
    @Test
    fun mergeAtBlockStart() {
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val model = BlockEditorModel(listOf(
            EditorBlock(firstId, Paragraph, "앞"),
            EditorBlock(secondId, Paragraph, "뒤"),
        ))

        val selection = model.backspaceAtStart(secondId)!!

        assertEquals(listOf("앞뒤", ""), model.texts)
        assertEquals(firstId, model.blocks[0].id)
        assertTrue(model.blocks.none { it.id == secondId })
        assertEquals(BlockSelection(firstId, r(1, 0)), selection)
    }

    /** iOS: 서식 블록 시작의 Backspace는 문단으로 변환한다. */
    @Test
    fun convertFormattedBlockBeforeMerging() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, Heading(2), "제목")))

        val selection = model.backspaceAtStart(id)!!

        assertEquals(EditorBlock(id, Paragraph, "제목"), model.blocks[0])
        assertEquals(BlockSelection(id, r(0, 0)), selection)
    }

    /** iOS: 빈 목록에서 Enter를 누르면 같은 ID의 일반 문단이 된다. */
    @Test
    fun emptyListBecomesParagraph() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, BulletedList, "")))

        val selection = model.splitBlock(id, atUtf16Offset = 0)!!

        assertEquals(EditorBlock(id, Paragraph, ""), model.blocks.first())
        assertEquals(id, selection.blockId)
    }

    // MARK: - 목록 번호

    /** iOS: 연속 목록 항목은 독립 블록이며 번호가 이어진다. */
    @Test
    fun consecutiveListItemsBecomeBlocks() {
        val model = BlockEditorModel("- 하나\n- 둘\n\n1. 첫째\n2. 둘째")

        assertEquals(listOf("하나", "둘", "첫째", "둘째", ""), model.texts)
        assertEquals(BulletedList, model.blocks[0].kind)
        assertEquals(BulletedList, model.blocks[1].kind)
        assertEquals(NumberedList, model.blocks[2].kind)
        assertEquals(NumberedList, model.blocks[3].kind)
        assertEquals(1, model.numberedListOrdinal(model.blocks[2].id))
        assertEquals(2, model.numberedListOrdinal(model.blocks[3].id))
        assertEquals("2. 둘째", model.blocks[3].markdown(numberedListOrdinal = 2))

        val nested = EditorBlock.fromMarkdown("  - 하위 항목")
        assertEquals(BulletedList, nested.kind)
        assertEquals(1, nested.indentLevel)
        assertEquals("하위 항목", nested.text)
        assertEquals("  - 하위 항목", nested.markdown)
    }

    /** iOS: 중첩 번호 목록 뒤의 같은 깊이 번호는 이어지고 같은 깊이 문단에서 초기화된다. */
    @Test
    fun numberedListOrdinalSkipsNestedBlocksAndResetsAtPeerParagraph() {
        val first = EditorBlock(kind = NumberedList, text = "바깥 첫째")
        val nested = EditorBlock(kind = NumberedList, text = "안쪽 첫째", indentLevel = 1)
        val second = EditorBlock(kind = NumberedList, text = "바깥 둘째")
        val paragraph = EditorBlock(kind = Paragraph, text = "구분")
        val reset = EditorBlock(kind = NumberedList, text = "다시 첫째")
        val model = BlockEditorModel(listOf(first, nested, second, paragraph, reset))

        assertEquals(1, model.numberedListOrdinal(first.id))
        assertEquals(1, model.numberedListOrdinal(nested.id))
        assertEquals(2, model.numberedListOrdinal(second.id))
        assertEquals(1, model.numberedListOrdinal(reset.id))
    }

    // MARK: - 명령·서식

    /** iOS: 복제·삭제·종류 변환은 undo와 redo로 복구된다. */
    @Test
    fun editCommandsAreUndoable() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, Paragraph, "본문")))

        val duplicateSelection = model.duplicate(id)!!
        assertEquals(listOf("본문", "본문"), model.texts.take(2))
        assertNotEquals(id, duplicateSelection.blockId)

        model.transform(duplicateSelection.blockId, Quote)
        assertEquals(Quote, model.blocks[1].kind)
        model.delete(id)
        assertEquals(Quote, model.blocks.first().kind)

        assertTrue(model.undo())
        assertEquals(id, model.blocks.first().id)
        assertTrue(model.undo())
        assertEquals(Paragraph, model.blocks[1].kind)
        assertTrue(model.undo())
        assertEquals(listOf("본문", ""), model.texts)

        assertTrue(model.redo())
        assertEquals(listOf("본문", "본문"), model.texts.take(2))
    }

    /** iOS: 인라인 서식·바로가기·들여쓰기도 같은 전이 모델을 사용한다. */
    @Test
    fun formattingShortcutAndIndent() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, Paragraph, "본문")))

        val formatSelection = model.applyInlineFormat(InlineFormat.Bold, id, r(0, 2))!!
        assertEquals("본문", model.blocks[0].text)
        assertEquals(listOf(InlineMark(InlineFormat.Bold, r(0, 2))), model.blocks[0].inlineMarks)
        assertEquals(r(0, 2), formatSelection.range)

        model.updateText(id, "- 본문")
        assertNotNull(model.applyShortcut(id, BulletedList, prefixUtf16Length = 2))
        assertEquals(BulletedList, model.blocks[0].kind)
        assertEquals("본문", model.blocks[0].text)

        assertTrue(model.indent(id))
        assertEquals(1, model.blocks[0].indentLevel)
        assertTrue(model.outdent(id))
        assertEquals(0, model.blocks[0].indentLevel)
    }

    /** iOS: 일반 문단도 들여쓰기와 내어쓰기가 화면에 반영된다. (headIndent 단언은 Phase 2) */
    @Test
    fun paragraphIndentUpdatesLayout() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, Paragraph, "본문")))

        assertTrue(model.indent(id))
        assertEquals(1, model.blocks[0].indentLevel)
        assertTrue(model.outdent(id))
        assertEquals(0, model.blocks[0].indentLevel)
    }

    /** iOS: 인라인 서식 토글은 선택한 하위 범위만 해제한다. */
    @Test
    fun inlineFormatToggleSubtractsSelectedRange() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(
            EditorBlock(id, Paragraph, "가나다라", listOf(InlineMark(InlineFormat.Bold, r(0, 4)))),
        ))

        val selection = model.applyInlineFormat(InlineFormat.Bold, id, r(1, 2))!!

        assertEquals(r(1, 2), selection.range)
        assertEquals(
            listOf(InlineMark(InlineFormat.Bold, r(0, 1)), InlineMark(InlineFormat.Bold, r(3, 1))),
            model.blocks[0].inlineMarks,
        )
    }

    /** iOS: 위아래 이동은 문서 경계를 넘지 않는다. */
    @Test
    fun moveBoundaries() {
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val model = BlockEditorModel(listOf(
            EditorBlock(firstId, Paragraph, "첫째"),
            EditorBlock(secondId, Paragraph, "둘째"),
        ))

        assertFalse(model.moveUp(firstId))
        assertFalse(model.moveDown(secondId))
        assertTrue(model.moveDown(firstId))
        assertEquals(listOf(secondId, firstId), model.blocks.take(2).map { it.id })
        assertTrue(model.undo())
        assertEquals(listOf(firstId, secondId), model.blocks.take(2).map { it.id })
    }

    // MARK: - history

    /** iOS: 텍스트 입력은 undo 대상이고 새 입력은 redo 분기를 폐기한다. */
    @Test
    fun textInputParticipatesInHistory() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, Paragraph, "A")))

        model.updateText(id, "AB")
        assertTrue(model.undo())
        assertEquals("A", model.blocks[0].text)

        model.updateText(id, "AC")
        assertFalse(model.redo())
        assertEquals("AC", model.blocks[0].text)
    }

    /** iOS: undo와 redo는 문서와 함께 커서 선택을 복원한다. */
    @Test
    fun historyRestoresSelection() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, Paragraph, "AB")))
        val before = BlockSelection(id, r(1, 0))
        val after = BlockSelection(id, r(2, 0))

        model.updateSelection(before)
        model.updateText(id, "AXB")
        model.updateSelection(after)

        assertTrue(model.undo())
        assertEquals("AB", model.blocks[0].text)
        assertEquals(before, model.currentSelection)

        assertTrue(model.redo())
        assertEquals("AXB", model.blocks[0].text)
        assertEquals(after, model.currentSelection)
    }

    /** iOS: 텍스트와 커서 변경은 하나의 전이로 undo와 redo에 기록된다. */
    @Test
    fun textAndSelectionUpdateAtomically() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, Paragraph, "AB")))
        val before = BlockSelection(id, r(1, 0))
        val after = BlockSelection(id, r(2, 0))

        model.updateSelection(before)
        model.updateText(id, "AXB", after)

        assertEquals("AXB", model.blocks[0].text)
        assertEquals(after, model.currentSelection)
        assertTrue(model.undo())
        assertEquals("AB", model.blocks[0].text)
        assertEquals(before, model.currentSelection)
        assertTrue(model.redo())
        assertEquals("AXB", model.blocks[0].text)
        assertEquals(after, model.currentSelection)
    }

    /** iOS: 여러 블록 selection은 undo와 redo에서 전역 range로 복원된다. */
    @Test
    fun historyRestoresCrossBlockDocumentSelection() {
        val model = BlockEditorModel(listOf(
            EditorBlock(kind = Paragraph, text = "앞"),
            EditorBlock(kind = Paragraph, text = "뒤"),
        ))
        val before = r(0, 3)
        model.updateDocumentSelection(before)

        val replacementSelection = model.replaceDocumentText(before, "새")

        assertEquals(r(1, 0), replacementSelection)
        assertEquals(r(1, 0), model.currentDocumentSelection)
        assertTrue(model.undo())
        assertEquals("앞\n뒤\n", model.documentText)
        assertEquals(before, model.currentDocumentSelection)
        assertTrue(model.redo())
        assertEquals("새\n", model.documentText)
        assertEquals(r(1, 0), model.currentDocumentSelection)
    }

    // MARK: - 연속 문서

    /** iOS: 블록은 ID를 유지한 채 하나의 연속 UTF-16 문서로 투영된다. */
    @Test
    fun projectsBlocksIntoContinuousDocument() {
        val paragraphId = UUID.randomUUID()
        val codeId = UUID.randomUUID()
        val model = BlockEditorModel(listOf(
            EditorBlock(paragraphId, Paragraph, "가😀"),
            EditorBlock(codeId, EditorBlockKind.Code("swift"), "let\nx"),
        ))

        assertEquals("가😀\nlet\nx\n", model.documentText)
        assertEquals(r(0, 3), model.documentRange(paragraphId))
        assertEquals(r(4, 5), model.documentRange(codeId))
    }

    /** iOS: 여러 블록을 가로지른 교체는 중간 블록을 제거하고 양끝을 병합한다. */
    @Test
    fun replacesAcrossBlockBoundaries() {
        val firstId = UUID.randomUUID()
        val middleId = UUID.randomUUID()
        val lastId = UUID.randomUUID()
        val model = BlockEditorModel(listOf(
            EditorBlock(firstId, Heading(2), "첫째"),
            EditorBlock(middleId, Quote, "둘째"),
            EditorBlock(lastId, Paragraph, "셋째"),
        ))
        val firstRange = model.documentRange(firstId)!!
        val lastRange = model.documentRange(lastId)!!
        val selection = r(firstRange.location + 1, lastRange.location + 1 - firstRange.location - 1)

        val result = model.replaceDocumentText(selection, "X")

        assertNotNull(result)
        assertEquals(listOf("첫X째", ""), model.texts)
        assertEquals(firstId, model.blocks[0].id)
        assertEquals(Heading(2), model.blocks[0].kind)
        assertTrue(model.blocks.none { it.id == middleId || it.id == lastId })
    }

    /** iOS: 일반 줄바꿈은 새 블록이고 코드 줄바꿈은 같은 블록이다. */
    @Test
    fun distinguishesBlockBreakFromCodeSoftBreak() {
        val paragraphId = UUID.randomUUID()
        val codeId = UUID.randomUUID()
        val paragraphModel = BlockEditorModel(listOf(EditorBlock(paragraphId, Paragraph, "앞뒤")))
        val codeModel = BlockEditorModel(listOf(EditorBlock(codeId, EditorBlockKind.Code("swift"), "let x")))

        val paragraphRange = paragraphModel.documentRange(paragraphId)!!
        val paragraphSelection = paragraphModel.replaceDocumentText(r(paragraphRange.location + 1, 0), "\n")
        val codeRange = codeModel.documentRange(codeId)!!
        val codeSelection = codeModel.replaceDocumentText(r(codeRange.location + 3, 0), "\n")

        assertEquals(listOf("앞", "뒤", ""), paragraphModel.texts)
        assertEquals(paragraphId, paragraphModel.blocks[0].id)
        assertEquals(2, paragraphSelection?.location)
        assertEquals(listOf("let\n x", ""), codeModel.texts)
        assertEquals(codeId, codeModel.blocks[0].id)
        assertEquals(4, codeSelection?.location)
    }

    /** iOS: 연속 문서 Enter는 빈 목록을 같은 ID의 문단으로 바꾼다. */
    @Test
    fun documentEnterExitsEmptyList() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, BulletedList, "")))

        val selection = model.replaceDocumentText(r(0, 0), "\n")

        assertEquals(EditorBlock(id, Paragraph, ""), model.blocks.first())
        assertEquals(r(0, 0), selection)
    }

    /** iOS: 연속 문서 Backspace는 블록 시작의 서식 변환과 코드 경계를 보존한다. */
    @Test
    fun documentBackspaceUsesBlockBoundaryRules() {
        val paragraphId = UUID.randomUUID()
        val headingId = UUID.randomUUID()
        val headingModel = BlockEditorModel(listOf(
            EditorBlock(paragraphId, Paragraph, "앞"),
            EditorBlock(headingId, Heading(2), "제목"),
        ))
        val headingRange = headingModel.documentRange(headingId)!!

        val headingSelection = headingModel.replaceDocumentText(r(headingRange.location - 1, 1), "")

        assertEquals(EditorBlock(headingId, Paragraph, "제목"), headingModel.blocks[1])
        assertEquals(r(headingRange.location, 0), headingSelection)

        val codeId = UUID.randomUUID()
        val followingId = UUID.randomUUID()
        val codeModel = BlockEditorModel(listOf(
            EditorBlock(codeId, EditorBlockKind.Code("swift"), "let x"),
            EditorBlock(followingId, Paragraph, "다음"),
        ))
        val followingRange = codeModel.documentRange(followingId)!!
        val before = codeModel.blocks

        val codeBoundarySelection = codeModel.replaceDocumentText(r(followingRange.location - 1, 1), "")

        assertEquals(before, codeModel.blocks)
        assertEquals(r(followingRange.location - 1, 0), codeBoundarySelection)
    }

    /** iOS: 전체 선택 교체는 하나의 일반 문단과 후속 빈 문단을 만든다. */
    @Test
    fun selectAllReplacementCreatesParagraph() {
        val model = BlockEditorModel(listOf(
            EditorBlock(kind = Heading(1), text = "제목"),
            EditorBlock(kind = BulletedList, text = "항목"),
        ))

        val selection = model.replaceDocumentText(r(0, model.documentText.length), "새 문서")

        assertEquals(listOf("새 문서", ""), model.texts)
        assertEquals(Paragraph, model.blocks[0].kind)
        assertEquals(r(4, 0), selection)
    }

    // MARK: - 전체 문서 블록 교체

    /** iOS: 전체 문서 앱 내부 복사·붙여넣기는 블록 종류와 선택을 보존한다. */
    @Test
    fun wholeDocumentBlockRoundTripPreservesBlocks() {
        val source = BlockEditorModel("# **제목**\n본문 \\(x\\)\n\\[\nE = mc^2\n\\]\n- [x] 할 일")
        val target = BlockEditorModel("기존")

        val selection = target.replaceDocumentBlocks(r(0, target.documentText.length), source.blocks)!!

        assertEquals(source.kinds, target.kinds)
        assertEquals(source.texts, target.texts)
        assertEquals(source.blocks.map { it.inlineMarks }, target.blocks.map { it.inlineMarks })
        assertEquals(r(target.documentText.length - 1, 0), selection)
    }

    /** iOS: 앱 내부 전체 문서 붙여넣기는 Markdown과 충돌하는 원문 블록도 그대로 보존한다. */
    @Test
    fun wholeDocumentBlockPastePreservesAmbiguousBlocks() {
        val source = BlockEditorModel(listOf(
            EditorBlock(kind = Paragraph, text = "# 리터럴 제목"),
            EditorBlock(kind = Paragraph, text = ""),
            EditorBlock(kind = Paragraph, text = "- 리터럴 목록"),
            EditorBlock(kind = EditorBlockKind.Code("swift"), text = "let fence = \"```\""),
        ))
        val target = BlockEditorModel("기존")

        val selection = target.replaceDocumentBlocks(r(0, target.documentText.length), source.blocks)!!

        assertEquals(source.kinds, target.kinds)
        assertEquals(source.texts, target.texts)
        assertEquals(source.blocks.map { it.inlineMarks }, target.blocks.map { it.inlineMarks })
        assertEquals(r(target.documentText.length - 1, 0), selection)
    }

    /** iOS: 외부 plain text로 전체 교체해도 Markdown 블록 문법으로 해석하지 않는다. */
    @Test
    fun wholeDocumentPlainTextReplacementDoesNotParseMarkdown() {
        val model = BlockEditorModel("# 기존 제목")

        val selection = model.replaceDocumentText(r(0, model.documentText.length), "# 리터럴 제목\n\n- 리터럴 목록")

        assertEquals(listOf(Paragraph, Paragraph, Paragraph, Paragraph), model.kinds)
        assertEquals(listOf("# 리터럴 제목", "", "- 리터럴 목록", ""), model.texts)
        assertEquals(r(model.documentText.length - 1, 0), selection)
    }
}
