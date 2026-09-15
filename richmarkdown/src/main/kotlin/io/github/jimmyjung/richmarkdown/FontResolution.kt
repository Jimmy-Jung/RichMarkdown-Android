// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import androidx.annotation.ColorInt

/**
 * 값 타입 [RichMarkdownFont]·[RichMarkdownTheme]를 Android 그래픽 타입으로 해석하는 공용 헬퍼.
 * Compose·View 렌더러(D5)가 같은 규칙으로 해석해야 인라인 수식 raster 크기와 본문 크기가 맞는다.
 */

/**
 * `Typeface` 해석. [RichMarkdownFont.Design.Custom]은 앱이 등록한 family 이름으로 찾고, 없으면
 * 시스템 기본으로 물러난다(`Typeface.create(String, Int)` 동작). [RichMarkdownFont.Design.Rounded]는
 * Android에 대응 서체가 없어 [RichMarkdownFont.Design.Default]와 같다.
 */
internal fun RichMarkdownFont.resolveTypeface(): Typeface {
    val base: Typeface = when (val d = design) {
        RichMarkdownFont.Design.Default, RichMarkdownFont.Design.Rounded -> Typeface.SANS_SERIF
        RichMarkdownFont.Design.Monospaced -> Typeface.MONOSPACE
        RichMarkdownFont.Design.Serif -> Typeface.SERIF
        is RichMarkdownFont.Design.Custom -> Typeface.create(d.familyName, Typeface.NORMAL)
    }
    val weightValue = weight?.value ?: return base
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(base, weightValue, false)
    } else {
        // API 28 미만은 가변 굵기가 없다. Semibold(600) 이상만 bold로 접는다.
        Typeface.create(base, if (weightValue >= RichMarkdownFontWeight.Semibold.value) Typeface.BOLD else Typeface.NORMAL)
    }
}

/** 시스템 fontScale을 적용한 텍스트 크기(px). 수식 raster의 `fontSizePx`도 이 값을 쓴다. */
internal fun RichMarkdownFont.textSizePx(context: Context): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, unscaledSizeSp, context.resources.displayMetrics)

/** 본문 색. 수식 raster key의 `colorArgb`도 이 값이다. 다른 색은 `theme.<색>.resolve(isDark)`로 읽는다. */
@ColorInt
internal fun RichMarkdownTheme.resolvedTextColor(isDark: Boolean): Int = textColor.resolve(isDark)
