# richmarkdown-core 동작 명세

기준일: 2026-10-06 · 구현 확인 기반 · 테스트 실행 상태는 별도입니다.

이 명세는 `InternalRichMarkdownApi`로 표시한 내부 원문 분석 규칙입니다. 원문을 문서 구조로 분석하는 작업을 파싱(parsing)이라고 합니다. 마이너(minor) 버전에서도 바뀔 수 있으며, 앱에서 사용할 안정적인 진입점은 [richmarkdown](richmarkdown.md)입니다.

[모듈 선언](../../../richmarkdown-core/build.gradle.kts)은 Android UI 없이 Java 가상 머신(JVM)에서 동작하며 대상 버전이 17임을 정합니다. [버전 선언](../../../gradle/libs.versions.toml)의 원문 분석 라이브러리는 commonmark-java 0.30.0이며 Android 라이브러리 계층의 minSdk는 30입니다. minSdk는 앱이 실행되는 최소 Android API 수준입니다.

아래 테스트 이름은 확인 코드의 위치이며 이번 문서 작업에서 새로 얻은 실행 결과가 아닙니다.

## 입력과 위치

UTF-8은 문자를 바이트로 저장하는 방식이고 UTF-16은 16비트 단위로 저장하는 방식입니다. 가상 예시 `A😀B`는 UTF-8 6바이트, Kotlin `String.length`로는 UTF-16 4단위이며 이모지는 두 단위의 쌍(surrogate pair)을 차지합니다. 입력 크기 제한은 UTF-8, 원문 위치는 UTF-16이므로 서로의 숫자를 그대로 사용할 수 없습니다.

`[start, end)`는 시작을 포함하고 끝을 제외하는 반열린 범위입니다. 위 예에서 `[1, 3)`은 이모지의 두 UTF-16 단위를 가리킵니다. Unicode 문자 번호인 code point나 결합 문자를 포함한 글자 단위인 grapheme의 개수도 UTF-16 위치와 구분합니다.

| ID | 요구와 현재 동작 | 근거·테스트 선언 |
| --- | --- | --- |
| AC-01 | `parse(String, DollarMathOptions)`는 파싱 전 `InputLimits.bound`를 적용합니다. 원문 상한은 UTF-8 262,144바이트입니다. | `InputLimits.kt`; `oversizedInputIsTruncatedWithMarker` |
| AC-02 | 바이트 상한을 초과하면 문자 경계를 찾는 `BreakIterator`로 앞부분(prefix)을 UTF-8 65,536바이트 이내로 남깁니다. 두 줄바꿈과 `… [입력 제한 초과]`를 붙이므로 이 값은 생략 표시를 포함한 최종 길이 상한이 아닙니다. | `InputLimits.kt`; `requestOf_appliesInputLimits`는 상위 모듈 테스트 |
| AC-03 | 인용 중첩 깊이는 64까지입니다. 초과 행 직전에서 잘라 생략 표시를 붙이며 백틱이나 물결표로 감싼 코드 블록(fenced code) 본문의 `>`는 깊이로 세지 않습니다. CR·LF·CRLF 등 줄바꿈과 인용 영역을 벗어나는 경우를 처리합니다. | `RichMarkdownParserFixtureTest.kt`의 인용·코드 블록·CR 입력과 기대값 |
| AC-04 | 모든 원문(source) 위치는 UTF-16 단위의 `[start, end)`입니다. 입력 바이트 제한, code point, grapheme 개수와 바꿔 쓰면 안 됩니다. | `Utf16Range.kt`; 다국어 구간 검색·파서의 입력과 기대값 |
| AC-05 | 수식을 임시 문자로 덮는 마스크는 원문의 UTF-16 길이와 CR/LF 줄바꿈을 보존합니다. 보호 구간을 복원하면 원문과 같아야 합니다. | `MathProtector.kt`; `MaskRoundTripTest.kt` |
| AC-06 | `parse(BoundedInput, …)`도 바이트·인용 깊이를 다시 검사하고 이전 잘림 여부(flag)를 보존합니다. `scanInlineMathSpans`는 위치 조회용 내부 경로로 자동 제한하지 않으며 호출자가 제한 원문과 유효한 제외 범위를 제공합니다. | `RichMarkdownParser.kt`; `directlyConstructedBoundedInputCannotBypassLimits` |

## 수식 인식

파싱은 원문을 문서 구조로 분석하는 작업입니다. 문장 안의 수식을 인라인, 문단 전체를 차지해 별도로 배치하는 수식을 블록이라고 합니다. `\(`·`\)` 같은 시작·끝 기호가 수식 구분자입니다.

| ID | 요구와 현재 동작 | 근거·테스트 선언 |
| --- | --- | --- |
| AC-07 | 기본은 `\(...\)` 인라인과 문단 전체 `\[...\]` 블록입니다. 인라인 수식은 한 행을 넘지 않습니다. | `MathScanner.kt`; `MathScannerFixtureTest.kt` |
| AC-08 | `Single`은 `$...$` 인라인을 켭니다. `InlineDouble`은 문장 안 `$$...$$`를 켭니다. 달러 기호 옵션이 하나라도 켜지면 문단 전체 `$$...$$`가 블록으로 인식됩니다. | `MathSpan.kt`, `MathScanner.blockSpan`; 달러 기호 입력과 기대값 |
| AC-09 | 달러 기호 인라인 수식은 여는 기호 뒤·닫는 기호 앞 공백이나 탭, 닫는 기호 뒤 ASCII 숫자, 줄바꿈을 허용하지 않습니다. 역슬래시로 이스케이프한 달러 기호는 구분자가 아니며 `$$`를 먼저 판정합니다. | `inlineDollarSpan`, `inlineDoubleDollarSpan` |
| AC-10 | 코드·HTML 제외 구간(hard barrier) 내부나 그 경계를 가로지르는 수식은 인정하지 않습니다. 링크·이미지는 조건부 제외 구간(soft range)이며 수식 내용이 그 구간 전체를 포함할 때만 수식으로 읽습니다. | 수식 검색의 제외 구간·조건부 제외 구간, 파서의 링크·이미지 입력과 기대값 |
| AC-11 | 빈 내용·중첩·닫히지 않은 괄호 구분자는 원문을 유지합니다. 문단 전체가 아닌 `\[`는 원문과 `NonParagraphDisplayDelimiter` 진단을 남깁니다. | `MathScanner.kt`; 형식이 잘못된 입력과 기대값 |
| AC-12 | `MathSegment.source`는 구분자를 포함하고 `latex`는 구분자와 바깥 공백을 제거한 내용입니다. `allMathSegments`는 문서 순서로 중복을 제거합니다. | `MathSpan.kt`, `ParsedDocument.kt` |

4,096 UTF-8 바이트 수식 상수와 `OversizedMathSource` 진단 종류가 선언되어 있다는 사실만으로 수식 검색에서 초과 입력을 거절한다고 볼 수는 없습니다. 현재 스캐너는 이 바이트 제한을 적용하지 않으며 [수식 렌더 서비스](richmarkdown.md)가 엔진에 넘기기 전에 제한합니다.

## Markdown과 스트리밍 표시

| ID | 요구와 현재 동작 | 근거·테스트 선언 |
| --- | --- | --- |
| AC-13 | 문단·제목·코드·수식·인용·목록·구분선·GitHub Markdown 확장(GFM)의 표를 데이터로 만듭니다. 문장 안의 굵게·기울임·취소선·코드·링크·줄바꿈도 다룹니다. 표는 32열·512셀까지이며 초과하면 원문 문단으로 표시합니다. | `ParsedDocument.kt`, `RichMarkdownParser.kt` |
| AC-14 | HTML은 문자로, 이미지는 대체 텍스트(alt text)로 표시 모델에 남깁니다. 상대 URL·허용하지 않는 주소 종류(scheme)·주소 식별자(URI) 파싱 실패는 링크 이름 텍스트가 됩니다. | `LinkPolicy`, 파서의 입력과 기대값 |
| AC-15 | 수식 밖의 `&amp;` 같은 HTML 문자 표기(entity)와 `\*` 같은 기호 이스케이프(escape)는 해석합니다. 형식이 잘못된 수식 구분자의 역슬래시는 보존합니다. 문자 표기가 내부 임시 표시자와 충돌해 내용을 지우지 않아야 합니다. | `decodedEntityCannotCollideWithOpaqueMathMarker`, 이스케이프 입력과 기대값 |
| AC-16 | `StreamingTail`은 원문을 분석한 문서 구조(AST)를 바꾸지 않습니다. 마지막 텍스트 조각(Text run)의 닫히지 않은 기호를 표시에서만 숨기되 문단 전체가 비면 유지합니다. | `StreamingTail.kt`, `StreamingTailTest.kt` |
| AC-17 | `fadePlan`은 끝의 연속 텍스트 조각만 grapheme 단위로 나누어 흐리게 표시합니다. 코드·수식·링크·줄바꿈에서 멈추며 마지막 조각의 불투명도(alpha)는 0.2입니다. | `StreamingTail.kt`, `StreamingTailTest.kt` |

스트리밍 기호 숨김은 일부 상황을 구별하지 못하는 추정 규칙(휴리스틱)입니다. 이미 파서가 이스케이프를 해석한 일반 텍스트와 수식 기호를 완전히 구분할 수는 없습니다. 일반 표시로 돌아가면 원문을 다시 보여 주는 책임은 상위 렌더러에게 있으며, grapheme 경계는 실행 환경의 `BreakIterator` 구현을 따릅니다.

## 작업 실행

요청 합치기는 실행 중 1건은 유지하고 대기 중인 1건을 최신 입력으로 교체하는 방식입니다. `generation`은 호출자가 붙이는 요청 순서번호이며, 잠금(lock) 안에서 지금까지 받은 최대 번호와 비교합니다. 코루틴의 수명을 묶어 관리하는 scope가 취소되면 상태를 정리하지만 취소된 scope 자체를 복구하지는 않습니다.

ATOMIC은 시작 전 취소에도 코루틴 본문에 진입하게 하는 시작 방식입니다. 이 코드에서는 `try` 안의 첫 취소 검사로 실제 입력 실행을 막고 `finally`에서 상태를 정리하는 데 사용합니다. 이미 실행 중인 작업을 즉시 강제로 종료하는 기능은 아닙니다.

| ID | 요구와 현재 동작 | 근거·테스트 선언 |
| --- | --- | --- |
| AC-18 | `CoalescingWorker`는 실행 최대 1개와 최신 대기 입력(pending) 최대 1개를 유지합니다. 실행 중 입력을 강제 취소하지 않습니다. | `latestWinsAndSingleConcurrency` |
| AC-19 | 요청번호를 받는 함수 형태(overload)는 잠금 안에서 최대 수신 번호 이하의 제출을 무시합니다. 호출자는 계속 증가하는 `Int` generation을 제공해야 합니다. | `lowerGenerationCannotReplaceNewerPendingInput`, 실제 스레드 동시 제출 테스트 |
| AC-20 | `submit`은 여러 스레드에서 안전하게 호출하며 `perform`은 잠금 밖에서 실행합니다. `awaitIdle`은 실행·대기가 없는 상태를 기다립니다. ATOMIC으로 시작하고 첫 작업 전에 취소를 검사해, 시작 전·진행 중 취소나 작업 예외에서 대기·실행 상태를 해제합니다. 취소된 scope의 입력은 실행하지 않습니다. | `CoalescingWorker.kt`; `exceptionDropsPendingAndLiveScopeCanRestart`, `cancellationBeforeDrainStartsReleasesIdle`, `cancellationDuringDrainDropsPending` |

## 내부 API와 검증 한계

`RichMarkdownParser.parse/scanInlineMathSpans`, `InputLimits.bound/BoundedInput`, `Utf16Range`, `ParsedDocument/ParsedBlock/InlineRun/MathSegment`, `DollarMathOptions`, `MathScanner/MathProtector`, `StreamingTail`, `CoalescingWorker`가 화면 표시 코드에서 사용하는 주요 API입니다. Kotlin에서 public으로 선언됐더라도 모두 안정적인 외부용 API라는 뜻은 아닙니다.

근거 파일은 [소스](../../../richmarkdown-core/src/main/)와 [JVM 테스트](../../../richmarkdown-core/src/test/)에 있습니다. 테스트 선언과 실행 성공을 구분하며 실행 결과·플랫폼 범위는 [검수 기록](../validation.md)에 정리합니다. 성능 수치를 추정하지 않습니다.

반복해서 쓰는 용어는 [보고서 용어 안내](../glossary.md)에서 확인할 수 있습니다.
