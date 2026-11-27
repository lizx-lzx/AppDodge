#!/usr/bin/env python3
"""Exercise AppDodge's actual force-stop on the disposable QA AVD, never via adb force-stop.

Use a daily 100% Photos rule with narrowing only and an installed, running Shizuku manager.
Run 'before-grant' on an in-place upgrade, grant from AppDodge's UI, then 'after-grant'.
"""
import argparse
import re
import subprocess
import time
from pathlib import Path
from foreground_recheck_ui import set_period
from gated_exit_ui import Form, HOME, PHOTOS, TARGET, get_icon, home_icon, opened, save, state, until

SWITCH="拦截后停止后台运行"

def target_status(v):
    flags=next(line for line in v.shell("dumpsys","package",PHOTOS).splitlines()
               if re.match(r"^\s+User 0:.*\bstopped=(true|false)\b",line))
    ps=v.shell("ps","-A","-o","PID,NAME")
    pids=[r.split()[0] for r in ps.splitlines() if r.split() and
          (r.split()[-1]==PHOTOS or r.split()[-1].startswith(PHOTOS+":"))]
    return pids, bool(re.search(r"\bstopped=true\b",flags))

def entered(v,icon):
    old=state(v);v.touch(icon)
    until(lambda:opened(v,PHOTOS),"Photos did not really enter",5)
    running,stopped=target_status(v)
    assert running and not stopped,(running,stopped)
    return old,running

def returned(v,old):
    until(lambda:int(state(v)["observedHome"])>int(old["observedHome"]) and opened(v,HOME),
          "AppDodge never returned the real target to the launcher",15)
    s=state(v)
    assert s["lastReturned"]==PHOTOS and s["receiptOutcome"]=="confirmed",s

def before_grant(v):
    until(lambda:state(v).get("ready")=="true","enable the QA accessibility service after instrumentation")
    v.start();v.tap("Photos"); assert not v.checked(SWITCH)
    v.switch(SWITCH,True);save(v)
    old,pids=entered(v,home_icon(v));returned(v,old)
    until(lambda:state(v)["lastBackgroundStop"]=="unavailable","missing authorization did not get reported")
    live,stopped=target_status(v);assert set(pids)&set(live) and not stopped,(pids,live,stopped)
    print("PASS: default off on upgrade; opt-in without authorization still returned HOME, same process alive",pids,flush=True)
    v.start();v.tap("Photos");v.act("选择停止后台的方式 ›");v.tap("使用已安装的 Shizuku");v.tap("请求授权")

def after_grant(v):
    v.start();v.tap("Photos");assert v.checked(SWITCH);v.shot("已开启-仅此应用");v.back()
    icon=home_icon(v);old,pids=entered(v,icon)
    v.shell("input","keyevent","KEYCODE_HOME");until(lambda:opened(v,HOME),"manual exit failed")
    time.sleep(5)
    s=state(v);live,stopped=target_status(v)
    assert set(pids)&set(live) and not stopped and s["backgroundStopRequests"]==old["backgroundStopRequests"],(s,live)
    print("PASS: user pressed Home while timer active; same process alive",live,flush=True)
    old,pids=entered(v,icon);returned(v,old)
    until(lambda:int(state(v)["backgroundStopConfirmed"])==int(old["backgroundStopConfirmed"])+1,
          "HOME confirmed but no force-stop confirmation; "+str(state(v).get("lastBackgroundStop")),6)
    live,stopped=target_status(v);assert not live and stopped,(live,stopped)
    print("PASS: actually opened; AppDodge returned HOME and stopped it; PIDs before",pids,"after",live,"stopped=true",flush=True)
    v.start();v.tap("Photos");v.tap("其余时间");v.slider(0);save(v)
    old,pids=entered(v,get_icon(v));time.sleep(5)
    live,stopped=target_status(v)
    assert opened(v,PHOTOS) and set(pids)&set(live) and not stopped and state(v)["backgroundStopRequests"]==old["backgroundStopRequests"]
    print("PASS: same rule, probability zero, explicitly reopens and stays usable",live,flush=True)
    v.shell("input","keyevent","KEYCODE_HOME")

def strict(v):
    # Start with a warm process from the preceding probability-zero check.
    set_period(v,True);icon=home_icon(v);pids,stopped=target_status(v)
    assert pids and not stopped
    old=state(v);v.touch(icon)
    until(lambda:int(state(v)["methodDraws"])>int(old["methodDraws"]),"no desktop interception")
    time.sleep(1);new=state(v);live,stopped=target_status(v)
    assert opened(v,HOME) and set(live)&set(pids) and not stopped and new["backgroundStopRequests"]==old["backgroundStopRequests"]
    print("PASS: strict desktop-only interception retained the warm target",live,flush=True)
    old=state(v);v.shell("am","start","--user","0","-n",TARGET);returned(v,old)
    until(lambda:int(state(v)["backgroundStopConfirmed"])>int(old["backgroundStopConfirmed"]),"no force-stop after a strict foreground return",5)
    live,stopped=target_status(v);assert not live and stopped
    print("PASS: genuine strict foreground entry returned and stopped",flush=True)

if __name__=="__main__":
    a=argparse.ArgumentParser(description=__doc__);a.add_argument("serial");a.add_argument("phase",choices=["before-grant","after-grant","strict"])
    a.add_argument("--evidence",required=True,type=Path);args=a.parse_args();v=Form(args.serial,args.evidence)
    assert subprocess.check_output([*v.adb,"emu","avd","name"],text=True).splitlines()[0]=="AppDodge_Daily_QA"
    {"before-grant":before_grant,"after-grant":after_grant,"strict":strict}[args.phase](v)
