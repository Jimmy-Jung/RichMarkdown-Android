// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.spike

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ratex.DisplayItem
import io.ratex.RaTeXEngine
import io.ratex.RaTeXException
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * P0 spike: RaTeX 0.1.14(D4 provisional)가 16 KB 페이지 에뮬레이터에서 동작하는지 실측한다.
 * 판정은 최소 조건만 걸고 수치는 `[spike]` 로그로 남긴다 (목표치는 실측 뒤 정한다).
 * 핵심 질문은 `\text{한글}`이 실제 글리프로 그려지는가다 — 실패하면 그 자체가 spike의 결론이다.
 */
@RunWith(AndroidJUnit4::class)
class RaTeXSpikeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun loadFonts() {
        // KaTeX 폰트는 ratex-android AAR의 assets/fonts에 들어 있다.
        RaTeXFontLoader.ensureLoaded(context)
    }

    @Test
    fun 한글_text가_렌더된다() {
        val (plain, plainBitmap) = render("x", display = false)
        val (hangul, hangulBitmap) = render("x\\text{한글}", display = false)
        val plainOpaque = opaqueCount(plainBitmap)
        val hangulOpaque = opaqueCount(hangulBitmap)

        val hangulGlyphs = hangul.displayList.items
            .filterIsInstance<DisplayItem.GlyphPath>()
            .filter { it.charCode in HANGUL_SYLLABLES }
        val fontIds = hangulGlyphs.map { it.font }.distinct()
        val missingTypeface = fontIds.filter { RaTeXFontLoader.getTypeface(it) == null }
        log("[spike] 한글 GlyphPath ${hangulGlyphs.size}개, font=$fontIds, typeface 없음=$missingTypeface")
        log("[spike] width ${plain.widthPx} → ${hangul.widthPx}, opaque $plainOpaque → $hangulOpaque")

        // 아래 두 fail이 spike의 핵심 결론이다. 관측한 font id와 개수를 메시지에 남긴다.
        if (hangulGlyphs.isEmpty()) {
            fail(
                "한글 GlyphPath가 0개다 — \\text{한글}이 글리프로 나오지 않는다. " +
                    "items=${hangul.displayList.items.size}, opaque $plainOpaque → $hangulOpaque",
            )
        }
        if (missingTypeface.isNotEmpty()) {
            fail(
                "한글 글리프 ${hangulGlyphs.size}개의 typeface가 null이라 draw에서 건너뛴다. " +
                    "font=$missingTypeface, opaque $plainOpaque → $hangulOpaque",
            )
        }
        assertTrue("폭이 커져야 한다: ${plain.widthPx} → ${hangul.widthPx}", hangul.widthPx > plain.widthPx)
        assertTrue("불투명 픽셀이 2배 이상이어야 한다: $plainOpaque → $hangulOpaque", hangulOpaque >= plainOpaque * 2)
    }

    @Test
    fun Latin_text와_수식_기본형이_렌더된다() {
        listOf("\\frac{a}{b}" to true, "\\text{abc} + x^2" to false).forEach { (latex, display) ->
            val (renderer, bitmap) = render(latex, display)
            val opaque = opaqueCount(bitmap)
            log("[spike] $latex: ${renderer.widthPx}×${renderer.heightPx}+${renderer.depthPx}px, opaque=$opaque")
            assertTrue("$latex width", renderer.widthPx > 0f)
            assertTrue("$latex height", renderer.heightPx > 0f)
            assertTrue("$latex depth", renderer.depthPx >= 0f)
            assertTrue("$latex 불투명 픽셀 없음", opaque > 0)
        }
        // 아래첨자는 베이스라인 밑으로 내려가므로 depth가 양수여야 한다 (인라인 베이스라인 정렬의 전제).
        val subscript = RaTeXEngine.parseBlocking("y_2", displayMode = false)
        assertTrue("y_2 depth=${subscript.depth}", subscript.depth > 0.0)
    }

    @Test
    fun 첫_로드와_parse_지연_측정() {
        // 테스트 실행 순서는 보장되지 않는다. 다른 테스트가 먼저 돌았으면 "첫 parse"는 warm 값이다.
        val (fontCount, fontMs) = timed { RaTeXFontLoader.loadFromAssets(context, "fonts") }
        val (first, firstMs) = timed { RaTeXEngine.parseBlocking("\\frac{a}{b}") }
        val samplesMs = List(30) { timed { RaTeXEngine.parseBlocking("\\int_0^1 x^2 \\, dx") }.second }.sorted()
        val medianMs = samplesMs[samplesMs.size / 2]
        val p95Ms = samplesMs[(samplesMs.size * 95 / 100).coerceAtMost(samplesMs.lastIndex)]
        val renderer = RaTeXRenderer(first, 48f) { RaTeXFontLoader.getTypeface(it) }
        val (_, drawMs) = timed { renderer.draw(Canvas(newBitmap(renderer))) }

        log("[spike] loadFromAssets: ${fontCount}개 폰트, ${fmt(fontMs)} ms")
        log("[spike] first parseBlocking(\\frac{a}{b}): ${fmt(firstMs)} ms")
        log("[spike] parseBlocking(\\int_0^1 x^2 dx) ×30: median ${fmt(medianMs)} ms, p95 ${fmt(p95Ms)} ms")
        log("[spike] draw 48px: ${fmt(drawMs)} ms")
        assertTrue("폰트 0개 로드", fontCount > 0)
        assertTrue(firstMs > 0 && medianMs > 0 && p95Ms > 0 && drawMs > 0)
    }

    @Test
    fun so가_16KB_페이지_기기에서_로드된다() {
        log("[spike] _SC_PAGESIZE = ${Os.sysconf(OsConstants._SC_PAGESIZE)}")
        // RaTeXEngine 클래스 초기화가 System.loadLibrary("ratex_ffi")를 호출한다.
        assertTrue(RaTeXEngine.parseBlocking("x").width > 0.0)
    }

    @Test
    fun 잘못된_LaTeX는_RaTeXException() {
        // fail-open 계약: 파서 오류는 잡을 수 있는 예외여야 원문을 그대로 보여줄 수 있다.
        val error = assertThrows(RaTeXException::class.java) { RaTeXEngine.parseBlocking("\\frac{") }
        log("[spike] RaTeXException: ${error.message}")
        assertNotNull(error.message)
    }

    /** parse → 렌더러 → ARGB 비트맵 draw. 비트맵은 바운딩 박스보다 가로·세로 2px 크다. */
    private fun render(latex: String, display: Boolean, fontSizePx: Float = 48f): Pair<RaTeXRenderer, Bitmap> {
        val displayList = RaTeXEngine.parseBlocking(latex, displayMode = display)
        val renderer = RaTeXRenderer(displayList, fontSizePx) { id -> RaTeXFontLoader.getTypeface(id) }
        val bitmap = newBitmap(renderer)
        renderer.draw(Canvas(bitmap))
        return renderer to bitmap
    }

    private fun newBitmap(renderer: RaTeXRenderer): Bitmap = Bitmap.createBitmap(
        ceil(renderer.widthPx).toInt() + 2,
        ceil(renderer.totalHeightPx).toInt() + 2,
        Bitmap.Config.ARGB_8888,
    )

    /** alpha > 0 픽셀 수. 글리프가 실제로 그려졌는지의 근거다. */
    private fun opaqueCount(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { (it ushr 24) != 0 }
    }

    /** elapsedRealtimeNanos 기준 실행 시간(ms)과 결과. */
    private inline fun <T> timed(block: () -> T): Pair<T, Double> {
        val start = SystemClock.elapsedRealtimeNanos()
        val result = block()
        return result to (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    }

    private fun fmt(ms: Double): String = "%.2f".format(ms)

    private fun log(message: String) {
        Log.i(TAG, message)
        println(message)
    }

    private companion object {
        const val TAG = "RichMarkdownSpike"
        val HANGUL_SYLLABLES = 0xAC00..0xD7A3
    }
}
