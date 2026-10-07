# RichMarkdown Android 패키지 보고서

기준일: 2026-10-06 · 구현 기준: `0.2.0` 릴리스 대상 소스

이 보고서는 RichMarkdown을 Android 앱에 연결하거나 수정할 개발자를 위한 문서입니다. 네 라이브러리 모듈이 맡는 일, 입력·반환값·실패 처리 규칙, 현재 구조를 선택한 이유를 설명합니다.

설명은 릴리스 대상 구현을 기준으로 [모듈 설정](../../settings.gradle.kts), 각 모듈의 소스와 테스트를 대조했습니다. 테스트 코드가 있다는 사실과 실제로 실행해 통과했다는 사실은 구분합니다.

문서 구성은 iOS 보고서와 플레이어 모듈 보고서의 흐름을 참고했습니다. 아키텍처 문서는 전체 구조를, ADR(Architecture Decision Record)은 구조를 선택한 이유를, 명세는 앱에서 지켜야 할 사용 규칙을 설명합니다. 낯선 표현은 [용어 안내](glossary.md)에서 뜻과 예시를 확인할 수 있습니다.

조사에서 발견한 문제, 이번 릴리스에 반영한 수정, 추가 측정이나 요구가 필요해 보류한 항목은 [개선 기록](improvements/README.md)에 나눠 적었습니다.

모듈별 문서는 `architecture/`, `spec/`, `adr/`, `improvements/` 공통 폴더에 문서 유형별로 모았습니다. 각 유형의 `README.md`에서 전체 지도를 확인하고, 모듈 이름의 파일이나 ADR 절로 이동합니다.

## 문서 지도

| 읽으려는 내용 | 문서 |
| --- | --- |
| 용어의 뜻과 실제 사용 예 | [용어 안내](glossary.md) |
| 모듈 경계와 입력부터 화면까지의 흐름 | [architecture/README.md](architecture/README.md) |
| 통합 조건·플랫폼 차이·공통 제한 | [spec/README.md](spec/README.md) |
| 모듈별 구조 결정 목록 | [adr/README.md](adr/README.md) |
| 확인한 빈틈과 최소 개선안 | [improvements/README.md](improvements/README.md) |
| 이번 문서 검사의 결과와 한계 | [validation.md](validation.md) |
| 개념도 원본과 재생성 방법 | [assets/README.md](assets/README.md) |

## 모듈별 문서

| 모듈 | 역할 | 아키텍처 | 명세 | ADR |
| --- | --- | --- | --- | --- |
| `richmarkdown-core` | Markdown 분석·수식 구간·입력 제한·대기 요청 관리 | [구조](architecture/richmarkdown-core.md) | [계약](spec/richmarkdown-core.md) | [결정](adr/README.md#richmarkdown-core-adr) |
| `richmarkdown` | Compose·View의 문서·수식 표시 | [구조](architecture/richmarkdown.md) | [계약](spec/richmarkdown.md) | [결정](adr/README.md#richmarkdown-adr) |
| `richmarkdown-highlight` | Prism을 이용한 코드 색칠 구간 계산 | [구조](architecture/richmarkdown-highlight.md) | [계약](spec/richmarkdown-highlight.md) | [결정](adr/README.md#richmarkdown-highlight-adr) |
| `richmarkdown-mermaid` | 로컬 WebView 다이어그램 | [구조](architecture/richmarkdown-mermaid.md) | [계약](spec/richmarkdown-mermaid.md) | [결정](adr/README.md#richmarkdown-mermaid-adr) |

Gradle 모듈은 함께 빌드하는 소스와 의존성의 묶음입니다. 이 저장소의 배포 대상 라이브러리는 네 개이며, `richmarkdown-core`도 별도로 배포할 수 있는 JVM(Java Virtual Machine) 모듈입니다. Android 화면 API에 의존하지 않아 JVM에서 파싱 코드를 다룰 수 있습니다.

`demo`는 예제 앱, `spikes:prism-quickjs`는 엔진을 확인하는 실험 모듈입니다. Android에는 iOS의 `RichMarkdownBlockEditor`에 해당하는 라이브러리 모듈이 없습니다.

## 읽는 방법

처음에는 전체 아키텍처를 읽고 사용하는 라이브러리의 명세로 이동합니다. 변경을 검토할 때는 해당 모듈의 ADR과 개선 기록을 함께 읽습니다. 요구 ID는 동작을 추적하기 위한 번호이며, 소스·테스트 링크에서 해당 구현을 확인할 수 있습니다.

같은 이름의 iOS API와 개념을 공유하더라도 위치 단위, OS 조건, 수명 해제, 선택 동작은
각 플랫폼의 명세로 확인합니다. Mermaid 로컬 렌더 검사는 실제 GitHub·VSCode 표시와 별개이며,
Excalidraw는 SVG와 편집 원본을 함께 제공합니다.
