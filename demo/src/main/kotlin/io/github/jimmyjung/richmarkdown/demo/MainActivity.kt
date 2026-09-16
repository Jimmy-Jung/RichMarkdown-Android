// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DemoTheme { Home() } }
    }
}

private data class Entry(val title: String, val activity: Class<out Activity>, val codeExtensions: Boolean = false)
private val entries = listOf(
    Entry("AI 챗봇 (Compose)", ComposeChatActivity::class.java),
    Entry("AI 챗봇 (View)", ViewChatActivity::class.java),
    Entry("SSE 실시간 렌더링 (Compose)", SseStreamingActivity::class.java),
    Entry("코드 블록 확장 (Mermaid · Prism)", ShowcaseActivity::class.java, codeExtensions = true),
    Entry("샘플 쇼케이스 (Compose · View)", ShowcaseActivity::class.java),
)

@Composable
private fun Home() {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = ReadableWidth + 32.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Text(stringResource(R.string.app_name), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
            }
            item {
                Surface(shape = RoundedCornerShape(12.dp)) {
                    Column {
                        entries.forEachIndexed { index, entry ->
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                    context.startActivity(Intent(context, entry.activity).apply {
                                        if (entry.codeExtensions) putExtra(ShowcaseActivity.EXTRA_SECTION, "code")
                                    })
                                }.padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Icon(DemoChevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (index < entries.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        }
    }
}
