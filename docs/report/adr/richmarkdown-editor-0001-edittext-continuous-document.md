# ADR-0001: EditText 하나의 연속 문서와 앱이 소유한 블록 모델

상태: 채택 — [D1a](../../../DEVELOPMENT.md#2-결정-ledger) 구현 기록 · 확인일: 2026-10-08

## 문제

블록 편집기는 제목·목록·할 일·인용·코드·수식을 블록 단위로 다루면서도, 사용자가 여러 블록에 걸쳐 선택·복사·삭제할 수 있어야 합니다. 한글처럼 IME 조합이 필요한 입력과 Android의 텍스트 선택 핸들·맞춤법 제안도 그대로 동작해야 합니다. 저장 형식은 iOS `RichMarkdownBlockEditor`와 같은 Markdown이어야 두 플랫폼의 문서가 같은 의미로 왕복합니다.

## 현재 선택

세 가지를 함께 선택했습니다.

1. **화면은 `EditText` 하위 클래스 하나입니다.** `BlockDocumentEditText`는 모든 블록을 줄바꿈으로 이은 연속 문서 하나를 표시합니다. 블록 경계를 넘는 선택·복사·삭제는 Android 텍스트 선택이 그대로 처리합니다.
2. **블록 구조는 화면 문자열이 아니라 모델에 있습니다.** 화면 문자열은 `documentText`와 정확히 같고, 목록 마커·체크박스·인용 바·수식 이미지는 span으로만 표시합니다. 사용자 편집은 UTF-16 범위 교체 하나로 바꿔 모델에 전달하고, `replaceDocumentText`가 분할·병합·종류 변환으로 환원합니다.
3. **앱이 `BlockEditorModel`을 소유합니다.** 편집 뷰는 콜백으로 편집을 전달하고 `setState`로 결과를 받아 다시 그립니다. 뷰 안에 두 번째 모델을 두지 않으므로 Compose·View·도구 모음이 같은 모델 객체를 봅니다.

모델·코덱은 iOS Swift 소스를 순수 Kotlin으로 옮겼고 iOS 모델·코덱 테스트 47개를 함께 옮겼습니다. Compose 함수는 같은 `EditText`를 `AndroidView`로 감쌉니다.

## 대안과 비용

| 대안 | 장점 | 현재 선택과 비교한 비용 |
| --- | --- | --- |
| Compose `BasicTextField`·`TextFieldState` | Compose 상태와 바로 연결됩니다. | 블록별 문단 장식, 원문 위 수식 이미지, IME 조합 중 서식 유지를 이 API로 표현할 수 있는지 확인하지 않았습니다. View 앱에는 별도 호스팅이 필요합니다. |
| 블록마다 입력 필드 하나 | 블록 단위 레이아웃과 재사용이 단순합니다. | 여러 블록에 걸친 선택·복사·삭제를 직접 구현해야 하며, 블록 사이 포커스 이동과 IME 연결을 필드마다 따로 다뤄야 합니다. |
| WebView 기반 편집기 | 웹 편집 라이브러리를 재사용할 수 있습니다. | 렌더 모듈의 WebView 비의존 원칙과 맞지 않고, 브리지 비용과 Markdown 계약의 이중 구현이 생깁니다. |
| 편집 뷰가 모델을 소유 | 앱 연결 코드가 줄어듭니다. | 도구 모음·저장·실행 취소를 앱이 같은 모델로 다루기 어렵고 iOS 호스트 계약과 달라집니다. |

현재 선택의 비용도 있습니다.

- 편집할 때마다 문서 전체를 다시 스타일링하므로 긴 문서에서 느려질 수 있습니다. 성능은 측정하지 않았습니다([AE-I07](../improvements/richmarkdown-editor.md#ae-i07-편집마다-문서-전체를-다시-스타일링합니다)).
- Android에는 오른쪽 들여쓰기 span이 없어 인용·코드·수식의 오른쪽 여백을 적용하지 않습니다([AE-I05](../improvements/richmarkdown-editor.md#ae-i05-인용코드수식의-오른쪽-여백)).
- 렌더 모듈의 span·글꼴 해석·칩 그리기를 `@InternalRichMarkdownApi`로 공개해 공유하므로, 두 모듈을 같은 버전으로 함께 배포해야 합니다.
- Compose 래퍼는 상태를 다음 재구성에서 받으므로 편집과 재동기화 사이에 지연이 있습니다([AE-I08](../improvements/richmarkdown-editor.md#ae-i08-compose-래퍼의-재동기화는-다음-재구성까지-늦습니다)).

## 재검토 기준과 근거

긴 문서에서 편집 지연이 측정되면 블록 단위 span 비교로 다시 그리는 범위를 줄이는 방법부터 검토합니다. Compose 텍스트 입력 API가 문단 장식·인라인 이미지·IME 조합 중 서식을 지원한다는 것이 확인되면 Compose 전용 구현을 다시 비교합니다. 렌더 모듈의 내부 API 변경이 잦아지면 공유 span을 별도 모듈로 분리하는 방법을 검토합니다.

근거는 [편집기 소스](../../../richmarkdown-editor/src/main/kotlin/io/github/jimmyjung/richmarkdown/editor/)의 `BlockDocumentEditText.kt`, `BlockEditorModel.kt`, `MarkdownStyler.kt`, `compose/BlockDocumentTextEditor.kt`와 [JVM 테스트](../../../richmarkdown-editor/src/test/)·[Android 테스트](../../../richmarkdown-editor/src/androidTest/)입니다. 실행 결과와 확인하지 않은 범위는 [검수 기록](../validation.md#2026-10-08-블록-편집기-추가-검증)을 따릅니다.
