// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.mermaid

import android.content.Context
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jimmyjung.richmarkdown.RichMarkdownDiagramRendering
import io.github.jimmyjung.richmarkdown.RichMarkdownTheme

/**
 * ```` ```mermaid ```` 코드 블록을 공식 Mermaid 다이어그램으로 대체하는 확장 (iOS `MermaidDiagramRenderer.swift`).
 *
 * ```kotlin
 * RichMarkdownCodeBlockOptions(diagram = MermaidDiagramRenderer.shared)
 * ```
 *
 * `RichMarkdownCodeBlockOptions`는 인스턴스 동일성으로 비교하므로 [shared] 하나를 재사용한다.
 */
class MermaidDiagramRenderer private constructor() : RichMarkdownDiagramRendering {

    override val languages: Set<String> = setOf("mermaid")

    /** 다크 여부는 뷰가 `uiMode`에서 읽는다 (인터페이스에 isDark가 없다). */
    override fun createView(
        context: Context,
        source: String,
        theme: RichMarkdownTheme,
        onSizeChange: () -> Unit,
    ): View = MermaidDiagramView(context).also { view ->
        view.source = source
        view.theme = theme
        view.onSizeChange = onSizeChange
    }

    /**
     * `AndroidView`는 렌더 뒤의 높이 변화를 따라오지 않는다 (iOS `UIViewRepresentable` §3.6와 같다).
     * `onSizeChange`로 올라온 높이를 state로 들어 `Modifier.height`에 건다.
     */
    @Composable
    override fun Content(source: String, theme: RichMarkdownTheme) {
        val isDark = isSystemInDarkTheme()
        val density = LocalDensity.current
        var heightPx by remember { mutableIntStateOf(0) }
        val height = if (heightPx > 0) with(density) { heightPx.toDp() } else MermaidDiagramView.PLACEHOLDER_HEIGHT_DP.dp

        AndroidView(
            factory = { context ->
                MermaidDiagramView(context).also { view ->
                    view.onSizeChange = { heightPx = view.contentHeightPx }
                }
            },
            modifier = Modifier.fillMaxWidth().height(height),
            update = { view ->
                view.source = source
                view.theme = theme
                view.isDark = isDark
            },
        )
    }

    companion object {
        val shared: MermaidDiagramRenderer = MermaidDiagramRenderer()
    }
}
