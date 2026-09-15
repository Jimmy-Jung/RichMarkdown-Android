#!/usr/bin/env bash
# Author: JunyoungJung
# Date: 2026-09-15
#
# 데모 앱 스크린샷·SSE 스트리밍 GIF를 다시 만든다 (iOS `scripts/capture-sse-gifs.sh` 대응).
# 전제: 에뮬레이터/기기 1대 연결, `demo` 디버그 APK 설치됨(`./gradlew :demo:installDebug`), ffmpeg 설치.
# 산출물: docs/screenshots/{01-showcase-compose,02-showcase-view,03-chat-compose,04-chat-view}.png, 05-sse-streaming.gif
#
# 사용: scripts/capture-demo-screens.sh [ADB_SERIAL] [OUT_DIR]
set -euo pipefail

SERIAL="${1:-${ANDROID_SERIAL:-emulator-5554}}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${2:-$REPO/docs/screenshots}"
PKG="io.github.jimmyjung.richmarkdown.demo"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
WORK="${CAPTURE_WORK_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/richmarkdown-capture.XXXXXX")}"
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
launch ShowcaseActivity --es renderer compose;           shot 01-showcase-compose.png
launch ShowcaseActivity --es renderer view;              shot 02-showcase-view.png
launch ComposeChatActivity --ez autoplay true;  sleep 6;  shot 03-chat-compose.png
launch ViewChatActivity --ez autoplay true;     sleep 6;  shot 04-chat-view.png
fi

echo "[2/2] SSE 스트리밍 GIF (${SSE_SECONDS}s)"
SETTLE_SECONDS="${SSE_SETTLE_SECONDS:-0.5}" launch SseStreamingActivity --ez autoplay true   # 스트림 시작 장면부터 담는다
# 에뮬레이터는 screenrecord 인코더가 없을 수 있어 screencap 프레임 루프로 기록한다 (fps ≈ GIF_FPS).
frames="$WORK/frames"; mkdir -p "$frames"
frame_count=0
deadline=$((SECONDS + SSE_SECONDS))
frame_interval="$(python3 -c "print(1.0/$GIF_FPS)")"
while [ "$SECONDS" -lt "$deadline" ]; do
  adb exec-out screencap -p > "$frames/$(printf '%04d' "$frame_count").png"
  frame_count=$((frame_count + 1))
  sleep "$frame_interval"
done
echo "  프레임 ${frame_count}장"
palette="$WORK/palette.png"
ffmpeg -loglevel error -y -framerate "$GIF_FPS" -i "$frames/%04d.png" \
  -vf "scale=$GIF_WIDTH:-1:flags=lanczos,palettegen=max_colors=128" "$palette"
ffmpeg -loglevel error -y -framerate "$GIF_FPS" -i "$frames/%04d.png" -i "$palette" \
  -lavfi "scale=$GIF_WIDTH:-1:flags=lanczos [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=3" \
  "$OUT/05-sse-streaming.gif"
echo "  [gif] 05-sse-streaming.gif ($(stat -f%z "$OUT/05-sse-streaming.gif") bytes)"

adb shell am force-stop "$PKG" >/dev/null
echo "완료. README 스크린샷 표는 docs/screenshots/*.png·*.gif를 참조한다."
