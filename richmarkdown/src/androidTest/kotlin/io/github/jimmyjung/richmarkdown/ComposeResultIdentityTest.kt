// Date: 2026-10-06

package io.github.jimmyjung.richmarkdown

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jimmyjung.richmarkdown.compose.BlockMathView
import io.github.jimmyjung.richmarkdown.compose.CodeBlockView
import io.github.jimmyjung.richmarkdown.compose.RenderContext
import io.github.jimmyjung.richmarkdown.compose.ResolvedTheme
import io.github.jimmyjung.richmarkdown.core.Utf16Range
import io.github.jimmyjung.richmarkdown.core.RichMarkdownParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ComposeResultIdentityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val pendingMath = ConcurrentLinkedQueue<CompletableDeferred<MathVectorLayout?>>()

    @Before
    fun resumeTestActivity() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun awaitInitialHierarchy() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    @After
    fun releasePendingMath() {
        pendingMath.forEach { it.complete(null) }
    }

    private class Pending(val response: CompletableDeferred<List<RichMarkdownHighlightSpan>> = CompletableDeferred())

    private class ControlledHighlighter : RichMarkdownSyntaxHighlighting {
        val calls = ConcurrentLinkedQueue<Pending>()
        override suspend fun spans(code: String, language: String): List<RichMarkdownHighlightSpan> {
            val request = Pending()
            calls.add(request)
            return request.response.await()
        }
        // 구현체의 값 동등성으로 서로 다른 요청 state를 공유하면 안 된다.
        override fun equals(other: Any?): Boolean = other is ControlledHighlighter
        override fun hashCode(): Int = 0
    }

    @Test
    fun codeLanguageAndImplementationChangesStartPlainAtSamePosition() {
        val first = ControlledHighlighter()
        val second = ControlledHighlighter()
        val code = mutableStateOf("val alpha = 1")
        val language = mutableStateOf("kotlin")
        val implementation = mutableStateOf<RichMarkdownSyntaxHighlighting>(first, referentialEqualityPolicy())
        compose.setContent {
            val styles = ResolvedTheme(RichMarkdownTheme.Default, false)
            CodeBlockView(
                language.value,
                code.value,
                RenderContext(styles, emptyMap(), RichMarkdownCodeBlockOptions(implementation.value), 32f, 0, null),
            )
        }
        awaitInitialHierarchy()
        fun pending(highlighter: ControlledHighlighter): Pending {
            compose.waitUntil(5_000) { highlighter.calls.isNotEmpty() }
            return highlighter.calls.remove()
        }
        fun styles() = compose.onNodeWithText(code.value)
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().spanStyles
        fun complete(request: Pending) {
            compose.runOnIdle {
                request.response.complete(listOf(RichMarkdownHighlightSpan(Utf16Range(0, 3), RichMarkdownHighlightKind.Keyword)))
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(code.value).fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .any { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.spanStyles?.isNotEmpty() == true }
            }
        }
        complete(pending(first))

        compose.runOnIdle { code.value = "var beta = 2" }
        val sourceChange = pending(first)
        assertTrue("B 작업이 보류된 동안 A 색 범위는 없어야 한다", styles().isEmpty())
        complete(sourceChange)

        compose.runOnIdle { language.value = "swift" }
        val languageChange = pending(first)
        assertTrue("같은 코드라도 언어가 바뀌면 이전 색을 버린다", styles().isEmpty())
        complete(languageChange)

        compose.runOnIdle { implementation.value = second }
        val rendererChange = pending(second)
        assertTrue("값이 동등한 다른 구현체도 이전 결과를 공유하지 않는다", styles().isEmpty())
        complete(rendererChange)
        assertEquals("var beta = 2", compose.onNodeWithText(code.value).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text)
    }

    /**
     * 스트리밍 append는 바뀌지 않은 블록을 다시 그리지 않는다 — parse 전 재구성(같은 문서)과 parse 게시 뒤 모두.
     * 코드 블록 본문이 실행될 때마다 `diagramRenderer(language)`가 [RichMarkdownDiagramRendering.languages]를 읽는다.
     */
    @Test
    fun streamingAppendDoesNotRecomposeUnchangedCodeBlock() {
        val bodies = AtomicInteger()
        val counter = object : RichMarkdownDiagramRendering {
            override val languages: Set<String>
                get() = setOf("counter").also { bodies.incrementAndGet() }
            override fun createView(context: Context, source: String, theme: RichMarkdownTheme, onSizeChange: () -> Unit): View =
                View(context)
            @Composable
            override fun Content(source: String, theme: RichMarkdownTheme) = BasicText(source)
        }
        val options = RichMarkdownCodeBlockOptions(diagram = counter)
        val markdown = mutableStateOf("```counter\nA\n```\n\n첫 문단")
        compose.setContent {
            RichMarkdown(markdown.value, streaming = RichMarkdownStreamingOptions.Default, codeBlocks = options, isDarkTheme = false)
        }
        fun awaitParagraph(text: String) = compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        awaitParagraph("첫 문단")
        compose.waitForIdle()
        val before = bodies.get()
        assertTrue("코드 블록이 그려져야 한다", before > 0)

        compose.runOnIdle { markdown.value += " 추가" }
        awaitParagraph("첫 문단 추가")
        compose.waitForIdle()
        assertEquals("append 재구성이 바뀌지 않은 코드 블록을 다시 그렸다", before, bodies.get())
    }

    @Test
    fun blockMathChangedSourceSizeColorAndLoaderRejectPreviousOrLateVectors() {
        MathRenderService.shared.ensureFontsLoaded(InstrumentationRegistry.getInstrumentation().targetContext)
        val vector = runBlocking {
            MathRenderService.shared.layout(MathRenderKey("x", 32f, 0xFF000000.toInt(), true))
        }!!
        val source = mutableStateOf("\\[x\\]")
        val size = mutableStateOf(32f)
        val color = mutableStateOf(0xFF000000.toInt())
        val calls = ConcurrentLinkedQueue<Pair<MathRenderKey, CompletableDeferred<MathVectorLayout?>>>()
        val load: suspend (MathRenderKey) -> MathVectorLayout? = { request ->
            val response = CompletableDeferred<MathVectorLayout?>()
            pendingMath.add(response)
            calls.add(request to response)
            // 취소를 무시한 공급자의 늦은 완료도 현재 요청을 바꾸면 안 된다.
            withContext(NonCancellable) { response.await() }
        }
        val loader = mutableStateOf(load, referentialEqualityPolicy())
        compose.setContent {
            val segment = RichMarkdownParser.parse(source.value).allMathSegments.single()
            BlockMathView(
                segment,
                RenderContext(ResolvedTheme(RichMarkdownTheme.Default, false), emptyMap(),
                    RichMarkdownCodeBlockOptions.None, size.value, color.value, null),
                loader.value,
            )
        }
        awaitInitialHierarchy()
        fun pending(): Pair<MathRenderKey, CompletableDeferred<MathVectorLayout?>> {
            compose.waitUntil(5_000) { calls.isNotEmpty() }
            return calls.remove()
        }
        fun complete(response: CompletableDeferred<MathVectorLayout?>) {
            compose.runOnIdle { response.complete(vector) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("수식: ${RichMarkdownParser.parse(source.value).allMathSegments.single().latex}")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
        }
        complete(pending().second)
        compose.runOnIdle { source.value = "\\[y\\]" }
        val old = pending()
        compose.onNodeWithText(source.value).assertExists()
        compose.onNodeWithContentDescription("수식: y").assertDoesNotExist()
        compose.runOnIdle { size.value = 48f; color.value = 0xFF0000FF.toInt() }
        val current = pending()
        assertEquals(48f, current.first.fontSizePx, 0f)
        assertEquals(0xFF0000FF.toInt(), current.first.colorArgb)
        compose.runOnIdle { old.second.complete(vector) }
        compose.onNodeWithText(source.value).assertExists()
        complete(current.second)
        compose.runOnIdle {
            loader.value = { request -> load(request) }
        }
        val replacement = pending()
        compose.onNodeWithText(source.value).assertExists()
        complete(replacement.second)
    }
}
