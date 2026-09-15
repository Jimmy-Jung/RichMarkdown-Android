// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** iOS `DemoLayout.readableWidth` 720pt 대응. 넓은 화면에서 본문 폭을 여기서 멈춘다. */
val ReadableWidth: Dp = 720.dp

/** 최소 material3 테마. 동적 색은 끈다 — 라이브러리 텍스트 색(검정/흰색)과 배경 대비를 고정하기 위해서다. */
@Composable
fun DemoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
