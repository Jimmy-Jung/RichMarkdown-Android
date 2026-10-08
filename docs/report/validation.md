# 0.3.0 릴리스 검수 기록

기준일: 2026-10-08

현재 구현·회귀 테스트·릴리스용 배포 파일을 확인한 기록입니다. 첫 절은 0.3.0 릴리스 게이트이고, 2026-10-08 블록 편집기 절은 0.2.0 릴리스 뒤 편집기를 추가했을 때의 검수입니다. 2026-10-06 실행 결과는 그날의 검사 기록이며, 문서 보완을 위해 앱 테스트를 다시 실행한 결과로 읽지 않습니다. 테스트 코드의 존재와 실행 성공을 구분하며, 적용·보류 항목은 [개선 기록](improvements/README.md)을 따릅니다. 검사 용어의 뜻은 [용어 안내](glossary.md)를 참고합니다.

## 2026-10-08 0.3.0 릴리스 검증

`VERSION_NAME`을 0.3.0으로 올린 소스(블록 편집기 커밋 `159fff4`~`1253fb4` 포함)로 릴리스 게이트를 실행했습니다. 계측 테스트는 `ANDROID_SERIAL`로 Pixel 6 프로필 에뮬레이터(Android 37.1 이미지, 16 KB 페이지) 한 대만 지정해 모듈별로 차례대로 실행했습니다. 단위 테스트는 이전 결과를 재사용하지 않도록 다시 실행했습니다.

| 검사 | 결과 | 근거 범위 |
| --- | --- | --- |
| Core JVM | 105개 통과, 실패·제외 0 | `:richmarkdown-core:test` |
| 표시 모듈 단위·계측 | 11개·21개 통과, 실패·제외 0 | `:richmarkdown:testDebugUnitTest`·`connectedDebugAndroidTest` |
| Highlight 계측 | 18개 통과, 실패·제외 0 | `:richmarkdown-highlight:connectedDebugAndroidTest` |
| Mermaid 계측 | 19개 통과, 실패·제외 0 | `:richmarkdown-mermaid:connectedDebugAndroidTest` |
| 편집기 JVM·계측 | 62개·31개 통과, 실패·제외 0 | 계측은 스타일러 13개, 편집 뷰 15개, Compose 래퍼 3개. 아래 절의 30개에 줄 높이 회귀([AE-I14](improvements/richmarkdown-editor.md#ae-i14-서식-적용-시-한글-줄-높이-변화)) 1개 추가 |
| 예제 앱 | 단위 7개 통과, `assembleDebug` 성공 | `:demo:testDebugUnitTest`·`assembleDebug` |
| 정적 검사 | 편집기 `lintDebug` 오류 0·경고 3, 예제 앱 `lintDebug` 오류 0·경고 4 | 편집기 경고는 `UseTomlInstead` 3건. 예제 앱 경고는 `DataExtractionRules`·`UnusedResources`·`AlwaysShowAction`·`UseTomlInstead` 각 1건 |
| Release 빌드 | 다섯 모듈 성공 | Core `assemble`, 나머지 네 모듈 `assembleRelease` |
| 릴리스용 배포 파일 | 다섯 모듈 통과 | 아래 대조 |

다섯 모듈을 저장소 밖의 빈 로컬 Maven 저장소에 `publishToMavenLocal`로 생성했습니다. 모듈마다 `io.github.jimmy-jung:<모듈>:0.3.0` 좌표의 Release AAR(Core는 JAR)·POM·Gradle metadata·sources JAR·Javadoc JAR가 있고, 저장소 내부 의존성은 모두 0.3.0을 가리킵니다. AAR 네 개의 minSdk는 30입니다. 기존 네 모듈의 POM 의존성 목록·이름·라이선스·SCM 값은 0.2.0 Release ZIP과 같습니다. 편집기 POM은 `richmarkdown` 0.3.0을 compile 범위(`api`)로 선언하고 Compose BOM 2026.09.00을 가져옵니다.

이 저장소로 만든 `richmarkdown-android-0.3.0-maven.zip`은 항목 30개, 3,975,555바이트입니다. 0.2.0 ZIP과 같은 `io/github/jimmy-jung/<모듈>/` 배치에 편집기 디렉터리가 추가되었습니다. SHA-256은 `b63bd3081916d3452538380f16cf122ed94309b9c45fc4390c07ff9423bd535d`이며 함께 만든 `.sha256` 파일과 대조했습니다.

다음은 이번 검증에서 확인하지 않았습니다.

- ZIP을 별도 소비 프로젝트의 저장소로 추가해 의존성을 해결하는 확인은 하지 않았습니다.
- 실기기 Gboard 조합 중 도구 모음·클립보드 왕복과 화면을 사람이 직접 보는 확인([AE-I12](improvements/richmarkdown-editor.md#ae-i12-실기기-ime클립보드화면-확인))은 여전히 하지 않았습니다.
- Maven Central 발행과 서명은 이번 범위가 아닙니다.

## 2026-10-08 블록 편집기 추가 검증

당시 미배포였던 `richmarkdown-editor` 모듈(커밋 `159fff4`~`ffec7d7`)과 이 모듈을 위한 렌더 모듈의 내부 API 공개를 검사했습니다. 계측 테스트는 Pixel 6 프로필 에뮬레이터의 Android 37.1 이미지(16 KB 페이지)에서 실행했습니다. `VERSION_NAME`은 0.2.0 그대로이며 배포 파일은 만들지 않았습니다.

| 검사 | 결과 | 근거 범위 |
| --- | --- | --- |
| 편집기 JVM | 62개 통과 | iOS에서 옮긴 모델·코덱 47개, Android 포트 계약 7개, payload 4개, 편집 환원 4개 |
| 편집기 계측 | 30개 통과 | 스타일러 13개, 편집 뷰·IME 조합·재동기화·도구 모음·다크 모드 14개, Compose 래퍼·수식 이미지·클립보드 왕복 3개 |
| 표시 모듈 단위·계측 | 11개·21개 통과 | 내부 API 공개(`22985e3`) 뒤 기존 표시 동작 |
| Core JVM | 105개 통과 | 기존 파싱·입력·작업 실행기 |
| 예제 앱 | 단위 7개 통과, `assembleDebug` 성공 | 블록 편집 화면 첫 문서 파싱과 명령 연결 4개 포함 |
| 정적 검사 | 편집기 `lintDebug` 오류 0·경고 3, 예제 앱 `lintDebug` 오류 0 | 편집기 경고는 기존 모듈과 같은 `UseTomlInstead` 형식 |
| 에뮬레이터 수동 확인 | 통과 | 실행, `adb input text` 입력, 도구 모음 굵게 뒤 내보낸 Markdown `# 회의 노트 **Phase3**`, 완료로 키보드 닫기, 실행 취소, 종류 메뉴로 인용 변환, FATAL 로그 없음 |
| 예제 앱 UI 검사 | `verify-demo-ui.py` 통과 | 블록 편집 화면 항목 포함 |

iOS `Sources/RichMarkdownBlockEditor` Swift 소스를 컴파일해 모델·코덱 결과를 무작위 입력으로 비교했습니다. 이모지·결합 문자가 없는 입력 4,000건과 긴 입력 4,000건은 차이가 0건이었습니다. 이모지·결합 문자 입력 1,500건의 차이 113건은 서로게이트 중간 범위 거절 81건([AE-I04](improvements/richmarkdown-editor.md#ae-i04-서로게이트-쌍-중간-범위-거절))과 코덱의 UTF-16 단위 순회 32건([AE-I10](improvements/richmarkdown-editor.md#ae-i10-결합-문자가-붙은-서식-기호의-코덱-순회-단위))으로 설명됩니다. 비교 스크립트는 저장소에 포함하지 않았으므로 이 기록만으로 재현할 수는 없습니다.

다음은 이번 검수에서 확인하지 않았습니다.

- 에뮬레이터 화면의 수식 글자 표시는 캡처했지만 사람이 직접 보지 않았습니다. 첫 확인에서 수식이 비어 있던 원인(테스트의 글꼴 로딩 순서)은 고쳤으나 고친 뒤의 화면은 보지 않았습니다.
- 실제 Gboard 소프트 키보드의 조합 중 도구 모음 누르기는 계측 테스트의 IME 연결 호출로만 확인했습니다.
- 실기기 클립보드, 긴 문서의 편집 성능, 예제 앱 화면 회전 뒤 문서 유지(현재 첫 문서로 돌아감)는 확인하지 않았습니다.
- 블록 편집 정지컷·GIF(`09-block-editor.png`·`10-block-editor.gif`)는 이후 `ONLY=block-editor scripts/capture-demo-screens.sh emulator-5554`로 에뮬레이터에서만 촬영했습니다. 스크립트가 녹화 중 접근성 트리와 녹화 뒤 내보낸 Markdown으로 결과를 확인하지만, 프레임은 사람이 직접 보지 않았습니다.
- 편집기 모듈의 Maven 배포 파일 생성(`publishToMavenLocal` 포함)과 대조는 실행하지 않았습니다.

## 2026-10-07 API 정리 후 재검증

효과가 없는 `LatexMathFont`·`mathFont` 공개 API를 제거한 현재 소스로 Core JVM 105개와 표시 모듈 단위 11개가 통과했고, 실패·제외는 0개입니다. 표시·Highlight·Mermaid 계측 테스트 APK 세 개의 컴파일, 예제 앱 `assembleDebug`·`lintDebug`, 네 모듈 `publishToMavenLocal`도 통과했습니다.

네 모듈의 0.2.0 좌표·Release AAR/JAR·POM·Gradle metadata와 sources JAR를 대조하고 Maven 저장소 ZIP을 다시 생성했습니다. sources JAR에 제거한 API 파일이 포함되지 않는지 검사했습니다. JavaScript 의존성 감사는 취약점 0건이며 양 플랫폼 Mermaid JS 회귀와 현재 변경의 상대 링크·공백 검사도 통과했습니다.

이번 재검증 시 연결된 Android 기기는 없었습니다. 아래 2026-10-06 계측 58개 통과를 API 제거 후의 새 실행 결과로 확대하지 않습니다. API 제거 후 계측은 APK 컴파일까지 확인했으며, 실제 기기·에뮬레이터 실행은 이번에 다시 수행하지 않았습니다.

## 2026-10-06 실행 결과

| 검사 | 결과 | 근거 범위 |
| --- | --- | --- |
| Core JVM | 105개 통과, 실패·제외 0 | 파싱·입력·범위·작업 실행기의 정상/취소/예외 |
| 표시 모듈 단위 | 11개 통과, 실패·제외 0 | 같은 요청 판별·캐시·대기 버퍼 |
| 표시 모듈 계측 | 21개 통과, 실패·제외 0 | 실제 Compose·View·모델·RaTeX·수명·공급자 자원 해제 |
| Highlight 계측 | 18개 통과, 실패·제외 0 | 실제 QuickJS·색칠 구간 계산과 자원 해제의 동시 호출·재초기화 |
| Mermaid 계측 | 19개 통과, 실패·제외 0 | 실제 WebView·최신 DOM 반영·복구·보안 설정·콘텐츠 보안 정책(CSP)·실패 표시 |
| 예제 앱 | assembleDebug·lintDebug 통과 | 실제 빌드와 정적 검사, 전체 수동 화면 QA와 구분 |
| 릴리스용 배포 파일 | 네 모듈 통과 | 0.2.0 AAR/JAR·POM·Gradle metadata를 로컬 Maven 저장소로 생성·대조 |
| Node·CSP 회귀 | 양 플랫폼 통과 | 최신 DOM·최종 화면 갱신 단계·취소·복구·초기 실행 코드 해시·초기화 설정 잠금 |
| 포함 라이브러리 보안 검사 | 취약점 0 | Mermaid 버전 유지, DOMPurify 3.4.16·JavaScript KaTeX 0.18.2로 보안 수정 버전 고정 |

초기 계측 실패는 실행 환경과 테스트용 입력·준비 코드의 문제를 구분해 처리했습니다. 모듈 계측을 `--no-parallel`로 순차 실행했습니다. JUnit 반환형과 Compose의 초기 뷰 계층을 기다리는 코드도 수정했습니다. 제품 검사 조건·대기 제한을 약화하거나 테스트를 제외하지 않았습니다.

## 문서·시각자료 검사

2026-10-07에 모듈별 문서를 `architecture/`, `spec/`, `adr/`, `improvements/` 공통 폴더로 통합했습니다. 모듈별 ADR 목차는 공통 `adr/README.md`로 합치고 결정문 파일명에 모듈 이름을 붙였습니다. 이동 후 상대 링크·앵커와 이전 경로 제거를 검사했으며, 구현 설명과 기존 도식 원문은 보존했습니다. 위 실행 결과는 기존 릴리스 검수 기록입니다.

기존 문서 검사에서는 Markdown 보고서와 README의 코드 블록·표·상대 링크·이미지 대체 텍스트 검사를 통과했습니다. CHANGELOG는 기존 반복 소제목 관례를 유지하고 신규 0.2.0 항목을 별도로 검사했습니다. Mermaid 8개 도식의 밝은·어두운 배경 PNG 16개를 로컬 검사했으며 GitHub·VSCode 공통 문법 범위를 벗어났다는 경고와 실패는 0입니다.

기존 Excalidraw 검사에서는 원본·SVG 네 쌍의 대응 관계·화살표 머리·그림 잘림 검사를 통과하고 PNG 네 장을 직접 확인했습니다. Mermaid 개념도는 최신 ID를 확인한 뒤 화면에 반영하는 조건으로 갱신했습니다. 범용 스타일 검사기는 개념도 스킬과 팔레트·선 규칙이 달라 통과하지 않았습니다. 전체 스타일 검사 통과로 보고하지 않습니다.

2026-10-07 문서 보완에서는 기존 보고서 23개를 쉬운 한국어로 다듬고 Android용 용어 안내를 추가했습니다. 일반 Markdown 24개의 문서 검사, 상대 링크 211개와 제목 앵커 16개의 대상 검사, 개인정보 검사를 통과했습니다. Mermaid 8개의 밝은·어두운 배경 PNG 16개를 다시 만들고 직접 확인했으며 경고·실패는 0입니다. Excalidraw 네 원본의 그림 데이터·SVG와 비 Mermaid 코드 예제는 그대로 유지하고, 그림 설명만 보완했습니다.

소스 대조와 독립 문서 검수에서 모델 클래스와 객체 소유의 차이, 현재 추가된 코드 색칠·해제 경합 검사, Mermaid 오류 인자 이름과 문자열 비교 방식을 명확히 했습니다. 요구 ID와 기존 실행 결과·상한·구현 기준일은 보존하고, ADR 목차의 표시 모듈 요구 범위를 현행 AR-22까지 맞췄습니다. 오늘의 문서 검사와 위의 앱 실행 검수는 별도 기록입니다.

2026-10-08 블록 편집기 문서 갱신에서는 편집기 아키텍처·명세·ADR 두 개·개선 기록을 추가하고 색인·용어 안내·README·DEVELOPMENT를 고쳤습니다. 일반 Markdown 34개의 문서 검사를 통과했습니다. CHANGELOG는 Keep a Changelog의 반복 소제목(`Added`·`Changed`·`Fixed`) 5건이 같은 앵커로 보고되며, 기존 관례대로 유지했습니다. 저장소 Markdown의 상대 링크 307개와 제목 앵커 대상을 별도 스크립트로 검사해 문제 0건이었습니다. 새로 추가하거나 고친 Mermaid 4개(편집기 3개, 전체 아키텍처 1개)는 mermaid-cli 11.17.0으로 밝은·어두운 테마 SVG 8개를 만들어 종료 코드 0과 구문 오류 문구 없음만 확인했습니다. 이 그림들은 사람이 직접 보지 않았습니다.

보고서에는 개인 이름·로컬 절대 경로·장치/볼륨 식별자·이메일을 포함하지 않았습니다. 자동 생성된 외부 라이브러리 번들의 문자열에 필요한 공백은 보존하고, 해당 파일의 Git 공백 검사 속성만 예외로 둡니다. 나머지 변경과 새 문서는 공백·변경 범위를 검사했습니다.

## 확인하지 않은 범위

실제 GitHub·VSCode 미리보기의 표시, 투명 Excalidraw SVG의 다크 배경, 모든 사용자 환경·성능 상한·가능한 공격 입력 전체는 확인하지 않았습니다. 블록 편집기의 미확인 범위는 [2026-10-08 절](#2026-10-08-블록-편집기-추가-검증)과 [AE-I12](improvements/richmarkdown-editor.md#ae-i12-실기기-ime클립보드화면-확인)에 있습니다. 단위·계측 성공을 그 범위의 승인으로 확대하지 않습니다.

로컬 Maven 저장소와 ZIP의 준비는 원격 발행과 별도 단계입니다. 0.3.0 ZIP에는 블록 편집기를 포함한 다섯 모듈이 있습니다. 배포 파일과 원격 게시 상태는 [0.3.0 GitHub Release](https://github.com/Jimmy-Jung/RichMarkdown-Android/releases/tag/0.3.0)에서 확인합니다. 공유 자산의 원본 iOS 커밋과 해시는 각 모듈의 `SYNC-MANIFEST.txt`에 기록합니다. Maven Central 최초 발행은 별도 배포 경로입니다.
