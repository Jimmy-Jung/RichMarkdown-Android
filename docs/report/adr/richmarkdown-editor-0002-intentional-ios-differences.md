# ADR-0002: iOS와 의도적으로 다르게 둔 편집 동작

상태: 채택 — 구현 기록 · 확인일: 2026-10-08

## 문제

편집기의 모델·코덱과 편집 뷰는 iOS `RichMarkdownBlockEditor`를 옮겼습니다. 그러나 문자 경계 API, IME 동작, 텍스트 뷰의 첨부 방식이 플랫폼마다 다릅니다. iOS 동작을 그대로 따르면 Android에서 데이터 손실이나 화면·모델 불일치, 사용할 수 없는 도구 모음이 생기는 지점이 있었습니다.

## 현재 선택

| 차이 | Android 선택 | 이유 |
| --- | --- | --- |
| 문자 묶음 경계 | Swift `Character` 경계 대신 `java.text.BreakIterator.getCharacterInstance()`를 사용합니다. | Kotlin에는 Swift `Character`에 해당하는 타입이 없습니다. |
| 서로게이트 쌍 중간 범위 | 시작·끝이 서로게이트 쌍 가운데인 범위를 거절합니다. | iOS의 내림 보정을 따르면 iOS 소스와의 차등 비교에서 원문 일부가 사라지는 결과가 나왔습니다([AE-I04](../improvements/richmarkdown-editor.md#ae-i04-서로게이트-쌍-중간-범위-거절)). |
| 줄바꿈 문자 | Markdown 입력과 문서 교체 문자열의 CRLF·CR을 LF로 바꿉니다. | Swift는 `\r\n`을 한 `Character`로 다루지만 Kotlin은 두 문자로 다룹니다. |
| 공백 제거 | 코드 fence 판정에 Swift `.whitespaces`와 같은 규칙(유니코드 공백 구분자와 탭)을 사용합니다. | Kotlin `trim()`은 줄바꿈과 제어 문자까지 지웁니다. |
| 화면 문자열 | `documentText`와 정확히 같게 둡니다. 수식은 원문 위 `ReplacementSpan`으로 표시합니다. | iOS처럼 이미지 한 글자와 U+2063 채움 문자를 쓰면 화면 문자열이 원문과 달라집니다. Android에서는 렌더된 수식 뒤 Backspace도 화면과 모델에서 같은 범위를 지웁니다([AE-I02](../improvements/richmarkdown-editor.md#ae-i02-렌더된-수식-뒤-backspace의-원문-보존)). |
| 모델이 무시한 편집 | 편집 콜백 뒤 항상 앱의 최신 상태로 다시 그립니다. | 빈 문단 Enter처럼 모델이 바꾸지 않는 편집 뒤에도 화면과 모델을 같게 유지합니다([AE-I01](../improvements/richmarkdown-editor.md#ae-i01-모델이-무시변형한-편집-뒤-화면-재동기화)). |
| 조합 중 도구 모음 | 조합을 확정해 한 번 전달하고 IME를 다시 시작한 뒤 명령을 보냅니다. | Gboard는 입력 중인 영문 단어 전체를 조합 영역으로 두므로 iOS처럼 무시하면 영문 입력 중 도구 모음을 쓸 수 없습니다([AE-I03](../improvements/richmarkdown-editor.md#ae-i03-조합-중-도구-모음-명령)). |
| 실행 취소 입력 | `EditText` 자체 실행 취소 대신 Ctrl+Z·Ctrl+Shift+Z·메뉴를 모델 명령으로 보냅니다. | 실행 취소 기록을 모델 하나에만 둡니다. |
| 클립보드 payload | `ClipDescription` extras와 MIME 형식 표시에 JSON을 싣습니다. | Android `ClipData.Item`은 텍스트·HTML·Intent·URI를 담는 구조라 블록 JSON은 클립 설명의 extras에 싣습니다. JSON 형태는 iOS Codable과 같게 둡니다. |

모델·코덱 동작은 위 차이를 제외하면 iOS와 같게 유지합니다. iOS 소스를 컴파일한 차등 비교에서 이모지·결합 문자가 없는 입력과 긴 입력 8,000건은 차이가 없었습니다. 이모지·결합 문자 입력 1,500건의 차이 113건은 서로게이트 거절(81건)과 코덱의 UTF-16 단위 순회(32건)로 설명됩니다. 비교 스크립트는 저장소에 포함하지 않았습니다.

## 대안과 비용

| 대안 | 비용 |
| --- | --- |
| iOS 동작을 그대로 복제 | 서로게이트 내림 보정의 원문 손실, 무시한 편집 뒤 화면·모델 불일치, 영문 조합 중 도구 모음 사용 불가를 그대로 가져옵니다. |
| 도구 모음을 조합 중 비활성화 | Gboard에서는 영문 단어를 입력하는 동안 거의 항상 비활성화됩니다. |
| iOS와 같은 채움 문자 사용 | 화면 위치와 모델 위치가 수식마다 달라져 편집 환원과 IME 상태 유지가 복잡해집니다. |

현재 선택은 같은 입력에 대해 플랫폼마다 결과가 다를 수 있다는 비용이 있습니다. 서로게이트 쌍 가운데 범위를 넘기는 호출은 Android에서 실패합니다. 조합 중 도구 모음을 누르면 iOS와 달리 조합 중이던 글자가 확정됩니다.

## 재검토 기준과 근거

iOS가 서로게이트 범위·채움 문자·무시한 편집의 처리 방식을 바꾸면 차등 비교를 다시 실행하고 이 표를 갱신합니다. 결합 문자가 붙은 서식 기호의 실제 사용 보고가 있으면 코덱을 문자 묶음 단위 순회로 바꾸는 것을 검토합니다. iOS에서 관찰한 두 가지 의심 동작은 iOS 코드를 바꾸지 않고 [개선 기록](../improvements/richmarkdown-editor.md#ios-측-관찰)에 남겼습니다.

근거는 [편집기 소스](../../../richmarkdown-editor/src/main/kotlin/io/github/jimmyjung/richmarkdown/editor/)의 `TextBoundaries.kt`, `BlockDocumentEditText.kt`, `MarkdownStyler.kt`, `EditorSpans.kt`와 `AndroidPortContractTest`, `BlockDocumentEditTextTest`입니다.
