#!/usr/bin/env python3
"""Check a prepared rest rule on a disposable emulator; the personal handset is never an input.

The fixture must have the service on, a current immediate-return rest rule for --target and
a stock Pixel launcher. Before 0.17, check dismissal of AppDodge’s own card. Since 0.17,
verify the manager is absent from the actual overview and never swipe any card. --force-stop distinguishes
removing the task from the explicit OS stop and deliberately leaves the test app stopped.
"""
import argparse
import re
import time
from pathlib import Path
from navigation_ui import Screen, PKG, until

def run(v, component, force_stop):
    pkg = component.split("/")[0]
    service = PKG+"/.NativeRestService"
    home = v.shell("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.HOME").splitlines()[-1].split("/")[0]
    active=lambda p: " "+p+"/" in v.front()
    dump=lambda: v.shell("dumpsys","activity","service",service)
    def recents():
        return any(re.search(r"\* Recent #\d+: Task\{.*:"+re.escape(PKG)+r"\}",l)
                   for l in v.shell("dumpsys","activity","recents").splitlines())
    def check(context):
        d=dump(); assert "AppDodge: ready=true" in d and "paused=false" in d, d
        before=int(re.search(r"observedHome=(\d+)",d)[1])
        v.shell("am","start","-n",component)
        until(lambda:active(home) and int(re.search(r"observedHome=(\d+)",dump())[1])>before,context+" did not return")
        d=dump()
        assert "lastReturned="+pkg in d and "receiptOutcome=confirmed" in d,d
        print(context, "ready=true",re.search(r"returnRequests=.*?lastReturnAgeMs=",d)[0].split(" lastReturnAgeMs=")[0],flush=True)
    v.start();v.shell("input","keyevent","KEYCODE_HOME");until(lambda:active(home),"HOME");check("HOME")
    v.start();v.back();until(lambda:active(home),"back from manager");check("BACK")
    version=int(re.search(r"versionCode=(\d+)",v.shell("dumpsys","package",PKG))[1])
    if version>=23: # 0.17 / manager categorically excluded from Recents
        from background_journal_ui import recents_check
        from shared_basket_ui import Form
        recents_check(Form(v.adb[-1],v.out))
        check("RECENTS CARD EXCLUDED")
    else:
        v.start();until(lambda:active(PKG) and recents(),"manager card")
        v.shell("input","keyevent","KEYCODE_HOME");time.sleep(.4)
        v.shell("input","keyevent","KEYCODE_APP_SWITCH")
        until(lambda:any(n.get("id","").endswith("action_screenshot") for n in v.nodes(home)), "recents transition")
        size=re.findall(r'(\d+)x(\d+)',v.shell("wm","size"))[-1]; w,h=map(int,size)
        centered=any(n.get("desc")=="反人性软件" and v.bounds(n)[0]<w//2<v.bounds(n)[2] for n in v.nodes(home))
        assert centered,"refusing to swipe a different application's card"
        v.shell("input","swipe",w//2,int(h*.80),w//2,5,390)
        until(lambda:not recents(),"dismissed manager card is still present")
        v.shell("input","keyevent","KEYCODE_HOME");until(lambda:active(home),"home after card dismissal")
        check("RECENTS CARD DISMISSED")
    if force_stop:
        v.shell("am","force-stop",PKG)
        until(lambda:"ready=true" not in dump(),"force-stop did not stop service")
        v.shell("am","start","-n",component);until(lambda:active(pkg),"force-stopped app should not intercept")
        time.sleep(1.5);assert active(pkg) and "ready=true" not in dump()
        print("EXPLICIT FORCE-STOP: no bound AppDodge service; target remains foreground",flush=True)

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("serial");p.add_argument("--disposable",required=True,action="store_true")
    p.add_argument("--target",default="com.android.bbkcalculator/.Calculator");p.add_argument("--shots",type=Path,required=True)
    p.add_argument("--force-stop",action="store_true"); args=p.parse_args()
    run(Screen(args.serial,args.shots),args.target,args.force_stop)
