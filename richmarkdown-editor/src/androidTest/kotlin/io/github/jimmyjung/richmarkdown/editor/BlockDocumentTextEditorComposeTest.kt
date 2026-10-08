// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import android.content.ClipboardManager
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.BulletedList
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Code
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Heading
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Paragraph
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.ToDo
import io.github.jimmyjung.richmarkdown.editor.compose.BlockDocumentTextEditor
import io.github.jimmyjung.richmarkdown.view.MathAttachmentSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compose 래퍼(재구성으로 상태를 넘기는 호스트)·attach 수명의 수식 raster·실제 클립보드 왕복. */
class BlockDocumentTextEditorComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** iOS SwiftUI 데모와 같은 연결: 모델은 앱이 소유하고 콜백 뒤 State로 다시 넘긴다. */
    private class ComposeHost(val model: BlockEditorModel) {
        var blocks by mutableStateOf(model.blocks)
        var selection by mutableStateOf(model.currentDocumentSelection)
        var parsesDollarMath by mutableStateOf(false)
        val pasted = mutableListOf<List<EditorBlock>>()

        fun publish() {
            blocks = model.blocks
            selection = model.currentDocumentSelection
        }
    }

    private fun setContent(host: ComposeHost): BlockDocumentEditText {
        compose.setContent {
            BlockDocumentTextEditor(
                blocks = host.blocks,
                selection = host.selection,
                canUndo = host.model.canUndo,
                canRedo = host.model.canRedo,
                onReplaceText = { range, text -> host.model.replaceDocumentText(range, text).also { host.publish() } },
                onSelectionChange = {
                    host.model.updateDocumentSelection(it)
                    host.publish()
                },
                onToolbarAction = { _, _ -> },
                modifier = Modifier.fillMaxSize(),
                onReplaceDocumentBlocks = { range, blocks ->
                    host.pasted += blocks
                    host.model.replaceDocumentBlocks(range, blocks).also { host.publish() }
                },
                parsesDollarMath = host.parsesDollarMath,
                sourceMarkdown = host.model.markdown,
            )
        }
        compose.waitForIdle()
        return compose.runOnIdle {
            compose.activity.window.decorView.findViewWithTag<BlockDocumentEditText>(BlockDocumentEditText.VIEW_TAG)
        }
    }

    /** 모델이 무시한 Enter는 blocks가 그대로라 호스트 State가 바뀌지 않는다 — 래퍼 revision이 재적용을 보장한다. */
    @Test
    fun composeWrapperResyncsAfterIgnoredAndAcceptedEdits() {
        val model = BlockEditorModel(listOf(EditorBlock(text = "가")))
        model.updateDocumentSelection(r(2, 0))
        val host = ComposeHost(model)
        val view = setContent(host)

        compose.runOnIdle { view.onCreateInputConnection(EditorInfo())!!.commitText("\n", 1) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("가\n", model.documentText)
            assertEquals(model.documentText, view.text.toString())
            assertEquals(2, view.selectionStart)
        }

        compose.runOnIdle {
            view.setSelection(1)
            view.onCreateInputConnection(EditorInfo())!!.commitText("나", 1)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("가나\n", model.documentText)
            assertEquals(model.documentText, view.text.toString())
            assertEquals(r(2, 0), model.currentDocumentSelection)
        }
    }

    /** attach 동안 없는 수식 raster를 요청하고, 도착하면 span만 바꿔 원문 위에 이미지를 덮는다. */
    @Test
    fun missingInlineMathIsRenderedAfterAttach() {
        val model = BlockEditorModel(listOf(EditorBlock(text = "앞 \\(\\frac{a_{7}}{b+13}\\) 뒤")))
        model.updateDocumentSelection(r(model.documentText.length, 0))
        val view = setContent(ComposeHost(model))
        compose.waitUntil(10_000) {
            view.text.getSpans(0, view.text.length, MathAttachmentSpan::class.java).isNotEmpty()
        }
        compose.runOnIdle { assertEquals(model.documentText, view.text.toString()) }
    }

    /** iOS에 전용 테스트가 없던 실제 pasteboard 경로(E-20). 전체 선택 복사 → ClipboardManager → 붙여넣기로 블록 구조 복원. */
    @Test
    fun clipboardPayloadRoundTripsBlocks() {
        val original = BlockEditorModel(
            listOf(
                EditorBlock(kind = Heading(2), text = "제목"),
                EditorBlock.fromMarkdown("**굵게** 본문"),
                EditorBlock(kind = ToDo(true), text = "완료"),
                EditorBlock(kind = BulletedList, text = "들여쓴 항목", indentLevel = 2),
                EditorBlock(kind = Code("kotlin"), text = "val x = 1\nval y = 2"),
                EditorBlock(kind = Paragraph, text = ""),
                EditorBlock(kind = Paragraph, text = "끝"),
            ),
        )
        val host = ComposeHost(original)
        val view = setContent(host)
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)

        compose.runOnIdle {
            assertTrue(view.requestFocus())
            view.setSelection(0, view.text.length)
            assertTrue(view.onTextContextMenuItem(android.R.id.copy))
        }
        compose.runOnIdle {
            val clip = clipboard.primaryClip
            assertNotNull("포커스를 가진 앱만 클립보드를 읽는다", clip)
            assertEquals(original.markdown, clip!!.getItemAt(0).text.toString())
            assertTrue(clip.description.hasMimeType(BlockDocumentEditText.BLOCK_DOCUMENT_MIME_TYPE))
            val json = clip.description.extras?.getString(BlockDocumentEditText.BLOCK_DOCUMENT_EXTRA)!!
            assertEquals(original.blocks.map { it.text }, BlockDocumentPasteboardPayload.decode(json.encodeToByteArray())!!.map { it.text })
        }

        // 빈 문서 전체를 선택한 채 붙여넣으면 블록 구조(종류·서식·들여쓰기·빈 문단)가 그대로 돌아온다.
        val expected = original.blocks
        compose.runOnIdle {
            host.model.replaceDocumentText(r(0, host.model.documentText.length), "")
            host.publish()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            view.setSelection(0, view.text.length)
            assertTrue(view.onTextContextMenuItem(android.R.id.paste))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val restored = host.model.blocks
            assertEquals(1, host.pasted.size)
            assertEquals(expected.map { it.kind }, restored.map { it.kind })
            assertEquals(expected.map { it.text }, restored.map { it.text })
            assertEquals(expected.map { it.inlineMarks }, restored.map { it.inlineMarks })
            assertEquals(expected.map { it.indentLevel }, restored.map { it.indentLevel })
            assertEquals(host.model.documentText, view.text.toString())
        }
    }
}
