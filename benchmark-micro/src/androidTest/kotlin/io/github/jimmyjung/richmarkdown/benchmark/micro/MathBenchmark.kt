// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.benchmark.micro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jimmyjung.richmarkdown.MathRenderService
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * RaTeX parse·draw, 48 px. `MathRenderService.newRenderer`/`renderUncached`가 부르는 io.ratex 호출을 그대로 잰다 —
 * 서비스 공개 API(`render`)는 LruCache가 두 번째 반복부터 작업을 가리므로 직접 호출한다. 폰트 로드는 서비스 경로다.
 *
 * - parse: `RaTeXEngine.parseBlocking` (LaTeX → display list)
 * - draw: `RaTeXRenderer(displayList, 48 px)` 생성(레이아웃) + 미리 만든 bitmap에 `draw` (bitmap 할당 제외)
 */
@RunWith(AndroidJUnit4::class)
class MathBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val fontSizePx = 48f

    @Before
    fun loadFonts() {
        MathRenderService.shared.ensureFontsLoaded(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun ratexParse_simple() = parse(BenchmarkInputs.SIMPLE_MATH, display = false)

    @Test
    fun ratexParse_complex() = parse(BenchmarkInputs.COMPLEX_MATH, display = true)

    @Test
    fun ratexDraw_simple() = draw(BenchmarkInputs.SIMPLE_MATH, display = false)

    @Test
    fun ratexDraw_complex() = draw(BenchmarkInputs.COMPLEX_MATH, display = true)

    private fun parse(latex: String, display: Boolean) {
        benchmarkRule.measureRepeated { RaTeXEngine.parseBlocking(latex, display, Color.BLACK) }
    }

    private fun draw(latex: String, display: Boolean) {
        val displayList = RaTeXEngine.parseBlocking(latex, display, Color.BLACK)
        val probe = RaTeXRenderer(displayList, fontSizePx, RaTeXFontLoader::getTypeface)
        val bitmap = Bitmap.createBitmap(
            ceil(probe.widthPx).toInt().coerceAtLeast(1),
            ceil(probe.totalHeightPx).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        benchmarkRule.measureRepeated { RaTeXRenderer(displayList, fontSizePx, RaTeXFontLoader::getTypeface).draw(canvas) }
    }
}
