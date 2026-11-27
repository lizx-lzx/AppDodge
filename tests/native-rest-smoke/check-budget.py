#!/usr/bin/env python3
"""Observe a short random-return rule on an owned emulator (optional companion: media mute).

Start with a fresh attempt (e.g. after five full minutes away), an enabled 100% rule in its effective period, a different unprotected comparison app,
and an unlocked emulator with no credential. This script never creates rules or grants services.
"""
import argparse
import json
import re
import subprocess
import time

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("serial")
parser.add_argument("target",help="test package/activity configured for a short random return")
parser.add_argument("other",help="unprotected comparison package/activity")
parser.add_argument("--mute",action="store_true",help="assert real shared media mute and restoration for a timer+mute rule")
parser.add_argument("--expiry",action="store_true",help="wait a real five minutes after exhaustion and check for a fresh draw")
args=parser.parse_args()
def require(ok,message):
    if not ok: raise AssertionError(message)
require(re.fullmatch(r"emulator-\d+",args.serial),"Specify an emulator-NNNN serial, not a phone")
for app in [args.target,args.other]:
    require(re.fullmatch(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+",app),"Invalid package/activity")
target=args.target.split('/')[0]
other=args.other.split('/')[0]
require(target!=other,"Comparison app must be different")
def shell(*cmd):
    return subprocess.check_output(["adb","-s",args.serial,"shell",*cmd],text=True).strip()
require(shell("getprop","ro.kernel.qemu")=="1","Emulators only")
def fields():
    d=shell("dumpsys","activity","service","dev.local.appdodge/.NativeRestService")
    return dict(re.findall(r"\b(\w+)=([^\s,]+)",d))
def count(key):
    return int(fields()[key])
def focused():
    return next((s.split(" u0 ",1)[1].split("/",1)[0] for s in shell("dumpsys","activity","activities").splitlines() if "topResumedActivity=" in s and " u0 " in s),"")
def music():
    s=shell("dumpsys","audio").split("- STREAM_MUSIC:",1)[1].split("\n- STREAM_",1)[0]
    return (re.search(r"\bMuted: (true|false)",s)[1]=="true",int(re.search(r"streamVolume:(\d+)",s)[1]))
def start(component): shell("am","start","-n",component)
def wait(condition,label,timeout=8):
    until=time.monotonic()+timeout
    while time.monotonic()<until:
        val=condition()
        if val:return val
        time.sleep(.12)
    raise AssertionError((label,focused(),fields()))
def record(label):
    f=fields()
    keys="delayDraws sampledSeconds lastSampledSeconds delayRemainingMs parkedRemainingMs delayResumes exhaustedResumes rejoinedExhausted lastDelayTarget observedHome returnRequests methodDraws methodMask effectActiveMask effectMediaLease".split()
    print(json.dumps({"point":label,"time":round(time.monotonic(),2),"foreground":focused(),"media":music(),
                      **{k:f[k] for k in keys if k in f}},ensure_ascii=False),flush=True)
    return f
def active():return fields().get("delayTarget")==target
def wake():shell("input","keyevent","KEYCODE_WAKEUP");shell("wm","dismiss-keyguard")
home=shell("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.HOME").splitlines()[-1].split("/",1)[0]
def budget_state(before,draws,label):
    wait(lambda:active() and focused()==target,label)
    if args.mute:wait(lambda:music()[0],"mute restored on target")
    f=record(label)
    require(int(f["delayDraws"])==draws,"Re-entry re-drew the random exit on "+label)
    left=int(f["delayRemainingMs"])
    # Actual shell/OS window transitions can consume some foreground time on the way out and in.
    require(before-2100<=left<=before+500,("Foreground budget changed while away",label,before,left))
    return left
start(args.other);wait(lambda:focused()==other,"baseline comparison")
original=music()
if args.mute:require(not original[0] and original[1]>0,"Start from audible media for audio evidence")
try:
    fresh=count("delayDraws")
    start(args.target)
    wait(lambda:count("delayDraws")>fresh,"initial timer draw")
    first=record("first entry")
    draws=int(first["delayDraws"])
    seconds=int(first["lastSampledSeconds"])
    # A legitimate very short draw may expire before a switch/sleep sequence can be observed.
    # Don't misreport it as a failure: run again on an independent attempt after five minutes.
    require(seconds>=10,("Sample too short for the switch/sleep sequence; use a fresh attempt",seconds))
    wait(lambda:active() and focused()==target,"target has active timer")
    if args.mute:wait(lambda:music()[0],"target is actually muted")
    time.sleep(1.2); pre=count("delayRemainingMs");start(args.other)
    wait(lambda:focused()==other and not active(),"left target")
    if args.mute:wait(lambda:music()==original,"comparison app is audible")
    time.sleep(3.5);record("other app; no active timer")
    start(args.target);budget_state(pre,draws,"first return")
    time.sleep(.7);pre=count("delayRemainingMs");shell("input","keyevent","KEYCODE_SLEEP")
    wait(lambda:fields().get("interactive")=="false","screen asleep")
    if args.mute:wait(lambda:music()==original,"sleep released audio")
    time.sleep(3.5);record("screen asleep")
    wake()
    if focused()!=target:start(args.target)
    left=budget_state(pre,draws,"return after screen off")
    before_home=count("observedHome")
    wait(lambda:focused()==home and count("observedHome")>before_home,"actual timed return to original launcher",left/1000+7)
    if args.mute:wait(lambda:music()==original,"home restored media")
    record("exhausted")
    # Even if the OS reports the target briefly in front during launch, no new time is granted.
    h=count("observedHome");start(args.target)
    wait(lambda:focused()==home and count("observedHome")>h,"immediate return on exhausted reopen",6)
    f=record("reopened exhausted")
    require(int(f["delayDraws"])==draws,"Exhausted return minted more time")
    if args.mute:require(music()==original,"media remained muted on HOME")
    if args.expiry:
        start(args.other);wait(lambda:focused()==other,"compare through cooldown")
        time.sleep(301.0)
        prior=count("delayDraws")
        start(args.target)
        def drew_for_target():
            f=fields()
            return int(f["delayDraws"])==prior+1 and f.get("lastDelayTarget")==target
        wait(drew_for_target,"five minutes away permits one fresh draw for this target")
        record("new episode after five minutes")
    print("PASS: foreground-only budget, switch and sleep, exhausted reopen"+(", full expiry" if args.expiry else ""),flush=True)
finally:
    wake();start(args.other)
    if args.mute:wait(lambda:music()==original,"restore baseline before ending")
