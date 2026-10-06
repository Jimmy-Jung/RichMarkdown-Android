// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.util.LruCache
import androidx.annotation.ColorInt
import io.github.jimmyjung.richmarkdown.core.InputLimits
import io.ratex.RaTeXEngine
import io.ratex.RaTeXException
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * 수식 raster 요청 key (iOS `MathRenderService.swift` `MathRenderKey`, DEVELOPMENT.md D4 cache key).
 * iOS의 pointSize × displayScale은 [fontSizePx] 하나로 합친다.
 */
data class MathRenderKey(
    val latex: String,
    val mathFont: LatexMathFont,
    val fontSizePx: Float,
    @ColorInt val colorArgb: Int,
    val isDisplay: Boolean,
)

/** raster 결과. baseline은 bitmap 상단에서 [ascentPx] 아래 (iOS `RenderedMath`). */
class RenderedMath(
    val bitmap: Bitmap,
    val widthPx: Float,
    /** `RaTeXRenderer.heightPx` — 글리프 bleed를 포함한 baseline 위 높이. */
    val ascentPx: Float,
    /** `RaTeXRenderer.depthPx` — baseline 아래 깊이. */
    val descentPx: Float,
)

/**
 * 블록 수식용 벡터 경로 (iOS `BlockMathVectorView` 대응). 이미지 중간 단계가 없어 크기가 생성
 * 시점에 확정된다. View/Compose 양쪽에서 Canvas에 직접 그린다. RaTeX 타입은 밖으로 새지 않는다.
 */
class MathVectorLayout internal constructor(private val renderer: RaTeXRenderer) {
    val widthPx: Float get() = renderer.widthPx
    val ascentPx: Float get() = renderer.heightPx
    val descentPx: Float get() = renderer.depthPx

    /** origin = bounding box 좌상단. baseline은 `ascentPx` 아래다. */
    fun draw(canvas: Canvas) = renderer.draw(canvas)
}

/**
 * parse 전에는 원문 byte와 폰트 범위만 제한한다. LaTeX 명령 길이는 실제 화면 폭과 다르므로
 * bitmap·벡터 크기는 parse 뒤 공통 renderer 경계에서 검사한다.
 */
private object RasterInputLimits {
    const val MIN_FONT_SIZE_PX = 1.0
    /** iOS maximumPointSize 256 × maximumDisplayScale 4. */
    const val MAX_FONT_SIZE_PX = 1024.0
    const val MAX_PIXEL_EDGE = 8_192.0
    const val MAX_PIXEL_COUNT = 4_194_304.0

    fun allows(key: MathRenderKey): Boolean {
        val pxPerEm = key.fontSizePx.toDouble()
        if (!pxPerEm.isFinite() || pxPerEm < MIN_FONT_SIZE_PX || pxPerEm > MAX_FONT_SIZE_PX) return false
        val sourceUnits = key.latex.toByteArray(Charsets.UTF_8).size
        return sourceUnits <= InputLimits.MAX_MATH_SOURCE_UTF8_BYTES
    }
}

/**
 * RaTeX 격리 서비스 (iOS `MathRenderService` actor, DEVELOPMENT.md D4).
 * `io.ratex.*`는 이 파일만 import한다 — 엔진 교체 비용을 한 파일로 유지한다.
 *
 * raster는 [Dispatchers.Default]에서만 실행한다(`parseBlocking`은 main 금지). 캐시는 hop 없이 읽는다.
 */
class MathRenderService private constructor() {

    // ponytail: cache 상한은 P0 측정 전 잠정값(iOS 동일 64 MiB). cost는 bitmap pixel byte.
    private val cache = object : LruCache<MathRenderKey, RenderedMath>(64 * 1024 * 1024) {
        override fun sizeOf(key: MathRenderKey, value: RenderedMath): Int = value.bitmap.byteCount
    }

    /** 같은 key의 실패 로그를 한 번만 남기기 위한 기록. raster 재시도 자체는 막지 않는다. */
    private val warnedKeys = LruCache<MathRenderKey, Boolean>(256)

    @Volatile
    private var appContext: Context? = null

    /**
     * KaTeX 폰트(ratex-android AAR `assets/fonts`) 로드. 멱등이며 아무 스레드에서 호출 가능.
     * 렌더러는 뷰 생성 시점에 한 번 호출한다 — 폰트가 없으면 글리프가 그려지지 않는다(P0 실측).
     */
    fun ensureFontsLoaded(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        RaTeXFontLoader.ensureLoaded(app, FONT_ASSET_DIR)
    }

    /** 캐시 조회만. MainActor 동기 fast path와 worker의 일괄 게시 판단에서 hop 없이 읽는다. */
    fun cachedImage(key: MathRenderKey): RenderedMath? = cache.get(key)

    /** 렌더 실패(RaTeXException·preflight 초과·bitmap 상한)는 null. 호출자는 해당 노드만 원문 source로 유지한다. */
    suspend fun render(key: MathRenderKey): RenderedMath? {
        if (!preflightAllows(key)) return null
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.Default) {
            cache.get(key) ?: renderUncached(key)?.also { cache.put(key, it) }
        }
    }

    /**
     * 블록 수식 벡터 layout. raster와 같은 원문·폰트 사전 제한과 실제 크기 상한을 공유한다.
     * 캐시하지 않는다.
     */
    suspend fun layout(key: MathRenderKey): MathVectorLayout? {
        if (!preflightAllows(key)) return null
        return withContext(Dispatchers.Default) {
            newRenderer(key)?.let(::MathVectorLayout)
        }
    }

    /** `ComponentCallbacks2.onTrimMemory`에서 호출 (iOS memory warning 대응). */
    fun trimMemory() {
        cache.evictAll()
    }

    private fun renderUncached(key: MathRenderKey): RenderedMath? {
        val renderer = newRenderer(key) ?: return null
        val widthPx = renderer.widthPx
        val totalHeightPx = renderer.totalHeightPx
        val width = ceil(widthPx).toInt().coerceAtLeast(1)
        val height = ceil(totalHeightPx).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bitmap))
        return RenderedMath(
            bitmap = bitmap,
            widthPx = widthPx,
            ascentPx = renderer.heightPx,
            descentPx = renderer.depthPx,
        )
    }

    /** parse + renderer 생성. worker 스레드 전용. 실패는 null + 로그 1회. */
    private fun newRenderer(key: MathRenderKey): RaTeXRenderer? {
        appContext?.let { RaTeXFontLoader.ensureLoaded(it, FONT_ASSET_DIR) }
        val displayList = try {
            RaTeXEngine.parseBlocking(key.latex, key.isDisplay, key.colorArgb)
        } catch (e: RaTeXException) {
            return warnOnce(key, e.message ?: "parse error")
        }
        val renderer = RaTeXRenderer(displayList, key.fontSizePx, RaTeXFontLoader::getTypeface)
        val width = renderer.widthPx.toDouble()
        val height = renderer.totalHeightPx.toDouble()
        if (!width.isFinite() || !height.isFinite() || width < 0 || height < 0 ||
            width > RasterInputLimits.MAX_PIXEL_EDGE || height > RasterInputLimits.MAX_PIXEL_EDGE ||
            ceil(width).coerceAtLeast(1.0) * ceil(height).coerceAtLeast(1.0) > RasterInputLimits.MAX_PIXEL_COUNT
        ) {
            return warnOnce(key, "layout ${width}×${height} 상한 초과")
        }
        return renderer
    }

    private fun <T> warnOnce(key: MathRenderKey, reason: String): T? {
        if (warnedKeys.put(key, true) == null) {
            Log.w(TAG, "수식 raster 실패 (원문 fail-open): $reason latex=${key.latex.take(80)}")
        }
        return null
    }

    companion object {
        private const val TAG = "RichMarkdown"
        private const val FONT_ASSET_DIR = "fonts"

        val shared: MathRenderService by lazy { MathRenderService() }

        /**
         * latex ≤ 4096 UTF-8 bytes, 1 ≤ fontSizePx ≤ 1024. 실제 크기는 parse 뒤 raster·벡터
         * 공통 경계에서 edge ≤ 8192, pixel count ≤ 4,194,304로 제한한다.
         */
        fun preflightAllows(key: MathRenderKey): Boolean = RasterInputLimits.allows(key)
    }
}
