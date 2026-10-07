# RichMarkdown Android 통합 명세

기준일: 2026-10-07 · 구현 기준: `0.2.0` 릴리스 대상 소스

이 문서는 모듈 선택과 앱에서 지켜야 할 공통 사용 규칙을 다룹니다. 명세의 계약은 입력 조건, 반환값, 실패 처리 규칙을 뜻합니다. API별 요구·예외와 테스트 근거는
[모듈별 명세](../README.md#모듈별-문서), 개선 제안은 [개선 기록](../improvements/README.md)에 있습니다. 낯선 용어는 [용어 안내](../glossary.md)를 참고합니다.

## 모듈·플랫폼 선언

| ID | 현재 선언·동작 | 근거 |
| --- | --- | --- |
| APK-01 | 라이브러리는 Core·표시 모듈·Highlight·Mermaid 네 모듈입니다. demo는 예제 앱, spike는 엔진 확인용 실험 모듈입니다. | [settings.gradle.kts](../../../settings.gradle.kts) |
| APK-02 | Android 라이브러리의 선언된 최소 API는 30이며 compileSdk는 37입니다. 이는 프로젝트 설정으로, 설치된 SDK나 장치 상태를 설명하지 않습니다. | [버전 카탈로그](../../../gradle/libs.versions.toml), [Renderer 설정](../../../richmarkdown/build.gradle.kts) |
| APK-03 | Core는 JVM 모듈입니다. Android 모듈의 View·Compose API와 독립적으로 파싱 코드를 다룹니다. | [Core 설정](../../../richmarkdown-core/build.gradle.kts) |
| APK-04 | commonmark `0.30.0`, RaTeX `0.1.14`, QuickJS `1.0.15`를 선언합니다. 앱에 포함한 JavaScript 파일과 지원하는 언어 문법은 각 엔진 명세를 따릅니다. | [버전 카탈로그](../../../gradle/libs.versions.toml) |
| APK-05 | 표시 모듈의 코드 블록 옵션에 코드 색칠·다이어그램 구현체를 선택해서 전달합니다. 기본값은 `None`입니다. | `RichMarkdownCodeBlockOptions.kt` · [표시 모듈 소스](../../../richmarkdown/src/main) |

예제 앱의 별도 최소 OS·설정은 라이브러리 전체의 지원 보장과 구분합니다. desugaring은 일부 Java API를 이전 Android 버전에서 쓰도록 빌드 때 보완하는 기능입니다.
기본 표시 모듈은 최신 전체 문자열을 받으며, 네 모듈은 iOS 블록 편집기 API를 제공하지 않습니다.

## 입력·범위·실패 계약

| ID | 현재 기준 | 해석·예외 |
| --- | --- | --- |
| APK-06 | 기본 수식은 `\(...\)`, `\[...\]`입니다. 달러 기호 구분자는 앱이 명시적으로 켜야 합니다. | [Core 명세](richmarkdown-core.md), [표시 모듈 명세](richmarkdown.md) |
| APK-07 | 전체 파싱 입력은 UTF-8 262,144바이트로 제한하며, 초과 시 앞부분을 65,536바이트까지 표시합니다. | 내부 보호값이며 생략 표시·줄바꿈·문자 경계 정책은 [Core 명세](richmarkdown-core.md)를 따릅니다. |
| APK-08 | 인용 깊이 64, 표 열 32·셀 512 제한은 Core에서 처리합니다. 수식 원문 4,096 UTF-8바이트 제한은 표시 모듈의 수식 엔진 진입에서 적용합니다. | 수식 상수·진단 선언만으로 수식 찾기 단계의 초과 거부를 보장하지 않습니다. [Core 명세](richmarkdown-core.md), [표시 모듈 명세](richmarkdown.md) |
| APK-09 | Core 원문 범위와 코드 색칠 범위는 UTF-16 `[start, end)`입니다. 시작 위치는 포함하고 끝 위치는 제외합니다. 바이트 상한과 위치 범위는 서로 다른 단위입니다. | [Core 명세](richmarkdown-core.md), [코드 색칠 명세](richmarkdown-highlight.md) |
| APK-10 | 코드 색칠 블록 상한은 100,000 UTF-16 단위입니다. 미지원 언어·실패는 빈 색칠 구간 목록으로 돌아갑니다. | [코드 색칠 명세](richmarkdown-highlight.md) |
| APK-11 | Mermaid 원문 상한은 20,000 UTF-8바이트이며 앱에 포함한 파일을 사용합니다. 페이지 로드와 그림 계산 응답의 대기 제한은 각각 적용합니다. | [Mermaid 명세](richmarkdown-mermaid.md) |

## 비동기 표시와 수명

| ID | 현재 계약 | 확인 위치 |
| --- | --- | --- |
| APK-12 | 모델은 메인 스레드에서 요청·화면 반영을 관리합니다. 요청 순서 번호(`generation`)가 다른 결과는 모델 상태에 반영하지 않습니다. | [표시 모듈 명세](richmarkdown.md) |
| APK-13 | 작업 실행기는 실행 하나와 최신 대기 하나를 유지합니다. 실행 중 작업의 즉시 중단을 보장하지 않습니다. | [Core 명세](richmarkdown-core.md) |
| APK-14 | Compose와 View는 같은 모델 클래스를 사용하지만 모델 객체는 각 화면이 따로 소유합니다. 각 뷰에 보관하는 수식·코드 색 상태가 현재 입력과 맞는지도 따로 확인해야 합니다. | [표시 모듈 개선안](../improvements/richmarkdown.md) |
| APK-15 | Mermaid는 앱 쪽 응답과 JavaScript의 표시 전 임시 영역에서 최신 ID를 대조합니다. 마지막 화면 갱신 단계 이후에만 웹 문서 구조(DOM)·높이를 반영합니다. 취소로 실행 상태가 남으면 다음 요청 전에 페이지를 다시 로드합니다. | [Mermaid 개선 기록](../improvements/richmarkdown-mermaid.md) |

수식 서체는 RaTeX가 KaTeX 기반 내장 글꼴에서 자동으로 선택합니다. Android 0.2.0에서는
`LatexMathFont`와 테마·요청·캐시 키의 `mathFont` 선택 인자를 제거했습니다. 크기·색·블록 여부는
계속 지정하며 내부 `mathFontSizePx`는 크기 값으로 유지합니다. 기존 앱은 해당 인자를 삭제해야 합니다.

iOS와 같은 동작 규칙을 공유한다는 설명이 모든 Swift·Kotlin API의 이름·인자가 일대일로
같다는 뜻은 아닙니다. 테마, 글자 크기, 수식 이미지 재사용 조건과 플랫폼별 차이는
각 플랫폼의 표시 모듈 명세를 따릅니다. 삭제 후 실행 검수는 [AR-I05](../improvements/richmarkdown.md#ar-i05-효과가-없는-수식-서체-선택-api-제거)와
[검수 기록](../validation.md)에 연결하며 과거 실행 성공과 구분합니다.

## 검증을 읽는 기준

각 명세의 테스트 링크는 해당 계약을 확인하는 코드 위치입니다. JVM 테스트, Android
계측 테스트(기기·에뮬레이터에서 실행하는 검사), 실제 View·Compose 동작은 서로 다른 검증입니다. 이번 문서 작성에서
수행한 문서 검사와 수행하지 않은 실행 검증은 [validation.md](../validation.md)에 기록합니다.
