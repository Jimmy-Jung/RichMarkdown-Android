#!/bin/bash
# Author: JunyoungJung
# Date: 2026-10-14
#
# P2 성능 하네스 실행기 — Microbenchmark(:benchmark-micro)·Macrobenchmark(:benchmark-macro)를 실기기에서 돌리고 결과를 모은다.
#   scripts/run-benchmarks.sh [micro|macro|all|gfxinfo] [추가 Gradle 인자...]   (기본 all)
#     gfxinfo — Gradle·Perfetto·컴파일 명령 없이, 설치된 demo(benchmark 빌드)를 am start + input swipe로 스크롤하고
#               반복마다 `dumpsys gfxinfo`를 남긴다. SM-G988N은 Macrobenchmark 실행 중 커널 패닉(`kmem_cache_free` Oops)으로
#               재부팅돼(2026-10-14, 3회) Compose 챗 스크롤을 이 경로로 쟀다. ScrollBenchmark와 같은 제스처다.
#     예) 한 클래스만: scripts/run-benchmarks.sh macro -Pandroid.testInstrumentationRunnerArguments.class=io.github.jimmyjung.richmarkdown.benchmark.macro.StartupBenchmark
#   ANDROID_SERIAL  대상 기기 (기본 R3CN20B37FP, Samsung SM-G988N / Android 13). 에뮬레이터 수치는 보고하지 않는다.
#   COOL_SKIN_C     각 모듈 실행 전 대기 조건: thermal status 0 + SKIN 온도 < 이 값 (기본 35). 0이면 대기하지 않는다.
#                   (2026-10-14 실측: 연속 실행 뒤 status 2·SKIN 40.7 °C에서 런치 수치가 측정됨 → 냉각 후 재측정)
# 빌드 환경은 scripts/gradle-env.sh(HANDOFF.md 외장 SSD 규칙: GRADLE_USER_HOME·TMPDIR·JAVA_HOME·ANDROID_HOME·JAVA_TOOL_OPTIONS,
# --project-cache-dir, -Prichmarkdown.buildRoot, --console=plain, mkdir "$B/gradle.lock" 잠금)을 그대로 쓴다.
# 결과:
#   $B/p2/micro/<실행 시각>/   benchmarkData.json + perfetto 트레이스 + HTML 리포트 (AGP connected_android_test_additional_output 복사)
#   $B/p2/macro/<실행 시각>/   benchmarkData.json + 반복별 perfetto 트레이스 + HTML 리포트
#   $B/p2/gfxinfo/<실행 시각>/<Activity>-<반복>.txt   gfxinfo 원본 (summarize-benchmarks.py가 파싱)
#   $B/p2/logs/    Gradle 로그 (실행별)
#   $B/p2/device-<시각>-<micro|macro>.txt  단계 직전 기기·환경 스냅샷 (모델·빌드·배터리·열 상태·화면 주사율)
# 실패한 벤치마크가 있어도 결과를 복사한 뒤 Gradle 종료 코드로 끝난다.
set -uo pipefail
source "$(dirname "$0")/gradle-env.sh" || exit 1

MODE="${1:-all}"
shift $(( $# > 0 ? 1 : 0 ))
export ANDROID_SERIAL="${ANDROID_SERIAL:-R3CN20B37FP}"
ADB="$ANDROID_HOME/platform-tools/adb"
OUT="$B/p2"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT/micro" "$OUT/macro" "$OUT/logs"

snapshot_device() { # <단계> — 단계 직전 기기 상태를 $OUT/device-<시각>-<단계>.txt에 남긴다(device.txt는 최신 사본).
  local file="$OUT/device-$STAMP-$1.txt"
  {
    echo "serial=$ANDROID_SERIAL"
    echo "model=$("$ADB" shell getprop ro.product.model)"
    echo "android=$("$ADB" shell getprop ro.build.version.release) (API $("$ADB" shell getprop ro.build.version.sdk))"
    echo "fingerprint=$("$ADB" shell getprop ro.build.fingerprint)"
    echo "battery: $("$ADB" shell dumpsys battery | grep -E 'level|status|USB powered' | tr -s ' ' | tr '\n' ';')"
    echo "thermal: $("$ADB" shell dumpsys thermalservice 2>/dev/null | grep -E 'Thermal Status|mName=(AP|SKIN|BAT)' | tr -s ' \t' ' ' | tr '\n' ';')"
    echo "display: $("$ADB" shell wm size | tr '\n' ' ') density=$("$ADB" shell wm density | tr '\n' ' ')"
    local disp mode
    disp="$("$ADB" shell dumpsys display)"
    mode="$(echo "$disp" | grep -m1 -oE 'mActiveModeId=[0-9]+' | cut -d= -f2)"
    echo "refresh: activeMode=$(echo "$disp" | grep -m1 -oE "\{id=$mode, width=[0-9]+, height=[0-9]+, fps=[0-9.]+") samsung refresh_rate_mode=$("$ADB" shell settings get secure refresh_rate_mode) (2=적응형)"
  } > "$file"
  cp "$file" "$OUT/device.txt"
  cat "$file"
}

require_device() { # 실기기가 USB에서 빠지면(도크 허브 재열거 실측) 빌드 전에 멈춘다.
  [ "$("$ADB" get-state 2>/dev/null)" = device ] || { echo "[run-benchmarks] $ANDROID_SERIAL 연결 안 됨 — 중단" >&2; exit 2; }
}

wait_cool() { # 열 스로틀이 런치·프레임 수치를 바꾼다. 화면을 끈 채 30초마다 확인한다.
  local limit="${COOL_SKIN_C:-35}" info st skin
  [ "$limit" = 0 ] && return
  "$ADB" shell input keyevent KEYCODE_SLEEP < /dev/null
  while :; do
    info="$("$ADB" shell dumpsys thermalservice < /dev/null)"
    st="$(echo "$info" | sed -n 's/^Thermal Status: //p' | head -1)"
    skin="$(echo "$info" | grep -m1 'mName=SKIN' | sed -E 's/.*mValue=([0-9.]+).*/\1/')"
    [ "$st" = 0 ] && awk "BEGIN{exit !($skin < $limit)}" && break
    echo "[run-benchmarks] 냉각 대기: thermal=$st SKIN=$skin °C (목표 0 / < $limit °C)"
    sleep 30
  done
}

wake_device() { # 벤치마크 러너는 잠금 화면에서 실패한다. 지속 설정은 바꾸지 않는다.
  "$ADB" shell input keyevent KEYCODE_WAKEUP
  "$ADB" shell wm dismiss-keyguard
}

copy_outputs() { # <모듈 빌드 디렉터리> <대상> — 실행별 하위 폴더에 보존한다(summarize는 벤치마크별 최신 결과를 쓴다).
  local src="$1/outputs/connected_android_test_additional_output" dst="$2/$STAMP"
  mkdir -p "$dst"
  [ -d "$src" ] && cp -R "$src/." "$dst/" && echo "[run-benchmarks] 결과 → $dst"
  [ -d "$1/reports/androidTests" ] && mkdir -p "$dst/report" && cp -R "$1/reports/androidTests/." "$dst/report/"
  find "$dst" -name '*benchmarkData.json' -print
}

PKG=io.github.jimmyjung.richmarkdown.demo
GFX_ITERATIONS=5

gfx_scroll() { # <Activity> — 웜 진입 뒤 반복마다 gfxinfo reset → 6페이지 아래·6페이지 위 → 덤프
  local dir="$OUT/gfxinfo/$STAMP" size w h x low high i
  mkdir -p "$dir"
  size="$("$ADB" shell wm size < /dev/null | tail -1 | awk '{print $NF}')" # Override가 있으면 그것(UiDevice와 같은 논리 해상도)
  w="${size%x*}"; h="${size#*x}"; x=$((w / 2)); low=$((h * 3 / 4)); high=$((h / 4))
  "$ADB" shell am force-stop "$PKG" < /dev/null
  "$ADB" shell am start -W -n "$PKG/.$1" < /dev/null > /dev/null
  sleep 1.5
  for i in $(seq 0 $((GFX_ITERATIONS - 1))); do
    "$ADB" shell dumpsys gfxinfo "$PKG" reset < /dev/null > /dev/null
    for _ in 1 2 3 4 5 6; do "$ADB" shell input swipe "$x" "$low" "$x" "$high" 300 < /dev/null; sleep 0.7; done
    for _ in 1 2 3 4 5 6; do "$ADB" shell input swipe "$x" "$high" "$x" "$low" 300 < /dev/null; sleep 0.7; done
    "$ADB" shell dumpsys gfxinfo "$PKG" < /dev/null > "$dir/$1-$i.txt"
  done
  "$ADB" shell dumpsys package dexopt < /dev/null | grep -A2 "\[$PKG\]" > "$dir/dexopt.txt"
  echo "[run-benchmarks] gfxinfo $1 → $dir"
}

rc=0
if [ "$MODE" = gfxinfo ]; then
  require_device
  "$ADB" shell pm list packages "$PKG" < /dev/null | grep -q "$PKG" || { echo "demo 미설치 — :demo:installBenchmark 먼저" >&2; exit 2; }
  wait_cool
  snapshot_device gfxinfo
  wake_device
  gfx_scroll ViewChatActivity
  gfx_scroll ComposeChatActivity
fi
if [ "$MODE" = micro ] || [ "$MODE" = all ]; then
  require_device
  wait_cool
  snapshot_device micro
  wake_device
  gradle_run :benchmark-micro:connectedReleaseAndroidTest "$@" 2>&1 | tee "$OUT/logs/micro-$STAMP.log" || rc=$?
  copy_outputs "$B/build/benchmark-micro" "$OUT/micro"
fi
if [ "$MODE" = macro ] || [ "$MODE" = all ]; then
  require_device
  wait_cool
  snapshot_device macro
  wake_device
  gradle_run :benchmark-macro:connectedBenchmarkAndroidTest "$@" 2>&1 | tee "$OUT/logs/macro-$STAMP.log" || rc=$?
  copy_outputs "$B/build/benchmark-macro" "$OUT/macro"
fi
exit "$rc"
