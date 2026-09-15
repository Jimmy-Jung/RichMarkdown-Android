// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import io.github.jimmyjung.richmarkdown.core.Utf16Range

/**
 * 코드 블록 확장점 (iOS `RichMarkdownCodeBlockOptions.swift`, DEVELOPMENT.md D1·D6·D7).
 *
 * 코어는 인터페이스만 선언하고 구현은 별도 모듈(`richmarkdown-highlight`, `richmarkdown-mermaid`)이
 * 담당한다. `richmarkdown`만 쓰는 앱은 JS 런타임도 WebView도 3 MB 번들도 링크하지 않는다.
 *
 * 두 확장 모두 참조 타입이라 **동일 인스턴스** 여부로 비교한다 (Swift `===`). 구현체 내부 상태
 * (JS 컨텍스트, 이미지 캐시)는 값 비교 대상이 아니다.
 */
class RichMarkdownCodeBlockOptions(
    /** 코드 블록 본문에 색 역할을 부여한다. null이면 plain monospace. */
    val highlighter: RichMarkdownSyntaxHighlighting? = null,
    /** 담당 언어의 코드 블록을 다이어그램 뷰로 대체한다. */
    val diagram: RichMarkdownDiagramRendering? = null,
) {
    /** 이 코드 블록 언어를 담당하는 다이어그램 렌더러. 없으면 null. */
    internal fun diagramRenderer(language: String?): RichMarkdownDiagramRendering? {
        val renderer = diagram ?: return null
        val key = language?.lowercase() ?: return null
        return if (key in renderer.languages) renderer else null
    }

    override fun equals(other: Any?): Boolean =
        other is RichMarkdownCodeBlockOptions &&
            highlighter === other.highlighter &&
            diagram === other.diagram

    override fun hashCode(): Int =
        31 * System.identityHashCode(highlighter) + System.identityHashCode(diagram)

    companion object {
        /** 아무 확장도 걸지 않은 기본값. */
        val None: RichMarkdownCodeBlockOptions = RichMarkdownCodeBlockOptions()
    }
}

// MARK: - 신택스 하이라이팅

/**
 * 표시 역할. 엔진의 토큰 이름이 아니라 테마가 색을 붙이는 단위다.
 * 엔진마다 토큰 분류가 다르므로 구현체가 이 7종으로 정규화한다 (iOS Docs/CODE_BLOCK_EXTENSIONS.md §4.3).
 */
enum class RichMarkdownHighlightKind {
    Keyword,
    String,
    Comment,
    Number,
    Type,
    Function,
    Property,
}

/**
 * 원문의 UTF-16 범위 + 역할. 원문 자체는 절대 바꾸지 않는다 —
 * 실패 시 원문을 표시하는 v1 원칙을 하이라이팅에도 그대로 적용한다.
 */
data class RichMarkdownHighlightSpan(
    val range: Utf16Range,
    val kind: RichMarkdownHighlightKind,
)

/**
 * 코드 블록 하이라이터. 구현은 `richmarkdown-highlight`의 Prism 래퍼다.
 *
 * 토큰화는 메인 스레드 밖에서 끝내고 도착한 범위만 색으로 반영한다.
 * 미지원 언어와 실패는 **빈 리스트**이며 예외를 던지지 않는다.
 */
interface RichMarkdownSyntaxHighlighting {
    suspend fun spans(code: String, language: String): List<RichMarkdownHighlightSpan>
}

// MARK: - 다이어그램

/**
 * 특정 언어의 코드 블록을 통째로 대체하는 뷰 공급자. 구현은 `richmarkdown-mermaid`다.
 * Compose·View 두 렌더러(D5)에 각각 뷰를 공급한다.
 */
interface RichMarkdownDiagramRendering {
    /** 담당하는 코드 블록 언어 (소문자). 예: `setOf("mermaid")`. */
    val languages: Set<String>

    /**
     * View 렌더러용. 높이가 확정·변경될 때마다 [onSizeChange]를 호출해야
     * RecyclerView 셀 self-sizing이 다시 측정된다. 메인 스레드에서 호출된다.
     */
    fun createView(
        context: Context,
        source: String,
        theme: RichMarkdownTheme,
        onSizeChange: () -> Unit,
    ): View

    /** Compose 렌더러용. */
    @Composable
    fun Content(source: String, theme: RichMarkdownTheme)
}
