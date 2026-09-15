# RichMarkdown (Android)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-minSdk%2024-3DDC84.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-0.1.0%20%EA%B0%9C%EB%B0%9C%20%EC%A4%91-yellow.svg)](CHANGELOG.md)

iOS [RichMarkdown](https://github.com/Jimmy-Jung/RichMarkdown)과 **동일한 렌더 계약**을 제공하는
Android 라이브러리. LLM 채팅 메시지의 Markdown, GFM 표, 인라인/블록 LaTeX 수식, 코드 블록을
WebView 없이 네이티브로 렌더한다. Jetpack Compose는 `RichMarkdown()`, Android View는
`RichMarkdownView`를 쓴다 — 두 렌더러는 같은 파서·수식 raster·캐시를 공유한다.

```
원의 넓이는 \( A = \pi r^2 \)입니다.
            ↓
문장 흐름 안에 baseline 정렬된 수식이 포함된 네이티브 텍스트
```

- 코어는 WebView·JavaScript 런타임·이미지 로더를 링크하지 않는다. 코드 하이라이팅과 Mermaid는
  별도 opt-in 모듈이다.
- 스트리밍 입력(최신 전체 문자열)을 전제로 설계했다. coalescing + latest-wins.
- 수식 문법·실패 정책·스트리밍 표시 규칙은 iOS와 같다. 설계 결정과 근거는
  [DEVELOPMENT.md](DEVELOPMENT.md)에 있다.

## 현재 상태

**P1 렌더러·확장 모듈 구현 완료, 데모 앱 동작.** Maven Central 미발행(P2 진행). 코어 파이프라인(수식 스캔·길이 보존 mask·commonmark 어댑터·스트리밍 tail·
입력 상한·latest-wins worker)과 iOS에서 이식한 fixture 테스트가 있다. Compose·View 렌더러와
수식·하이라이트·Mermaid 엔진 연동은 P0 spike(수식 엔진 검증) 뒤에 들어간다. Maven Central
**미발행**.

## 스크린샷

`demo/` 앱의 실제 화면. Pixel 6 AVD(Android 16, API 37.1, 16 KB 페이지)에서 `scripts/capture-demo-screens.sh`로 찍었다.
같은 스크립트가 정지컷 4장과 SSE 스트리밍 GIF를 다시 만든다 (`./gradlew :demo:installDebug` 뒤 실행).

| 쇼케이스 · Compose 렌더러 | 쇼케이스 · View 렌더러 |
|---|---|
| ![Compose 렌더러: 인라인·블록 수식, 코드 하이라이팅](docs/screenshots/01-showcase-compose.png) | ![View 렌더러: 같은 문서를 RichMarkdownView로](docs/screenshots/02-showcase-view.png) |
| 한글 문장 안에 baseline 정렬된 `\( A = \pi r^2 \)`, 가로 스크롤·복사 버튼이 붙은 블록 수식, Prism 하이라이팅이 적용된 Kotlin 코드 블록 | 같은 샘플을 `RichMarkdownView`(TextView·Spannable)로 렌더. 인라인 수식 `ReplacementSpan`의 ascent/descent가 글줄 baseline에 맞는다 |

| AI 챗봇 · Compose (`LazyColumn`) | AI 챗봇 · View (`RecyclerView`) |
|---|---|
| ![Compose 채팅 버블 안의 표·코드·목록·인용](docs/screenshots/03-chat-compose.png) | ![RecyclerView 셀 안의 RichMarkdownView](docs/screenshots/04-chat-view.png) |
| 셀 안 수식이 든 GFM 표, 코드 블록 헤더(언어 라벨·복사), 번호 목록의 인라인 분수, 왼쪽 바 인용 | RecyclerView 셀마다 `RichMarkdownView`를 두고 `markdown`만 바꾼다. 표 셀 높이가 행에 맞춰 테두리가 이어진다 |

| SSE 실시간 렌더링 |
|---|
| ![SSE 프레임이 도착하는 대로 렌더되는 스트리밍 데모](docs/screenshots/05-sse-streaming.gif) |
| `text/event-stream` 조각을 `RichMarkdownStreamingTextBuffer`(100 ms latest-wins)로 합쳐 누적 문자열을 넘긴다. 마지막 문단 끝 12 grapheme이 옅어지고, 아직 닫히지 않은 `\(`·`**`·백틱 opener는 closer가 올 때까지 숨긴다. 스트리밍 append는 이전 렌더를 유지해 원문으로 되돌아가는 플래시가 없다 |

## 설치 (예정)

발행 전이다. 발행 뒤 좌표는 다음과 같다.

```kotlin
dependencies {
    implementation("io.github.jimmy-jung:richmarkdown:<version>")

    // opt-in. 필요한 것만 추가한다.
    implementation("io.github.jimmy-jung:richmarkdown-highlight:<version>") // Prism + QuickJS
    implementation("io.github.jimmy-jung:richmarkdown-mermaid:<version>")   // 공식 Mermaid + WebView
}
```

### API 30 미만을 지원하는 앱의 필수 설정

파서 의존성 commonmark-java 0.30이 `java.util.List.of`(Android API 30+)를 쓴다. `minSdk`가
30 미만인 앱은 **core library desugaring**을 켜야 한다. 켜지 않으면 API 30 미만 기기에서
파싱이 실패하고, 라이브러리는 크래시 대신 Markdown 원문을 그대로 표시한다(fail-open).

```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

이 요건은 upstream에 `List.of` 치환 PR이 머지되면 제거한다(DEVELOPMENT.md D3a).

## 사용법

### Compose

```kotlin
import io.github.jimmyjung.richmarkdown.RichMarkdown

@Composable
fun MessageBubble(markdown: String) {
    RichMarkdown(markdown = markdown)
}
```

| 파라미터 | 설명 |
|---|---|
| `dollarMath` | `LatexDollarMathOptions.None`(기본) / `Single`(`$…$`, `$$…$$` 블록) / `Single + InlineDouble`(문장 안 `$$…$$`) |
| `theme` | `RichMarkdownTheme` — 요소별 색 8종 + 폰트 7종 + `syntax` 역할 색 7종. 값 타입이라 렌더 요청 key에 들어간다 |
| `streaming` | `RichMarkdownStreamingOptions?` — 스트리밍 중인 메시지에만 건다. 끝나면 `null` |
| `codeBlocks` | `RichMarkdownCodeBlockOptions(highlighter, diagram)` — opt-in 모듈 주입. 기본 `None` |
| `isDarkTheme` | 기본 `isSystemInDarkTheme()` |
| `onOpenLink` | `null`이면 `LocalUriHandler`. 허용 scheme은 `https`·`http`·`mailto`만 |

메시지 목록의 세로 스크롤·virtualization은 소비 앱 몫이다(`LazyColumn`). 뷰는 주어진 폭을 채우므로
넓은 화면에서는 소비 앱이 읽기 폭(예: 720dp)을 제한한다 — `demo/`의 `ShowcaseActivity` 참고.

### View

```kotlin
val view = RichMarkdownView(context).apply {
    markdown = message          // setter가 입력 상한(256 KiB)을 적용한다
    dollarMath = LatexDollarMathOptions.Single
    onContentSizeChange = { /* RecyclerView 셀 self-sizing 재측정 */ }
}
```

`RichMarkdownView`는 Compose를 감싼 래퍼가 아니라 `LinearLayout` + `TextView`/`Spannable` 렌더러다.
파서·수식 raster·캐시는 Compose 렌더러와 공유한다. RecyclerView에서는 셀마다 뷰를 두고 `markdown`만 바꾼다
(`demo/`의 `ViewChatActivity`).

### 스트리밍

```kotlin
val buffer = RichMarkdownStreamingTextBuffer(scope)   // 100 ms latest-wins, trailing 게시
sseFlow.collect { chunk -> buffer.append(chunk) }      // 또는 buffer.submit(누적 전체 문자열)

val text by buffer.text.collectAsState()
RichMarkdown(markdown = text, streaming = if (isStreaming) RichMarkdownStreamingOptions.Default else null)
```

렌더러는 항상 **누적 전체 문자열**을 받는다. 스트리밍 append(이전 문자열이 새 문자열의 prefix)는 이전 렌더를
유지한 채 새 블록만 붙는다. `RichMarkdownStreamingOptions`는 마지막 문단 끝 12 grapheme 페이드와
미닫힘 `**`·백틱·`\(`(·`$`) opener 숨김을 켠다 — 파싱 결과는 바꾸지 않는다.

### 코드 블록 확장 (opt-in)

```kotlin
val codeBlocks = RichMarkdownCodeBlockOptions(
    highlighter = PrismHighlighter.shared(context),   // richmarkdown-highlight
    diagram = MermaidDiagramRenderer.shared,          // richmarkdown-mermaid
)
RichMarkdown(markdown = message, codeBlocks = codeBlocks)
```

하이라이터는 iOS와 같은 Prism 1.30.0 문법 18종을 QuickJS에서 실행하고 역할 7종(`theme.syntax`)으로 색을 입힌다.
미지원 언어·실패는 plain 코드 블록으로 되돌린다. ` ```mermaid ` 블록은 공식 Mermaid 11.17.2를 WebView에서
그린다 — 원문 20,000바이트·edge 200·높이 4,000dp 한계와 실패 시 "오류 한 줄 + 원문" 표시는 iOS와 같다.

## 지원 matrix

| 항목 | 값 |
|---|---|
| minSdk | 24 (전 모듈 동일. 근거: androidx.webkit 1.17.0 floor) |
| compileSdk | 37 (Compose BOM 2026.09.00의 compose 1.12.1이 37 이상 요구, AAR 메타데이터 검사 실측) |
| 빌드 | AGP 9.4.0, Gradle 9.6.0, Kotlin 2.4.20, JDK 17+ |
| 파서 | commonmark-java 0.30.0 (+ gfm-tables, gfm-strikethrough) |
| 수식 엔진 | RaTeX 0.1.14 (provisional — P0 spike 후 확정) |
| 하이라이트 | Prism 1.30.0 + quickjs-kt 1.0.15 (opt-in) |
| 다이어그램 | Mermaid 11.17.2 + Android WebView (opt-in) |

## iOS 계약 대응

| Android | iOS | 비고 |
|---|---|---|
| 0.1.0 (개발 중) | 0.7.1 | 수식 문법·fail-open·스트리밍 규칙 동일. 수식 폰트는 KaTeX 단일(iOS 12종과 다름) |

공유 자산(Prism 번들 22파일, Mermaid 번들 3파일)은 `scripts/sync-ios-assets.sh`가 iOS 저장소에서
복사하고 iOS `Docs/CODE_BLOCK_EXTENSIONS.md`의 SHA-256 표와 대조한다.

```sh
./scripts/sync-ios-assets.sh            # ../RichMarkdown 기본
./scripts/sync-ios-assets.sh /path/to/RichMarkdown
```

## 빌드

```sh
./gradlew :richmarkdown-core:test                       # 코어 fixture (host JVM)
./gradlew -Prichmarkdown.buildRoot=/path/outside build  # 산출물을 저장소 밖에 둘 때
```

## License

MIT — [LICENSE](LICENSE). 번들·의존 라이브러리 라이선스는
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
