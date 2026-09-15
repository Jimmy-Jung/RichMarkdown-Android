#!/usr/bin/env bash
# Author: JunyoungJung
# Date: 2026-09-15
#
# iOS RichMarkdown 저장소의 공유 자산(Prism 번들, Mermaid 번들)을 이 저장소의 assets로 복사하고
# iOS Docs/CODE_BLOCK_EXTENSIONS.md의 SHA-256 표와 대조한다 (DEVELOPMENT.md D8).
#
#   ./scripts/sync-ios-assets.sh [iOS 저장소 경로]   # 기본 ../RichMarkdown
#
# 불일치가 하나라도 있으면 non-zero로 끝난다. iOS 저장소는 읽기만 한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
ios_root="${1:-$repo_root/../RichMarkdown}"
ios_root="$(cd "$ios_root" && pwd -P)"

docs="$ios_root/Docs/CODE_BLOCK_EXTENSIONS.md"
prism_src="$ios_root/Sources/RichMarkdownHighlight/Resources/Prism"
mermaid_src="$ios_root/Sources/RichMarkdownMermaid/Resources/WebAssets"
prism_dst="$repo_root/richmarkdown-highlight/src/main/assets/prism"
mermaid_dst="$repo_root/richmarkdown-mermaid/src/main/assets/mermaid"

for required in "$docs" "$prism_src" "$mermaid_src"; do
  [[ -e "$required" ]] || { echo "없음: $required" >&2; exit 2; }
done

source_commit="$(git -C "$ios_root" rev-parse HEAD)"
mkdir -p "$prism_dst" "$mermaid_dst"

# Docs 표의 `| \`파일\` | 바이트 | \`sha\` |` 행에서 파일명→sha를 뽑는다.
expected_sha() {
  local name="$1"
  awk -F'|' -v name="$name" '
    {
      f = $2; gsub(/[` ]/, "", f)
      if (f == name) { s = $4; gsub(/[` ]/, "", s); print s; exit }
    }' "$docs"
}

mismatches=0
copied=0

sync_dir() {
  local src="$1"
  local dst="$2"
  # 매니페스트는 assets 밖(모듈 루트)에 둔다 — APK에 실리지 않게.
  local module_root="${dst%%/src/*}"
  local manifest="$module_root/SYNC-MANIFEST.txt"
  {
    echo "# iOS RichMarkdown 자산 동기화 매니페스트"
    echo "# source_commit=$source_commit"
    echo "# columns: file<TAB>bytes<TAB>sha256<TAB>source_commit"
  } > "$manifest"

  local path name bytes actual expected
  for path in "$src"/*; do
    [[ -f "$path" ]] || continue
    name="$(basename "$path")"
    cp "$path" "$dst/$name"
    copied=$((copied + 1))
    bytes="$(wc -c < "$dst/$name" | tr -d ' ')"
    actual="$(shasum -a 256 "$dst/$name" | cut -d' ' -f1)"
    expected="$(expected_sha "$name")"
    if [[ -z "$expected" ]]; then
      # 브리지(native-tokenize.js)·고지 파일처럼 Docs 표에 없는 파일은 복사만 하고 기록한다.
      echo "  (표 없음) $name $bytes bytes"
    elif [[ "$expected" == "$actual" ]]; then
      echo "  OK  $name"
    else
      echo "  MISMATCH $name expected=$expected actual=$actual" >&2
      mismatches=$((mismatches + 1))
    fi
    printf '%s\t%s\t%s\t%s\n' "$name" "$bytes" "$actual" "$source_commit" >> "$manifest"
  done
}

echo "iOS 저장소: $ios_root ($source_commit)"
echo "Prism → $prism_dst"
sync_dir "$prism_src" "$prism_dst"
echo "Mermaid → $mermaid_dst"
sync_dir "$mermaid_src" "$mermaid_dst"

echo "복사 ${copied}개, SHA 불일치 ${mismatches}개"
[[ "$mismatches" -eq 0 ]]
