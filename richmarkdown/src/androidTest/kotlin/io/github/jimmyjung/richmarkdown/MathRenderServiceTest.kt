// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/** RaTeX 격리 서비스의 raster·벡터·캐시·fail-open 계약 (P1-CONTRACTS §1). */
@RunWith(AndroidJUnit4::class)
class MathRenderServiceTest {

    private val service = MathRenderService.shared

    @Before
    fun loadFonts() {
        service.ensureFontsLoaded(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun key(latex: String, isDisplay: Boolean = false, fontSizePx: Float = 48f) =
        MathRenderKey(latex, fontSizePx, Color.BLACK, isDisplay)

    @Test
    fun inlineRasterHasMetricsAndPixels() {
        val rendered = runBlocking { service.render(key("x^2")) }
        assertNotNull(rendered)
        rendered!!
        assertTrue("ascent=${rendered.ascentPx}", rendered.ascentPx > 0f)
        assertTrue("descent=${rendered.descentPx}", rendered.descentPx >= 0f)
        assertTrue(rendered.widthPx > 0f)
        assertTrue(rendered.bitmap.width >= ceil(rendered.widthPx).toInt())
        assertTrue("불투명 픽셀 없음", opaqueCount(rendered.bitmap) > 0)
    }

    @Test
    fun invalidLatexFailsOpen() {
        assertNull(runBlocking { service.render(key("\\frac{")) })
        assertNull(runBlocking { service.layout(key("\\frac{", isDisplay = true)) })
        val nestedIncomplete = "\\underbrace{".repeat(64) + "x" + "}".repeat(63)
        assertNull(runBlocking { service.render(key(nestedIncomplete)) })
        assertNull(runBlocking { service.layout(key(nestedIncomplete, isDisplay = true)) })
    }

    @Test
    fun preflightRejectsBeforeEngine() {
        assertNull(runBlocking { service.render(key("x".repeat(5_000))) })
        assertNull(runBlocking { service.render(key("x", fontSizePx = 0.5f)) })
    }

    @Test
    fun actualLayoutBoundsApplyToRasterAndVector() {
        val excessiveWidth = key("x".repeat(100), isDisplay = true, fontSizePx = 256f)
        val excessivePixels = key("\\rule{3em}{2em}", isDisplay = true, fontSizePx = 1024f)
        listOf(excessiveWidth, excessivePixels).forEach { request ->
            assertTrue("원문·폰트 범위 내 입력", MathRenderService.preflightAllows(request))
            assertNull("raster actual bounds", runBlocking { service.render(request) })
            assertNull("vector actual bounds", runBlocking { service.layout(request) })
        }
    }

    @Test
    fun cacheHitReturnsSameInstance() {
        val k = key("\\alpha + \\beta")
        val first = runBlocking { service.render(k) }
        assertNotNull(first)
        assertSame(first, service.cachedImage(k))
        assertSame(first, runBlocking { service.render(k) })

        service.trimMemory()
        assertNull(service.cachedImage(k))
    }

    @Test
    fun displayLayoutDrawsPixels() {
        val layout = runBlocking { service.layout(key("\\frac{a}{b}", isDisplay = true)) }
        assertNotNull(layout)
        layout!!
        assertTrue(layout.widthPx > 0f && layout.ascentPx > 0f && layout.descentPx > 0f)

        val bitmap = Bitmap.createBitmap(
            ceil(layout.widthPx).toInt(),
            ceil(layout.ascentPx + layout.descentPx).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        layout.draw(Canvas(bitmap))
        assertTrue("벡터 draw가 픽셀을 남기지 않음", opaqueCount(bitmap) > 0)
    }

    @Test
    fun complexMathRendersThroughSharedService() {
        complexMathSamples.forEach { (name, latex) ->
            val request = key(latex, isDisplay = true)
            val rendered = runBlocking { service.render(request) }
            assertNotNull("$name raster", rendered)
            assertTrue("$name raster pixels", opaqueCount(rendered!!.bitmap) > 0)
            if (name == "three-rows-two-underbraces") {
                // parse 성공만으로는 부족하다. 하단에 넓은 brace의 실제 획이 남아야 한다.
                assertTrue("아래 중괄호 획이 보이지 않음", longestBottomStroke(rendered.bitmap) > request.fontSizePx * 2)
            }

            val layout = runBlocking { service.layout(request) }
            assertNotNull("$name vector", layout)
            layout!!
            val bitmap = Bitmap.createBitmap(
                ceil(layout.widthPx).toInt(),
                ceil(layout.ascentPx + layout.descentPx).toInt(),
                Bitmap.Config.ARGB_8888,
            )
            layout.draw(Canvas(bitmap))
            assertTrue("$name vector pixels", opaqueCount(bitmap) > 0)
        }
    }

    @Test
    fun nativeComplexMathProbe() {
        complexMathSamples.forEach { (name, latex) ->
            val renderer = RaTeXRenderer(
                RaTeXEngine.parseBlocking(latex, true, Color.BLACK),
                48f,
                RaTeXFontLoader::getTypeface,
            )
            val bitmap = Bitmap.createBitmap(
                ceil(renderer.widthPx).toInt(),
                ceil(renderer.totalHeightPx).toInt(),
                Bitmap.Config.ARGB_8888,
            )
            renderer.draw(Canvas(bitmap))
            val pixels = opaqueCount(bitmap)
            assertTrue("$name native pixels", pixels > 0)
            Log.i("ComplexMathProbe", "$name ${bitmap.width}x${bitmap.height} pixels=$pixels")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val screenshot = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(screenshot).apply {
                drawColor(Color.WHITE)
                renderer.draw(this)
            }
            context.cacheDir.resolve("complex-math-$name.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private val complexMathSamples = listOf(
        "underbrace" to "\\underbrace{a+b+c}_{q}",
        "array-two-rows" to "\\left\\{\\begin{array}{cc}1+\\frac{a-b}{2\\Delta t/T}&\\mathrm{if}\\ Q>0\\\\0&\\mathrm{if}\\ Q=0\\end{array}\\right.",
        "three-rows-two-underbraces" to """
            r_t=\left\{\begin{array}{ccc}
            \underbrace{\begin{array}{c}
            1+\frac{\bar{R}_Q(t+\Delta t)-R_Q(t)}{2\Delta t/T_{\mathrm{single}}}\\
            0\\
            0
            \end{array}}_{r_t^{(1)}}&
            \underbrace{\begin{array}{c}
            \vphantom{\frac{\bar{R}_Q}{T_{\mathrm{single}}}}+0\\
            -P\\
            +0
            \end{array}}_{r_t^{(2)}}&
            \begin{array}{l}
            \vphantom{\frac{\bar{R}_Q}{T_{\mathrm{single}}}}\mathrm{if}\ \bar{R}_Q(t+\Delta t)>0\\
            \mathrm{if}\ \bar{R}_Q(t)\ne0\ \mathrm{and}\ R_Q(t+\Delta t)=0\\
            \mathrm{if}\ R_Q(t)=0
            \end{array}
            \end{array}\right.
        """.trimIndent(),
        "cases-positive-control" to "\\begin{cases}1+\\frac{a-b}{2\\Delta t/T}&\\mathrm{if}\\ Q>0\\\\0&\\mathrm{if}\\ Q=0\\end{cases}",
    )

    private fun opaqueCount(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { (it ushr 24) != 0 }
    }

    private fun longestBottomStroke(bitmap: Bitmap): Int {
        val row = IntArray(bitmap.width)
        var longest = 0
        for (y in (bitmap.height * 0.6f).toInt() until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            var run = 0
            row.forEach { pixel ->
                run = if ((pixel ushr 24) != 0) run + 1 else 0
                longest = maxOf(longest, run)
            }
        }
        return longest
    }
}
