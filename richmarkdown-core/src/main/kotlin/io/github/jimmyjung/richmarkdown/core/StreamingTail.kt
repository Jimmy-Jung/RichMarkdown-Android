// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

import java.text.BreakIterator
import java.util.Locale

/**
 * 스트리밍 중 tail 문단(마지막 리프 문단·헤딩)의 **표시 전용** 변환. iOS `StreamingTail`과 1:1.
 *
 * 파싱 결과는 바꾸지 않는다. 렌더러가 표시 직전에 마지막 `Text` run만 손본다.
 * - 미닫힌 opener 억제: 파서가 짝 없는 `**`·백틱·`\(`를 literal로 내보내므로 closer가
 *   도착하기 전까지 그 토큰만 숨긴다. closer가 오면 재파싱이 Strong·code·math로 바꾼다.
 * - 꼬리 페이드: 마지막 N grapheme의 alpha를 끝으로 갈수록 낮춰 도착 위치를 보인다.
 */
@InternalRichMarkdownApi
public object StreamingTail {
    public data class Piece(val run: InlineRun, val text: String, val alpha: Double)

    public data class FadePlan(
        /** 그대로 렌더하는 앞부분. */
        val head: List<InlineRun>,
        /** 끝에서부터 alpha를 적용하는 grapheme 조각. 앞→뒤 순서. */
        val tail: List<Piece>,
    )

    /** 마지막 grapheme은 텍스트 길이와 무관하게 항상 이 값이라 tick마다 흔들리지 않는다. */
    internal const val MINIMUM_ALPHA: Double = 0.2

    // MARK: - 미닫힌 opener 억제

    public fun hidingUnclosedOpeners(runs: List<InlineRun>, parsesDollarMath: Boolean): List<InlineRun> =
        hidingUnclosedOpeners(runs, DollarMathOptions.fromParsesDollarMath(parsesDollarMath))

    /**
     * 마지막 `Text` run에서 짝 없는 opener 토큰을 모두 표시에서 지운다.
     * 문단 전체가 비게 되면 지우지 않는다(높이 0으로 튀는 프레임 방지).
     */
    public fun hidingUnclosedOpeners(runs: List<InlineRun>, dollarMath: DollarMathOptions): List<InlineRun> {
        val last = runs.lastOrNull() ?: return runs
        val text = (last.content as? InlineContent.Text)?.text ?: return runs
        val stripped = strippingUnclosedOpeners(text, dollarMath)
        if (stripped == text) return runs

        return if (stripped.isEmpty()) {
            if (runs.size <= 1) runs else runs.dropLast(1)
        } else {
            runs.dropLast(1) + last.copy(content = InlineContent.Text(stripped))
        }
    }

    internal fun strippingUnclosedOpeners(string: String, parsesDollarMath: Boolean): String =
        strippingUnclosedOpeners(string, DollarMathOptions.fromParsesDollarMath(parsesDollarMath))

    /**
     * 한 literal text run 안의 opener 판정. 같은 run 안에서 짝지을 수 있었던 토큰은 파서가 이미
     * Strong·code·math로 바꿨으므로 남은 opener는 대부분 미매칭이다. 예외(`\(\)`, `$5 and $10`)만
     * "뒤에 closer 없음" 검사로 걸러낸다.
     */
    internal fun strippingUnclosedOpeners(string: String, dollarMath: DollarMathOptions): String {
        val chars = string.graphemes()
        val kept = ArrayList<String>(chars.size)
        var index = 0
        // closer 존재 여부는 마지막 위치 사전계산으로 O(1)에 판정한다. tail 문단은 tick마다
        // 전체를 다시 스캔하므로 `$`·백틱이 많은 문단에서 O(n²)로 미끄러지지 않게 한다.
        val lastDollar = chars.lastIndexOf("$").takeIf { it >= 0 }
        val lastDoubleDollar = lastStart(listOf("$", "$"), chars)
        val lastCloseParen = lastStart(listOf("\\", ")"), chars)
        val lastBacktickRunStart = lastBacktickRunStarts(chars)

        fun characterAt(offset: Int): String? = chars.getOrNull(offset)

        fun isLeftFlanking(end: Int): Boolean {
            val next = characterAt(end) ?: return true
            return !next.isWhitespaceGrapheme()
        }

        while (index < chars.size) {
            val current = chars[index]

            if (current == "\\") {
                val next = characterAt(index + 1)
                if (next == null) {
                    // 문단 끝에 홀로 남은 백슬래시는 `\(` 같은 토큰의 앞 절반이다.
                    index += 1
                    continue
                }
                if (next == "(" && !(lastCloseParen?.let { it >= index + 2 } ?: false)) {
                    index += 2
                    continue
                }
                // `\$`·`\*`·`\\` 같은 이스케이프는 그대로 둔다.
                kept.add(current)
                kept.add(next)
                index += 2
                continue
            }

            if (current == "*") {
                val runLength = repeatCount("*", chars, index)
                val end = index + runLength
                val previousIsDigit = kept.lastOrNull()?.isNumberGrapheme() == true
                val nextIsDigit = characterAt(end)?.isNumberGrapheme() == true
                if (runLength <= 3 && isLeftFlanking(end) && !(previousIsDigit && nextIsDigit)) {
                    index = end
                    continue
                }
                kept.addAll(chars.subList(index, end))
                index = end
                continue
            }

            if (current == "~" && characterAt(index + 1) == "~") {
                val end = index + 2
                if (isLeftFlanking(end)) {
                    index = end
                    continue
                }
                kept.addAll(chars.subList(index, end))
                index = end
                continue
            }

            if (current == "`") {
                val runLength = repeatCount("`", chars, index)
                val end = index + runLength
                if (!(lastBacktickRunStart[runLength]?.let { it >= end } ?: false)) {
                    index = end
                    continue
                }
                kept.addAll(chars.subList(index, end))
                index = end
                continue
            }

            if (current == "$" && !dollarMath.isEmpty) {
                // `$$` opener는 `InlineDouble`일 때만 미닫힌 마크다. 그 외 `$$`는 그대로 둔다.
                if (characterAt(index + 1) == "$") {
                    if (DollarMathOptions.InlineDouble in dollarMath) {
                        val after = characterAt(index + 2)
                        val opensMath = after?.let {
                            !it.isWhitespaceGrapheme() && !it.isNumberGrapheme() && it != "$"
                        } ?: true
                        val hasLaterDoubleDollar = lastDoubleDollar?.let { it >= index + 2 } ?: false
                        if (opensMath && !hasLaterDoubleDollar) {
                            index += 2
                            continue
                        }
                    }
                    kept.addAll(chars.subList(index, index + 2))
                    index += 2
                    continue
                }
                if (DollarMathOptions.Single in dollarMath) {
                    val next = characterAt(index + 1)
                    val opensMath = next?.let { !it.isWhitespaceGrapheme() && !it.isNumberGrapheme() } ?: true
                    val hasLaterDollar = lastDollar?.let { it > index } ?: false
                    if (opensMath && !hasLaterDollar) {
                        index += 1
                        continue
                    }
                }
                kept.add(current)
                index += 1
                continue
            }

            kept.add(current)
            index += 1
        }
        return kept.joinToString("")
    }

    private fun repeatCount(grapheme: String, chars: List<String>, start: Int): Int {
        var end = start
        while (end < chars.size && chars[end] == grapheme) end += 1
        return end - start
    }

    /** `sequence`가 마지막으로 시작하는 위치. */
    private fun lastStart(sequence: List<String>, chars: List<String>): Int? {
        if (sequence.size > chars.size) return null
        var index = chars.size - sequence.size
        while (index >= 0) {
            if (chars.subList(index, index + sequence.size) == sequence) return index
            index -= 1
        }
        return null
    }

    /** 백틱 연속 길이별 마지막 시작 위치. 정확히 같은 길이의 연속이 뒤에 있으면 code span이 닫힌 것이다. */
    private fun lastBacktickRunStarts(chars: List<String>): Map<Int, Int> {
        val starts = HashMap<Int, Int>()
        var index = 0
        while (index < chars.size) {
            if (chars[index] == "`") {
                val run = repeatCount("`", chars, index)
                starts[run] = index
                index += run
            } else {
                index += 1
            }
        }
        return starts
    }

    // MARK: - 꼬리 페이드

    /**
     * 끝에서부터 연속한 `Text` run을 grapheme 조각으로 나눈다.
     * break·math·code·link를 만나면 멈춘다 — 커서가 그 뒤에 있지 않다.
     */
    public fun fadePlan(runs: List<InlineRun>, graphemeCount: Int): FadePlan {
        if (graphemeCount <= 0) return FadePlan(head = runs, tail = emptyList())

        val head = runs.toMutableList()
        val tail = ArrayList<Piece>()
        var distanceFromEnd = 0

        while (distanceFromEnd < graphemeCount) {
            val last = head.lastOrNull() ?: break
            val text = (last.content as? InlineContent.Text)?.text ?: break
            val graphemes = text.graphemes()
            val budget = graphemeCount - distanceFromEnd
            val faded = graphemes.takeLast(budget)
            val remaining = graphemes.dropLast(faded.size)

            val pieces = faded.mapIndexed { offset, grapheme ->
                // faded의 마지막 원소가 끝에서 거리 distanceFromEnd다.
                val distance = distanceFromEnd + (faded.size - 1 - offset)
                Piece(run = last, text = grapheme, alpha = alpha(distance, graphemeCount))
            }
            tail.addAll(0, pieces)
            distanceFromEnd += faded.size

            head.removeAt(head.lastIndex)
            if (remaining.isNotEmpty()) {
                head.add(last.copy(content = InlineContent.Text(remaining.joinToString(""))))
                break
            }
        }

        return FadePlan(head = head, tail = tail)
    }

    /**
     * `distance` = 끝에서부터 grapheme 거리(0 = 마지막). N 이상이면 원래 색.
     * 마지막 grapheme이 정확히 [MINIMUM_ALPHA]가 되도록 최소값에서 더해 올린다(부동소수 오차 방지).
     */
    internal fun alpha(distance: Int, count: Int): Double {
        if (distance >= count) return 1.0
        return MINIMUM_ALPHA + (1 - MINIMUM_ALPHA) * distance.toDouble() / count.toDouble()
    }
}

// MARK: - grapheme helpers (Swift `Character` 대응)

/** extended grapheme cluster 단위로 나눈다. Swift `Array(string)`과 같은 단위다. */
private fun String.graphemes(): List<String> {
    if (isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(this)
    val result = ArrayList<String>(length)
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result.add(substring(start, end))
        start = end
        end = iterator.next()
    }
    return result
}

private fun String.isWhitespaceGrapheme(): Boolean {
    val codePoint = codePointAt(0)
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
}

private fun String.isNumberGrapheme(): Boolean =
    when (Character.getType(codePointAt(0))) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }
