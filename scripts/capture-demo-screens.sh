#!/usr/bin/env bash
# Author: JunyoungJung
# Date: 2026-09-15
#
# 데모 앱 스크린샷과 SSE 스트리밍·블록 편집 GIF를 다시 만든다 (iOS `scripts/capture-sse-gifs.sh` 대응).
# 전제: 대상 기기에 최신 `demo` 디버그 APK 설치, adb·ffmpeg·python3 사용 가능.
# 산출물: 홈·쇼케이스·채팅·코드 확장·SSE·블록 편집 정지컷 9장, 05-sse-streaming.gif, 10-block-editor.gif
#
# 사용: scripts/capture-demo-screens.sh [ADB_SERIAL] [OUT_DIR]
#       ONLY=block-editor scripts/capture-demo-screens.sh   # 블록 편집 정지컷·GIF만 다시 만든다
set -euo pipefail

SERIAL="${1:-${ANDROID_SERIAL:-emulator-5554}}"
REPO="$(cd "$(dirname "$0")/.." && pwd -P)"
OUT="${2:-$REPO/docs/screenshots}"
PKG="io.github.jimmyjung.richmarkdown.demo"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
# 캡처 프레임·팔레트는 검증된 외장 SSD의 checkout별 고정 경로를 재사용한다.
WORK="$(python3 - "$REPO" "${CAPTURE_WORK_DIR:-}" <<'PYWORK'
import hashlib, pathlib, plistlib, subprocess, sys
volume = pathlib.Path("/Volumes/990EVO-1TB")
info = plistlib.loads(subprocess.check_output(["diskutil", "info", "-plist", str(volume)]))
assert (info["MountPoint"], info["VolumeUUID"], info["Internal"], info["WritableVolume"]) == (
    str(volume), "85D9ECCC-1754-48CD-856D-C41DC3D56220", False, True,
), "외장 SSD 검증 실패"
repo = pathlib.Path(sys.argv[1]).resolve()
key = repo.name + "-" + hashlib.sha256(str(repo).encode()).hexdigest()[:12]
work = pathlib.Path(sys.argv[2]) if sys.argv[2] else volume / "Developer/AgentBuilds" / key / "capture"
assert work.resolve().is_relative_to(volume), "캡처 임시 경로는 외장 SSD 안이어야 합니다"
work.mkdir(parents=True, exist_ok=True)
print(work.resolve())
PYWORK
)"
GIF_WIDTH="${GIF_WIDTH:-480}"   # README 열 폭에 맞춘 축소 폭(px)
GIF_FPS="${GIF_FPS:-8}"
SSE_SECONDS="${SSE_SECONDS:-9}"
ONLY="${ONLY:-all}"
case "$ONLY" in
  all | block-editor) ;;
  *) echo "ONLY는 all 또는 block-editor입니다: $ONLY" >&2; exit 2 ;;
esac

mkdir -p "$OUT" "$WORK"
adb() { "$ADB" -s "$SERIAL" "$@"; }

launch() { # <Activity 짧은 이름> [am start 추가 인자...]
  local activity="$1"; shift
  adb shell am force-stop "$PKG" >/dev/null
  adb shell am start -W -n "$PKG/.$activity" "$@" >/dev/null
  sleep "${SETTLE_SECONDS:-4}"   # 폰트 로드·수식 raster·WebView 다이어그램 완료 대기
}

shot() { # <파일명>
  adb exec-out screencap -p > "$OUT/$1"
  echo "  [png] $1 ($(stat -f%z "$OUT/$1") bytes)"
}

record_gif() { # <GIF 파일명> <최대 초> [조작 PID] — 최대 시간이 지나거나 조작 프로세스가 끝날 때까지 기록한다
  local gif="$1" job="${3:-}"
  local frames="$WORK/frames/${gif%.gif}" palette="$WORK/palette-${gif%.gif}.png"
  local frame_count=0 deadline=$((SECONDS + $2)) start_time frame_interval actual_fps
  mkdir -p "$frames"
  # 에뮬레이터는 screenrecord 인코더가 없을 수 있어 screencap 프레임 루프로 기록한다 (fps ≈ GIF_FPS).
  start_time="$(python3 -c 'import time; print(time.monotonic())')"
  frame_interval="$(python3 -c "print(1.0/$GIF_FPS)")"
  while [ "$SECONDS" -lt "$deadline" ] && { [ -z "$job" ] || kill -0 "$job" 2>/dev/null; }; do
    adb exec-out screencap -p > "$frames/$(printf '%04d' "$frame_count").png"
    frame_count=$((frame_count + 1))
    sleep "$frame_interval"
  done
  # screencap 전송 시간까지 포함해 재생 속도를 실제 캡처 시간에 맞춘다.
  actual_fps="$(python3 - "$start_time" "$frame_count" <<'PYFPS'
import sys, time
print(int(sys.argv[2]) / (time.monotonic() - float(sys.argv[1])))
PYFPS
)"
  if [ -n "$job" ]; then wait "$job"; fi   # 조작이 실패했으면 GIF를 만들지 않고 멈춘다
  echo "  프레임 ${frame_count}장"
  ffmpeg -loglevel error -y -framerate "$actual_fps" -i "$frames/%04d.png" \
    -vf "trim=end_frame=$frame_count,scale=$GIF_WIDTH:-1:flags=lanczos,palettegen=max_colors=128" "$palette"
  ffmpeg -loglevel error -y -framerate "$actual_fps" -i "$frames/%04d.png" -i "$palette" \
    -lavfi "scale=$GIF_WIDTH:-1:flags=lanczos [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=3" \
    -frames:v "$frame_count" "$OUT/$gif"
  echo "  [gif] $gif ($(stat -f%z "$OUT/$gif") bytes)"
}

ui_dump() { # 현재 화면의 접근성 트리를 $WORK/ui.xml에 받는다
  adb shell uiautomator dump /sdcard/richmarkdown-ui.xml >/dev/null
  adb exec-out cat /sdcard/richmarkdown-ui.xml > "$WORK/ui.xml"
}

ui_bounds() { # <라벨> — 마지막 ui_dump에서 text·content-desc·class가 라벨이거나 content-desc가 "라벨,"로 시작하는 항목의 "x1 y1 x2 y2"
  python3 - "$WORK/ui.xml" "$1" <<'PYUI'
import re, sys, xml.etree.ElementTree as ET
path, label = sys.argv[1:]
for node in ET.parse(path).iter("node"):
    desc = node.get("content-desc") or ""
    if label in (node.get("text"), desc, node.get("class")) or desc.startswith(label + ","):
        print(*re.findall(r"\d+", node.get("bounds")))
        break
else:
    sys.exit("화면 항목 없음: " + label)
PYUI
}

tap_center() { # <x1> <y1> <x2> <y2>
  adb shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
}

# 도구 모음 메뉴는 편집기 포커스를 지키는 비포커스 팝업이라 uiautomator 덤프에 나오지 않는다.
# 팝업 창 frame을 dumpsys에서 읽고, 위아래 8dp 여백을 뺀 높이를 항목 수로 나눠 행을 고른다.
BLOCK_KINDS=(문단 "제목 1" "제목 2" "제목 3" 목록 번호 "할 일" 인용 코드 수식)   # demo BlockEditorActions.kt의 BLOCK_KINDS 순서
tap_block_menu() { # <블록 종류 이름> — 열린 블록 추가·종류 메뉴에서 그 항목을 누른다
  local point
  adb shell dumpsys window windows > "$WORK/windows.txt"
  point="$(python3 - "$WORK/windows.txt" "$PKG" "$DENSITY" "$1" "${BLOCK_KINDS[@]}" <<'PYMENU'
import re, sys
path, package, density, title, kinds = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5:]
popups = [w for w in open(path).read().split("Window #") if "Pop-Up Window" in w and "package=" + package in w]
assert len(popups) == 1, "열린 메뉴 팝업이 하나가 아닙니다: %d" % len(popups)
x1, y1, x2, y2 = map(int, re.search(r" frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", popups[0]).groups())
padding = 8 * density // 160
row = (y2 - y1 - 2 * padding) / len(kinds)
print((x1 + x2) // 2, int(y1 + padding + row * (kinds.index(title) + 0.5)))
PYMENU
)"
  adb shell input tap $point
}

scroll_toolbar() { # <누를 x> <뗄 x> — 키보드 위 도구 모음의 가로 스크롤 영역을 끈다
  # 녹화 부하로 한 번 끌기가 끝까지 닿지 않을 때가 있어 두 번 끌어 스크롤 끝에 붙인다.
  adb shell input swipe "$1" "$TOOLBAR_Y" "$2" "$TOOLBAR_Y" 250
  adb shell input swipe "$1" "$TOOLBAR_Y" "$2" "$TOOLBAR_Y" 250
  sleep 0.4
}

block_editor_actions() { # <블록 추가> <블록 종류> <굵게> <실행 취소> 각 "x1 y1 x2 y2" — 녹화하는 동안 실행하는 조작
  sleep 1
  adb shell input text "%sPhase3"   # adb input은 한글을 입력하지 못해 영문 단어를 쓴다
  sleep 0.8
  adb shell input keycombination KEYCODE_SHIFT_LEFT KEYCODE_CTRL_LEFT KEYCODE_DPAD_LEFT   # 방금 입력한 단어 선택
  sleep 1
  tap_center $3; sleep 1
  tap_center $2; sleep 0.8; tap_block_menu 인용
  ui_dump; ui_bounds "블록 종류 바꾸기, 현재 블록: 인용" >/dev/null   # 덤프하는 동안 인용 상태를 보여준다
  # Ctrl+Z는 Gboard가 마지막 입력 글자 취소로 먼저 가져가므로 도구 모음의 실행 취소 버튼을 누른다.
  scroll_toolbar "${SCROLL_TO_END[@]}"; tap_center $4; sleep 1
  scroll_toolbar "${SCROLL_TO_START[@]}"; tap_center $1; sleep 0.8; tap_block_menu "할 일"; sleep 0.6
  adb shell input text "Update%sREADME"; sleep 2   # 반복 재생 전에 결과 화면을 보여준다
}

if [ "$ONLY" = all ]; then
  echo "[1/3] 정지컷 (기기 $SERIAL -> $OUT)"
  if [ "${SKIP_STILLS:-0}" != "1" ]; then
  launch MainActivity;                                    shot 00-home.png
  launch ShowcaseActivity --es renderer compose;           shot 01-showcase-compose.png
  launch ShowcaseActivity --es renderer view;              shot 02-showcase-view.png
  launch ComposeChatActivity;  shot 03-chat-compose.png
  launch ViewChatActivity;  shot 04-chat-view.png
  launch ShowcaseActivity --es section code --es renderer compose; shot 06-code-compose.png
  launch ShowcaseActivity --es section code --es renderer view;    shot 07-code-view.png
  launch SseStreamingActivity;                             shot 08-sse-idle.png
  fi

  echo "[2/3] SSE 스트리밍 GIF (${SSE_SECONDS}s)"
  SETTLE_SECONDS="${SSE_SETTLE_SECONDS:-0.5}" launch SseStreamingActivity --ez autoplay true   # 스트림 시작 장면부터 담는다
  record_gif 05-sse-streaming.gif "$SSE_SECONDS"
fi

echo "[3/3] 블록 편집 정지컷·GIF (기기 $SERIAL -> $OUT)"
launch BlockEditorActivity
if [ "${SKIP_STILLS:-0}" != "1" ]; then shot 09-block-editor.png; fi
DENSITY="$(adb shell wm density | grep -o '[0-9]*' | tail -1)"
ui_dump
document="$(ui_bounds android.widget.EditText)"
read -r _ top right _ <<<"$document"
# 제목 줄 끝에 caret을 두면 키보드와 도구 모음이 올라온다 (verify-demo-ui.py와 같은 위치).
adb shell input tap $((right - 30 * DENSITY / 160)) $((top + 32 * DENSITY / 160))
sleep 1.5
# 제목 1은 기본 글꼴이 굵어 굵게가 보이지 않으므로, 편집 문서 텍스트에서 다음 본문 문단 길이를 읽어
# caret을 그 문단 끝으로 옮긴다 (줄바꿈 1개 + 문단 글자 수만큼 →).
moves="$(python3 - "$WORK/ui.xml" <<'PYMOVE'
import sys, xml.etree.ElementTree as ET
text = next(n.get("text") for n in ET.parse(sys.argv[1]).iter("node") if n.get("class") == "android.widget.EditText")
paragraph = text.split("\n")[1]
assert paragraph.startswith("오늘 목표"), "제목 다음 줄이 예상한 본문 문단이 아닙니다: " + paragraph
print(1 + len(paragraph))
PYMOVE
)"
# 한 번에 몰아 보내면 데모의 선택 재동기화보다 빨라 일부 이동이 사라진다. 키마다 adb를 따로 부른다.
for ((i = 0; i < moves; i++)); do adb shell input keyevent KEYCODE_DPAD_RIGHT; done
sleep 0.5
ui_dump
add="$(ui_bounds "블록 추가")"
kind="$(ui_bounds "블록 종류 바꾸기")"
bold="$(ui_bounds 굵게)"
done_button="$(ui_bounds "키보드 닫기")"
read -r kind_left toolbar_top _ toolbar_bottom <<<"$kind"
read -r done_left _ done_right _ <<<"$done_button"
TOOLBAR_Y=$(((toolbar_top + toolbar_bottom) / 2))
# 스크롤 영역 안에서 누르고 반대쪽 화면 끝까지 끌어 끝에 붙인다. 화면 왼쪽 끝은 뒤로 가기 제스처 영역이라 누르지 않는다.
SCROLL_TO_END=("$((done_left - 40))" 0)
SCROLL_TO_START=("$((kind_left + 20))" "$done_right")
# 실행 취소는 가로 스크롤 끝에 있다. 끝까지 민 위치에서 좌표를 읽고 처음으로 되돌린 뒤 녹화한다.
scroll_toolbar "${SCROLL_TO_END[@]}"
ui_dump
undo="$(ui_bounds "실행 취소")"
scroll_toolbar "${SCROLL_TO_START[@]}"
block_editor_actions "$add" "$kind" "$bold" "$undo" &
record_gif 10-block-editor.gif 30 $!
# GIF가 의도한 결과로 끝났는지 내보낸 Markdown으로 확인한다 (문단 끝 굵게 유지·실행 취소로 문단 복귀·새 할 일).
ui_dump; options="$(ui_bounds "렌더 옵션")"; tap_center $options
ui_dump; toggle="$(ui_bounds "Markdown 보기")"; tap_center $toggle
adb shell input keyevent 4
ui_dump
python3 - "$WORK/ui.xml" <<'PYCHECK'
import sys, xml.etree.ElementTree as ET
texts = [node.get("text") or "" for node in ET.parse(sys.argv[1]).iter("node")]
markdown = next((text for text in texts if text.startswith("# 회의 노트\n")), None)
assert markdown, "내보낸 Markdown이 제목 1 '회의 노트'로 시작하지 않습니다"
lines = markdown.split("\n")
assert lines[1].startswith("**오늘 목표**") and lines[1].endswith(" **Phase3**") and lines[2] == "- [ ] Update README", (
    "블록 편집 GIF의 최종 Markdown이 예상과 다릅니다: %r" % lines[:3])
print("  [check] 내보낸 Markdown 2~3행: " + " ⏎ ".join(lines[1:3]))
PYCHECK

adb shell am force-stop "$PKG" >/dev/null
echo "완료. README 스크린샷 표는 docs/screenshots/*.png·*.gif를 참조한다."
