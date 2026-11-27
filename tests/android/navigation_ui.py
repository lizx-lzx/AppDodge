#!/usr/bin/env python3
"""Run the native two-tab navigation and interception flows on a disposable, stock-home emulator.
Install AppDodge, the read-only tests/native-home-probe APK and a launcher-visible calculator first.
This intentionally resets ONLY AppDodge test data. Do not use an emulator containing real rules.
"""
import argparse
import json
import re
import shutil
import subprocess
import time
from pathlib import Path

PKG = "dev.local.appdodge"
SERVICE = PKG + "/" + PKG + ".NativeRestService"


class Screen:
    def __init__(self, serial, out):
        if not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("Only a disposable Android emulator is supported")
        self.adb = [shutil.which("adb") or str(Path.home()/"Library/Android/sdk/platform-tools/adb"), "-s", serial]
        assert self.shell("getprop", "ro.kernel.qemu") == "1", "Not an emulator"
        self.out = out
        out.mkdir(exist_ok=True, parents=True)

    def shell(self, *args):
        return subprocess.check_output([*self.adb, "shell", *map(str,args)], text=True, timeout=20).strip()

    def nodes(self, package=PKG):
        deadline=time.monotonic()+8
        while time.monotonic()<deadline:
            raw = self.shell("am", "instrument", "-w", "-e", "launcher", package, "-e", "onscreen", "1",
                             "-e", "rows", "all", "dev.local.nativehomeprobe/.Probe")
            doc = json.loads(raw[raw.index("{"):raw.rindex("}")+1])
            if doc["status"] == "ok":
                assert not doc["rowsTruncated"] and not doc["treeTruncated"], "incomplete view tree"
                return doc["nodes"]
            time.sleep(.2)
        raise AssertionError(doc)

    def target(self, word, *, prop=None, nodes=None):
        rows = nodes if nodes is not None else self.nodes()
        for key in ([prop] if prop else ["id", "desc", "text"]):
            found = [n for n in rows if n.get(key)==word]
            if len(found)==1:
                return found[0]
        raise AssertionError((word, [(n.get("text"),n.get("desc")) for n in rows if n.get("text") or n.get("desc")]))

    def tap(self, word, *, prop=None, package=PKG):
        n=self.target(word, prop=prop, nodes=self.nodes(package))
        x,y,X,Y=self.bounds(n)
        self.shell("input", "tap", (x+X)//2, (y+Y)//2)
        time.sleep(.38)

    @staticmethod
    def bounds(n):
        return list(map(int, n["screenBounds"].split(",")))

    def text(self):
        return "\n".join(n["text"] for n in self.nodes() if n.get("text"))

    def back(self):
        self.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(.18)

    def start(self):
        self.shell("am", "start", "-W", "-n", PKG+"/.MainActivity")
        time.sleep(.26)

    def slider(self,value):
        """Set the actual native slider, reading its current RangeInfo instead of guessing a percentage."""
        read=lambda: next(n for n in self.nodes() if n.get("range",[None,None])[:2]==[0,100])
        n=read();x,y,X,Y=self.bounds(n);H=(y+Y)//2
        # Calibrate the touch bounds from two different points; verify each displayed result.
        samples=[]
        for f in (.25,.75):
            px=round(x+(X-x)*f);self.shell("input","tap",px,H)
            samples.append((px,read()["range"][2]))
        (x1,p1),(x2,p2)=samples
        assert p2>p1
        px=min(X-1,max(x+1,round(x1+(value-p1)*(x2-x1)/(p2-p1))))
        for _ in range(4):
            # A tiny tap inside the thumb can be a no-op near the endpoints. Drag from
            # the far side so that 1%/99% are reachable as well as the middle values.
            self.shell("input","swipe",x2 if px<x+(X-x)//2 else x1,H,px,H,260)
            got=read()["range"][2]
            if got==value:return
            px=min(X-1,max(x+1,px+round((value-got)*(x2-x1)/(p2-p1))))
        raise AssertionError(("slider",value,got))

    def shot(self, name):
        with (self.out/(name+".png")).open("wb") as f:
            subprocess.run([*self.adb, "exec-out","screencap","-p"],stdout=f,check=True)

    def front(self):
        raw=self.shell("dumpsys","activity","activities")
        return next((s.strip() for s in raw.splitlines() if "topResumedActivity=" in s),"")

    def service(self, enabled):
        current=self.shell("settings","get","secure","enabled_accessibility_services")
        other=[s for s in current.split(":") if s not in ("", "null", SERVICE, PKG+"/.NativeRestService")]
        if enabled: other.append(SERVICE)
        if other: self.shell("settings","put","secure","enabled_accessibility_services",":".join(other))
        else: self.shell("settings","delete","secure","enabled_accessibility_services")
        self.shell("settings","put","secure","accessibility_enabled",int(bool(other)))
        def connected():
            return "AppDodge: ready=true" in self.shell("dumpsys","activity","service",PKG+"/.NativeRestService")
        until(lambda: connected()==enabled, "service "+str(enabled))

    def fresh(self):
        self.service(False)  # Do not race the old service unbind with granting the fresh fixture.
        assert self.shell("pm","clear",PKG)=="Success"
        self.start()
        self.service(True)


def until(ok, label, seconds=8):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if ok():return
        time.sleep(.18)
    raise AssertionError(label)


def add(v,label):
    v.tap("＋ 添加第一个应用")
    v.tap(label)
    v.tap("保存规则")
    v.target(label)


def run(v,label,component):
    if "/" not in component or component.startswith(PKG+"/"):
        raise ValueError("Provide separate installed test app component")
    calc=component.split("/")[0]
    home=v.shell("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.HOME").splitlines()[-1].split("/")[0]
    assert home not in (calc,PKG) and "." in home
    def active(pkg): return " "+pkg+"/" in v.front()
    def launch_target():v.shell("am","start","-n",component)
    def swiped_remove():
        x,y,X,Y=v.bounds(v.target(label))
        width=int(re.findall(r'(\d+)x\d+',v.shell("wm","size"))[-1])
        v.shell("input","swipe",int(width*.78),(y+Y)//2,int(width*.27),(y+Y)//2,220)
        v.tap("移除",prop="text")
        v.tap("android:id/button1")
    v.fresh()
    rows=v.nodes()
    coords={t:v.bounds(v.target(t,prop="desc",nodes=rows)) for t in ("应用","设置")}
    assert not any(n.get("desc")=="反馈库" or n.get("text")=="反馈库" for n in rows)
    v.tap("设置",prop="desc")
    assert coords["设置"]==v.bounds(v.target("设置",prop="desc"))
    assert coords["应用"]==v.bounds(v.target("应用",prop="desc"))
    v.shot("02-设置");v.tap("应用",prop="desc");add(v,label)
    assert "休息" in v.text()
    time.sleep(2.0)  # Let the save receipt clear before documenting the screen.
    v.shot("01-我的应用")
    # A launch from outside the app bypasses the launcher icon. Check Home AND a new actual return.
    def count():
        s=v.shell("dumpsys","activity","service",PKG+"/.NativeRestService")
        return int(re.search(r"observedHome=(\d+)",s)[1])
    v.shell("input","keyevent","KEYCODE_HOME");until(lambda:active(home),"home before opening")
    before=count();launch_target();until(lambda:count()>before and active(home),"target did not return to stock Home")
    print("PASS: native tabs, add, confirmed foreground interception",flush=True)
    v.start();v.service(False);until(lambda:"现在不会拦截" in v.text(),"offline warning")
    assert "未保护" in v.text()
    v.shot("03-保护未运行")
    v.tap("去开启")
    settings="com.android.settings"
    until(lambda:active(settings),"open Android accessibility directly")
    rows=v.nodes(settings)
    if not any(n.get("id")==settings+":id/switch_text" for n in rows):
        v.target("原桌面点按保护",prop="text",nodes=rows)
        v.shot("07-系统无障碍列表")
        v.tap("原桌面点按保护",prop="text",package=settings)
    v.tap(settings+":id/switch_text",package=settings)
    v.tap(settings+":id/permission_enable_allow_button",package=settings)
    until(lambda:"ready=true" in v.shell("dumpsys","activity","service",PKG+"/.NativeRestService"),"Android grant")
    for _ in range(3):
        if not active(settings):break
        v.back()
    until(lambda:active(PKG) and "现在不会拦截" not in v.text(),"warning failed to clear")
    swiped_remove();assert label not in v.text()
    launch_target();until(lambda:active(calc),"regular launch after removing rule");time.sleep(1.0);assert active(calc)
    v.start();add(v,label)
    print("PASS: disabled-service warning, recovery, swipe-remove, target stays open without rule",flush=True)
    v.tap("设置",prop="desc");v.tap("防反悔");v.tap("24 小时冷静期");v.tap("开启冷静期")
    v.back();v.back();v.tap("应用",prop="desc")
    v.tap(label);v.tap("负反馈");assert " / 12" in v.text(),v.text()
    v.tap("强行消音说明");assert "后台音乐" in v.text();v.shot("08-选项内的消音说明");v.back()
    v.tap("其余时间")
    v.slider(37)
    v.tap("负反馈");v.tap("假装加载");v.tap("视野缩窄");v.tap("完成选择")
    v.tap("每次触发");v.tap("随机 2～3 种")
    v.tap("预约保存 · 24 小时后生效");assert "明天" in v.text()
    v.tap(label);s=v.text();assert "正在编辑预约" in s and "当前：" in s and "37%" in s,s
    v.shot("04-当前规则与预约");v.back()
    v.tap("设置",prop="desc");v.tap("待生效的更改");assert "37%" in v.text()
    v.shot("05-待生效的更改");title=next(n["text"] for n in v.nodes() if n.get("text","").startswith("修改"+label+"规则"))
    v.tap(title);assert "正在编辑预约" in v.text()
    v.back();s=v.text();assert "待生效的更改" in s and "37%" in s,s
    v.back();v.tap("应用",prop="desc")
    v.tap(label);v.tap("撤回修改");assert "新规则生效" not in v.text()
    swiped_remove();s=v.text();assert label in s and "明天" in s and "移除" in s,s
    v.shot("06-移除待生效");v.tap("撤回",prop="text");assert "明天" not in v.text()
    print("PASS: cooling edit/current diff in both locations; undo edit/removal preserves rule",flush=True)
    v.tap("设置",prop="desc");v.tap("防反悔");v.tap("锁定规则 15 分钟");v.tap("锁定 15 分钟")
    assert "规则已锁定" in v.text()
    v.back();v.tap("应用",prop="desc");v.tap(label)
    s=v.text();assert "我的应用" in s and "添加应用" not in s and "保存规则" not in s,s
    print("PASS: real edit remains unavailable across tabs while locked",flush=True)
    v.fresh();add(v,label)
    v.tap("设置",prop="desc");v.tap("防反悔");v.tap("管理时段")
    management_gate(v)


def management_gate(v):
    hour=(int(v.shell("date","+%H"))+6)%24
    for key in ("开始 20:00","结束 20:15"):
        v.tap(key);v.tap(str(hour),prop="desc");v.tap("android:id/button1")
    assert "启用后会立即退出" in v.text()
    v.tap("启用管理时段")
    outside=lambda:" "+PKG+"/" not in v.front()
    until(outside,"manager must close even when in Settings")
    v.start()
    until(outside,"relaunch must not bypass closed management window")
    health=v.shell("dumpsys","activity","service",PKG+"/.NativeRestService")
    assert "ready=true" in health and "configured=1 paused=false" in health,health
    print("PASS: management window closes Settings, refuses relaunch, keeps protection running",flush=True)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("serial");p.add_argument("--disposable",action="store_true",required=True)
    p.add_argument("--label",default="计算器");p.add_argument("--target",default="com.android.bbkcalculator/.Calculator")
    p.add_argument("--shots",type=Path,required=True)
    args=p.parse_args()
    v=Screen(args.serial,args.shots)
    assert v.shell("pm","path","dev.local.nativehomeprobe").startswith("package:"),"install native-home-probe.apk"
    run(v,args.label,args.target)
