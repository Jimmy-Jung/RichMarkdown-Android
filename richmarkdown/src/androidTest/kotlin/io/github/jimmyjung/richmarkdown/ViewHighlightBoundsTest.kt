// Date: 2026-10-06

package io.github.jimmyjung.richmarkdown

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jimmyjung.richmarkdown.core.DollarMathOptions
import io.github.jimmyjung.richmarkdown.core.ParsedBlock
import io.github.jimmyjung.richmarkdown.core.Utf16Range
import io.github.jimmyjung.richmarkdown.view.BlockViewBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewHighlightBoundsTest {
    @Test
    fun customRangesOutsideSourceAndOverlapsAreIgnoredWithoutChangingCode() = runBlocking {
        val code = "val x = 1"
        val highlighter = object : RichMarkdownSyntaxHighlighting {
            override suspend fun spans(code: String, language: String) = listOf(
                RichMarkdownHighlightSpan(Utf16Range(0, 3), RichMarkdownHighlightKind.Keyword),
                RichMarkdownHighlightSpan(Utf16Range(2, 5), RichMarkdownHighlightKind.String),
                RichMarkdownHighlightSpan(Utf16Range(5, code.length + 10), RichMarkdownHighlightKind.Comment),
                RichMarkdownHighlightSpan(Utf16Range(4, 4), RichMarkdownHighlightKind.Number),
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            withContext(Dispatchers.Main.immediate) {
                val builder = BlockViewBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    RichMarkdownTheme.Default,
                    false,
                    DollarMathOptions.None,
                    RichMarkdownCodeBlockOptions(highlighter),
                    scope,
                    {},
                    {},
                )
                val block = builder.blockView(ParsedBlock.CodeBlock("kotlin", code), emptyMap(), null) as LinearLayout
                val body = (block.getChildAt(1) as HorizontalScrollView).getChildAt(0) as TextView
                assertEquals(code, body.text.toString())
                val styled = body.text as Spanned
                val ranges = styled.getSpans(0, styled.length, ForegroundColorSpan::class.java)
                    .map { styled.getSpanStart(it) to styled.getSpanEnd(it) }
                assertEquals(listOf(0 to 3), ranges)
            }
        } finally {
            scope.cancel()
        }
    }
}
