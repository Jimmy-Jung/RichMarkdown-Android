// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown

import android.os.SystemClock
import android.text.NoCopySpan
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jimmyjung.richmarkdown.core.InputLimits
import io.github.jimmyjung.richmarkdown.core.RichMarkdownParser
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds

/**
 * 렌더 수준 스트리밍 검사 (Tests/Fixtures/README.md §7, iOS `StreamingRenderEquivalenceTests.swift`).
 *
 * `fixtures/streaming`의 `.md`를 유니코드 코드 포인트 조각으로 잘라 데모 SSE 화면과 같은 배선으로 흘린다:
 * [RichMarkdownStreamingTextBuffer.append] → `text` 게시 → `RichMarkdown(markdown = text, streaming = Default)`,
 * 끝나면 `flush()` 뒤 `streaming = null`. 같은 게시를 View 채팅 화면처럼 [RichMarkdownView.markdown]에도 넣어
 * 렌더 모델 게시 순서(latest-wins)와 최종 문서를 직접 확인한다.
 *
 * 최종 결과는 전체 원문을 한 번에 넣은 렌더와 비교한다 — Compose는 semantics 트리, View는 뷰 계층 서술,
 * 문서는 캐시를 거치지 않은 직접 파싱. 스트리밍 tail의 흔적(페이드 alpha·숨긴 opener)이 남으면 서술 비교에서 드러난다.
 */
@RunWith(Parameterized::class)
class StreamingRenderEquivalenceTest(private val name: String, private val chunkSize: Int) {

    @get:Rule
    val compose = createComposeRule()

    private val failures = ConcurrentLinkedQueue<Throwable>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> failures += error },
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun streamedRenderMatchesOneShotRender() {
        assertEquals("streaming fixture 6종과 각 .chunks.json을 읽어야 한다", 6, cases().map { it[0] }.toSet().size)
        val full = readAsset("$DIR/$name.md")
        ParseCache.removeAll()

        val harness = Harness(full)
        harness.stream(codePointChunks(full, chunkSize))
        harness.awaitStreamedModelIdle()

        // 게시 순서: 버퍼·렌더 모델의 게시는 언제나 앞선 게시를 prefix로 갖는다 (latest-wins).
        assertTrue("코루틴 실패: ${failures.toList()}", failures.isEmpty())
        assertEquals("버퍼에 쌓인 조각은 원문과 같아야 한다", full, harness.buffer.text.value)
        assertNull("버퍼가 이전 원문을 게시했다", firstRegression(harness.published))
        assertNull("더 새 문서 뒤에 이전 문서가 게시됐다", firstRegression(harness.identities))
        assertNull("더 새 원문 뒤에 이전 원문이 게시됐다", firstRegression(harness.fallbacks))
        assertEquals("최종 게시는 마지막 제출이다", full, harness.identities.lastOrNull())

        val state = harness.streamedView.model.state.value
        val direct = RichMarkdownParser.parse(InputLimits.bound(full), LatexDollarMathOptions.None.toCore())
        assertEquals("스트리밍 최종 문서가 전체 원문 1회 파싱과 다르다", direct, state.document)

        harness.showReference()
        awaitStableEqual("Compose semantics") { describe(semantics(STREAMED_TAG)) to describe(semantics(REFERENCE_TAG)) }
        awaitStableEqual("View 계층") { compose.runOnIdle { describe(harness.streamedView) to describe(harness.referenceView) } }
    }

    // MARK: - 하네스

    /** 데모 SSE 화면의 Compose 배선과 View 채팅 화면의 View 배선을 한 버퍼에 묶는다. */
    private inner class Harness(private val full: String) {
        lateinit var buffer: RichMarkdownStreamingTextBuffer
        lateinit var streamedView: RichMarkdownView
        lateinit var referenceView: RichMarkdownView
        val published = ArrayList<String>()
        val identities = ArrayList<String>()
        val fallbacks = ArrayList<String>()
        private val isStreaming = mutableStateOf(true)
        private val referenceVisible = mutableStateOf(false)

        init {
            // 데모는 100ms 간격이다. 짧은 간격으로 latest-wins 합치기·trailing 게시·렌더 중간 게시가 모두 일어나게 한다.
            compose.runOnUiThread { buffer = RichMarkdownStreamingTextBuffer(scope, interval = 5.milliseconds) }
            compose.setContent {
                val text by buffer.text.collectAsState()
                Column(Modifier.fillMaxWidth()) {
                    RichMarkdown(
                        markdown = text,
                        modifier = Modifier.fillMaxWidth().testTag(STREAMED_TAG),
                        streaming = if (isStreaming.value) RichMarkdownStreamingOptions.Default else null,
                        isDarkTheme = false,
                    )
                    AndroidView(factory = { context -> newView(context).also { streamedView = it } }, Modifier.fillMaxWidth())
                    if (referenceVisible.value) {
                        RichMarkdown(full, Modifier.fillMaxWidth().testTag(REFERENCE_TAG), isDarkTheme = false)
                        AndroidView(
                            factory = { context -> newView(context).also { referenceView = it; it.markdown = full } },
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            compose.runOnIdle {
                streamedView.streaming = RichMarkdownStreamingOptions.Default
                scope.launch { buffer.text.drop(1).collect { published += it; streamedView.markdown = it } }
                scope.launch { streamedView.model.state.collect(::record) }
            }
        }

        private fun newView(context: android.content.Context) = RichMarkdownView(context).apply { isDarkTheme = false }

        private fun record(state: RichMarkdownRenderModel.State) {
            state.parseIdentity?.markdown?.let { if (identities.lastOrNull() != it) identities += it }
            if (state.fallbackMarkdown.isNotEmpty() && fallbacks.lastOrNull() != state.fallbackMarkdown) fallbacks += state.fallbackMarkdown
        }

        /** 조각마다 한 프레임을 진행해 Compose가 중간 원문을 실제로 그리게 한다. 끝은 데모와 같은 flush → streaming 해제. */
        fun stream(chunks: List<String>) {
            for (chunk in chunks) {
                compose.runOnUiThread { buffer.append(chunk) }
                compose.mainClock.advanceTimeByFrame()
                SystemClock.sleep(1)
            }
            compose.runOnUiThread {
                buffer.flush()
                isStreaming.value = false
                streamedView.streaming = null
            }
        }

        fun awaitStreamedModelIdle() = compose.waitUntil(IDLE_TIMEOUT_MS) {
            compose.runOnIdle { streamedView.model.state.value.parseIdentity?.markdown == full && !streamedView.model.hasOutstandingWork }
        }

        fun showReference() {
            compose.runOnIdle { referenceVisible.value = true }
            compose.waitUntil(IDLE_TIMEOUT_MS) {
                compose.runOnIdle { ::referenceView.isInitialized && referenceView.model.state.value.document != null && !referenceView.model.hasOutstandingWork }
            }
        }
    }

    /**
     * 두 서술이 같아진 뒤 [STABLE_POLLS]번 연속 같은지 본다. 수식 벡터·이미지 hydration은 idle 뒤에도 비동기로 오므로
     * 한 번의 비교로 끝내지 않는다. 시간 안에 같아지지 않으면 마지막 서술로 실패한다. [read]는 테스트 스레드에서 부른다.
     */
    private fun awaitStableEqual(label: String, read: () -> Pair<List<String>, List<String>>) {
        val deadline = SystemClock.uptimeMillis() + IDLE_TIMEOUT_MS
        var streak = 0
        while (streak < STABLE_POLLS) {
            compose.waitForIdle()
            val (streamed, reference) = read()
            assertTrue("$label: 1회 렌더 서술이 비어 있다", reference.isNotEmpty())
            streak = if (streamed == reference) streak + 1 else 0
            if (streak == 0 && SystemClock.uptimeMillis() > deadline) {
                assertEquals("$name × $chunkSize: 스트리밍 결과($label)가 전체 원문 1회 렌더와 다르다", reference, streamed)
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun semantics(tag: String): SemanticsNode = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    companion object {
        private const val DIR = "fixtures/streaming"
        private const val STREAMED_TAG = "streamed"
        private const val REFERENCE_TAG = "reference"
        private const val IDLE_TIMEOUT_MS = 20_000L
        private const val STABLE_POLLS = 5
        private const val POLL_MS = 100L

        @JvmStatic
        @Parameterized.Parameters(name = "{0} × {1}")
        fun cases(): List<Array<Any>> = InstrumentationRegistry.getInstrumentation().context.assets
            .list(DIR).orEmpty().filter { it.endsWith(".md") }.sorted()
            .flatMap { file ->
                val name = file.removeSuffix(".md")
                val sizes = JSONObject(readAsset("$DIR/$name.chunks.json")).getJSONArray("sizes")
                List(sizes.length()) { arrayOf<Any>(name, sizes.getInt(it)) }
            }

        private fun readAsset(path: String): String =
            InstrumentationRegistry.getInstrumentation().context.assets.open(path).bufferedReader().use { it.readText() }

        /** 유니코드 코드 포인트 단위 조각. grapheme(결합 문자·ZWJ 이모지)도 가를 수 있다 — README §7. */
        fun codePointChunks(text: String, size: Int): List<String> {
            val codePoints = text.codePoints().toArray()
            return (codePoints.indices step size).map { start ->
                String(codePoints, start, minOf(size, codePoints.size - start))
            }
        }

        /** 앞 값을 prefix로 갖지 않는 첫 게시. 없으면 null. */
        fun firstRegression(sequence: List<String>): Pair<String, String>? =
            sequence.zipWithNext().firstOrNull { (previous, next) -> !next.startsWith(previous) }

        /** semantics 트리를 문서 순서로 적는다: 글자, span 스타일(색 alpha 포함), 링크, 접근성 설명. */
        fun describe(node: SemanticsNode): List<String> = buildList {
            fun visit(current: SemanticsNode, depth: Int) {
                val texts = current.config.getOrNull(SemanticsProperties.Text).orEmpty().map { describe(it) }
                val description = current.config.getOrNull(SemanticsProperties.ContentDescription)
                if (texts.isNotEmpty() || description != null) add("$depth text=$texts description=$description")
                current.children.forEach { visit(it, depth + 1) }
            }
            visit(node, 0)
        }

        private fun describe(text: AnnotatedString): String = buildString {
            append(text.text)
            text.spanStyles.forEach { append(" |${it.start}-${it.end} ${it.item}") }
            text.getLinkAnnotations(0, text.length).forEach { append(" |link ${it.start}-${it.end} ${(it.item as? LinkAnnotation.Url)?.url}") }
            text.getStringAnnotations(0, text.length).forEach { append(" |${it.tag} ${it.start}-${it.end} ${it.item}") }
        }

        /** 보이는 뷰 계층을 문서 순서로 적는다: 뷰 종류, 글자, span 종류·범위·전경색, 접근성 설명. */
        fun describe(view: View): List<String> = buildList {
            fun visit(current: View, depth: Int) {
                if (current.visibility != View.VISIBLE) return
                val text = (current as? TextView)?.text
                add("$depth ${current.javaClass.simpleName} text=${text?.toString()?.let(::quote)} spans=${spans(text)} description=${current.contentDescription}")
                if (current is ViewGroup) for (index in 0 until current.childCount) visit(current.getChildAt(index), depth + 1)
            }
            visit(view, 0)
        }

        private fun spans(text: CharSequence?): List<String> {
            val spanned = text as? Spanned ?: return emptyList()
            return spanned.getSpans(0, spanned.length, Any::class.java)
                .filter { it !is NoCopySpan }
                .map { span ->
                    val color = (span as? ForegroundColorSpan)?.foregroundColor?.let { "#%08X".format(it) }.orEmpty()
                    "${span.javaClass.simpleName}@${spanned.getSpanStart(span)}-${spanned.getSpanEnd(span)}$color"
                }
                .sorted()
        }

        private fun quote(value: String) = "\"" + value.replace("\n", "\\n") + "\""
    }
}
