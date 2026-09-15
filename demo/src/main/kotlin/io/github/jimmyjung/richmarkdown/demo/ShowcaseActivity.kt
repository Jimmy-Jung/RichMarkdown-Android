// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jimmyjung.richmarkdown.LatexDollarMathOptions
import io.github.jimmyjung.richmarkdown.RichMarkdown
import io.github.jimmyjung.richmarkdown.RichMarkdownCodeBlockOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownView

/**
 * 샘플 전부를 한 화면에 나열하고 렌더러(Compose/View)·`$` 수식·다크·코드 블록 확장을 토글로 비교한다.
 * iOS `ChatDemoView` 렌더 옵션 메뉴 + `CodeBlockExtensionDemoView` 렌더러 피커 대응.
 *
 * intent extra `renderer=view`로 View 렌더러에서 시작한다 (스크린샷 자동화).
 */
class ShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = if (intent.getStringExtra(EXTRA_RENDERER) == "view") Renderer.View else Renderer.Compose
        setContent { Showcase(initial) }
    }

    companion object {
        const val EXTRA_RENDERER = "renderer"
    }
}

enum class Renderer(val label: String) { Compose("Compose"), View("View") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Showcase(initialRenderer: Renderer) {
    val systemDark = isSystemInDarkTheme()
    var renderer by rememberSaveable { mutableStateOf(initialRenderer) }
    var parsesDollarMath by rememberSaveable { mutableStateOf(false) }
    var isDark by rememberSaveable { mutableStateOf(systemDark) }
    var usesExtensions by rememberSaveable { mutableStateOf(true) }

    val context = LocalContext.current
    val codeBlocks = remember(usesExtensions) {
        if (usesExtensions) demoCodeBlocks(context) else RichMarkdownCodeBlockOptions.None
    }
    val dollarMath = if (parsesDollarMath) LatexDollarMathOptions.Single else LatexDollarMathOptions.None

    DemoTheme(darkTheme = isDark) {
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.title_showcase)) }) }) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .widthIn(max = ReadableWidth)
                    .fillMaxWidth(),
            ) {
                // 컨트롤은 두 줄로. 한 Row에 넣으면 폰 폭에서 마지막 칩이 세로로 눌린다.
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(parsesDollarMath, { parsesDollarMath = !parsesDollarMath }, label = { Text("$ 수식") })
                    FilterChip(isDark, { isDark = !isDark }, label = { Text("다크") })
                    FilterChip(usesExtensions, { usesExtensions = !usesExtensions }, label = { Text("코드 확장") })
                }

                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    items(SampleMarkdown.showcaseSections, key = { it.title }) { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                section.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            when (renderer) {
                                Renderer.Compose -> RichMarkdown(
                                    markdown = section.markdown,
                                    modifier = Modifier.fillMaxWidth(),
                                    dollarMath = dollarMath,
                                    codeBlocks = codeBlocks,
                                    isDarkTheme = isDark,
                                )
                                // 같은 원문을 네이티브 View 렌더러로. 높이는 뷰가 스스로 requestLayout해 잡는다.
                                Renderer.View -> AndroidView(
                                    factory = { RichMarkdownView(it) },
                                    modifier = Modifier.fillMaxWidth(),
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
