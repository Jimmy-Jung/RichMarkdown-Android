// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

/**
 * 키보드 도구 모음(또는 앱 자체 툴바)이 편집기에 전달하는 편집 명령. iOS `EditorToolbarAction`.
 *
 * 편집기는 명령을 실행하지 않고 현재 선택과 함께 `onToolbarAction`으로 앱에 넘긴다. 실행 연결은 앱 책임이다.
 * [Done]은 키보드를 닫는 요청일 뿐 저장·전송 성공을 뜻하지 않는다.
 */
sealed interface EditorToolbarAction {
    data class Insert(val kind: EditorBlockKind) : EditorToolbarAction
    data class Transform(val kind: EditorBlockKind) : EditorToolbarAction
    data class Format(val format: InlineFormat) : EditorToolbarAction
    data object Indent : EditorToolbarAction
    data object Outdent : EditorToolbarAction
    data object Undo : EditorToolbarAction
    data object Redo : EditorToolbarAction
    data object Duplicate : EditorToolbarAction
    data object Delete : EditorToolbarAction
    data object MoveUp : EditorToolbarAction
    data object MoveDown : EditorToolbarAction
    data object Done : EditorToolbarAction
}

/**
 * 편집기에 주입하는 도구 모음의 계약. iOS `BlockEditorInputAccessory`.
 *
 * Android에는 `inputAccessoryView`가 없어 구체 UI(키보드 위 배치 등)는 앱이 만든다 — 편집기는 주입점만 연다.
 * 편집기가 선택 블록·undo 가능 여부가 바뀔 때마다 [update]를 호출한다.
 */
interface BlockEditorInputAccessory {
    fun update(kind: EditorBlockKind, canUndo: Boolean, canRedo: Boolean)

    /**
     * 편집기에 붙을 때 명령 전달 함수를 받는다. 도구 모음 버튼은 이 함수로 명령을 보낸다
     * (iOS `makeInputAccessory`의 `onAction` 인자). 한글 조합 중이면 편집기가 명령을 무시하고 안내를 읽는다.
     */
    fun bind(perform: (EditorToolbarAction) -> Unit) = Unit
}
