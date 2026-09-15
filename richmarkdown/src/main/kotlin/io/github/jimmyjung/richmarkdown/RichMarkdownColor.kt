// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import androidx.annotation.ColorInt

/**
 * light/dark ARGB 쌍. iOS의 동적 `UIColor { traits in ... }`에 대응한다.
 *
 * `androidx.compose.ui.graphics.Color`를 직접 담지 않는 이유: 네이티브 View 렌더러(D5)가
 * `android.graphics.Paint`/`Spannable`에 ARGB `Int`를 넣어야 하고, 두 렌더러가 같은 값에서
 * 색을 해석해야 한다. Compose 쪽은 `Color(resolve(isDark))`로 변환한다.
 */
data class RichMarkdownColor(
    @get:ColorInt val light: Int,
    @get:ColorInt val dark: Int,
) {
    @ColorInt
    fun resolve(isDark: Boolean): Int = if (isDark) dark else light

    companion object {
        /** light/dark 구분 없는 고정 색. */
        fun single(@ColorInt argb: Int): RichMarkdownColor = RichMarkdownColor(argb, argb)

        fun dynamic(@ColorInt light: Int, @ColorInt dark: Int): RichMarkdownColor =
            RichMarkdownColor(light, dark)

        /** `0xRRGGBB` 두 값을 불투명 ARGB로 감싼다. iOS `RichMarkdownSyntaxColors.dynamic(light:dark:)` 대응. */
        fun rgb(light: Int, dark: Int): RichMarkdownColor =
            RichMarkdownColor(opaque(light), opaque(dark))

        private fun opaque(rgb: Int): Int = (0xFF shl 24) or (rgb and 0xFFFFFF)
    }
}
