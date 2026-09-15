// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.view

import io.github.jimmyjung.richmarkdown.RichMarkdownStreamingOptions
import io.github.jimmyjung.richmarkdown.core.DollarMathOptions
import io.github.jimmyjung.richmarkdown.core.InlineRun
import io.github.jimmyjung.richmarkdown.core.StreamingTail
import kotlin.math.roundToInt

/**
 * 스트리밍 tail 텍스트 블록의 표시 전용 변환 (iOS `RichMarkdownUIView.configure(_:runs:…tail:)` 앞부분).
 *
 * 파싱 결과는 바꾸지 않는다. tail이 아니면(`tail == null`) 원본 run을 그대로 돌려준다.
 * 표·코드·수식 블록은 호출자가 tail을 넘기지 않으므로 페이드가 없다.
 */
internal object StreamingDisplay {
    class Plan(
        /** 원래 색으로 그리는 앞부분. */
        val head: List<InlineRun>,
        /** 끝에서부터 alpha를 낮추는 grapheme 조각. */
        val tail: List<StreamingTail.Piece>,
    )

    fun plan(runs: List<InlineRun>, tail: RichMarkdownStreamingOptions?, dollarMath: DollarMathOptions): Plan {
        val displayRuns = if (tail?.hidesUnclosedInlineMarks == true) {
            StreamingTail.hidingUnclosedOpeners(runs, dollarMath)
        } else {
            runs
        }
        val fade = StreamingTail.fadePlan(displayRuns, tail?.tailFadeGraphemeCount ?: 0)
        return Plan(fade.head, fade.tail)
    }

    /** ARGB 색의 alpha만 낮춘다 (iOS `UIColor.withAlphaComponent`). */
    fun withAlpha(argb: Int, alpha: Double): Int {
        val a = (alpha.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (argb and 0x00FFFFFF) or (a shl 24)
    }
}
