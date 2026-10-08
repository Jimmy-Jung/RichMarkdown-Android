# HANDOFF — 작업 인계 보드

- 작성자: JunyoungJung
- 갱신: 2026-10-08 (KST)
- 용도: 세션이 바뀌어도(예: `cc2` 프로필로 위임) 같은 지점에서 이어 가기 위한 상태판. 완료 항목은 체크하고 커밋에 포함한다.

## 현재 지점

- 커밋: P1·P2 완료, minSdk 30(D9a), upstream 이슈 #457 등록, 복합 수식 상한 수정. 0.1.0(2026-10-06)·0.2.0(`38a1db9`) 태그와 GitHub Release. 남은 항목: Maven Central 실제 발행(사용자 승인·자격 증명) 또는 JitPack 설정.
- 블록 편집기(D1a, 2026-10-08): `richmarkdown-editor` 모듈·렌더 모듈 내부 API 공개·데모 화면·문서까지 로컬 커밋(`159fff4`~`ffec7d7` + 문서 커밋). push·태그·버전 변경은 하지 않았다(VERSION_NAME 0.2.0, CHANGELOG `[Unreleased]`). 남은 항목은 아래 «블록 편집기» 보드.
- 설계 정본: `DEVELOPMENT.md`(결정 D0~D9·D1a·D3a·D4a·D9a, §8 P0 결과). API 계약: `docs/P1-CONTRACTS.md`, 편집기는 `docs/report/spec/richmarkdown-editor.md`.
- 에뮬레이터: AVD `RichMarkdown_Validation`(pixel_6, android-37.1 `google_apis_ps16k`, 16 KB 페이지). AVD는 외장 SSD `$B/AndroidAVD`에 있으므로 `ANDROID_AVD_HOME="$B/AndroidAVD"`를 지정해야 보인다. 이전에 적던 `Pixel_6` AVD는 존재하지 않는다. `API24_Pixel6`는 minSdk 30 이후 검증 대상이 아니다(참고용).

## 빌드 환경 (외장 SSD 규칙, 필수)

```sh
R=/Users/jimmy/Documents/GitHub/RichMarkdown-Android
B=/Volumes/990EVO-1TB/Developer/AgentBuilds/RichMarkdown-Android-4b1e7db9848d
cd "$R"
export GRADLE_USER_HOME=/Volumes/990EVO-1TB/Developer/Gradle TMPDIR="$B/tmp/" \
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.io.tmpdir=/Volumes/990EVO-1TB/Developer/Gradle/.tmp"
./gradlew <task> --project-cache-dir "$B/gradle-project-cache" -Prichmarkdown.buildRoot="$B/build" --console=plain
```

- 동시 실행 금지. 여러 작업자가 있으면 `mkdir "$B/gradle.lock"` 성공한 쪽만 실행하고 끝나면 `rmdir`.
- 에뮬레이터: `ANDROID_AVD_HOME="$B/AndroidAVD" "$ANDROID_HOME/emulator/emulator" -avd RichMarkdown_Validation -no-window -no-audio -no-boot-anim -no-snapshot-save -gpu swiftshader_indirect &`
- 자산 갱신: `scripts/sync-ios-assets.sh` (iOS 저장소 `../RichMarkdown` 기준, SHA-256 대조).

## 작업 보드

### P1 — 렌더러 (진행 중)
- [x] `richmarkdown`: `MathRenderService`·`ParseCache`·`RichMarkdownRenderModel`·`RichMarkdownStreamingTextBuffer`·`FontResolution` (계약 §1~4) — 단위 11/11
- [x] `richmarkdown`: Compose 렌더러 `RichMarkdown()` (`compose/`) — 컴파일 통과, 시각 검증은 데모에서
- [x] `richmarkdown`: View 렌더러 `RichMarkdownView` (`view/`) — 컴파일 통과, 시각 검증은 데모에서
- [x] `richmarkdown-highlight`: `PrismHighlighter` + instrumented 테스트 13/13
- [x] `richmarkdown-mermaid`: `MermaidWebRenderer`·`MermaidDiagramView`·`MermaidDiagramRenderer` + 테스트
- [x] 통합 빌드: `:richmarkdown-core:test`, `assembleDebug`, 3모듈 `connectedDebugAndroidTest` 전부 green (2026-09-15 18:40)
- [x] 에뮬레이터 스크린샷으로 인라인 수식 baseline·칩·표·스트리밍 확인 (Compose·View 각각) — `docs/screenshots/`, View 표 셀 높이 결함 수정
- [ ] `DEVELOPMENT.md` §7 게이트·`CHANGELOG.md` 갱신, 커밋·push

### P2 — 데모앱·검증·발행
- [x] `demo` 모듈(`com.android.application`, coreLibraryDesugaring ON): Compose 채팅 화면, View(RecyclerView) 채팅 화면, SSE 스트리밍 데모(iOS `Examples/RichMarkdownDemo` 대응), 코드 블록 확장 토글 — Pixel_6 설치·동작 확인
- [x] API 24 에뮬레이터에서 데모 실행 → commonmark `List.of` desugaring 확인(D3a) — `API24_Pixel6`, 크래시 0
- [x] `README.md` 스크린샷·SSE GIF 첨부 (`scripts/capture-demo-screens.sh`)
- [x] `README.md` 사용법(실제 API) 보강, `THIRD_PARTY_NOTICES.md` 점검(okhttp 추가)
- [x] Maven Central 발행 설정(vanniktech maven.publish 0.37.0, POM_* in gradle.properties, `publishToMavenLocal` 검증)
- [ ] Maven Central 실제 발행 — 사용자 승인 + `~/.gradle/gradle.properties`에 mavenCentralUsername/Password·signingInMemoryKey/Password 설정 후 `./gradlew publishToMavenCentral -PRELEASE_SIGNING_ENABLED=true`
- [x] upstream 이슈 등록: https://github.com/commonmark/commonmark-java/issues/457 (2026-09-16). PR은 유지보수자가 해법 1(치환)을 원하면 진행

### 블록 편집기 (D1a, [Unreleased])
- [x] `richmarkdown-editor` 모듈 + iOS 모델·코덱 이식(`159fff4`) — JVM 62/62, iOS Swift 소스 차등 비교(8,000건 차이 0, 이모지·결합 문자 1,500건 차이 113건 = 서로게이트 거절 81 + 코덱 UTF-16 순회 32)
- [x] 렌더 모듈 span·글꼴 해석·칩 그리기 `@InternalRichMarkdownApi` 공개(`22985e3`) — `:richmarkdown` 단위 11/11·계측 21/21
- [x] `BlockDocumentEditText`·`MarkdownStyler`·클립보드 payload·Compose 래퍼(`18a1fce`), 조합 중 도구 모음 확정(`eed41a3`) — 계측 30/30, `lintDebug` 오류 0
- [x] 데모 `BlockEditorActivity` + 키보드 위 도구 모음(`ffec7d7`) — `:demo` 단위 7/7, `assembleDebug`·`lintDebug` 통과, 에뮬레이터 수동 확인, `verify-demo-ui.py` 통과
- [x] `docs/report`(편집기 architecture·spec·ADR 0001/0002·improvements, validation 2026-10-08 절)·README·DEVELOPMENT D1a 갱신
- [x] `ONLY=block-editor scripts/capture-demo-screens.sh emulator-5554`로 `09-block-editor.png`·`10-block-editor.gif` 생성 — 에뮬레이터에서만 촬영, 스크립트가 접근성 트리·내보낸 Markdown으로 결과 확인, 프레임은 사람이 보지 않음
- [ ] 수식 글자 화면 표시와 블록 편집 정지컷·GIF를 사람이 직접 확인(캡처만 했고 보지 않음)
- [ ] 실기기 Gboard 조합 중 도구 모음·클립보드 왕복 확인
- [ ] 미해결 개선: 오른쪽 여백(AE-I05), 마지막 줄 caret 높이(AE-I06), 전체 재스타일링 성능 측정(AE-I07), Compose 재동기화 지연(AE-I08), 블록 수식 벡터화(AE-I09), 데모 회전 시 문서 유지(AE-I11)
- [ ] 다음 릴리스: VERSION_NAME 올리기, 다섯 모듈 `publishToMavenLocal`·배포 파일 대조·Release ZIP 재생성(사용자 승인 후)

## 위임 규칙 (cc2)

일간 토큰 소진 임박 시 이 파일을 최신화·커밋한 뒤 터미널에서:

```sh
cd /Users/jimmy/Documents/GitHub/RichMarkdown-Android && \
CLAUDE_CONFIG_DIR="$HOME/.claude-work2" claude --model claude-fable-5-1 \
  "HANDOFF.md를 읽고 미완료 항목을 위에서부터 이어서 구현해라. 빌드는 HANDOFF.md의 외장 SSD 규칙을 그대로 쓴다. 각 항목이 끝나면 HANDOFF.md 체크박스를 갱신하고 커밋한다."
```
