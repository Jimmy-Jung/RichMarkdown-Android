#!/usr/bin/env python3
# Author: JunyoungJung
# Date: 2026-09-16
"""설치된 데모의 화면 회귀 검사. 사용: python3 scripts/verify-demo-ui.py emulator-5554

앱 데이터는 유지하며 데모 화면만 재실행한다. 캡처/XML은 검증된 외장 SSD에 저장한다.
"""
import hashlib
import json
import pathlib
import plistlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SERIAL = sys.argv[1]
PACKAGE = "io.github.jimmyjung.richmarkdown.demo"
PROJECT = pathlib.Path(__file__).resolve().parents[1]
VOLUME = pathlib.Path("/Volumes/990EVO-1TB")
info = plistlib.loads(subprocess.check_output(["diskutil", "info", "-plist", str(VOLUME)]))
assert (info["MountPoint"], info["VolumeUUID"], info["Internal"], info["WritableVolume"]) == (
    str(VOLUME), "85D9ECCC-1754-48CD-856D-C41DC3D56220", False, True,
), "외장 SSD 검증 실패"
key = PROJECT.name + "-" + hashlib.sha256(str(PROJECT).encode()).hexdigest()[:12]
OUTPUT = VOLUME / "Developer/AgentBuilds" / key / "ui-parity"
assert OUTPUT.resolve().is_relative_to(VOLUME)
OUTPUT.mkdir(parents=True, exist_ok=True)


def adb(*args):
    return subprocess.check_output(["adb", "-s", SERIAL, *args], timeout=30)


def screen(name):
    adb("shell", "uiautomator", "dump", "/sdcard/richmarkdown-ui.xml")
    data = adb("shell", "cat", "/sdcard/richmarkdown-ui.xml")
    (OUTPUT / (name + ".xml")).write_bytes(data)
    (OUTPUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
    return ET.fromstring(data)


def find(tree, label):
    for node in tree.iter("node"):
        if label in (node.get("text"), node.get("content-desc")):
            return node
    raise AssertionError("화면 항목 없음: " + label)


def tap(tree, label):
    node = find(tree, label)
    assert node.get("enabled") == "true", "비활성 항목: " + label
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.3)


def launch(activity):
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/." + activity)


launch("MainActivity")
home = screen("home")
for label in ["AI 챗봇 (Compose)", "AI 챗봇 (View)", "SSE 실시간 렌더링 (Compose)",
              "코드 블록 확장 (Mermaid · Prism)", "샘플 쇼케이스 (Compose · View)"]:
    find(home, label)
tap(home, "AI 챗봇 (Compose)")
chat = screen("chat-compose")
find(chat, "원의 넓이 공식이 뭐야?")
tap(chat, "렌더 옵션")
options = screen("chat-options")
tap(options, "케이스 라벨 표시")
adb("shell", "input", "keyevent", "4")
chat = screen("chat-labels-hidden")
assert not any(n.get("text") == "인라인 수식 · baseline" for n in chat.iter("node"))
tap(chat, "재생")
time.sleep(14)
chat = screen("chat-replay-complete")
assert any("오차함수로 쓰면" in n.get("text", "") for n in chat.iter("node")), "긴 채팅 답변의 하단 미표시"
tap(chat, "뒤로")
find(screen("home-return"), "RichMarkdown Demo")

launch("ViewChatActivity")
chat = screen("chat-view")
find(chat, "원의 넓이 공식이 뭐야?")
find(chat, "뒤로 가기")
tap(chat, "재생")
# 같은 좌표로 바로 다시 재생하여 취소된 작업의 finally가 새 작업을 덮지 않는지 확인한다.
tap(chat, "재생")
time.sleep(14)
chat = screen("chat-view-replay-complete")
assert any("오차함수로 쓰면" in n.get("text", "") for n in chat.iter("node")), "View 재시작 후 하단 미표시"

launch("MainActivity")
tap(screen("home-code"), "코드 블록 확장 (Mermaid · Prism)")
code = screen("code-compose")
find(code, "검색 파이프라인")
tap(code, "View")
code = screen("code-view")
find(code, "검색 파이프라인")
tap(code, "렌더 옵션")
options = screen("code-options")
for label in ["신택스 하이라이팅 (Prism)", "Mermaid 다이어그램", "다크 모드"]:
    find(options, label)
tap(options, "다크 모드")
adb("shell", "input", "keyevent", "4")
find(screen("code-dark"), "검색 파이프라인")

launch("SseStreamingActivity")
sse = screen("sse-idle")
find(sse, "시작을 누르면 SSE 프레임이 도착하는 대로 렌더링합니다.")
for rate in ["5Hz", "20Hz", "60Hz"]:
    find(sse, rate)
tap(sse, "5Hz")
sse = screen("sse-slow")
tap(sse, "시작")
sse = screen("sse-running")
find(sse, "중지")
# Compose merges button enabled state into the clickable ancestor.
rate_nodes = [node for node in sse.iter("node")
              if any(child.get("text") in ["5Hz", "20Hz", "60Hz"] for child in node)]
assert len(rate_nodes) == 3, "속도 선택 버튼을 찾지 못함"
assert all(node.get("enabled") == "false" for node in rate_nodes), "스트리밍 중 속도 변경 가능"
tap(sse, "중지")
sse = screen("sse-stopped")
tap(sse, "60Hz")
sse = screen("sse-fast")
tap(sse, "시작")
time.sleep(8)
sse = screen("sse-complete")
find(sse, "완료 ([DONE])")
find(sse, "시작")
assert any("오차함수로 쓰면" in n.get("text", "") for n in sse.iter("node")), "최종 답변 미표시"
print(json.dumps({"result": "passed", "checks": ["home", "chat-order", "chat-replay-bottom", "labels", "back",
    "native-chat-restart", "code-renderers", "dark", "sse-speed-stop-restart-complete"],
    "evidence": str(OUTPUT)}, ensure_ascii=False))
