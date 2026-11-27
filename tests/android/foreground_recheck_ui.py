#!/usr/bin/env python3
"""Test HOME acknowledgement on the disposable AppDodge_Daily_QA AVD; not on physical devices.

Needs the saved Photos rule and original workspace icon used by gated_exit_ui.py. Android's
temporary screen pinning is a reproducible way to accept a HOME request without actually
changing applications. No assumption that the user's device was pinned is made here.
"""
import argparse
import re
import subprocess
import time
from pathlib import Path
from gated_exit_ui import Form, HOME, PHOTOS, SETTINGS, TARGET, get_icon, home_icon, interval, opened, save, state, until


def set_period(v, rest):
    v.start(); v.tap("Photos"); v.tap("休息时段")
    hour = int(v.shell("date", "+%H"))
    start,end=((hour-1)%24,(hour+2)%24) if rest else ((hour+3)%24,(hour+4)%24)
    existing=v.text().splitlines()
    changed=("%02d:00"%start not in existing or "%02d:00"%end not in existing)
    if changed:interval(v,0,start,0,end,0)
    if not rest:
        v.tap("其余时间")
        if "0%" not in v.text().splitlines():v.slider(0);changed=True
    if changed:save(v)
    else:v.back()


def pinned(v):
    assert opened(v,PHOTOS),v.front()
    task = re.search(r"\bt(\d+)[ }]",v.front())[1]
    v.shell("am","task","lock",task)
    until(lambda:"mLockTaskModeState=PINNED" in v.shell("dumpsys","activity","activities"),"system did not pin the target")


def release(v):
    v.shell("am","task","lock","stop")
    until(lambda:"mLockTaskModeState=NONE" in v.shell("dumpsys","activity","activities"),"pin was not released")


def rotate(v):
    v.shell("wm","user-rotation","lock","1")
    until(lambda:"mCurrentRotation=ROTATION_90" in v.shell("dumpsys","window","displays"),"display did not reach landscape")


def pin_case(v, baseline, switch=False):
    v.service(False)
    v.shell("am","start","-W","-n",TARGET); rotate(v); pinned(v)
    try:
        v.service(True)
        until(lambda:int(state(v)["returnRequests"])>=1,"did not request HOME for real landscape target")
        first=state(v)
        assert opened(v,PHOTOS) and first["accepted"]=="true" and first["observedHome"]=="0",first
        release(v)
        if switch:
            v.shell("am","start","-W","-a","android.settings.SETTINGS")
            until(lambda:opened(v,SETTINGS),"could not switch to unrelated settings")
            time.sleep(3); assert opened(v,SETTINGS),v.front()
            print("PASS early switch: Settings remains in front past pending return checks",flush=True)
        elif baseline:
            time.sleep(5)
            last=state(v)
            assert opened(v,PHOTOS) and last["returnRequests"]=="1" and last["observedHome"]=="0",last
            print("REPRODUCED 0.19: system accepted HOME; pin released; 5 seconds later landscape target still in foreground; requests=1, confirmations=0",flush=True)
        else:
            start=time.monotonic()
            until(lambda:int(state(v)["observedHome"])==1 and opened(v,HOME),"landscape target never returned after initial ineffective HOME",5)
            last=state(v)
            assert int(last["returnRequests"])>=2 and last["lastReturned"]==PHOTOS and last["receiptOutcome"]=="confirmed",last
            print(f"PASS landscape HOME: ineffective first request, {last['returnRequests']} requests, 1 confirmation, observed home {time.monotonic()-start:.2f}s after unpin",flush=True)
    finally:
        if "mLockTaskModeState=PINNED" in v.shell("dumpsys","activity","activities"):release(v)


def shade(v):
    """A target activity can be resumed while System UI, not the app, owns input."""
    v.service(False);v.shell("am","start","-W","-n",TARGET);rotate(v)
    v.shell("cmd","statusbar","expand-notifications")
    try:
        until(lambda:re.search(r'mCurrentFocus=Window\{[^\n]*NotificationShade',v.shell('dumpsys','window')),
              "the notification shade never acquired focus")
        v.service(True);time.sleep(.9);s=state(v)
        assert s["returnRequests"]=="0" and s["observedHome"]=="0",s
    finally:
        v.shell("cmd","statusbar","collapse")
    until(lambda:int(state(v)["observedHome"])==1 and opened(v,HOME),"settled landscape target failed to return after shade")
    print("PASS transition: did not request HOME over the focused notification shade; returned after the same landscape target regained its window",flush=True)


def verify(v):
    set_period(v,True)
    before=state(v);v.touch(home_icon(v))
    until(lambda:int(state(v)["methodDraws"])>int(before["methodDraws"]),"original icon was not intercepted")
    assert opened(v,HOME),v.front()
    print("PASS original icon: intercept executed and launcher remains foreground",flush=True)
    before=state(v);v.shell("am","start","-W","-n",TARGET)
    until(lambda:int(state(v)["observedHome"])>int(before["observedHome"]) and opened(v,HOME),"external target failed to return")
    before=state(v);v.shell("input","keyevent","KEYCODE_APP_SWITCH");time.sleep(.65)
    w,h=map(int,v.shell("wm","size").split()[-1].split("x"));v.shell("input","tap",w//2,h//2)
    until(lambda:int(state(v)["observedHome"])>int(before["observedHome"]) and opened(v,HOME),"return via actual Recents card bypassed rule")
    print("PASS external start and warm Recents card: each confirmed a real new HOME return",flush=True)
    pin_case(v,False)
    pin_case(v,False,switch=True)
    shade(v)
    v.shell("wm","user-rotation","free");v.shell("input","keyevent","KEYCODE_HOME")
    set_period(v,False)
    icon=get_icon(v);before=state(v);v.touch(icon)
    until(lambda:opened(v,PHOTOS),"daily 0% unexpectedly blocked target")
    time.sleep(2.5)
    assert opened(v,PHOTOS) and state(v)["returnRequests"]==before["returnRequests"]
    print("PASS 0%: target stays open with no HOME attempt",flush=True)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("serial");p.add_argument("--disposable",required=True,action="store_true")
    p.add_argument("--evidence",type=Path,required=True);p.add_argument("phase",choices=["baseline","verify"]);a=p.parse_args()
    v=Form(a.serial,a.evidence)
    assert subprocess.check_output([*v.adb,"emu","avd","name"],text=True).splitlines()[0]=="AppDodge_Daily_QA"
    previous=v.shell("wm","user-rotation").split()
    try:
        if a.phase=="baseline":
            assert "versionName=0.19.0" in v.shell("dumpsys","package","dev.local.appdodge")
            set_period(v,True);pin_case(v,True)
        else:verify(v)
    finally:
        v.shell("wm","user-rotation",*previous);v.shell("input","keyevent","KEYCODE_HOME")
