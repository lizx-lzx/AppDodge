#!/usr/bin/env python3
"""Original-icon gate and HOME receipts; only for the project's disposable Pixel QA AVD."""
import argparse
import re
import subprocess
import time
from pathlib import Path
from selected_policy_ui import SELECTED, PHOTOS, get_icon, opened
from shared_basket_ui import Form, blocked, state, interval
from navigation_ui import PKG, until

TARGET=PHOTOS+"/.home.HomeActivity"
HOME="com.google.android.apps.nexuslauncher"
SETTINGS="com.android.settings"
GATE="延迟打开后退出"
NOW="打开后退回桌面"


def save(v):
    v.tap("保存规则")
    if any(n.get("text")=="确认保存" for n in v.nodes()):v.tap("确认保存")
    v.target("Photos")


def set_basket(v,label):
    v.tap("负反馈");v.tap("全选");v.tap("取消全选");v.act(label)
    v.switch("轻振两下",False);v.switch("倒数 5 秒",False);v.act("完成选择")


def seed(v):
    assert "versionName=0.18.0" in v.shell("dumpsys","package",PKG)
    v.fresh();v.tap("＋ 添加第一个应用");v.tap("按应用名或包名搜索",prop="desc");v.shell("input","text","Photos")
    v.touch(next(n for n in v.nodes() if n.get("text")=="Photos" and not n.get("desc")))
    v.tap("休息时段");assert SELECTED in v.text()
    set_basket(v,"间歇卡顿");save(v);v.touch(home_icon(v))
    until(lambda:opened(v,PHOTOS) and state(v)["effectTouch"]=="true","0.18 selected rest did not open and stall the enrolled app",14)
    v.shell("input","keyevent","KEYCODE_HOME")
    print("BASELINE: 0.18 soft rest policy, original Photos icon really opens and stalls touch",flush=True)


def daily(v,label,prepare=False):
    v.start();v.tap("Photos")
    if prepare:
        v.tap("休息时段");hour=int(v.shell("date","+%H"));interval(v,0,(hour+3)%24,0,(hour+4)%24,0)
    v.tap("其余时间")
    if prepare:v.slider(100)
    set_basket(v,label);save(v)


def home_icon(v):
    icon=get_icon(v)
    until(lambda:int(state(v)["overlays"])>=1,"no original-icon interceptor")
    return icon


def gate(v,icon):
    s=state(v); draws=int(s["methodDraws"]); visits=int(s["delayDraws"])
    v.touch(icon)
    until(lambda:state(v)["iconTarget"]==PHOTOS,"first tap did not arm the gate")
    remaining=int(state(v)["iconDueInMs"])
    assert 8000<=remaining<=10000 and opened(v,HOME),(remaining,v.front())
    v.touch(icon);assert opened(v,HOME) and state(v)["iconTarget"]==PHOTOS
    assert int(state(v)["delayDraws"])==visits, "should not count desktop waiting"
    until(lambda:state(v)["iconDueInMs"]=="0" and state(v)["iconTarget"]==PHOTOS,
          "10-second gate did not become ready",12)
    assert opened(v,HOME)
    assert int(state(v)["methodDraws"])==draws+1
    v.touch(icon)
    until(lambda:opened(v,PHOTOS),"after waiting and confirming, actual Photos must open",4)


def verify(v):
    v.start();v.target("Photos")  # in-place install: the original active rule still exists
    v.tap("Photos");v.tap("休息时段");v.shot("01-休息段");v.back()
    icon=home_icon(v);before=state(v);v.touch(icon)
    until(lambda:int(state(v)["methodDraws"])>int(before["methodDraws"]),"no new original-icon action")
    assert opened(v,HOME) and state(v)["effectTouch"]=="false" and state(v)["lastDelayTarget"]=="null"
    blocked(v,TARGET);print("PASS: upgrade preserved old selected basket; old permissive rest now blocks original icon and actual external foreground",flush=True)
    daily(v,GATE,prepare=True)
    v.tap("Photos");v.tap("其余时间");v.shot("02-其余时间-延迟打开后退出");v.back()
    icon=home_icon(v)
    # Fresh visit: time the app really spends foreground; no service deadline during wait.
    before=state(v);gate(v,icon)
    until(lambda:state(v)["delayTarget"]==PHOTOS,"timer did not attach to confirmed target",3)
    active=state(v); sampled=int(active["sampledSeconds"]);assert sampled in (1,2),active
    left=int(active["delayRemainingMs"]);assert 0<left<=sampled*1000,active
    begun=time.monotonic()
    until(lambda:int(state(v)["returnRequests"])==int(before["returnRequests"])+1,
          "no HOME request after gated entry",4)
    elapsed=time.monotonic()-begun
    # Measure budget expiry separately from the system's HOME animation/receipt.
    assert left/1000-.35<=elapsed<left/1000+1,(left,elapsed)
    until(lambda:int(state(v)["observedHome"])==int(before["observedHome"])+1 and opened(v,HOME),
          "requested HOME was not actually observed",4)
    after=state(v);assert after["lastReturned"]==PHOTOS and int(after["delayDraws"])==int(before["delayDraws"])+1,after
    print(f"PASS original Photos icon: 10-second gate rejects early retap; target actually opened; "
          f"foreground budget {sampled}s; at {left} ms remaining HOME was requested {elapsed:.2f}s later and confirmed",flush=True)

    # HOME leaves Photos in Recents. Select its card, not the desktop icon; no fresh allowance.
    before=state(v)
    v.shell("input","keyevent","KEYCODE_APP_SWITCH");time.sleep(.65)
    w,h=map(int,v.shell("wm","size").split()[-1].split("x"))
    v.shell("input","tap",w//2,h//2)
    until(lambda:int(state(v)["observedHome"])>int(before["observedHome"]) and opened(v,HOME),
          "exhausted Photos card did not return to HOME")
    end=state(v)
    assert end["lastReturned"]==PHOTOS and int(end["exhaustedResumes"])>int(before["exhaustedResumes"]) and end["delayDraws"]==before["delayDraws"],end
    print("PASS selecting the actual Photos card in Recents resumes the exhausted budget; HOME confirmed, no new duration",flush=True)

    # New service session confines this assertion to a new budget, not the exhausted one above.
    v.service(False);v.service(True);icon=home_icon(v);before=state(v)
    gate(v,icon)
    until(lambda:state(v)["delayTarget"]==PHOTOS,"target budget not yet active",3)
    v.shell("am","start","-W","-a","android.settings.SETTINGS")
    until(lambda:opened(v,SETTINGS),"Settings not in front")
    time.sleep(2.4);assert opened(v,SETTINGS) and state(v)["returnRequests"]==before["returnRequests"]
    print("PASS leaving the gated target for Settings before expiry: Settings remains in front beyond deadline",flush=True)

    daily(v,NOW)
    icon=home_icon(v);before=state(v);v.touch(icon)
    until(lambda:int(state(v)["observedHome"])>int(before["observedHome"]) and opened(v,HOME),"immediate entry only showed desktop receipt")
    result=state(v);assert result["lastReturned"]==PHOTOS and result["receiptOutcome"]=="confirmed",result
    print("PASS original Photos icon + open then return: actual target-window HOME request and launcher confirmation",flush=True)

    daily(v,"强行消音")
    icon=home_icon(v);before=state(v);v.touch(icon)
    until(lambda:opened(v,PHOTOS) and state(v)["effectMediaLease"]=="true","selected mute not actually active",5)
    until(lambda:int(state(v)["observedHome"])>int(before["observedHome"]) and opened(v,HOME),"content-only hit left the target open",12)
    assert state(v)["effectMediaLease"]=="false"
    print("PASS: 100%, mute only: target really opened, music muted, then HOME confirmed and mute released",flush=True)
    v.start();v.tap("Photos");v.tap("其余时间");v.slider(0);save(v)
    icon=get_icon(v);before=state(v);v.touch(icon);until(lambda:opened(v,PHOTOS),"daily 0% expected normal open")
    time.sleep(3)
    end=state(v);assert opened(v,PHOTOS) and end["returnRequests"]==before["returnRequests"] and end["effectMediaLease"]=="false"
    print("PASS: same shared mute basket at 0%: target stays open, no mute or HOME request",flush=True)
    v.shell("input","keyevent","KEYCODE_HOME")


def cover(v):
    """Small unrelated non-focused overlay must not leave the enrolled app open."""
    v.start();v.tap("Photos");v.tap("其余时间")
    if "100%" not in v.text():v.slider(100);save(v)
    else:v.back()
    fixture="dev.local.budgetoverlay"
    assert v.shell("pm","path",fixture).startswith("package:")
    permission="SYSTEM_ALERT_WINDOW"
    m=re.search(permission+r": (allow|default|deny|ignore|foreground)",v.shell("appops","get",fixture,permission))
    previous=m.group(1) if m else "default"
    v.shell("appops","set",fixture,permission,"allow")
    try:
        v.shell("input","keyevent","KEYCODE_HOME")
        v.shell("am","start","-W","-n",fixture+"/.Open")
        until(lambda:"QA nonfocusable floatmark" in v.shell("dumpsys","window","windows"),"floating test window absent")
        proper=lambda s:"type=TYPE_SYSTEM" in s and "bounds=Rect(940, 416 - 1050, 476)" in s and "focused=false" in s and "active=false" in s
        until(lambda:any(proper(s) for s in v.shell("dumpsys","accessibility").splitlines()),"system has not yet reported the external non-focusing mark")
        print("test fixture:",next(s.strip() for s in v.shell("dumpsys","accessibility").splitlines() if proper(s)),flush=True)
        before=state(v);v.shell("am","start","-n",TARGET)
        until(lambda:int(state(v)["observedHome"])>int(before["observedHome"]) and opened(v,HOME),"small overlay left target open",5)
        after=state(v);assert after["lastReturned"]==PHOTOS and after["effectMediaLease"]=="false",after
        print("PASS 110×60 non-focusing system-class overlay: selected app actually entered and returned, no stuck timer or media lease",flush=True)
        v.shell("am","start","-W","-a","android.settings.SETTINGS");time.sleep(2.3)
        assert opened(v,SETTINGS) and state(v)["returnRequests"]==after["returnRequests"]
    finally:
        v.shell("am","force-stop",fixture)
        v.shell("appops","set",fixture,permission,previous)
    v.start();v.tap("Photos");v.tap("休息时段");h=int(v.shell("date","+%H"))
    interval(v,0,h,0,(h+1)%24,0);save(v)
    icon=home_icon(v);v.touch(icon);v.shot("03-硬拦图标内回执")
    assert opened(v,HOME) and state(v)["effectMediaLease"]=="false"
    print("PASS: hard rest with mute-only basket, icon stayed home; see 03 for actual in-icon acknowledgement",flush=True)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("serial");p.add_argument("--disposable",required=True,action="store_true")
    p.add_argument("--evidence",type=Path,required=True);p.add_argument("phase",choices=["seed","verify","cover"]);a=p.parse_args()
    v=Form(a.serial,a.evidence)
    assert subprocess.check_output([*v.adb,"emu","avd","name"],text=True).splitlines()[0]=="AppDodge_Daily_QA"
    {"seed":seed,"verify":verify,"cover":cover}[a.phase](v)
