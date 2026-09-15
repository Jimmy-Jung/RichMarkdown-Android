// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

/**
 * 블록 수식의 가로 정렬. 콘텐츠가 가용 폭보다 좁을 때만 의미가 있고, 넓으면 가로 스크롤한다.
 * iOS `LatexEquationAlignment`.
 */
enum class LatexEquationAlignment {
    Leading,
    Center,
    Trailing,
}

/**
 * 공개 theme. 값 비교로 렌더 요청 key에 포함된다. iOS `RichMarkdownTheme.swift`와 필드 1:1.
 *
 * 색과 폰트 모두 **요소 단위**다. 범위(문자 구간) 단위 지정은 제공하지 않는다.
 *
 * 기본 색은 iOS가 쓰는 UIKit 시스템 시맨틱 색의 light/dark 값을 ARGB로 옮긴 것이다
 * (Apple HIG 시스템 색 표). Android는 Context 없이 시맨틱 색을 해석할 수 없어 값으로 고정한다.
 */
data class RichMarkdownTheme(
    /** iOS `.primary` = label: light #000000 / dark #FFFFFF */
    val textColor: RichMarkdownColor = RichMarkdownColor.rgb(light = 0x000000, dark = 0xFFFFFF),
    /**
     * iOS `Color.accessibleLink`. 시스템 블루(#007AFF)는 흰 배경 대비 약 3.6:1로 본문 기준(4.5:1)
     * 미달. light (0.04, 0.31, 0.72) ≈ #0A4FB8 약 7.5:1, dark (0.55, 0.75, 1.00) ≈ #8CBFFF 약 8.9:1.
     */
    val linkColor: RichMarkdownColor = RichMarkdownColor.rgb(light = 0x0A4FB8, dark = 0x8CBFFF),
    /** iOS `secondarySystemBackground`: light #F2F2F7 / dark #1C1C1E */
    val codeBlockBackground: RichMarkdownColor = RichMarkdownColor.rgb(light = 0xF2F2F7, dark = 0x1C1C1E),
    /** iOS `secondarySystemFill`: light rgba(120,120,128,0.16) / dark rgba(120,120,128,0.32) */
    val inlineCodeBackground: RichMarkdownColor = RichMarkdownColor.dynamic(light = 0x29787880, dark = 0x52787880),
    /**
     * 인라인 코드 텍스트 색 (iOS `Color.inlineCodeAccent`, Notion 스타일 붉은 강조).
     * light (0.66, 0.20, 0.15) ≈ #A83326, dark (1.00, 0.45, 0.41) ≈ #FF7369. 칩 배경 대비 약 5.5:1 / 5.7:1.
     */
    val inlineCodeForeground: RichMarkdownColor = RichMarkdownColor.rgb(light = 0xA83326, dark = 0xFF7369),
    /** 인라인 코드 칩 테두리. iOS `separator`: light rgba(60,60,67,0.29) / dark rgba(84,84,88,0.60) */
    val inlineCodeBorder: RichMarkdownColor = RichMarkdownColor.dynamic(light = 0x4A3C3C43, dark = 0x99545458.toInt()),
    /** iOS `systemGray3`: light #C7C7CC / dark #48484A */
    val quoteBar: RichMarkdownColor = RichMarkdownColor.rgb(light = 0xC7C7CC, dark = 0x48484A),
    /** iOS `tertiarySystemBackground`: light #FFFFFF / dark #2C2C2E */
    val codeHeaderBackground: RichMarkdownColor = RichMarkdownColor.rgb(light = 0xFFFFFF, dark = 0x2C2C2E),
    /** 코드 블록 신택스 색. `RichMarkdownCodeBlockOptions.highlighter`를 주입했을 때만 쓰인다. */
    val syntax: RichMarkdownSyntaxColors = RichMarkdownSyntaxColors.Default,

    /** 본문 문단, 리스트 마커, 링크, 원문 fallback. 수식 raster의 기준 크기도 이 값을 따른다. */
    val bodyFont: RichMarkdownFont = RichMarkdownFont(relativeTo = RichMarkdownTextStyle.Body),
    val heading1Font: RichMarkdownFont = RichMarkdownFont(relativeTo = RichMarkdownTextStyle.Title1, weight = RichMarkdownFontWeight.Bold),
    val heading2Font: RichMarkdownFont = RichMarkdownFont(relativeTo = RichMarkdownTextStyle.Title2, weight = RichMarkdownFontWeight.Bold),
    val heading3Font: RichMarkdownFont = RichMarkdownFont(relativeTo = RichMarkdownTextStyle.Title3, weight = RichMarkdownFontWeight.Semibold),
    /** 헤딩 4단계 이하 전부. */
    val heading4Font: RichMarkdownFont = RichMarkdownFont(relativeTo = RichMarkdownTextStyle.Headline),
    /** 인라인 코드, 코드 블록 본문, 블록 수식 fallback. */
    val codeFont: RichMarkdownFont = RichMarkdownFont(design = RichMarkdownFont.Design.Monospaced, relativeTo = RichMarkdownTextStyle.Body),
    /** 코드 블록 헤더의 언어 라벨. */
    val codeLabelFont: RichMarkdownFont = RichMarkdownFont(design = RichMarkdownFont.Design.Monospaced, relativeTo = RichMarkdownTextStyle.Caption),
    /** 수식 서체. Android는 [LatexMathFont.KaTeX] 하나다. */
    val mathFont: LatexMathFont = LatexMathFont.KaTeX,
    /** 블록 수식 정렬. 기본 leading (콘텐츠가 좁을 때만 의미). */
    val equationAlignment: LatexEquationAlignment = LatexEquationAlignment.Leading,
) {
    /** 헤딩 레벨별 폰트. 4단계 이하는 모두 [heading4Font]다. */
    fun headingFont(level: Int): RichMarkdownFont = when (level) {
        1 -> heading1Font
        2 -> heading2Font
        3 -> heading3Font
        else -> heading4Font
    }

    companion object {
        val Default: RichMarkdownTheme = RichMarkdownTheme()
    }
}

/**
 * 코드 블록 신택스 색. 역할별 **요소 단위**이며 테마의 다른 색과 같은 규칙이다.
 * 기본값은 iOS `RichMarkdownSyntaxColors`의 light/dark hex 그대로 — 코드 블록 배경 위에서
 * 일곱 역할 모두 본문 대비 기준(4.5:1)을 넘는다.
 */
data class RichMarkdownSyntaxColors(
    val keyword: RichMarkdownColor = RichMarkdownColor.rgb(light = 0x9A246C, dark = 0xFF7AB2),
    val string: RichMarkdownColor = RichMarkdownColor.rgb(light = 0xAF301D, dark = 0xFC8F74),
    val comment: RichMarkdownColor = RichMarkdownColor.rgb(light = 0x627069, dark = 0x9AA7B2),
    val number: RichMarkdownColor = RichMarkdownColor.rgb(light = 0x7654A3, dark = 0xD9C97C),
    val type: RichMarkdownColor = RichMarkdownColor.rgb(light = 0x176A66, dark = 0x82D4C0),
    val function: RichMarkdownColor = RichMarkdownColor.rgb(light = 0x235CAD, dark = 0x73BFFF),
    val property: RichMarkdownColor = RichMarkdownColor.rgb(light = 0x74519A, dark = 0xC2A8FF),
) {
    fun color(kind: RichMarkdownHighlightKind): RichMarkdownColor = when (kind) {
        RichMarkdownHighlightKind.Keyword -> keyword
        RichMarkdownHighlightKind.String -> string
        RichMarkdownHighlightKind.Comment -> comment
        RichMarkdownHighlightKind.Number -> number
        RichMarkdownHighlightKind.Type -> type
        RichMarkdownHighlightKind.Function -> function
        RichMarkdownHighlightKind.Property -> property
    }

    companion object {
        val Default: RichMarkdownSyntaxColors = RichMarkdownSyntaxColors()
    }
}
