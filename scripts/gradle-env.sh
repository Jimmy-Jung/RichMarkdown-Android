#!/bin/bash
# Author: JunyoungJung
# Date: 2026-10-14
#
# Gradle 실행 환경을 셸 함수로 묶는다. run-benchmarks.sh·measure-sizes.sh가 source한다.
#   gradle_run <gradle 인자...>  — `mkdir "$B/gradle.lock"` 성공 후에만 실행하고 끝나면 rmdir. 잠금이 있으면 30초마다 재시도.
# 저장소 안에 build/·.gradle/을 만들지 않는다 (--project-cache-dir, -Prichmarkdown.buildRoot).
#
# 기기별 값은 저장소에 넣지 않는다. scripts/gradle-env.local.sh(gitignore)가 있으면 먼저 읽는다. 쓰는 변수:
#   RICHMARKDOWN_BUILD_DIR            빌드 산출물·임시 폴더 (기본 ~/.cache/richmarkdown-android)
#   RICHMARKDOWN_REQUIRE_VOLUME       지정하면 그 볼륨이 외장·쓰기 가능으로 마운트되어 있을 때만 진행한다
#   RICHMARKDOWN_REQUIRE_VOLUME_UUID  지정하면 볼륨 UUID까지 확인한다
#   GRADLE_USER_HOME · JAVA_HOME · ANDROID_HOME   지정하지 않으면 Gradle·Android Studio 기본값

R="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
[ -f "$R/scripts/gradle-env.local.sh" ] && source "$R/scripts/gradle-env.local.sh"
B="${RICHMARKDOWN_BUILD_DIR:-$HOME/.cache/richmarkdown-android}"

verify_build_volume() { # 디렉터리 존재만으로 마운트를 판단하지 않는다.
  [ -n "${RICHMARKDOWN_REQUIRE_VOLUME:-}" ] || return 0
  local plist uuid mount internal writable
  plist="$(diskutil info -plist "$RICHMARKDOWN_REQUIRE_VOLUME")" || { echo "$RICHMARKDOWN_REQUIRE_VOLUME 미마운트 — 중단" >&2; return 1; }
  uuid="$(echo "$plist" | plutil -extract VolumeUUID raw -)"
  mount="$(echo "$plist" | plutil -extract MountPoint raw -)"
  internal="$(echo "$plist" | plutil -extract Internal raw -)"
  writable="$(echo "$plist" | plutil -extract WritableVolume raw -)"
  [ "$mount" = "$RICHMARKDOWN_REQUIRE_VOLUME" ] && [ "$internal" = false ] && [ "$writable" = true ] &&
    { [ -z "${RICHMARKDOWN_REQUIRE_VOLUME_UUID:-}" ] || [ "$uuid" = "$RICHMARKDOWN_REQUIRE_VOLUME_UUID" ]; } ||
    { echo "빌드 볼륨 검증 실패 (uuid=$uuid mount=$mount internal=$internal writable=$writable) — 중단" >&2; return 1; }
}
verify_build_volume || return 1 2>/dev/null || exit 1

export TMPDIR="$B/tmp/"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ -n "${GRADLE_USER_HOME:-}" ]; then
  # JVM은 TMPDIR만으로 임시 경로가 바뀌지 않는다. 기존 JAVA_TOOL_OPTIONS는 보존한다.
  mkdir -p "$GRADLE_USER_HOME/.tmp"
  export GRADLE_USER_HOME JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.io.tmpdir=$GRADLE_USER_HOME/.tmp"
fi
mkdir -p "$B/tmp"

gradle_run() {
  until mkdir "$B/gradle.lock" 2>/dev/null; do
    echo "[gradle-env] $B/gradle.lock 점유 중 — 30초 후 재시도"
    sleep 30
  done
  trap 'rmdir "$B/gradle.lock" 2>/dev/null' EXIT INT TERM
  local rc=0
  (cd "$R" && ./gradlew "$@" --project-cache-dir "$B/gradle-project-cache" -Prichmarkdown.buildRoot="$B/build" --console=plain) || rc=$?
  rmdir "$B/gradle.lock" 2>/dev/null
  trap - EXIT INT TERM
  return "$rc"
}
