// Date: 2026-10-06

package io.github.jimmyjung.richmarkdown

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jimmyjung.richmarkdown.core.DollarMathOptions
import io.github.jimmyjung.richmarkdown.core.ParsedBlock
import io.github.jimmyjung.richmarkdown.view.AppearanceKey
import io.github.jimmyjung.richmarkdown.view.BlockViewBuilder
import io.github.jimmyjung.richmarkdown.view.IncrementalRebuild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagramViewDisposalTest {
    private class RecordingDiagram : RichMarkdownDiagramRendering {
        override val languages = setOf("diagram")
        val created = ArrayList<View>()
        val disposed = ArrayList<View>()
        override fun createView(context: Context, source: String, theme: RichMarkdownTheme, onSizeChange: () -> Unit) =
            View(context).also(created::add)
        override fun disposeView(view: View) {
            if (created.any { it === view }) disposed.add(view)
        }
        @Composable override fun Content(source: String, theme: RichMarkdownTheme) {}
    }

    @Test
    fun replacedNestedDiagramsDisposeWhileFallbackAndReusedViewsRemainAlive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            withContext(Dispatchers.Main.immediate) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val provider = RecordingDiagram()
                val replacementProvider = RecordingDiagram()
                val options = RichMarkdownCodeBlockOptions(diagram = provider)
                fun builder(codeBlocks: RichMarkdownCodeBlockOptions) = BlockViewBuilder(
                    context, RichMarkdownTheme.Default, false, DollarMathOptions.None, codeBlocks, scope, {}, {},
                )
                fun appearance(codeBlocks: RichMarkdownCodeBlockOptions) =
                    AppearanceKey(RichMarkdownTheme.Default, false, 32f, codeBlocks)
                val stack = LinearLayout(context)
                val rebuild = IncrementalRebuild(stack)
                val initial = listOf(ParsedBlock.BlockQuote(listOf(ParsedBlock.CodeBlock("diagram", "A"))))
                rebuild.renderBlocks(initial, emptyMap(), appearance(options), null, builder(options))
                val original = stack.getChildAt(0)
                rebuild.renderFallback("일시 원문", builder(options))
                assertEquals(0, provider.disposed.size)
                rebuild.renderBlocks(initial, emptyMap(), appearance(options), null, builder(options))
                assertSame(original, stack.getChildAt(0))
                assertEquals(1, provider.created.size)
                assertEquals(0, provider.disposed.size)

                val changed = listOf(ParsedBlock.BlockQuote(listOf(ParsedBlock.CodeBlock("diagram", "B"))))
                rebuild.renderBlocks(changed, emptyMap(), appearance(options), null, builder(options))
                assertEquals(listOf(provider.created[0]), provider.disposed)
                val replacement = RichMarkdownCodeBlockOptions(diagram = replacementProvider)
                rebuild.renderBlocks(changed, emptyMap(), appearance(replacement), null, builder(replacement))
                assertEquals(provider.created, provider.disposed)
                assertEquals(0, replacementProvider.disposed.size)
                rebuild.renderBlocks(emptyList(), emptyMap(), appearance(replacement), null, builder(replacement))
                assertEquals(replacementProvider.created, replacementProvider.disposed)
            }
        } finally {
            scope.cancel()
        }
    }
}
