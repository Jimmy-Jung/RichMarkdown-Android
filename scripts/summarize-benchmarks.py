#!/usr/bin/env python3
# Author: JunyoungJung
# Date: 2026-10-14
"""P2 결과 집계 — $B/p2/{micro,macro}/**/benchmarkData.json, sizes.json, device.txt(+ notes.md) → $B/p2/android-results.md.

    scripts/summarize-benchmarks.py [P2_DIR]   (기본 $RICHMARKDOWN_BUILD_DIR/p2, scripts/gradle-env.sh와 같은 빌드 폴더)

Microbenchmark: timeNs median·min, allocationCount median. Macrobenchmark: StartupTimingMetric(timeToInitialDisplayMs)
median·P50·P90(runs 보간), FrameTimingMetric frameDurationCpuMs P50/P90/P99·frameOverrunMs P50/P90/P99.
"""
import glob
import json
import math
import os
import re
import sys
from datetime import date

P2 = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.environ.get("RICHMARKDOWN_BUILD_DIR", os.path.expanduser("~/.cache/richmarkdown-android")), "p2")


def load_benchmarks(kind):
    """재실행으로 같은 벤치마크가 여러 JSON에 있으면 가장 최근 파일의 결과를 쓴다."""
    latest = {}
    for path in glob.glob(os.path.join(P2, kind, "**", "*benchmarkData.json"), recursive=True):
        with open(path) as f:
            data = json.load(f)
        mtime = os.path.getmtime(path)
        for bench in data.get("benchmarks", []):
            key = (bench.get("className"), bench["name"])
            if key not in latest or latest[key][0] < mtime:
                latest[key] = (mtime, (path, data.get("context", {}), bench))
    return [row for _, row in sorted(latest.values(), key=lambda v: v[1][2]["name"])]


def percentile(values, p):
    values = sorted(values)
    if not values:
        return math.nan
    k = (len(values) - 1) * p / 100
    lo, hi = math.floor(k), math.ceil(k)
    return values[lo] + (values[hi] - values[lo]) * (k - lo)


def fmt(value, digits=2):
    return "—" if value is None or (isinstance(value, float) and math.isnan(value)) else f"{value:,.{digits}f}"


def micro_table(rows):
    lines = ["| 벤치마크 | median | min | allocations (median) | 측정 × 반복 |", "|---|---:|---:|---:|---:|"]
    for _, _, b in rows:
        t = b["metrics"].get("timeNs", {})
        a = b["metrics"].get("allocationCount", {})
        unit, scale = ("ms", 1e6) if t.get("median", 0) >= 1e6 else ("µs", 1e3)
        lines.append(
            f"| `{b['name']}` | {fmt(t.get('median', math.nan) / scale)} {unit} | {fmt(t.get('minimum', math.nan) / scale)} {unit} "
            f"| {fmt(a.get('median'), 0)} | {len(t.get('runs', []))} × {b.get('repeatIterations', '—')} |"
        )
    return lines


def startup_table(rows):
    lines = ["| 시나리오 | 컴파일 모드 | TTID median | P50 | P90 | min | max | runs (ms) |", "|---|---|---:|---:|---:|---:|---:|---|"]
    for _, _, b in rows:
        m = b["metrics"].get("timeToInitialDisplayMs")
        if not m:
            continue
        runs = m.get("runs", [])
        mode = "Partial(speed-profile, warmup 3)" if b["name"].endswith("_partial") else "None"
        lines.append(
            f"| `{b['name']}` | {mode} | {fmt(m['median'])} | {fmt(percentile(runs, 50))} | {fmt(percentile(runs, 90))} "
            f"| {fmt(m['minimum'])} | {fmt(m['maximum'])} | {', '.join(fmt(r, 1) for r in runs)} |"
        )
    return lines


def frame_table(rows):
    lines = ["| 시나리오 | 지표 | P50 | P90 | P95 | P99 | 프레임 수(반복별) |", "|---|---|---:|---:|---:|---:|---|"]
    for _, _, b in rows:
        for metric, stats in sorted(b.get("sampledMetrics", {}).items()):
            counts = [len(r) for r in stats.get("runs", [])]
            lines.append(
                f"| `{b['name']}` | {metric} | {fmt(stats.get('P50'))} | {fmt(stats.get('P90'))} | {fmt(stats.get('P95'))} "
                f"| {fmt(stats.get('P99'))} | {', '.join(map(str, counts))} |"
            )
    return lines


def parse_gfxinfo(path):
    """`dumpsys gfxinfo <pkg>` 한 덤프 → 카운터와 HISTOGRAM(ms → 프레임 수)."""
    text = open(path, errors="replace").read()
    counter = lambda label: int(re.search(rf"^{re.escape(label)}: (\d+)", text, re.M).group(1))
    histogram = {}
    match = re.search(r"^HISTOGRAM: (.+)$", text, re.M)
    for bucket in (match.group(1).split() if match else []):
        ms, count = bucket.split("ms=")
        histogram[int(ms)] = histogram.get(int(ms), 0) + int(count)
    return {
        "frames": counter("Total frames rendered"), "janky": counter("Janky frames"),
        "jankyLegacy": counter("Janky frames (legacy)"), "deadlineMissed": counter("Number Frame deadline missed"),
        "histogram": histogram,
    }


def histogram_percentile(histogram, p):
    total = sum(histogram.values())
    seen = 0
    for ms in sorted(histogram):
        seen += histogram[ms]
        if seen * 100 >= total * p:
            return ms
    return math.nan


def gfxinfo_section():
    runs = sorted(glob.glob(os.path.join(P2, "gfxinfo", "*")))
    if not runs:
        return ["(gfxinfo 결과 없음)"]
    latest = runs[-1]
    lines = [f"원본: `gfxinfo/{os.path.basename(latest)}/` (반복 5회 × 6페이지 왕복, 히스토그램 합산 백분위, 1 ms 버킷 — 최소 버킷이 5 ms라 \"5 ms\"는 5 ms 이하)", "",
             "| 화면 | 프레임 수(반복별) | P50 | P90 | P95 | P99 | janky (deadline) | janky (legacy 16 ms) | deadline missed |",
             "|---|---|---:|---:|---:|---:|---:|---:|---:|"]
    for activity in ("ViewChatActivity", "ComposeChatActivity"):
        dumps = [parse_gfxinfo(f) for f in sorted(glob.glob(os.path.join(latest, f"{activity}-*.txt")))]
        if not dumps:
            continue
        merged = {}
        for d in dumps:
            for ms, count in d["histogram"].items():
                merged[ms] = merged.get(ms, 0) + count
        frames = sum(d["frames"] for d in dumps)
        pct = lambda key: f"{sum(d[key] for d in dumps):,} ({100 * sum(d[key] for d in dumps) / frames:.1f}%)"
        lines.append(
            f"| {activity} | {', '.join(str(d['frames']) for d in dumps)} | {histogram_percentile(merged, 50)} ms "
            f"| {histogram_percentile(merged, 90)} ms | {histogram_percentile(merged, 95)} ms | {histogram_percentile(merged, 99)} ms "
            f"| {pct('janky')} | {pct('jankyLegacy')} | {sum(d['deadlineMissed'] for d in dumps):,} |"
        )
    dexopt = os.path.join(latest, "dexopt.txt")
    if os.path.exists(dexopt):
        lines += ["", f"컴파일 상태: `{' '.join(open(dexopt).read().split())}`"]
    return lines


def sizes_section():
    path = os.path.join(P2, "sizes.json")
    if not os.path.exists(path):
        return ["(sizes.json 없음 — scripts/measure-sizes.sh 미실행)"]
    with open(path) as f:
        s = json.load(f)
    apk = s["demoApk"]
    lines = [
        "| 항목 | 크기 |", "|---|---:|",
        f"| demo release APK file-size | {apk['fileSizeBytes']:,} B |",
        f"| demo release APK download-size | {apk['downloadSizeBytes']:,} B |",
    ]
    lines += [f"| {m['artifact']} | {m['bytes']:,} B |" for m in s["modules"]]
    verdict = "64비트 ABI 전부 통과" if apk["all64bit16kAligned"] else "64비트 ABI 실패 있음"
    lines += ["", f"16 KB 정렬: ELF LOAD {verdict} (요건 대상 arm64-v8a·x86_64), zipalign -P 16 {'통과' if apk['zipAligned16k'] else '실패'}", "",
              "| .so | 최소 LOAD align | 16 KB | 바이트 |", "|---|---:|:---:|---:|"]
    mark = lambda l: "OK" if l["aligned16k"] else ("FAIL" if l["required"] else "4 KB (32비트, 요건 외)")
    lines += [f"| {l['path']} | {l['minLoadAlign']} | {mark(l)} | {l['bytes']:,} |" for l in apk["nativeLibs"]]
    return lines


def device_section(micro_rows):
    lines = []
    path = os.path.join(P2, "device.txt")
    if os.path.exists(path):
        lines += ["```", open(path).read().rstrip(), "```"]
    if micro_rows:
        ctx = micro_rows[0][1]
        build = ctx.get("build", {})
        lines.append(
            f"benchmark context: cpuLocked={ctx.get('cpuLocked')} sustainedPerformanceMode={ctx.get('sustainedPerformanceModeEnabled')} "
            f"cores={ctx.get('cpuCoreCount')} maxFreq={ctx.get('cpuMaxFreqHz')} memTotal={ctx.get('memTotalBytes')} "
            f"fingerprint={build.get('fingerprint')}"
        )
    return lines


def main():
    micro, macro = load_benchmarks("micro"), load_benchmarks("macro")
    startup = [r for r in macro if "timeToInitialDisplayMs" in r[2]["metrics"]]
    frames = [r for r in macro if r[2].get("sampledMetrics")]
    doc = [
        f"# Android P2 성능 하네스 결과 ({date.today().isoformat()})", "",
        "- 기기: Samsung SM-G988N (Android 13, API 33) 실기기. 에뮬레이터 수치는 포함하지 않는다.",
        "- 실행: `scripts/run-benchmarks.sh {micro|macro|gfxinfo|all}` → `scripts/measure-sizes.sh` → `scripts/summarize-benchmarks.py`. 표별 실행 시각·열 상태는 §6.",
        "- 원본: `micro/**/benchmarkData.json`, `macro/**/benchmarkData.json`(+ `.perfetto-trace`), `sizes.json`, `device.txt`.", "",
        "## 1. Microbenchmark (`:benchmark-micro`, release 테스트 APK)", "", *micro_table(micro), "",
        "## 2. 런치 (`StartupTimingMetric`, 5회)", "", *startup_table(startup), "",
        "## 3. 프레임 타이밍 (`FrameTimingMetric`, 5회, Partial 컴파일)", "", *frame_table(frames), "",
        "## 3-b. 스크롤 프레임 시간 (`dumpsys gfxinfo`, 하네스 없음 — §6 참고)", "", *gfxinfo_section(), "",
        "## 4. 크기", "", *sizes_section(), "",
        "## 5. 기기·환경", "", *device_section(micro), "",
    ]
    notes = os.path.join(P2, "notes.md")  # 실행 이력·주의 사항(수기). 있으면 그대로 붙인다.
    if os.path.exists(notes):
        doc += [open(notes).read().rstrip(), ""]
    out = os.path.join(P2, "android-results.md")
    with open(out, "w") as f:
        f.write("\n".join(doc))
    print(f"[summarize] micro {len(micro)} / startup {len(startup)} / frame {len(frames)} → {out}")


if __name__ == "__main__":
    main()
