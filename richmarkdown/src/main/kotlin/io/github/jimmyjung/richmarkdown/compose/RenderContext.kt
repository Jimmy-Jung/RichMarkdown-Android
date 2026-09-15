// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.compose

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.unit.sp
import io.github.jimmyjung.richmarkdown.RenderedMath
import io.github.jimmyjung.richmarkdown.RichMarkdownCodeBlockOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownColor
import io.github.jimmyjung.richmarkdown.RichMarkdownFont
import io.github.jimmyjung.richmarkdown.RichMarkdownHighlightKind
import io.github.jimmyjung.richmarkdown.RichMarkdownTheme
import io.github.jimmyjung.richmarkdown.core.MathSegment

/**
 * 테마를 Compose 색·`TextStyle`로 한 번 해석한 값. iOS `RichMarkdownFont.resolvedFont` +
 * `@Environment(\.richMarkdownTheme)` 색 접근에 대응한다.
 * `remember(theme, isDark)`로 만들어 재구성마다 Typeface를 다시 만들지 않는다.
 */
internal class ResolvedTheme(val theme: RichMarkdownTheme, val isDark: Boolean) {
    val textColor: Color = theme.textColor.toColor(isDark)
    val linkColor: Color = theme.linkColor.toColor(isDark)
    val codeBlockBackground: Color = theme.codeBlockBackground.toColor(isDark)
    val inlineCodeBackground: Color = theme.inlineCodeBackground.toColor(isDark)
    val inlineCodeForeground: Color = theme.inlineCodeForeground.toColor(isDark)
    val inlineCodeBorder: Color = theme.inlineCodeBorder.toColor(isDark)
    val quoteBar: Color = theme.quoteBar.toColor(isDark)
    val codeHeaderBackground: Color = theme.codeHeaderBackground.toColor(isDark)

    val body: TextStyle = theme.bodyFont.toTextStyle(textColor)
    val code: TextStyle = theme.codeFont.toTextStyle(textColor)
    val codeLabel: TextStyle = theme.codeLabelFont.toTextStyle(textColor)
    private val headings: List<TextStyle> = (1..4).map { theme.headingFont(it).toTextStyle(textColor) }

    /** 헤딩 레벨별 스타일. 4단계 이하는 전부 `heading4Font` (iOS `headingFont(level:)`). */
    fun heading(level: Int): TextStyle = headings[level.coerceIn(1, 4) - 1]

    /** 인라인 코드 span. 감싼 블록 크기가 아니라 `codeFont` 크기를 쓴다 (iOS 알려진 제약). */
    val inlineCode: SpanStyle = SpanStyle(
        color = inlineCodeForeground,
        fontSize = code.fontSize,
        fontWeight = code.fontWeight,
        fontFamily = code.fontFamily,
    )

    /** 수식 raster 전·실패 시 원문 표시 span (계약 §7: `codeFont`, 색은 본문 색을 그대로 상속). */
    val mathFallback: SpanStyle = inlineCode.copy(color = Color.Unspecified)

    fun syntaxColor(kind: RichMarkdownHighlightKind): Color = theme.syntax.color(kind).toColor(isDark)
}

/**
 * 블록 재귀에 함께 내려가는 렌더 입력. iOS `RichMarkdownBlockView`의 `images` 인자와
 * environment(theme·codeBlocks) 묶음이다.
 */
internal class RenderContext(
    val styles: ResolvedTheme,
    val images: Map<MathSegment, RenderedMath>,
    val codeBlocks: RichMarkdownCodeBlockOptions,
    /** 수식 기준 크기(px) = bodyFont sp × fontScale × density. 블록 수식 벡터 레이아웃 key에 쓴다. */
    val mathFontSizePx: Float,
    /** 해석된 본문 색 ARGB. 블록 수식 key에 쓴다. */
    val colorArgb: Int,
    /** null이면 Compose `LocalUriHandler`가 연다 (계약 §5). */
    val onOpenLink: ((Uri) -> Unit)?,
) {
    val theme: RichMarkdownTheme get() = styles.theme
}

internal fun RichMarkdownColor.toColor(isDark: Boolean): Color = Color(resolve(isDark))

private fun RichMarkdownFont.toTextStyle(color: Color): TextStyle = TextStyle(
    color = color,
    // sp에는 시스템 fontScale이 자동으로 곱해진다 — iOS `@ScaledMetric` 배율에 해당 (치환표 §4).
    fontSize = unscaledSizeSp.sp,
    fontWeight = weight?.let { FontWeight(it.value) },
    fontFamily = design.toFontFamily(),
)

/** iOS `.standard/.monospaced/.rounded/.custom` → Compose `FontFamily`. Rounded는 Android에 없어 Default. */
private fun RichMarkdownFont.Design.toFontFamily(): FontFamily = when (this) {
    RichMarkdownFont.Design.Default, RichMarkdownFont.Design.Rounded -> FontFamily.Default
    RichMarkdownFont.Design.Monospaced -> FontFamily.Monospace
    RichMarkdownFont.Design.Serif -> FontFamily.Serif
    // 못 찾은 family는 `Typeface.create`가 시스템 서체로 물러난다 (FontResolution.resolveTypeface와 같은 규칙).
    is RichMarkdownFont.Design.Custom ->
        FontFamily(Typeface(android.graphics.Typeface.create(familyName, android.graphics.Typeface.NORMAL)))
}
