// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jimmyjung.richmarkdown.RichMarkdownTheme
import io.github.jimmyjung.richmarkdown.editor.BlockAlignmentConfiguration
import io.github.jimmyjung.richmarkdown.editor.BlockDocumentEditText
import io.github.jimmyjung.richmarkdown.editor.BlockEditorInputAccessory
import io.github.jimmyjung.richmarkdown.editor.EditorBlock
import io.github.jimmyjung.richmarkdown.editor.EditorRange
import io.github.jimmyjung.richmarkdown.editor.EditorToolbarAction

/**
 * Compose 블록 편집기. iOS SwiftUI `BlockDocumentTextEditor`(UIViewRepresentable) 대응으로 [BlockDocumentEditText]를 감싼다.
 *
 * 앱이 `BlockEditorModel`을 소유하고, 콜백에서 모델을 바꾼 뒤 [blocks]·[selection] 등을 다시 넘긴다.
 * Compose는 재구성 때 상태를 전달하므로 편집 콜백마다 내부 revision을 올려 다음 프레임에 반드시 상태를 다시 적용한다 —
 * 모델이 편집을 무시해 [blocks]가 그대로여도 화면을 모델 문서와 다시 맞춘다.
 *
 * ```kotlin
 * val model = remember { BlockEditorModel(markdown) }
 * var blocks by remember { mutableStateOf(model.blocks) }
 * var selection by remember { mutableStateOf(model.currentDocumentSelection) }
 * fun publish() { blocks = model.blocks; selection = model.currentDocumentSelection }
 * BlockDocumentTextEditor(
 *     blocks = blocks, selection = selection, canUndo = model.canUndo, canRedo = model.canRedo,
 *     onReplaceText = { range, text -> model.replaceDocumentText(range, text).also { publish() } },
 *     onSelectionChange = { model.updateDocumentSelection(it); publish() },
 *     onToolbarAction = { action, range -> /* 명령을 모델에 연결 */ publish() },
 *     sourceMarkdown = model.markdown,
 * )
 * ```
 */
@Composable
fun BlockDocumentTextEditor(
    blocks: List<EditorBlock>,
    selection: EditorRange?,
    canUndo: Boolean,
    canRedo: Boolean,
    onReplaceText: (EditorRange, String) -> EditorRange?,
    onSelectionChange: (EditorRange) -> Unit,
    onToolbarAction: (EditorToolbarAction, EditorRange) -> Unit,
    modifier: Modifier = Modifier,
    onReplaceDocumentBlocks: ((EditorRange, List<EditorBlock>) -> EditorRange?)? = null,
    parsesDollarMath: Boolean = false,
    theme: RichMarkdownTheme = RichMarkdownTheme.Default,
    blockAlignment: BlockAlignmentConfiguration = BlockAlignmentConfiguration.Default,
    sourceMarkdown: String? = null,
    inputAccessory: BlockEditorInputAccessory? = null,
) {
    var revision by remember { mutableIntStateOf(0) }
    val currentOnReplaceText by rememberUpdatedState(onReplaceText)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentOnToolbarAction by rememberUpdatedState(onToolbarAction)
    val currentOnReplaceDocumentBlocks by rememberUpdatedState(onReplaceDocumentBlocks)

    // revision이 키라서 편집 콜백 뒤에는 매개변수가 같아도 update가 다시 돈다(재동기화).
    val hostRevision = revision
    val update: (BlockDocumentEditText) -> Unit = remember(
        hostRevision, blocks, selection, canUndo, canRedo, parsesDollarMath, theme, blockAlignment, sourceMarkdown, inputAccessory,
    ) {
        { view ->
            if (view.inputAccessory !== inputAccessory) view.inputAccessory = inputAccessory
            view.setState(blocks, selection, canUndo, canRedo, parsesDollarMath, theme, blockAlignment, sourceMarkdown)
        }
    }

    AndroidView(
        factory = { context ->
            BlockDocumentEditText(context).apply {
                hostUpdatesAfterCallback = true
                this.onReplaceText = { range, text -> currentOnReplaceText(range, text).also { revision += 1 } }
                this.onSelectionChange = { currentOnSelectionChange(it) }
                this.onToolbarAction = { action, range -> currentOnToolbarAction(action, range) }
                this.onReplaceDocumentBlocks = { range, pasted ->
                    currentOnReplaceDocumentBlocks?.invoke(range, pasted)?.also { revision += 1 }
                }
            }
        },
        modifier = modifier,
        update = update,
        onRelease = { it.releaseHost() },
    )
}
