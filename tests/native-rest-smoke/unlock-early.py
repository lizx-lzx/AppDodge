#!/usr/bin/env python3
"""Send one tap on the first observed *system* unlock, without waiting for AppDodge.

Use only a disposable, unlocked/swipe-keyguard emulator with a previously verified
ordinary HOME icon. Test "allowed" on the same device/timings as "blocked".
"""
from pathlib import Path
import argparse
import re
import subprocess
import time

ap = argparse.ArgumentParser(description=__doc__)
ap.add_argument('serial'); ap.add_argument('phase', choices=('allowed', 'blocked', 'suspended'))
ap.add_argument('target'); ap.add_argument('x', type=int); ap.add_argument('y', type=int)
ap.add_argument('--delays-ms', nargs='+', type=int, default=[0, 100, 350])
ap.add_argument('--cycles', type=int, default=1, help='repetitions per delay')
ap.add_argument('--asleep-seconds', type=float, default=1.)
ap.add_argument('--second-tap-lock', action='store_true', help='start with an already warned target: each round uses its second tap to really lock')
ap.add_argument('--shots', type=Path, help='capture each actual result for visual verification')
a = ap.parse_args()
if a.shots: a.shots.mkdir(parents=True,exist_ok=True)
if not re.fullmatch(r'emulator-\d+',a.serial) or not re.fullmatch(r'[a-zA-Z_][\w.]+',a.target) or min(a.x,a.y,min(a.delays_ms)) < 0 or a.cycles<1 or a.asleep_seconds<0:
    ap.error('Pass a disposable emulator, package, valid pixel coordinates and nonnegative delays.')
def adb(*args):
    return subprocess.check_output(['adb','-s',a.serial,*map(str,args)],text=True,timeout=15).strip()
def sh(s): return adb('shell',s)
def fg(): return sh('dumpsys activity activities | grep topResumedActivity | head -1')
def until(fn, msg):
    stop=time.monotonic()+8
    while not fn():
        if time.monotonic()>=stop: raise RuntimeError(msg)
        time.sleep(.05)
assert sh('getprop ro.kernel.qemu')=='1' and sh('locksettings get-disabled')=='false'
home=sh('cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME').splitlines()[-1].split('/')[0]
assert re.fullmatch(r'[a-z][\w.]+',home) and home not in ('android','dev.local.appdodge')
locked=lambda: sh('dumpsys window policy | grep -m1 "^[ ]*showing="').strip()=='showing=true'
go_home=lambda: until(lambda: home+'/' in fg() and not locked(),'emulator not unlocked on original launcher')
if a.second_tap_lock and a.phase not in ('blocked','suspended'): ap.error('--second-tap-lock requires a protected target')
n=0; passed=0
for delay in a.delays_ms*a.cycles:
    n+=1
    front=fg()
    if 'android/com.android.internal.app.SuspendedAppActivity' in front:
        sh('input keyevent KEYCODE_BACK')
    elif a.target+'/' in front: sh('input keyevent KEYCODE_HOME')
    go_home()
    # The regression concerns wake-up, including when the user presses the power button.
    if a.second_tap_lock:
        # Precondition: an already visible warning, including the previous round's first tap.
        time.sleep(1)
        sh(f'input tap {a.x} {a.y}')
    else: sh('input keyevent KEYCODE_SLEEP')
    until(lambda: 'mWakefulness=Asleep' in sh('dumpsys power') and locked(),'did not enter screen-off+keyguard')
    if a.second_tap_lock: assert 'mLastSleepReason=accessibility' in sh('dumpsys power')
    time.sleep(a.asleep_seconds)
    sh('input keyevent KEYCODE_WAKEUP')
    until(lambda: 'mWakefulness=Awake' in sh('dumpsys power') and locked(),'wake did not present the keyguard')
    # Gate on the real keyguard and Android input focus (not merely the resumed Activity,
    # which often remains HOME behind the lock screen). Never inspect service readiness.
    times=sh(f'''
        start=$(date +%s%3N); wm dismiss-keyguard
        for n in $(seq 1 150); do
          if dumpsys window policy | grep -q "^[ ]*showing=false" &&
            dumpsys input | grep -A1 "FocusedWindows:" | grep -Fq " {home}/"; then
              observed=$(date +%s%3N); {('sleep %.3f' % (delay/1000)) if delay else ':'}
              issued=$(date +%s%3N); log -t AppDodgeEarly "round={n} issued=$issued"; input tap {a.x} {a.y}
              echo "$start $observed $issued"; exit 0
          fi
        done
        exit 1
    ''').splitlines()[-1]
    start, observed, issued = map(int, times.split())
    time.sleep(.55)
    if a.shots:
        (a.shots/f'round-{n:02}.png').write_bytes(subprocess.check_output(['adb','-s',a.serial,'exec-out','screencap','-p']))
    # Stamp with milliseconds so a previous attempt can never count for this tap.
    stamp=sh(f"date -d '@{issued//1000}' '+%m-%d %H:%M:%S'")+f".{issued%1000:03d}"
    logs=adb('logcat','-d','-T',stamp,'-s','AppDodgeGuard:I','ActivityTaskManager:I','DODGELAB:I')
    first=len(re.findall(r'AppDodgeGuard:\s+first_tap\b',logs))
    starts=sum(' START u' in l and f'cmp={a.target}/' in l for l in logs.splitlines())
    resumes=sum('DOT_FOREGROUND onResume' in l for l in logs.splitlines()) if a.target=='dev.local.dodgelab' else None
    ev=adb('logcat','-d','-b','events','-T',stamp,'-s','wm_create_activity:I','wm_resume_activity:I','wm_on_resume_called:I')
    actual=sum(('wm_create_activity' in l or 'wm_resume_activity' in l) and f',{a.target}/' in l for l in ev.splitlines())
    sys_dialog=any('wm_on_resume_called' in l and 'com.android.internal.app.SuspendedAppActivity' in l for l in ev.splitlines())
    front=fg()
    if a.phase=='allowed': ok=(a.target+'/' in front and starts>0)
    elif a.phase=='suspended':
        assert 'suspended=true' in sh(f'dumpsys package {a.target} | grep "User 0:"')
        # Android may log START with the originally requested component before it redirects
        # to the system activity. Actual activity lifecycle and the resulting screen decide.
        os_denied=('android/com.android.internal.app.SuspendedAppActivity' in front and sys_dialog)
        local_warning=(home+'/' in front and first==1 and starts==0)
        ok=(actual==0 and not resumes and (os_denied or local_warning))
    else: ok=(home+'/' in front and not locked() and first==1 and starts==0 and not resumes)
    passed+=ok
    print(f'round={n} delay={delay}ms dismiss→system_ready={observed-start}ms ready→issue={issued-observed}ms '
          f'first={first} requests={starts} targetCreateOrResume={actual} onResume={resumes} actual_front='+
          ('HOME' if home+'/' in front else 'target' if a.target+'/' in front else 'system-paused-dialog' if 'android/com.android.internal.app.SuspendedAppActivity' in front else 'other')+
          (' PASS' if ok else ' MISS / not a demonstrated interception'), flush=True)
    if a.second_tap_lock and not ok: break # no visible warning to start the next round
print(f'{a.phase}: {passed}/{n}', flush=True)
raise SystemExit(int(passed!=n))
