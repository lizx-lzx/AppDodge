"""Follow usage_caps_ui.py on the same disposable Pixel: add an actual pre-open block."""
import sys, time
from pathlib import Path
from shared_basket_ui import Form, state
from navigation_ui import until

v=Form(sys.argv[1],Path(sys.argv[2]))
v.start()
v.tap("个人报告",prop="desc"); v.tap("今天")
before=v.text()
assert "拦截 4 次" in before and "打开前拦住 0 次" in before,before
def metric(body,title):return body.split(title+"\n",1)[1].split("\n",1)[0]
average=metric(before,"平均每次用多久才退出")
total=metric(before,"退出前合计用时")
v.tap("应用",prop="desc");v.tap("＋ 添加应用")
v.tap("按应用名或包名搜索",prop="desc");v.shell("input","text","Photos")
v.touch(next(n for n in v.nodes() if n.get("text")=="Photos" and not n.get("desc")))
v.tap("保存规则");v.target("Photos")
v.shell("input","keyevent","KEYCODE_HOME")
until(lambda:int(state(v)["overlays"])>0,"Photos original icon")
home=v.shell("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.HOME").splitlines()[-1].split("/")[0]
icon=v.target("Photos",nodes=v.nodes(home));v.touch(icon);v.touch(icon)
time.sleep(.5)
assert home in v.front()
v.start();v.tap("个人报告",prop="desc");v.tap("今天")
after=v.text()
assert "拦截 5 次" in after and "打开前拦住 1 次   ·   使用后退出 4 次" in after and "Photos" in after,after
assert metric(after,"平均每次用多久才退出")==average and metric(after,"退出前合计用时")==total,after
v.shot("拦截报告");v.tap("运行日志")
log=v.text()
assert "Photos · 休息时段拦截" in log and "打开前拦住" in log and "Calendar · 今日额度已用完" in log,log
v.shot("拦截明细");v.tap("累计拦截")
assert "拦截报告" in v.text() and "拦截 5 次" in v.text()
print(after)
