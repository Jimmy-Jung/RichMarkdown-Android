// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

/** 수식 구분자 종류. iOS DEVELOPMENT.md §3 delimiter 규칙과 1:1. */
@InternalRichMarkdownApi
public enum class MathKind(public val isDisplay: Boolean) {
    /** `\( ... \)` */
    InlineParen(isDisplay = false),
    /** `\[ ... \]` — 공백 제외 paragraph 전체일 때만 */
    DisplayBracket(isDisplay = true),
    /** `$ ... $` (opt-in) */
    InlineDollar(isDisplay = false),
    /** `$$ ... $$` (opt-in, paragraph 전체) */
    DisplayDollar(isDisplay = true),
    /** `$$ ... $$` (opt-in inlineDouble, 문장 안) */
    InlineDoubleDollar(isDisplay = false),
}

/**
 * opt-in dollar 수식 범위. iOS `LatexDollarMathOptions`(OptionSet)에 대응한다.
 *
 * - [Single]: `$...$` inline과 paragraph 전체 `$$...$$` block. 기존 `parsesDollarMath = true`와 같다.
 * - [InlineDouble]: 문장 안 `$$...$$`를 inline 수식으로 해석한다.
 */
@InternalRichMarkdownApi
@JvmInline
public value class DollarMathOptions(public val rawValue: Int) {
    public val isEmpty: Boolean get() = rawValue == 0

    public operator fun contains(option: DollarMathOptions): Boolean =
        (rawValue and option.rawValue) == option.rawValue && option.rawValue != 0

    public operator fun plus(option: DollarMathOptions): DollarMathOptions =
        DollarMathOptions(rawValue or option.rawValue)

    public companion object {
        public val None: DollarMathOptions = DollarMathOptions(0)
        public val Single: DollarMathOptions = DollarMathOptions(1 shl 0)
        public val InlineDouble: DollarMathOptions = DollarMathOptions(1 shl 1)

        public fun fromParsesDollarMath(parsesDollarMath: Boolean): DollarMathOptions =
            if (parsesDollarMath) Single else None
    }
}

/** 보호 버퍼로 마스킹되는 수식 span. 원문 UTF-16 범위와 구분자를 포함한 source를 보존한다. */
@InternalRichMarkdownApi
public data class ProtectedMathSpan(
    val originalRange: Utf16Range,
    val kind: MathKind,
    /** 원래 구분자를 포함한 원문. 렌더 실패 시 이 문자열을 그대로 표시한다. */
    val source: String,
) {
    /** 수식 엔진에 넘길, 구분자를 제거하고 앞뒤 공백을 다듬은 LaTeX. */
    val latex: String
        get() {
            val drop = when (kind) {
                MathKind.InlineParen, MathKind.DisplayBracket,
                MathKind.DisplayDollar, MathKind.InlineDoubleDollar -> 2
                MathKind.InlineDollar -> 1
            }
            if (source.length < drop * 2) return ""
            return source.substring(drop, source.length - drop).trim()
        }
}

/** 내부 diagnostic. 미완성·빈·중첩 delimiter 등은 plain text로 두고 여기 기록한다. */
@InternalRichMarkdownApi
public data class MathDiagnostic(
    val kind: Kind,
    /** 해당 원문 수식 span 전체. */
    val range: Utf16Range,
) {
    public enum class Kind {
        UnterminatedDelimiter,
        EmptyMath,
        NestedDelimiter,
        OversizedMathSource,
        NonParagraphDisplayDelimiter,
    }
}
