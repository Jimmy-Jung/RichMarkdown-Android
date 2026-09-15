// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.view

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.jimmyjung.richmarkdown.RenderedMath
import io.github.jimmyjung.richmarkdown.RichMarkdownCodeBlockOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownStreamingOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownTheme
import io.github.jimmyjung.richmarkdown.core.InlineContent
import io.github.jimmyjung.richmarkdown.core.InlineRun
import io.github.jimmyjung.richmarkdown.core.MathSegment
import io.github.jimmyjung.richmarkdown.core.ParsedBlock

/**
 * 블록 뷰 재사용 판정용 겉모습 식별자 (iOS `AppearanceKey`).
 *
 * `theme` 자체를 담는다 — 인용 바 색처럼 Request에 실리지 않는 필드는 파생 값만으로 구별되지 않는다.
 * `isDark`는 다크 모드 전환, `fontSizePx`는 fontScale·density 변화, `codeBlocks`는 하이라이터·다이어그램 교체를 잡는다.
 */
internal data class AppearanceKey(
    val theme: RichMarkdownTheme,
    val isDark: Boolean,
    val fontSizePx: Float,
    val codeBlocks: RichMarkdownCodeBlockOptions,
)

/**
 * 증분 rebuild (iOS `RichMarkdownUIView.rebuildBlocks` · `setBlockViews`).
 *
 * 스트리밍 입력은 append 중심이라 앞쪽 블록이 안정적이다. 값이 처음 달라지는 index부터 뒤쪽 전부를
 * 새로 만든다(suffix 교체). 중간 삽입 diff(LCS)는 복잡도 대비 이득이 없어 구현하지 않는다.
 */
internal class IncrementalRebuild(private val stack: LinearLayout) {
    private class RenderedBlock(
        val block: ParsedBlock,
        val view: View,
        /** 이 블록이 수식 이미지 사전에서 찾아 쓴 개수. hydration으로 바뀌면 다시 만든다. */
        val mathImageCount: Int,
        /** 이 블록에 적용된 스트리밍 tail 옵션. tail이 아니게 되거나 스트림이 끝나면 다시 그린다. */
        val tail: RichMarkdownStreamingOptions?,
    )

    private var renderedBlocks: List<RenderedBlock> = emptyList()
    private var renderedAppearance: AppearanceKey? = null

    /**
     * 원문 fallback 전용 재사용 뷰. 스트리밍은 markdown 갱신마다 fallback 게시(`document == null`)를 거치므로
     * 매 tick TextView를 만들고 버리지 않는다.
     */
    private var fallbackTextView: ChipTextView? = null

    fun renderBlocks(
        blocks: List<ParsedBlock>,
        images: Map<MathSegment, RenderedMath>,
        appearance: AppearanceKey,
        streaming: RichMarkdownStreamingOptions?,
        builder: BlockViewBuilder,
    ) {
        // 폰트·색·scale이 바뀌면 모든 블록의 텍스트가 달라진다.
        val reusable = if (appearance == renderedAppearance) renderedBlocks else emptyList()
        val rendered = ArrayList<RenderedBlock>(blocks.size)
        // 앞에서부터 뷰 identity가 바뀌지 않은 연속 길이. `setBlockViews`는 이 구간의 계층을 건드리지 않는다.
        var stableCount = 0
        var stablePrefix = true

        for ((index, block) in blocks.withIndex()) {
            val tail = if (index == blocks.lastIndex) streaming else null
            val mathCount = mathImageCount(block, images)
            val previous = reusable.getOrNull(index)
            if (previous != null) {
                if (previous.block == block && previous.mathImageCount == mathCount && previous.tail == tail) {
                    rendered.add(previous)
                    if (stablePrefix) stableCount += 1
                    continue
                }
                // 스트리밍 중에는 같은 종류의 블록을 새로 만들지 않고 내용만 바꾼다. 앞 블록이 모두 그대로인
                // 일반적인 tick에서는 계층 조작이 없어 읽던 문단의 TalkBack 포커스도 유지된다.
                if (streaming != null && builder.updateBlockInPlace(previous.view, previous.block, block, images, tail)) {
                    rendered.add(RenderedBlock(block, previous.view, mathCount, tail))
                    if (stablePrefix) stableCount += 1
                    continue
                }
            }
            stablePrefix = false
            rendered.add(RenderedBlock(block, builder.blockView(block, images, tail), mathCount, tail))
        }

        setBlockViews(rendered.map { it.view }, stableCount)
        renderedBlocks = rendered
        renderedAppearance = appearance
    }

    /**
     * 최신 원문 fallback 즉시 표시. `renderedBlocks`는 비우지 않는다 — 뷰 인스턴스를 살려 두고 계층에서만 떼어,
     * parse가 끝난 다음 게시에서 앞쪽 블록을 그대로 되돌린다.
     */
    fun renderFallback(markdown: String, builder: BlockViewBuilder) {
        val view = fallbackTextView ?: builder.textView().also { fallbackTextView = it }
        builder.configureFallback(view, markdown)
        // reusedCount 1: 직전 프레임도 fallback이었다면(연속 스트리밍) 계층 조작이 없다.
        setBlockViews(listOf(view), reusedCount = 1)
    }

    /**
     * 목표 배열로 `stack`을 맞춘다. 재사용 prefix가 이미 같은 순서로 실려 있으면 뒤쪽만 교체한다.
     * `addView`는 계층에서 떼어낸 뷰에만 호출한다.
     */
    private fun setBlockViews(views: List<View>, reusedCount: Int) {
        val attachedCount = stack.childCount
        val prefixIsAttached = attachedCount >= reusedCount &&
            (0 until reusedCount).all { stack.getChildAt(it) === views[it] }
        val kept = if (prefixIsAttached) reusedCount else 0

        for (index in attachedCount - 1 downTo kept) stack.removeViewAt(index)
        for (index in kept until views.size) {
            val view = views[index]
            (view.parent as? ViewGroup)?.removeView(view)
            stack.addView(view)
        }
    }
}

/** 이 블록이 수식 이미지 사전에서 실제로 찾아 쓰는 수식 중 준비된 개수 (iOS `mathImageCount`). */
internal fun mathImageCount(block: ParsedBlock, images: Map<MathSegment, RenderedMath>): Int = when (block) {
    is ParsedBlock.Paragraph -> mathImageCount(block.runs, images)
    is ParsedBlock.Heading -> mathImageCount(block.runs, images)
    // 블록 수식은 벡터 뷰로 그려 이미지 사전을 쓰지 않는다. 값이 같으면 hydration 게시에서도 무조건 재사용이다.
    is ParsedBlock.BlockMath -> 0
    is ParsedBlock.BlockQuote -> block.children.sumOf { mathImageCount(it, images) }
    is ParsedBlock.UnorderedList -> block.items.sumOf { item -> item.sumOf { mathImageCount(it, images) } }
    is ParsedBlock.OrderedList -> block.items.sumOf { item -> item.sumOf { mathImageCount(it, images) } }
    is ParsedBlock.Table -> (block.table.header + block.table.rows.flatten()).sumOf { mathImageCount(it, images) }
    is ParsedBlock.CodeBlock, ParsedBlock.ThematicBreak -> 0
}

private fun mathImageCount(runs: List<InlineRun>, images: Map<MathSegment, RenderedMath>): Int =
    runs.count { run -> (run.content as? InlineContent.Math)?.let { images.containsKey(it.segment) } == true }

internal fun containsMath(runs: List<InlineRun>): Boolean = runs.any { it.content is InlineContent.Math }

internal fun containsMath(block: ParsedBlock): Boolean = when (block) {
    is ParsedBlock.Paragraph -> containsMath(block.runs)
    is ParsedBlock.Heading -> containsMath(block.runs)
    is ParsedBlock.BlockMath -> true
    is ParsedBlock.BlockQuote -> block.children.any(::containsMath)
    is ParsedBlock.UnorderedList -> block.items.any { item -> item.any(::containsMath) }
    is ParsedBlock.OrderedList -> block.items.any { item -> item.any(::containsMath) }
    is ParsedBlock.Table -> (block.table.header + block.table.rows.flatten()).any(::containsMath)
    is ParsedBlock.CodeBlock, ParsedBlock.ThematicBreak -> false
}
