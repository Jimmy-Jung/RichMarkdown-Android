// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

/**
 * 텍스트 크기 계층. iOS `RichMarkdownTextStyle`(Dynamic Type 기준)에 대응한다.
 *
 * Android에는 스타일별 시스템 metric이 없으므로 iOS 기본 Dynamic Type(Large)의 point size를
 * `sp` 기준값으로 옮겼다 (`RichMarkdownFont.swift` `defaultSize`). `sp`는 시스템 `fontScale`로
 * 자동 확대되므로 Dynamic Type 배율 역할을 그대로 한다. 수식 raster의 기준 크기도 이 값이다.
 */
enum class RichMarkdownTextStyle(val defaultSizeSp: Float) {
    Body(17f),
    Headline(17f),
    Title1(28f),
    Title2(22f),
    Title3(20f),
    Caption(12f),
}

/** 굵기. iOS `RichMarkdownFontWeight`와 1:1. 렌더러가 `android.graphics.Typeface`/Compose `FontWeight`로 해석한다. */
enum class RichMarkdownFontWeight(val value: Int) {
    UltraLight(100),
    Thin(200),
    Light(300),
    Regular(400),
    Medium(500),
    Semibold(600),
    Bold(700),
    Heavy(800),
    Black(900),
}

/**
 * 요소별 폰트 지정. 순수 값 타입이라 [RichMarkdownTheme]에 담고 수식 렌더 요청 key에도 넣는다.
 * `Typeface`나 Compose `FontFamily`를 직접 담지 않는 이유는 iOS와 같다 — 두 렌더러 사이에
 * 손실 없는 변환이 없고, 값 비교가 가능해야 캐시 key가 된다. 실제 해석은 렌더러가 한다.
 *
 * ```kotlin
 * RichMarkdownFont(design = RichMarkdownFont.Design.Custom("Georgia"), relativeTo = RichMarkdownTextStyle.Body)
 * RichMarkdownFont(relativeTo = RichMarkdownTextStyle.Title1, sizeSp = 34f, weight = RichMarkdownFontWeight.Heavy)
 * ```
 */
class RichMarkdownFont(
    val design: Design = Design.Default,
    /** 크기 계층. [sizeSp]가 없을 때의 기본 크기 출처. */
    val relativeTo: RichMarkdownTextStyle = RichMarkdownTextStyle.Body,
    sizeSp: Float? = null,
    /** null이면 스타일의 기본 굵기. [Design.Custom] 서체는 해당 굵기가 없으면 무시될 수 있다. */
    val weight: RichMarkdownFontWeight? = null,
) {
    /**
     * 유효한 명시 크기만 보존한다. 공개 테마 입력은 앱 설정이나 원격 테마에서 올 수 있으므로
     * NaN·무한·0 이하는 여기서 걸러 렌더러와 캐시 key에 닿지 않게 한다 (iOS `explicitSize`).
     */
    val sizeSp: Float? = sizeSp?.takeIf { it.isFinite() && it > 0f }

    /** 시스템 fontScale 적용 전 크기(sp). 명시 크기가 없으면 [relativeTo]의 기본값. */
    val unscaledSizeSp: Float get() = sizeSp ?: relativeTo.defaultSizeSp

    sealed interface Design {
        /** 시스템 서체 (Roboto 등 기기 기본). iOS `.standard`. */
        data object Default : Design

        /** 시스템 고정폭 서체. iOS `.monospaced`. */
        data object Monospaced : Design

        /** 시스템 serif 서체(`serif` family). iOS에는 없는 값이다. */
        data object Serif : Design

        /**
         * iOS `.rounded` 대응 자리. Android 시스템에는 rounded 변형이 없어 렌더러가 [Default]와
         * 같게 해석한다. API 형태 호환용으로만 둔다.
         */
        data object Rounded : Design

        /** 앱이 등록한 서체 family 이름. 찾지 못하면 시스템 서체로 물러난다. */
        data class Custom(val familyName: String) : Design
    }

    fun copy(
        design: Design = this.design,
        relativeTo: RichMarkdownTextStyle = this.relativeTo,
        sizeSp: Float? = this.sizeSp,
        weight: RichMarkdownFontWeight? = this.weight,
    ): RichMarkdownFont = RichMarkdownFont(design, relativeTo, sizeSp, weight)

    override fun equals(other: Any?): Boolean =
        other is RichMarkdownFont &&
            design == other.design &&
            relativeTo == other.relativeTo &&
            sizeSp == other.sizeSp &&
            weight == other.weight

    override fun hashCode(): Int {
        var result = design.hashCode()
        result = 31 * result + relativeTo.hashCode()
        result = 31 * result + (sizeSp?.hashCode() ?: 0)
        result = 31 * result + (weight?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RichMarkdownFont(design=$design, relativeTo=$relativeTo, sizeSp=$sizeSp, weight=$weight)"
}
