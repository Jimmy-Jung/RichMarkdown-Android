// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** 루트 목록. iOS `RichMarkdownDemoApp` 루트 `List` 대응 — 각 데모 화면으로 들어가는 버튼만 둔다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DemoTheme { Home() } }
    }
}

private data class Entry(val titleRes: Int, val description: String, val activity: Class<out Activity>)

private val entries = listOf(
    Entry(R.string.title_showcase, "샘플 전부 · Compose/View 전환 · \$ 수식·다크·코드 확장 토글", ShowcaseActivity::class.java),
    Entry(R.string.title_compose_chat, "LazyColumn 버블 + 스트리밍 재생", ComposeChatActivity::class.java),
    Entry(R.string.title_view_chat, "RecyclerView 셀의 RichMarkdownView + 스트리밍 재생", ViewChatActivity::class.java),
    Entry(R.string.title_sse, "text/event-stream 디코더 · 로컬 시뮬레이션 또는 https 엔드포인트", SseStreamingActivity::class.java),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home() {
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .widthIn(max = ReadableWidth)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            entries.forEach { entry ->
                Button(
                    onClick = { context.startActivity(Intent(context, entry.activity)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(entry.titleRes), style = MaterialTheme.typography.titleMedium)
                        Text(entry.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
