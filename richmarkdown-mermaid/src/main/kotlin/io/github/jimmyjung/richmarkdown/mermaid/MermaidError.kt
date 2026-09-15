// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.mermaid

/**
 * Mermaid 표시 실패 사유 (iOS `MermaidError`, `MermaidWebRenderer.swift`).
 * 어느 경우든 호출한 뷰가 원문 코드 블록으로 되돌린다 (Docs/CODE_BLOCK_EXTENSIONS.md §3.3).
 */
sealed class MermaidError(message: String) : Exception(message) {
    data object EmptySource : MermaidError("Mermaid 원문이 비어 있습니다.")

    data class SourceTooLarge(val utf8Bytes: Int, val limit: Int) :
        MermaidError("Mermaid 원문이 UTF-8 ${utf8Bytes}바이트로 상한 ${limit}바이트를 넘습니다.")

    data object ResourceMissing : MermaidError("번들에서 Mermaid 리소스를 찾을 수 없습니다.")

    data object LoadTimeout : MermaidError("Mermaid WebView 초기화 시간이 초과되었습니다.")

    /** iOS `webContentTerminated`. Android에서는 `onRenderProcessGone`이다. */
    data object WebContentTerminated : MermaidError("WebView 렌더러 프로세스가 종료되었습니다.")

    data object InvalidSize : MermaidError("Mermaid가 유효한 크기를 반환하지 않았습니다.")

    /** iOS `render(String)`. index.html의 `renderDiagram`이 reject한 메시지 그대로다. */
    data class RenderFailed(val reason: String) : MermaidError(reason)
}
