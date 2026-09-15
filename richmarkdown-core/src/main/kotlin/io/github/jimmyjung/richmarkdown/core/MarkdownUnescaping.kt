// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

/**
 * Markdown backslash escape 해제.
 *
 * 2차 파싱 결과의 Text 노드는 원문 slice로 되돌려 쓴다(수식 mask 복원 때문). 그래서 파서가
 * 해 주는 escape 해제가 사라지므로 span 밖 텍스트에만 직접 적용한다. 수식 span source는
 * 원래 구분자를 보존해야 하므로 건드리지 않는다.
 *
 * 수식 구분자 문자(`(`, `)`, `[`, `]`)는 해제하지 않는다. 수식으로 인식되지 않은 `\(x + y`에서
 * backslash를 벗기면 "잘못된 LaTeX는 원래 구분자를 포함한 source를 표시한다"는 계약이 깨진다.
 * `\\(`는 `\\` → `\` 접힘으로 의도한 리터럴 `\(`가 남는다.
 */
@InternalRichMarkdownApi
public fun String.unescapingMarkdownPunctuation(): String {
    if (indexOf('\\') < 0) return this
    val result = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '\\' && i + 1 < length && this[i + 1] in ESCAPABLE_PUNCTUATION) {
            result.append(this[i + 1])
            i += 2
            continue
        }
        result.append(c)
        i += 1
    }
    return result.toString()
}

/** CommonMark escapable ASCII punctuation에서 수식 구분자 `(`, `)`, `[`, `]`를 뺀 집합. */
private val ESCAPABLE_PUNCTUATION: Set<Char> = "!\"#$%&'*+,-./:;<=>?@\\^_`{|}~".toSet()
