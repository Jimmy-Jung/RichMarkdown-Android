# richmarkdown 개선 제안

기준일: 2026-10-07 · 승인 후 구현·회귀 기록

개선 전 관찰, 당시 검토한 변경, 실제 반영한 코드를 구분합니다. 근거는 [소스](../../../richmarkdown/src/main/), [JVM 테스트](../../../richmarkdown/src/test/), [Android 테스트](../../../richmarkdown/src/androidTest/)입니다. 이번 문서 보완에서는 빌드·테스트를 새로 실행하지 않았으며 기존 실행 성공과 최종 확인 범위는 [검수 기록](../validation.md)에 있습니다.

## AR-I01: Compose 로컬 비동기 결과의 요청 일치 확인

**상태: 구현·회귀 추가.** 요청 일치 기준(identity)은 두 입력을 같은 작업으로 볼지 판단하는 비교값입니다. Compose 화면 내부의 코드 상태(state)는 원문·언어·`RichMarkdownCodeBlockOptions`의 구현 객체 참조, 수식 상태는 수식 생성 조건(render key)·배치 공급자(layout loader)별로 분리했습니다. `remember`는 조건이 바뀌면 새 상태를 만들고 `LaunchedEffect`는 작업이 받은 상태에만 결과를 쓰므로, 새 요청은 기본 색 코드(plain)/수식 원문(source)으로 시작합니다.

`ComposeResultIdentityTest`는 A 결과 표시 후 B 작업이 보류된 동안의 표시를 제어합니다. 코드는 원문·언어·값은 같아도 다른 객체인 구현체 교체를 검사하고, 수식은 원문·색·크기·공급자 교체와 취소를 무시한 이전 작업의 지연 완료를 검사합니다. 수식 loader 인자는 내부 표시 함수(composable)에만 추가했으며 기본값은 공유 서비스(shared)입니다.

- **개선 전 관찰:** `CodeBody`와 `BlockMathView`는 작업을 다시 시작하는 비교값(key)이 바뀌어도 `produceState`가 기억한 같은 결과 상태를 읽었습니다. 따라서 이전 코드 색이나 선·글자 모양을 직접 그리는 수식 윤곽선(vector)이 새 입력의 화면에 남을 수 있었습니다.
- **발생 조건과 영향:** 같은 Compose 표시 위치에서 A 결과를 만든 뒤 B로 교체하고, B의 코드 요소 분류(토큰화)·수식 배치 계산을 늦추면 이전 결과가 B 화면에 잠시 남을 가능성이 있었습니다. 소스의 `initialValue` 재설정을 의도한 주석만으로 이 표시를 차단했다고 볼 수는 없습니다.
- **판단 근거:** Compose 공식 문서는 `produceState`가 `remember`로 상태를 보관하고 key가 바뀌면 결과를 만드는 작업(producer)을 취소·재시작한다고 설명합니다. 이전 결과가 남을 수 있다는 판단은 이 동작과 기존 호출 코드를 함께 읽은 해석입니다. [Android Developers: produceState](https://developer.android.com/develop/ui/compose/side-effects#producestate).
- **당시 검토한 변경:** 비동기 결과를 원문·언어·구현 객체 또는 수식 조건과 연결해 현재 입력과 일치하는 결과만 읽도록 합니다. 일치하지 않으면 기본 색 코드·수식 원문을 표시합니다. 작업 시작 시 결과를 비우는 방식만으로 화면 재계산(재구성) 직후의 이전 결과 표시까지 막았다고 판단하지 않습니다.
- **검사 설계와 반영:** A 성공 → B 작업 보류 → B의 표시가 기본 색 코드/수식 원문인지 확인하는 순서로 같은 UI 위치를 재사용합니다. 코드 언어·색 공급자(highlighter) 교체와 수식 색·크기·배치 공급자 교체를 포함합니다. 수식에서는 A의 늦은 완료 → B 완료 순서도 제어해 취소에 응답하지 않는 공급자의 결과가 현재 화면을 덮지 않는지 검사합니다.

## AR-I02: 직접 Request 생성의 입력 제한 경계

**상태: 구현·회귀 추가.** 모델 `submit`과 Core `parse(BoundedInput, …)`에서 바이트 수·인용 깊이를 다시 제한합니다. 기존 잘림 여부(flag)는 OR 연산으로 보존하며 모델의 원문 대체 표시(fallback)·캐시(cache)·작업 처리기(worker)에는 제한 규칙에 맞게 정리한 복사본을 보관합니다. 공개 Request 생성자와 `submit(request = …)` 이름은 유지했습니다.

기존 개선 기록에서는 변경 전 Core 직접 생성 테스트의 기대값 검사(assertion)가 실패함을 확인했습니다. `directRequestsBoundFallbackBeforeParsingAndPreserveTruncation`과 Core `directlyConstructedBoundedInputCannotBypassLimits`는 같은 과대 원문·깊은 인용·기존 잘림 상태를 다룹니다.

- **개선 전 관찰:** `Request.of`와 두 UI가 적용하는 제한을 Request·BoundedInput 직접 생성으로 우회할 수 있었습니다.
- **발생 조건과 영향:** `BoundedInput(상한 초과 원문, false)`로 직접 Request를 만들어 모델에 제출하면 입력·대체 원문 상한을 우회할 수 있었습니다. 공식 UI를 통한 정상 경로에는 해당하지 않습니다.
- **당시 검토한 변경:** `Request` 생성 단계에서 제한하거나 모델의 입력 경로(ingress)에서 다시 검사합니다. 원문과 잘림 여부를 함께 정리하되 기존 사용 API와의 호환성을 먼저 확인합니다.
- **검사 설계와 반영:** 직접 생성, `Request.of` 같은 생성 함수(factory), 두 UI 경로에서 같은 과대 원문·깊은 인용을 넣어 크기를 제한한 원문을 보관하는지 비교합니다. 모델 직접 요청과 Core 직접 입력은 위 회귀 테스트로 연결하며, View의 제한 후 프로퍼티 값은 `ViewRendererIntegrationTest`가 확인합니다.

## AR-I03: 게시 계약을 Android 실행 테스트로 연결

**상태: 통합 회귀 추가.** `RenderModelIntegrationTest`는 메인 스레드 반영, 메시지 교체, 원문 뒤 덧붙이기(append), 문서를 먼저 표시한 뒤 완성된 수식 이미지로 채우는 처리(hydration)를 확인합니다. 수식 색/크기 변경 시 이전 이미지 사용 중단, 코루틴 작업 범위(scope) 취소, 메인 스레드 밖 submit 거부도 다룹니다.

`ViewRendererIntegrationTest`는 실제 화면 연결/분리(attach/detach), 같은 모델 재사용, 새 메시지, 크기 콜백, 프로퍼티 읽기(getter)의 제한 원문을 확인합니다. `ComposeResultIdentityTest`는 같은 Compose 위치의 비동기 결과 교체를 제어합니다. 모델의 미완료 여부(outstanding)는 요청 순서 번호(generation)의 별도 완료 기록 대신 실제 worker 상태를 조회하므로 취소 후 정리(cleanup)가 끝나면 false가 됩니다.

- **개선 전 관찰:** 기존 순수 로직·엔진 테스트만으로 메인 스레드 결과 반영·UI 수명을 확인할 수 없었습니다.
- **발생 조건과 영향:** 개별 로직·엔진 테스트가 통과해도 A 요청 결과가 B 화면에 반영되는 회귀나 화면 분리/재사용의 수명 문제가 빠질 수 있습니다. 이 회귀를 이번 문서 보완에서 새로 재현한 것은 아닙니다.
- **당시 검토한 변경:** 기존 Android 테스트에 두 요청 순서를 제어하는 모델·두 UI의 통합 사례를 추가합니다. 요청 취소와 마지막 반영값, 크기 콜백을 함께 확인하고 테스트 제어에 필요한 범위만 추가합니다.
- **검사 설계와 반영:** 메시지 교체·원문 덧붙이기·색/크기 변경·scope 취소, View 연결/분리/재사용, Compose 같은 위치 재구성의 마지막 원문·요청 일치를 확인합니다. 취소된 scope에 요청을 제출해도 미완료 상태가 남지 않는 검사는 `canceledModelScopeHasNoOutstandingWorkOrLatePublication`에 있습니다.

공유 수식 캐시가 지난 요청 순서 번호의 완성된 비트맵을 저장하는 정책은 수식 조건(content key)으로 결과를 재사용하는 목적과 일치합니다. 이를 지난 요청 결과가 현재 화면을 덮는 결함(stale)으로 분류하지 않습니다. 중복 계산·캐시 비용은 필요할 때 별도로 측정하며, 최신 화면 반영 규칙을 이미 시작한 동기 계산의 즉시 중단 보장으로 확대하지 않습니다.

## AR-I04: 앱 지정 코드 색 범위와 다이어그램 뷰 해제

**상태: 구현·회귀 추가.** View가 앱 지정 코드 색 공급자의 원문 밖 범위를 잘라 색칠하거나 겹침을 허용하던 처리를 바꿨습니다. Compose와 같이 시작 위치순으로 유효하고 겹치지 않는 범위만 반영하며 결과가 돌아온 뒤 작업 취소 여부도 확인합니다. `ViewHighlightBoundsTest`는 원문을 보존하고 유효한 범위만 색칠하는 규칙을 검사합니다.

색 범위는 UTF-16의 16비트 단위로 표현합니다. 예를 들어 이모지 `😀`는 한 글자처럼 보여도 두 단위이므로 화면 글자 수 기준으로 범위를 만들지 않습니다.

다이어그램에는 부모의 다크 모드(dark)를 전달하는 추가 인자 함수(overload)와 `disposeView`를 추가했습니다. `disposeView`의 기본 연결점(hook)은 아무 동작도 하지 않으므로(no-op) 기존 공급자를 계속 사용할 수 있습니다. `IncrementalRebuild`는 새 목록에 유지하지 않는 이전 최상위 뷰(root)와 하위 뷰(subtree)에 대해 이전 공급자(provider)에게 해제를 요청합니다.

중첩된 코드 블록도 해제 검사 대상이지만 일시 원문 대체 표시·화면 분리·재사용에는 해제하지 않습니다. `DiagramViewDisposalTest`는 교체·대체 원문에서 복원·공급자 교체·빈 문서 전환을 검사합니다. 앱이 Android 다이어그램 뷰를 직접 만들었다면 [Mermaid 명세](../spec/richmarkdown-mermaid.md)의 영구 폐기 규칙을 따라야 합니다.

## AR-I05: 효과가 없는 수식 서체 선택 API 제거

**상태: 2026-10-07 사용자 승인 · 0.2.0 소스 반영 · 삭제 후 JVM·빌드·배포 파일 검수 통과, 계측 APK 컴파일 확인. 실제 계측 재실행 범위는 [검수 기록](../validation.md)을 따릅니다.**

- **개선 전 관찰:** `LatexMathFont`는 KaTeX 하나만 제공했지만 테마, 모델 요청, 수식 캐시 키와 생성 함수가 `mathFont`를 받았습니다. 실제 RaTeX 호출은 이 선택 값으로 서체를 바꾸지 않고 수식 내용에 맞는 내장 글꼴을 사용했습니다.
- **사용 시 혼동:** 앱은 서체를 선택할 수 있는 설정으로 읽을 수 있었지만 실제 표시가 달라지는 선택지는 없었습니다. 동작에 영향을 주지 않는 값을 요청·캐시 비교에도 전달했습니다.
- **반영한 변경:** `LatexMathFont`, `RichMarkdownTheme.mathFont`, `Request.mathFont`, `MathRenderKey.mathFont`와 생성 함수의 해당 인자를 제거했습니다. 이전 이름을 유지하는 별칭이나 효과 없는 대체 설정은 추가하지 않았습니다.
- **유지하는 동작:** RaTeX의 KaTeX 기반 내장 글꼴 사용, `bodyFont`를 기준으로 한 수식 크기, 색, 독립 블록 수식 정렬은 유지합니다. `fontSizePx`와 내부 `mathFontSizePx`는 크기를 전달하는 값이므로 그대로 사용합니다. 수식 이미지 키는 원문·실제 px 크기·색·블록 여부입니다.
- **앱의 변경:** 0.1.0에서 넘기던 `mathFont` 인자를 테마·직접 요청·캐시 키·`Request.of` 같은 생성 함수 호출에서 삭제합니다. `LatexMathFont` import와 변수·타입 참조도 제거하고 앱·소비 라이브러리를 0.2.0 의존성으로 다시 컴파일합니다. 이 공개 API 변경을 `0.x` 마이너 버전에서 반영합니다.
- **검수 구분:** 현재 소스와 계약 문서를 대조한 결과입니다. 기존 빌드·회귀 성공을 이 삭제 뒤의 실행 성공으로 재사용하지 않습니다. 삭제 후 컴파일과 Compose·View 수식 표시 회귀는 [검수 기록](../validation.md)에서 별도로 확인합니다.

근거는 `RichMarkdownTheme.kt`, `RichMarkdownRenderModel.kt`, `MathRenderService.kt`와 두 UI의 수식
크기 전달 코드입니다. 과거 D4의 서체 설정 결정은 [DEVELOPMENT.md](../../../DEVELOPMENT.md)의 D4a가
대체하며 엔진 선택 이력은 유지합니다. 실제 선택 가능한 여러 수식 서체를 지원하게 되면 그때
엔진 지원·공개 API·캐시 구분 기준을 함께 설계해야 합니다.
