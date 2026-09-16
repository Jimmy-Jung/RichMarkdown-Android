#!/usr/bin/env bash
# Author: JunyoungJung
# Date: 2026-09-15
#
# 데모 앱 스크린샷·SSE 스트리밍 GIF를 다시 만든다 (iOS `scripts/capture-sse-gifs.sh` 대응).
# 전제: 대상 기기에 최신 `demo` 디버그 APK 설치, adb·ffmpeg·python3 사용 가능.
# 산출물: 홈·쇼케이스·채팅·코드 확장·SSE 정지컷 8장과 05-sse-streaming.gif
#
# 사용: scripts/capture-demo-screens.sh [ADB_SERIAL] [OUT_DIR]
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

echo "[1/2] 정지컷 (기기 $SERIAL -> $OUT)"
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

echo "[2/2] SSE 스트리밍 GIF (${SSE_SECONDS}s)"
SETTLE_SECONDS="${SSE_SETTLE_SECONDS:-0.5}" launch SseStreamingActivity --ez autoplay true   # 스트림 시작 장면부터 담는다
# 에뮬레이터는 screenrecord 인코더가 없을 수 있어 screencap 프레임 루프로 기록한다 (fps ≈ GIF_FPS).
frames="$WORK/frames"; mkdir -p "$frames"
frame_count=0
start_time="$(python3 -c 'import time; print(time.monotonic())')"
deadline=$((SECONDS + SSE_SECONDS))
frame_interval="$(python3 -c "print(1.0/$GIF_FPS)")"
while [ "$SECONDS" -lt "$deadline" ]; do
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
echo "  프레임 ${frame_count}장"
palette="$WORK/palette.png"
ffmpeg -loglevel error -y -framerate "$actual_fps" -i "$frames/%04d.png" \
  -vf "trim=end_frame=$frame_count,scale=$GIF_WIDTH:-1:flags=lanczos,palettegen=max_colors=128" "$palette"
ffmpeg -loglevel error -y -framerate "$actual_fps" -i "$frames/%04d.png" -i "$palette" \
  -lavfi "scale=$GIF_WIDTH:-1:flags=lanczos [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=3" \
  -frames:v "$frame_count" "$OUT/05-sse-streaming.gif"
echo "  [gif] 05-sse-streaming.gif ($(stat -f%z "$OUT/05-sse-streaming.gif") bytes)"

adb shell am force-stop "$PKG" >/dev/null
echo "완료. README 스크린샷 표는 docs/screenshots/*.png·*.gif를 참조한다."
