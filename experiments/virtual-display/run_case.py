#!/usr/bin/env python3
"""Compare device sleep with physical display power-off using synthetic App state."""
import argparse
import fcntl
import json
import re
import socket
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--profile", type=Path, default=Path(__file__).with_name("profile.json"))
parser.add_argument("--seconds", type=int, default=12)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
lock_path = Path(__file__).with_name("artifacts") / "case.lock"
lock_path.parent.mkdir(parents=True, exist_ok=True)
case_lock = lock_path.open("w")
try:
    fcntl.flock(case_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    raise SystemExit("CASE_ALREADY_RUNNING")
profile = json.loads(args.profile.read_text())
strategy = profile["power_strategy"]
if strategy not in ("sleep", "display-only"):
    raise SystemExit("UNKNOWN_POWER_STRATEGY")
events = []

def adb(*command):
    result = subprocess.run(["adb", "-s", args.serial, *command], capture_output=True, text=True, timeout=15)
    events.append({"command": ["adb", "-s", args.serial, *command], "stdout": result.stdout,
                   "stderr": result.stderr, "exit": result.returncode, "time": time.time()})
    result.check_returncode()
    return result.stdout

def rpc(request):
    with socket.create_connection(("127.0.0.1", 18765), timeout=15) as connection:
        connection.sendall((json.dumps(request, ensure_ascii=False) + "\n").encode())
        value = json.loads(connection.makefile("r", encoding="utf-8").readline())
    events.append({"rpc": request, "result": value, "time": time.time()})
    return value

def state():
    value = rpc({"action": "nodes"})
    for window in value.get("windows", []):
        for node in window["nodes"]:
            if node["description"] == "eta-vd-state":
                return json.loads(node["text"])
    return None

def display_states():
    raw = adb("shell", "cmd", "display", "get-displays")
    return {int(m.group(1)): m.group(2) for m in re.finditer(r'Display id (\d+):[^\n]*?, state (\w+)', raw)}

try:
    adb("shell", "cmd", "display", "power-reset", "0")
    adb("shell", "input", "-d", "0", "keyevent", "224")
    time.sleep(2)
    start = rpc({"action": "status"})
    display = start["displayId"]
    before = state()
    if before is None:
        raise RuntimeError("NO_SYNTHETIC_APP_WINDOW_BEFORE_TEST")
    if strategy == "sleep":
        adb("shell", "input", "-d", "0", "keyevent", "223")
    else:
        adb("shell", "cmd", "display", "power-off", "0")
    time.sleep(2)
    power_states_before = display_states()
    first = rpc({"action": "status"})
    deadline = time.monotonic() + args.seconds
    while time.monotonic() < deadline:
        time.sleep(min(10, max(0, deadline - time.monotonic())))
        rpc({"action": "status"})
    rpc({"action": "tap", "x": 360, "y": 139})
    marker = "USB探索-" + strategy + "-" + str(time.time_ns())[-8:]
    text_result = rpc({"action": "text", "text": marker})
    time.sleep(1)
    after = state()
    end = rpc({"action": "status"})
    power_states_after = display_states()
    keyguard = adb("shell", "dumpsys", "window", "policy")
    keyguard_lines = keyguard.partition("KeyguardServiceDelegate")[2].partition("Looper state:")[0]
    passed = (power_states_before.get(0) == "OFF" and power_states_after.get(0) == "OFF"
              and power_states_after.get(display) == "ON"
              and end["frames"] > first["frames"] and end["frameAgeMs"] < 3000
              and after is not None and after["counter"] == before["counter"] + 1
              and after["text"] == marker and after["text"] != before["text"] and text_result.get("ok"))
    result = {"strategy": strategy, "seconds": args.seconds, "passed": bool(passed), "marker": marker,
              "before": before, "after": after, "first": first, "end": end,
              "displayStatesBefore": power_states_before, "displayStatesAfter": power_states_after,
              "keyguard": keyguard_lines.strip(), "events": events}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"strategy": strategy, "result": "PASS" if passed else "FAIL",
                      "main": power_states_after.get(0), "virtual": power_states_after.get(display),
                      "freshFrames": end["frames"] > first["frames"],
                      "counterVerified": after is not None and after["counter"] == before["counter"] + 1,
                      "chineseVerified": after is not None and after["text"] == marker and after["text"] != before["text"],
                      "deviceSecure": end["deviceSecure"]}, ensure_ascii=False, separators=(",", ":")))
finally:
    adb("shell", "cmd", "display", "power-reset", "0")
    adb("shell", "input", "-d", "0", "keyevent", "224")
raise SystemExit(0 if passed else 1)
