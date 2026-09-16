// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** iOS `DemoLayout.readableWidth` 720pt 대응. */
val ReadableWidth: Dp = 720.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF0064D2), onPrimary = Color.White,
    background = Color(0xFFF2F2F7), onBackground = Color(0xFF1C1C1E),
    surface = Color.White, onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF636366),
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White,
    secondaryContainer = Color(0xFFD9E9FF), onSecondaryContainer = Color(0xFF004A99),
    outlineVariant = Color(0xFFD1D1D6),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF), onPrimary = Color.Black,
    background = Color.Black, onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF1C1C1E), onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF3A3A3C), onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF1C1C1E),
    secondaryContainer = Color(0xFF183657), onSecondaryContainer = Color(0xFFA8D1FF),
    outlineVariant = Color(0xFF38383A),
)

@Composable
fun DemoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val window = LocalActivity.current?.window
    val view = LocalView.current
    SideEffect {
        window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

private val BackIcon = ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f, autoMirror = true).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
        moveTo(20f, 12f); lineTo(4f, 12f); moveTo(11f, 5f); lineTo(4f, 12f); lineTo(11f, 19f)
    }
}.build()
val DemoChevron = ImageVector.Builder("Next", 24.dp, 24.dp, 24f, 24f, autoMirror = true).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
        moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
    }
}.build()
private val OptionsIcon = ImageVector.Builder("Options", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        listOf(5f, 11f, 17f).forEach { y ->
            moveTo(10f, y); lineTo(14f, y); lineTo(14f, y + 4f); lineTo(10f, y + 4f); close()
        }
    }
}.build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoTopAppBar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = { backDispatcher?.onBackPressed() }) {
                Icon(BackIcon, contentDescription = "뒤로")
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun DemoOptionsMenu(content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(OptionsIcon, contentDescription = "렌더 옵션") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, content = content)
    }
}

@Composable
fun DemoToggleOption(label: String, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(modifier = Modifier.semantics {
        role = Role.Checkbox
        toggleableState = ToggleableState(checked)
    }, text = { Text(label) }, onClick = onToggle, trailingIcon = {
        Checkbox(checked = checked, onCheckedChange = null)
    })
}
