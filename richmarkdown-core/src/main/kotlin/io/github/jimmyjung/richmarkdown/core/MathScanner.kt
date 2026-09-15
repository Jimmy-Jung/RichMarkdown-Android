// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

/**
 * 원문에서 수식 span을 찾는다. iOS DEVELOPMENT.md §3.
 *
 * - 수식은 1차 Markdown AST가 아니라 원문 전체에서 탐색한다. `\(a * b\)`, `\(x_[i]\)`처럼
 *   Markdown 기호가 든 LaTeX를 노드 분할과 무관하게 보호하기 위함이다.
 * - code/HTML 전체 범위는 hard barrier: 내부 delimiter를 절대 수식으로 보지 않고
 *   경계를 가로지르는 매칭도 없다.
 * - link/image 전체 범위는 soft range: 수식 span이 완전히 포함하면 수식이 이기고,
 *   delimiter가 range 안에 있으면(부분 겹침 포함) 수식이 아니다.
 *   → `[\(x\)](url)`은 보호되고 `\([a](b)\)`는 수식이다.
 * - block 수식은 1차 AST paragraph 전체 기준으로 판정하고, 나머지 허용 범위에서 inline을 찾는다.
 *
 * 위치 단위는 UTF-16 code unit이다 (iOS는 UTF-8 byte). 규칙은 동일하다.
 */
@InternalRichMarkdownApi
public class MathScanner(
    private val text: String,
    forbiddenRanges: List<Utf16Range>,
    softRanges: List<Utf16Range> = emptyList(),
    private val paragraphRanges: List<Utf16Range>,
    private val dollarMath: DollarMathOptions,
) {
    public constructor(
        text: String,
        forbiddenRanges: List<Utf16Range>,
        softRanges: List<Utf16Range> = emptyList(),
        paragraphRanges: List<Utf16Range>,
        parsesDollarMath: Boolean,
    ) : this(
        text = text,
        forbiddenRanges = forbiddenRanges,
        softRanges = softRanges,
        paragraphRanges = paragraphRanges,
        dollarMath = DollarMathOptions.fromParsesDollarMath(parsesDollarMath),
    )

    public data class Result(
        val spans: List<ProtectedMathSpan>,
        val diagnostics: List<MathDiagnostic>,
    )

    private val chars: CharArray = text.toCharArray()

    /** 정렬·병합된 hard barrier (code/HTML 전체 범위). */
    private val hardRanges: List<Utf16Range> = Utf16Range.merged(forbiddenRanges)

    /** 정렬·병합된 soft range (link/image 전체 범위). */
    private val softRanges: List<Utf16Range> = Utf16Range.merged(softRanges)

    public fun scan(): Result {
        val spans = ArrayList<ProtectedMathSpan>()
        val diagnostics = ArrayList<MathDiagnostic>()

        // 1) block 수식: 공백 제외 paragraph 전체가 \[...\] 또는 opt-in $$...$$
        for (paragraph in paragraphRanges) {
            if (intersectsHard(paragraph)) continue
            blockSpan(paragraph, diagnostics)?.let(spans::add)
        }

        // 2) inline 수식: hard barrier와 block span을 배리어로 두고 허용 구간에서 탐색.
        val barriers = Utf16Range.merged(hardRanges + spans.map { it.originalRange })
        for (segment in allowedSegments(barriers)) {
            scanInline(segment, spans, diagnostics)
        }

        spans.sortBy { it.originalRange.start }
        return Result(spans, diagnostics)
    }

    // MARK: - Block math

    private fun blockSpan(paragraph: Utf16Range, diagnostics: MutableList<MathDiagnostic>): ProtectedMathSpan? {
        val trimmed = trimWhitespace(paragraph)
        if (trimmed.length < 4) return null
        val s = trimmed.start
        val e = trimmed.end

        // \[ ... \]
        if (chars[s] == '\\' && chars[s + 1] == '[' && !isBackslashEscaped(s)) {
            if (!(e - s >= 4 && chars[e - 2] == '\\' && chars[e - 1] == ']' && !isBackslashEscaped(e - 2))) {
                diagnostics.add(MathDiagnostic(MathDiagnostic.Kind.UnterminatedDelimiter, trimmed))
                return null
            }
            // 내부에 조기 닫힘 \] 이 있으면 paragraph 전체 wrap이 아니다.
            if (firstUnescapedDelimiter('\\', ']', Utf16Range(s + 2, e - 2)) != null) {
                diagnostics.add(MathDiagnostic(MathDiagnostic.Kind.NestedDelimiter, trimmed))
                return null
            }
            val inner = trimWhitespace(Utf16Range(s + 2, e - 2))
            if (inner.isEmpty) {
                diagnostics.add(MathDiagnostic(MathDiagnostic.Kind.EmptyMath, trimmed))
                return null
            }
            if (!softRangesAllow(span = trimmed, content = Utf16Range(s + 2, e - 2))) return null
            return makeSpan(trimmed, MathKind.DisplayBracket)
        }

        // $$ ... $$ (opt-in). $$는 $보다 먼저 판정한다.
        if (!dollarMath.isEmpty && chars[s] == '$' && chars[s + 1] == '$' && !isBackslashEscaped(s)) {
            if (!(e - s >= 5 && chars[e - 2] == '$' && chars[e - 1] == '$' && !isBackslashEscaped(e - 2))) {
                diagnostics.add(MathDiagnostic(MathDiagnostic.Kind.UnterminatedDelimiter, trimmed))
                return null
            }
            if (firstDollarDollar(Utf16Range(s + 2, e - 2)) != null) {
                diagnostics.add(MathDiagnostic(MathDiagnostic.Kind.NestedDelimiter, trimmed))
                return null
            }
            val inner = trimWhitespace(Utf16Range(s + 2, e - 2))
            if (inner.isEmpty) {
                diagnostics.add(MathDiagnostic(MathDiagnostic.Kind.EmptyMath, trimmed))
                return null
            }
            if (!softRangesAllow(span = trimmed, content = Utf16Range(s + 2, e - 2))) return null
            return makeSpan(trimmed, MathKind.DisplayDollar)
        }

        return null
    }

    // MARK: - Inline math

    private fun scanInline(
        segment: Utf16Range,
        spans: MutableList<ProtectedMathSpan>,
        diagnostics: MutableList<MathDiagnostic>,
    ) {
        var i = segment.start
        while (i < segment.end) {
            val c = chars[i]
            if (c == '\\' && i + 1 < segment.end && chars[i + 1] == '(' && !isBackslashEscaped(i)) {
                val (span, nextIndex) = inlineParenSpan(i, segment, diagnostics)
                if (span != null) {
                    spans.add(span)
                    i = span.originalRange.end
                } else {
                    i = nextIndex
                }
                continue
            }
            if (c == '\\' && i + 1 < segment.end && chars[i + 1] == '[' && !isBackslashEscaped(i)) {
                // ponytail: paragraph 전체가 아닌 \[...\]는 v1에서 plain text + diagnostic.
                // 필요가 측정되면 inline display로 승격한다.
                diagnostics.add(
                    MathDiagnostic(
                        MathDiagnostic.Kind.NonParagraphDisplayDelimiter,
                        Utf16Range(i, minOf(i + 2, segment.end)),
                    ),
                )
                i += 2
                continue
            }
            if (!dollarMath.isEmpty && c == '$' && !isBackslashEscaped(i)) {
                // $$를 $보다 먼저 판정한다. inline 위치의 $$는 InlineDouble일 때만 수식이다.
                if (i + 1 < segment.end && chars[i + 1] == '$') {
                    if (DollarMathOptions.InlineDouble in dollarMath) {
                        val span = inlineDoubleDollarSpan(i, segment)
                        if (span != null) {
                            spans.add(span)
                            i = span.originalRange.end
                            continue
                        }
                    }
                    i += 2
                    continue
                }
                if (DollarMathOptions.Single in dollarMath) {
                    val span = inlineDollarSpan(i, segment)
                    if (span != null) {
                        spans.add(span)
                        i = span.originalRange.end
                        continue
                    }
                }
                i += 1
                continue
            }
            i += 1
        }
    }

    /**
     * `\( ... \)` — 한 logical line의 inline 수식.
     *
     * 수식이 아닐 때는 다음 스캔 위치도 함께 돌려준다. 중첩·빈 구분자는 닫는 delimiter 뒤로
     * 건너뛰어야 구간 전체가 plain text로 남는다. 미완성 구분자는 여는 delimiter 뒤부터
     * 계속 탐색해 같은 줄의 다른 후보를 놓치지 않는다.
     */
    private fun inlineParenSpan(
        open: Int,
        segment: Utf16Range,
        diagnostics: MutableList<MathDiagnostic>,
    ): Pair<ProtectedMathSpan?, Int> {
        // 여는 delimiter가 link/image 내부에 있으면 수식이 아니다.
        if (insideSoftRange(open)) return null to open + 2
        val contentStart = open + 2
        val lineEnd = endOfLine(contentStart, segment.end)
        val close = firstUnescapedDelimiter('\\', ')', Utf16Range(contentStart, lineEnd))
        if (close == null) {
            diagnostics.add(MathDiagnostic(MathDiagnostic.Kind.UnterminatedDelimiter, Utf16Range(open, lineEnd)))
            return null to open + 2
        }
        val end = close + 2
        // 중첩 opener는 구간 전체가 plain text + diagnostic이다.
        if (firstUnescapedDelimiter('\\', '(', Utf16Range(contentStart, close)) != null) {
            diagnostics.add(MathDiagnostic(MathDiagnostic.Kind.NestedDelimiter, Utf16Range(open, end)))
            return null to end
        }
        val inner = trimWhitespace(Utf16Range(contentStart, close))
        if (inner.isEmpty) {
            diagnostics.add(MathDiagnostic(MathDiagnostic.Kind.EmptyMath, Utf16Range(open, end)))
            return null to end
        }
        if (!softRangesAllow(span = Utf16Range(open, end), content = Utf16Range(contentStart, close))) {
            return null to end
        }
        return makeSpan(Utf16Range(open, end), MathKind.InlineParen) to end
    }

    /**
     * `$ ... $` — v1 자체 규칙 (iOS DEVELOPMENT.md §3):
     * 여는 `$` 바로 뒤/닫는 `$` 바로 앞 공백 금지, 닫는 `$` 뒤 숫자 금지, 줄바꿈 금지.
     */
    private fun inlineDollarSpan(open: Int, segment: Utf16Range): ProtectedMathSpan? {
        if (insideSoftRange(open)) return null
        val contentStart = open + 1
        val lineEnd = endOfLine(contentStart, segment.end)
        if (!(contentStart < lineEnd && !isSpaceOrTab(chars[contentStart]))) return null

        var candidate = contentStart
        while (true) {
            val close = firstUnescapedDelimiter(null, '$', Utf16Range(candidate, lineEnd)) ?: return null
            // 닫는 후보가 $$의 일부이면 무효.
            if (close + 1 < lineEnd && chars[close + 1] == '$') {
                candidate = close + 2
                continue
            }
            val validBefore = close > contentStart && !isSpaceOrTab(chars[close - 1])
            val validAfter = close + 1 >= lineEnd || !isAsciiDigit(chars[close + 1])
            if (validBefore && validAfter) {
                if (!softRangesAllow(span = Utf16Range(open, close + 1), content = Utf16Range(contentStart, close))) {
                    candidate = close + 1
                    continue
                }
                return makeSpan(Utf16Range(open, close + 1), MathKind.InlineDollar)
            }
            candidate = close + 1
        }
    }

    /**
     * `$$ ... $$` — 문장 안 inline 수식(opt-in InlineDouble). `$...$`와 같은 공백·숫자·줄바꿈 규칙.
     * paragraph 전체를 감싼 `$$`는 block 단계에서 먼저 잡히므로 여기 오지 않는다.
     */
    private fun inlineDoubleDollarSpan(open: Int, segment: Utf16Range): ProtectedMathSpan? {
        if (insideSoftRange(open)) return null
        val contentStart = open + 2
        val lineEnd = endOfLine(contentStart, segment.end)
        if (!(contentStart < lineEnd && !isSpaceOrTab(chars[contentStart]))) return null

        var candidate = contentStart
        while (true) {
            val close = firstDollarDollar(Utf16Range(candidate, lineEnd)) ?: return null
            val end = close + 2
            val validBefore = close > contentStart && !isSpaceOrTab(chars[close - 1])
            val validAfter = end >= lineEnd || !isAsciiDigit(chars[end])
            if (validBefore && validAfter) {
                if (!softRangesAllow(span = Utf16Range(open, end), content = Utf16Range(contentStart, close))) {
                    candidate = end
                    continue
                }
                return makeSpan(Utf16Range(open, end), MathKind.InlineDoubleDollar)
            }
            candidate = close + 1
        }
    }

    // MARK: - Soft range 규칙

    /** span과 겹치는 모든 soft range(link/image)가 content 안에 완전히 포함될 때만 수식이 성립한다. */
    private fun softRangesAllow(span: Utf16Range, content: Utf16Range): Boolean {
        for (soft in softRanges) {
            if (!soft.overlaps(span)) continue
            if (!(soft.start >= content.start && soft.end <= content.end)) return false
        }
        return true
    }

    private fun insideSoftRange(index: Int): Boolean = softRanges.any { index in it }

    // MARK: - Char helpers

    private fun makeSpan(range: Utf16Range, kind: MathKind): ProtectedMathSpan =
        ProtectedMathSpan(range, kind, text.substring(range.start, range.end))

    /** index 위치 바로 앞의 연속 backslash 수가 홀수면 escape된 것이다. */
    private fun isBackslashEscaped(index: Int): Boolean {
        var count = 0
        var i = index - 1
        while (i >= 0 && chars[i] == '\\') {
            count += 1
            i -= 1
        }
        return count % 2 == 1
    }

    /**
     * `prefix`가 null이면 단일 문자 delimiter, 있으면 2문자 delimiter(prefix+char)를 찾는다.
     * escape된 delimiter는 건너뛴다. 반환값은 delimiter 시작 index.
     */
    private fun firstUnescapedDelimiter(prefix: Char?, char: Char, range: Utf16Range): Int? {
        var i = range.start
        while (i < range.end) {
            if (prefix != null) {
                if (chars[i] == prefix && i + 1 < range.end && chars[i + 1] == char && !isBackslashEscaped(i)) {
                    return i
                }
            } else if (chars[i] == char && !isBackslashEscaped(i)) {
                return i
            }
            i += 1
        }
        return null
    }

    private fun firstDollarDollar(range: Utf16Range): Int? {
        var i = range.start
        while (i + 1 < range.end) {
            if (chars[i] == '$' && chars[i + 1] == '$' && !isBackslashEscaped(i)) return i
            i += 1
        }
        return null
    }

    private fun endOfLine(start: Int, limit: Int): Int {
        var i = start
        while (i < limit) {
            val c = chars[i]
            if (c == '\n' || c == '\r') return i
            i += 1
        }
        return limit
    }

    private fun trimWhitespace(range: Utf16Range): Utf16Range {
        var s = range.start
        var e = range.end
        while (s < e && isWhitespace(chars[s])) s += 1
        while (e > s && isWhitespace(chars[e - 1])) e -= 1
        return Utf16Range(s, e)
    }

    private fun isWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'
    private fun isSpaceOrTab(c: Char): Boolean = c == ' ' || c == '\t'
    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    private fun intersectsHard(range: Utf16Range): Boolean = hardRanges.any { it.overlaps(range) }

    private fun allowedSegments(barriers: List<Utf16Range>): List<Utf16Range> {
        val segments = ArrayList<Utf16Range>()
        var cursor = 0
        for (barrier in barriers) {
            if (cursor < barrier.start) segments.add(Utf16Range(cursor, barrier.start))
            cursor = maxOf(cursor, barrier.end)
        }
        if (cursor < chars.size) segments.add(Utf16Range(cursor, chars.size))
        return segments
    }
}
