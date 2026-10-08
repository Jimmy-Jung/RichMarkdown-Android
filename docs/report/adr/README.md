# RichMarkdown Android 아키텍처 결정 지도

기준일: 2026-10-08

ADR(Architecture Decision Record)은 구조를 선택한 이유, 다른 방법의 비용, 다시 검토할 조건을 기록한 문서입니다. 블록 편집기 ADR을 제외한 아래 문서는 **현재 구현을 읽고 정리한 기록**입니다. 과거의 승인 절차나 최초 결정 날짜를 추정해서 확정하지 않습니다. 변경 전 문제와 이번 릴리스의 반영 내역은 [개선 기록](../improvements/README.md)에서 구분합니다. [용어 안내](../glossary.md)에서 요청 번호와 범위 단위 등의 뜻을 확인할 수 있습니다.

| 모듈 | 다루는 결정 | ADR 목록 |
| --- | --- | --- |
| Core | JVM 분리, UTF-16 원문 범위, 최신 대기 작업 실행기 | [Core ADR](README.md#richmarkdown-core-adr) |
| 표시 모듈 | Compose·View가 사용하는 공통 모델 클래스와 요청 번호별 화면 반영 | [표시 모듈 ADR](README.md#richmarkdown-adr) |
| Highlight | QuickJS·Prism 분리와 원문 검증 | [Highlight ADR](README.md#richmarkdown-highlight-adr) |
| Mermaid | 앱에 포함한 파일·WebView와 앱의 연결·요청 수명 | [Mermaid ADR](README.md#richmarkdown-mermaid-adr) |
| Editor | EditText 하나의 연속 문서, 앱이 소유한 블록 모델, iOS와 다르게 둔 편집 동작 | [편집기 ADR](README.md#richmarkdown-editor-adr) |

각 ADR은 실제 코드의 선택을 설명하고 재검토 조건을 포함합니다. 현재 보장과 개선 후 목표를 구분하며, 측정하지 않은 성능이나 대안별 점수를 결론의 근거로 삼지 않습니다.

## richmarkdown-core ADR

기준일: 2026-10-06 · 현재 구현을 읽고 정리한 기록

이 기록은 과거 승인 절차나 결정일을 추정하지 않습니다. 확인 가능한 코드 선택, 선택의 비용, 변경 기준을 정리합니다.

| ADR | 현재 선택 | 주요 계약 |
| --- | --- | --- |
| [0001](richmarkdown-core-0001-jvm-two-pass-and-coalescing.md) | 순수 JVM 코어, UTF-16 위치를 보존하는 2회 파싱, 잠금 기반 대기 요청 합치기 | AC-01~AC-20 |

[아키텍처](../architecture/richmarkdown-core.md)에서 흐름을, [명세](../spec/richmarkdown-core.md)에서 구분자와 상한을, [개선 제안](../improvements/richmarkdown-core.md)에서 미채택 검토를 확인할 수 있습니다.

## richmarkdown ADR

기준일: 2026-10-06 · 현재 구현을 읽고 정리한 기록

| ADR | 현재 선택 | 관련 요구 |
| --- | --- | --- |
| [0001](richmarkdown-0001-shared-model-and-vector-math.md) | Compose와 View의 공통 표시 모델 클래스, 요청 번호를 확인한 화면 반영, 블록 수식 벡터 표시 | AR-01~AR-22 |

승인 이력이나 과거 결정 날짜를 추정하지 않습니다. [아키텍처](../architecture/richmarkdown.md), [명세](../spec/richmarkdown.md), [별도 개선 제안](../improvements/richmarkdown.md)을 함께 읽으면 현재 구현과 미채택 변경을 구분할 수 있습니다.

## richmarkdown-highlight ADR

기준일: 2026-10-06 · 현재 구현을 읽고 정리한 기록

| ADR | 현재 선택 | 관련 요구 |
| --- | --- | --- |
| [0001](richmarkdown-highlight-0001-bundled-prism-and-utf16-spans.md) | 로컬 Prism + QuickJS, 원문 일치 검증, UTF-16 역할 범위 | AH-01~AH-09 |

[아키텍처](../architecture/richmarkdown-highlight.md), [명세](../spec/richmarkdown-highlight.md), [개선 제안](../improvements/richmarkdown-highlight.md)을 함께 확인합니다. 이 문서는 과거의 승인이나 결정 날짜를 새로 확정하지 않습니다.

## richmarkdown-mermaid ADR

기준일: 2026-10-06 · 기존 구조를 읽고 정리한 기록과 사용자 요청 개선 반영

| ADR | 현재 선택 | 관련 요구 |
| --- | --- | --- |
| [0001](richmarkdown-mermaid-0001-local-webview-and-cancellation-boundary.md) | 로컬 WebView 표시, 화면 연결 후 그림 계산, 최신 요청 ID·DOM 반영 조건·엔진 순차 실행·영구 자원 해제 | AM-01~AM-14 |

[아키텍처](../architecture/richmarkdown-mermaid.md), [명세](../spec/richmarkdown-mermaid.md), [개선 제안](../improvements/richmarkdown-mermaid.md)을 함께 읽습니다. 기존 구조의 원 결정 시점은 추정하지 않습니다. 2026-10-06 개선은 사용자 요청에 따라 반영했으며 실행 검수 상태는 [검수 기록](../validation.md)에서 확인합니다.

## richmarkdown-editor ADR

기준일: 2026-10-08 · D1a에 따라 새로 구현하며 작성한 기록

| ADR | 현재 선택 | 관련 요구 |
| --- | --- | --- |
| [0001](richmarkdown-editor-0001-edittext-continuous-document.md) | `EditText` 하나의 연속 문서, span만 입히는 블록 표시, 앱이 소유한 `BlockEditorModel` | AE-01~AE-25 |
| [0002](richmarkdown-editor-0002-intentional-ios-differences.md) | 문자 경계·서로게이트 범위 거절·줄바꿈 정규화·채움 문자 미사용·무시한 편집의 재동기화·조합 중 도구 모음 확정 | AE-02, AE-03, AE-07, AE-14, AE-16, AE-18 |

이 모듈은 다른 ADR과 달리 소급 기록이 아니라 2026-10-08 구현과 함께 작성했습니다. [아키텍처](../architecture/richmarkdown-editor.md), [명세](../spec/richmarkdown-editor.md), [개선 기록](../improvements/richmarkdown-editor.md)을 함께 읽습니다. 실행 검수는 [검수 기록](../validation.md#2026-10-08-블록-편집기-추가-검증)에서 확인합니다.
