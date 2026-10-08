# Changelog

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따른다. 버전은 iOS와 독립된
semver다. `0.x`에서는 minor 버전에서도 공개 API가 바뀔 수 있다.

## [Unreleased]

### Added

- opt-in `richmarkdown-editor` 모듈(D1a): iOS `RichMarkdownBlockEditor`의 순수 Kotlin 모델·코덱 이식 —
  `EditorBlock`·`EditorBlockKind`·`EditorRange`·`BlockSelection`·`InlineMark`·`InlineMarkdownCodec`·
  `BlockEditorModel`(연속 문서 좌표, 분할·병합·변환·이동, undo/redo 100)·`BlockDocumentPasteboardPayload`(version 1 JSON).
- iOS와 다른 점: grapheme 경계는 `BreakIterator`, surrogate pair 중간 범위는 거절, Markdown 입력·문서 교체의
  CRLF·CR은 LF로 정규화, fence 트림은 Swift `.whitespaces`(Zs + 탭) 규칙.
- `richmarkdown-editor` 편집 뷰: `BlockDocumentEditText`(EditText, iOS `BlockDocumentUITextView` + Coordinator)·
  `MarkdownStyler`·`BlockAlignmentConfiguration`·`EditorToolbarAction`·`BlockEditorInputAccessory`와 Compose 래퍼
  `compose.BlockDocumentTextEditor`. 목록 마커·체크박스·인용 바·인라인 코드 칩, 인라인·블록 수식(raster),
  IME 조합 확정 시 한 번만 모델에 전달, 전체 문서 복사·붙여넣기 블록 payload(`ClipDescription` extras), Ctrl+Z/Ctrl+Shift+Z → 모델 undo/redo.
- 편집 뷰의 iOS와 다른 점: 표시 문자열이 `documentText`와 같다(U+2063 보충 문자 없음, 수식은 원문 위 span),
  모델이 무시·변형한 편집 뒤에도 최신 상태로 다시 그려 화면 == 모델, `InputConnection`을 감싸 IME 확정을 감지,
  trailing indent(인용·코드·수식 오른쪽 여백)는 적용하지 않는다.
- 데모 `BlockEditorActivity`(iOS `BlockEditorDemoView`): 앱이 소유한 `BlockEditorModel`과 `BlockDocumentTextEditor`,
  키보드 위 가로 스크롤 도구 모음(블록 추가·종류 바꾸기·서식·들여쓰기·실행 취소·더보기·완료, `BlockEditorInputAccessory`),
  `$ 수식 파싱`·내보낸 Markdown 보기 옵션. 홈 목록·`capture-demo-screens.sh`·`verify-demo-ui.py`에 추가.

### Changed

- `richmarkdown`: 편집기가 재사용하는 `InlineCodeChipSpan`·`TypefaceStyleSpan`·`MathAttachmentSpan`·
  `RichMarkdownFont.resolveTypeface()`·`RichMarkdownFont.textSizePx(context)`·`RichMarkdownTheme.resolvedTextColor(isDark)`와
  칩 그리기(`InlineCodeChipPainter`)를 `@InternalRichMarkdownApi`(opt-in)로 공개한다. 뷰어 동작은 그대로다.

### Fixed

- `richmarkdown-editor`: IME 조합 중 도구 모음 명령을 무시하던 동작을 고친다. 조합을 확정해 모델에 한 번 전달하고 IME를 다시 시작한 뒤
  확정 선택으로 명령을 보낸다. Gboard 등 라틴 키보드는 입력 중 단어 전체를 조합 영역으로 두어 영문 입력 중 도구 모음이 막혔다.
  iOS는 marked text 중 명령을 무시한다(의도적 차이).
- `richmarkdown-editor`: 한글 줄에 굵게·기울임을 걸면 줄 높이가 커지던 문제를 고친다(SM-G988N 실측 제목 137→149px).
  `BlockDocumentEditText`의 `isFallbackLineSpacing`을 끈다. span이 줄을 여러 run으로 나눌 때 한글 fallback 글꼴 metrics가 줄 높이에 더해졌다.

### Not yet

- Maven Central 실제 발행(사용자 승인), commonmark-java upstream PR(D3a ③).

## [0.2.0] - 2026-10-07

### Fixed

- 직접 `BoundedInput`·`Request` 생성의 입력 제한 우회를 막고 시작 전/실행 중 취소에서도 worker idle 상태를 해제한다.
- Compose의 코드 색·수식 vector 결과를 요청별 state로 분리하고 View 하이라이트의 범위 밖·겹침 입력을 안전하게 제외한다.
- native diagram의 영구 교체·제거에서 기존 공급자에게 dispose를 전달한다. 일반 detach/reuse는 구분한다.
- Mermaid의 최신 request ID·JS staging·deadline·취소 후 페이지 복구·명시 dark 전달·WebView 생성 실패·영구 폐기·bounded fallback을 보완한다.
- Mermaid HTML/sanitizer 설정 잠금과 CSP bootstrap hash로 inline 이벤트를 제한하고, 전이 의존성 DOMPurify 3.4.16·JavaScript KaTeX 0.18.2 patch 번들을 공유한다.

### Added

- Core·모델·Compose·View·native 엔진의 입력/수명 회귀와 모듈별 architecture·spec·ADR·개선 기록을 추가한다.
- diagram 공급자에 기본 구현을 가진 dark 전달 overload와 `disposeView`를 추가한다. 기존 공급자 소스는 기존 메서드를 계속 구현할 수 있다.

### Removed

- 선택지가 하나뿐인 `LatexMathFont`와 `RichMarkdownTheme.mathFont`, `RichMarkdownRenderModel.Request`·`Request.of`·`MathRenderKey`의 `mathFont` 인자를 제거한다. 수식은 RaTeX의 KaTeX 서체를 자동으로 사용한다.

### Compatibility

- 네 artifact와 선언된 minSdk 30은 유지한다. 버전은 iOS 0.9.0과 독립된 Android 0.2.0이다.
- native RaTeX 0.1.14는 유지하며 최초 Maven Central 발행은 이번 GitHub Release 배포와 구분한다.
- 0.1.0에서 옮길 때 기존 `mathFont` 인자와 `LatexMathFont` import를 삭제한다. 해당 프로퍼티·생성자·자동 생성되는 `copy` 등도 바뀌므로 소비 라이브러리를 다시 컴파일한다. 수식 크기·색·정렬·비트맵/벡터 표시 방식은 유지한다.

## [0.1.0] - 2026-10-06

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
- `array`·`\underbrace` 복합 수식이 원문 byte × 폰트 크기 예상폭 상한에 걸려 거부되던 결함.
  parse 전에는 4 KiB 원문과 1–1024 px 폰트만 검사하고, parse 후 실제 layout 크기로
  각 변 8192 px·4,194,304 pixel을 raster·벡터 공통 경계에서 제한한다.

[Unreleased]: https://github.com/Jimmy-Jung/RichMarkdown-Android/compare/0.2.0...HEAD
[0.2.0]: https://github.com/Jimmy-Jung/RichMarkdown-Android/releases/tag/0.2.0
[0.1.0]: https://github.com/Jimmy-Jung/RichMarkdown-Android/releases/tag/0.1.0
