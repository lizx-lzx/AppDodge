#!/usr/bin/env python3
"""Exercise per-app shared baskets, schedules and runtime effects on a disposable Pixel emulator."""
import argparse
import re
import subprocess
import time
from pathlib import Path
from navigation_ui import Screen, PKG, until

CALC = "com.android.bbkcalculator/.Calculator"
OTHER = "com.google.android.deskclock/com.android.deskclock.DeskClock"
IDEAS = ("假装加载","间歇卡顿","视野缩窄","强行消音","自动改道")


class Form(Screen):
    def swipe(self, upwards):
        w,h=map(int,re.findall(r'(\d+)x(\d+)',self.shell("wm","size"))[-1])
        a,b=(.76,.36) if upwards else (.36,.76)
        self.shell("input","swipe",w//2,int(h*a),w//2,int(h*b),330)
        time.sleep(.15)

    def reveal(self, word, upwards=True, prop=None):
        for attempt in range(8):
            rows=self.nodes()
            try:return self.target(word,nodes=rows,prop=prop)
            except AssertionError:
                if attempt==7: raise
                self.swipe(upwards)

    def touch(self,n):
        x,y,X,Y=self.bounds(n)
        self.shell("input","tap",(x+X)//2,(y+Y)//2)
        time.sleep(.22)

    def act(self,label,up=True):
        self.touch(self.reveal(label,up))

    def checked(self, label, up=True):
        return self.reveal(label,up)["checked"]

    def switch(self, label, state, up=True):
        node=self.reveal(label,up)
        if node["checked"]!=state:self.touch(node)

    def set_time(self, description, hour, minute):
        self.touch(self.reveal(description,prop="desc"))
        self.tap(str(hour),prop="desc")
        self.tap(str(minute),prop="desc")
        self.tap("android:id/button1")


def state(v):
    raw=v.shell("dumpsys","activity","service",PKG+"/.NativeRestService")
    return dict(re.findall(r"\b([A-Za-z]\w*)=([^\s,]+)",raw))


def blocked(v,component=CALC):
    home=v.shell("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.HOME").splitlines()[-1].split("/")[0]
    v.shell("input","keyevent","KEYCODE_HOME")
    until(lambda:"observedHome" in state(v),"diagnostic service not ready")
    before=int(state(v)["observedHome"])
    v.shell("am","start","-n",component)
    until(lambda:int(state(v)["observedHome"])>before and " "+home+"/" in v.front(),"strict rest did not return target to stock Home")
    s=state(v)
    assert s["lastReturned"]==component.split("/")[0] and s["effectMediaLease"]=="false" and s["effectTouch"]=="false",s
    print("PASS: real strict return",s["lastReturned"],s.get("lastFeedback"),flush=True)
    return s


def prepare_legacy(v):
    """Seed two different real 0.15 rules before an in-place candidate install."""
    assert "versionName=0.15.0" in v.shell("dumpsys","package",PKG)
    v.fresh()
    v.tap("＋ 添加第一个应用");v.tap("计算器");v.tap("拦截后反馈")
    v.tap("全选");v.tap("取消全选");v.tap("休息提醒");v.tap("完成选择");v.tap("保存规则")
    v.tap("＋ 添加应用");v.tap("Calendar");v.tap("其余时间")
    v.tap("触发概率");v.tap("25%");v.tap("负反馈篮")
    v.tap("间歇卡顿");v.tap("假装加载");v.tap("完成选择");v.tap("保存规则")
    assert "Calendar" in v.text() and "计算器" in v.text()
    print("Prepared 0.15 fixtures: calculator NOTICE only + daily OFF; Calendar NOW + daily STALL at 25%.",flush=True)


def assert_visible_ideas(v):
    rows=v.nodes()
    for idea in IDEAS:
        assert isinstance(v.target(idea,nodes=rows)["checked"], bool), idea


def mode(v,combo):
    v.act("每次触发")
    v.tap("随机 2～3 种" if combo else "一种")


def interval(v,index,h1,m1,h2,m2):
    # Re-read after choosing start. An overnight suffix can change on the end control.
    for label,h,m in (("开始",h1,m1),("结束",h2,m2)):
        times=[n for n in v.nodes() if label in n.get("desc","") and re.search(r'\d{2}:\d{2}',n["desc"])]
        assert index<len(times),(label,index,[n.get("desc") for n in times])
        v.set_time(times[index]["desc"],h,m)
def choose_clock(v,h,m=0):
    v.tap(str(h),prop="desc");v.tap(str(m),prop="desc");v.tap("android:id/button1")


def upgrade(v):
    assert "versionName=0.16.0" in v.shell("dumpsys","package",PKG)
    v.start(); v.tap("Calendar");v.tap("其余时间");assert "25%" in v.text()
    v.tap("负反馈");v.tap("起始选择");v.tap("原日常选择")
    assert v.checked("间歇卡顿") and not v.checked("视野缩窄")
    v.back();v.tap("放弃修改");v.back()
    v.tap("计算器");v.tap("休息时段");v.tap("负反馈");assert_visible_ideas(v)
    assert not v.checked("视野缩窄")
    assert v.checked("休息提醒") and not v.checked("轻振两下")
    v.back()
    # A discarded checkbox edit must leave the old rule (and the other app) intact.
    v.tap("负反馈");v.tap("间歇卡顿");v.back();v.tap("放弃修改")
    v.tap("负反馈");assert not v.checked("间歇卡顿")
    v.tap("视野缩窄");v.act("打开后退回桌面")
    v.act("倒数 5 秒");v.act("完成选择")
    v.tap("其余时间");assert "0%" in v.text();v.tap("负反馈")
    assert v.checked("视野缩窄") and v.checked("倒数 5 秒")
    v.back();v.tap("保存规则");v.target("改用共用负反馈？");v.tap("确认保存")
    v.tap("计算器");v.tap("其余时间");assert "0%" in v.text()
    v.tap("负反馈");assert v.checked("视野缩窄") and not v.checked("强行消音")
    assert v.checked("休息提醒") and v.checked("倒数 5 秒")
    v.back();v.tap("休息时段");v.tap("负反馈");assert v.checked("视野缩窄") and v.checked("倒数 5 秒")
    v.back();v.back();v.tap("Calendar");v.tap("负反馈");v.tap("起始选择");v.tap("原日常选择")
    assert v.checked("间歇卡顿") and not v.checked("视野缩窄")
    v.back();v.tap("放弃修改");v.back()
    print("PASS: actual 0.15 split policies retained; explicit conversion, same 0%-disabled basket on reopen in both scopes, other app unchanged.",flush=True)


def behavior(v):
    v.start();v.tap("计算器");v.tap("休息时段")
    hour=int(v.shell("date","+%H"))
    first,last=(23,hour+1) if hour<13 else (hour-1,1)
    away=(hour+5)%24 if hour<13 else (hour-5)%24
    interval(v,0,first,0,last,0)
    v.act("＋ 添加时段");choose_clock(v,away);choose_clock(v,away+1)
    mode(v,True);v.tap("其余时间");v.slider(37)
    assert "一种" in v.text() and "37%" in v.text();v.shot("02-日常37")
    v.tap("保存规则");v.tap("计算器");v.tap("休息时段");s=v.text()
    assert "随机 2～3 种" in s and f"次日 {last:02}:00" in s and f"{away:02}:00" in s,s
    v.shot("01-多段休息");v.tap("负反馈");assert_visible_ideas(v);v.shot("03-共享负反馈")
    assert v.checked("倒数 5 秒") and v.checked("休息提醒");v.shot("04-拦后回应");v.back()
    v.tap("其余时间");assert "37%" in v.text() and "一种" in v.text()
    v.back()
    strict_then_day(v,first,last,away)


def strict_then_day(v,first,last,away):
    res=blocked(v)
    # Exactly two selected responses; observe both instead of a single randomly relabelled toast.
    assert int(res["compositeMask"])==5 and res["feedbackEntry"]=="return",res
    v.shot("05-严格组合")
    print("PASS: overnight + other period persisted; 37% day single does not change strict combination; NOTICE and COUNTDOWN actually executed together.",flush=True)

    # Remove the currently active period, retaining the other period as the only one.
    v.start();v.tap("计算器");v.tap("休息时段")
    overnight=next(n["desc"] for n in v.nodes() if re.fullmatch(r"第 \d+ 段开始 "+f"{first:02}:00",n.get("desc","")))
    v.act("移除"+overnight.split("段")[0]+"段");v.tap("其余时间");v.slider(100)
    v.tap("负反馈");v.tap("强行消音");v.tap("完成选择");mode(v,True);v.tap("保存规则");v.tap("确认保存")
    v.tap("计算器");v.tap("休息时段");s=v.text()
    assert f"{away:02}:00" in s and f"次日 {last:02}:00" not in s
    v.tap("其余时间");assert "100%" in v.text() and "随机 2～3 种" in v.text()
    v.tap("负反馈");assert v.checked("视野缩窄") and v.checked("强行消音");v.back();v.back()
    combo=Path(__file__).resolve().parents[1]/"native-rest-smoke/check-combo.py"
    import sys
    subprocess.run([sys.executable,str(combo),v.adb[-1],CALC,OTHER,"--shots",str(v.out/"daily-combo")],check=True)


def redirect_combination(v):
    home=v.shell("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.HOME").splitlines()[-1].split("/")[0]
    v.shell("input","keyevent","KEYCODE_HOME");time.sleep(.6)
    icon=v.target("Photos",nodes=v.nodes(home))
    v.start();v.tap("＋ 添加应用");v.tap("按应用名或包名搜索",prop="desc");v.shell("input","text","Photos")
    v.touch(next(n for n in v.nodes() if n.get("text")=="Photos" and not n.get("desc")))
    v.tap("休息时段");v.act("负反馈");v.tap("全选");v.tap("取消全选");v.tap("假装加载");v.tap("自动改道")
    v.act("备选应用");v.tap("Clock")
    v.switch("轻振两下",False);v.switch("倒数 5 秒",False);v.act("完成选择")
    mode(v,True);v.tap("保存规则");v.target("Photos")
    def attempt():
        v.shell("input","keyevent","KEYCODE_HOME")
        until(lambda:int(state(v)["overlays"])>0,"original icon overlay")
        time.sleep(.6)
        before=int(state(v)["feedbackCount"])
        v.touch(icon);until(lambda:int(state(v)["feedbackCount"])>before,"original icon intercepted")
        s=state(v);assert int(s["compositeMask"])==768 and s["feedbackEntry"]=="icon" and " "+home+"/" in v.front(),s
    def watch(seconds,permitted,want=None):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            top=v.front();assert any(" "+p+"/" in top for p in permitted),top
            if want and " "+want+"/" in top:return
            time.sleep(.15)
        assert want is None,"expected redirect to "+str(want)
    attempt();v.shot("06-加载后改道");clock=OTHER.split("/")[0]
    watch(19,(home,clock),clock)
    attempt();v.shell("am","start","-W","-a","android.settings.SETTINGS")
    until(lambda:int(state(v)["desktopSequence"])==0,"leaving the stock desktop cancels pending redirect")
    watch(32,("com.android.settings",))  # quick retry uses a longer 30-second wait
    print("PASS: real original icon ran loading + redirect without opening target; switching to Settings cancelled pending redirect.",flush=True)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("serial");p.add_argument("--disposable",required=True,action="store_true")
    p.add_argument("--shots",type=Path,required=True);p.add_argument("stage",choices=["prepare-legacy","upgrade","behavior","redirect"])
    args=p.parse_args();v=Form(args.serial,args.shots)
    {"prepare-legacy":prepare_legacy,"upgrade":upgrade,"behavior":behavior,"redirect":redirect_combination}[args.stage](v)
