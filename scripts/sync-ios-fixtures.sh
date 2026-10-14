#!/usr/bin/env bash
# Author: JunyoungJung
# Date: 2026-10-14
#
# iOS RichMarkdown 저장소의 공용 fixture 코퍼스(Tests/Fixtures)를 코어 테스트 리소스로 복사하고
# iOS manifest.json의 SHA-256과 대조한다 (iOS Tests/Fixtures/README.md §1).
#
#   ./scripts/sync-ios-fixtures.sh [iOS 저장소 경로]   # 기본 ../RichMarkdown
#
# 불일치가 하나라도 있으면 non-zero로 끝난다. iOS 저장소는 읽기만 한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
ios_root="${1:-$repo_root/../RichMarkdown}"
ios_root="$(cd "$ios_root" && pwd -P)"

src="$ios_root/Tests/Fixtures"
check="$ios_root/scripts/fixtures-manifest.py"
dst="$repo_root/richmarkdown-core/src/test/resources/fixtures"

for required in "$src/manifest.json" "$check"; do
  [[ -e "$required" ]] || { echo "없음: $required" >&2; exit 2; }
done

mkdir -p "$dst"
echo "iOS 저장소: $ios_root ($(git -C "$ios_root" rev-parse HEAD))"
echo "$src → $dst"
rsync -a --delete --exclude .DS_Store "$src/" "$dst/"

python3 -I "$check" --check "$dst"
