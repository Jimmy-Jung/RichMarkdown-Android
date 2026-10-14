#!/usr/bin/env python3
# Author: JunyoungJung
# Date: 2026-10-14
#
# richmarkdown-core/src/test/resources/fixtures/가 manifest.json(iOS 정본에서 복사됨)과 일치하는지 검사한다.
# iOS 저장소 없이 CI에서 쓴다. 동기화 자체는 scripts/sync-ios-fixtures.sh가 맡는다.
import hashlib, json, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1] / "richmarkdown-core" / "src" / "test" / "resources" / "fixtures"
SKIP = {"manifest.json", "README.md", ".DS_Store"}

manifest = {e["path"]: e["sha256"] for e in json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))["files"]}
actual = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
          for p in ROOT.rglob("*") if p.is_file() and p.name not in SKIP}
problems = sorted({*(f"missing: {p}" for p in manifest.keys() - actual.keys()),
                   *(f"extra: {p}" for p in actual.keys() - manifest.keys()),
                   *(f"changed: {p}" for p in manifest.keys() & actual.keys() if manifest[p] != actual[p])})
for line in problems:
    print(line)
print(f"fixtures: {'OK' if not problems else 'MISMATCH'} ({len(actual)} files)")
sys.exit(1 if problems else 0)
