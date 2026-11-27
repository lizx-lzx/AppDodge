# 原桌面点按保护：模拟器验收

用 **Android 14 一次性模拟器 + 原装 Pixel Launcher**，不在个人手机上跑本工具。安装本项目实际 APK；选择一个有独立桌面图标的普通应用（不在底栏、文件夹、应用列表或搜索里），记录该图标中心像素。先正常点一次，确定坐标打开的就是它，再返回原桌面。

开启 AppDodge 的「原桌面点按保护」无障碍服务，在「选择应用」中为这个包开启覆盖当前时间的休息规则。模拟器应有可滑动解锁、不设口令的系统锁屏；如果此临时 AVD 默认选择「无」，可运行 `adb -s <模拟器> shell locksettings set-disabled false`。保持屏幕常亮，等桌面稳定。

```sh
python3 tests/native-rest-smoke/check.py emulator-5588 blocked dev.local.dodgelab 276 440 \
  --record /tmp/rest-check.webm --shots /tmp/rest-check-shots
```

首次点按和紧随的点按不锁手机；看过提醒后再点，脚本等系统实际报告 `Asleep / OFF / keyguard showing=true`。它**只**在已经睡眠后发送唤醒和解锁，不发送熄屏或电源按键。解锁后检查没有自动重锁，再次首点仍停在桌面。运行后检查截图里的预警和角标是否可辨认，并核对该时段内的 Activity 启动日志；脚本不能替代视觉检查。

时段结束后在同一页同一位置执行：

```sh
python3 tests/native-rest-smoke/check.py emulator-5588 allowed dev.local.dodgelab 276 440
```

换包、位置或模拟器需换相应参数。若用自己的测试 Activity（本次用 Dodge Dot），让它在 onResume 记日志，方便与系统 ActivityTaskManager 的启动记录交叉核对。Pixel 的结果不代表 vivo 的桌面实现。

### 不等待保护层，测解锁后的第一次点击

先完成没有保护时的同格阳性对照。 `unlock-early.py` 从系统锁屏不再显示、原桌面成为输入焦点起，按要求的延迟立即发出一次点击；不会等 AppDodge 报告图标就绪。输出的时间是**观察到系统状态至发出请求**的间隔，不是硬件触摸到达时刻。每轮只发一次；停在桌面但没有有效提醒的点击不算拦截成功。可用 `--shots` 看每一轮的实际结果。

```sh
python3 tests/native-rest-smoke/unlock-early.py emulator-5592 allowed dev.local.dodgelab 414 681 --delays-ms 0 150 350
# 启用这枚普通原图标的限制时段后
python3 tests/native-rest-smoke/unlock-early.py emulator-5592 blocked dev.local.dodgelab 414 681 --delays-ms 0 150 350 --cycles 2 --shots /tmp/unlock-results
```

默认由测试端按睡眠键，独立检测自然熄屏后重建；**要测 AppDodge 自己锁屏的闭环**，先让首次提示实际显示，再用 `--second-tap-lock`。此时程序点图标请求锁屏，不发送睡眠键，并要求系统休眠原因为无障碍服务。正常桌面、允许时段和测试坐标都要先在本次使用的模拟器上核实。

`suspended` 仅测试已由**单独授权的模拟器操作**暂停的目标包。脚本不会授予权限、暂停或恢复任何包。它把 AppDodge 的原位提示与 Android 暂停对话框分别记录。Android 可能在重定向前以目标包名记一条 START，须分别核对实际 Activity 创建/恢复、目标 onResume 和最终画面。暂停实验结束后解除该目标的暂停、移除测试规则，并再次实际打开同一图标。
