// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.unit.dp
import io.github.jimmyjung.richmarkdown.LatexDollarMathOptions
import io.github.jimmyjung.richmarkdown.RichMarkdown
import io.github.jimmyjung.richmarkdown.RichMarkdownCodeBlockOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownStreamingOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownStreamingTextBuffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 스트리밍 재생 조각 간격. iOS SSE 데모 "20Hz". */
const val REPLAY_CHUNK_DELAY_MS = 50L

/** 재생으로 붙는 질문. */
const val REPLAY_QUESTION = "정규분포의 전체 넓이가 1인 이유를 설명해줘"

/**
 * Compose 채팅 (iOS `ChatDemoView`). 답변 버블마다 [RichMarkdown]을 넣고, `재생`은 fixture를 50ms 간격으로
 * [RichMarkdownStreamingTextBuffer]에 흘려 마지막 버블을 스트리밍 표시한다.
 *
 * 첫 질문부터 읽고, 재생할 때만 마지막 답변으로 이동한다.
 * intent extra `autoplay=true`면 진입 즉시 재생한다.
 */
class ComposeChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DemoTheme { ComposeChat(autoplay = intent.getBooleanExtra(EXTRA_AUTOPLAY, false)) } }
    }

    companion object {
        const val EXTRA_AUTOPLAY = "autoplay"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeChat(autoplay: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val buffer = remember { RichMarkdownStreamingTextBuffer(scope) }
    val replayText by buffer.text.collectAsState()
    var replayStarted by remember { mutableStateOf(false) }
    var isStreaming by remember { mutableStateOf(false) }
    var replayJob by remember { mutableStateOf<Job?>(null) }
    var parsesDollarMath by rememberSaveable { mutableStateOf(false) }
    var showsCaseLabels by rememberSaveable { mutableStateOf(true) }
    val codeBlocks = remember { demoCodeBlocks(context) }
    val dollarMath = if (parsesDollarMath) LatexDollarMathOptions.Single else LatexDollarMathOptions.None

    fun replay() {
        replayJob?.cancel()
        replayStarted = true
        replayJob = scope.launch {
            buffer.reset()
            isStreaming = true
            try {
                for (chunk in SampleMarkdown.graphemeChunks(SampleMarkdown.streamingAnswer)) {
                    buffer.append(chunk)
                    delay(REPLAY_CHUNK_DELAY_MS)
                }
                buffer.flush()
            } finally {
                isStreaming = false
            }
        }
    }

    LaunchedEffect(autoplay) { if (autoplay) replay() }
    val replayAnswerHeight by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "replay-answer" }?.size }
    }
    LaunchedEffect(replayText, replayStarted) {
        if (replayStarted) listState.scrollToItem(SampleMarkdown.conversation.size + 2)
    }
    LaunchedEffect(replayAnswerHeight) {
        if (isStreaming) listState.scrollToItem(SampleMarkdown.conversation.size + 2)
    }

    Scaffold(
        topBar = {
            DemoTopAppBar(
                title = "AI 챗봇",
                actions = {
                    TextButton(
                        onClick = ::replay,
                        enabled = !isStreaming,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                    ) {
                        Text(stringResource(R.string.action_replay))
                    }
                    DemoOptionsMenu {
                        DemoToggleOption("$ 수식 파싱 (opt-in)", parsesDollarMath) { parsesDollarMath = !parsesDollarMath }
                        DemoToggleOption("케이스 라벨 표시", showsCaseLabels) { showsCaseLabels = !showsCaseLabels }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = ReadableWidth + 32.dp).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(SampleMarkdown.conversation, key = { it.id }) { message ->
                    when (message.role) {
                        ChatMessage.Role.User -> UserBubble(message.text)
                        ChatMessage.Role.Assistant -> AssistantBubble(
                            caseName = if (showsCaseLabels) message.caseName else "",
                            markdown = message.text,
                            dollarMath = dollarMath,
                            codeBlocks = codeBlocks,
                            streaming = null,
                        )
                    }
                }
                if (replayStarted) {
                    item(key = "replay-question") { UserBubble(REPLAY_QUESTION) }
                    item(key = "replay-answer") {
                        AssistantBubble(
                            caseName = if (showsCaseLabels) "스트리밍 재생" else "",
                            markdown = replayText,
                            dollarMath = dollarMath,
                            codeBlocks = codeBlocks,
                            streaming = if (isStreaming) RichMarkdownStreamingOptions.Default else null,
                        )
                    }
                    item(key = "replay-bottom") { Spacer(Modifier.height(1.dp)) }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            Modifier
                .padding(start = 40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun AssistantBubble(
    caseName: String,
    markdown: String,
    dollarMath: LatexDollarMathOptions,
    codeBlocks: RichMarkdownCodeBlockOptions,
    streaming: RichMarkdownStreamingOptions?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (caseName.isNotEmpty()) {
            Text(
                caseName,
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                .padding(14.dp),
        ) {
            RichMarkdown(
                markdown = markdown,
                modifier = Modifier.fillMaxWidth(),
                dollarMath = dollarMath,
                streaming = streaming,
                codeBlocks = codeBlocks,
            )
        }
    }
}
