#!/usr/bin/env python3
"""Observe a 100% narrowing+mute rule on an owned emulator. Configure its allowed period first."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("serial", help="owned emulator-NNNN")
p.add_argument("target", help="target package/activity, with the two daily effects already selected")
p.add_argument("other", help="unprotected package/activity, e.g. a clock")
p.add_argument("--shots", type=Path, help="directory to store actual screenshots and JSON observations")
args = p.parse_args()
if not re.fullmatch(r"emulator-\d+", args.serial):
    p.error("Use a dedicated emulator, never a real phone.")
for component in [args.target, args.other]:
    if not re.fullmatch(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+", component):
        p.error("Expected a package/activity component")
if args.target.split('/')[0] == args.other.split('/')[0]:
    p.error("Two different apps are required.")
adb = ["adb", "-s", args.serial]
def shell(*cmd):
    return subprocess.check_output([*adb, "shell", *cmd], text=True).strip()
if shell("getprop", "ro.kernel.qemu") != "1":
    raise SystemExit("This test never operates a non-emulator.")

def effect():
    raw = shell("dumpsys", "activity", "service", "dev.local.appdodge/.NativeRestService")
    return dict(re.findall(r"\b([A-Za-z]\w*)=([^\s,]+)", raw))
def audio(stream):
    raw = shell("dumpsys", "audio").split("- STREAM_"+stream+":")[1].split("\n- STREAM_")[0]
    return {"muted":re.search(r"\bMuted: (true|false)", raw)[1]=="true",
            "volume":int(re.search(r"streamVolume:(\d+)", raw)[1])}
def front():
    return next((l.strip() for l in shell("dumpsys","activity","activities").splitlines() if "topResumedActivity" in l), "")
def until(ok, label, timeout=12):
    end = time.monotonic()+timeout
    while time.monotonic()<end:
        if ok(): return
        time.sleep(.15)
    raise AssertionError((label,effect(),front()))
def open_app(component):
    shell("am", "start", "-n", component)
    until(lambda: (" "+component.split('/')[0]+"/") in front(), "requested app foreground")
evidence = []
if args.shots: args.shots.mkdir(exist_ok=True, parents=True)
def observe(label):
    eff = effect()
    obs = {"state":label,"top":front(),"media":audio("MUSIC"),"ring":audio("RING"),
           **{k:eff.get(k) for k in ("effectMask","effectActiveMask","effectNarrowPct","effectInk","effectTouch","effectMediaLease","methodDraws","chancePasses")}}
    evidence.append(obs)
    print(json.dumps(obs,ensure_ascii=False),flush=True)
    if args.shots:
        with (args.shots/(label+".png")).open('wb') as out:
            subprocess.run([*adb,"exec-out","screencap","-p"],stdout=out,check=True)
        (args.shots/"observed.json").write_text(json.dumps(evidence,indent=2,ensure_ascii=False)+"\n")
    return obs

open_app(args.other)
before=observe("before")
if before["media"]["muted"] or before["media"]["volume"]<=0:
    raise SystemExit("Use an audible emulator baseline before running the test.")
try:
    open_app(args.target)
    until(lambda: effect().get("effectInk")=="true" and audio("MUSIC")["muted"],"narrowing and media silence")
    until(lambda: int(effect().get("effectNarrowPct","0"))>=35,"narrow bars visibly closing",22)
    combined=observe("combined")
    assert combined["media"]["muted"] and combined["effectMediaLease"]=="true"
    assert combined["effectInk"]=="true" and combined["effectTouch"]=="false"
    assert combined["ring"]==before["ring"]
    draws=combined["methodDraws"]
    open_app(args.other)
    until(lambda: effect().get("effectInk")=="false" and audio("MUSIC")==before["media"],"both effects removed")
    after=observe("after")
    assert after["effectMediaLease"]=="false" and after["ring"]==before["ring"]
    open_app(args.target)
    until(lambda: effect().get("effectInk")=="true" and audio("MUSIC")["muted"],"retry restores both")
    again=observe("retry")
    assert again["methodDraws"]==draws and int(again["effectNarrowPct"])>=int(combined["effectNarrowPct"])
finally:
    open_app(args.other)
    until(lambda: effect().get("effectInk")=="false" and audio("MUSIC")==before["media"], "restore before finishing")
print("PASS: narrowing + shared media silence in one visit, both withdrawn, immediate re-entry without redraw")
