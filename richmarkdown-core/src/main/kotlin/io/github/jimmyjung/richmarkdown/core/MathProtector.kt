// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

/**
 * 길이 보존 mask. iOS DEVELOPMENT.md §3 보호 버퍼.
 *
 * 수식 span의 각 non-newline UTF-16 code unit을 ASCII `x` 하나로 바꾼다. LF/CR는 그대로 둔다.
 * 보호 버퍼와 원문의 길이가 항상 같으므로 2차 파싱의 위치를 offset 변환 없이 원문에 그대로 쓴다.
 * surrogate pair도 code unit 단위로 `xx`가 되어 길이가 유지된다.
 */
@InternalRichMarkdownApi
public object MathProtector {
    private const val MASK = 'x'

    public fun protect(chars: CharArray, spans: List<ProtectedMathSpan>): CharArray {
        val masked = chars.copyOf()
        for (span in spans) {
            for (i in span.originalRange.start until span.originalRange.end) {
                val c = masked[i]
                if (c != '\n' && c != '\r') masked[i] = MASK
            }
        }
        return masked
    }

    public fun protect(text: String, spans: List<ProtectedMathSpan>): String =
        String(protect(text.toCharArray(), spans))

    /** `restore(protect(source)) == source`를 code unit 단위로 보장한다. */
    public fun restore(masked: CharArray, original: CharArray, spans: List<ProtectedMathSpan>): CharArray {
        require(masked.size == original.size) { "mask는 길이를 보존해야 한다" }
        val restored = masked.copyOf()
        for (span in spans) {
            for (i in span.originalRange.start until span.originalRange.end) {
                restored[i] = original[i]
            }
        }
        return restored
    }
}
