# P1 구현 계약 — 공유 렌더 모델 API

- 작성일: 2026-09-15 (KST)
- 수정일: 2026-10-07
- 상태: P1에서 정한 공유 모델 구조를 현재 0.2.0 릴리스 대상 API에 맞춰 갱신했습니다.
  아래 코드는 주요 타입과 호출 규칙을 요약한 계약입니다. 최종 시그니처는 현재 소스를 따르며 실행 검수는 [검수 기록](report/validation.md)에 연결합니다.

모듈 `richmarkdown`, 패키지 `io.github.jimmyjung.richmarkdown`. iOS 원본: `RichMarkdownRenderModel.swift`,
`MathRenderService.swift`, `ParseCache.swift`, `RichMarkdownStreamingOptions.swift`(버퍼).

수식 서체는 RaTeX가 KaTeX 기반 내장 글꼴에서 자동으로 선택합니다. 0.2.0에서는
`LatexMathFont`와 테마·요청·캐시 키의 `mathFont` 선택 인자를 제거했습니다.
기존 코드에서는 해당 인자를 삭제하고 크기·색·블록 여부만 전달합니다. `fontSizePx`와
내부 표시 문맥의 `mathFontSizePx`는 크기 값이므로 유지합니다.

## 1. MathRenderService.kt — RaTeX 격리 (이 파일만 `io.ratex.*` import)

```kotlin
data class MathRenderKey(
    val latex: String,
    val fontSizePx: Float,          // iOS pointSize × displayScale에 해당. px 단위 하나로 통일
    @ColorInt val colorArgb: Int,
    val isDisplay: Boolean,
)

/** raster 결과. baseline은 bitmap 상단에서 ascentPx 아래. */
class RenderedMath(
    val bitmap: Bitmap,
    val widthPx: Float,
    val ascentPx: Float,            // RaTeXRenderer.heightPx
    val descentPx: Float,           // RaTeXRenderer.depthPx
)

/** 블록 수식용 벡터 경로 (iOS BlockMathVectorView 대응). View/Compose 양쪽에서 Canvas에 직접 그린다. */
class MathVectorLayout internal constructor(...) {
    val widthPx: Float; val ascentPx: Float; val descentPx: Float
    fun draw(canvas: android.graphics.Canvas)   // origin = bounding box 좌상단
}

class MathRenderService private constructor() {
    companion object {
        val shared: MathRenderService
        fun preflightAllows(key: MathRenderKey): Boolean   // latex ≤ 4096 UTF-8 bytes, 1 ≤ fontSizePx ≤ 1024
                                                           // 배치 결과 생성 뒤 실제 각 변 ≤ 8192, 올림한 면적 ≤ 4,194,304를 별도로 검사
    }
    fun ensureFontsLoaded(context: Context)                // RaTeXFontLoader.ensureLoaded(applicationContext). 멱등, 아무 스레드
    fun cachedImage(key: MathRenderKey): RenderedMath?     // LruCache(cost = bitmap.byteCount, 64 MiB), hop 없음
    suspend fun render(key: MathRenderKey): RenderedMath?  // Dispatchers.Default. 실패(RaTeXException·preflight) → null
    suspend fun layout(key: MathRenderKey): MathVectorLayout?  // 블록 수식 벡터. 실패 → null
    fun trimMemory()                                        // ComponentCallbacks2.onTrimMemory에서 호출
}
```

## 2. ParseCache.kt

```kotlin
internal object ParseCache {
    class Key internal constructor(val value: String, val sourceByteCount: Int)
    fun key(markdown: String, dollarMath: LatexDollarMathOptions, wasTruncated: Boolean): Key   // "${rawValue}${T|-}$markdown"
    fun document(key: Key): ParsedDocument?
    fun store(key: Key, document: ParsedDocument)   // cost = max(source,1)×3 + blocks×512 + mathSegments×1024, 총 16 MiB
    fun removeAll()
}
```

## 3. RichMarkdownRenderModel.kt

```kotlin
class RichMarkdownRenderModel(scope: CoroutineScope) {   // scope = main dispatcher 기반 (뷰/컴포저블 수명)
    data class ParseIdentity(val markdown: String, val dollarMath: LatexDollarMathOptions, val wasTruncated: Boolean) {
        fun isStreamingPrefix(of: ParseIdentity): Boolean
    }
    data class Request(
        val boundedInput: InputLimits.BoundedInput,
        val dollarMath: LatexDollarMathOptions,
        val fontSizePx: Float,
        @ColorInt val colorArgb: Int,
        val rastersDisplayMath: Boolean = true,      // 현재 Compose·View 모두 블록 수식을 벡터로 그리므로 false 전달
    ) {
        val markdown: String; val wasTruncated: Boolean; val parseIdentity: ParseIdentity
        fun matchesRasterConfiguration(other: Request): Boolean
        companion object { fun of(markdown: String, dollarMath: LatexDollarMathOptions, fontSizePx: Float, colorArgb: Int, rastersDisplayMath: Boolean = true): Request /* InputLimits.bound 적용 */ }
    }
    data class State(
        val document: ParsedDocument?,
        val parseIdentity: ParseIdentity?,
        val mathImages: Map<MathSegment, RenderedMath>,
        val imageRequest: Request?,
        val fallbackMarkdown: String,
    )
    val state: StateFlow<State>
    @MainThread fun submit(request: Request)      // 같은 Request 재제출 무시, generation 증가, latest-wins worker
    val hasOutstandingWork: Boolean
    suspend fun awaitIdle()
}
```

게시 규칙(iOS와 동일): parseIdentity 변경 시 fallbackMarkdown 갱신; 스트리밍 append(이전 identity가 새 markdown의 prefix)면
이전 document·images 유지, 아니면 비움; raster 설정만 바뀌면 document 유지·images 비움. worker: ParseCache 조회 → 파싱
(append는 캐시 저장 안 함) → generation 확인 → 캐시된 raster로 1차 게시 → 누락 segment raster(각 단계 generation 확인) → 최종 게시.
D3a: 파서 호출을 `try { … } catch (e: NoSuchMethodError | NoClassDefFoundError)`로 감싸 실패 시 document=null 유지(원문 fallback) + `Log.e`.

수식 이미지 설정은 `fontSizePx`·`colorArgb`·`rastersDisplayMath`로 비교합니다. 개별 수식의
`MathRenderKey`에는 여기에 LaTeX 원문과 해당 수식의 `isDisplay`를 넣습니다. 수식 서체를
선택하는 값은 어느 비교에도 들어가지 않습니다.

## 4. RichMarkdownStreamingTextBuffer.kt

```kotlin
class RichMarkdownStreamingTextBuffer(scope: CoroutineScope, interval: Duration = 100.milliseconds) {
    val text: StateFlow<String>
    fun submit(latest: String)   // 간격 안 갱신은 마지막 값만, 간격 끝 trailing 게시 1회
    fun flush()
}
```

## 5. 렌더러 공개 진입점

```kotlin
@Composable fun RichMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    dollarMath: LatexDollarMathOptions = LatexDollarMathOptions.None,
    theme: RichMarkdownTheme = RichMarkdownTheme.Default,
    streaming: RichMarkdownStreamingOptions? = null,
    codeBlocks: RichMarkdownCodeBlockOptions = RichMarkdownCodeBlockOptions.None,
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    onOpenLink: ((android.net.Uri) -> Unit)? = null,     // null → LocalUriHandler
)

class RichMarkdownView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var markdown: String            // setter가 InputLimits 적용, getter는 canonical 텍스트
    var dollarMath: LatexDollarMathOptions
    var theme: RichMarkdownTheme
    var streaming: RichMarkdownStreamingOptions?
    var codeBlocks: RichMarkdownCodeBlockOptions
    var isDarkTheme: Boolean?       // null → Configuration.uiMode
    var onOpenLink: ((android.net.Uri) -> Unit)?
    var onContentSizeChange: (() -> Unit)?
}
```

## 6. 인라인 수식 baseline 규칙

- View: `ReplacementSpan.getSize`에서 `fm.ascent = -ceil(ascentPx)`, `fm.descent = ceil(descentPx)`, `top/bottom` 동일. draw는 `y(baseline) - ascentPx`에 bitmap 상단.
- Compose: `InlineTextContent(Placeholder(width, height = ascent + descent, PlaceholderVerticalAlign.AboveBaseline))`, 내부에서 bitmap을 `descentPx`만큼 아래로 translate하여 그린다(클립 없음). 검증은 에뮬레이터 스크린샷.
- 접근성 라벨: `"수식: <latex>"` (iOS 동일).

## 7. 실패 표시 (iOS §1 fail-open 그대로)

수식 raster 실패 → 원문 `segment.source`를 codeFont로 표시. 파서 예외(D3a) → 원문 markdown 전체를 bodyFont로 표시. 하이라이트 실패 → plain.
다이어그램 실패 → 오류 한 줄 + 원문 코드 블록.
