// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import io.github.jimmyjung.richmarkdown.RichMarkdownStreamingOptions
import io.github.jimmyjung.richmarkdown.core.DollarMathOptions
import io.github.jimmyjung.richmarkdown.core.InlineRun
import io.github.jimmyjung.richmarkdown.core.StreamingTail

/**
 * 마지막 리프 문단·헤딩에만 전달되는 스트리밍 표시 문맥. iOS `RichMarkdownStreamingTailContext`.
 *
 * 컨테이너(인용·리스트)는 마지막 자식에게만 넘기고, 표·코드·수식 블록은 소비하지 않는다 —
 * 그 블록이 마지막이면 페이드가 없다 (iOS 알려진 제약). `dollarMath`는 `$`·`$$` opener 판정에 쓴다.
 * 파싱 결과·렌더 요청은 바꾸지 않는다.
 */
internal class StreamingTailContext(
    val options: RichMarkdownStreamingOptions,
    private val dollarMath: DollarMathOptions,
) {
    /** 표시용 run. 미닫힌 opener를 숨긴 결과이며 칩 판정·접근성 라벨도 이 run을 쓴다 (iOS `displayRuns`). */
    fun displayRuns(runs: List<InlineRun>): List<InlineRun> =
        if (options.hidesUnclosedInlineMarks) StreamingTail.hidingUnclosedOpeners(runs, dollarMath) else runs
}

/**
 * 표시 run을 그대로 그릴 `head`와 grapheme마다 alpha가 다른 `tail`로 나눈다.
 * 기본 12 grapheme, 마지막 alpha 0.2 (`StreamingTail.MINIMUM_ALPHA`). 0이면 전부 head다.
 */
internal fun fadePlan(runs: List<InlineRun>, graphemeCount: Int): StreamingTail.FadePlan =
    StreamingTail.fadePlan(runs, graphemeCount)

/** 꼬리 조각 span: 원 run의 굵게·기울임·취소선을 유지하고 색만 alpha를 낮춘다 (iOS `base(_, alpha:)`). */
internal fun fadedStyle(piece: StreamingTail.Piece, textColor: Color): SpanStyle =
    emphasisStyle(piece.run).copy(color = textColor.copy(alpha = textColor.alpha * piece.alpha.toFloat()))
