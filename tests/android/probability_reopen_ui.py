#!/usr/bin/env python3
"""Re-enter a 60% daily Photos rule on the disposable QA AVD. Measure visits, not an expected 6/10 quota."""
import argparse
import subprocess
import time
from pathlib import Path

from gated_exit_ui import Form, HOME, PHOTOS, TARGET, get_icon, opened, save, state, until
from foreground_recheck_ui import set_period
from shared_basket_ui import mode

MASK = (1 << 11) | (1 << 12)  # narrowing + mute; neither is a timed exit


def configure(v):
    set_period(v, False)  # rest excludes now; other time initially 0%
    v.start(); v.tap("Photos"); v.tap("其余时间"); v.slider(60)
    v.tap("负反馈"); v.tap("全选"); v.tap("取消全选")
    v.act("视野缩窄"); v.act("强行消音")
    v.switch("轻振两下",False); v.switch("倒数 5 秒",False); v.act("完成选择")
    mode(v,True); save(v)
    v.tap("Photos");v.tap("其余时间")
    summary=v.text()
    assert "60%" in summary and "随机 2～3 种" in summary and "视野缩窄" in summary and "强行消音" in summary,summary
    print("Saved effective rule:", " | ".join(summary.splitlines()),flush=True)
    v.back()


def attempt(v,icon,i):
    before=state(v)
    origin="icon" if i%2 else "external"
    if origin=="icon":v.touch(icon)
    else:v.shell("am","start","-n",TARGET)
    until(lambda:opened(v,PHOTOS),"new visit did not enter the actual enrolled app")
    until(lambda:state(v)["effectMode"]!="-1" or state(v)["effectMediaLease"]=="true" or
          int(state(v)["chancePasses"])>int(before["chancePasses"]) or int(state(v)["observedHome"])>int(before["observedHome"]),
          "neither a pass nor intervention for visit "+str(i))
    s=state(v)
    if int(s["chancePasses"])>int(before["chancePasses"]):
        # More than the entire first visible phase for narrowing plus mute. Real window/content
        # churn on the same visit must not reroll a miss and eject the user later.
        v.shell("input","swipe",510,1300,510,770,250);time.sleep(5)
        s=state(v)
        assert opened(v,PHOTOS) and s["chancePasses"]==str(int(before["chancePasses"])+1) and s["observedHome"]==before["observedHome"] and s["effectMediaLease"]=="false" and s["methodDraws"]==before["methodDraws"],s
        result="pass"
        v.shell("input","keyevent","KEYCODE_HOME")  # only after pass proved, user ends that visit
    else:
        until(lambda:int(state(v)["observedHome"])>int(before["observedHome"]) and opened(v,HOME),"hit never actually returned to launcher",11)
        s=state(v)
        assert int(s["methodMask"])==MASK and s["receiptOutcome"]=="confirmed" and s["lastReturned"]==PHOTOS,s
        result="hit"
    if "dailyDecisions" in s:
        assert int(s["dailyDecisions"])==int(before["dailyDecisions"])+1,s
        assert s["decisionTarget"]==PHOTOS and s["decisionChance"]=="60" and s["decisionResult"]==result,s
        assert s["decisionEntry"]==("icon" if origin=="icon" else "foreground"),s
        assert int(s["dailyHits"])==int(before["dailyHits"])+(result=="hit"),s
        assert s["methodEntry"]!="retry",s
    until(lambda:opened(v,HOME),"next attempt must start at launcher")
    time.sleep(.9) # a new deliberate tap, not debounce
    print(f"visit {i} {origin} {result} source={s['methodEntry']} draws={s['methodDraws']} passes={s['chancePasses']} confirmed={s['observedHome']} fresh-decisions={s.get('dailyDecisions','-')}",flush=True)
    return result,s


def run(v,baseline):
    v.start();v.tap("Photos");v.tap("其余时间")
    txt=v.text();assert "60%" in txt and "随机 2～3 种" in txt and "视野缩窄" in txt and "强行消音" in txt,txt
    v.back()
    icon=get_icon(v)
    seen_hit=False;miss_after_hit=False;old_retries=0;fresh_after_hit=0
    for i in range(1,29):
        result,s=attempt(v,icon,i)
        if seen_hit:
            if baseline:
                assert result=="hit" and s["methodEntry"]=="retry",s
                old_retries+=1
            else:
                if result=="pass":miss_after_hit=True
                fresh_after_hit+=1
        if result=="hit":seen_hit=True
        if old_retries==4:
            print("REPRODUCED: four confirmed forced exits on four new deliberate openings, all source=retry, no new draw",flush=True);return
        if not baseline and miss_after_hit and fresh_after_hit>=4:
            print("PASS: a genuinely new opening after a confirmed forced exit can be permitted; permission stays effective for the visit.",flush=True);return
    raise AssertionError("No conclusive sequence after 28 visits; report inconclusive rather than infer from a quota.")


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("serial");p.add_argument("--disposable",required=True,action="store_true")
    p.add_argument("--evidence",type=Path,required=True);p.add_argument("phase",choices=["configure","baseline","verify"]);a=p.parse_args()
    v=Form(a.serial,a.evidence)
    assert subprocess.check_output([*v.adb,"emu","avd","name"],text=True).splitlines()[0]=="AppDodge_Daily_QA"
    if a.phase=="configure":configure(v)
    else:run(v,a.phase=="baseline")
