// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.MainThread
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jimmyjung.richmarkdown.RichMarkdown
import io.github.jimmyjung.richmarkdown.RichMarkdownStreamingOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownStreamingTextBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SSE 실시간 렌더링 (iOS `SSEDemoView`). `text/event-stream` 프레임을 받아 [RichMarkdownStreamingTextBuffer]로
 * 합친 누적 문자열을 [RichMarkdown]에 계속 넘긴다.
 *
 * - 엔드포인트가 비어 있으면 fixture를 SSE 프레임으로 만들어 선택한 속도로 로컬에서 흘린다 (네트워크 불필요).
 * - 엔드포인트가 있으면 OkHttp로 GET·바디 없음·인증 없음으로 읽는다. `usesCleartextTraffic=false`라 https만 된다.
 *   응답 `Content-Type`이 `text/event-stream`이 아니면 거부한다.
 */
class SseStreamingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // intent extra `autoplay=true`면 진입 즉시 로컬 시뮬레이션을 시작한다 (README 캡처 스크립트용).
        setContent { DemoTheme { SseScreen(autoplay = intent.getBooleanExtra(EXTRA_AUTOPLAY, false)) } }
    }

    companion object {
        const val EXTRA_AUTOPLAY = "autoplay"
    }
}

/** 스트림 하나의 수명. 화면 상태(Compose state)는 main에서만 바꾼다. */
private class SseController(private val scope: CoroutineScope) {
    val buffer = RichMarkdownStreamingTextBuffer(scope)
    var isStreaming by mutableStateOf(false)
    var status by mutableStateOf("로컬 시뮬레이션 준비")
    var chunkCount by mutableStateOf(0)
    var receivedChars by mutableStateOf(0)

    private var job: Job? = null
    private var call: Call? = null
    private var runID = 0

    // 스트림은 idle 구간이 길 수 있어 read timeout을 끈다.
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    fun start(url: String, rateHz: Int) {
        stop()
        val run = runID
        buffer.reset()
        receivedChars = 0
        chunkCount = 0
        isStreaming = true
        status = if (url.isBlank()) "로컬 시뮬레이션 중…" else "연결 중…"
        job = scope.launch {
            try {
                if (url.isBlank()) streamLocal(rateHz) else streamEndpoint(url.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (run == runID) status = "오류: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                // 중지 직후 다시 시작한 스트림을 이전 작업의 finally가 덮지 않는다.
                if (run == runID) {
                    buffer.flush()
                    isStreaming = false
                }
            }
        }
    }

    fun stop() {
        runID += 1
        buffer.flush()
        if (isStreaming) status = "중지됨"
        isStreaming = false
        // cancel()이 소켓을 닫아 IO 스레드의 blocking read를 깨운다.
        call?.cancel()
        call = null
        job?.cancel()
        job = null
    }

    private suspend fun streamLocal(rateHz: Int) {
        val splitter = SseLineSplitter()
        val decoder = SseDecoder()
        for (frame in SseFixtures.frames()) {
            for (byte in frame.toByteArray()) {
                val event = splitter.consume(byte)?.let(decoder::consume) ?: continue
                if (!applyEvent(event)) return
            }
            delay(1_000L / rateHz)
        }
        decoder.finish()?.let(::applyEvent)
    }

    private suspend fun streamEndpoint(url: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("http(s) URL이 아닙니다.")
        val request = Request.Builder().url(httpUrl).header("Accept", "text/event-stream").build()
        val newCall = client.newCall(request)
        call = newCall
        withContext(Dispatchers.IO) {
            newCall.execute().use { response ->
                if (!response.isSuccessful) throw IOException("서버가 HTTP ${response.code}를 반환했습니다.")
                val type = response.header("Content-Type").orEmpty()
                if (!type.startsWith("text/event-stream")) throw IOException("Content-Type이 text/event-stream이 아닙니다: $type")

                val splitter = SseLineSplitter()
                val decoder = SseDecoder()
                val stream = response.body.byteStream()
                val bytes = ByteArray(4096)
                while (true) {
                    ensureActive()
                    val n = stream.read(bytes)
                    if (n < 0) break
                    val events = ArrayList<SseDecoder.Event>()
                    for (i in 0 until n) splitter.consume(bytes[i])?.let(decoder::consume)?.let(events::add)
                    if (events.isNotEmpty() && !withContext(Dispatchers.Main) { events.all(::applyEvent) }) return@use
                }
                val tail = ArrayList<SseDecoder.Event>()
                splitter.flush()?.let(decoder::consume)?.let(tail::add)
                decoder.finish()?.let(tail::add)
                withContext(Dispatchers.Main) { tail.all(::applyEvent) }
            }
        }
        if (isStreaming) status = "스트림 종료 (연결이 닫혔습니다)."
    }

    /** 이벤트를 화면에 반영한다. `false`면 스트림을 멈춘다. */
    @MainThread
    private fun applyEvent(event: SseDecoder.Event): Boolean = when (event) {
        is SseDecoder.Event.Text -> {
            buffer.append(event.delta)
            chunkCount += 1
            receivedChars += event.delta.length
            true
        }
        is SseDecoder.Event.Failure -> {
            status = "서버가 오류를 보냈습니다: ${event.message}"
            false
        }
        SseDecoder.Event.Done -> {
            status = "완료 ([DONE])"
            false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SseScreen(autoplay: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { SseController(scope) }
    val text by controller.buffer.text.collectAsState()
    var url by rememberSaveable { mutableStateOf("") }
    var rateHz by rememberSaveable { mutableStateOf(20) }
    val codeBlocks = remember { demoCodeBlocks(context) }
    val scrollState = rememberScrollState()

    // 콘텐츠가 자라 maxValue가 바뀔 때마다 바닥으로. 갱신은 버퍼가 10Hz로 합쳐 주므로 스크롤 레이아웃을 포화시키지 않는다.
    LaunchedEffect(scrollState.maxValue) {
        if (controller.isStreaming) scrollState.scrollTo(scrollState.maxValue)
    }
    DisposableEffect(Unit) { onDispose { controller.stop() } }
    LaunchedEffect(autoplay) { if (autoplay) controller.start(url, rateHz) }

    Scaffold(topBar = { DemoTopAppBar(stringResource(R.string.title_sse)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = ReadableWidth + 32.dp)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (controller.isStreaming) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                "${controller.chunkCount} 청크 · ${controller.receivedChars}자",
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (text.isEmpty()) {
                        Text(
                            "시작을 누르면 SSE 프레임이 도착하는 대로 렌더링합니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            RichMarkdown(
                                markdown = text,
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                streaming = if (controller.isStreaming) RichMarkdownStreamingOptions.Default else null,
                                codeBlocks = codeBlocks,
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            Surface(color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = ReadableWidth + 32.dp).fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = {
                                if (controller.isStreaming) controller.stop() else controller.start(url, rateHz)
                            }) {
                                Text(if (controller.isStreaming) "중지" else "시작")
                            }
                            SingleChoiceSegmentedButtonRow(Modifier.weight(1f).widthIn(min = (220 * LocalDensity.current.fontScale).dp)) {
                                listOf(5, 20, 60).forEachIndexed { index, rate ->
                                    SegmentedButton(
                                        selected = rateHz == rate,
                                        onClick = { rateHz = rate },
                                        enabled = !controller.isStreaming && url.isBlank(),
                                        shape = SegmentedButtonDefaults.itemShape(index, 3),
                                        icon = {},
                                    ) { Text("${rate}Hz") }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !controller.isStreaming,
                            label = { Text("SSE 엔드포인트 (https · GET)") },
                            placeholder = { Text("비우면 로컬 시뮬레이션") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Text(
                            controller.status,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
