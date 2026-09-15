# HANDOFF — 작업 인계 보드

- 작성자: JunyoungJung
- 갱신: 2026-09-15 (KST)
- 용도: 세션이 바뀌어도(예: `cc2` 프로필로 위임) 같은 지점에서 이어 가기 위한 상태판. 완료 항목은 체크하고 커밋에 포함한다.

## 현재 지점

- 커밋: P1 전부(공유 모델·Compose·View·Prism·Mermaid) 반영. P2 데모 앱 진행 중(미커밋 `demo/` 가능 — `git status` 확인).
- 설계 정본: `DEVELOPMENT.md`(결정 D0~D9·D3a, §8 P0 결과). API 계약: `docs/P1-CONTRACTS.md`.
- 에뮬레이터: AVD `Pixel_6`(android-37.1, 16 KB 페이지). API 24 이미지 `system-images;android-24;google_apis;arm64-v8a` 설치 진행/완료 여부는 `sdkmanager --list_installed`로 확인.

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
- 에뮬레이터: `"$ANDROID_HOME/emulator/emulator" -avd Pixel_6 -no-window -no-audio -no-boot-anim -no-snapshot-save -gpu swiftshader_indirect &`
- 자산 갱신: `scripts/sync-ios-assets.sh` (iOS 저장소 `../RichMarkdown` 기준, SHA-256 대조).

## 작업 보드

### P1 — 렌더러 (진행 중)
- [x] `richmarkdown`: `MathRenderService`·`ParseCache`·`RichMarkdownRenderModel`·`RichMarkdownStreamingTextBuffer`·`FontResolution` (계약 §1~4) — 단위 11/11
- [x] `richmarkdown`: Compose 렌더러 `RichMarkdown()` (`compose/`) — 컴파일 통과, 시각 검증은 데모에서
- [x] `richmarkdown`: View 렌더러 `RichMarkdownView` (`view/`) — 컴파일 통과, 시각 검증은 데모에서
- [x] `richmarkdown-highlight`: `PrismHighlighter` + instrumented 테스트 13/13
- [x] `richmarkdown-mermaid`: `MermaidWebRenderer`·`MermaidDiagramView`·`MermaidDiagramRenderer` + 테스트
- [x] 통합 빌드: `:richmarkdown-core:test`, `assembleDebug`, 3모듈 `connectedDebugAndroidTest` 전부 green (2026-09-15 18:40)
- [ ] 에뮬레이터 스크린샷으로 인라인 수식 baseline·칩·표·스트리밍 확인 (Compose·View 각각)
- [ ] `DEVELOPMENT.md` §7 게이트·`CHANGELOG.md` 갱신, 커밋·push

### P2 — 데모앱·검증·발행
- [ ] `demo` 모듈(`com.android.application`, coreLibraryDesugaring ON): Compose 채팅 화면, View(RecyclerView) 채팅 화면, SSE 스트리밍 데모(iOS `Examples/RichMarkdownDemo` 대응), 코드 블록 확장 토글
- [ ] API 24 에뮬레이터에서 데모 실행 → commonmark `List.of` desugaring 확인(D3a)
- [ ] `README.md` 사용법·스크린샷, `THIRD_PARTY_NOTICES.md` 점검
- [ ] Maven Central 발행 설정(`maven-publish` + signing; 자격 증명은 `~/.gradle/gradle.properties`에만) — 실제 발행은 사용자 승인 후
- [ ] upstream PR 준비: commonmark-java `List.of` → Android 호환 치환(D3a ③)

## 위임 규칙 (cc2)

일간 토큰 소진 임박 시 이 파일을 최신화·커밋한 뒤 터미널에서:

```sh
cd /Users/jimmy/Documents/GitHub/RichMarkdown-Android && \
CLAUDE_CONFIG_DIR="$HOME/.claude-work2" claude --model claude-fable-5-1 \
  "HANDOFF.md를 읽고 미완료 항목을 위에서부터 이어서 구현해라. 빌드는 HANDOFF.md의 외장 SSD 규칙을 그대로 쓴다. 각 항목이 끝나면 HANDOFF.md 체크박스를 갱신하고 커밋한다."
```
