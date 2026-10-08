// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jimmyjung.richmarkdown.MathRenderKey
import io.github.jimmyjung.richmarkdown.MathRenderService
import io.github.jimmyjung.richmarkdown.RichMarkdownTheme
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.BulletedList
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Code
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Equation
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Paragraph
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Quote
import io.github.jimmyjung.richmarkdown.resolvedTextColor
import io.github.jimmyjung.richmarkdown.textSizePx
import io.github.jimmyjung.richmarkdown.view.MathAttachmentSpan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** iOS view·IME 테스트 이식 + Android 편집 뷰 고유 계약(재동기화·도구 모음·단축키·다크 모드·수식 줄 높이). */
@RunWith(AndroidJUnit4::class)
class BlockDocumentEditTextTest {

    /** 부모 없이 쓰는 뷰도 TextView가 LayoutParams를 읽으므로(checkForResize) 일반 배치 값을 준다. */
    private fun newView() = BlockDocumentEditText(targetContext).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun InputConnection.isComposing(view: BlockDocumentEditText) = BaseInputConnection.getComposingSpanStart(view.text) != -1

    private fun BlockDocumentEditText.selection() = r(selectionStart, selectionEnd - selectionStart)

    /** 뷰보다 먼저 raster를 캐시에 넣는다. 폰트를 먼저 올리지 않으면 글리프 없는 이미지가 캐시된다(뷰는 init에서 올린다). */
    private fun cacheMath(key: MathRenderKey) {
        MathRenderService.shared.ensureFontsLoaded(targetContext)
        assertNotNull(runBlocking { MathRenderService.shared.render(key) })
    }

    // MARK: - iOS view 테스트

    @Test
    fun coordinatorClampsOverflowingSelections() {
        for (selection in OVERFLOW_SELECTIONS) onMain {
            val view = newView()
            view.setState(listOf(EditorBlock(text = "본문")), selection, canUndo = false, canRedo = false)
            val location = selection.location.coerceIn(0, 2)
            val length = selection.length.coerceIn(0, 2 - location)
            assertEquals("본문", view.text.toString())
            assertEquals(r(location, length), view.selection())
        }
    }

    /** iOS: TextKit 2 attachment 뷰 생성 → Android: 원문 전체를 덮는 ReplacementSpan이 실제 크기로 배치된다. */
    @Test
    fun equationAttachmentMaterializesInLayout() {
        val equation = EditorBlock(kind = Equation, text = "E = mc^2")
        val theme = RichMarkdownTheme.Default
        cacheMath(MathRenderKey(equation.text, theme.codeFont.textSizePx(targetContext), theme.resolvedTextColor(targetContext.isNightMode()), true))
        onMain {
            val view = newView()
            val model = BlockEditorModel(listOf(equation))
            model.updateDocumentSelection(r(model.documentText.length, 0))
            ModelHost(view, model)
            layout(view, 1080)
            val span = view.text.getSpans(0, view.text.length, DisplayMathSpan::class.java).single()
            assertEquals(0, view.text.getSpanStart(span))
            assertEquals(equation.text.length + 1, view.text.getSpanEnd(span))
            assertTrue(span.widthPx > 1)
            val line = view.layout.getLineForOffset(0)
            assertTrue(view.layout.getLineBottom(line) - view.layout.getLineTop(line) >= span.ascentPx + span.descentPx)
        }
    }

    /** iOS displayEquationAttachmentExpandsTextLine: 블록 수식 줄 높이가 이미지를 담고 앞뒤 문장과 겹치지 않는다. */
    @Test
    fun displayEquationAttachmentExpandsTextLine() {
        val preceding = EditorBlock(kind = Paragraph, text = "\$ 구분자도 켜져 있으면 수식입니다: \$e^{i\\pi} + 1 = 0\$")
        val equation = EditorBlock(kind = Equation, text = "\\int_{-\\infty}^{\\infty} e^{-x^2} \\, dx = \\sqrt{\\pi}")
        val following = EditorBlock(kind = BulletedList, text = "할 일 하나")
        val theme = RichMarkdownTheme.Default
        cacheMath(MathRenderKey(equation.text, theme.codeFont.textSizePx(targetContext), theme.resolvedTextColor(targetContext.isNightMode()), true))
        val view = onMain {
            newView().also { view ->
                val model = BlockEditorModel(listOf(preceding, equation, following))
                model.updateDocumentSelection(r(model.documentText.length, 0))
                ModelHost(view, model)
                layout(view, 720)
            }
        }
        // 첫 배치 뒤 폭에 맞춘 재스타일(축소)이 한 번 더 일어난다.
        instrumentation.waitForIdleSync()
        onMain {
            layout(view, 720)
            val layout = view.layout
            val equationStart = preceding.text.length + 1
            val span = view.text.getSpans(0, view.text.length, DisplayMathSpan::class.java).single()
            assertTrue(span.widthPx <= 720)
            val line = layout.getLineForOffset(equationStart)
            val imageTop = layout.getLineBaseline(line) - span.ascentPx
            val imageBottom = layout.getLineBaseline(line) + span.descentPx
            assertTrue("앞 문장 ${layout.getLineBottom(line - 1)} > 수식 위 $imageTop", layout.getLineBottom(line - 1) <= imageTop)
            val followingLine = layout.getLineForOffset(equationStart + equation.text.length + 1)
            assertTrue("수식 아래 $imageBottom > 다음 문장 ${layout.getLineTop(followingLine)}", imageBottom <= layout.getLineTop(followingLine))
        }
    }

    @Test
    fun equationSourceTransitionAlignsUnicodeCaret() {
        val block = EditorBlock(kind = Equation, text = "😀x")
        val after = EditorBlock(text = "뒤")
        var reported: EditorRange? = null
        val view = onMain {
            newView().also { view ->
                view.onSelectionChange = { reported = it }
                view.setState(listOf(block, after), r(4, 0), canUndo = false, canRedo = false)
                view.setSelection(1)
            }
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals(block.text + "\n" + after.text, view.text.toString())
            assertEquals(r(0, 0), view.selection())
            assertEquals(r(0, 0), reported)
        }
    }

    // MARK: - iOS IME 테스트

    @Test
    fun markedTextCommitsOneReplacement() = onMain {
        val view = newView()
        val model = BlockEditorModel(listOf(EditorBlock(text = "가")))
        model.updateDocumentSelection(r(1, 0))
        val host = ModelHost(view, model)
        val connection = view.onCreateInputConnection(EditorInfo())!!

        for (composing in listOf("ㅎ", "하", "한")) {
            connection.setComposingText(composing, 1)
            assertTrue(connection.isComposing(view))
            assertTrue("조합 중에는 모델에 알리지 않는다", host.replacements.isEmpty())
            assertEquals("가\n", model.documentText)
        }
        connection.commitText("한", 1)

        assertEquals(listOf(r(1, 0) to "한"), host.replacements)
        assertEquals("가한\n", view.text.toString())
        assertEquals(model.documentText, view.text.toString())
        assertEquals(r(2, 0), view.selection())
    }

    /** iOS unmarkText = Android finishComposingText: 텍스트가 그대로라 TextWatcher가 울리지 않아도 확정된다. */
    @Test
    fun markedTextPreservesAmbiguousInsertionRange() = onMain {
        val view = newView()
        val model = BlockEditorModel(listOf(EditorBlock(text = "가가")))
        model.updateDocumentSelection(r(0, 0))
        val host = ModelHost(view, model)
        val connection = view.onCreateInputConnection(EditorInfo())!!

        connection.setComposingText("가", 1)
        assertTrue(host.replacements.isEmpty())
        connection.finishComposingText()

        assertEquals(listOf(r(0, 0) to "가"), host.replacements)
        assertEquals(r(1, 0), view.selection())
        assertEquals(model.documentText, view.text.toString())
    }

    @Test
    fun coordinatorUsesTextViewEditRange() = onMain {
        val id = UUID.randomUUID()
        val view = newView()
        val model = BlockEditorModel(listOf(EditorBlock(id = id, kind = Paragraph, text = "\n")))
        model.updateDocumentSelection(r(0, 0))
        val host = ModelHost(view, model)

        view.onCreateInputConnection(EditorInfo())!!.commitText("\n", 1)

        assertEquals(r(0, 0), host.replacements.single().first)
        assertEquals(EditorBlock(id = id, kind = Paragraph, text = ""), model.blocks.first())
        assertEquals(r(0, 0), model.currentDocumentSelection)
        // Android 개선: 모델이 무시한 Enter 뒤에도 화면이 모델 문서와 같다.
        assertEquals(model.documentText, view.text.toString())
        assertEquals(r(0, 0), view.selection())
    }

    // MARK: - Android 재동기화

    /** 빈 문단 Enter(무시), 빈 목록 Enter(종류만 변환), 코드 뒤 Backspace(caret만 이동), 인용 시작 Backspace(변환) 뒤 화면 == 모델. */
    @Test
    fun resyncsAfterIgnoredOrTransformedEdits() = onMain {
        fun check(blocks: List<EditorBlock>, caret: Int, edit: (InputConnection) -> Unit, expectedCaret: Int) {
            val view = newView()
            val model = BlockEditorModel(blocks)
            model.updateDocumentSelection(r(caret, 0))
            val host = ModelHost(view, model)
            val documentBefore = model.documentText
            edit(view.onCreateInputConnection(EditorInfo())!!)
            assertEquals(1, host.replacements.size)
            assertEquals(model.documentText, view.text.toString())
            assertEquals(documentBefore, view.text.toString())
            assertEquals(r(expectedCaret, 0), view.selection())
        }
        // "가\n" + 끝 빈 문단에서 Enter
        check(listOf(EditorBlock(text = "가")), caret = 2, edit = { it.commitText("\n", 1) }, expectedCaret = 2)
        // 빈 글머리표에서 Enter → 문단으로 나간다
        check(listOf(EditorBlock(kind = BulletedList, text = ""), EditorBlock(text = "뒤")), caret = 0, edit = { it.commitText("\n", 1) }, expectedCaret = 0)
        // 코드 블록 바로 뒤 문단 시작에서 Backspace → 합치지 않고 코드 끝으로 caret만 옮긴다
        check(listOf(EditorBlock(kind = Code(null), text = "let"), EditorBlock(text = "x")), caret = 4, edit = { it.deleteSurroundingText(1, 0) }, expectedCaret = 3)
        // 인용 시작에서 Backspace → 문단으로 변환
        check(listOf(EditorBlock(text = "앞"), EditorBlock(kind = Quote, text = "인용")), caret = 2, edit = { it.deleteSurroundingText(1, 0) }, expectedCaret = 2)
    }

    /** 렌더된 인라인 수식 바로 뒤 Backspace: 뷰가 지운 범위를 그대로 모델에 보내고 화면 == 모델. */
    @Test
    fun backspaceAfterRenderedInlineMathMatchesModel() {
        val theme = RichMarkdownTheme.Default
        cacheMath(MathRenderKey("x", theme.bodyFont.textSizePx(targetContext), theme.resolvedTextColor(targetContext.isNightMode()), false))
        lateinit var host: ModelHost
        onMain {
            val view = newView()
            val model = BlockEditorModel(listOf(EditorBlock(text = "a \\(x\\)")))
            model.updateDocumentSelection(r(7, 0))
            host = ModelHost(view, model)
            val math = view.text.getSpans(0, view.text.length, MathAttachmentSpan::class.java).single()
            assertEquals(2, view.text.getSpanStart(math))
            assertEquals(7, view.text.getSpanEnd(math))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        instrumentation.waitForIdleSync()
        onMain {
            val (range, replacement) = host.replacements.single()
            assertEquals("", replacement)
            assertEquals(7, range.end)
            assertEquals(host.model.documentText, host.view.text.toString())
            assertEquals("a \\(x\\)".removeRange(range.location, range.end), host.model.blocks.first().text)
        }
    }

    // MARK: - 도구 모음·단축키

    /**
     * Android 차이: 조합 중 명령은 무시하지 않고 조합을 확정한 뒤 보낸다(iOS는 무시). 확정 텍스트는 한 번만 모델에 가고,
     * 명령은 확정 뒤 선택을 받는다. 한글 음절과 Gboard식 라틴 단어 조합 모두 같다.
     */
    @Test
    fun toolbarCommitsCompositionOnceThenUsesCommittedSelection() = onMain {
        val bold = EditorToolbarAction.Format(InlineFormat.Bold)
        for ((composing, committed) in listOf(listOf("ㅎ", "하", "한") to "한", listOf("h", "he", "hello") to "hello")) {
            val view = newView()
            val model = BlockEditorModel(listOf(EditorBlock(text = "가")))
            model.updateDocumentSelection(r(1, 0))
            val host = ModelHost(view, model)
            val connection = view.onCreateInputConnection(EditorInfo())!!
            for (text in composing) connection.setComposingText(text, 1)
            assertTrue(host.replacements.isEmpty())

            view.performToolbarAction(bold)

            val caret = r(1 + committed.length, 0)
            assertEquals(listOf(r(1, 0) to committed), host.replacements)
            assertEquals(listOf(bold to caret), host.actions)
            assertTrue("조합이 끝나야 한다", !connection.isComposing(view))
            assertEquals("가$committed\n", model.documentText)
            assertEquals(model.documentText, view.text.toString())
            assertEquals(caret, view.selection())
            // 낡은 연결의 조합 종료는 이미 확정된 글자를 다시 보내지 않는다.
            connection.finishComposingText()
            assertEquals(1, host.replacements.size)
            // 확정 뒤 Enter·선택·명령 경로는 평소대로다.
            view.onCreateInputConnection(EditorInfo())!!.commitText("\n", 1)
            assertEquals(listOf(r(1, 0) to committed, caret to "\n"), host.replacements)
            assertEquals(model.documentText, view.text.toString())
            view.setSelection(0, 1)
            view.performToolbarAction(EditorToolbarAction.Indent)
            assertEquals(EditorToolbarAction.Indent to r(0, 1), host.actions.last())
        }
    }

    /** Gboard는 caret이 단어 안에 들어오면 기존 단어를 조합 영역으로 잡는다. 텍스트가 그대로면 모델 편집 없이 명령만 간다. */
    @Test
    fun toolbarDuringRecomposedExistingWordSendsNoReplacement() = onMain {
        val view = newView()
        val model = BlockEditorModel(listOf(EditorBlock(text = "hello world")))
        model.updateDocumentSelection(r(3, 0))
        val host = ModelHost(view, model)
        val connection = view.onCreateInputConnection(EditorInfo())!!
        connection.setComposingRegion(0, 5)
        assertTrue(connection.isComposing(view))

        view.performToolbarAction(EditorToolbarAction.Duplicate)

        assertTrue(host.replacements.isEmpty())
        assertEquals(listOf(EditorToolbarAction.Duplicate to r(3, 0)), host.actions)
        assertTrue(!connection.isComposing(view))
        assertEquals(model.documentText, view.text.toString())
    }

    /** EditText 자체 undo 대신 모델 undo/redo로 보낸다(Ctrl+Z, Ctrl+Shift+Z, 메뉴). */
    @Test
    fun undoShortcutsAndMenuRouteToToolbarActions() = onMain {
        val view = newView()
        val host = ModelHost(view, BlockEditorModel(listOf(EditorBlock(text = "본문"))))
        val undo = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        val redo = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertTrue(view.onKeyShortcut(KeyEvent.KEYCODE_Z, undo))
        assertTrue(view.onKeyShortcut(KeyEvent.KEYCODE_Z, redo))
        assertTrue(view.onTextContextMenuItem(android.R.id.undo))
        assertTrue(view.onTextContextMenuItem(android.R.id.redo))
        assertEquals(
            listOf(EditorToolbarAction.Undo, EditorToolbarAction.Redo, EditorToolbarAction.Undo, EditorToolbarAction.Redo),
            host.actions.map { it.first },
        )
    }

    @Test
    fun inputAccessoryTracksActiveBlock() = onMain {
        val view = newView()
        val model = BlockEditorModel(listOf(EditorBlock(kind = Quote, text = "인용"), EditorBlock(kind = Code(null), text = "x")))
        model.updateDocumentSelection(r(0, 0))
        val host = ModelHost(view, model)
        val updates = mutableListOf<EditorBlockKind>()
        var bound: ((EditorToolbarAction) -> Unit)? = null
        view.inputAccessory = object : BlockEditorInputAccessory {
            override fun update(kind: EditorBlockKind, canUndo: Boolean, canRedo: Boolean) {
                updates += kind
            }

            override fun bind(perform: (EditorToolbarAction) -> Unit) {
                bound = perform
            }
        }
        assertEquals(Quote, updates.last())
        model.updateDocumentSelection(r(3, 0))
        host.publish()
        assertEquals(Code(null), updates.last())
        // 도구 모음 버튼은 bind로 받은 함수로 명령을 보낸다.
        bound!!(EditorToolbarAction.Duplicate)
        assertEquals(EditorToolbarAction.Duplicate to r(3, 0), host.actions.single())
    }

    // MARK: - 다크 모드

    @Test
    fun darkModeRecolorsDocument() = onMain {
        val view = newView()
        view.setState(listOf(EditorBlock(text = "본문")), null, canUndo = false, canRedo = false)
        val base = targetContext.resources.configuration
        fun configuration(night: Boolean) = Configuration(base).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        view.dispatchConfigurationChanged(configuration(night = true))
        assertEquals(RichMarkdownTheme.Default.textColor.dark, view.text.paintAt(0).color)
        view.dispatchConfigurationChanged(configuration(night = false))
        assertEquals(RichMarkdownTheme.Default.textColor.light, view.text.paintAt(0).color)
    }

    private fun layout(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }
}
