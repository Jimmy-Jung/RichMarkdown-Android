// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import java.text.BreakIterator

// Swift `String`·`Character` 의미를 Kotlin UTF-16 `String` 위에서 재현하는 내부 도우미.
// iOS 코드는 Character(grapheme cluster) 단위로 순회하지만 이 포트는 UTF-16 char 단위로 순회한다.
// ponytail: 구분자가 전부 ASCII라 결합 문자(`*` + U+0301 등)가 붙은 경우만 결과가 다르다. 실사용 보고가 있으면 grapheme 순회로 바꾼다.
// ponytail: Swift `hasPrefix`·`==`의 canonical equivalence(NFC/NFD 동일시)는 재현하지 않는다. 같은 조건에서 재검토.

// MARK: - 범위 검증

/** iOS `EditorBlock.isValidRange`. 끝 위치를 더하기 전에 음수·위치·길이를 검사해 overflow를 막는다. */
internal fun EditorRange.isValid(textLength: Int): Boolean =
    location >= 0 && length >= 0 && location <= textLength && length <= textLength - location

/**
 * iOS `isValidRange && Range(NSRange, in: text) != nil` 대응.
 *
 * 편차 2: 시작·끝이 UTF-16 surrogate pair 중간이면 거절한다. iOS `Range(_:in:)`는 내림 보정한다.
 */
internal fun String.containsScalarAligned(range: EditorRange): Boolean =
    range.isValid(length) && isScalarBoundary(range.location) && isScalarBoundary(range.end)

private fun String.isScalarBoundary(offset: Int): Boolean =
    offset <= 0 || offset >= length || !(this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate())

/**
 * iOS `String.Index(_:within:)` 대응: UTF-16 offset이 문자(grapheme cluster) 경계인가.
 *
 * 편차 1: Swift `Character` 경계 대신 `java.text.BreakIterator.getCharacterInstance()`를 쓴다
 * (Android는 ICU, JVM 20+는 확장 grapheme 규칙).
 */
internal fun String.isGraphemeBoundary(offset: Int): Boolean {
    if (offset < 0 || offset > length) return false
    if (offset == 0 || offset == length) return true
    return BreakIterator.getCharacterInstance().apply { setText(this@isGraphemeBoundary) }.isBoundary(offset)
}

// MARK: - Swift 문자 분류

/**
 * Swift `trimmingCharacters(in: .whitespaces)` — Unicode Zs와 U+0009만 제거한다.
 * 편차 4 주의: Kotlin `trim()`은 개행·제어 문자까지 지우므로 쓰지 않는다.
 */
internal fun String.trimmingSwiftWhitespaces(): String =
    trim { it == '\t' || it.category == CharCategory.SPACE_SEPARATOR }

/** Swift `Character.isWhitespace` (Unicode White_Space 속성. 전부 BMP). */
internal fun isSwiftWhitespace(codePoint: Int): Boolean = when (codePoint) {
    in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
    else -> false
}

/** Swift `Character.isNewline`. */
internal fun Char.isSwiftNewline(): Boolean = this in '\n'..'\r' || this == '\u0085' || this == ' ' || this == ' '

/** Swift `Character.isLetter || Character.isNumber` (Alphabetic 속성 또는 Numeric_Type 보유). */
internal fun isSwiftWord(codePoint: Int): Boolean = Character.isAlphabetic(codePoint) ||
    when (Character.getType(codePoint).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

// MARK: - 줄바꿈

/** 편차 3: Markdown 입력과 문서 교체 문자열의 CRLF·CR을 LF로 맞춘다. Swift는 `"\r\n"`을 한 Character로 다룬다. */
internal fun String.normalizingLineEndings(): String =
    if (indexOf('\r') < 0) this else replace("\r\n", "\n").replace('\r', '\n')
