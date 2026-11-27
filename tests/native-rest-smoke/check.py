#!/usr/bin/env python3
"""Exercise an already configured stock launcher; refuse real devices."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("serial", help="owned emulator-NNNN")
p.add_argument("phase", choices=("blocked", "allowed"))
p.add_argument("target", help="protected app package")
p.add_argument("x", type=int, help="center of its stock launcher icon")
p.add_argument("y", type=int)
p.add_argument("--record", type=Path, help="path for original emulator screen recording (.webm)")
p.add_argument("--shots", type=Path, help="save original screenshots for visual checks")
args = p.parse_args()
if not re.fullmatch(r"emulator-\d+", args.serial):
    p.error("Use a disposable emulator, never a personal phone.")
adb = os.environ.get("ADB") or shutil.which("adb") or str(Path.home()/"Library/Android/sdk/platform-tools/adb")
def call(*cmd, binary=False):
    return subprocess.check_output([adb, "-s", args.serial, *map(str, cmd)], text=not binary)
def sh(*cmd):
    return call("shell", *cmd).strip()
assert sh("getprop", "ro.kernel.qemu") == "1", "Target is not an emulator"
home = sh("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").splitlines()[-1].split("/")[0]
assert home != "dev.local.appdodge" and "." in home, f"Use the original Home ({home})"
assert sh("locksettings", "get-disabled") == "false", "Enable swipe keyguard on the disposable AVD for a real lock test."
start = time.monotonic()
stamp = sh("date", "'+%m-%d %H:%M:%S.000'")
def info(s):
    print(f"{time.monotonic() - start:5.2f}s {s}", flush=True)
def tap():
    sh("input", "tap", args.x, args.y)
def resumed():
    return next((l.strip() for l in sh("dumpsys", "activity", "activities").splitlines() if "topResumedActivity=" in l), "")
def state():
    power = sh("dumpsys", "power")
    display = sh("dumpsys", "display")
    keyguard = sh("dumpsys", "window", "policy")
    def read(pattern, source):
        match = re.search(pattern, source, re.M)
        return match[1] if match else "transition"
    return (read(r"mWakefulness=(\w+)", power), read(r"mScreenState=(\w+)", display),
            read(r"^\s*showing=(true|false)", keyguard))
def check(expr, label):
    if not expr:
        raise RuntimeError(label + f"; actual={state()}; {resumed()}")
    info(label)
def shot(name):
    if args.shots:
        args.shots.mkdir(parents=True,exist_ok=True)
        (args.shots/(name+".png")).write_bytes(call("exec-out","screencap","-p",binary=True))
def on_home():
    return f" {home}/" in resumed()
check(on_home(), f"stock HOME={home}, target={args.target}, tap=({args.x},{args.y})")
if args.record:
    if args.record.suffix != ".webm" or args.record.exists():
        p.error("--record must be a new .webm file")
    call("emu","screenrecord","start","--fps","24","--bit-rate","3M",args.record.resolve())
try:
    if args.phase == "allowed":
        tap()
        time.sleep(.9)
        check(f" {args.target}/" in resumed(), "regular tap entered target")
        shot("allowed")
    else:
        time.sleep(.5)
        # Send the pair in one shell, so the interval is bounded on the device.
        sh(f"input tap {args.x} {args.y}; input tap {args.x} {args.y}")
        time.sleep(.55)
        check(state()[:2] == ("Awake","ON") and on_home(), "first tap + fast repeat: still awake on original HOME")
        shot("first-warning")
        time.sleep(.85)
        tap()
        for _ in range(12):
            current = state()
            if current == ("Asleep","OFF","true"): break
            time.sleep(.12)
        check(current == ("Asleep","OFF","true"), f"deliberate repeat: power, display, keyguard = {current}")
        check("mLastSleepReason=accessibility" in sh("dumpsys","power"), "system attributes sleep to accessibility")
        shot("screen-off")
        time.sleep(.5)
        sh("input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(.55)
        info(f"wake (test operator): {state()}")
        shot("awake-keyguard")
        sh("wm", "dismiss-keyguard")  # non-secure emulator swipe keyguard
        time.sleep(.9)
        check(state() == ("Awake","ON","false") and on_home(), "after unlock: awake on HOME, no automatic relock")
        shot("after-unlock")
        tap()
        time.sleep(.7)
        check(state()[:2] == ("Awake","ON") and on_home(), "first tap in new session: still awake on HOME")
        shot("new-session-warning")
        # Activity starts are observable even if a blocking UI would immediately cover them.
        logs = call("logcat","-d","-T",stamp,"-s","ActivityTaskManager:I","DODGELAB:I","AppDodgeGuard:I")
        markers = re.findall(r"AppDodgeGuard:\s+(first_tap|duplicate_tap|lock_requested accepted=true|screen_off session_reset)",logs)
        check(markers == ["first_tap","duplicate_tap","lock_requested accepted=true","screen_off session_reset","first_tap"],
              f"ordered service events: {markers}")
        starts = [l for l in logs.splitlines() if ("START u" in l and f"cmp={args.target}/" in l) or (args.target=="dev.local.dodgelab" and "DOT_FOREGROUND" in l)]
        check(not starts, f"no target activity start/resume during blocked phase ({len(starts)} events)")
finally:
    if args.record:
        call("emu","screenrecord","stop")
