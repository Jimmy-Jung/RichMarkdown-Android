# richmarkdown-mermaid 명세

기준일: 2026-10-06 · 현재 소스 확인 · 실제 Android 실행은 별도

다이어그램이 필요한 앱은 `RichMarkdownCodeBlockOptions(diagram = MermaidDiagramRenderer.shared)`로 이 모듈을 연결합니다. 연결하지 않은 코드 블록은 기존 방식대로 원문이나 문법 강조를 적용한 코드로 표시합니다. 그림은 앱의 로컬 assets에 포함한 Mermaid 11.17.2로 만들고, 확대해도 선명한 벡터 그림인 SVG를 앱 내 웹 화면인 `WebView`에 표시합니다.

[모듈 선언](../../../richmarkdown-mermaid/build.gradle.kts)과 [버전 선언](../../../gradle/libs.versions.toml)의 Android minSdk는 30, compileSdk는 37입니다. minSdk는 최소 지원 API 수준, compileSdk는 컴파일 기준이며 모든 지원 버전의 실행 성공을 뜻하지는 않습니다. Mermaid 본체 버전은 유지하고 DOMPurify 3.4.16·JavaScript KaTeX 0.18.2를 고정해 보안 개선 번들을 다시 생성한 상태입니다. 이전 실행 검수와 아직 완료되지 않은 배포 단계는 [validation](../validation.md)에서 구분합니다.

## API

아래에서 앱 측(native)은 Kotlin 코드, 웹 측은 JavaScript 코드를 뜻합니다. 코루틴은 결과를 기다리는 동안 스레드를 계속 붙잡지 않는 비동기 작업이고, `suspend` 함수는 이런 대기를 할 수 있는 함수입니다. 메인 dispatcher는 화면을 다루는 메인 스레드에서 코루틴을 실행하는 방식입니다. overload는 같은 이름에 인자 구성이 다른 함수이며, adapter는 상위 Markdown과 Mermaid를 연결하는 구현입니다.

| API | 계약 |
| --- | --- |
| `MermaidDiagramRenderer.shared` | 소문자 `mermaid` 언어만 담당합니다. 앱에서 같은 다이어그램 공급자 인스턴스를 재사용할 수 있습니다. |
| `createView(context, source, theme, onSizeChange)` | 기존 호환 API입니다. 시스템 다크 모드에 따라 Android View를 만듭니다. 메인 스레드에서 사용합니다. |
| `Content(source, theme)` | 기존 호환 API입니다. 시스템 다크 모드 값을 `isDark` 인자가 있는 함수로 전달합니다. |
| `createView(context, source, theme, isDark, onSizeChange)` / `Content(source, theme, isDark)` | 상위 Markdown에서 정한 다크 모드를 전달합니다. 기존 사용자 정의 구현은 기본 구현을 통해 이전 overload로 연결됩니다. |
| `MermaidDiagramView.dispose()` | 뷰를 영구 제거할 때 메인 스레드에서 호출합니다. 뷰에 속한 코루틴 범위(scope), 응답 대기 목록(`pending`), `WebView`를 정리합니다. 중복 호출할 수 있습니다. |
| `MermaidDiagramView` | 원문 `source`, 테마 `theme`, 다크 모드 `isDark`, 크기 변경 알림 `onSizeChange`를 설정합니다. `contentHeightPx`는 외부에서 읽기만 할 수 있는 높이입니다. |
| `MermaidWebRenderer.render(source, dark, widthPx, fontSizePx)` | 메인 dispatcher에서 실행하는 `suspend` 렌더 함수입니다. 검사한 크기 `Size`를 반환하거나 오류를 전달합니다. |
| `MermaidWebRenderer.destroy()` | `WebView`를 폐기합니다. 이후 이 인스턴스는 재사용할 수 없습니다. |

## 요구와 한계

이 표의 px는 화면 픽셀, dp는 화면 밀도를 고려한 Android 배치 단위입니다. density는 두 단위의 변환 배율입니다. CSS px는 웹 페이지의 배치 단위이며 여기서는 dp로 전달합니다. 자연 크기는 SVG가 원래 차지하는 크기이고, 유한값은 무한대나 NaN(숫자로 표현할 수 없는 값)이 아닌 수입니다.

Mermaid 원문에는 렌더 설정을 넣는 directive와 frontmatter 문법이 있습니다. `secure`는 원문으로 덮어쓰지 못하게 잠그는 설정 키 목록입니다. 여기서는 HTML 라벨 사용 여부인 `htmlLabels`와 위험한 태그·속성을 제거하는 DOMPurify의 설정인 `dompurifyConfig`도 잠급니다. `maxTextSize`는 원문 길이, `maxEdges`는 도형 간 연결 수를 제한합니다.

CSP(Content Security Policy)는 웹 페이지에서 허용할 스크립트와 자원 출처를 제한하는 규칙입니다. bootstrap은 페이지를 처음 준비하는 초기 실행 코드입니다. 이 코드의 SHA-256 해시가 정확히 일치하는 경우만 허용하고, `onerror`처럼 HTML 속성에 적는 이벤트 코드(inline event)는 차단합니다.

| ID | 요구와 현재 구현 | 근거·테스트 선언 |
| --- | --- | --- |
| AM-01 | `mermaid` 코드 블록의 본문만 다이어그램으로 바꾸며 상위 헤더와 원문 복사를 유지합니다. | `MermaidDiagramRenderer.kt`, 상위 블록 렌더러; 옵션·언어 테스트 |
| AM-02 | 공백만 있는 `source`와 UTF-8 20,000바이트를 초과하는 원문을 거절합니다. 웹 페이지를 불러오기 전에 검사합니다. | `MermaidWebRenderer.render`; 과대 원문 테스트 |
| AM-03 | HTML에서도 원문의 UTF-16 문자열 길이를 20,000으로 제한합니다. Mermaid의 `maxTextSize` 20,000, `maxEdges` 200, 최상위(root)·flowchart의 `htmlLabels` false를 설정합니다. `htmlLabels`·`dompurifyConfig`는 원문의 중첩 directive·frontmatter에서도 잠급니다. 앱 측 UTF-8 바이트 제한과는 단위가 다릅니다. | `index.html` |
| AM-04 | 앱의 assets를 불러오고 `file`·`content` 접근과 페이지 이동을 차단합니다. CSP는 외부 통신과 인라인 이벤트를 막고, 스크립트는 같은 출처인 `self`와 정확한 bootstrap SHA-256만 허용합니다. strict, 최상위·flowchart의 `htmlLabels` false, `secure` 목록의 `htmlLabels`·`dompurifyConfig`로 원문이 안전 설정을 완화하지 못하게 합니다. 사용자 원문은 `JSONObject.quote`로 처리한 함수의 문자열 인자입니다. | settings·Client·HTML; assets·CSP 테스트 |
| AM-05 | 뷰가 창에 붙고 폭이 1px보다 클 때만 시작합니다. 초기 높이는 64dp이며 완료 결과나 실패 대체 표시(fallback)의 높이가 바뀌면 콜백을 호출합니다. | View; 초기 높이·크기 콜백 테스트 |
| AM-06 | `source`·`dark`·폭·글자 크기(px)가 같은 요청은 생략합니다. 새 요청은 이전 그림과 접근성 내용을 즉시 가리고 로딩 상태로 전환합니다. 새 요청과 창에서 떨어지는 단계(detach)는 이전 작업인 `Job`을 취소합니다. 진행 중 detach 뒤 다시 붙으면(reattach) 렌더링을 다시 시작합니다. | `renderedKey`, 뷰 생성·부착·제거 처리 |
| AM-07 | 앱 측 취소·시간 초과는 `pending` ID를 제거해 늦은 브리지 응답을 무시합니다. 브리지는 JavaScript가 Kotlin에 결과를 전달하는 연결 부분입니다. 웹 측도 현재 요청 번호(generation)를 확인해 이전 결과가 DOM, 즉 웹 화면 구조에 반영되지 않게 합니다. `initialize`·`render`는 대기열에서 하나씩 실행하고, 숨긴 임시 영역(staging)의 마지막 화면 갱신(frame) 대기와 번호 확인 뒤 최신 SVG를 표시합니다. | `evaluateRender`, Bridge, `index.html` |
| AM-08 | 로드와 렌더 응답 대기는 각각 15초로 제한합니다. 최초 요청 전체가 15초 안에 끝난다는 의미는 아닙니다. 로드 실패, 진행 중 렌더 취소, 렌더 시간 초과 다음 호출은 페이지를 다시 불러옵니다. 로드 URL의 구분 번호로 이전 페이지의 완료·실패 알림도 제외합니다. | `loadIfNeeded`, `evaluateRender` |
| AM-09 | 가용 폭을 density로 나누고 소수점 아래를 버린(floor) 뒤 120~2,000 CSS px로 제한합니다. 자연 폭보다 가용 폭이 좁으면 줄이고 작은 그림을 확대하지 않습니다. | 렌더러와 HTML |
| AM-10 | SVG 본문 높이는 최대 4,000 CSS px, 여백(padding) 포함 응답은 최대 4,032dp입니다. 앱 측도 유한한 양수·4,032 이하 높이인지 확인하고 density를 곱해 px로 바꿉니다. | HTML·렌더 높이 검사 |
| AM-11 | `fontSizePx`를 density로 나눠 JavaScript에 전달합니다. `textZoom` 100으로 시스템 글자 크기 배율인 `fontScale`이 두 번 적용되지 않게 합니다. 두 손가락으로 확대할 때는 HTML 화면 배율(viewport)의 최대 5배와 WebView 확대 설정을 사용합니다. | settings·HTML |
| AM-12 | WebView 실행 구성 요소(provider)의 생성 실패와 렌더 중 실패는 오류 한 줄과 원문을 표시합니다. 과대 원문은 상한 이내의 앞부분(prefix)과 생략 표식만 보여줍니다. 작업이 없는 상태(idle)의 종료도 알림을 받아 복구하며 기존 WebView를 폐기하고 연속 종료에 한 번만 재시도합니다. 성공하거나 새 `source`·`theme`가 들어오면 재시도 횟수를 다시 사용할 수 있습니다. | View의 오류 처리와 `retriedAfterTermination`; 잘못된 원문 테스트 |
| AM-13 | WebView와 HTML 컨테이너는 'Mermaid 다이어그램' 접근성 라벨을 제공합니다. 도형별 의미나 탐색 접근성의 완전성을 보장하지 않습니다. | `addWebView`, HTML |
| AM-14 | `MermaidDiagramView.isDark`를 직접 지정하면 시스템 다크 모드보다 이 설정을 우선합니다. 지정 후에는 시스템 설정(configuration)의 다크 모드 변경을 따르지 않습니다. adapter의 새 overload는 상위 Markdown의 명시적 다크 모드를 받습니다. 기존 overload는 시스템 다크 모드를 유지합니다. 원문의 directive·class·style로 지정한 자체 색은 별도입니다. | Renderer와 View의 다크 모드 처리 |

원문 `source` 전체와 상위 코드 블록의 복사 기능은 유지합니다. 실패 텍스트 뷰는 UTF-8 20,000바이트 이내의 앞부분과 생략 표식만 측정합니다. Unicode의 각 문자 값을 구분하는 code point 경계를 보존하지만, 여러 문자 값이 하나로 보이는 복합 이모지 전체(grapheme) 경계까지 보장하지 않습니다. 표시 상한은 원문 전체의 메모리 보관 상한이 아닙니다.

일반 detach에서는 재부착을 허용하며 영구 제거에는 `dispose()`가 필요합니다. Compose는 `onRelease`에서 자동으로 호출합니다. 기본 `RichMarkdownView`는 영구 제거된 블록과 그 아래 뷰들(subtree)에 이전 공급자의 `disposeView`를 호출합니다. Mermaid adapter는 자신이 생성한 뷰만 정리합니다. 직접 뷰를 생성한 앱은 영구 제거할 때 `dispose()`를 호출해야 합니다.

이전 그림을 가릴 때는 기존 SVG를 즉시 삭제하지 않습니다. WebView의 투명도와 접근성 표시를 바꿔 감추고, 최신 결과가 확정될 때 웹 화면의 SVG를 교체합니다. 작업을 기다리는 JavaScript의 `Promise` 대기열이 끝나지 않으면 취소·시간 초과 다음 요청에서 페이지를 다시 불러와 버립니다. `Promise`는 나중에 완료될 결과이고, 이 취소 처리는 실행 중인 JavaScript의 강제 중단이 아닙니다.

## 오류 처리

`MermaidError`는 다음 오류를 구분합니다.

| 오류 | 의미 |
| --- | --- |
| `EmptySource` | 원문이 공백뿐이거나 비어 있습니다. |
| `SourceTooLarge(utf8Bytes, limit)` | 원문의 UTF-8 바이트 수가 제한을 넘습니다. |
| `ResourceMissing` | 페이지 리소스를 불러오지 못했습니다. |
| `LoadTimeout` | 초기 페이지 로드 대기 시간이 초과됐습니다. |
| `WebContentTerminated` | 웹 내용을 실행하는 프로세스가 종료됐습니다. |
| `InvalidSize` | 결과 크기가 유효하지 않습니다. |
| `RenderFailed(reason)` | JavaScript 렌더 오류나 렌더 응답 시간 초과입니다. |

Android의 렌더 응답 시간 초과는 별도 `RenderTimeout` 오류가 아니라 `RenderFailed(reason)`으로 전달합니다. 뷰는 일반 실패 사유와 원문을 보여줍니다. 코루틴의 `CancellationException`은 실패 안내로 바꾸지 않고 다시 던집니다. 종료 알림은 WebView 교체·재시도 경로로 처리합니다.

오류 사유는 정해진 형식이 없는 문자열입니다. 민감한 원문을 다루는 앱이 이를 외부 로그로 내보내면 별도 관리가 필요합니다.

## 근거와 실행 확인

[소스 디렉터리](../../../richmarkdown-mermaid/src/main/)에 API·HTML 구현이 있습니다. [Android 테스트](../../../richmarkdown-mermaid/src/androidTest/)의 `MermaidDiagramViewTest.kt`는 flowchart, 실패 처리, 크기, 다크 모드 변경을 확인하도록 작성됐습니다. 같은 뷰의 두 번째 렌더 시간은 로그만 남기며, 성능 합격 기준을 자동 판정(assertion)하지 않습니다.

이번 문서 보완에서는 테스트나 WebView 표시를 새로 실행하지 않았습니다. 기존 실행 검수는 [validation](../validation.md)에 연결하고, 적용 내역과 남은 확인은 [개선안](../improvements/richmarkdown-mermaid.md)에 정리합니다. 테스트가 제어한 종료 알림은 실제 OS의 프로세스 강제 종료 재현과 다릅니다. 전체 WebView 제공 환경, 도형별 접근성, 장시간 메모리·성능, 모든 공격 사례의 보안 검수를 마쳤다는 뜻은 아닙니다.
