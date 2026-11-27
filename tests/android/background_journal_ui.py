#!/usr/bin/env python3
"""Upgrade an isolated Pixel fixture: no Recents card, live protection and a persistent guard journal."""
import argparse
import re
import time
from pathlib import Path
from navigation_ui import until, PKG
from shared_basket_ui import Form, CALC, blocked, state, mode, interval


def launch(v):
    """Use a normal app launcher intent (including MAIN/LAUNCHER and task flags)."""
    v.shell("am","start","-W","-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER",
            "-f","0x10200000","-n",PKG+"/.MainActivity")
    until(lambda:" "+PKG+"/" in v.front(),"app launcher entry")


def home_pkg(v):
    return v.shell("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN",
                   "-c","android.intent.category.HOME").splitlines()[-1].split("/")[0]


def seed(v):
    assert "versionName=0.16.0" in v.shell("dumpsys","package",PKG)
    v.fresh()
    v.tap("＋ 添加第一个应用");v.tap("计算器");v.tap("休息时段");v.tap("负反馈")
    v.switch("轻振两下",False);v.switch("休息提醒",True)
    v.tap("完成选择");mode(v,True);v.tap("保存规则");v.target("计算器")
    assert int(blocked(v)["compositeMask"])==5
    launch(v);v.tap("＋ 添加应用");v.tap("按应用名或包名搜索",prop="desc");v.shell("input","text","Photos")
    v.touch(next(n for n in v.nodes() if n.get("text")=="Photos" and not n.get("desc")))
    v.tap("保存规则");v.target("Photos");v.shell("input","keyevent","KEYCODE_HOME")
    print("PASS: 0.16 two actual rules, external calculator configured for countdown + reminder.",flush=True)


def overview_nodes(v):
    return v.nodes(home_pkg(v))


def enter_log(v):
    launch(v)
    if "设置" in v.text(): v.tap("设置")
    if "运行日志" in v.text(): v.tap("运行日志")
    return v.text()


def manager_icon(v):
    v.shell("input","keyevent","KEYCODE_HOME")
    size=re.findall(r"(\d+)x(\d+)",v.shell("wm","size"))[-1]
    w,h=map(int,size)
    v.shell("input","swipe",w//2,int(h*.84),w//2,int(h*.20),350);time.sleep(.4)
    for _ in range(6):
        icons=[n for n in overview_nodes(v) if (n.get("desc") or n.get("text"))=="反人性软件"]
        if icons:
            v.touch(icons[0]);until(lambda:" "+PKG+"/" in v.front(),"real app-drawer icon did not open manager");return
        v.shell("input","swipe",w//2,int(h*.78),w//2,int(h*.24),350);time.sleep(.25)
    raise AssertionError("AppDodge launcher entry missing")


def recents_check(v):
    clock="com.google.android.deskclock/com.android.deskclock.DeskClock"
    v.shell("am","start","-W","-n",clock)
    manager_icon(v)
    # Home from the manager, then use the actual overview; internal dumpsys raw tasks include excluded tasks.
    v.shell("input","keyevent","KEYCODE_HOME");time.sleep(.5)
    v.shell("input","keyevent","KEYCODE_APP_SWITCH")
    until(lambda:any(n.get("id","").endswith("action_screenshot") for n in overview_nodes(v)),"Pixel Recents not visible")
    nodes=overview_nodes(v)
    w=int(re.findall(r"(\d+)x(\d+)",v.shell("wm","size"))[-1][0])
    center=[n.get("desc") for n in nodes if n.get("desc") and v.bounds(n)[0] < w//2 < v.bounds(n)[2]]
    assert "Clock" in center and not any(n.get("desc")=="反人性软件" for n in nodes),center
    v.shot("017-最近任务没有管理卡片")
    v.shell("input","keyevent","KEYCODE_HOME")
    print("PASS: most recent manager is excluded from the real overview; Clock is still present.",flush=True)


def direct_overview(v):
    """Direct app-to-overview can include the resumed top task even when normal Recents excludes it."""
    manager_icon(v);v.shell("input","keyevent","KEYCODE_APP_SWITCH");time.sleep(3)
    ns=overview_nodes(v)
    assert any(n.get("id","").endswith("action_screenshot") for n in ns),"Pixel overview not open"
    w,h=map(int,re.findall(r"(\d+)x(\d+)",v.shell("wm","size"))[-1])
    shown=any(n.get("desc")=="反人性软件" and v.bounds(n)[0]<w//2<v.bounds(n)[2] for n in ns)
    (v.out/"017-direct-recents-state.txt").write_text(v.shell("dumpsys","activity","-a","activities",PKG))
    v.shot("017-前台直接最近任务")
    if shown:
        # A direct transition is not the same as leaving via Home: only dismiss the positively identified own card.
        v.shell("input","swipe",w//2,int(h*.8),w//2,5,390);time.sleep(.6)
    v.shell("input","keyevent","KEYCODE_HOME");s=blocked(v);assert s["ready"]=="true" and s["paused"]=="false",s
    print("Direct foreground to overview: own card", "still present; actual swipe preserved Pixel service" if shown else "absent",
          "(normal overview after Home is checked separately)",flush=True)


def icon_check(v):
    v.shell("input","keyevent","KEYCODE_HOME")
    until(lambda:int(state(v)["overlays"])>0,"original icon protection")
    icon=v.target("Photos",nodes=overview_nodes(v))
    before=int(state(v)["feedbackCount"])
    v.touch(icon)
    until(lambda:int(state(v)["feedbackCount"])>before,"real Photos icon tap not intercepted")
    s=state(v)
    assert s["feedbackEntry"]=="icon" and "photos" in s["feedbackPackage"] and " "+home_pkg(v)+"/" in v.front(),s
    print("PASS: original Photos icon blocked while manager offscreen:",s["lastFeedback"],flush=True)


def behavior(v):
    assert "versionName=0.17.0" in v.shell("dumpsys","package",PKG)
    until(lambda:state(v).get("configured")=="2","fixture rules not present after covered upgrade")
    recents_check(v);icon_check(v)
    # Direct target launch after Back tests the path a notification or recent task would take.
    launch(v);v.back();until(lambda:" "+home_pkg(v)+"/" in v.front(),"back did not exit manager")
    result=blocked(v);assert int(result["compositeMask"])==5 and result["feedbackEntry"]=="return",result
    body=enter_log(v); (v.out/"017-journal.txt").write_text(body)
    assert "保护已连接" in body and "Photos" in body and "计算器" in body,body
    assert "倒数" in body and "休息提醒" in body and "桌面" in body,body
    v.shot("017-运行日志")
    v.back();v.tap("运行日志")
    again=v.text()
    assert body==again,(body,again)
    print("PASS: real actions and counted combination appear in a stable journal; re-entering does not duplicate rows.",flush=True)


def stopped(v):
    # Only an explicitly disposable emulator is allowed; this tears down the test service.
    v.shell("am","force-stop",PKG)
    until(lambda:"ready=true" not in v.shell("dumpsys","activity","service",PKG+"/.NativeRestService"),"test stop didn't stop service")
    v.shell("am","start","-W","-n",CALC);time.sleep(.8)
    assert " "+CALC.split("/")[0]+"/" in v.front(),"explicit stop unexpectedly intercepted target"
    v.shell("input","keyevent","KEYCODE_HOME")
    launch(v)
    if "运行日志" not in v.text():v.tap("设置")
    v.tap("运行日志")
    after=v.text();(v.out/"017-after-force-stop.txt").write_text(after)
    info=v.shell("dumpsys","activity","exit-info",PKG)
    latest=re.search(r"ApplicationExitInfo #0:.*?timestamp=\d{4}-(\d{2}-\d{2} \d{2}:\d{2}:\d{2}).*?subreason=21 \(FORCE STOP\)",info,re.S)
    assert latest and latest[1]+"\n系统强行停止了反人性软件" in after,(info[:550],after)
    assert "计算器" in after and "Photos" in after,after
    v.shot("017-强停后读取历史")
    v.back();v.tap("运行日志")
    assert v.text()==after
    print("PASS: log recovered OS-reported force-stop after explicit termination; earlier actions remain after process death.",flush=True)


def nohit(v):
    # This stage follows the deliberate force-stop, so restore the disposable fixture's grant.
    launch(v);v.service(True)
    for _ in range(3):
        if "我的应用" in v.text(): break
        if any(n.get("text")=="应用" for n in v.nodes()):v.tap("应用");break
        v.back()
    v.tap("计算器");v.tap("休息时段");h=int(v.shell("date","+%H"))
    interval(v,0,(h+3)%24,0,(h+4)%24,0)
    v.tap("其余时间");v.slider(0);v.tap("保存规则")
    calc_pkg=CALC.split("/")[0]
    v.shell("am","start","-W","-n",CALC);time.sleep(.6)
    assert " "+calc_pkg+"/" in v.front()
    log=enter_log(v);assert "本次正常使用" not in log,log
    v.back();v.tap("应用");v.tap("计算器");v.tap("其余时间");v.slider(1);v.tap("保存规则")
    after_one_percent(v)


def after_one_percent(v):
    calc_pkg=CALC.split("/")[0]
    # 1% is still real probability. Explicitly permit a genuine hit before finding an unhit attempt.
    for _ in range(12):
        v.shell("input","keyevent","KEYCODE_HOME");time.sleep(.4)
        v.shell("am","start","-W","-n",CALC);time.sleep(.75)
        if " "+calc_pkg+"/" in v.front() and int(state(v)["chancePasses"])>0:break
        time.sleep(5.3)
    else: raise AssertionError("no unhit attempt observed")
    # Generating repeated events inside the same foreground visit must not create new attempts.
    for i in range(7): v.shell("input","tap",400,850)
    time.sleep(.5);logs=enter_log(v)
    assert logs.count("计算器 · 本次正常使用")==1 and "日常触发概率 1%，这次未触发" in logs,logs
    v.shot("017-未命中记录")
    print("PASS: 0% produces no non-hit events; 1% actual non-hit and repeated touches in the same visit yield one reason row.",flush=True)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("serial");p.add_argument("--disposable",action="store_true",required=True);p.add_argument("--out",type=Path,required=True)
    p.add_argument("--stage",choices=["seed","behavior","direct-overview","stopped","nohit"],required=True)
    a=p.parse_args();v=Form(a.serial,a.out);{"seed":seed,"behavior":behavior,"direct-overview":direct_overview,"stopped":stopped,"nohit":nohit}[a.stage](v)
