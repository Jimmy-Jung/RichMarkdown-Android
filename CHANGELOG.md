# Changelog

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따른다. 버전은 iOS와 독립된
semver다. `0.x`에서는 minor 버전에서도 공개 API가 바뀔 수 있다.

## [Unreleased]

### Added

- 저장소 골격: Gradle 9.6.0 + AGP 9.4.0 + Kotlin 2.4.20, compileSdk 37 / minSdk 24, 모듈 4개
  (`richmarkdown-core`, `richmarkdown`, `richmarkdown-highlight`, `richmarkdown-mermaid`),
  `-Prichmarkdown.buildRoot` 산출물 리다이렉트.
- `richmarkdown-core` 모델 타입: `Utf16Range`, `MathKind`, `DollarMathOptions`,
  `ProtectedMathSpan`, `MathDiagnostic`, `ParsedDocument`/`ParsedBlock`/`InlineRun`, `LinkPolicy`,
  `MathProtector`(길이 보존 mask), `String.unescapingMarkdownPunctuation()`,
  `@InternalRichMarkdownApi`.
- `richmarkdown-core` 파이프라인 — iOS `RichMarkdownCore` 이식(UTF-8 byte → UTF-16 code unit):
  `MathScanner`(hard/soft barrier·block/inline 구분자 규칙·diagnostic), `RichMarkdownParser`
  (commonmark-java 2-pass: 1차 범위 수집 → 원문 수식 스캔 → mask → 2차 파싱 → 모델 변환,
  entity/escape 경로 포함), `InputLimits`(256 KiB·64 KiB·block quote 깊이 64·표 32열/512셀),
  `StreamingTail`(미닫힘 opener 숨김·꼬리 페이드), `CoalescingWorker`(latest-wins, 실행 1 + 대기 1).
- 코어 JVM 테스트 101건(iOS fixture 이식): `MathScannerFixtureTest` 35, `RichMarkdownParserFixtureTest` 29,
  `RichMarkdownParserTest` 15, `StreamingTailTest` 12, `MaskRoundTripTest` 5, `CoalescingWorkerTest` 5.
- `richmarkdown` 공개 API 값 타입(렌더러 미포함): `RichMarkdownTheme`/`RichMarkdownSyntaxColors`,
  `RichMarkdownColor`, `RichMarkdownFont`/`RichMarkdownTextStyle`/`RichMarkdownFontWeight`,
  `LatexMathFont`, `LatexDollarMathOptions`, `RichMarkdownStreamingOptions`,
  `RichMarkdownCodeBlockOptions` + `RichMarkdownSyntaxHighlighting`/`RichMarkdownDiagramRendering`.
- opt-in 모듈 assets: iOS와 동일한 Prism 1.30.0 번들 22파일(`richmarkdown-highlight`), Mermaid 11.17.2
  번들·`index.html`·고지(`richmarkdown-mermaid`). 각 모듈 루트 `SYNC-MANIFEST.txt`에 SHA-256 기록.
- 설계 문서 `DEVELOPMENT.md`(결정 D0~D9·D3a), `README.md`, `THIRD_PARTY_NOTICES.md`.
- `scripts/sync-ios-assets.sh`: iOS 저장소의 Prism·Mermaid 번들을 복사하고 iOS Docs의 SHA-256 표와 대조한다.

- P0 spike: host `spikes/prism-quickjs`(QuickJS + Prism 번들), instrumented `RaTeXSpikeTest`·`PrismQuickJsAndroidSpikeTest`·
  `MermaidWebViewSpikeTest`. 결과는 DEVELOPMENT.md §8. D4(RaTeX) 확정.
- 빌드: `compileSdk` 37 (Compose BOM 2026.09.00 요구), androidx.test 1.7.0 / ext-junit 1.3.0.

- P1: `MathRenderService`(RaTeX 격리, LruCache 64 MiB, iOS preflight), `ParseCache`, `RichMarkdownRenderModel`
  (generation·latest-wins·2단계 게시·스트리밍 append 유지, D3a fail-open catch), `RichMarkdownStreamingTextBuffer`.
- P1: Compose 렌더러 `RichMarkdown()`과 View 렌더러 `RichMarkdownView` — 블록 9종, 인라인 수식 baseline,
  코드 칩, 표, 코드 블록 헤더·복사, 블록 수식 벡터, 스트리밍 tail(미닫힘 opener 숨김·12 grapheme 페이드).
- P1: `richmarkdown-highlight` `PrismHighlighter`(QuickJS, iOS 번들 공유, 역할 7종), `richmarkdown-mermaid`
  `MermaidDiagramRenderer`(WebViewAssetLoader, attach 후 렌더, fail-open). instrumented 테스트 통과.

- P2: `demo` 앱(쇼케이스 Compose/View 토글, AI 챗봇 Compose·RecyclerView, SSE 실시간 렌더링), README 스크린샷·SSE GIF,
  `scripts/capture-demo-screens.sh`. API 24 에뮬레이터에서 coreLibraryDesugaring 동작 실측(D3a).
- 발행 설정: `com.vanniktech.maven.publish` 0.37.0, 좌표 `io.github.jimmy-jung:richmarkdown{,-core,-highlight,-mermaid}`,
  POM 메타데이터(MIT). `publishToMavenLocal` 검증. Central 발행은 자격 증명·서명 키 준비 후.

### Changed

- minSdk 24 → 30 (D9a, 2026-09-16). commonmark-java `List.of`를 desugaring 없이 쓰기 위한 하한. 소비 앱의
  `coreLibraryDesugaring` 요건과 README 필수 설정 절을 제거했다. 데모 앱도 desugaring을 끈다.

### Fixed

- View 렌더러 표 셀 높이가 행에 맞지 않아 테두리가 어긋나던 결함.

### Not yet

- Maven Central 실제 발행(사용자 승인), commonmark-java upstream PR(D3a ③).
