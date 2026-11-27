#!/usr/bin/env python3
"""0.18 actions during rest. Use only a disposable API 34 Pixel AVD, calculator QA fixture and Clock."""
import argparse
import json
import re
import time
from pathlib import Path
from shared_basket_ui import Form, CALC, OTHER, blocked, mode, state
from navigation_ui import PKG, until

SELECTED = "按所选反馈执行"
BLOCK = "完全禁止打开"
CALCPKG = CALC.split("/")[0]
CLOCKPKG = OTHER.split("/")[0]
PHOTOS = "com.google.android.apps.photos"


def audio(v,stream="MUSIC"):
    raw=v.shell("dumpsys","audio").split("- STREAM_"+stream+":")[1].split("\n- STREAM_")[0]
    return bool(re.search(r"\bMuted: true",raw)),int(re.search(r"streamVolume:(\d+)",raw)[1])


def opened(v,pkg):
    return " "+pkg+"/" in v.front()


def policy(v,label):
    v.act("执行方式");v.tap(label)


def prepare_old(v):
    assert "versionName=0.17.0" in v.shell("dumpsys","package",PKG)
    v.fresh();v.tap("＋ 添加第一个应用");v.tap("计算器");v.tap("负反馈")
    v.switch("间歇卡顿",True);v.switch("强行消音",True);v.switch("打开后退回桌面",False)
    v.switch("轻振两下",False);v.switch("倒数 5 秒",False);v.act("完成选择")
    v.tap("其余时间");v.slider(37);mode(v,True);v.tap("保存规则");v.tap("确认保存")
    v.target("计算器");blocked(v);v.start();v.tap("计算器");v.shot("0.17.0-休息硬拦");v.back()
    print("Original 0.17 rule: only content stall and mute; rest blocks, day 37% uses both.",flush=True)


def only_app_receives_tap(v,target_node,should_open):
    v.touch(target_node);time.sleep(.2)
    top=v.front()
    assert ("com.android.bbkcalculator/.Result" in top if should_open else CALC in top),top


def upgrade_and_effects(v):
    assert "versionName=0.18.0" in v.shell("dumpsys","package",PKG)
    v.start();v.tap("计算器");v.tap("休息时段")
    assert BLOCK in v.text(),v.text()
    v.tap("负反馈");assert v.checked("间歇卡顿") and v.checked("强行消音");v.back()
    v.tap("其余时间");s=v.text();assert "37%" in s and "随机 2～3 种" in s,s
    v.back();blocked(v)
    v.start();v.tap("计算器");v.tap("休息时段");policy(v,SELECTED);mode(v,True)
    v.tap("保存规则");v.target("确认保存");v.tap("取消");v.back();v.tap("放弃修改")
    blocked(v)
    v.start();v.tap("计算器");v.tap("休息时段");policy(v,SELECTED);mode(v,True)
    v.tap("保存规则");v.tap("确认保存");v.tap("计算器");v.tap("休息时段");s=v.text()
    assert SELECTED in s and "随机 2～3 种" in s,s
    v.shot("01-休息按反馈执行");v.tap("其余时间");assert "37%" in v.text() and "随机 2～3 种" in v.text()
    v.back()
    v.shell("am","start","-W","-n",OTHER);baseline=audio(v);ring=audio(v,"RING")
    assert not baseline[0] and baseline[1]>0,baseline
    v.shell("am","start","-W","-n",CALC);until(lambda:opened(v,CALCPKG),"selected policy must let calculator stay in foreground")
    until(lambda:state(v)["effectMediaLease"]=="true" and audio(v)[0],"actual shared media mute during rest")
    # The QA Calculator's real central button opens a separate Result activity.
    # Capture its bounds before the stall, then deliver identical physical input in both phases.
    target=v.target("打开详情",nodes=v.nodes(CALCPKG))
    until(lambda:state(v)["effectTouch"]=="true","stall never blocked input",12)
    only_app_receives_tap(v,target,False)
    assert audio(v)[0] and audio(v,"RING")==ring
    until(lambda:state(v)["effectTouch"]=="false","stall did not yield to a new working phase",10)
    only_app_receives_tap(v,target,True)
    print("Frozen: Calculator remained. Thawed: identical button tap opened Result. Media muted, ring unchanged.",flush=True)
    v.shell("am","start","-W","-n",OTHER)
    until(lambda:state(v)["effectMediaLease"]=="false" and state(v)["effectTouch"]=="false" and audio(v)==baseline,"effects did not release on leaving target")
    assert opened(v,CLOCKPKG) and audio(v,"RING")==ring
    print("PASS: old rest policy remained closed, cancelling switch preserved it; explicitly selected rest paired input stalls with music mute, exit restored media.",flush=True)

def get_icon(v):
    home=v.shell("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.HOME").splitlines()[-1].split("/")[0]
    v.shell("input","keyevent","KEYCODE_HOME");time.sleep(.4)
    return v.target("Photos",nodes=v.nodes(home))


def photo_basket(v,content,terminal,adding):
    v.start()
    if adding:
        v.tap("＋ 添加第一个应用" if "＋ 添加第一个应用" in v.text() else "＋ 添加应用");v.tap("按应用名或包名搜索",prop="desc");v.shell("input","text","Photos")
        v.touch(next(n for n in v.nodes() if n.get("text")=="Photos" and not n.get("desc")))
    else:v.tap("Photos")
    v.tap("休息时段");assert SELECTED in v.text(),v.text()
    v.tap("负反馈");v.tap("全选");v.tap("取消全选");v.tap(content);v.act(terminal)
    if terminal=="自动改道":
        v.act("备选应用");v.tap("Clock")
    v.switch("轻振两下",False);v.switch("倒数 5 秒",False);v.act("完成选择")
    mode(v,True);v.tap("保存规则")
    if terminal=="直接锁屏":v.tap("确认保存")
    v.target("Photos")


def icon_attempt(v,icon):
    v.shell("input","keyevent","KEYCODE_HOME")
    until(lambda:int(state(v).get("overlays",0))>0,"no icon interceptor")
    time.sleep(.35);v.touch(icon);until(lambda:opened(v,PHOTOS),"actual Photos target never appeared",5)


def guard(v,pkg,seconds,locks=None):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        assert opened(v,pkg),v.front()
        if locks is not None:
            assert int(state(v)["acceptedLocks"])==locks
            assert "mWakefulness=Awake" in v.shell("dumpsys","power")
        time.sleep(.18)


def redirect(v):
    icon=get_icon(v);photo_basket(v,"视野缩窄","自动改道",True)
    icon_attempt(v,icon)
    until(lambda:state(v)["effectInk"]=="true" and int(state(v)["effectNarrowPct"])>0,"visual phase never started before redirect")
    assert opened(v,PHOTOS);v.shot("02-实际改道前的缩窄")
    until(lambda:opened(v,CLOCKPKG),"selected destination did not open after visual feedback",10)
    assert state(v)["effectInk"]=="false"
    # A quick retry from the same icon must cancel if the user goes elsewhere during the visual part.
    icon_attempt(v,icon)
    v.shell("am","start","-W","-a","android.settings.SETTINGS");guard(v,"com.android.settings",8)
    assert state(v)["effectInk"]=="false"
    # An external open cannot authorize a redirect even after a real icon episode.
    v.shell("am","start","-W","-n",PHOTOS+"/.home.HomeActivity");guard(v,PHOTOS,8)
    print("PASS: actual foreground narrowing then Clock; switching to Settings cancelled redirect; an external launch did not redirect.",flush=True)
    return icon


def cross(v):
    icon=redirect(v)
    photo_basket(v,"间歇卡顿","直接锁屏",False)
    # Do the leave case on the first (not expedited retry) episode.
    before=int(state(v)["acceptedLocks"])
    icon_attempt(v,icon);v.shell("am","start","-W","-a","android.settings.SETTINGS");guard(v,"com.android.settings",8,before)
    icon_attempt(v,icon)
    until(lambda:state(v)["effectTouch"]=="true","touch blocker should actually engage before lock",11)
    until(lambda:int(state(v)["acceptedLocks"])>before and "mWakefulness=Asleep" in v.shell("dumpsys","power"),"lock after verified stall",12)
    v.shell("input","keyevent","KEYCODE_WAKEUP");v.shell("wm","dismiss-keyguard")
    print("PASS: early leave cancelled scheduled lock; remaining on target actually blocked touch before lock.",flush=True)


def cooling(v):
    v.start();v.tap("设置",prop="desc");v.tap("防反悔");v.tap("24 小时冷静期");v.tap("开启冷静期")
    v.back();v.back();v.tap("应用",prop="desc");v.tap("计算器");v.tap("休息时段")
    policy(v,BLOCK);mode(v,False);v.tap("预约保存 · 24 小时后生效")
    if any(n.get("text")=="确认保存" for n in v.nodes()):v.tap("确认保存")
    v.tap("计算器");s=v.text();assert "正在编辑预约" in s and "当前：" in s and "明天" in s,s
    v.shot("03-策略变更需冷静期")
    v.shell("am","start","-W","-n",CALC)
    until(lambda:opened(v,CALCPKG) and state(v)["effectMediaLease"]=="true","pending hard block took effect too early")
    v.shell("am","start","-W","-n",OTHER);until(lambda:state(v)["effectMediaLease"]=="false","mute restored after cooling check")
    print("PASS: queued policy change shows timing, old selected behavior still executes during cooling.",flush=True)


def all_selected(v):
    v.start();v.tap("＋ 添加第一个应用" if "＋ 添加第一个应用" in v.text() else "＋ 添加应用");v.tap("计算器")
    v.tap("休息时段");assert SELECTED in v.text()
    v.tap("负反馈");v.tap("全选");v.act("备选应用");v.tap("Clock")
    v.act("全选回应");v.act("完成选择");mode(v,True);v.tap("保存规则");v.tap("确认保存")
    # Reopen an effective, saved rule, not an unchecked design preview.
    v.tap("计算器");v.tap("休息时段");s=v.text()
    assert SELECTED in s and "随机 2～3 种" in s and "12 种方式 · 5 项回应" in s and "17" in s,s
    v.shot("0.18.0-休息时从全部17项中组合")
    print("PASS: saved and reopened, rest uses all twelve methods plus five responses. Actual label:",s.replace("\n"," "),flush=True)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("serial");p.add_argument("--disposable",required=True,action="store_true")
    p.add_argument("--shots",type=Path,required=True);p.add_argument("stage",choices=["prepare","upgrade","cross","cooling","redirect","all"])
    args=p.parse_args();v=Form(args.serial,args.shots)
    {"prepare":prepare_old,"upgrade":upgrade_and_effects,"cross":cross,"cooling":cooling,"redirect":redirect,"all":all_selected}[args.stage](v)
