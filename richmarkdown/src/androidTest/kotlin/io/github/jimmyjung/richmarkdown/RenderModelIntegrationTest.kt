// Date: 2026-10-06

package io.github.jimmyjung.richmarkdown

import android.graphics.Color
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jimmyjung.richmarkdown.core.InputLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenderModelIntegrationTest {
    private fun request(source: String, size: Float = 32f, color: Int = Color.BLACK) =
        RichMarkdownRenderModel.Request.of(source, LatexDollarMathOptions.None, size, color)

    @Test
    fun directRequestsBoundFallbackBeforeParsingAndPreserveTruncation(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val model = RichMarkdownRenderModel(scope)
        val oversized = "a".repeat(InputLimits.MAX_INPUT_UTF8_BYTES + 1)
        val deep = "정상\n" + ">".repeat(InputLimits.MAX_BLOCK_QUOTE_DEPTH + 1) + " 인용"
        try {
            for (source in listOf(oversized, deep)) {
                val direct = request("unused").copy(boundedInput = InputLimits.BoundedInput(source, false))
                withContext(Dispatchers.Main.immediate) {
                    model.submit(direct)
                    assertEquals(InputLimits.bound(source).text, model.state.value.fallbackMarkdown)
                }
                withTimeout(10_000) { model.awaitIdle() }
                assertTrue(model.state.value.document!!.wasTruncated)
                assertEquals(request(source).parseIdentity, model.state.value.parseIdentity)
            }
            withContext(Dispatchers.Main.immediate) {
                model.submit(request("표시 원문").copy(boundedInput = InputLimits.BoundedInput("표시 원문", true)))
            }
            withTimeout(10_000) { model.awaitIdle() }
            assertTrue(model.state.value.document!!.wasTruncated)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun latestMessagePublishesOnlyOnMainAndAppendKeepsHydratedMath(): Unit = runBlocking {
        MathRenderService.shared.ensureFontsLoaded(InstrumentationRegistry.getInstrumentation().targetContext)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val model = RichMarkdownRenderModel(scope)
        val publicationsOnMain = ArrayList<Boolean>()
        val current = request("새 메시지 \\(x^2\\)")
        try {
            withContext(Dispatchers.Main.immediate) {
                scope.launch { model.state.drop(1).collect { publicationsOnMain.add(Looper.getMainLooper().isCurrentThread) } }
                model.submit(request("이전 메시지 \\(y^2\\)"))
                model.submit(current)
                assertNull(model.state.value.document)
                assertEquals(current.markdown, model.state.value.fallbackMarkdown)
            }
            withTimeout(10_000) { model.awaitIdle() }
            val hydrated = model.state.value
            assertEquals(current.parseIdentity, hydrated.parseIdentity)
            assertEquals(current, hydrated.imageRequest)
            assertTrue(hydrated.mathImages.isNotEmpty())
            val append = request(current.markdown + " 추가 문장")
            withContext(Dispatchers.Main.immediate) {
                model.submit(append)
                assertSame(hydrated.document, model.state.value.document)
                assertEquals(hydrated.mathImages, model.state.value.mathImages)
            }
            withTimeout(10_000) { model.awaitIdle() }
            assertEquals(append.parseIdentity, model.state.value.parseIdentity)
            assertEquals(hydrated.mathImages, model.state.value.mathImages)
            val appearance = request(append.markdown, 36f, Color.BLUE)
            withContext(Dispatchers.Main.immediate) {
                model.submit(appearance)
                assertNotNull(model.state.value.document)
                assertTrue(model.state.value.mathImages.isEmpty())
                assertNull(model.state.value.imageRequest)
            }
            withTimeout(10_000) { model.awaitIdle() }
            assertEquals(appearance, model.state.value.imageRequest)
            assertFalse(model.hasOutstandingWork)
            assertTrue(publicationsOnMain.isNotEmpty())
            assertTrue(publicationsOnMain.all { it })
            assertThrows(IllegalStateException::class.java) { model.submit(request("main 외부")) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun canceledModelScopeHasNoOutstandingWorkOrLatePublication(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val model = RichMarkdownRenderModel(scope)
        scope.cancel()
        withContext(Dispatchers.Main.immediate) { model.submit(request("취소된 요청 \\(x\\)")) }
        withTimeout(10_000) { model.awaitIdle() }
        assertFalse(model.hasOutstandingWork)
        assertNull(model.state.value.document)
        assertTrue(model.state.value.mathImages.isEmpty())
    }
}
