# upstream 제안 자료 — commonmark-java Android 호환 (D3a ③)

- 작성자: JunyoungJung
- 작성일: 2026-09-15 (KST)
- 상태: **자료만 준비.** 실제 이슈·PR 제출은 사용자 승인 뒤에 한다 (DEVELOPMENT.md D3a).

## 문제

commonmark-java 0.24.0부터 코어가 `java.util.List.of(...)`(및 일부 `Set.of`/`Map.of`)를 쓴다. 이 API는 Android **API 30**부터 존재한다.
Android 앱이 `minSdk < 30`이고 core library desugaring을 켜지 않으면 API 30 미만 기기에서 `NoSuchMethodError`가 난다.

- 도입 시점: 0.22.0(2024-03)에는 0곳, 0.24.0부터 `InlineParserImpl.java`에 8곳 (실측, 태그별 `grep`).
- 영향 파일(0.30.0, `commonmark` 코어 모듈): `internal/InlineParserImpl.java`(8), `renderer/markdown/CoreMarkdownNodeRenderer.java`(3),
  `renderer/html/CoreHtmlNodeRenderer.java`(3), `parser/Parser.java`(3, 주석 예시 포함), `internal/ParagraphParser.java`(2),
  `internal/IndentedCodeBlockParser.java`(2), `renderer/text/CoreTextContentNodeRenderer.java`, `renderer/markdown/MarkdownRenderer.java`,
  `renderer/html/HtmlWriter.java`, `renderer/html/DefaultUrlSanitizer.java`, `parser/block/AbstractBlockParser.java`, `node/SourceSpans.java` (각 1).
- upstream 상태: README가 "Android API 19 이상 best-effort 지원"을 명시하고, CI(`.github/workflows/ci.yml`)는 `commonmark-android-test`의
  **lint만** 실행한다(에뮬레이터 실행 없음). 그래서 회귀가 잡히지 않았다.
- 선례: PR #369 "Remove usage of requireNonNullElseGet (Android compat)"(2025-03) 머지 — 같은 종류의 수정이 받아들여진 기록.

## 제안

1. 코어 모듈에서 `List.of(...)`/`Set.of(...)`/`Map.of(...)`를 Java 8 API로 치환한다.
   - 빈 컬렉션: `Collections.emptyList()` / `emptySet()` / `emptyMap()`
   - 원소 1개: `Collections.singletonList(x)` / `singleton(x)` / `singletonMap(k, v)`
   - 원소 여럿: `Collections.unmodifiableList(Arrays.asList(a, b))` (또는 내부 전용이면 `Arrays.asList`)
   - 불변성 계약은 유지된다(`unmodifiable*`). 성능 차이는 무시할 수준(모두 상수 크기).
2. `commonmark-android-test` CI에 API 24 에뮬레이터 실행(또는 최소한 `lint`의 `NewApi` 검사 실패를 에러로 승격)을 추가해 재발을 막는다.
   - 현재 lint가 왜 `List.of`(API 30)를 잡지 못하는지는 미확인 — 제안 시 함께 질문한다.

## 제출 형식(초안)

- 이슈 제목: `Android: List.of() (API 30) used in core since 0.24.0 breaks minSdk < 30 without desugaring`
- 본문: 재현(minSdk 24, desugaring off, `Parser.builder().build().parse("*a*")` → `NoSuchMethodError`), 도입 버전, 영향 파일 목록, #369 선례, 치환 제안.
- PR: 위 치환 + android-test 강화. `mvn verify` 통과 확인 후.

## 우리 쪽 후속

머지되어 새 버전이 나오면 `gradle/libs.versions.toml`의 commonmark 버전을 올리고, README의 "API 30 미만 필수 설정" 절과
`RichMarkdownRenderModel`의 D3a fail-open 주석에서 요건을 제거한다(catch 자체는 방어 코드로 남겨도 무해).
