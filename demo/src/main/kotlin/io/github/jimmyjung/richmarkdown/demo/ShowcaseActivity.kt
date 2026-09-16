// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jimmyjung.richmarkdown.LatexDollarMathOptions
import io.github.jimmyjung.richmarkdown.RichMarkdown
import io.github.jimmyjung.richmarkdown.RichMarkdownCodeBlockOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownView
import io.github.jimmyjung.richmarkdown.highlight.PrismHighlighter
import io.github.jimmyjung.richmarkdown.mermaid.MermaidDiagramRenderer

/** 동일한 렌더러 피커로 전체 샘플과 코드 블록 확장 전용 문서를 표시한다. */
class ShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = if (intent.getStringExtra(EXTRA_RENDERER) == "view") Renderer.View else Renderer.Compose
        setContent { Showcase(initial, codeOnly = intent.getStringExtra(EXTRA_SECTION) == "code") }
    }

    companion object {
        const val EXTRA_RENDERER = "renderer"
        const val EXTRA_SECTION = "section"
    }
}

enum class Renderer(val label: String) { Compose("Compose"), View("View") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Showcase(initialRenderer: Renderer, codeOnly: Boolean) {
    val systemDark = isSystemInDarkTheme()
    var renderer by rememberSaveable { mutableStateOf(initialRenderer) }
    var parsesDollarMath by rememberSaveable { mutableStateOf(codeOnly) }
    var isDark by rememberSaveable { mutableStateOf(systemDark) }
    var highlightsCode by rememberSaveable { mutableStateOf(true) }
    var drawsDiagrams by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val codeBlocks = remember(highlightsCode, drawsDiagrams) {
        RichMarkdownCodeBlockOptions(
            highlighter = if (highlightsCode) PrismHighlighter.shared(context) else null,
            diagram = if (drawsDiagrams) MermaidDiagramRenderer.shared else null,
        )
    }
    val dollarMath = if (parsesDollarMath) LatexDollarMathOptions.Single else LatexDollarMathOptions.None
    val sections = if (codeOnly) listOf(SampleSection("코드 블록 확장", SampleMarkdown.codeBlockExtensions))
        else SampleMarkdown.showcaseSections

    DemoTheme(darkTheme = isDark) {
        Scaffold(topBar = {
            DemoTopAppBar(if (codeOnly) "코드 블록 확장" else "샘플 쇼케이스") {
                DemoOptionsMenu {
                    DemoToggleOption("$ 수식 파싱 (opt-in)", parsesDollarMath) { parsesDollarMath = !parsesDollarMath }
                    DemoToggleOption("신택스 하이라이팅 (Prism)", highlightsCode) { highlightsCode = !highlightsCode }
                    DemoToggleOption("Mermaid 다이어그램", drawsDiagrams) { drawsDiagrams = !drawsDiagrams }
                    DemoToggleOption("다크 모드", isDark) { isDark = !isDark }
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                SingleChoiceSegmentedButtonRow(
                    Modifier.widthIn(max = ReadableWidth + 32.dp).fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Renderer.entries.forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = renderer == value,
                            onClick = { renderer = value },
                            shape = SegmentedButtonDefaults.itemShape(index, Renderer.entries.size),
                            label = { Text(value.label) },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        modifier = Modifier.widthIn(max = ReadableWidth + 32.dp).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        items(sections, key = { it.title }) { section ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!codeOnly) Text(section.title, style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                when (renderer) {
                                    Renderer.Compose -> RichMarkdown(
                                        markdown = section.markdown, modifier = Modifier.fillMaxWidth(),
                                        dollarMath = dollarMath, codeBlocks = codeBlocks, isDarkTheme = isDark,
                                    )
                                    Renderer.View -> AndroidView(
                                        factory = { RichMarkdownView(it) }, modifier = Modifier.fillMaxWidth(),
                                        update = { view ->
                                            view.markdown = section.markdown
                                            view.dollarMath = dollarMath
                                            view.codeBlocks = codeBlocks
                                            view.isDarkTheme = isDark
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
