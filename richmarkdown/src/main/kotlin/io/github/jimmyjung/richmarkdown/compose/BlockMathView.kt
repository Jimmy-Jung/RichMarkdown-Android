// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import io.github.jimmyjung.richmarkdown.LatexEquationAlignment
import io.github.jimmyjung.richmarkdown.MathRenderKey
import io.github.jimmyjung.richmarkdown.MathRenderService
import io.github.jimmyjung.richmarkdown.MathVectorLayout
import io.github.jimmyjung.richmarkdown.core.MathSegment

/** iOS `BlockMathView`: 수식과 복사 버튼 사이 `HStack(spacing: 8)`. */
private val BLOCK_MATH_SPACING = 8.dp

/**
 * 블록 수식. raster가 아니라 `MathRenderService.layout` 벡터를 Canvas에 직접 그린다
 * (iOS UIKit `BlockMathVectorView`; 그래서 Compose 요청은 `rastersDisplayMath = false`).
 * 레이아웃 전·실패는 원문 source를 `codeFont`로 표시한다 (계약 §7).
 */
@Composable
internal fun BlockMathView(segment: MathSegment, ctx: RenderContext) {
    val key = MathRenderKey(
        latex = segment.latex,
        mathFont = ctx.theme.mathFont,
        fontSizePx = ctx.mathFontSizePx,
        colorArgb = ctx.colorArgb,
        isDisplay = true,
    )
    // key(원문·색·크기)가 바뀌면 null로 되돌아가 stale 벡터를 섞지 않는다.
    val layout by produceState<MathVectorLayout?>(initialValue = null, key) {
        value = MathRenderService.shared.layout(key)
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BLOCK_MATH_SPACING),
        verticalAlignment = Alignment.Top,
    ) {
        val current = layout
        if (current != null) {
            MathVectorCanvas(current, segment.latex, ctx, Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                BasicText(segment.source, style = ctx.styles.code)
            }
        }
        CopyButton(segment.source, contentDescription = "수식 원문 복사", tint = ctx.styles.textColor)
    }
}

/**
 * 정렬은 콘텐츠가 뷰포트보다 좁을 때만 의미가 있다. `BoxWithConstraints`로 뷰포트 폭을 얻어
 * 최소 폭으로 채우고, 넓으면 가로 스크롤한다 (iOS `GeometryReader` + `ScrollView(.horizontal)`).
 */
@Composable
private fun MathVectorCanvas(layout: MathVectorLayout, latex: String, ctx: RenderContext, modifier: Modifier) {
    val alignment = when (ctx.theme.equationAlignment) {
        LatexEquationAlignment.Leading -> Alignment.CenterStart
        LatexEquationAlignment.Center -> Alignment.Center
        LatexEquationAlignment.Trailing -> Alignment.CenterEnd
    }
    val density = LocalDensity.current
    val width = with(density) { layout.widthPx.toDp() }
    val height = with(density) { (layout.ascentPx + layout.descentPx).toDp() }
    val description = "수식: $latex"
    BoxWithConstraints(modifier) {
        val viewport = maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(
                if (viewport.isFinite) Modifier.widthIn(min = viewport) else Modifier,
                contentAlignment = alignment,
            ) {
                Canvas(Modifier.size(width, height).semantics { contentDescription = description }) {
                    // origin = bounding box 좌상단 (계약 §1). Compose Canvas 원점과 같다.
                    drawIntoCanvas { layout.draw(it.nativeCanvas) }
                }
            }
        }
    }
}
