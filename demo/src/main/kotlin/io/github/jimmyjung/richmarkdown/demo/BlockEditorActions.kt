// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.demo

import io.github.jimmyjung.richmarkdown.editor.BlockEditorModel
import io.github.jimmyjung.richmarkdown.editor.BlockSelection
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind
import io.github.jimmyjung.richmarkdown.editor.EditorRange
import io.github.jimmyjung.richmarkdown.editor.EditorToolbarAction

/** 블록 편집 데모의 첫 문서. iOS `EditorDemoView.seedDocument`에 목록 들여쓰기·번호·할 일·블록 수식을 더했다. */
val BLOCK_EDITOR_SAMPLE: String = """
    # 회의 노트

    **오늘 목표**: 블록 편집 확인. *기울임*, ~~취소선~~, `인라인 코드`.

    피타고라스 정리 \( a^2 + b^2 = c^2 \) 를 인라인으로 씁니다.

    ## 할 일 정리

    - 할 일 하나
      - 들여쓴 하위 항목
    - 할 일 둘

    1. 첫 번째 단계
    2. 두 번째 단계

    - [x] 끝난 일
    - [ ] 남은 일

    ### 인용·코드·수식

    > 인용문 안의 수식 \( \frac{1}{n} \to 0 \)

    ```kotlin
    val answer = 42
    ```

    \[\int_{-\infty}^{\infty} e^{-x^2} \, dx = \sqrt{\pi}\]
""".trimIndent()

/** 도구 모음이 고르는 블록 종류. iOS `BlockKeyboardToolbar.blockMenu()` 순서. */
val BLOCK_KINDS: List<EditorBlockKind> = listOf(
    EditorBlockKind.Paragraph,
    EditorBlockKind.Heading(1),
    EditorBlockKind.Heading(2),
    EditorBlockKind.Heading(3),
    EditorBlockKind.BulletedList,
    EditorBlockKind.NumberedList,
    EditorBlockKind.ToDo(isChecked = false),
    EditorBlockKind.Quote,
    EditorBlockKind.Code(language = null),
    EditorBlockKind.Equation,
)

/** 표시용 블록 이름. iOS `EditorBlockKind.title` (엔진은 표시 문자열을 갖지 않으므로 데모가 책임진다). */
val EditorBlockKind.title: String
    get() = when (this) {
        EditorBlockKind.Paragraph -> "문단"
        is EditorBlockKind.Heading -> "제목 $level"
        EditorBlockKind.BulletedList -> "목록"
        EditorBlockKind.NumberedList -> "번호"
        is EditorBlockKind.ToDo -> "할 일"
        EditorBlockKind.Quote -> "인용"
        is EditorBlockKind.Code -> "코드"
        EditorBlockKind.Equation -> "수식"
    }

/** 체크 여부·코드 언어는 무시하고 도구 모음 항목과 같은 종류인지 본다. */
fun EditorBlockKind.isSameKind(other: EditorBlockKind): Boolean = when (other) {
    is EditorBlockKind.ToDo -> this is EditorBlockKind.ToDo
    is EditorBlockKind.Code -> this is EditorBlockKind.Code
    else -> this == other
}

/**
 * 도구 모음 명령을 모델에 적용한다. iOS `BlockEditorDemoView.perform(_:selection:)`.
 *
 * 명령 시점 선택을 먼저 모델에 반영하고, 선택이 시작하는 블록(블록 끝에서 잘린 범위)에 명령을 건다.
 * 서식은 선택이 블록 밖으로 잘렸으면 선택을 바꾸지 않는다. 위·아래 이동은 caret일 때만 옮긴 블록 안 같은 위치로 선택을 되돌린다.
 */
fun BlockEditorModel.perform(action: EditorToolbarAction, selection: EditorRange) {
    if (action == EditorToolbarAction.Done) return
    updateDocumentSelection(selection)
    val active = blockSelection(selection) ?: return
    val id = active.blockId
    when (action) {
        is EditorToolbarAction.Insert -> updateSelection(insert(after = id, kind = action.kind))
        is EditorToolbarAction.Transform -> transform(id, action.kind)
        is EditorToolbarAction.Format -> {
            val next = applyInlineFormat(action.format, id, active.range)
            if (active.range.length == selection.length) updateSelectionIfPresent(next)
        }
        EditorToolbarAction.Indent -> indent(id)
        EditorToolbarAction.Outdent -> outdent(id)
        EditorToolbarAction.Undo -> undo()
        EditorToolbarAction.Redo -> redo()
        EditorToolbarAction.Duplicate -> updateSelectionIfPresent(duplicate(id))
        EditorToolbarAction.Delete -> updateSelectionIfPresent(delete(id))
        EditorToolbarAction.MoveUp -> if (moveUp(id) && selection.length == 0) updateSelection(active)
        EditorToolbarAction.MoveDown -> if (moveDown(id) && selection.length == 0) updateSelection(active)
        EditorToolbarAction.Done -> Unit
    }
}

/** iOS `updateSelection(_:)`의 nil guard. 모델의 `updateSelection(null)`은 선택 해제라 그대로 부르지 않는다. */
private fun BlockEditorModel.updateSelectionIfPresent(selection: BlockSelection?) {
    if (selection != null) updateSelection(selection)
}
