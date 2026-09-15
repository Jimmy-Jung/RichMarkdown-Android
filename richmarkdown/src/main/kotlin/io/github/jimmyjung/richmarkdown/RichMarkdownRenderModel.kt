// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import android.os.Looper
import android.util.Log
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import io.github.jimmyjung.richmarkdown.core.CoalescingWorker
import io.github.jimmyjung.richmarkdown.core.InputLimits
import io.github.jimmyjung.richmarkdown.core.MathSegment
import io.github.jimmyjung.richmarkdown.core.ParsedDocument
import io.github.jimmyjung.richmarkdown.core.RichMarkdownParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 비동기 렌더 계약 (iOS `RichMarkdownRenderModel.swift`, DEVELOPMENT.md §4).
 *
 * main 스레드에서는 generation 관리와 게시만 한다. CPU parse/raster는 [Dispatchers.Default]에서 실행한다.
 * 게시는 2단계: (1) 수식을 원문으로 둔 [ParsedDocument], (2) 수식 이미지 hydration.
 *
 * @param scope main dispatcher 기반 scope (뷰/컴포저블 수명). worker의 perform은 이 scope에서 시작한다.
 */
class RichMarkdownRenderModel(scope: CoroutineScope) {

    /**
     * Markdown parsing 결과를 결정하는 canonical 입력 식별자다.
     * 수식 raster 설정은 포함하지 않으므로 테마/색/scale 변경 때 문서를 유지할 수 있다.
     */
    data class ParseIdentity(
        val markdown: String,
        val dollarMath: LatexDollarMathOptions,
        val wasTruncated: Boolean,
    ) {
        /**
         * [of]가 이 identity의 **스트리밍 append**(같은 파싱 설정, markdown 확장)인가.
         * 잘린 입력은 표시 상한에서 prefix 관계가 깨지므로 제외한다.
         */
        fun isStreamingPrefix(of: ParseIdentity): Boolean =
            dollarMath == of.dollarMath &&
                !wasTruncated && !of.wasTruncated &&
                of.markdown.startsWith(markdown)
    }

    data class Request(
        /** 과대 원문을 보관하지 않는 canonical bounded input이다. */
        val boundedInput: InputLimits.BoundedInput,
        val dollarMath: LatexDollarMathOptions,
        val fontSizePx: Float,
        @ColorInt val colorArgb: Int,
        val mathFont: LatexMathFont = LatexMathFont.KaTeX,
        /**
         * 블록(display) 수식 raster가 필요한가. View 렌더러는 블록 수식을 벡터로 그리므로 false를
         * 보낸다 — 아무도 읽지 않는 raster를 만들지 않는다. 인라인 수식 raster에는 영향이 없다.
         */
        val rastersDisplayMath: Boolean = true,
    ) {
        val markdown: String get() = boundedInput.text
        val wasTruncated: Boolean get() = boundedInput.wasTruncated
        val parseIdentity: ParseIdentity get() = ParseIdentity(markdown, dollarMath, wasTruncated)

        /**
         * markdown/파싱 조건을 제외한 raster 설정(크기·색·서체)이 같은가.
         * 스트리밍 append로 문서가 stale인 동안 이전 이미지를 계속 써도 되는지 판정한다 —
         * 수식 raster key는 latex source 기준이라 문서 안 위치와 무관하다.
         */
        fun matchesRasterConfiguration(other: Request): Boolean =
            fontSizePx == other.fontSizePx &&
                colorArgb == other.colorArgb &&
                mathFont == other.mathFont &&
                rastersDisplayMath == other.rastersDisplayMath

        companion object {
            /** public ingress. [InputLimits.bound]를 적용해 canonical form만 모델에 들어온다. */
            fun of(
                markdown: String,
                dollarMath: LatexDollarMathOptions,
                fontSizePx: Float,
                @ColorInt colorArgb: Int,
                mathFont: LatexMathFont = LatexMathFont.KaTeX,
                rastersDisplayMath: Boolean = true,
            ): Request = Request(
                boundedInput = InputLimits.bound(markdown),
                dollarMath = dollarMath,
                fontSizePx = fontSizePx,
                colorArgb = colorArgb,
                mathFont = mathFont,
                rastersDisplayMath = rastersDisplayMath,
            )
        }
    }

    data class State(
        val document: ParsedDocument?,
        /** [document]가 어느 parsing 입력에서 만들어졌는지. UI는 현재 Request의 parseIdentity와 같을 때만 표시한다. */
        val parseIdentity: ParseIdentity?,
        val mathImages: Map<MathSegment, RenderedMath>,
        /** [mathImages]가 어느 전체 render request(크기/색 포함)로 만들어졌는지. UI는 현재 Request와 같을 때만 쓴다. */
        val imageRequest: Request?,
        /**
         * 아직 파싱되지 않은 최신 요청을 표시할 안전한 원문. 과대 입력은 [InputLimits]의 표시
         * 상한으로 잘려 있어 fallback에도 원본 전체 문자열이 전달되지 않는다.
         */
        val fallbackMarkdown: String,
    )

    private class Job(
        val generation: Int,
        val request: Request,
        /**
         * 표시 중인 문서의 스트리밍 append인가. append parse는 [ParseCache]에 넣지 않는다 —
         * tick마다 누적 원문 전체가 새 키가 되어 다른 셀의 항목을 밀어내고, 그 키는 다시 조회되지 않는다.
         */
        val isStreamingAppend: Boolean,
    )

    private val _state = MutableStateFlow(State(null, null, emptyMap(), null, ""))
    val state: StateFlow<State> = _state.asStateFlow()

    /** main에서만 쓴다. worker는 [isCurrent]로 읽기만 하므로 volatile로 충분하다. */
    @Volatile
    private var generation = 0

    @Volatile
    private var completedGeneration = 0
    private var lastRequest: Request? = null

    private val worker = CoalescingWorker<Job>(scope) { job -> process(job) }

    /**
     * 같은 요청 재제출은 무시한다. 컴포저블 재구성·뷰 재바인딩은 같은 값으로 다시 올 수 있다 —
     * 결과가 같으므로 게시(뷰 재구성)도 없어야 한다. 진행 중이면 그 작업이 곧 게시한다.
     */
    @MainThread
    fun submit(request: Request) {
        check(Looper.getMainLooper().isCurrentThread) { "submit은 main 스레드에서만 호출한다" }
        if (request == lastRequest) return
        val previous = lastRequest
        val parseIdentityChanged = request.parseIdentity != previous?.parseIdentity
        val isStreamingAppend = parseIdentityChanged &&
            _state.value.parseIdentity?.isStreamingPrefix(request.parseIdentity) == true
        lastRequest = request
        generation += 1

        // Markdown/dollar parsing 조건이 바뀌면 cache 결과가 게시되기 전에는 이전 문서를
        // 보이면 안 된다 — 셀 재사용에서 다른 메시지의 문서가 한 프레임 되살아난다.
        //
        // 예외는 **스트리밍 append**다: 표시 중인 문서가 새 markdown의 prefix면 새 parse가
        // 게시될 때까지 이전 렌더(문서 + 이미지)를 유지한다. 매 갱신 원문 fallback으로
        // 되돌리면 화면 전체가 원문 ↔ 렌더를 오가며 출렁인다(iOS 데모 실측).
        val current = _state.value
        _state.value = when {
            parseIdentityChanged && !isStreamingAppend ->
                State(null, null, emptyMap(), null, request.markdown)
            parseIdentityChanged -> current.copy(fallbackMarkdown = request.markdown)
            // 수식 설정만 바뀐 경우: parsed document는 유지하고 이미지만 무효화한다.
            else -> current.copy(mathImages = emptyMap(), imageRequest = null)
        }

        // 캐시가 전부 있어도 여기서 동기로 게시하지 않는다(iOS 실측). 동기 게시는 셀이
        // 붙는 레이아웃 패스 안에서 리사이즈를 일으켜 스크롤이 얼어붙는다. worker 왕복
        // 뒤 publishComplete(단일 게시)로 합쳐지는 것으로 충분하다.
        //
        // generation high-water mark는 worker lock 안에서 판정한다 — 늦게 도착한 이전 job이
        // 최신 pending을 덮지 못한다.
        worker.submit(Job(generation, request, isStreamingAppend), generation)
    }

    /** 입력 종료 후 idle 검증용 (테스트). 마지막으로 제출된 generation의 처리가 끝나면 false. */
    val hasOutstandingWork: Boolean
        get() = completedGeneration < generation

    /** 실행 중·대기 중인 작업이 모두 끝날 때까지 기다린다. */
    suspend fun awaitIdle() = worker.awaitIdle()

    private fun isCurrent(generation: Int): Boolean = generation == this.generation

    private suspend fun process(job: Job) {
        runJob(job)
        onMain { completedGeneration = maxOf(completedGeneration, job.generation) }
    }

    /**
     * CPU 작업은 전부 [Dispatchers.Default]에서, 게시와 cache 삽입만 main으로 hop한다
     * (iOS `nonisolated static run` + `await model.…`). generation 확인은 volatile 읽기라
     * hop이 필요 없다. 각 단계 사이에 확인해 stale job은 이후 raster·게시를 만들지 않는다.
     */
    private suspend fun runJob(job: Job) = withContext(Dispatchers.Default) {
        // service 진입 직후 generation 확인.
        if (!isCurrent(job.generation)) return@withContext
        val request = job.request

        val cacheKey = ParseCache.key(request.markdown, request.dollarMath, request.wasTruncated)
        val parsed = ParseCache.document(cacheKey) ?: run {
            val fresh = parseOrNull(request) ?: return@withContext
            // 스트리밍 append는 저장하지 않는다. 스트림의 첫 제출(교체)만 캐시에 남긴다.
            // 확인과 삽입을 같은 main turn에서 해 그 사이 새 요청이 끼어들지 않게 한다.
            if (!job.isStreamingAppend) {
                onMain { if (isCurrent(job.generation)) ParseCache.store(cacheKey, fresh) }
            }
            fresh
        }

        if (!isCurrent(job.generation)) return@withContext

        // 1단계 게시: 캐시된 raster는 즉시 hydration하고, 없는 수식만 원문으로 시작한다.
        // 전부 캐시에 있으면(스트리밍 append의 일반 경우) 이것이 단일 게시다 — 이미 raster된
        // 수식이 원문으로 되돌아가는 프레임을 만들지 않는다.
        val images = HashMap<MathSegment, RenderedMath>()
        val missing = ArrayList<MathSegment>()
        for (segment in rasterSegments(parsed, request)) {
            val cached = MathRenderService.shared.cachedImage(renderKey(segment, request))
            if (cached != null) images[segment] = cached else missing.add(segment)
        }
        onMain {
            if (isCurrent(job.generation)) {
                _state.value = State(parsed, request.parseIdentity, images.toMap(), request, _state.value.fallbackMarkdown)
            }
        }
        if (missing.isEmpty()) return@withContext

        for (segment in missing) {
            // 각 수식 사이에 generation 확인. stale이면 이후 raster·cache 삽입이 없다.
            if (!isCurrent(job.generation)) return@withContext
            MathRenderService.shared.render(renderKey(segment, request))?.let { images[segment] = it }
        }

        // 최종 게시 직전 확인.
        if (!isCurrent(job.generation)) return@withContext
        onMain {
            if (isCurrent(job.generation)) {
                _state.value = _state.value.copy(mathImages = images.toMap(), imageRequest = request)
            }
        }
    }

    /**
     * D3a: commonmark-java 0.30.0은 `List.of`(API 30)를 쓴다. desugaring이 꺼진 소비 앱에서는
     * [NoSuchMethodError]/[NoClassDefFoundError]가 나므로 여기서 잡아 원문 fail-open으로 둔다
     * (document = null → fallbackMarkdown 표시). 로그는 프로세스당 1회.
     */
    private fun parseOrNull(request: Request): ParsedDocument? = try {
        RichMarkdownParser.parse(request.boundedInput, request.dollarMath.toCore())
    } catch (e: NoSuchMethodError) {
        logParserFailure(e)
    } catch (e: NoClassDefFoundError) {
        logParserFailure(e)
    } catch (e: Throwable) {
        logParserFailure(e)
    }

    private fun logParserFailure(error: Throwable): ParsedDocument? {
        if (parserFailureLogged.compareAndSet(false, true)) {
            Log.e(
                TAG,
                "Markdown 파서 실패 — 원문을 그대로 표시한다. API 30 미만은 coreLibraryDesugaring이 필요하다 (DEVELOPMENT.md D3a)",
                error,
            )
        }
        return null
    }

    private suspend inline fun onMain(crossinline block: () -> Unit) =
        withContext(Dispatchers.Main.immediate) { block() }

    private companion object {
        const val TAG = "RichMarkdown"
        val parserFailureLogged = AtomicBoolean(false)

        /**
         * 이 요청이 raster해야 하는 수식 segment (문서 순서, 중복 제거).
         *
         * display segment는 `BlockMath` 블록과 1:1이다 — display delimiter는 공백 제외 paragraph
         * 전체일 때만 인정되고(§3), 아니면 diagnostic과 함께 plain text로 남아 segment 자체가
         * 만들어지지 않는다. 따라서 `kind.isDisplay` 필터가 곧 "블록 수식 제외"다.
         */
        fun rasterSegments(parsed: ParsedDocument, request: Request): List<MathSegment> {
            val all = parsed.allMathSegments
            return if (request.rastersDisplayMath) all else all.filter { !it.kind.isDisplay }
        }

        fun renderKey(segment: MathSegment, request: Request): MathRenderKey = MathRenderKey(
            latex = segment.latex,
            mathFont = request.mathFont,
            fontSizePx = request.fontSizePx,
            colorArgb = request.colorArgb,
            isDisplay = segment.kind.isDisplay,
        )
    }
}
