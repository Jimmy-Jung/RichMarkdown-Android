// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.github.jimmyjung.richmarkdown.editor.BlockEditorInputAccessory
import io.github.jimmyjung.richmarkdown.editor.BlockEditorModel
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind
import io.github.jimmyjung.richmarkdown.editor.EditorToolbarAction
import io.github.jimmyjung.richmarkdown.editor.InlineFormat
import io.github.jimmyjung.richmarkdown.editor.compose.BlockDocumentTextEditor

/**
 * 블록 편집 데모 (iOS `BlockEditorDemoView`). 논리 블록은 앱이 가진 [BlockEditorModel]에 두고 화면에는 연속 문서 하나만 보인다.
 * 키보드 위에는 iOS `BlockKeyboardToolbar`에 대응하는 가로 스크롤 명령 막대를 둔다.
 */
class BlockEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DemoTheme { BlockEditorScreen() } }
    }
}

// MARK: - 상태

/** iOS `@State var model`. 모델이 가변 클래스라 바꿀 때마다 [revision]을 올려 재구성을 일으킨다. */
private class DemoEditorState {
    val model = BlockEditorModel(BLOCK_EDITOR_SAMPLE)
    var revision by mutableIntStateOf(0)
        private set

    fun <T> mutate(change: BlockEditorModel.() -> T): T = model.change().also { revision += 1 }
}

/**
 * iOS `BlockKeyboardToolbar: BlockEditorInputAccessory`. 편집기가 [bind]로 명령 전달 함수를, [update]로 현재 블록 상태를 준다.
 * 명령은 반드시 편집기의 함수로 보낸다 — IME 조합 확정과 현재 선택 첨부를 편집기가 맡는다.
 */
private class ToolbarState : BlockEditorInputAccessory {
    var kind by mutableStateOf<EditorBlockKind>(EditorBlockKind.Paragraph)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** 편집기가 bind로 준 함수. 첫 구성은 bind보다 먼저라 버튼은 이 값을 붙잡지 말고 [perform]으로 누를 때 읽는다. */
    private var bound: (EditorToolbarAction) -> Unit = {}

    fun perform(action: EditorToolbarAction) = bound(action)

    override fun update(kind: EditorBlockKind, canUndo: Boolean, canRedo: Boolean) {
        this.kind = kind
        this.canUndo = canUndo
        this.canRedo = canRedo
    }

    override fun bind(perform: (EditorToolbarAction) -> Unit) {
        bound = perform
    }
}

// MARK: - 화면

@Composable
private fun BlockEditorScreen() {
    val editor = remember { DemoEditorState() }
    val toolbar = remember { ToolbarState() }
    var parsesDollarMath by rememberSaveable { mutableStateOf(false) }
    var showsMarkdown by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            DemoTopAppBar(stringResource(R.string.title_block_editor)) {
                DemoOptionsMenu {
                    DemoToggleOption("$ 수식 파싱 (opt-in)", parsesDollarMath) { parsesDollarMath = !parsesDollarMath }
                    DemoToggleOption("Markdown 보기", showsMarkdown) { showsMarkdown = !showsMarkdown }
                }
            }
        },
    ) { padding ->
        // revision을 읽어 모델이 바뀔 때마다 이 범위가 다시 실행되고 최신 blocks·selection을 넘긴다.
        val markdown = remember(editor.revision) { editor.model.markdown }
        val model = editor.model
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()) {
            BlockDocumentTextEditor(
                blocks = model.blocks,
                selection = model.currentDocumentSelection,
                canUndo = model.canUndo,
                canRedo = model.canRedo,
                onReplaceText = { range, text -> editor.mutate { replaceDocumentText(range, text) } },
                onSelectionChange = { selection -> editor.mutate { updateDocumentSelection(selection) } },
                onToolbarAction = { action, range -> editor.mutate { perform(action, range) } },
                modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface),
                onReplaceDocumentBlocks = { range, blocks -> editor.mutate { replaceDocumentBlocks(range, blocks) } },
                parsesDollarMath = parsesDollarMath,
                sourceMarkdown = markdown,
                inputAccessory = toolbar,
            )
            if (showsMarkdown) MarkdownExport(markdown, Modifier.weight(0.7f))
            HorizontalDivider()
            BlockKeyboardToolbar(toolbar)
        }
    }
}

/** 내보낸 Markdown(`model.markdown`) 읽기 전용 보기. */
@Composable
private fun MarkdownExport(markdown: String, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("내보낸 Markdown", Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(markdown, Modifier.padding(top = 8.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// MARK: - 도구 모음 (iOS BlockKeyboardToolbar)

/** 키보드 위 가로 스크롤 명령 막대. 완료(키보드 닫기)는 스크롤 밖 오른쪽에 고정한다. */
@Composable
private fun BlockKeyboardToolbar(state: ToolbarState) {
    val perform = state::perform
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                ToolbarMenu("+", "블록 추가", BLOCK_KINDS.map { MenuEntry(it.title, EditorToolbarAction.Insert(it)) }, perform)
                ToolbarMenu(
                    label = "${state.kind.title} ▾",
                    description = "블록 종류 바꾸기, 현재 블록: ${state.kind.title}",
                    entries = BLOCK_KINDS.map { MenuEntry(it.title, EditorToolbarAction.Transform(it), selected = state.kind.isSameKind(it)) },
                    perform = perform,
                    highlighted = true,
                )
                ToolbarButton("B", { perform(EditorToolbarAction.Format(InlineFormat.Bold)) }, "굵게", style = TextStyle(fontWeight = FontWeight.Bold))
                ToolbarButton("I", { perform(EditorToolbarAction.Format(InlineFormat.Italic)) }, "기울임", style = TextStyle(fontStyle = FontStyle.Italic))
                ToolbarButton(
                    "S", { perform(EditorToolbarAction.Format(InlineFormat.Strikethrough)) }, "취소선",
                    style = TextStyle(textDecoration = TextDecoration.LineThrough),
                )
                ToolbarButton("</>", { perform(EditorToolbarAction.Format(InlineFormat.Code)) }, "인라인 코드", style = TextStyle(fontFamily = FontFamily.Monospace))
                ToolbarButton("내어쓰기", { perform(EditorToolbarAction.Outdent) })
                ToolbarButton("들여쓰기", { perform(EditorToolbarAction.Indent) })
                ToolbarButton("실행 취소", { perform(EditorToolbarAction.Undo) }, enabled = state.canUndo)
                ToolbarButton("다시 실행", { perform(EditorToolbarAction.Redo) }, enabled = state.canRedo)
                ToolbarMenu(
                    "더보기", "블록 더보기",
                    listOf(
                        MenuEntry("복제", EditorToolbarAction.Duplicate),
                        MenuEntry("위로 이동", EditorToolbarAction.MoveUp),
                        MenuEntry("아래로 이동", EditorToolbarAction.MoveDown),
                        MenuEntry("블록 삭제", EditorToolbarAction.Delete, destructive = true),
                    ),
                    perform,
                )
            }
            ToolbarButton("완료", { perform(EditorToolbarAction.Done) }, "키보드 닫기")
        }
    }
}

private class MenuEntry(val title: String, val action: EditorToolbarAction, val selected: Boolean = false, val destructive: Boolean = false)

/**
 * iOS `showsMenuAsPrimaryAction` 메뉴. 팝업이 포커스를 가져가면 편집기 포커스와 키보드가 사라지므로 비포커스 팝업으로 연다.
 * 고른 항목은 [MenuEntry.selected]면 강조한다(현재 블록 종류).
 */
@Composable
private fun ToolbarMenu(
    label: String,
    description: String,
    entries: List<MenuEntry>,
    perform: (EditorToolbarAction) -> Unit,
    highlighted: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarButton(label, { expanded = true }, description, highlighted = highlighted)
        DropdownMenu(expanded, { expanded = false }, properties = PopupProperties(focusable = false)) {
            for (entry in entries) {
                val colors = MaterialTheme.colorScheme
                DropdownMenuItem(
                    text = { Text(entry.title, color = if (entry.destructive) colors.error else Color.Unspecified) },
                    onClick = {
                        expanded = false
                        perform(entry.action)
                    },
                    modifier = Modifier
                        .background(if (entry.selected) colors.secondaryContainer else Color.Transparent)
                        .semantics { selected = entry.selected },
                )
            }
        }
    }
}

/** 48dp 이상 터치 영역의 글자 버튼. [description]이 [label]과 다르면 접근성 이름으로 쓴다(iOS accessibilityLabel). */
@Composable
private fun ToolbarButton(
    label: String,
    onClick: () -> Unit,
    description: String = label,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    style: TextStyle = TextStyle.Default,
) {
    val colors = MaterialTheme.colorScheme
    TextButton(
        onClick = onClick,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
            if (description != label) contentDescription = description
        },
        enabled = enabled,
        colors = if (highlighted) {
            ButtonDefaults.textButtonColors(containerColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer)
        } else {
            ButtonDefaults.textButtonColors()
        },
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        Text(label, style = LocalTextStyle.current.merge(style), maxLines = 1)
    }
}
