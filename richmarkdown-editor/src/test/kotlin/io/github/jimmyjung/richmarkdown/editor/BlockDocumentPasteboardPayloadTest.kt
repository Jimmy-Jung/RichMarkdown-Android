// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.BulletedList
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Equation
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Heading
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.NumberedList
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Paragraph
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Quote
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.ToDo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 블록 클립보드 payload(version 1 JSON)의 형태·왕복·방어 규칙. iOS `BlockDocumentPasteboardPayload`에는
 * 전용 테스트가 없어(spec E-21) Android에서 새로 고정한다.
 */
class BlockDocumentPasteboardPayloadTest {

    private fun encodeToString(blocks: List<EditorBlock>): String? =
        BlockDocumentPasteboardPayload.encode(blocks)?.toString(Charsets.UTF_8)

    private fun decode(json: String): List<EditorBlock>? = BlockDocumentPasteboardPayload.decode(json.toByteArray())

    private fun block(kind: String, extra: String = "") =
        """{"version":1,"blocks":[{"kind":$kind,"text":"ab","inlineMarks":[],"indentLevel":0$extra}]}"""

    @Test
    fun encodesSwiftCodableShape() {
        val heading = EditorBlock(kind = Heading(2), text = "제목", inlineMarks = listOf(InlineMark(InlineFormat.Italic, r(0, 1))))
        assertEquals(
            """{"version":1,"blocks":[{"kind":{"heading":{"level":2}},"text":"제목","inlineMarks":[{"format":1,"range":[0,1]}],"indentLevel":0}]}""",
            encodeToString(listOf(heading)),
        )
        assertEquals(
            """{"version":1,"blocks":[{"kind":{"code":{}},"text":"a\"b\\c\n\u0001","inlineMarks":[],"indentLevel":0}]}""",
            encodeToString(listOf(EditorBlock(kind = EditorBlockKind.Code(null), text = "a\"b\\c\n\u0001"))),
        )
        assertTrue(encodeToString(listOf(EditorBlock(kind = EditorBlockKind.Code("swift"), text = "")))!!
            .contains(""""kind":{"code":{"language":"swift"}}"""))
        assertTrue(encodeToString(listOf(EditorBlock(kind = ToDo(true), text = "", indentLevel = 2)))!!
            .contains(""""kind":{"toDo":{"isChecked":true}},"text":"","inlineMarks":[],"indentLevel":2"""))
    }

    @Test
    fun roundTripPreservesKindsTextMarksAndIndentWithNewIds() {
        val source = listOf(
            EditorBlock(kind = Heading(3), text = "제목 😀", inlineMarks = listOf(InlineMark(InlineFormat.Bold, r(3, 2)))),
            EditorBlock(kind = Paragraph, text = "", indentLevel = 1),
            EditorBlock(kind = BulletedList, text = "항목", indentLevel = 3),
            EditorBlock(kind = NumberedList, text = "번호", inlineMarks = listOf(InlineMark(InlineFormat.Code, r(0, 2)))),
            EditorBlock(kind = ToDo(false), text = "할 일"),
            EditorBlock(kind = Quote, text = "인용", inlineMarks = listOf(InlineMark(InlineFormat.Strikethrough, r(0, 1)))),
            EditorBlock(kind = EditorBlockKind.Code("kotlin"), text = "val x = \"\\n\"\n\t끝"),
            EditorBlock(kind = Equation, text = "\\frac{a}{b}"),
        )

        val decoded = BlockDocumentPasteboardPayload.decode(BlockDocumentPasteboardPayload.encode(source)!!)!!

        assertEquals(source.map { it.kind }, decoded.map { it.kind })
        assertEquals(source.map { it.text }, decoded.map { it.text })
        assertEquals(source.map { it.inlineMarks }, decoded.map { it.inlineMarks })
        assertEquals(source.map { it.indentLevel }, decoded.map { it.indentLevel })
        assertTrue(source.zip(decoded).none { (a, b) -> a.id == b.id })
    }

    @Test
    fun decodeClampsAndNormalizesTamperedPayload() {
        val json = """
            { "extra": [1, 2.5e3, {"a": null}], "version": 1, "blocks": [
              {"kind": {"heading": {"level": 99999999999}}, "text": "a\ud83d\ude00b",
               "inlineMarks": [{"format": 0, "range": [0, 99]}, {"format": 0, "range": [0, 1, 7]}], "indentLevel": -7},
              {"kind": {"code": {"language": null}, "unknown": {}}, "text": "x",
               "inlineMarks": [{"format": 0, "range": [0, 1]}], "indentLevel": 1e1}
            ]}
        """.trimIndent()

        val blocks = decode(json)!!

        assertEquals(Heading(3), blocks[0].kind)
        assertEquals("a😀b", blocks[0].text)
        assertEquals(listOf(InlineMark(InlineFormat.Bold, r(0, 1))), blocks[0].inlineMarks)
        assertEquals(0, blocks[0].indentLevel)
        assertEquals(EditorBlockKind.Code(null), blocks[1].kind)
        // iOS와 같이 decode는 코드 블록의 서식을 지우지 않는다(직렬화·표시에서 무시된다).
        assertEquals(listOf(InlineMark(InlineFormat.Bold, r(0, 1))), blocks[1].inlineMarks)
        assertEquals(3, blocks[1].indentLevel)
    }

    @Test
    fun rejectsInvalidPayloads() {
        assertNull(BlockDocumentPasteboardPayload.encode(emptyList()))
        assertNull(BlockDocumentPasteboardPayload.encode(listOf(EditorBlock(text = "가".repeat(90_000)))))

        val rejected = listOf(
            block("""{"paragraph":{}}""").replace("\"version\":1", "\"version\":2"),
            """{"version":1,"blocks":[]}""",
            """{"blocks":[]}""",
            """{"version":1,"blocks":[{"kind":{"paragraph":{}},"inlineMarks":[],"indentLevel":0}]}""",
            block("""{"foo":{}}"""),
            block("""{"paragraph":{},"quote":{}}"""),
            block("""{"paragraph":true}"""),
            block("""{"heading":{"level":1.5}}"""),
            block("""{"toDo":{}}"""),
            block("""{"code":{"language":3}}"""),
            block("""{"paragraph":{}}""").replace("\"inlineMarks\":[]", "\"inlineMarks\":[{\"format\":4,\"range\":[0,1]}]"),
            block("""{"paragraph":{}}""").replace("\"inlineMarks\":[]", "\"inlineMarks\":[{\"format\":0,\"range\":[0]}]"),
            block("""{"paragraph":{}}""") + " x",
            block("""{"paragraph":{}}""").replace("\"ab\"", "'ab'"),
            block("""{"paragraph":{}}""").replace("\"ab\"", "\"a\u0001b\""),
            block("""{"paragraph":{}}""", ""","deep":${"[".repeat(20)}${"]".repeat(20)}"""),
            """{"version":01,"blocks":[]}""",
            "[]",
            "",
        )
        for (json in rejected) assertNull(json, decode(json))

        assertNull(BlockDocumentPasteboardPayload.decode(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)))
        val oversized = block("""{"paragraph":{}}""").replace("\"ab\"", "\"${"a".repeat(256 * 1024)}\"")
        assertNull(decode(oversized))
    }
}
