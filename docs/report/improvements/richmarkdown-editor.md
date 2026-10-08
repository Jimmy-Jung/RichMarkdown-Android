# richmarkdown-editor 개선 기록

기준일: 2026-10-08 · 상태: **AE-I01~AE-I04·AE-I14 구현·회귀 추가, AE-I13 해결, AE-I05~AE-I12 미해결**

블록 편집기를 iOS에서 옮기면서 iOS 동작과 다르게 고친 항목과, 이번 작업에서 남긴 한계를 구분합니다. 근거는 [편집기 소스](../../../richmarkdown-editor/src/main/kotlin/io/github/jimmyjung/richmarkdown/editor/), [JVM 테스트](../../../richmarkdown-editor/src/test/), [Android 테스트](../../../richmarkdown-editor/src/androidTest/)입니다. 미해결 항목의 문구는 소스의 `ponytail:` 주석과 KDoc에 적힌 한계를 따릅니다. 실행 결과는 [검수 기록](../validation.md#2026-10-08-블록-편집기-추가-검증)에 있습니다.

## AE-I01: 모델이 무시·변형한 편집 뒤 화면 재동기화

**상태: 구현·회귀 추가.** 편집 콜백이 끝나면 앱이 넘긴 최신 상태로 다시 그립니다. 앱이 콜백 안에서 `setState`를 호출하지 않으면 모델이 편집을 반영하지 않은 것으로 보고 마지막 상태로 그립니다.

- **문제 상황:** 빈 문단 Enter는 모델을 바꾸지 않고, 빈 목록 Enter와 인용 시작 Backspace는 종류만 바꾸며, 코드 블록 바로 뒤 Backspace는 caret만 옮깁니다. 이때 사용자가 입력한 줄바꿈이나 삭제가 화면에만 남으면 화면 문자열과 `documentText`가 달라집니다.
- **회귀:** `resyncsAfterIgnoredOrTransformedEdits`는 위 네 경우에서 화면 문자열과 모델 문서, caret을 비교합니다. `composeWrapperResyncsAfterIgnoredAndAcceptedEdits`는 Compose 래퍼가 다음 재구성에서 같은 상태로 돌아오는지 확인합니다.

## AE-I02: 렌더된 수식 뒤 Backspace의 원문 보존

**상태: 구현·회귀 추가.** 화면 문자열을 `documentText`와 같게 두고 수식은 원문 위 `ReplacementSpan`으로 덮습니다.

- **문제 상황:** 수식 이미지 한 글자와 보이지 않는 채움 문자로 길이를 맞추는 방식에서는 화면에서 지운 문자와 모델에서 지워지는 원문 문자가 다를 수 있습니다.
- **회귀:** `backspaceAfterRenderedInlineMathMatchesModel`은 렌더된 인라인 수식 바로 뒤에서 Backspace를 보내고, 앱에 전달된 범위로 모델을 바꾼 결과와 화면 문자열이 같은지 확인합니다. `styledStringEqualsDocumentTextForVariedDocument`는 여러 블록 종류에서 스타일링 결과가 `documentText`와 같은지 확인합니다.

## AE-I03: 조합 중 도구 모음 명령

**상태: 구현·회귀 추가(커밋 `eed41a3`).** 조합 중 명령을 무시하던 처음 구현을 바꿨습니다. 조합을 확정해 `onReplaceText`로 한 번 전달하고, `InputMethodManager.restartInput`으로 IME 조합 상태를 버린 뒤, 확정 뒤 선택으로 `onToolbarAction`을 호출합니다.

- **문제 상황:** Gboard 등 영문 키보드는 입력 중인 단어 전체를 조합 영역으로 둡니다. 명령을 무시하면 영문 단어를 입력하는 동안 굵게·종류 변환 등을 쓸 수 없습니다.
- **회귀:** `toolbarCommitsCompositionOnceThenUsesCommittedSelection`은 한글 음절과 영문 단어 조합 중 굵게 명령을 보내 교체 1회·명령 1회·확정 뒤 caret을 확인합니다. 이전 IME 연결의 `finishComposingText`가 같은 글자를 다시 보내지 않는지도 검사합니다. `toolbarDuringRecomposedExistingWordSendsNoReplacement`는 기존 단어를 다시 조합하는 중이면 교체 없이 명령만 가는지 확인합니다.
- **남은 확인:** 실제 Gboard 소프트 키보드에서 누른 결과는 확인하지 않았습니다([AE-I12](#ae-i12-실기기-ime클립보드화면-확인)).

## AE-I04: 서로게이트 쌍 중간 범위 거절

**상태: 구현·회귀 추가.** 시작이나 끝이 서로게이트 쌍 가운데인 범위를 모델이 거절합니다.

- **문제 상황:** iOS는 이런 범위를 앞쪽 경계로 내림 보정합니다. iOS 소스를 컴파일한 차등 비교에서 이 보정을 따르면 원문 일부가 사라지는 결과가 나왔고, 이모지·결합 문자 입력 1,500건의 차이 113건 중 81건이 이 항목이었습니다.
- **회귀:** `surrogateMiddleRangesAreRejected`가 모델 API별 거절을 검사합니다.

## AE-I05: 인용·코드·수식의 오른쪽 여백

**상태: 미해결.** iOS는 인용에 16pt, 코드·수식에 12pt 오른쪽 들여쓰기(`tailIndent`)를 적용합니다. Android에는 오른쪽 들여쓰기 span이 없어 `BlockParagraphSpan`은 왼쪽 여백만 적용합니다. 줄 바꿈 폭을 줄이는 별도 구현이 필요합니다.

## AE-I06: 문단 마지막 줄 caret 높이

**상태: 미해결.** 문단 간격을 마지막 줄의 아래 여백(descent)에 더하므로 문단 마지막 줄의 caret이 그만큼 깁니다. 간격을 다음 문단 위쪽으로 옮겨도 같은 문제가 생기므로, 고치려면 caret을 직접 그려야 합니다.

## AE-I07: 편집마다 문서 전체를 다시 스타일링합니다

**상태: 미해결 · 성능 미측정.** iOS와 같이 편집마다 문서 전체를 다시 스타일링하므로 비용이 블록 수에 비례합니다. 인라인 수식 찾기 결과는 블록 단위로 캐시하지만 span 생성과 문단 배치는 다시 수행합니다. 긴 문서에서 지연이 측정되면 바뀐 블록의 span만 교체하는 방식으로 바꿉니다.

## AE-I08: Compose 래퍼의 재동기화는 다음 재구성까지 늦습니다

**상태: 미해결 · 영향 미측정.** Compose 래퍼는 상태를 콜백 뒤의 다음 재구성에서 넘깁니다. 편집 뷰는 콜백 직후 다시 그리지 않고 선택만 맞춘 채 다음 `setState`를 기다리며, 래퍼는 편집 콜백마다 `revision`을 올려 다음 재구성에서 반드시 상태를 다시 적용합니다. 그 사이에는 사용자가 입력한 그대로의 문자열이 보일 수 있습니다. 예를 들어 빈 문단 Enter의 줄바꿈은 다음 재구성에서 사라집니다. View 호스트는 콜백 안에서 `setState`를 호출하므로 이 지연이 없습니다.

## AE-I09: 블록 수식 비트맵을 벡터로 바꾸기

**상태: 미해결.** 편집기의 블록 수식은 `MathRenderService` 비트맵을 `DisplayMathSpan`으로 그리고, 텍스트 폭보다 넓으면 줄입니다. 확대·색 변경마다 비트맵을 다시 만듭니다. 렌더 모듈의 블록 수식처럼 `MathVectorLayout`으로 그리면 선명도와 메모리 비용이 달라지지만 비교 측정은 하지 않았습니다.

## AE-I10: 결합 문자가 붙은 서식 기호의 코덱 순회 단위

**상태: 보류.** iOS 코덱은 Swift `Character`(문자 묶음) 단위로 순회하지만 Android 코덱은 UTF-16 단위로 순회합니다. 서식 기호가 모두 ASCII이므로 `*` 뒤에 결합 문자가 붙는 경우처럼 드문 입력에서만 결과가 다릅니다. 차등 비교의 차이 113건 중 32건이 이 항목입니다. Swift 문자열 비교의 정규화 동일시(NFC·NFD)도 재현하지 않습니다. 실제 사용 보고가 있으면 문자 묶음 단위 순회로 바꿉니다.

## AE-I11: 데모 화면 회전 시 문서 초기화

**상태: 미해결(데모).** 편집 뷰는 텍스트를 인스턴스 상태로 저장하지 않습니다. 데모 `BlockEditorActivity`는 모델을 `remember`로만 보관하므로 화면 회전처럼 Activity가 다시 만들어지면 첫 문서로 돌아갑니다. 라이브러리 계약은 앱이 모델을 보존하는 것이므로, 데모에서 `model.markdown` 저장·복원을 추가하면 해결됩니다.

## AE-I12: 실기기 IME·클립보드·화면 확인

**상태: 미확인.** 다음은 이번 작업에서 확인하지 않았습니다.

- 에뮬레이터 스크린샷의 수식 글자 표시를 사람이 직접 보지 않았습니다. 첫 확인에서 목록·체크박스·인용·칩·서식은 정상이었고 수식은 테스트의 글꼴 로딩 순서 때문에 비어 있었습니다. 순서는 고쳤지만 고친 뒤의 화면은 보지 않았습니다.
- 실제 Gboard 소프트 키보드의 조합과 도구 모음 누르기는 계측 테스트의 IME 연결 호출로만 확인했습니다.
- 실기기 클립보드 복사·붙여넣기는 확인하지 않았습니다.
- 블록 편집 정지컷·GIF(`09-block-editor.png`·`10-block-editor.gif`)는 `ONLY=block-editor scripts/capture-demo-screens.sh emulator-5554`로 에뮬레이터에서만 촬영했고, 프레임은 사람이 직접 보지 않았습니다.
- 하드웨어 키보드의 Ctrl+Z 전달은 미해결·미확인입니다. 2026-10-08 에뮬레이터(Gboard)에서 `adb shell input text`로 ` Phase3`를 입력하고 단어를 선택해 굵게·인용 변환을 적용한 뒤 `input keycombination KEYCODE_CTRL_LEFT KEYCODE_Z`를 보내자, 인용 변환은 그대로이고 마지막 입력 글자 `3`만 지워졌습니다. Gboard가 키를 먼저 처리해 편집기 `onKeyShortcut`의 모델 undo까지 오지 않은 것으로 보이며 원인은 확인하지 않았습니다. 글자 입력 없이 종류만 바꾼 뒤의 Ctrl+Z는 같은 에뮬레이터에서 모델 undo로 되돌아갔습니다. 같은 날 실기기 SM-G988N(삼성 키보드)에서는 선택 뒤 Ctrl+Z가 모델 undo로 전달되었습니다. 실제 하드웨어 키보드에서는 확인하지 않았으므로 데모 GIF는 도구 모음의 실행 취소 버튼을 누릅니다.

## AE-I13: Maven 배포 파일 생성과 검수

**상태: 해결(0.3.0).** 0.2.0 Release ZIP에는 이 모듈이 없었습니다. 0.3.0에서 버전을 올린 뒤 다른 네 모듈과 함께 `publishToMavenLocal`로 Release AAR·POM·Gradle metadata·sources/Javadoc JAR를 생성하고 좌표·내부 의존성 버전·minSdk를 대조해 `richmarkdown-android-0.3.0-maven.zip`에 포함했습니다. 결과는 [검수 기록](../validation.md#2026-10-08-030-릴리스-검증)에 있습니다. Maven Central 발행은 별도 단계입니다.

## AE-I14: 서식 적용 시 한글 줄 높이 변화

**상태: 구현·회귀 추가.** `BlockDocumentEditText`의 `isFallbackLineSpacing`을 끕니다.

- **문제 상황:** 실기기(SM-G988N, Android 13)에서 제목 일부에 굵게를 걸면 글자 굵기는 그대로인데 줄 높이가 커졌습니다. 측정 결과 서체 객체와 글꼴 metrics는 같았고, 기본값인 fallback 줄 간격이 켜져 있을 때만 굵게·기울임 span이 줄을 여러 run으로 나누면서 한글 fallback 글꼴의 metrics가 줄 높이에 더해졌습니다(제목 137→149px, 본문 92→99px). 끈 상태에서는 두 경우 모두 높이가 같았습니다.
- **회귀:** `inlineEmphasisKeepsLineHeight`는 제목과 본문에서 굵게 전후의 첫 줄 높이를 비교합니다. 에뮬레이터(API 37)와 SM-G988N(Android 13)에서 통과했습니다.
- **남은 한계:** fallback 글꼴이 주 글꼴보다 큰 문자(태국어 등)는 줄 경계를 조금 넘어 그려질 수 있습니다. 렌더 모듈의 `TextView`·Compose 텍스트는 이 설정을 바꾸지 않았으므로, 한글 줄에서 굵게가 섞인 줄의 높이가 다른 줄과 다를 수 있는지는 확인하지 않았습니다.

## iOS 측 관찰

아래는 Android 구현 중 iOS 소스를 읽고 의심한 동작입니다. iOS에서 재현하지 않았고 iOS 코드는 바꾸지 않았습니다.

1. **무시한 편집 뒤 화면·모델 불일치 가능성.** iOS `BlockDocumentTextEditor.swift`의 `reconcileTextChange`는 `onReplaceText` 뒤 `baselineText`를 화면 문자열로 바꾸고 선택만 적용합니다. 모델이 빈 문단 Enter처럼 편집을 반영하지 않아 블록이 그대로이면, 화면에 입력한 줄바꿈이 남아 모델 문서와 달라질 수 있습니다. Android는 AE-I01로 다시 그립니다.
2. **렌더된 수식 뒤 Backspace의 숨은 원문 삭제 가능성.** iOS `MarkdownStyler.swift`는 수식을 이미지 한 글자와 U+2063 채움 문자(원문 길이 − 1개)로 표시합니다. 렌더된 수식 바로 뒤의 Backspace는 화면에서 채움 문자 하나를 지우고, 같은 위치의 원문 문자 하나가 모델에서 지워질 수 있습니다. Android는 AE-I02로 화면 문자열을 원문과 같게 둡니다.
