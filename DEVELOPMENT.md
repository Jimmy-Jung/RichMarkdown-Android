# RichMarkdown-Android 설계 문서

- 작성자: JunyoungJung
- 작성일: 2026-09-15 (KST)
- 상태: 골격 생성 단계(P0 전)

이 문서는 2026-09-15 아키텍처 대화(architecture-dialogue)에서 확정한 결정 세트다. iOS
[RichMarkdown](https://github.com/Jimmy-Jung/RichMarkdown)의 `DEVELOPMENT.md`에 대응하며,
결정 ID(D0~D9, D3a)는 대화의 ledger를 그대로 유지한다. 결정을 바꿀 때는 해당 ID를
`superseded`로 표시하고 후속 ID를 새로 만든다 — 이력을 선형으로 다시 쓰지 않는다.

## 0. 범위 한 문장

iOS `RichMarkdown`과 **동일한 렌더 계약**을 제공하는 동일 명칭의 Android 오픈소스 라이브러리.
LLM 채팅 메시지의 Markdown + GFM 표 + 인라인/블록 LaTeX + 코드 블록을 WebView 없이
네이티브로 렌더하고, 스트리밍 입력(누적 전체 문자열)에 latest-wins로 반응한다.

## 1. 근거 요약

### 확인된 사실 (Observed, 2026-09-15 실측)

- 동일 기능(네이티브 MD+GFM 표, `\(`·`\[` 수식 baseline 정렬, 스트리밍 미닫힘 억제, 코드
  하이라이트·Mermaid, Compose·View)을 한 패키지로 제공하는 Android 라이브러리는 없다.
  `RichMarkdown` 명칭은 Kotlin/Java GitHub 저장소·Maven Central(`io.github.jimmy-jung`)에서 미점유.
- 가장 가까운 후보 [huarangmeng/Markdown](https://github.com/huarangmeng/Markdown)(51★, 1인 유지)은
  `$`·`$$`만 파싱(`MathBlockStarter.kt`), 미닫힘 마크를 **덧붙여 닫음**(`InlineAutoCloser.kt`
  `buildRepairSuffix`), 렌더러가 Coil3·Ktor3·diagram-render를 `implementation`으로 강제 —
  iOS 계약(`\(`·`\[` 기본, opener 숨김, 코어는 WebView·JS·이미지 로더 미링크)과 상반.
- iOS 코어 계약(`README.md`·`DEVELOPMENT.md`): 2-pass 길이 보존 mask, hard/soft barrier,
  fail-open 표, latest-wins coalescing(실행 1 + 대기 1), 요소 단위 테마, 링크 scheme allowlist.
- iOS `RichMarkdownUIView.swift` 1332줄 vs `RichMarkdownView.swift` 647줄 — UIKit 네이티브
  렌더러는 셀 재사용·in-place 스트리밍 갱신 실측 근거가 쌓인 구현이다(iOS §5).
- 의존성 AAR `AndroidManifest.xml` 실측 minSdk: compose-ui 1.10.0 = 23, quickjs-kt 1.0.15 = 23,
  androidx.webkit 1.17.0 = 24, ratex-android 0.1.14 = 21.
- commonmark-java 0.30.0 코어는 `java.util.List.of`를 사용한다(`InlineParserImpl.java` 8곳,
  0.24.0부터). Android는 API 30부터 제공. `desugar_jdk_libs`(jdk11 계열)에
  `ImmutableCollections.java`가 있어 소비 앱이 core library desugaring을 켜면 동작한다.
  upstream은 2025-03 PR #369로 Android 비호환 API(`requireNonNullElseGet`)를 제거한 선례가 있다.

### 추론 (Inferred)

- 기성품 부재의 간접 증거: LLM 앱 rikkahub(7,624★)가 JetBrains/markdown 포크 + jlatexmath 포크를
  직접 조합해 쓴다.

### 미확인 (Unknown) → 검증 방법

| 항목 | 검증 |
|---|---|
| RaTeX Android에서 `\text{한글}`·CJK 렌더 (typeface 없으면 glyph skip) | P0 spike ① |
| RaTeX JNI 첫 로드·parse 지연 | P0 spike ② (iOS §7 P0 측정 방식) |
| `libratex_ffi.so` 16 KB 페이지 정렬 | P0 spike ③ |
| Compose `SelectionContainer` + 인라인 코드 칩 공존 | P1 렌더러 UI 테스트 |
| commonmark Android lint(`:app:lint`, minSdk 19)가 `List.of`를 잡지 않는 이유 | P0에서 API 24 에뮬레이터 실행 |
| quickjs-kt가 Kotlin 2.4.10·AGP 9.4로 빌드됨 → 소비자 Kotlin 최소 버전(metadata n-1) | P0 |
| 스트리밍 tick 전체 재파싱 비용 | P0 실측 |

## 2. 결정 ledger

| ID | 주제 | 상태 | 선택 | 근거 | 영향 | 근거 위치 |
|---|---|---|---|---|---|---|
| D0 | 구축 전략 | confirmed | 신규 라이브러리. iOS 설계·fixture 이식, 파서·수식 엔진은 의존성 | huarangmeng 계약 상반(구분자 기본값·미닫힘 정책·코어 의존성 경계), 명칭 확보 불가 | D1~D9 | huarangmeng master 실측; iOS DEVELOPMENT.md §1·§2 |
| D1 | v1 범위 | confirmed | B: 코어 + Highlight + Mermaid. BlockEditor 비목표 | iOS 0.7.1 렌더 측 전체 동등 요구 | D4 필수, D6·D7 신설 | iOS Package.swift products; Docs/CODE_BLOCK_EXTENSIONS.md |
| D2 | 플랫폼 타겟 | confirmed | Android 전용 + 순수 JVM 코어 모듈(`richmarkdown-core`, `@InternalRichMarkdownApi` opt-in) | 목표 Android, iOS는 Swift가 source of truth, WebView·View는 Android 전용, 전환 비용은 D3·D4 의존성이 결정 | D3·D4·D6 후보 전부 열림, 코어 fixture는 JVM 테스트 | 의존성 build.gradle.kts 타겟 실측; iOS §8 |
| D3 | Markdown 파서 | confirmed | commonmark-java 0.30.0 + `ext-gfm-tables`·`ext-gfm-strikethrough`, `IncludeSourceSpans.BLOCKS_AND_INLINES` | iOS swift-markdown = cmark 계열 → 블록 구조 fixture 일치, spec 스위트, `SourceSpan.inputIndex`. 기각: JetBrains/markdown(계열 불일치·spec 통과율 미확인), vendoring(유지 비용) | 코어 JVM 전용, mask 단위 UTF-16 code unit, D3a | `SourceSpan.java` inputIndex; README Java 11·Android; `InlineParserImpl.java` List.of |
| D3a | commonmark Android 호환 대응 | confirmed (split-from D3) | ① README·Gradle 안내에 coreLibraryDesugaring 필수 ② 파서 호출 경계에서 `NoSuchMethodError`/`NoClassDefFoundError`를 잡아 원문 fail-open + 로그 ③ upstream PR(List.of 치환) 계획 | `List.of` = API 30, desugar_jdk_libs jdk11 지원, PR #369 선례 | 소비 앱 요건 1개, fail-open 경로 1개. 외부 PR은 별도 승인 후 실행. upstream 머지 시 ①·② 제거 | desugar_jdk_libs `jdk11/.../ImmutableCollections.java`; commonmark `ci.yml` lint only |
| D4 | 수식 엔진 | **provisional** | RaTeX `io.github.erweixin:ratex-android:0.1.14`. 호출은 `MathRenderService` 한 파일에 격리, 공개 provider API 없음 | `RaTeXEngine.parse()` → `DisplayList(width,height,depth)`, `RaTeXRenderer.draw(canvas)` + `heightPx`/`depthPx` → Compose·View 공용 Bitmap/Canvas(iOS SwiftMath `asImage()`+`LayoutInfo` 동형). latex-renderer는 `LatexExporterState`가 composition에서만 생성. jlatexmath-android는 archived·GPL-2.0 탈락 | 캐시 키 = latex + fontSizePx + color + displayMode, ABI split, `mathFont` 테마 단일(KaTeX), `.so` 크래시 표면 | `platforms/android/src/main/kotlin/io/ratex/*.kt`; RaTeX issues #55·#56·#82 |
| D5 | UI 표면 | confirmed | Compose `RichMarkdown()` + 네이티브 `RichMarkdownView`(ViewGroup + TextView/Spannable). 파서·렌더 모델·수식 raster·캐시 공유 | D1 전체 동등, iOS UIKit 채택 사유(SwiftUI 선택·칩 충돌, 셀 재사용 성능 실측) | D4는 Canvas/Bitmap + metrics 출력 엔진만, D7 뷰 2종, 렌더러 작업량 2배 | iOS §5 UIKit 네이티브 렌더러; README UIKit 절 |
| D6 | 하이라이트 엔진 | confirmed | quickjs-kt 1.0.15 + iOS 동일 Prism 1.30.0 번들·`native-tokenize.js` 공유. 바인딩 호출 한 파일 격리(Zipline·quickjs-wrapper로 교체 가능) | 엔진 동일 → fixture·별칭·역할 매핑 iOS 이식. `prism-core.js` ES5, `document` 없으면 DOM 경로 우회(L1143), 클래식 스크립트 evaluate. JS 문자열 UTF-16 = `NSRange` 동형 | `.so` ×4 ABI(AAR 1.83 MB, 16 KB 정렬 실측 OK), opt-in 모듈 | `prism-core.js` L3-9·L1143·L1207; quickjs-kt AAR 실측 |
| D7 | Mermaid 경로 | confirmed | Android WebView + iOS 번들(`mermaid.bundle.js`·`index.html`) 공유. `WebViewAssetLoader`, 메시지 브리지, 한계값 iOS 상속 | Mermaid는 DOM 측정 의존 → headless JS 불가. 네이티브 대안 전부 초기 단계. 공식 엔진 유지가 iOS 결정의 핵심 | assets 3.45 MB(모듈 채택 앱만), androidx.webkit 의존, WebView 부재 시 원문 fail-open, Compose는 `AndroidView` 래핑 | iOS `WebAssets/index.html`; Docs §3.3·§3.5·§5.2 |
| D8 | 저장소·패키징·명칭 | confirmed | 별도 저장소 `Jimmy-Jung/RichMarkdown-Android`(로컬 `/Users/jimmy/Documents/GitHub/RichMarkdown-Android`). Maven `io.github.jimmy-jung:richmarkdown{,-core,-highlight,-mermaid}`. Kotlin 패키지 `io.github.jimmyjung.richmarkdown`. 독립 semver 0.1.0. MIT. 공유 자산은 `scripts/sync-ios-assets.sh`로 복사 + iOS Docs SHA-256 표 대조 | 사용자 경로 지정, 네임스페이스·저장소명 미점유(repo1 404, gh 404) | 자산 복제 + 동기화 스크립트, CI 분리, README에 iOS 계약 버전 대응표 | 디스크 폴더 존재 확인 |
| D9 | minSdk | confirmed | 24, 전 모듈 동일 | 의존성 최고 floor(androidx.webkit 1.17.0). 배포 분포 자료 없음 → 시장 점유로 정하지 않음. 30 미만 어떤 값도 D3a 요건을 없애지 못함 | Gradle `defaultConfig.minSdk`, 지원 matrix 한 줄, CI 에뮬레이터 API 24 | AAR AndroidManifest 실측 4건 |

D4 확정 조건: P0 spike ①②③ 전부 통과. ① 실패 시 `typefaceLoader`에 시스템 폰트 폴백
(RaTeX-CMP의 `getPlatformTypeFace(font, codePoint)` 패턴)이 가능한지 검토한 뒤 재제시.
실패하고 우회도 안 되면 latex-renderer로 돌아와 다시 결정한다.

## 3. iOS에서 그대로 옮기는 계약 (결정 불필요 — 근거는 "동일 계약")

- 수식 문법: `\(`·`\[` 기본, `$`·`$$`는 opt-in(`DollarMathOptions.Single`), 문장 안 `$$`는
  `InlineDouble`일 때만. 공백·숫자·줄바꿈 규칙은 iOS README «수식 문법» 그대로.
- 2-pass 파이프라인: 입력 상한 → 1차 파싱(hard barrier: 코드·HTML, soft range: 링크·이미지,
  paragraph 범위) → 원문 수식 스캔 → **길이 보존 mask** → 2차 파싱 → 수식 자리를 원문 slice로 복원.
- fail-open 표: 잘못된 LaTeX는 구분자 포함 원문, 중첩 구분자는 구간 전체 원문, 미지원 노드는
  plain text, 이미지는 alt만, HTML은 문자 그대로, 상한 초과는 bounded prefix + 생략 marker.
- 스트리밍: 최신 전체 문자열 입력, latest-wins coalescing(실행 1 + 대기 1), 100 ms 버퍼(trailing
  게시), 미닫힌 opener 숨김, 꼬리 페이드 12 grapheme(마지막 alpha 0.2), 표·코드·수식 블록이
  마지막이면 페이드 없음.
- 테마: 요소 단위(색 8·폰트 7·수식 서체). 문자 구간 단위 지정 없음. 폰트는 값 타입이라 렌더
  요청 키에 들어간다.
- 링크: `https`·`http`·`mailto`만 자동 링크. 나머지는 plain text.
- 접근성: 수식은 `"수식: <원본 LaTeX>"`로 읽힘, 링크 문단은 개별 link semantics 유지.
- 입력 상한(iOS `InputLimits`): 원문 256 KiB, 표시 prefix 64 KiB, block quote 깊이 64,
  수식 source 4 KiB, 표 32열·512셀. iOS와 같이 잠정값이며 공개 API로 고정하지 않는다.

## 4. Android 치환표

| iOS | Android | 비고 |
|---|---|---|
| Dynamic Type(`UIFontMetrics`, `@ScaledMetric`) | `fontScale` × sp | 수식은 scaled px로 다시 raster |
| 44 pt hit target | 48 dp | Material 접근성 기준 |
| VoiceOver label | TalkBack `contentDescription` | 문단 라벨 덮어쓰기 금지 규칙 동일 |
| UTF-8 byte mask (`UTF8LineMap`) | UTF-16 code unit mask (`Utf16Range`) | commonmark `SourceSpan.inputIndex`가 UTF-16 |
| `NSRange` 하이라이트 범위 | `Utf16Range` | JS 문자열도 UTF-16이라 Prism 출력 의미 동일 |
| `NSTextAttachment` 인라인 수식 | `ReplacementSpan`(View) / `InlineTextContent`(Compose) | baseline = `heightPx`·`depthPx` |
| `UIHostingConfiguration` | `ComposeView` 호스팅 | View 앱에서 Compose 렌더러 사용 시 |
| `WKProcessPool` 공유 | 해당 없음 | Android WebView는 앱 내 렌더러 프로세스 공유 |
| `callAsyncJavaScript` | `evaluateJavascript` + `WebMessageListener` 브리지 | 원문은 인자로만 전달 |
| `-apple-system` 폰트 | `sans-serif` | Mermaid `index.html` 차이 |
| `LatexMathFont` 12종 | 단일(KaTeX) | RaTeX·latex-renderer 모두 KaTeX 폰트 고정. 문서화된 플랫폼 차이 |

## 5. 모듈 구조

```
RichMarkdown-Android/
├─ richmarkdown-core/        kotlin("jvm"). 파싱·mask·수식 스캔·스트리밍 tail·입력 상한·coalescing
├─ richmarkdown/             com.android.library. 렌더 모델·MathRenderService(RaTeX 격리)·테마/폰트 값 타입·
│                            Compose RichMarkdown()·View RichMarkdownView·코드 블록 확장 인터페이스
├─ richmarkdown-highlight/   opt-in. quickjs-kt + assets/prism/ (iOS 22파일 복사)
├─ richmarkdown-mermaid/     opt-in. androidx.webkit + assets/mermaid/ (iOS 3파일 복사)
├─ scripts/sync-ios-assets.sh   ../RichMarkdown 자산 복사 + Docs SHA-256 대조
└─ DEVELOPMENT.md · README.md · LICENSE · THIRD_PARTY_NOTICES.md · CHANGELOG.md
```

| 모듈 | Maven 좌표 | iOS 대응 |
|---|---|---|
| `richmarkdown-core` | `io.github.jimmy-jung:richmarkdown-core` | `RichMarkdownCore`(비공개 target) — Maven에는 비공개가 없어 `@InternalRichMarkdownApi`로 표시 |
| `richmarkdown` | `io.github.jimmy-jung:richmarkdown` | `RichMarkdown` |
| `richmarkdown-highlight` | `io.github.jimmy-jung:richmarkdown-highlight` | `RichMarkdownHighlight` |
| `richmarkdown-mermaid` | `io.github.jimmy-jung:richmarkdown-mermaid` | `RichMarkdownMermaid` |

빌드 산출물은 `-Prichmarkdown.buildRoot=<외부 경로>`로 저장소 밖에 둘 수 있다(루트 `build.gradle.kts`).

## 6. 의존성 (2026-09-15 실측, `gradle/libs.versions.toml`)

| 의존성 | 버전 | 역할 | minSdk floor | 라이선스 |
|---|---|---|---|---|
| Android Gradle Plugin | 9.4.0 | 빌드(Gradle ≥ 9.6.0, JDK ≥ 17, Kotlin 내장) | — | — |
| Kotlin | 2.4.20 | 언어 | — | Apache-2.0 |
| compileSdk / minSdk | 37 / 24 | — | — | — |
| `org.commonmark:commonmark` + `-ext-gfm-tables` + `-ext-gfm-strikethrough` | 0.30.0 | 파서 (D3) | API 30 미만은 desugaring 필요 (D3a) | BSD-2-Clause |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core/-android/-test` | 1.11.0 | 비동기 | — | Apache-2.0 |
| `io.github.erweixin:ratex-android` | 0.1.14 | 수식 엔진 (D4, provisional) | 21 | MIT (KaTeX 폰트 SIL OFL 1.1) |
| `io.github.dokar3:quickjs-kt` | 1.0.15 | Prism 실행 (D6) | 23 | Apache-2.0 (QuickJS MIT) |
| `androidx.webkit:webkit` | 1.17.0 | Mermaid WebView (D7) | 24 | Apache-2.0 |
| `androidx.compose:compose-bom` | 2026.09.00 | Compose 렌더러 (compose-ui 1.10.0) | 23 | Apache-2.0 |
| `androidx.core:core-ktx` | 1.19.0 | — | — | Apache-2.0 |
| `androidx.activity:activity-compose` | 1.13.0 | 데모(P1) | — | Apache-2.0 |
| `org.junit.jupiter:junit-jupiter` | 6.1.3 | 코어 테스트 | — | EPL-2.0 (미확인 — 테스트 전용) |

D3a 소비 앱 요건(API 30 미만 지원 시):

```kotlin
android {
    compileOptions { isCoreLibraryDesugaringEnabled = true }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

## 7. 진행 게이트 (iOS §7 대응)

- **P0 spike**: D4 3건(`\text{한글}`, JNI 지연, 16 KB 정렬) + QuickJS에서 Prism 22파일 로드·
  `nativeTokenize` 동작 + WebView `renderDiagram` 동작 + commonmark desugaring 동작(API 24
  에뮬레이터) + `MathScanner`·`MaskRoundTrip`·`StreamingTail` fixture 이식 JVM 통과.
- **P1**: 코어 + Compose + View 렌더러, SSE 스트리밍 데모(Compose·View 각 1화면), API 24
  에뮬레이터 instrumented 테스트.
- **P2**: Highlight·Mermaid 모듈, 자산 동기화 스크립트 CI 검증, Maven Central 발행.

Definition of Done은 iOS fixture와 같은 입력에서 같은 블록 구조·수식 span·스트리밍 표시를
내는 것이다. 수치 성능 목표는 P0 실측 뒤에 근거와 함께 추가한다.
