// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Heading
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Paragraph
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Quote
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.ToDo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Android 포트가 iOS와 의도적으로 다르게 정한 4가지 편차와, iOS 테스트에 직접 근거가 없던 모델 경계를 고정한다.
 * 편차 1 grapheme 경계 = BreakIterator, 2 surrogate 중간 범위 거절, 3 CRLF·CR → LF, 4 Swift `.whitespaces` 트림.
 */
class AndroidPortContractTest {

    // MARK: - 편차

    /** 편차 2: surrogate pair 중간에서 시작·끝나는 범위는 서식·선택·편집 모두 거절한다(iOS는 내림 보정). */
    @Test
    fun surrogateMiddleRangesAreRejected() {
        val block = EditorBlock(
            text = "😀a",
            inlineMarks = listOf(InlineMark(InlineFormat.Bold, r(1, 2)), InlineMark(InlineFormat.Italic, r(0, 1))),
        )
        val model = BlockEditorModel(listOf(block))

        assertTrue(block.inlineMarks.isEmpty())
        assertNull(model.replaceText(block.id, r(1, 0), "x"))
        assertNull(model.replaceDocumentText(r(1, 0), "x"))
        assertNull(model.applyInlineFormat(InlineFormat.Bold, block.id, r(0, 1)))
        assertNull(model.blockSelection(r(1, 0)))
        model.updateDocumentSelection(r(2, 0))
        model.updateDocumentSelection(r(1, 0))
        assertEquals(r(2, 0), model.currentDocumentSelection)
        model.updateSelection(BlockSelection(block.id, r(1, 0)))
        assertNull(model.currentSelection)
        assertEquals("😀a", model.blocks[0].text)
        assertFalse(model.canUndo)

        assertNotNull(model.applyInlineFormat(InlineFormat.Bold, block.id, r(0, 2)))
        assertEquals(listOf(InlineMark(InlineFormat.Bold, r(0, 2))), model.blocks[0].inlineMarks)
    }

    /** 편차 1: 분할·바로가기 접두어 길이는 BreakIterator 문자 경계여야 한다(결합 문자·ZWJ 이모지 내부 거절). */
    @Test
    fun graphemeBoundariesGateSplitAndShortcut() {
        val combining = EditorBlock(text = "éx")
        val model = BlockEditorModel(listOf(combining))
        assertNull(model.splitBlock(combining.id, atUtf16Offset = 1))
        assertNull(model.applyShortcut(combining.id, Quote, prefixUtf16Length = 1))
        assertNotNull(model.splitBlock(combining.id, atUtf16Offset = 2))
        assertEquals(listOf("é", "x", ""), model.texts)

        val family = "👨‍👩‍👧"
        val familyBlock = EditorBlock(text = family + "a")
        val familyModel = BlockEditorModel(listOf(familyBlock))
        assertNull(familyModel.splitBlock(familyBlock.id, atUtf16Offset = 2))
        assertNotNull(familyModel.splitBlock(familyBlock.id, atUtf16Offset = family.length))
        assertEquals(listOf(family, "a", ""), familyModel.texts)
    }

    /** 편차 3: Markdown 입력과 문서 교체 문자열의 CRLF·CR은 LF로 맞춘 뒤 해석한다. */
    @Test
    fun lineEndingsAreNormalizedToLineFeed() {
        val model = BlockEditorModel("# 제목\r\n본문\r둘째\r\n\r\n```swift\r\nlet x\r\n```")
        assertEquals(listOf(Heading(1), Paragraph, Paragraph, EditorBlockKind.Code("swift"), Paragraph), model.kinds)
        assertEquals(listOf("제목", "본문", "둘째", "let x", ""), model.texts)

        val code = EditorBlock.fromMarkdown("```\r\ncode\r\n```")
        assertEquals(EditorBlockKind.Code(null), code.kind)
        assertEquals("code", code.text)

        val document = BlockEditorModel(listOf(EditorBlock(text = "가나")))
        val selection = document.replaceDocumentText(r(1, 0), "앞\r\n뒤")
        assertEquals(listOf("가앞", "뒤나", ""), document.texts)
        assertEquals(r(4, 0), selection)
    }

    /** 편차 4: fence 트림은 Swift `.whitespaces`(Zs + 탭)만 지운다. Kotlin `trim()`과 달리 FF 같은 제어 문자는 남는다. */
    @Test
    fun swiftWhitespaceTrimExcludesNewlinesAndControls() {
        val spaced = EditorBlock.fromMarkdown("```swift \ncode\n\t```　")
        assertEquals(EditorBlockKind.Code("swift"), spaced.kind)
        assertEquals("code", spaced.text)

        val formFeed = EditorBlock.fromMarkdown("```\ncode\n```\u000C")
        assertEquals(Paragraph, formFeed.kind)
        assertEquals("x \u000C", "\t  x \u000C".trimmingSwiftWhitespaces())
        assertEquals("\nx\n", " \nx\n\t".trimmingSwiftWhitespaces())
    }

    // MARK: - iOS 테스트가 직접 다루지 않던 모델 경계

    /** iOS `coordinatorUsesTextViewEditRange`의 모델 부분: 빈 문단의 Enter는 같은 ID를 유지하는 무변경이다. */
    @Test
    fun emptyParagraphEnterIsNoOp() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, Paragraph, "\n")))
        assertEquals(listOf("", ""), model.texts)

        val selection = model.replaceDocumentText(r(0, 0), "\n")

        assertEquals(EditorBlock(id, Paragraph, ""), model.blocks.first())
        assertEquals(r(0, 0), selection)
        assertEquals(r(0, 0), model.currentDocumentSelection)
        assertFalse(model.canUndo)
    }

    /** spec E-17: undo 기록은 최대 100개이며 redo도 그만큼만 되돌아온다. */
    @Test
    fun historyKeepsAtMostHundredEntries() {
        val id = UUID.randomUUID()
        val model = BlockEditorModel(listOf(EditorBlock(id, Paragraph, "0")))
        repeat(150) { model.updateText(id, "${it + 1}") }

        var undos = 0
        while (model.undo()) undos += 1
        assertEquals(100, undos)
        assertEquals("50", model.blocks[0].text)

        var redos = 0
        while (model.redo()) redos += 1
        assertEquals(100, redos)
        assertEquals("150", model.blocks[0].text)
    }

    /** 할 일 체크 상태와 번호 목록 재계산이 문서 Markdown 왕복에서 유지된다. */
    @Test
    fun documentMarkdownRestoresToDoAndRenumbersLists() {
        val model = BlockEditorModel("- [x] 완료\n- [ ] 할 일\n\n3. 셋\n7. 일곱")

        assertEquals(listOf(ToDo(true), ToDo(false), EditorBlockKind.NumberedList, EditorBlockKind.NumberedList, Paragraph), model.kinds)
        assertEquals("- [x] 완료\n- [ ] 할 일\n1. 셋\n2. 일곱\n", model.markdown)
    }
}
