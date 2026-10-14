#!/bin/bash
# Author: JunyoungJung
# Date: 2026-10-14
#
# P2 크기 측정 — 데모 release APK(apkanalyzer file-size/download-size), 발행 모듈 5개 산출물 크기(AAR 4 + 코어 JAR 1),
# APK 안 모든 .so의 16 KB ELF 정렬(LOAD 세그먼트 align ≥ 0x4000, NDK llvm-readelf)과 zip 정렬(zipalign -P 16).
#   scripts/measure-sizes.sh            → $B/p2/sizes.json + 표 출력
# 빌드 환경은 scripts/gradle-env.sh (HANDOFF.md 외장 SSD 규칙·잠금). 저장소 안에 산출물을 만들지 않는다.
set -euo pipefail
source "$(dirname "$0")/gradle-env.sh"

OUT="$B/p2"
WORK="$OUT/sizes-work"
mkdir -p "$OUT" "$WORK"
APKANALYZER="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
ZIPALIGN="$(ls -d "$ANDROID_HOME"/build-tools/*/zipalign | sort -V | tail -1)"
READELF="$(ls -d "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt/darwin-*/bin/llvm-readelf | sort -V | tail -1)"
[ -x "$APKANALYZER" ] && [ -x "$ZIPALIGN" ] && [ -x "$READELF" ] || { echo "apkanalyzer / zipalign / llvm-readelf 중 없는 도구가 있다" >&2; exit 1; }

gradle_run :demo:assembleRelease :richmarkdown-core:jar :richmarkdown:assembleRelease \
  :richmarkdown-highlight:assembleRelease :richmarkdown-mermaid:assembleRelease :richmarkdown-editor:assembleRelease

APK="$B/build/demo/outputs/apk/release/demo-release-unsigned.apk"
VERSION="$(sed -n 's/^VERSION_NAME=//p' "$R/gradle.properties")"
ARTIFACTS=(
  "$B/build/richmarkdown-core/libs/richmarkdown-core-$VERSION.jar"
  "$B/build/richmarkdown/outputs/aar/richmarkdown-release.aar"
  "$B/build/richmarkdown-highlight/outputs/aar/richmarkdown-highlight-release.aar"
  "$B/build/richmarkdown-mermaid/outputs/aar/richmarkdown-mermaid-release.aar"
  "$B/build/richmarkdown-editor/outputs/aar/richmarkdown-editor-release.aar"
)

# apkanalyzer는 JAVA_TOOL_OPTIONS 배너 때문에 자바 버전 검사 경고(line 173)를 stderr에 낸다. 값은 stdout이다.
file_size="$("$APKANALYZER" apk file-size "$APK" 2>/dev/null)"
download_size="$("$APKANALYZER" apk download-size "$APK" 2>/dev/null)"

# .so 정렬 검사: Google check_elf_alignment.sh와 같은 기준(LOAD align ≥ 2**14) + zipalign -P 16. 추출 폴더는 매번 새로 만든다.
LIBS="$WORK/libs"
[ -d "$LIBS" ] && rm -r "$LIBS"
mkdir -p "$LIBS"
unzip -o -q "$APK" 'lib/*' -d "$LIBS" || true
ELF_TSV="$WORK/elf.tsv"
: > "$ELF_TSV"
while IFS= read -r so; do
  rel="${so#"$LIBS/"}"
  min_align="$("$READELF" -l "$so" | awk '$1=="LOAD"{print $NF}' | sort -u | python3 -c 'import sys; v=[int(x,16) for x in sys.stdin.read().split()]; print(hex(min(v)) if v else "0x0")')"
  ok=$(( min_align >= 0x4000 ))
  printf '%s\t%s\t%s\t%s\n' "$rel" "$min_align" "$ok" "$(stat -f%z "$so")" >> "$ELF_TSV"
done < <(find "$LIBS" -name '*.so' | sort)
zip_ok=1; "$ZIPALIGN" -c -P 16 -v 4 "$APK" > "$WORK/zipalign.log" 2>&1 || zip_ok=0

python3 -I - "$OUT/sizes.json" "$ELF_TSV" "$APK" "$file_size" "$download_size" "$zip_ok" "${ARTIFACTS[@]}" <<'PY'
import json, os, sys
out, elf_tsv, apk, file_size, download_size, zip_ok, *artifacts = sys.argv[1:]
rows = [line.split("\t") for line in open(elf_tsv).read().splitlines() if line.strip()]
# 16 KB 페이지 요건(Google Play)은 64비트 ABI(arm64-v8a·x86_64)만 대상이다. 32비트는 참고로 표시한다.
REQUIRED = ("lib/arm64-v8a/", "lib/x86_64/")
libs = [{"path": p, "minLoadAlign": a, "aligned16k": ok == "1", "required": p.startswith(REQUIRED), "bytes": int(b)}
        for p, a, ok, b in rows]
modules = [{"artifact": os.path.basename(a), "bytes": os.path.getsize(a)} for a in artifacts]
result = {
    "demoApk": {"path": apk, "fileSizeBytes": int(file_size), "downloadSizeBytes": int(download_size),
                "zipAligned16k": zip_ok == "1", "all64bit16kAligned": all(l["aligned16k"] for l in libs if l["required"]), "nativeLibs": libs},
    "modules": modules,
}
json.dump(result, open(out, "w"), indent=2, ensure_ascii=False)
print(f"demo release APK  file-size={int(file_size):,} B  download-size={int(download_size):,} B  zipalign-16k={'OK' if zip_ok=='1' else 'FAIL'}")
for m in modules: print(f"  {m['artifact']:<40} {m['bytes']:>12,} B")
print("native libs (LOAD align / 16 KB):")
for l in libs: print(f"  {l['path']:<48} {l['minLoadAlign']:>8}  {'OK' if l['aligned16k'] else ('FAIL' if l['required'] else '4K(32bit, 요건 외)')}  {l['bytes']:>10,} B")
PY
echo "[measure-sizes] → $OUT/sizes.json"
