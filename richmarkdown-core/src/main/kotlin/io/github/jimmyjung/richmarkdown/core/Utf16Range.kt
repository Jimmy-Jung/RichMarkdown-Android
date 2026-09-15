// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

/**
 * 원문 `String`의 UTF-16 code unit 기준 half-open 범위 `[start, end)`.
 *
 * iOS 코어는 swift-markdown이 UTF-8 byte 위치를 주므로 byte 범위를 쓴다. Kotlin `String`과
 * commonmark-java의 `SourceSpan.inputIndex`는 UTF-16 code unit이므로 여기서는 이 단위로 통일한다.
 * mask(`MathProtector`)가 길이를 보존해야 한다는 계약은 단위만 다르고 같다.
 */
@InternalRichMarkdownApi
public data class Utf16Range(val start: Int, val end: Int) : Comparable<Utf16Range> {
    init {
        require(start in 0..end) { "잘못된 범위: [$start, $end)" }
    }

    public val length: Int get() = end - start
    public val isEmpty: Boolean get() = start == end

    public operator fun contains(index: Int): Boolean = index >= start && index < end

    /** `other`가 이 범위 안에 완전히 들어가는가. */
    public fun contains(other: Utf16Range): Boolean = other.start >= start && other.end <= end

    /** 공통 원소가 하나라도 있는가. 빈 범위는 어느 범위와도 겹치지 않는다 (Swift `Range.overlaps` 동일). */
    public fun overlaps(other: Utf16Range): Boolean = start < other.end && other.start < end

    override fun compareTo(other: Utf16Range): Int = start.compareTo(other.start)

    public companion object {
        /** 시작 위치로 정렬하고 겹치거나 맞닿은 범위를 병합한다. */
        public fun merged(ranges: List<Utf16Range>): List<Utf16Range> {
            val sorted = ranges.sortedBy { it.start }
            val result = ArrayList<Utf16Range>(sorted.size)
            for (range in sorted) {
                val last = result.lastOrNull()
                if (last != null && range.start <= last.end) {
                    result[result.lastIndex] = Utf16Range(last.start, maxOf(last.end, range.end))
                } else {
                    result.add(range)
                }
            }
            return result
        }
    }
}
