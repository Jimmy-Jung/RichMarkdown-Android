// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import io.github.jimmyjung.richmarkdown.core.DollarMathOptions

/**
 * opt-in dollar 수식 범위. [None]이면 `\(...\)`·`\[...\]`만 해석한다.
 * iOS `LatexDollarMathOptions`(OptionSet)와 1:1.
 *
 * ```kotlin
 * RichMarkdown(markdown = text, dollarMath = LatexDollarMathOptions.Single + LatexDollarMathOptions.InlineDouble)
 * ```
 */
@JvmInline
value class LatexDollarMathOptions(val rawValue: Int) {
    val isEmpty: Boolean get() = rawValue == 0

    operator fun contains(option: LatexDollarMathOptions): Boolean =
        option.rawValue != 0 && (rawValue and option.rawValue) == option.rawValue

    operator fun plus(option: LatexDollarMathOptions): LatexDollarMathOptions =
        LatexDollarMathOptions(rawValue or option.rawValue)

    internal fun toCore(): DollarMathOptions {
        var core = DollarMathOptions.None
        if (contains(Single)) core += DollarMathOptions.Single
        if (contains(InlineDouble)) core += DollarMathOptions.InlineDouble
        return core
    }

    companion object {
        val None: LatexDollarMathOptions = LatexDollarMathOptions(0)

        /** `$...$` inline과 paragraph 전체 `$$...$$` block. `parsesDollarMath = true`와 같다. */
        val Single: LatexDollarMathOptions = LatexDollarMathOptions(1 shl 0)

        /**
         * 문장 안 `$$...$$`를 inline 수식으로 해석한다. `$...$`와 같은 공백·숫자·줄바꿈 규칙.
         * 기본값이 아닌 이유: `$$5 and $$6` 같은 표기와 충돌할 수 있어 호출자가 켠다.
         */
        val InlineDouble: LatexDollarMathOptions = LatexDollarMathOptions(1 shl 1)

        /** 기존 Bool API 대응. `true` → [Single], `false` → [None]. */
        fun fromParsesDollarMath(parsesDollarMath: Boolean): LatexDollarMathOptions =
            if (parsesDollarMath) Single else None
    }
}
