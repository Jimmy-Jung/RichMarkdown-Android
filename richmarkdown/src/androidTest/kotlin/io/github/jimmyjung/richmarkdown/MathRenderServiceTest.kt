// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        MathRenderKey(latex, LatexMathFont.KaTeX, fontSizePx, Color.BLACK, isDisplay)

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
    }

    @Test
    fun preflightRejectsBeforeEngine() {
        assertNull(runBlocking { service.render(key("x".repeat(5_000))) })
        assertNull(runBlocking { service.render(key("x", fontSizePx = 0.5f)) })
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

    private fun opaqueCount(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { (it ushr 24) != 0 }
    }
}
