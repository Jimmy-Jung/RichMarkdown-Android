# richmarkdown 명세

기준일: 2026-10-07 · 현재 소스 확인 · Android 실행 성공은 별도

[모듈 선언](../../../richmarkdown/build.gradle.kts)과 [버전 선언](../../../gradle/libs.versions.toml)은 Android의 최소 지원 API(minSdk)를 30, 컴파일 기준 API(compileSdk)를 37로 정합니다. Markdown·수식 인식은 [core 명세](richmarkdown-core.md)를 따릅니다. API와 근거 파일은 [소스 디렉터리](../../../richmarkdown/src/main/)에서 확인할 수 있습니다.

구문 분석(parse)은 원문을 문단·표·수식 같은 구조로 읽는 처리입니다. 이미 만든 결과를 재사용하는 저장소를 캐시(cache), 같은 결과를 찾는 비교값을 키(key)라고 합니다. 아래 요구 사항은 현재 소스의 동작을 설명하며, 실제 실행 통과는 [검수 기록](../validation.md)과 구분합니다.

## 공개 진입점

| API | 입력·기본값·책임 |
| --- | --- |
| `RichMarkdown` composable | Compose에서 표시하는 함수입니다. `markdown`, `modifier`, `dollarMath = None`, `theme = Default`, `streaming = null`, `codeBlocks = None`을 받습니다. `isDarkTheme`은 기본으로 시스템 설정을 따르며 `onOpenLink`는 null일 수 있습니다. |
| `RichMarkdownView` | Android LinearLayout을 직접 구성합니다. `markdown`·`dollarMath`·`theme`·`streaming`·`codeBlocks`를 받으며 `isDarkTheme`·`onOpenLink`·`onContentSizeChange`는 null일 수 있습니다. |
| `RichMarkdownTheme` | 요소별 색·본문/제목/코드 폰트와 독립 블록 수식의 정렬인 `equationAlignment`를 지정합니다. 수식 크기는 `bodyFont`를 따르고 수식 서체는 엔진이 자동으로 선택합니다. |
| `RichMarkdownFont`, `RichMarkdownColor` | 사용자 글자 크기 설정을 반영하는 sp 크기·서체 디자인(design)·굵기(weight), 라이트/다크 색 값(ARGB: 투명도·빨강·초록·파랑)을 지정합니다. |
| `LatexDollarMathOptions` | 달러 수식 옵션입니다. `None`, `Single`, `InlineDouble`을 `plus`로 조합합니다. |
| `RichMarkdownStreamingOptions` | 끝부분에서 점차 나타낼 글자 수인 `tailFadeGraphemeCount = 12`, 아직 닫히지 않은 서식 기호를 숨기는 `hidesUnclosedInlineMarks = true`가 기본값입니다. grapheme은 사용자가 한 글자로 보는 단위입니다. |
| `RichMarkdownStreamingTextBuffer` | 메인 스레드의 코루틴 작업 범위(main scope)를 받습니다. 기본 간격(interval)은 100ms이며 `text: StateFlow`로 최신 값을 전달하고 `submit/append/flush/reset`을 제공합니다. |
| `RichMarkdownCodeBlockOptions` | 코드 색 공급자·다이어그램 공급자인 `highlighter/diagram`은 null일 수 있습니다. 같은 값을 가진 객체인지가 아니라 같은 구현 객체인지 참조로 비교합니다. |
| `RichMarkdownSyntaxHighlighting` | 비동기 `suspend spans(code, language)`로 원문의 UTF-16 범위와 일곱 색 역할을 반환합니다. |
| `RichMarkdownDiagramRendering` | 담당 언어인 소문자 `languages`, 메인 스레드의 `createView`, Compose의 `Content`를 제공합니다. View 크기가 바뀌면 콜백을 호출해야 합니다. `isDark` 인자를 추가한 함수(overload)는 기본으로 기존 함수를 호출합니다. `disposeView`는 영구 제거되는 뷰와 하위 뷰(subtree)마다 호출하며 공급자 소유 View만 해제합니다. 기본 구현은 아무 동작도 하지 않습니다(no-op). |

앱이 구현한 코드 색 공급자(custom highlighter)의 `RichMarkdownHighlightSpan.range`는 Core의 `Utf16Range`입니다. UTF-16은 위치·길이를 16비트 단위로 계산하므로 화면 글자 수와 다르며, 예를 들어 `😀`는 두 단위입니다.

이 타입은 `InternalRichMarkdownApi`의 WARNING 수준 경고를 내는 내부 API이므로, 공급자는 `@OptIn(InternalRichMarkdownApi::class)`로 사용 의도를 명시해야 합니다. 이런 명시적 사용 선택을 opt-in이라고 합니다. 내부 범위 타입은 마이너(minor) 버전에서도 변경될 수 있으며, 안정된 공개 구문 트리(AST) 계약으로 취급하지 않습니다.
근거는 [Core 소스](../../../richmarkdown-core/src/main/)의 `InternalRichMarkdownApi.kt`와
[Renderer 소스](../../../richmarkdown/src/main/)의 `RichMarkdownCodeBlockOptions.kt`입니다.

`RichMarkdownRenderModel`, `MathRenderService`와 결과 타입도 Kotlin에서 접근할 수 있지만, 구문 분석 결과 타입은 Core의 내부 opt-in API입니다. `Request`·`BoundedInput`을 직접 만들어도 모델 `submit`과 Core `parse`가 입력을 다시 제한합니다. 이전 입력 경로(ingress)에서 잘렸다는 flag는 OR 연산으로 유지하며 호출자가 보관한 원래 Request 자체는 수정하지 않습니다.

0.2.0에서는 `LatexMathFont`와 `RichMarkdownTheme.mathFont`, `Request.mathFont`, `MathRenderKey.mathFont` 및
생성 함수의 해당 인자를 제거했습니다. 0.1.0을 사용한 앱은 `mathFont` 인자를 삭제합니다.
이전 설정을 보존하는 별칭 API는 제공하지 않습니다. `fontSizePx`와 내부 `mathFontSizePx`는
크기를 전달하는 값이므로 유지합니다. RaTeX의 KaTeX 기반 글꼴 자동 선택, 크기·색·정렬 규칙은 유지합니다.

## 렌더와 비동기 요구

| ID | 요구와 현재 구현 | 근거·테스트 선언 |
| --- | --- | --- |
| AR-01 | 두 UI는 크기 제한 규칙에 맞게 정리한 원문(bounded canonical)을 요청·대체 표시(fallback)에 사용합니다. View 프로퍼티를 읽는 getter도 제한 원문을 반환하며 모델은 직접 만든 Request의 큰 원문도 상태·캐시·작업 처리기(worker)에 저장하기 전에 제한합니다. | `RichMarkdown.kt`, `RichMarkdownView.kt`, `RichMarkdownRenderModel.kt`; `directRequestsBoundFallbackBeforeParsingAndPreserveTruncation` |
| AR-02 | `submit`은 메인 스레드(main thread)만 허용하며 같은 Request는 다시 반영하지 않습니다. CPU 구문 분석·비트맵 생성(raster)은 `Dispatchers.Default` 작업 문맥에서 수행합니다. | `RichMarkdownRenderModel.kt` |
| AR-03 | 다른 메시지는 모델의 이전 문서·이미지를 지우고 최신 원문으로 바꿉니다. 같은 분석 설정에서 잘리지 않은 원문 뒤에 텍스트만 덧붙이면(append), 이전 원문 전체를 앞부분(prefix)으로 갖는 문서를 유지합니다. | `submit`; prefix JVM 테스트 |
| AR-04 | 수식 색·크기·독립 블록용 비트맵(display raster) 설정만 바뀌면 문서는 유지하고 이전 이미지는 사용하지 않습니다. 수식 서체는 설정 비교 항목이 아닙니다. | `matchesRasterConfiguration` 테스트 |
| AR-05 | 실행 1 + 최신 대기 1을 유지하며 현재 요청 순서 번호(generation)의 문서·최종 이미지와 새 ParseCache 결과만 반영합니다. 이미 시작한 비트맵 결과의 수식 내용 기반 캐시(content cache) 저장은 이 반영 조건(gate)과 별개입니다. | 모델과 core worker; core 동시성 테스트 |
| AR-06 | 준비된 캐시 이미지와 문서를 먼저 반영하고, 없는 수식을 원문으로 보여 준 뒤 이미지가 완성되면 채웁니다(hydration). 필요한 결과가 모두 캐시에 있으면(cache hit) 초기 문서 반영 한 번으로 끝납니다. | `runJob` |
| AR-07 | 구문 분석 중 던져진 오류(Throwable)는 크기를 제한한 원문 표시로 대신 처리하고 실패 로그는 프로세스당 한 번 남깁니다. RaTeX 분석 실패·상한 초과 결과는 null이며 구분자 포함 수식 원문을 표시합니다. | `parseOrNull`, `MathRenderService`; `invalidLatexFailsOpen` |
| AR-08 | View는 화면에 연결된(attach) 동안 상태(state)를 수집하고 분리(detach)되면 수집만 취소합니다. 표시 작업의 scope·모델은 뷰 수명 동안 보관합니다. | `RichMarkdownView.kt` |
| AR-09 | 같은 외형(appearance)과 블록 값은 재사용합니다. 스트리밍에서는 구조가 같은 블록의 내용을 기존 뷰에서 갱신(in-place)하고, 앞부분 뷰가 같은 순서로 연결되어 있으면 그 뒤만 다시 붙입니다. | `IncrementalRebuild.kt`, `BlockViewBuilder.kt` |

AR-05는 모델의 결과 반영 규칙입니다. Compose 내부의 코드 색·윤곽선 수식(vector)은 별도로 `remember` 상태를 요청마다 분리합니다. 코드는 원문·언어·확장 구현 객체 참조를, 블록 수식은 생성 조건(render key)과 배치 공급자(layout loader)를 비교합니다.

비교값이 바뀌는 즉시 기본 색 코드(plain)/수식 원문(source)으로 시작하며 이전 작업은 이전 상태만 갱신할 수 있습니다. 모델의 요청 순서 번호를 화면 내부 결과의 반영 조건으로 사용하는 방식은 아닙니다. 최신 결과만 반영한다는 규칙이 이미 시작한 동기 계산의 즉시 중단까지 보장하지도 않습니다.

## 수식과 캐시 상한

| ID | 요구·상한 | 근거·테스트 선언 |
| --- | --- | --- |
| AR-10 | LaTeX 원문은 UTF-8 기준 4,096바이트, 폰트는 유한한 1~1,024 px입니다. 원문 바이트 수를 예상 픽셀 폭으로 바꾸어 제한하지 않습니다. | `RasterInputLimits`; preflight JVM 테스트 |
| AR-11 | 분석 후 비트맵·윤곽선의 공통 생성 지점에서 각 변 8,192 px, 크기를 올림(ceil)한 면적 4,194,304픽셀까지 허용합니다. | `newRenderer`; `actualLayoutBoundsApplyToRasterAndVector` |
| AR-12 | 비트맵 캐시는 LaTeX 원문·실제 px 크기·ARGB 색·블록 수식 여부를 키로 사용하고 `bitmap.byteCount`로 64 MiB를 제한합니다. `trimMemory()`는 이미지를 비웁니다. 앱의 메모리 부족 콜백(memory callback) 등록은 자동으로 제공하지 않습니다. | `MathRenderService`; `cacheHitReturnsSameInstance` |
| AR-13 | ParseCache는 오래 사용하지 않은 항목부터 제거하는 LRU 방식이며 추정 비용 상한은 16 MiB입니다. 원문·달러 수식 옵션·잘림을 키에 넣고 원문 뒤에 덧붙이는 스트리밍 결과는 새로 저장하지 않습니다. | `ParseCache.kt`; cache JVM 테스트 |
| AR-14 | 두 UI의 독립 블록 수식은 선·글자 모양을 직접 그리는 윤곽선 표현(vector)입니다. 수식이 화면의 표시 영역(viewport)보다 좁을 때 정렬을 적용하고, 넓으면 가로 스크롤합니다. | `BlockMathView.kt`, `BlockViewBuilder.kt`; `displayLayoutDrawsPixels` |
| AR-15 | 본문 내 수식(inline)은 글자 기준선(baseline) 위아래 높이(ascent/descent)로 위치를 맞춥니다. 준비 전·실패 시에는 구분자 포함 원문인 `source`를 codeFont로 표시합니다. | `InlineSpans.kt`, `InlineText.kt`; raster metric 테스트 |

상한은 내부 구현 값이며 공개 API 고정값이나 메모리 측정값이 아닙니다. 비트맵 캐시에 여러 스레드가 안전하게 접근할 수 있다(thread safety)는 사실이 같은 키의 동시 중복 생성까지 막는다는 뜻은 아닙니다. 윤곽선 결과는 캐시하지 않습니다.

## 표시·확장·접근성

| ID | 요구와 현재 구현 | 근거 |
| --- | --- | --- |
| AR-16 | 코드 블록은 언어 라벨·원문 복사·가로 스크롤과 고정폭 글꼴(monospace)을 제공합니다. 다이어그램 공급자가 담당하는 언어면 본문만 교체합니다. 두 UI 모두 코드 색의 빈 범위·원문 밖 범위·겹침을 무시하며 원문은 바꾸지 않고, 미지원 시 기본 색 코드를 표시합니다. | `CodeBlockView.kt`, `BlockViewBuilder.kt`; `customRangesOutsideSourceAndOverlapsAreIgnoredWithoutChangingCode` |
| AR-17 | 주소의 앞부분이 허용한 종류(scheme)인 링크만 엽니다. 앱 지정 콜백이 없으면 Compose URI handler 또는 View ACTION_VIEW를 사용합니다. 링크는 밑줄로도 구분합니다. | core `LinkPolicy`, inline 렌더러 |
| AR-18 | 테마 색·폰트는 요소별로 지정합니다. 숫자가 아닌 값(NaN)·무한대·0 이하의 명시 폰트 크기는 기본값으로 복구합니다. 수식 서체는 RaTeX가 자동으로 선택하며 별도 선택 API와 문자 범위별 앱 지정 스타일 API는 없습니다. | `RichMarkdownFont.kt`, `RichMarkdownTheme.kt`, `MathRenderService.kt` |
| AR-19 | `streaming = null`은 일반 표시입니다. 점차 나타내는 효과(fade)의 글자 수가 음수이면 0으로 보정하며 마지막 하위 문단의 표시만 바꿉니다. 끝 블록이 표·코드·수식이면 페이드하지 않습니다. | streaming 옵션·표시 파일과 core tail 테스트 |
| AR-20 | 버퍼의 첫 값·간격이 지난 값은 즉시 전달하고, 간격 안의 값은 최신 하나로 모아 간격 끝에 전달(trailing)합니다. flush는 남은 값을 전달하고 reset은 새 스트림을 시작합니다. 메인 스레드·메인 scope 사용은 호출자가 지켜야 합니다. | buffer; `streamingBuffer_latestWinsWithTrailingPublish` |
| AR-21 | 수식 대체 콘텐츠는 LaTeX 음성 라벨, 제목은 제목임을 알리는 접근성 정보(heading semantics)를 제공합니다. 코드·수식 복사의 터치 영역(hit target)은 48dp입니다. 링크가 있는 문단 라벨은 개별 링크 정보를 덮지 않습니다. | inline·block 렌더러 |
| AR-22 | View 블록을 영구 교체할 때는 이전 다이어그램 공급자에게 제거할 뷰와 하위 뷰의 해제를 요청합니다. 원문 대체 표시·같은 최상위 뷰(root) 재사용·일시 분리는 해제하지 않습니다. 공급자가 바뀌어도 이전 공급자를 호출합니다. | `IncrementalRebuild.kt`; `DiagramViewDisposalTest` |

수식 크기는 bodyFont에 사용자의 글자 크기 배율(fontScale)·화면 밀도(density)를 반영한 픽셀(px) 값으로 정합니다. `bodyFont`의 서체 디자인·굵기를 바꾸어도 수식 서체를 선택하지는 않습니다. Android Rounded 글자 디자인은 Default와 같고 Serif는 Android 확장입니다. 두 UI는 부모가 정한 다크 모드 값을 다이어그램의 새 overload로 전달합니다.

기존 함수만 구현한 앱 지정 공급자도 기본 연결로 계속 호출됩니다. 명시적 다크 모드를 반영하려면 `isDark` 인자를 추가한 함수를 구현해야 합니다. 이 호환 연결이 기존 공급자에도 다크 모드 처리를 자동으로 추가하는 것은 아닙니다.

## 근거와 실행 확인

[JVM 테스트](../../../richmarkdown/src/test/)는 원문 덧붙이기·Request 설정·상한·ParseCache·버퍼를 다룹니다. [Android 테스트](../../../richmarkdown/src/androidTest/)는 실제 RaTeX, 모델의 메인 스레드 반영·원문 덧붙이기 후 수식 채우기·scope 취소, Compose 결과 교체, View의 화면 연결/분리 재사용·크기 콜백·사용자 지정 색 범위를 검사합니다.

이번 문서 보완에서는 테스트를 새로 실행하지 않았습니다. 기존 코드 대조와 실행 성공을 구분하며, 실제 실행 상태와 TalkBack·성능 등 미검증 범위는 [검수 기록](../validation.md)에 있습니다.

2026-10-07 수식 서체 API 삭제는 소스에 반영했으며, 이 문서만으로 삭제 후 빌드·회귀 통과를
선언하지 않습니다. 해당 변경의 실행 검수는 [AR-I05](../improvements/richmarkdown.md#ar-i05-효과가-없는-수식-서체-선택-api-제거)와 검수 기록에서 구분합니다.
