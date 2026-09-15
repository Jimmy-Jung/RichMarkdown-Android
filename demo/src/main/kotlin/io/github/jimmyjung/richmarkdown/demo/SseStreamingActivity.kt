// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.MainThread
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
 * - 엔드포인트가 비어 있으면 fixture를 SSE 프레임으로 만들어 20Hz로 로컬에서 흘린다 (네트워크 불필요).
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
    var status by mutableStateOf("엔드포인트가 비어 있으면 로컬 시뮬레이션(20Hz)으로 흘립니다.")
    var receivedChars by mutableStateOf(0)

    private var job: Job? = null
    private var call: Call? = null

    // 스트림은 idle 구간이 길 수 있어 read timeout을 끈다.
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    fun start(url: String) {
        stop()
        buffer.reset()
        receivedChars = 0
        isStreaming = true
        status = if (url.isBlank()) "로컬 시뮬레이션 중…" else "연결 중…"
        job = scope.launch {
            try {
                if (url.isBlank()) streamLocal() else streamEndpoint(url.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "오류: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                buffer.flush()
                isStreaming = false
            }
        }
    }

    fun stop() {
        // cancel()이 소켓을 닫아 IO 스레드의 blocking read를 깨운다.
        call?.cancel()
        call = null
        job?.cancel()
        job = null
    }

    private suspend fun streamLocal() {
        val splitter = SseLineSplitter()
        val decoder = SseDecoder()
        for (frame in SseFixtures.frames()) {
            for (byte in frame.toByteArray()) {
                val event = splitter.consume(byte)?.let(decoder::consume) ?: continue
                if (!applyEvent(event)) return
            }
            delay(REPLAY_CHUNK_DELAY_MS)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SseScreen(autoplay: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { SseController(scope) }
    val text by controller.buffer.text.collectAsState()
    var url by rememberSaveable { mutableStateOf("") }
    val codeBlocks = remember { demoCodeBlocks(context) }
    val scrollState = rememberScrollState()

    // 콘텐츠가 자라 maxValue가 바뀔 때마다 바닥으로. 갱신은 버퍼가 10Hz로 합쳐 주므로 스크롤 레이아웃을 포화시키지 않는다.
    LaunchedEffect(scrollState.maxValue) {
        if (controller.isStreaming) scrollState.scrollTo(scrollState.maxValue)
    }
    DisposableEffect(Unit) { onDispose { controller.stop() } }
    LaunchedEffect(autoplay) { if (autoplay) controller.start(url) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.title_sse)) }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .widthIn(max = ReadableWidth)
                .fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                label = { Text("SSE 엔드포인트 (https · GET)") },
                placeholder = { Text("비우면 로컬 시뮬레이션") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { if (controller.isStreaming) controller.stop() else controller.start(url) }) {
                    Text(stringResource(if (controller.isStreaming) R.string.action_stop else R.string.action_replay))
                }
                Text(
                    "${controller.status}  ·  ${controller.receivedChars}자",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
            ) {
                RichMarkdown(
                    markdown = text,
                    modifier = Modifier.fillMaxWidth(),
                    streaming = if (controller.isStreaming) RichMarkdownStreamingOptions.Default else null,
                    codeBlocks = codeBlocks,
                )
            }
        }
    }
}
