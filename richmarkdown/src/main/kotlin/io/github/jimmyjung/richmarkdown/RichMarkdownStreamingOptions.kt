// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

/**
 * 스트리밍 중인 문서의 **표시** 옵션. 파싱 결과·렌더 요청은 바꾸지 않는다.
 * `null`이면 스트리밍이 아니며 일반 렌더와 같다. iOS `RichMarkdownStreamingOptions`.
 */
class RichMarkdownStreamingOptions(
    tailFadeGraphemeCount: Int = 12,
    /** tail 문단의 미닫힌 `**`·백틱·`\(`·`$` opener를 closer가 도착할 때까지 숨긴다. */
    val hidesUnclosedInlineMarks: Boolean = true,
) {
    /** tail 문단 끝에서 alpha를 낮출 grapheme 수. 0이면 페이드 없음. 음수는 0으로 누른다 (iOS `max(0, _)`). */
    val tailFadeGraphemeCount: Int = tailFadeGraphemeCount.coerceAtLeast(0)

    fun copy(
        tailFadeGraphemeCount: Int = this.tailFadeGraphemeCount,
        hidesUnclosedInlineMarks: Boolean = this.hidesUnclosedInlineMarks,
    ): RichMarkdownStreamingOptions = RichMarkdownStreamingOptions(tailFadeGraphemeCount, hidesUnclosedInlineMarks)

    override fun equals(other: Any?): Boolean =
        other is RichMarkdownStreamingOptions &&
            tailFadeGraphemeCount == other.tailFadeGraphemeCount &&
            hidesUnclosedInlineMarks == other.hidesUnclosedInlineMarks

    override fun hashCode(): Int = 31 * tailFadeGraphemeCount + hidesUnclosedInlineMarks.hashCode()

    override fun toString(): String =
        "RichMarkdownStreamingOptions(tailFadeGraphemeCount=$tailFadeGraphemeCount, hidesUnclosedInlineMarks=$hidesUnclosedInlineMarks)"

    companion object {
        val Default: RichMarkdownStreamingOptions = RichMarkdownStreamingOptions()
    }
}
