# richmarkdown-highlight 동작 명세

기준일: 2026-10-06 · 현재 구현 확인 · 실행 결과 별도

이 모듈은 코드에 문법별 색을 적용할 때 선택적으로 추가하며 `RichMarkdownCodeBlockOptions(highlighter = PrismHighlighter.shared(context))`에 연결합니다. 코드 분석 라이브러리 Prism이 원문을 키워드·문자열 같은 조각(토큰)으로 나누고 JavaScript 엔진 QuickJS가 이를 실행합니다. 반환값은 원문 위치와 역할이며 실제 색은 상위 렌더러가 정합니다.

[모듈 선언](../../../richmarkdown-highlight/build.gradle.kts)과 [버전 선언](../../../gradle/libs.versions.toml)의 최소 실행 API 수준(minSdk)은 30, 컴파일 API 수준(compileSdk)은 37입니다. QuickJS 연결 라이브러리(binding)는 1.0.15, 패키지에 포함한 Prism 문법(bundle)은 1.30.0입니다. 네트워크에서 문법(grammar)을 추가하지 않습니다.

원문 위치와 길이는 문자를 16비트 단위로 표현하는 UTF-16 기준입니다. 가상 예시 `A😀B`는 Kotlin `String.length`가 4이며 이모지는 두 단위의 쌍(surrogate pair)으로 저장됩니다. 색 구간(span)의 `[1, 3)`은 시작 1을 포함하고 끝 3을 제외하므로 이모지 두 단위를 가리킵니다.

dispatcher는 코루틴을 실행할 위치를, Mutex는 같은 실행 환경을 여러 호출이 동시에 사용하지 못하게 하는 잠금을 뜻합니다. 코루틴은 대기 후 실행을 이어갈 수 있는 작업 단위이며 suspend 함수라는 이유만으로 항상 다른 스레드에서 실행되는 것은 아닙니다. 일반적인 용어 설명은 [보고서 용어 안내](../glossary.md)를 참고합니다.

## 공개 API와 요구

| API | 계약 |
| --- | --- |
| `PrismHighlighter.shared(context)` | 앱 리소스용 application context를 보관하는 공유 객체를 반환합니다. |
| `spans(code, language)` | 작업 중 잠시 대기할 수 있는 suspend 함수입니다. 원문 UTF-16 범위와 `RichMarkdownHighlightKind` 목록을 반환합니다. |
| `release()` | JavaScript 실행 환경(context)의 해제를 요청합니다. 이후 spans 호출에서 필요하면 다시 초기화합니다. |
| `maxCodeUtf16Units` | 코드 블록 한 개의 내부 처리 상한은 100,000입니다. |

| ID | 요구와 현재 구현 | 근거·테스트 선언 |
| --- | --- | --- |
| AH-01 | code 문자열을 실행할 코드에 끼워 넣지 않고 연결 함수(binding)를 통해 고정 함수의 인자로 전달합니다. | `tokenize`, `loadedContext`, `native-tokenize.js` |
| AH-02 | `code.isEmpty()` 또는 UTF-16 100,000 초과는 빈 리스트입니다. 바이트 수나 결합 문자를 포함한 글자 단위(grapheme)의 제한이 아닙니다. | 상한 테스트 |
| AH-03 | language는 소문자로 통일하고 문법 별칭(alias)을 적용합니다. 없는 문법은 로그 없이 빈 리스트입니다. | 별칭·미지원 언어 테스트 |
| AH-04 | 원문·언어 연결값 변경과 QuickJS 호출은 Default dispatcher에서 Mutex 잠금을 사용해 순서대로 수행합니다. 동시에 호출해도 실행 환경의 요청값이 섞이지 않아야 합니다. | 동시 호출 8개 비교 테스트 |
| AH-05 | 토큰 안의 자식 토큰까지 펼친 조각을 다시 합쳐 원문과 정확히 같아야만 범위를 반환합니다. 조각 필드 누락·원문 불일치는 전체 결과를 버립니다. | `validatedSpans`; 문법·다국어 테스트 |
| AH-06 | 범위는 시작 포함·끝 제외(half-open)의 UTF-16 단위입니다. 토큰 내용 길이를 누적해 계산하며 원문을 삭제·이스케이프·수정하지 않습니다. | 다국어 범위·surrogate pair 경계 검사 |
| AH-07 | 역할은 Keyword(키워드)·String(문자열)·Comment(주석)·Number(숫자)·Type(타입)·Function(함수)·Property(속성)입니다. 점 표기 토큰은 점 앞 분류에 대응시킵니다. 대응 역할이 없거나 연산자(operator)·구두 기호(punctuation)이면 별도 색을 주지 않습니다. | 역할·점 표기·연산자 테스트 |
| AH-08 | 처리 중 오류(throwable)는 빈 리스트로 반환하지만 취소 예외인 `CancellationException`은 호출자에게 다시 던집니다. 렌더러는 색 없는 코드를 유지합니다. | `spans` catch 구조 |
| AH-09 | release는 작업이 없으면(idle) 바로 닫고 실행 중이면 닫기 요청 표시(flag)를 남깁니다. 함수 반환이 네이티브 해제 완료를 보장하는 경계(barrier)는 아닙니다. 다음 호출은 실행 환경을 다시 만들 수 있습니다. | release 후 동일 결과; 동시 spans·release 반복 테스트 |

## 번들 문법과 별칭

공식 이름으로 확인한 번들 문법은 JavaScript, TypeScript, Python, Bash, Kotlin, Java, C, C++, Go, Rust, SQL, YAML, CSS, Markup, JSX, TSX, Swift, JSON입니다. `clike`는 자식 문법을 위한 공통 기반으로 먼저 읽습니다.

구현 자체의 별칭에는 `c++/cxx/cc/objective-c++ → cpp`, `golang → go`, `rs → rust`, `zsh/console/shell-session → bash`, `json5/jsonc → json`, `mysql/postgres/postgresql/sqlite → sql`, `htm → markup`, `node → javascript`가 있습니다. `js/ts/py/sh/shell/kt/kts/yml/html/xml/svg`는 번들이 등록한 별칭을 이용합니다.

별칭에 연결됐다는 것은 해당 언어의 확장 문법을 완전히 지원한다는 뜻은 아닙니다. 예를 들어 json5/jsonc는 별도 문법 파일을 읽지 않습니다.

근거: [소스](../../../richmarkdown-highlight/src/main/)의 `PrismHighlighter.kt`, `native-tokenize.js`와 [Android 테스트](../../../richmarkdown-highlight/src/androidTest/)의 `PrismHighlighterTest.kt`. 테스트는 문법 18종의 예제를 순회하지만 모든 문법·모든 Unicode 문자 조합을 망라하지 않습니다.

## 실행 보장의 한계

100,000 UTF-16 상한은 문자열 패턴을 찾는 정규식 코드 분석을 정해진 시간에 끝낸다는 종료 기한(deadline)이 아닙니다. 현재 함수에는 네이티브 실행을 중단하는 기능(interrupt)·시간 초과 처리(timeout)·대기열(queue)의 별도 상한이 없습니다. 분석 결과 캐시나 원문의 요청 순서번호(generation)도 없으며 오래된 결과를 화면에 적용할지는 상위 렌더러가 판단합니다.

호출을 순서대로 처리하는 것과 이미 실행 중인 JavaScript를 중단하는 것은 다른 기능입니다.

기존 QuickJS 테스트의 실행 성공과 미검증 범위는 [검수 기록](../validation.md)을 따릅니다. 해제 경합 반복 검사(release stress)가 모든 실행 순서·네이티브 해제 완료 시점을 증명하지는 않습니다. 네이티브 메모리와 의도적으로 처리 비용을 높인 입력(adversarial)의 분석 시간은 [개선 제안](../improvements/richmarkdown-highlight.md)의 추가 확인 범위입니다.
