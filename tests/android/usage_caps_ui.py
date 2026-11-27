"""Run on an owned disposable Google APIs emulator with the product and native-home-probe installed.
Resets this app's test state. Uses the stock Calendar activity; run on an English Android image.
"""
import sys,time
from pathlib import Path
from navigation_ui import Screen,PKG,until

v=Screen(sys.argv[1],Path(sys.argv[2]))
v.fresh()
# Keep historical Calendar activity outside this test from spending its daily allowance.
v.shell("cmd","appops","set",PKG,"GET_USAGE_STATS","ignore")
v.tap("＋ 添加第一个应用");v.tap("Calendar");v.tap("使用上限")
for label,n in (("单次使用上限（分钟）",1),("单日使用上限（分钟）",2),("单次用满后休息（分钟）",1)):
    v.tap(label,prop="desc");v.shell("input","text",n);v.back()
v.tap("同时启用休息时段");v.tap("确定");v.tap("保存规则")
assert "每天 2 分钟" in v.text()
def launch():
    v.shell("am","start","-n","com.google.android.calendar/com.android.calendar.AllInOneActivity")
    time.sleep(.65)
def home():return "launcher" in v.front().lower()
def home_receipts():
    import re
    dump=v.shell("dumpsys","activity","service",PKG+"/.NativeRestService")
    return int(re.search(r"observedHome=(\d+)",dump).group(1))
def returned(previous,seconds):
    until(lambda: home() and home_receipts()>previous,"quota return",seconds)
v.shell("input","keyevent","KEYCODE_HOME");launch();time.sleep(17)
v.shell("input","keyevent","KEYCODE_HOME");time.sleep(6)
count=home_receipts();launch();then=time.monotonic();returned(count,52)
assert 33<time.monotonic()-then<52
count=home_receipts();launch();returned(count,6)
time.sleep(61);count=home_receipts();launch();assert not home();returned(count,66)
count=home_receipts();launch();returned(count,6)
v.start();v.tap("设置",prop="desc");v.tap("运行日志")
assert all(x in v.text() for x in ("今日额度已用完","单次使用上限已到"))
v.shot("运行日志")
v.back();v.tap("个人报告",prop="desc")
body=v.text()
assert "拦截 4 次" in body and "打开前拦住 0 次   ·   使用后退出 4 次" in body and "Calendar" in body,body
assert "开启使用情况访问权限" not in body and "活动热图" not in body
v.tap("累计");assert "拦截 4 次" in v.text()
v.shot("个人报告")
