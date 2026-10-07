// Date: 2026-10-06

package io.github.jimmyjung.richmarkdown

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jimmyjung.richmarkdown.core.InputLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ViewRendererIntegrationTest {
    @get:Rule val compose = createComposeRule()

    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
        else -> ""
    }

    @Test
    fun detachRetainsModelAndReattachShowsOnlyReusedMessageWithSizeCallback() {
        val attached = mutableStateOf(true)
        val source = mutableStateOf("첫 메시지 \\(x^2\\)")
        val sizes = AtomicInteger()
        lateinit var view: RichMarkdownView
        compose.setContent {
            val context = LocalContext.current
            val retained = remember { RichMarkdownView(context).apply { onContentSizeChange = { sizes.incrementAndGet() } } }
            SideEffect { view = retained }
            if (attached.value) {
                AndroidView(factory = { retained }, update = { it.markdown = source.value })
            }
        }
        compose.waitUntil(10_000) { view.model.state.value.mathImages.isNotEmpty() && !view.model.hasOutstandingWork }
        val firstModel = view.model
        compose.waitUntil(5_000) { sizes.get() > 0 }
        compose.runOnIdle { attached.value = false }
        compose.waitUntil(5_000) { !view.isAttachedToWindow }
        compose.runOnIdle {
            source.value = "재사용한 **새 메시지**"
            view.markdown = source.value
        }
        compose.waitUntil(10_000) { view.model.state.value.parseIdentity?.markdown == source.value && !view.model.hasOutstandingWork }
        compose.runOnIdle { attached.value = true }
        compose.waitUntil(5_000) { view.isAttachedToWindow && text(view).contains("새 메시지") }
        compose.runOnIdle {
            assertTrue(firstModel === view.model)
            assertFalse(text(view).contains("첫 메시지"))
            assertTrue(view.model.state.value.mathImages.isEmpty())
            val oversized = "a".repeat(InputLimits.MAX_INPUT_UTF8_BYTES + 1)
            view.markdown = oversized
            assertEquals(InputLimits.bound(oversized).text, view.markdown)
            assertEquals(view.markdown, view.model.state.value.fallbackMarkdown)
        }
    }
}
