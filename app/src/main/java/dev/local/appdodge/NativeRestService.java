package dev.local.appdodge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.view.Display;
import android.view.accessibility.AccessibilityWindowInfo;
import android.media.AudioManager;
import android.content.ActivityNotFoundException;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.media.AudioAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.util.Random;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native icon feedback plus explicitly selected foreground return; never acts from an event name alone. */
public final class NativeRestService extends AccessibilityService {
    static final String TAG = "AppDodgeGuard";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Pad> pads = new HashMap<>();
    private final Random random = new java.security.SecureRandom();
    private GuardLog journal;
    private QuotaClock quotas;
    private final VisitSample sample=new VisitSample();
    private boolean sampleFrame;
    private long exitSample;
    private Rule pendingTerminal;private String terminalDest;private long terminalSample,terminalAt;
    private void terminal(Rule r,String dest,long measured){
        pendingTerminal=r;terminalDest=dest;terminalSample=measured;terminalAt=SystemClock.uptimeMillis();
    }
    private void terminalReceipt(String dest){
        if(pendingTerminal==null)return;
        if(SystemClock.uptimeMillis()-terminalAt>4000){pendingTerminal=null;return;}
        if(!java.util.Objects.equals(dest,terminalDest))return;
        recordExit(pendingTerminal,dest==null?"规则锁屏":"自动改道",terminalSample);pendingTerminal=null;
    }
    private void observeSample(ForegroundWindow w){
        if(w.pkg==null||!w.cover.canCountViewing())return;
        terminalReceipt(w.pkg);
        if(!returnConfigured(w.pkg)){sample.leave();return;}
        sampleFrame=true;sample.see(w.pkg,SystemClock.uptimeMillis());
    }
    private long used(String pkg){
        Rule c=capped.get(pkg);
        if(c!=null&&c.limits.session>0)return quotas.unreportedRound(pkg);
        long now=SystemClock.uptimeMillis();
        return Math.max(pkg.equals(sample.pkg)?sample.value(now):0,pkg.equals(visit.target)?visit.unreported(now):-1);
    }
    private void recordExit(Rule r,String reason,long ms){
        BlockLedger.get(this).record(r.packageName,r.label,reason,ms);
        if(capped.containsKey(r.packageName))quotas.credit(r.packageName);
        visit.credit(r.packageName);sample.leave();
    }
    private void iconBlock(Pad p,String reason){BlockLedger.get(this).record(p.rule.packageName,p.rule.label,reason,-1);}

    private boolean quotaFrame;
    private final Runnable quotaTick=this::scanNow;
    private long lastReportDay=Long.MIN_VALUE;
    private void syncUsage(boolean force){
        if(!ready || !UsageReport.allowed(this))return;
        long day=java.time.LocalDate.now().toEpochDay();
        if(!force&&day==lastReportDay)return;
        lastReportDay=day;
        Set<String> targets=new java.util.HashSet<>();for(Rule r:capped.values())if(r.limits.day>0)targets.add(r.packageName);
        if(targets.isEmpty())return;
        new UsageReport(this).read(targets,s->{
            if(connected!=this||!ready)return;
            UsageSeries.Day record=s.get(day);
            if(record!=null)for(Rule r:capped.values())
                if(r.limits.day>0)quotas.floor(r.packageName,day,record.apps.getOrDefault(r.packageName,0L));
            queueScan();
        });
    }
    static void refreshUsageAccess(){if(isConnected())connected.syncUsage(true);}
    private Rule quotaPlan; // also used to revalidate a confirmed quota exit's optional stop
    private final Map<String,Rule> capped=new HashMap<>();
    private void quotaRules(){
        java.util.HashSet<String> old=new java.util.HashSet<>(capped.keySet());
        capped.clear();for(Rule r:enrolled)if(!essential.contains(r.packageName)){
            if(r.limits.timed())capped.put(r.packageName,r);
            if(r.limits.session>0)old.remove(r.packageName);
        }
        for(String pkg:old)quotas.clearRound(pkg);
    }
    private void pauseQuota(){if(quotas!=null)quotas.focus(null);main.removeCallbacks(quotaTick);}
    private final Map<String, FeedbackBasket.Round> rounds = new HashMap<>();
    private int feedbackCount, lastChoice;
    private String lastEntry = "none", lastFeedbackPackage;
    private long visibleSince = -1, lastEarlyLockUp = -1;
    private NativeTarget activeNote;
    private int activeKind, countdownSecond, activeComposite, lastComposite;
    private boolean iconBound;
    private static final int ACTION_NOTE=-1,DESKTOP_NOTE=-2;
    private final ExperienceSchedule desktopLoading=new ExperienceSchedule(random);
    private NativeTarget sequencePolicy;
    private Pad sequencePad;
    private int sequenceMask;
    private long sequenceStarted,sequenceDue;
    private boolean sequencePulse;
    private final Runnable sequenceTick=this::updateDesktopSequence;
    private final IconIntervention iconSession=new IconIntervention();
    private Pad actionPad;
    private long sleepAtIconTap;
    private int lastActionMode=-1, realLocks, intentionalOpens;
    private String lastLockCheck="none";
    private final Runnable iconTick=this::updateIconAction;
    private AudioManager audio;
    private final AudioManager.OnModeChangedListener callMode = mode -> {
        if (mode!=AudioManager.MODE_NORMAL && actionPad!=null && iconSession.mode==Rule.EXIT_LOCK_DELAY) cancelIconAction();
    };
    private final AudioManager.OnModeChangedListener experienceAudioMode = mode -> {
        if (mode!=AudioManager.MODE_NORMAL) interruptForCall();
        queueScan();
    };
    private void interruptForCall() {
        stopWindowProbe(); foreground.leave("call-audio"); main.removeCallbacks(confirmHome);
        leaveExperience(); methodEntry.interrupted(false,SystemClock.elapsedRealtime()); cancelVisit("call-audio");
    }
    private ExperienceEffects experiences;
    private void suspendExperience() { if (experiences!=null) experiences.suspend(); }
    private void leaveExperience() { if (experiences!=null) experiences.leave(); }
    private CurtainFeedback curtain;
    private boolean curtainScheduled;
    private final Runnable curtainEdge=()->{ curtainScheduled=false; scanNow(); };
    private void hideCurtain() { if (curtain!=null) curtain.hide(); }
    private void resetCurtain() {
        if (curtainScheduled) main.removeCallbacks(curtainEdge);
        curtainScheduled=false; hideCurtain();
    }
    private final Runnable dismissNote = this::hideNote;
    private final Runnable tickNote = this::tickCountdown;
    // MainActivity and this bound service share the application's main thread and process.
    private static NativeRestService connected;
    private static Runnable connectionListener;
    static boolean isConnected() { return connected != null && connected.ready; }
    static void setConnectionListener(Runnable listener) { connectionListener = listener; }
    private void reportConnection(boolean live) {
        if(live && connected!=this){
            connected=this;
            if(journal==null)journal=new GuardLog(this);
            journal.add("保护已连接","系统已连接原桌面点按保护");
        }else if(!live && connected==this){
            pauseQuota();connected=null;
            journal.add("保护连接已断开","服务收到系统断开通知");
        }
        if (connectionListener != null) connectionListener.run();
    }
    private Rule confirmedPlanRule;
    private int confirmedPlanReplies;
    private int stopRequests, stopConfirmed;
    private String lastStop="none",lastStopVia="none";
    private final ForegroundReturn foreground = new ForegroundReturn();
    private final ForegroundWindow.Trace windowTrace=new ForegroundWindow.Trace();
    private final TargetWindowProbe windowProbe=new TargetWindowProbe();
    private int targetRechecks;
    private long targetHintAt=-1, verifiedTargetAt=-1;
    private String lastHintTarget, lastVerifiedTarget, lastWindowGate="idle";
    private final Runnable verifyTargetWindow=new Runnable(){
        @Override public void run(){
            if(windowProbe.nextAt()<0)return;
            windowProbe.advance();targetRechecks++;
            // getWindows() may otherwise keep exactly the stale transition which prompted this check.
            if(Build.VERSION.SDK_INT>=33)clearCache();
            scanNow();
            if(windowProbe.nextAt()>=0){
                main.removeCallbacks(this);main.postAtTime(this,windowProbe.nextAt());
            }
        }
    };
    private void armWindowProbe(String candidate,boolean event){
        if(!windowProbe.arm(candidate,SystemClock.uptimeMillis(),event))return;
        targetHintAt=SystemClock.elapsedRealtime();lastHintTarget=candidate;
        main.removeCallbacks(verifyTargetWindow);main.postAtTime(verifyTargetWindow,windowProbe.nextAt());
    }
    private void stopWindowProbe(){main.removeCallbacks(verifyTargetWindow);windowProbe.clear();}
    private final Runnable confirmHome = () -> { if (foreground.pending()) scanNow(); };
    private final ForegroundVisit visit = new ForegroundVisit();
    private final MethodEntry methodEntry = new MethodEntry(visit);
    private boolean deadlineScheduled;
    // Fresh foreground validation even when the target activity emits no further events.
    private final Runnable onDeadline = () -> { deadlineScheduled=false; scanNow(); };
    private boolean waitingForAlignment;
    private final Runnable settleSlide = () -> {
        scanNow(); // animation finished without another window event?
        if (waitingForAlignment) { leaveExperience(); methodEntry.interrupted(true,SystemClock.elapsedRealtime()); cancelVisit("unsettled-window"); }
    };
    private void stopAlignmentCheck() {
        if (waitingForAlignment) main.removeCallbacks(settleSlide);
        waitingForAlignment=false;
    }
    private String lastVisitEnd = "none";
    private void cancelVisit(String reason) {
        if (visit.active()) lastVisitEnd = reason;
        stopAlignmentCheck();
        stopTimerClock();
        visit.leave(SystemClock.elapsedRealtime(),SystemClock.uptimeMillis());
    }
    private void stopTimerClock() {
        resetCurtain();
        if (deadlineScheduled) main.removeCallbacks(onDeadline);
        deadlineScheduled=false;
        visit.suspend(SystemClock.uptimeMillis());
    }
    private List<Rule> enrolled = Collections.emptyList();
    private NativeRestConfig config;
    private NativeIconFinder finder;
    private WindowManager windows;
    private Set<String> essential;
    private SharedPreferences prefs;
    private boolean ready, replayingSwipe, lockInFlight, scanQueued;
    private TextView note;
    private final Runnable scan = () -> { scanQueued = false; sync(); };

    // The first snapshot after waking is often of the keyguard or a half-built launcher tree.
    // Relative to a real wake, not a persistent timer. Every attempt resolves the *live* cell.
    private static final int[] WAKE_RECHECK_MS = {32, 96, 240, 560, 1100, 2000};
    private boolean sessionOpen, offEdgeAlreadySeen, recovering;
    private final WakeProbeEdges edges = new WakeProbeEdges();
    private int retryIndex, wakeRound, retriesThisRound, syncCount;
    private long recoveryStart, observedUnlockAt, lastSyncAt, firstAnchorAfterUnlockMs = -1;
    private final Runnable retry = new Runnable() {
        @Override public void run() {
            if (!recovering || !ready) return;
            retryIndex++; retriesThisRound++;
            sync();
            if (recovering) scheduleRetry();
        }
    };

    private final Runnable coolingEdge=this::scheduleCooling;
    private void scheduleCooling(){
        main.removeCallbacks(coolingEdge);
        if(!ready)return;
        long at=config.nextCoolingAt(); // Also commits matured changes; normal prefs listeners reconcile the active visit.
        if(at>0)main.postDelayed(coolingEdge,Math.max(1,at-System.currentTimeMillis()));
    }
    private void prepareStopIfSelected(){
        if(ready && !config.paused())
            for(Rule r:enrolled)if(r.stopAfterReturn){
                LocalAdb.prepare(this); // reconnect only an existing trusted device, never pair on a rule event
                StopBridge.prepare(this); // existing Shizuku installations
                break;
            }
    }
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (p,k) -> {
        if(CoolingLedger.QUEUE.equals(k)){main.removeCallbacks(coolingEdge);main.post(coolingEdge);return;}
        if (k != null && !"rules".equals(k) && !"paused".equals(k)) return;
        foreground.disarmStop(); // no outstanding privileged dispatch survives a settings edit
        if ("rules".equals(k)) {
            enrolled=config.rules();quotaRules();
            reconcileSelections(deniedRules());
        } else {
            if (k==null){enrolled=config.rules();quotaRules();}
            clearSession();
        }
        prepareStopIfSelected();syncUsage(true);queueScan();
    };
    private final BroadcastReceiver system = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if(Intent.ACTION_TIME_TICK.equals(a)||Intent.ACTION_USER_PRESENT.equals(a))syncUsage(false);
            if(Intent.ACTION_SCREEN_ON.equals(a)||Intent.ACTION_USER_PRESENT.equals(a)||Intent.ACTION_TIME_CHANGED.equals(a))
                scheduleCooling();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                // Broadcasts to a frozen process can be delivered after unlock. If the actual
                // blocked edge already reset this session, do not erase a *new* visible warning.
                boolean openNow = displayUnlocked();
                sample.pause(SystemClock.uptimeMillis());if(!openNow)terminalReceipt(null);
                pauseQuota();
                if (!openNow || !offEdgeAlreadySeen) {
                    sessionOpen = false;
                    suspendSession();
                    endRecovery();
                }
                offEdgeAlreadySeen = false;
                Log.i(TAG, "screen_off session_reset");
                if (openNow) { edges.wakeSignal(); scanNow(); }
                return;
            }
            if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_USER_PRESENT.equals(a)) {
                // A duplicate USER_PRESENT is a hint to recheck, never another first-tap reset.
                if (lockInFlight && displayUnlocked()) {
                    suspendSession(); sessionOpen = false;
                }
                edges.wakeSignal();
                scanNow();
                return;
            }
            if (Intent.ACTION_TIME_CHANGED.equals(a) || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
                pauseQuota();
                // The clock may move without changing this app's effective time rule.
                // Revoke old surfaces, not an unchanged target's monotonic budget.
                suspendSession();
                reconcileSelections(deniedRules());
            }
            if (Intent.ACTION_LOCALE_CHANGED.equals(a)
                    || Intent.ACTION_PACKAGE_ADDED.equals(a) || Intent.ACTION_PACKAGE_REMOVED.equals(a)
                    || Intent.ACTION_PACKAGE_CHANGED.equals(a)) {
                suspendSession();
                finder.reload();
                essential = config.essentialPackages();quotaRules();
            }
            queueScan();
        }
    };
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (ready) { reportConnection(true);syncUsage(true); suspendSession(); sessionOpen = false; scanNow(); return; }
        windows = getSystemService(WindowManager.class);
        curtain = new CurtainFeedback(this); audio=getSystemService(AudioManager.class);
        experiences=new ExperienceEffects(this,this::scanNow,random);
        audio.addOnModeChangedListener(getMainExecutor(),experienceAudioMode);
        config = new NativeRestConfig(this);
        quotas = new QuotaClock(this);
        essential = config.essentialPackages();
        enrolled = config.rules();quotaRules();
        finder = new NativeIconFinder(this);
        prefs = getSharedPreferences(NativeRestConfig.PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(changed);
        IntentFilter screen = new IntentFilter();
        for (String a : new String[]{Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT,
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_PACKAGES_SUSPENDED,
                Intent.ACTION_PACKAGES_UNSUSPENDED}) screen.addAction(a);
        listen(screen);
        IntentFilter apps = new IntentFilter();
        apps.addAction(Intent.ACTION_PACKAGE_ADDED); apps.addAction(Intent.ACTION_PACKAGE_REMOVED);
        apps.addAction(Intent.ACTION_PACKAGE_CHANGED); apps.addDataScheme("package"); listen(apps);
        ready = true; scheduleCooling();reportConnection(true);prepareStopIfSelected();syncUsage(true);
        Log.i(TAG, "service_connected");
        scanNow();
    }
    private void listen(IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(system, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(system, f);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!ready || connected != this) return;
        int type = event.getEventType();
        CharSequence source=event.getPackageName();
        // An event is a reason to look again through a rotation/launch animation; it cannot
        // identify a safe foreground on its own. A 0% or out-of-schedule rule needs no probe.
        if(source!=null && sessionOpen && !config.paused() && (type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type==AccessibilityEvent.TYPE_WINDOWS_CHANGED)){
            for(Rule r:enrolled)if(r.packageName.contentEquals(source) && !essential.contains(r.packageName)){
                Rule active=r.enforcedAt(Store.now());
                if((active!=null&&active.returnHome)||capped.containsKey(r.packageName))armWindowProbe(r.packageName,true);
                break;
            }
        }
        if(type==AccessibilityEvent.TYPE_WINDOWS_CHANGED && finder.focus==WakeProbeEdges.Focus.HOME
                && (event.getWindowChanges()&(AccessibilityEvent.WINDOWS_CHANGE_FOCUSED|AccessibilityEvent.WINDOWS_CHANGE_ACTIVE))!=0)
            probeDisplayChange();
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Do not scan unrelated screen content. A target's first window event can precede
            // the availability of its root, so allow its own subsequent content/layout event.
            CharSequence pkg = event.getPackageName();
            if (!finder.fromHome(pkg) && !returnConfigured(pkg)) return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED && !pads.isEmpty()) {
            // No interception on coordinates from the outgoing page while the launcher settles.
            clearWindows();
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && finder.fromHome(event.getPackageName())) edges.homeSignal();
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Window focus is time-critical at unlock; content/scroll churn can be coalesced.
            scanNow();
        } else queueScan();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig){
        super.onConfigurationChanged(newConfig);
        if(ready && connected==this){probeDisplayChange();scanNow();}
    }
    private void probeDisplayChange(){
        if(!sessionOpen || config.paused())return;
        for(Rule r:enrolled){
            Rule v=r.enforcedAt(Store.now());
            if((v!=null && v.returnHome || capped.containsKey(r.packageName)) && !essential.contains(r.packageName)){
                armWindowProbe(TargetWindowProbe.DEPARTURE,true);return;
            }
        }
    }
    private void scanNow() {
        if (scanQueued) { main.removeCallbacks(scan); scanQueued = false; }
        sync();
    }
    private void beginRecovery() {
        main.removeCallbacks(retry);
        recovering = true; retryIndex = 0; recoveryStart = SystemClock.uptimeMillis();
        scheduleRetry();
    }
    private void scheduleRetry() {
        main.removeCallbacks(retry);
        if (retryIndex >= WAKE_RECHECK_MS.length) { recovering = false; return; }
        // Sleep is not an alarm: these callbacks never wake the device.
        main.postAtTime(retry, recoveryStart + WAKE_RECHECK_MS[retryIndex]);
    }
    private void endRecovery() { recovering = false; main.removeCallbacks(retry); }
    private void queueScan() {
        if (!ready || scanQueued) return;
        scanQueued = true;
        main.postDelayed(scan, 30);
    }
    private boolean displayUnlocked() {
        return getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private boolean usable() { return ready && connected == this && !config.paused() && displayUnlocked(); }
    private boolean returnConfigured(CharSequence pkg) {
        if (pkg == null) return false;
        for (Rule r : enrolled) if (r.packageName.contentEquals(pkg)) return true;
        return false;
    }
    private List<NativeTarget> deniedRules() {
        if (config.paused()) return Collections.emptyList();
        List<NativeTarget> result = new ArrayList<>();
        int now = Store.now();
        for (Rule saved : enrolled) {
            if (essential.contains(saved.packageName)) continue;
            Rule effective=saved.enforcedAt(now);
            if (effective!=null) result.add(NativeTarget.from(effective,now));
        }
        return result;
    }
    private void reconcileSelections(List<NativeTarget> effective) {
        Map<String,NativeTarget> map=new HashMap<>();
        for(NativeTarget t:effective)map.put(t.packageName,t);
        reconcileSelections(map);
    }
    private void reconcileSelections(Map<String,NativeTarget> current) {
        long now=SystemClock.elapsedRealtime();
        boolean hadTimer=visit.active();
        visit.reconcile(current,now);
        boolean changedSelection=methodEntry.reconcile(current);
        if(changedSelection || hadTimer && !visit.active()) {
            leaveExperience();cancelVisit("rule-change");foreground.leave("rule-change");
        }
    }
    private void sync() {
        quotaFrame=false;sampleFrame=false;
        scanFrame();
        if(!quotaFrame)pauseQuota();
        if(!sampleFrame)sample.pause(SystemClock.uptimeMillis());
    }
    private void scanFrame() {
        if (!ready || connected != this) return;
        syncCount++; lastSyncAt = SystemClock.uptimeMillis();
        if (!displayUnlocked()) {
            terminalReceipt(null);cancelVisit("screen-locked");
            // Observe the actual edge even if the ACTION_SCREEN_OFF receiver hasn't run.
            if (sessionOpen || lockInFlight) {
                sessionOpen = false; offEdgeAlreadySeen = true;
                edges.leftHome(); suspendSession(); endRecovery();
            }
            if (edges.startMissing(getSystemService(PowerManager.class).isInteractive()
                    && !config.paused() && !enrolled.isEmpty(), recovering)) beginRecovery();
            return;
        }
        if (!sessionOpen) {
            sessionOpen = true; wakeRound++; retriesThisRound = 0;
            observedUnlockAt = lastSyncAt; firstAnchorAfterUnlockMs = -1;
            beginRecovery();
        }
        if (replayingSwipe || lockInFlight) return;
        List<NativeTarget> rules = deniedRules();
        Map<String,NativeTarget> current = new HashMap<>();
        for (NativeTarget r : rules) current.put(r.packageName, r);
        reconcileSelections(current);
        rounds.keySet().retainAll(current.keySet());
        Map<String,NativeIconFinder.Hit> hits = finder.visible(rules,!capped.isEmpty());
        edges.observe(finder.focus);
        if (activeNote != null && (!current.containsKey(activeNote.packageName)
                || finder.focus != WakeProbeEdges.Focus.HOME)) hideNote();
        int before = pads.size();
        for (Pad pad : new ArrayList<>(pads.values())) {
            NativeIconFinder.Hit hit = hits.get(pad.rule.packageName);
            boolean changedNotice = hit != null && !pad.rule.samePolicy(current.get(pad.rule.packageName));
            if (hit == null || changedNotice || !pad.box.equals(hit.bounds)) {
                if (actionPad==pad) cancelIconAction();
                remove(pad); pads.remove(pad.rule.packageName);
                if (iconBound && activeNote != null && pad.rule.packageName.equals(activeNote.packageName)) hideNote();
            }
        }
        for (Map.Entry<String,NativeIconFinder.Hit> e : hits.entrySet()) {
            if (pads.containsKey(e.getKey())) continue;
            Rect r = e.getValue().bounds;
            Pad pad = new Pad(current.get(e.getKey()), r);
            if (place(pad, r.left, r.top, r.width(), r.height(), true)) pads.put(e.getKey(), pad);
        }
        if (actionPad!=null && iconSession.mode==Rule.EXIT_LOCK_DELAY && !quietLockSurface()) cancelIconAction();
        if(sequencePolicy!=null && (!sequencePolicy.samePolicy(current.get(sequencePolicy.packageName))
                || sequencePad!=null && pads.get(sequencePolicy.packageName)!=sequencePad || !quietLockSurface()))hideNote();
        checkForegroundReturn(current);
        if (!pads.isEmpty() && firstAnchorAfterUnlockMs < 0)
            firstAnchorAfterUnlockMs = SystemClock.uptimeMillis() - observedUnlockAt;
        int icons = 0;
        for (NativeTarget r : rules) if (r.interceptIcon) icons++;
        boolean missing = pads.size() < icons;
        if (edges.startMissing(missing, recovering)) beginRecovery();
        if (!missing) endRecovery();
        if (before != pads.size()) Log.i(TAG, "visible_anchors=" + pads.size());
    }
    /** Called after the original-desktop finder identifies the current focused surface. */
    private void checkForegroundReturn(Map<String,NativeTarget> current) {
        if (config.paused()) { stopWindowProbe();leaveExperience(); methodEntry.reset(); cancelVisit("paused"); visit.reset(); foreground.leave("paused"); return; }
        Rule selected;
        boolean any = false;
        
        for (NativeTarget r : current.values()) {
            if (r.policy.returnHome) any=true;
            
        }
        if (!any && capped.isEmpty()) { sample.leave();stopWindowProbe();leaveExperience(); methodEntry.clearVisit(); cancelVisit("outside-rule"); foreground.leave("outside-rule"); return; }
        if(windowProbe.target()!=null && !TargetWindowProbe.DEPARTURE.equals(windowProbe.target()) && !current.containsKey(windowProbe.target()) && !capped.containsKey(windowProbe.target()))stopWindowProbe();
        if (finder.focus == WakeProbeEdges.Focus.HOME) {
            lastWindowGate="home";

            // Recents and the app drawer can share Home's package. An intervening panel
            // invalidates the privilege even if the real workspace returns before binder delivery.
            if(foreground.confirmingStop() && !finder.isActualWorkspace(ForegroundWindow.focused(this,windows,methodEntry)))
                foreground.leftConfirmedHome();
            observedHome(current);
            return;
        }
        foreground.leftConfirmedHome(); // even an uncertain departure invalidates a pending binder callback
        ForegroundWindow win = ForegroundWindow.focused(this, windows, methodEntry,windowTrace,current);
        lastWindowGate=windowTrace.reason();
        if(!capped.isEmpty()){
            ForegroundWindow observed=win.pkg!=null?win:ForegroundWindow.forQuota(this,windows,methodEntry);
            observeSample(observed);if(checkQuota(observed))return;
        }else observeSample(win);
        if(win.pkg==null && (win==ForegroundWindow.TRANSITION || win==ForegroundWindow.SLIDING || win.launchTransition()))
            armWindowProbe(windowTrace.target()!=null?windowTrace.target():
                    foreground.pending()?foreground.lastRequested:null,false);
        if (win.lostReason != null) {
            if(win.launchTransition() && foreground.armedForHome() && !onlyReturnWindows(foreground.lastRequested))
                foreground.disarmStop();
            if(!win.launchTransition()){
                stopWindowProbe();
                foreground.leave(win.lostReason);main.removeCallbacks(confirmHome);
            }
            leaveExperience(); methodEntry.interrupted(win.launchTransition(),SystemClock.elapsedRealtime()); cancelVisit(win.lostReason); return;
        } // confirmed system/multiwindow surface
        if (win == ForegroundWindow.SAME_APP_PANEL) {
            foreground.disarmStop();
            methodEntry.cancelAppTerminal();
            // Suspend effects throughout a same-app dialog; don't interpret its dismissal as
            // a new attempt, including a prior PASS. Wait for verified fullscreen to act.
            stopAlignmentCheck(); suspendExperience(); stopTimerClock(); return;
        }
        if (win == ForegroundWindow.SLIDING) {
            suspendExperience(); stopTimerClock();
            if (!waitingForAlignment) {
                waitingForAlignment=true;
                float scale=Settings.Global.getFloat(getContentResolver(),Settings.Global.TRANSITION_ANIMATION_SCALE,1f);
                main.postDelayed(settleSlide,(long)(2000 * Math.max(1f,scale)));
            }
            return; // retain the same draw, but require full alignment before acting
        }
        if (win == ForegroundWindow.TRANSITION) { suspendExperience(); stopTimerClock(); return; } // no request, retain an existing app visit until focus is known

        stopAlignmentCheck();
        // The focused window can change between the icon lookup and this fresh read.
        // Classify the real launcher's window before treating unselected apps as a cancellation.

        if (finder.fromHome(win.pkg)) { observedHome(current); return; }
        // A cached outgoing root can briefly name a third owner during HOME animation.
        // Before cancelling the receipt, re-read focus. Never grant a stop across that gap.
        if(foreground.pending() && !foreground.attempted(win.pkg) && Build.VERSION.SDK_INT>=33){
            foreground.disarmStop();
            clearCache();
            ForegroundWindow fresh=ForegroundWindow.focused(this,windows,methodEntry);
            if(!java.util.Objects.equals(win.pkg,fresh.pkg)){queueScan();return;}
        }
        foreground.observedApp(win.pkg);
        NativeTarget target=current.get(win.pkg);
        selected=target==null?null:target.policy;
        if (selected==null || !selected.returnHome) {
            lastVerifiedTarget=null;
            if(SystemClock.elapsedRealtime()-targetHintAt>150)stopWindowProbe();
            leaveExperience();
            methodEntry.clearVisit(); cancelVisit("ineligible-app"); foreground.leave("ineligible-app"); return;
        }
        if (!usable() || !getSystemService(UserManager.class).isUserUnlocked()) {
            leaveExperience(); cancelVisit("unavailable"); return;
        }
        if(audio.getMode()!=AudioManager.MODE_NORMAL) {
            lastWindowGate="call-audio";interruptForCall();return;
        }
        long now=SystemClock.elapsedRealtime(), up=SystemClock.uptimeMillis();
        if(win.cover!=ForegroundWindow.Cover.CLEAR)methodEntry.cancelAppTerminal();
        if(!win.cover.canCountViewing()){
            // Do not even draw the probability ticket for a target hidden behind a system
            // surface; no haptic, new volume lease or navigation is due in this state.
            stopTimerClock();suspendExperience();return;
        }
        if(windowProbe.target()!=null)stopWindowProbe();
        if(!win.pkg.equals(lastVerifiedTarget)){lastVerifiedTarget=win.pkg;verifiedTargetAt=now;}
        lastWindowGate="verified";
        if(foreground.attempted(win.pkg)){
            // A redirect is allowed once. A HOME dispatch, in contrast, is complete only after
            // the launcher is observed. Same draw/budget/receipt: never re-roll or replay cues.
            if(confirmedPlanRule==null || ForegroundVisit.samePolicy(confirmedPlanRule,selected)){
                boolean exhausted=foreground.exhausted();
                if(foreground.retryDue(win.pkg,now))dispatchHome(-1,win,true);
                else if(!exhausted && foreground.exhausted())
                    journal.add(selected.label+" · 未退回桌面","重新确认后应用仍在前台；系统未完成退回");
                return;
            }
            foreground.leave("rule-change");main.removeCallbacks(confirmHome);
        }
        int passesBefore=methodEntry.passes;
        int picked=methodEntry.foregroundMask(selected,win.width,win.height,now,random);
        logNewProbabilityMiss(selected,passesBefore);
        if (picked<=0 || !selected.byEffects() && picked==EffectPlan.GATE) {
            leaveExperience(); cancelVisit("intentional-open"); return;
        }
        boolean flexible=selected.byEffects();
        if(flexible && (EffectPlan.replies(picked)&FeedbackBasket.HAPTIC)!=0 && methodEntry.takePulse())pulseFor(selected);
        int episode=flexible?EffectPlan.methods(picked):picked;
        int content=episode & MethodPool.EXPERIENCES, timed=EffectPlan.clock(selected,episode);
        if (timed!=0) {
            if (!visit.matches(selected,picked)) cancelVisit("changed-target");
            visit.enter(selected,picked,win.id,now,up,random);
        } else cancelVisit("daily-effect");
        // Already used up on a previous visit: never reapply loading/freeze/mute as a grace period.
        if (content!=0 && (timed==0 || !visit.rejoinedExhausted)){
            experiences.showGroup(win.pkg,content,win.cover.canDrawOverContent());
            int applied=experiences.newlyApplied();
            if(applied!=0){
                ArrayList<String> names=new ArrayList<>();
                for(DailyExitMethod m:DailyExitMethod.displayOrder(true))if((applied&MethodPool.bit(m.mode))!=0)names.add(m.label);
                journal.add(selected.label+" · 使用中干预","已生效："+String.join("＋",names));
            }
        }
        else leaveExperience();
        int ending=episode&~MethodPool.EXPERIENCES;
        boolean genuineHomeEnding=true;
        if(content!=0 && timed==0){
            // A probability hit is not permission for an unlimited visit. Once the chosen
            // intervention has actually been perceived, the default destination is HOME.
            ending=EffectPlan.afterPresentation(ending,win.cover.canCountViewing(),win.cover.canDrawOverContent(),
                    !experiences.contentUnavailable(),experiences.pastLoading()&&experiences.contentExperienced());
            if(ending==0){if(win.cover.canDrawOverContent())scheduleDeadline(250);return;}
            if((ending&EffectPlan.TERMINALS)!=0){
                Intent alternative=null;
                if(ending==EffectPlan.REDIRECT){
                    String dest=selected.sharedBasket.alternate;
                    if(config.validAlternative(win.pkg,dest))alternative=config.launchIntent(dest);
                }
                // Resolve the destination first; never authorize a later global action with a frame
                // captured before PackageManager or another app's scheduled rule was consulted.
                ForegroundWindow fresh=ForegroundWindow.focused(this,windows,methodEntry);
                if(!methodEntry.fromIcon() || !win.pkg.equals(fresh.pkg) || fresh.cover!=ForegroundWindow.Cover.CLEAR
                        || !usable() || audio.getMode()!=AudioManager.MODE_NORMAL){methodEntry.cancelAppTerminal();return;}
                leaveExperience();
                if(ending==EffectPlan.LOCK){
                    long duration=used(win.pkg);if(requestLock(selected.label,null)){terminal(selected,null,duration);return;}
                }else{
                    if(alternative!=null)try{startActivity(alternative);terminal(selected,selected.sharedBasket.alternate,used(win.pkg));confirmedPlanRule=null;foreground.begin(win.pkg,MethodPool.single(ending),selected.daily);intentionalOpens++;journal.add(selected.label+" · 自动改道","使用中干预后，已发起打开备选应用");return;}
                    catch(ActivityNotFoundException|SecurityException unavailable){}
                    journal.add(selected.label+" · 未能改道","备选应用不可用");
                }
                genuineHomeEnding=false;
                ending=EffectPlan.HOME; // an unavailable ending does not silently grant unlimited use
            }
        }
        int chosen=MethodPool.single(timed!=0?timed:ending);
        Rule original=selected;
        if(!flexible)selected=selected.forMode(chosen);
        if (timed!=0) {
            long left=visit.remaining(up);
            if (left>0) {
                if (selected.curtain && Rule.delayed(chosen) && win.cover.canDrawOverContent() && (content&MethodPool.bit(Rule.EXIT_NARROW))==0
                        && (content==0 || experiences.pastLoading())) {
                    if (left<=CurtainFeedback.WINDOW_MS) curtain.show(visit.due);
                    else if (!curtainScheduled) { curtainScheduled=true; main.postDelayed(curtainEdge,left-CurtainFeedback.WINDOW_MS); }
                } else resetCurtain();
                scheduleDeadline(left);return;
            }
            // Only the very first uninterrupted presentation can need the old 0-second grace.
            // On a resumed visit the saved remainder is always the full allowance.
            if (!visit.rejoined && content!=0 && win.cover.canDrawOverContent() && !experiences.contentUnavailable()
                    && !(experiences.pastLoading() && experiences.contentExperienced())) {
                resetCurtain();scheduleDeadline(250);return;
            }
        }
        // win was resolved in this scan, not captured at schedule time. Never apply an
        // expired visit's request to another application or a transition frame.
        // Freeze the actual observed time before requesting HOME; never count the return animation.
        long measured = ((flexible?EffectPlan.replies(picked):selected.feedbackMask) & FeedbackBasket.DURATION) != 0 ? visit.elapsed(SystemClock.uptimeMillis()) : -1;
        long observedUse=used(win.pkg);
        leaveExperience(); cancelVisit("home-request");
        if (foreground.begin(win.pkg,chosen,selected.daily)) {
            exitSample=observedUse;quotaPlan=null;confirmedPlanRule=original;
            confirmedPlanReplies=flexible?EffectPlan.replies(picked)&~FeedbackBasket.HAPTIC:0;
            if(!methodEntry.fromIcon())confirmedPlanReplies&=~FeedbackBasket.LOCK;
            dispatchHome(measured,win,genuineHomeEnding);
        }
    }
    private boolean checkQuota(ForegroundWindow win){
        Rule cap=capped.get(win.pkg);
        if(cap!=null && usable() && win.cover.canCountViewing() && audio.getMode()==AudioManager.MODE_NORMAL){
            quotaFrame=true;quotas.focus(win.pkg,cap.limits.session>0);
            QuotaBudget b=quotas.view(win.pkg);
            int reason=b.reason(cap.limits,System.currentTimeMillis());
            main.removeCallbacks(quotaTick);
            if(reason!=0){
                leaveExperience();cancelVisit("usage-limit");
                if(!foreground.attempted(win.pkg)){
                    exitSample=used(win.pkg);quotaPlan=cap;confirmedPlanRule=cap;foreground.begin(win.pkg,reason,false);
                    dispatchHome(-1,win,true);
                }else if(foreground.retryDue(win.pkg,SystemClock.elapsedRealtime()))dispatchHome(-1,win,true);
                return true;
            }
            // Exact deadline (independent of probability) plus a short persistence checkpoint.
            main.postDelayed(quotaTick,Math.max(1,Math.min(5000,b.next(cap.limits))));
        }
        return false;
    }
    /** During the exit animation only the returning target and its launcher's windows may coexist. */
    private boolean onlyReturnWindows(String pkg){
        for(AccessibilityWindowInfo w:getWindows()){
            if(w.getDisplayId()!=Display.DEFAULT_DISPLAY || w.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
            AccessibilityNodeInfo root=Build.VERSION.SDK_INT>=33?w.getRoot(0):w.getRoot();
            if(root==null)return false;
            try{
                CharSequence owner=root.getPackageName();
                if(owner==null || root.getWindowId()!=w.getId() || !pkg.contentEquals(owner) && !finder.fromHome(owner))return false;
            }finally{if(Build.VERSION.SDK_INT<33)root.recycle();}
        }
        return true;
    }
    private boolean stillSelected(Rule plan){
        if(plan==null || essential.contains(plan.packageName))return false;
        Rule saved=config.find(plan.packageName);
        if(saved==null || !saved.stopAfterReturn)return false;
        if(plan==quotaPlan)return saved.limits.timed() && saved.limits.toString().equals(plan.limits.toString());
        Rule active=saved.enforcedAt(Store.now());
        return active!=null && active.returnHome && ForegroundVisit.samePolicy(plan,active);
    }
    private boolean stopContextSafe(){
        return usable() && sessionOpen && !replayingSwipe && !lockInFlight
                && getSystemService(UserManager.class).isUserUnlocked() && audio.getMode()==AudioManager.MODE_NORMAL;
    }
    private ForegroundReturn.StopGate stopGate(ForegroundWindow captured,boolean genuineHome,long epoch){
        if(!genuineHome || !stillSelected(confirmedPlanRule))return ForegroundReturn.StopGate.OFF;
        if(epoch<0)return ForegroundReturn.StopGate.UNAVAILABLE;
        if(!stopContextSafe())return ForegroundReturn.StopGate.UNVERIFIED;
        ForegroundWindow actual=confirmedPlanRule==quotaPlan?ForegroundWindow.forQuota(this,windows,methodEntry):ForegroundWindow.focused(this,windows,methodEntry);
        return confirmedPlanRule.packageName.equals(actual.pkg) && captured.id==actual.id && actual.cover.canCountViewing()
                ? ForegroundReturn.StopGate.ARMED : ForegroundReturn.StopGate.UNVERIFIED;
    }
    private void dispatchHome(long measured,ForegroundWindow win,boolean genuineHome){
        long epoch=genuineHome && confirmedPlanRule!=null && confirmedPlanRule.stopAfterReturn?StopBridge.readyEpoch():-1;
        ForegroundReturn.StopGate stop=stopGate(win,genuineHome,epoch);
        long now=SystemClock.elapsedRealtime();
        boolean accepted=performGlobalAction(GLOBAL_ACTION_HOME);
        foreground.requested(accepted,now,measured,stop,epoch);
        // Both rejection and an accepted request whose target remains up need verification.
        main.removeCallbacks(confirmHome);main.postDelayed(confirmHome,foreground.nextCheckDelay());
    }
    private boolean sameSafeArrival(ForegroundReturn.Receipt receipt,Rule plan){
        if(!foreground.canStop(receipt,SystemClock.elapsedRealtime(),StopBridge.readyEpoch()) || !stopContextSafe() || !stillSelected(plan))
            return false;
        if(Build.VERSION.SDK_INT>=33)clearCache();
        return finder.isActualWorkspace(ForegroundWindow.focused(this,windows,methodEntry));
    }
    private void stopAfterReturn(ForegroundReturn.Receipt receipt,Rule plan){
        if(plan==null || receipt.stop==ForegroundReturn.StopGate.OFF)return;
        if(receipt.stop!=ForegroundReturn.StopGate.ARMED || !sameSafeArrival(receipt,plan)){
            lastStopVia="none";lastStop=receipt.stop==ForegroundReturn.StopGate.UNAVAILABLE?"unavailable":"not-verified";
            journal.add(plan.label+" · 未停止后台",receipt.stop==ForegroundReturn.StopGate.UNAVAILABLE
                    ?"退回时停止后台运行的授权未就绪":"未能确认同一次退出已回到原桌面");
            foreground.finishStop(receipt);return;
        }
        stopRequests++;lastStop="requested";lastStopVia=StopBridge.source(receipt.bridgeEpoch);
        StopBridge.stop(this,receipt.pkg,receipt.bridgeEpoch,receipt.expiresAt(),()->sameSafeArrival(receipt,plan),result->{
            foreground.finishStop(receipt);
            if(result.status==StopBridge.Result.Status.CONFIRMED)stopConfirmed++;
            lastStop=result.status.code;lastStopVia=StopBridge.source(receipt.bridgeEpoch);
            journal.add(plan.label+" · "+result.status.title,result.detail);
        });
    }
    private void scheduleDeadline(long delay) {
        if (!deadlineScheduled) { deadlineScheduled=true; main.postDelayed(onDeadline,delay); }
    }
    private void observedHome(Map<String,NativeTarget> current) {
        sample.leave();lastVerifiedTarget=null;
        leaveExperience(); methodEntry.home(SystemClock.elapsedRealtime());
        cancelVisit("home");
        ForegroundReturn.Receipt returned=foreground.home(SystemClock.elapsedRealtime());
        if (returned==null) return;
        stopWindowProbe();main.removeCallbacks(confirmHome);
        NativeTarget target=current.get(returned.pkg);
        Rule plan=confirmedPlanRule;confirmedPlanRule=null;
        stopAfterReturn(returned,plan);
        if(returned.mode==QuotaBudget.SESSION || returned.mode==QuotaBudget.DAY || returned.mode==QuotaBudget.REST){
            Rule saved=capped.get(returned.pkg);
            if(saved!=null){

                String why=returned.mode==QuotaBudget.DAY?"今日额度已用完":returned.mode==QuotaBudget.REST?"本轮休息尚未结束":"单次使用上限已到";
                journal.add(saved.label+" · 限时退出",why+" · 已确认回到桌面");
                recordExit(saved,why,exitSample);quotas.returned(returned.pkg,returned.mode,saved.limits);
                android.widget.Toast.makeText(this,saved.label+"："+why,android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if(plan!=null)recordExit(plan,plan.daily?"日常规则退出":"休息时段退出",exitSample);
        if(plan!=null && plan.byEffects()){
            if(target!=null&&ForegroundVisit.samePolicy(plan,target.policy)){
                if(returned.mode==Rule.EXIT_WAIT_OPEN)
                    journal.add(target.label+" · 延迟打开后退出","已确认回到桌面");
                // A second-tap lock is neither an automatic lock nor an external-entry receipt.
                // Rebind its warning only to this same, newly verified genuine Home icon.
                if((confirmedPlanReplies&FeedbackBasket.LOCK)!=0){
                    Pad anchor=pads.get(returned.pkg);
                    if(anchor!=null && verified(anchor)!=null)episodeReceipt(target,anchor,confirmedPlanReplies,returned.visitMillis,true);
                    else journal.add(target.label+" · 已退回桌面","原图标不可见，未显示再点锁屏");
                }else if(confirmedPlanReplies!=0 || returned.mode!=Rule.EXIT_WAIT_OPEN)
                    episodeReceipt(target,null,confirmedPlanReplies,returned.visitMillis);
            }
            return;
        }
        if (target!=null && target.policy.daily==returned.daily && (target.policy.hasMode(returned.mode) || target.policy.sharedRest() && returned.mode==Rule.EXIT_NOW)) feedback(target.selected(returned.mode),null,returned.visitMillis);
    }
    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter w, String[] args) {
        w.println("AppDodge: ready="+ready+" overlays="+pads.size()+" locking="+lockInFlight
                +" replaying="+replayingSwipe+" scan="+(finder==null?"not connected":finder.lastScan));
        w.println("wakeRound=" + wakeRound + " firstAnchorAfterUnlockMs=" + firstAnchorAfterUnlockMs
                + " wakeRetries=" + retriesThisRound + " recovering=" + recovering);
        long now=SystemClock.uptimeMillis();
        if (finder != null) w.println("syncs="+syncCount+" lastSyncAgoMs="+(now-lastSyncAt)
                +" queries="+finder.scans+" lastQueryAgoMs="+(now-finder.lastScanAt)
                +" emptyRefreshes="+finder.emptyRefreshes+" refreshRecovered="+finder.refreshRecovered);
        if(ready && args!=null && java.util.Arrays.asList(args).contains("rules")){
            int minute=Store.now();
            for(Rule saved:enrolled){
                Rule active=saved.enforcedAt(minute);Pad pad=pads.get(saved.packageName);
                w.println("ruleTarget="+saved.packageName+" activePeriod="+(active==null?"outside-off":active.daily?"outside":"rest")
                        +" effectiveChance="+(active==null?0:active.chance)+" dayChance="+saved.dailyChance
                        +" stopAfterReturn="+saved.stopAfterReturn+" periods="+saved.periods.text()+" iconBounds="+(pad==null?"none":pad.box.toShortString()));
            }
        }
        if (ready && args != null && java.util.Arrays.asList(args).contains("windows")) {
            // Opt-in, read-only diagnostic. Never use an active root alone as a safe touch target.
            android.accessibilityservice.AccessibilityServiceInfo info=getServiceInfo();
            AccessibilityNodeInfo active=getRootInActiveWindow();
            android.view.accessibility.AccessibilityWindowInfo win=active==null?null:active.getWindow();
            w.println("liveWindows="+getWindows().size()+" activeIsHome="+(active!=null&&finder.fromHome(active.getPackageName()))
                    +" activeWindowId="+(active==null?-1:active.getWindowId())+" canSeeWindow="+(win!=null)
                    +" windowFocused="+(win!=null&&win.isFocused())+" infoFlags="+(info==null?-1:info.flags));
        }
        long authority=StopBridge.readyEpoch();
        w.println("backgroundStopRequests="+stopRequests+" backgroundStopConfirmed="+stopConfirmed+" lastBackgroundStop="+lastStop
                +" lastStopVia="+lastStopVia+" stopConnection="+StopBridge.source(authority));
        w.println("returnRequests="+foreground.requests+" observedHome="+foreground.observedReturns
                +" receiptOutcome="+foreground.outcome+" lastRequested="+foreground.lastRequested+" accepted="+foreground.lastAccepted
                +" lastReturned="+foreground.lastReturned+" lastReturnAgeMs="
                +(foreground.lastReturned==null ? -1 : SystemClock.elapsedRealtime()-foreground.lastReturnedAt));
        w.println("windowGate="+lastWindowGate+" probeTarget="+windowProbe.target()+" targetRechecks="+targetRechecks
                +" lastHintTarget="+lastHintTarget+" hintElapsedMs="+targetHintAt+" verifiedElapsedMs="+verifiedTargetAt
                +" homeRequestElapsedMs="+foreground.lastRequestAt+" homeConfirmedElapsedMs="+foreground.lastReturnedAt
                +" homeRetries="+foreground.retries+" unconfirmedHome="+foreground.unconfirmed);
        w.println("foregroundWindow="+windowTrace);
        w.println("delayTarget="+visit.target+" delayWindow="+visit.window+" delayMode="+visit.mode
                +" sampledSeconds="+visit.seconds+" delayRemainingMs="+visit.remaining(SystemClock.uptimeMillis())
                +" parkedRemainingMs="+visit.parkedRemaining(visit.lastTarget,SystemClock.elapsedRealtime())+" delayResumes="+visit.resumes+" exhaustedResumes="+visit.exhaustedResumes+" rejoinedExhausted="+visit.rejoinedExhausted+" delayDraws="+visit.draws+" delayEndedBy="+lastVisitEnd+" lastDelayTarget="+visit.lastTarget+" lastSampledSeconds="+visit.lastSeconds);
        w.println("feedbackCount="+feedbackCount+" lastFeedback="+FeedbackBasket.name(lastChoice)
                +" desktopSequence="+sequenceMask+" compositeMask="+lastComposite+" feedbackEntry="+lastEntry+" feedbackPackage="+lastFeedbackPackage
                +" visibleFeedback="+FeedbackBasket.name(activeKind)+" countdownSecond="+countdownSecond);
        w.println("methodDraws="+methodEntry.draws+" lastMethod="+MethodEntry.singleMode(methodEntry.lastMask)+" methodMask="+methodEntry.lastMask+" methodEntry="+methodEntry.lastSource+" chancePasses="+methodEntry.passes);
        w.println("dailyDecisions="+methodEntry.dailyDecisions+" dailyHits="+methodEntry.dailyHits+" decisionTarget="+methodEntry.decisionTarget
                +" decisionChance="+methodEntry.lastChance+" decisionResult="+methodEntry.decisionResult+" decisionEntry="+methodEntry.decisionEntry);
        if (experiences!=null) w.println(experiences.diagnostic());
        w.println("iconMode="+iconSession.mode+" iconTarget="+(actionPad==null ? "none" : actionPad.rule.packageName)
                +" iconDueInMs="+(actionPad==null ? -1 : Math.max(0,iconSession.due-SystemClock.elapsedRealtime()))
                +" lastIconMode="+lastActionMode+" lockSurface="+lastLockCheck+" acceptedLocks="+realLocks+" intentionalOpens="+intentionalOpens);
        if (config != null) w.println("configured="+config.rules().size()+" paused="+config.paused()
                +" interactive="+getSystemService(PowerManager.class).isInteractive()
                +" keyguard="+getSystemService(KeyguardManager.class).isKeyguardLocked());
    }
    @Override public void onInterrupt() { clearSession(); }
    @Override public boolean onUnbind(Intent intent) {
        reportConnection(false); clearSession();
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        ready = false; if (connected == this) reportConnection(false);
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(changed);
            unregisterReceiver(system);
        }
        if (audio!=null) audio.removeOnModeChangedListener(experienceAudioMode);
        main.removeCallbacksAndMessages(null);
        clearSession();
        super.onDestroy();
    }
    private void clearSession() {
        pendingTerminal=null;sample.leave();
        pauseQuota();
        suspendSession();
        if(experiences!=null) experiences.reset();
        methodEntry.reset(); visit.reset();
    }
    private void suspendSession() {
        stopWindowProbe();lastVerifiedTarget=null;confirmedPlanRule=null;leaveExperience();
        methodEntry.endSurface(SystemClock.elapsedRealtime());
        cancelVisit("surface-ended"); foreground.leave("surface-ended"); main.removeCallbacks(confirmHome);
        for (FeedbackBasket.Round r : rounds.values()) r.release();
        lockInFlight = false; replayingSwipe = false;
        main.removeCallbacks(lockTimeout);
        clearWindows();
    }
    private void clearWindows() {
        cancelIconAction();
        for (Pad pad : pads.values()) remove(pad);
        pads.clear(); hideNote();
    }
    private void hideNote() {
        main.removeCallbacks(tickNote); main.removeCallbacks(dismissNote);
        main.removeCallbacks(sequenceTick);sequencePolicy=null;sequencePad=null;sequenceMask=0;
        desktopLoading.leave(SystemClock.elapsedRealtime());
        Pad armed = activeNote == null ? null : pads.get(activeNote.packageName);
        activeKind = 0; countdownSecond = 0; activeNote = null; iconBound = false; visibleSince = lastEarlyLockUp = -1;
        if (note != null) { remove(note); note = null; }
        if (armed != null) armed.invalidate();
    }
    private void remove(View v) {
        if (v.isAttachedToWindow() || v.getParent() != null) windows.removeViewImmediate(v);
    }

    private final class Pad extends View {
        final NativeTarget rule; final Rect box;
        final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        float downX, downY; long touchBegan; boolean single, moved, quickMove;
        Pad(NativeTarget rule, Rect r) {
            super(NativeRestService.this);
            this.rule = rule; box = new Rect(r);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        private long localBlockUntil;
        void showLocalBlock(){
            localBlockUntil=SystemClock.uptimeMillis()+1600;
            invalidate();postInvalidateDelayed(1600);
        }
        @Override protected void onDraw(Canvas c) {
            if(SystemClock.uptimeMillis()<localBlockUntil && !showsLock(this)){
                // Confine the receipt to this already-verified icon surface. Do not place an
                // extra window near a system panel that currently makes other actions unsafe.
                float h=Math.min(dp(23),getHeight()),w=Math.min(dp(48),getWidth());
                float l=(getWidth()-w)/2f,t=(getHeight()-h)/2f;
                pen.setStyle(Paint.Style.FILL);pen.setColor(0xff253d37);c.drawRoundRect(l,t,l+w,t+h,h/2,h/2,pen);
                pen.setTextAlign(Paint.Align.CENTER);pen.setTextSize(Math.min(dp(13),h*.62f));pen.setColor(0xffebf7ee);
                c.drawText("已拦住",getWidth()/2f,t+h/2-(pen.ascent()+pen.descent())/2,pen);return;
            }
            if (!showsLock(this)) return;
            // Tiny contrasting padlock leaves the real icon, its artwork and its label visible.
            float side = Math.min(dp(26), Math.min(getWidth(), getHeight()) / 2f);
            float x = getWidth() - side - dp(3), y = dp(3);
            c.save(); c.translate(x,y); c.scale(side/26f,side/26f);
            pen.setStyle(Paint.Style.FILL); pen.setColor(0xff253d37);
            c.drawRoundRect(0,0,26,26,9,9,pen);
            pen.setColor(0xffebf7ee); pen.setStrokeWidth(1.8f); pen.setStyle(Paint.Style.STROKE);
            c.drawRoundRect(9.5f,5.5f,16.5f,16,3.5f,3.5f,pen);
            pen.setStyle(Paint.Style.FILL); c.drawRoundRect(7,11,19,20,2,2,pen);
            c.restore();
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float x=e.getRawX(), y=e.getRawY();
            int action=e.getActionMasked();
            long duration=e.getEventTime()-e.getDownTime();
            if (action == MotionEvent.ACTION_DOWN) {
                touchBegan=SystemClock.elapsedRealtime(); downX=x; downY=y; single=true; moved=false; quickMove=false; return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) single=false;
            if (!single) return true;
            float dx=x-downX, dy=y-downY;
            if (!moved && Math.hypot(dx,dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                moved=true; quickMove=duration < ViewConfiguration.getLongPressTimeout();
            }
            if (action != MotionEvent.ACTION_UP) return true;
            single=false;
            // Check the actual focused page, selected rule, time, and current icon cell again.
            NativeIconFinder.Hit hit = verified(this);
            if (hit == null) { queueScan(); return true; }
            if (moved) {
                if (quickMove && duration < 650 && Math.hypot(dx,dy) >= dp(42))
                    forwardSwipe(this, x, y, duration);
                return true;
            }
            if (duration >= ViewConfiguration.getLongPressTimeout()) {
                hit.node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                queueScan(); return true;
            }
            if (box.contains((int)x,(int)y)) tap(this,hit,e.getDownTime());
            return true;
        }
    }
    private NativeIconFinder.Hit verified(Pad pad) {
        if (lockInFlight || replayingSwipe || pads.get(pad.rule.packageName) != pad || !usable()) return null;
        List<NativeTarget> active=deniedRules();
        boolean same=false;
        for (NativeTarget r:active) if (r.packageName.equals(pad.rule.packageName) && r.samePolicy(pad.rule)) { same=true; break; }
        if (!same) return null;
        NativeIconFinder.Hit hit = finder.visible(active).get(pad.rule.packageName);
        return hit != null && pad.box.equals(hit.bounds) ? hit : null;
    }
    private boolean boundTo(Pad pad) {
        return iconBound && note!=null && activeNote!=null && activeNote.packageName.equals(pad.rule.packageName);
    }
    private boolean showsLock(Pad pad) { return boundTo(pad) && activeKind==FeedbackBasket.LOCK; }
    // PASS can be inherited across icon/foreground events. Only a fresh failed draw increments passes.
    private void logNewProbabilityMiss(Rule rule,int before){
        if(rule.daily && rule.chance>0 && methodEntry.passes!=before)
            journal.add(rule.label+" · 本次正常使用","日常触发概率 "+rule.chance+"%，这次未触发");
    }
    private void tap(Pad pad, NativeIconFinder.Hit hit,long downUptime) {
        long now=SystemClock.elapsedRealtime();
        if(sequencePolicy!=null) {
            if(sequencePolicy.packageName.equals(pad.rule.packageName))return;
            hideNote();
        }
        if (actionPad!=null) {
            if (actionPad!=pad || sleptSinceTap() || iconSession.phase(now)==IconIntervention.EXPIRED) cancelIconAction();
            else {
                if (iconSession.phase(now)==IconIntervention.READY && pad.touchBegan>=iconSession.due) launchFrom(pad,false);
                return; // repeat taps during an existing action never reroll
            }
        }
        if (showsLock(pad)) {
            if (FeedbackBasket.canConfirmLock(activeKind,note.isShown(),visibleSince,lastEarlyLockUp,downUptime)
                    && quietLockSurface()) requestLock(pad);
            else lastEarlyLockUp=SystemClock.uptimeMillis();
            return;
        }
        FeedbackBasket.Round round=rounds.get(pad.rule.packageName);
        // An ongoing response still covers this icon after a return from another entry or
        // a haptic without a popup. Removing a visual isn't permission to roll the method again.
        if (round!=null && round.responding(now)) return;
        if (!methodEntry.mayTap(pad.rule.packageName,now)) return;
        int passesBefore=methodEntry.passes;
        if(pad.rule.policy.byEffects()){
            int episode=methodEntry.episodeIcon(pad.rule.policy,now,random);
            logNewProbabilityMiss(pad.rule.policy,passesBefore);
            if(episode==MethodEntry.NONE)return;
            int methods=episode>0?EffectPlan.methods(episode):0, receipts=episode>0?EffectPlan.replies(episode):0;
            boolean pulse=(receipts&FeedbackBasket.HAPTIC)!=0;
            if(pulse)pulseFor(pad.rule.policy);
            if(EffectPlan.opensFromIcon(episode)){
                hideNote();methodEntry.launchingEpisode(pad.rule.policy,episode,now,pulse);
                if(!hit.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
                    methodEntry.clearVisit();render(pad.rule,pad,FeedbackBasket.NOTICE,new FeedbackReceipt("这个图标目前打不开，留在桌面。",pad.rule.label),0);
                }
                return;
            }
            int chosen=MethodPool.single(methods);
            iconBlock(pad,"日常拦截");
            if(chosen==Rule.EXIT_REDIRECT){openAlternative(pad);return;}
            if(Rule.iconAction(chosen)){
                tapAction(pad,chosen,episode);
                if(actionPad==pad && iconSession.mode==chosen)journal.add(pad.rule.label+" · "+DailyExitMethod.forMode(chosen).label,"原图标未打开");
                return;
            }
            episodeReceipt(pad.rule,pad,receipts&~FeedbackBasket.HAPTIC,-1);return;
        }
        int mode=methodEntry.icon(pad.rule.policy,now,random);
        logNewProbabilityMiss(pad.rule.policy,passesBefore);
        if (mode==-1) return;
        if(mode==Rule.EXIT_LOAD && pad.rule.policy.sharedRest()) {
            iconBlock(pad,"休息时段拦截");desktopSequence(pad.rule,pad,SharedBasket.HOME_LOAD);return;
        }
        if (mode==Rule.EXIT_REDIRECT) {
            iconBlock(pad,"自动改道");journal.block(pad.rule,false,"自动改道");openAlternative(pad); return;
        }
        if (Rule.iconAction(mode)) { iconBlock(pad,DailyExitMethod.forMode(mode).label);journal.block(pad.rule,false,DailyExitMethod.forMode(mode).label);tapAction(pad,mode,0); return; }
        if (mode==MethodEntry.PASS || (MethodPool.bit(mode)&MethodPool.EXPERIENCES)!=0 || Rule.delayed(mode) || mode==Rule.EXIT_NOW && !pad.rule.policy.sharedRest() && pad.rule.policy.exitMode!=Rule.EXIT_NOW) {
            // The user tapped this exact icon. Use the launcher's real click action, not guessed
            // coordinates or an alternate package/Activity. Bind the choice before windows change.
            hideNote();
            methodEntry.launching(pad.rule.policy,mode,now);
            if (!hit.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                methodEntry.clearVisit();
                render(pad.rule,pad,FeedbackBasket.NOTICE,new FeedbackReceipt("这个图标目前打不开，留在桌面。",pad.rule.label),0);
            }
            return;
        }
        iconBlock(pad,pad.rule.policy.daily?"日常拦截":"休息时段拦截");feedback(pad.rule.selected(mode),pad,-1); // old single NOW rules still intercept on the icon
    }
    private void requestLock(Pad pad){requestLock(pad.rule.label,pad);}
    private boolean requestLock(String label,Pad pad) {
        lockInFlight = true;
        boolean accepted = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        Log.i(TAG, "lock_requested accepted=" + accepted);
        if (accepted) {
            journal.add(label+" · 锁屏","系统已接受锁屏请求");
            realLocks++;
            offEdgeAlreadySeen = true;
            clearWindows();
            main.postDelayed(lockTimeout, 2400);
        } else {
            lockInFlight = false;
            journal.add(label+" · 未锁屏","系统未接受锁屏请求");
            if(pad!=null)render(pad.rule,pad,FeedbackBasket.NOTICE,new FeedbackReceipt("这次没有锁屏，已拦住这次打开。",label),0);
        }
        return accepted;
    }
    // No backup launch paths, coordinate replays, global edits or full-screen prompts.
    private void tapAction(Pad pad,int mode,int episode) {
        long now=SystemClock.elapsedRealtime(); lastActionMode=mode;
        if (mode==Rule.EXIT_LOCK_NOW) {
            if (quietLockSurface() && verified(pad)!=null) requestLock(pad);
            return;
        }
        if (mode==Rule.EXIT_LOCK_DELAY && !quietLockSurface()) return;
        if (mode==Rule.EXIT_ALTERNATIVE && !config.validAlternative(pad.rule.packageName,pad.rule.alternate)) {
            render(pad.rule,pad,FeedbackBasket.NOTICE,new FeedbackReceipt("备选应用目前不可用，留在桌面。",pad.rule.label),0); return;
        }
        sleepAtIconTap=SystemClock.elapsedRealtime()-SystemClock.uptimeMillis();
        actionPad=pad; iconSession.start(mode,episode,now,random);
        if (mode==Rule.EXIT_LOCK_DELAY) audio.addOnModeChangedListener(getMainExecutor(),callMode);
        updateIconAction();
    }
    private void cancelIconAction() {
        if (actionPad==null) return;
        if (iconSession.mode==Rule.EXIT_LOCK_DELAY) audio.removeOnModeChangedListener(callMode);
        actionPad=null; iconSession.cancel(); main.removeCallbacks(iconTick);
        if (activeKind==ACTION_NOTE) hideNote();
    }
    private boolean windowOf(View view,int id) {
        if (view==null || !view.isAttachedToWindow()) return false;
        AccessibilityNodeInfo node=view.createAccessibilityNodeInfo();
        if (node==null) return false;
        try { return id>=0 && node.getWindowId()==id; }
        finally { if (Build.VERSION.SDK_INT<33) node.recycle(); }
    }
    boolean ownsEffectWindow(int id) { return experiences!=null && experiences.ownsWindow(id) || curtain!=null && windowOf(curtain.surface(),id); }
    boolean ownsWindow(int id) {
        if (ownsEffectWindow(id)) return true;
        if (windowOf(note,id)) return true;
        for (Pad pad:pads.values()) if (windowOf(pad,id)) return true;
        return false;
    }
    private boolean lockSurface(String reason) { lastLockCheck=reason; return false; }
    private boolean quietLockSurface() {
        if (!usable() || !getSystemService(UserManager.class).isUserUnlocked()
                || finder.focus!=WakeProbeEdges.Focus.HOME) return lockSurface("not-home");
        if (audio.getMode()!=AudioManager.MODE_NORMAL) return lockSurface("call-audio");
        android.view.WindowMetrics metrics=windows.getMaximumWindowMetrics();
        Rect body=new Rect(metrics.getBounds());
        Insets bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        body.inset(bars.left,bars.top,bars.right,bars.bottom);
        if (body.isEmpty()) return lockSurface("no-display");
        int apps=0;
        for (AccessibilityWindowInfo w:getWindows()) {
            if (w.getDisplayId()!=Display.DEFAULT_DISPLAY) continue;
            if (w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION) {
                if (++apps!=1 || !w.isFocused() || w.isInPictureInPictureMode()) return lockSurface("other-focus");
                Rect bounds=new Rect(); w.getBoundsInScreen(bounds);
                if (!bounds.contains(body)) return lockSurface("partial-app");
                AccessibilityNodeInfo root=w.getRoot();
                if (root==null) return lockSurface("no-root");
                try { if (!finder.fromHome(root.getPackageName())) return lockSurface("other-app"); }
                finally { if (Build.VERSION.SDK_INT<33) root.recycle(); }
            } else if (w.getType()!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || !ownsWindow(w.getId())) {
                if (w.isFocused() || w.isActive()) return lockSurface("system-focus");
                Region r=new Region(); w.getRegionInScreen(r);
                if (r.op(body,Region.Op.INTERSECT)) return lockSurface("covered-type-"+w.getType()); // heads-up, shade, IME or other occluder
            }
        }
        lastLockCheck=apps==1 ? "clear" : "no-app"; return apps==1;
    }
    private boolean sleptSinceTap() {
        // Handler deadlines pause during deep sleep; never use a consent from before that sleep
        // even if a delayed SCREEN_OFF broadcast hasn't run yet.
        return SystemClock.elapsedRealtime()-SystemClock.uptimeMillis()-sleepAtIconTap>80;
    }
    private void updateIconAction() {
        Pad pad=actionPad; if (pad==null) return;
        if (sleptSinceTap() || verified(pad)==null || (iconSession.mode==Rule.EXIT_LOCK_DELAY && !quietLockSurface())) {
            cancelIconAction(); return;
        }
        long now=SystemClock.elapsedRealtime(); int phase=iconSession.phase(now);
        if (phase==IconIntervention.EXPIRED) { cancelIconAction(); return; }
        if (phase==IconIntervention.LOCK_DUE) { cancelIconAction(); requestLock(pad); return; }
        String title,sub;
        if (phase==IconIntervention.OFFER) {
            try {
                android.content.pm.ApplicationInfo info=getPackageManager().getApplicationInfo(pad.rule.alternate,0);
                title="换个去处"; sub="打开「"+info.loadLabel(getPackageManager())+"」  →";
            } catch (android.content.pm.PackageManager.NameNotFoundException gone) { cancelIconAction(); return; }
        } else if (phase==IconIntervention.READY) { title="再点图标打开"; sub=iconSession.episode!=0?"进入后 1～2 秒退回桌面":"已经等了 10 秒"; }
        else if (iconSession.mode==Rule.EXIT_LOCK_DELAY) { title=iconSession.seconds(now)+" 秒后锁屏"; sub="点此取消，或离开桌面"; }
        else { title="先等 "+iconSession.seconds(now)+" 秒"; sub="等完后，再点一次才会打开"; }
        if (note==null || activeKind!=ACTION_NOTE || activeNote!=pad.rule) {
            render(pad.rule,pad,ACTION_NOTE,new FeedbackReceipt(title,sub),0);
            if (note==null) { cancelIconAction(); return; }
        }
        setMessage(note,title,sub);
        long delay=phase==IconIntervention.WAIT ? iconSession.due-now-(iconSession.seconds(now)-1)*1000L : iconSession.expires-now;
        main.removeCallbacks(iconTick); main.postDelayed(iconTick,Math.max(1,delay));
    }
    private void launchFrom(Pad pad,boolean alternate) {
        if (actionPad!=pad || sleptSinceTap() || verified(pad)==null
                || iconSession.phase(SystemClock.elapsedRealtime())!=(alternate?IconIntervention.OFFER:IconIntervention.READY)) {
            cancelIconAction(); return;
        }
        if (alternate) { cancelIconAction(); openAlternative(pad); return; }
        String dest=pad.rule.packageName;
        Intent start=config.launchIntent(dest);
        int episode=iconSession.episode;
        cancelIconAction(); // consumes the single permission before attempting to launch
        if (start!=null) {
            long now=SystemClock.elapsedRealtime();
            if(episode!=0)methodEntry.launchingEpisode(pad.rule.policy,episode,now,(EffectPlan.replies(episode)&FeedbackBasket.HAPTIC)!=0);
            else methodEntry.launching(pad.rule.policy,Rule.EXIT_WAIT_OPEN,now);
            try { startActivity(start); intentionalOpens++; return; }
            catch (ActivityNotFoundException | SecurityException unavailable) { methodEntry.clearVisit(); }
        }
        render(pad.rule,pad,FeedbackBasket.NOTICE,new FeedbackReceipt("这个应用目前打不开，留在桌面。",pad.rule.label),0);
    }
    private void openAlternative(Pad pad) { openAlternative(pad,pad.rule.alternate); }
    private void openAlternative(Pad pad,String dest) {
        Intent start=verified(pad)!=null && config.validAlternative(pad.rule.packageName,dest) ? config.launchIntent(dest) : null;
        if (start!=null) try { hideNote(); startActivity(start); intentionalOpens++;journal.add(pad.rule.label+" · 自动改道","已发起打开备选应用"); return; }
        catch (ActivityNotFoundException | SecurityException unavailable) { /* disabled between check and launch */ }
        render(pad.rule,pad,FeedbackBasket.NOTICE,new FeedbackReceipt("备选应用目前不可用，留在桌面。",pad.rule.label),0);
    }
    private final Runnable lockTimeout = () -> {
        if (!lockInFlight) return;
        suspendSession(); queueScan();
    };
    /** An external entry arrives here only after the target was seen, HOME requested, then HOME seen. */
    private void feedback(NativeTarget rule, Pad pad, long visitMillis) {
        boolean external = pad == null;
        long now = SystemClock.elapsedRealtime();
        FeedbackBasket.Round round = rounds.computeIfAbsent(rule.packageName, ignored -> new FeedbackBasket.Round());
        if(rule.policy.hardCombo()) {
            if(round.responding(now)){if(external)journal.block(rule,true,"");return;}
            int picked=rule.policy.sharedBasket.drawRestResponses(random,!external);
            if((picked&(SharedBasket.HOME_LOAD|SharedBasket.HOME_REDIRECT))!=0){
                desktopSequence(rule,pad,picked);return;
            }
            journal.block(rule,external,GuardLog.responses(picked));
            if(picked==0){if(pad!=null && verified(pad)!=null)pad.showLocalBlock();return;}
            round.startComposite((picked&FeedbackBasket.COUNTDOWN)!=0,now);
            boolean pulse=(picked&FeedbackBasket.HAPTIC)!=0 && doublePulse();
            int seconds=round.seconds(now);
            lastComposite=picked;feedbackCount++;lastChoice=seconds>0?FeedbackBasket.COUNTDOWN:
                    (picked&FeedbackBasket.NOTICE)!=0?FeedbackBasket.NOTICE:FeedbackBasket.HAPTIC;
            lastEntry=external?"return":"icon"; lastFeedbackPackage=rule.packageName;
            if(picked==FeedbackBasket.HAPTIC){
                FeedbackReceipt msg=FeedbackReceipt.forChoice(lastChoice,rule.label,rule.notice,external,-1,0,pulse);
                if(msg==null)hideNote();else render(rule,pad,lastChoice,msg,0);
                return;
            }
            render(rule,pad,lastChoice,combinedMessage(rule,picked,seconds,pulse),seconds);
            activeComposite=picked;compositePulsed=pulse;
            return;
        }
        if(external && rule.policy.sharedRest() && rule.policy.sharedBasket.restIconMethods()==MethodPool.bit(Rule.EXIT_LOAD)){
            desktopSequence(rule,null,SharedBasket.HOME_LOAD);return;
        }
        int mask = visitMillis >= 0 ? rule.feedbackMask : rule.feedbackMask & ~FeedbackBasket.DURATION;
        if(rule.policy.sharedBasket!=null && external)mask&=~FeedbackBasket.LOCK;
        lastComposite=0;
        if (mask == 0){journal.block(rule,external,"");if(pad!=null && verified(pad)!=null)pad.showLocalBlock();return;} // cannot show a duration without this confirmed visit's measurement
        int kind = round.draw(mask, external, now, random);
        if (kind == 0) {
            if(external)journal.block(rule,true,"");
            // A confirmed return can interrupt the visual surface for an ongoing countdown.
            // Restore its actual remainder without another draw or another five seconds.
            int seconds = round.seconds(now);
            if (seconds > 0 && (activeNote == null || !rule.packageName.equals(activeNote.packageName)))
                render(rule,pad,FeedbackBasket.COUNTDOWN,FeedbackReceipt.countdown(seconds),seconds);
            return;
        }
        journal.block(rule,external,GuardLog.responses(kind));
        feedbackCount++; lastChoice = kind;
        lastEntry = external ? "return" : "icon"; lastFeedbackPackage = rule.packageName;
        boolean pulseSent=kind==FeedbackBasket.HAPTIC && doublePulse();
        if (kind == FeedbackBasket.LOCK && pad != null) pad.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        int seconds=round.seconds(now);
        FeedbackReceipt message=FeedbackReceipt.forChoice(kind,rule.label,rule.notice,external,visitMillis,seconds,pulseSent);
        if(message==null){hideNote();return;} // tactile choice: no duplicate visual reminder
        render(rule,pad,kind,message,seconds);
    }
    /** Only a genuine icon tap can authorize a later redirect. The spinner is a small,
     * non-touchable Home receipt, never a cover over another app. Leaving Home abandons it. */
    private void desktopSequence(NativeTarget choice,Pad pad,int mask) {
        if(!quietLockSurface()){
            journal.add(choice.label+(pad==null?" · 已退回桌面":" · 原图标未打开"),
                    "系统窗口占用桌面，未执行："+GuardLog.responses(mask));
            if(pad!=null && verified(pad)!=null)pad.showLocalBlock();
            return;
        }
        NativeTarget active=null;
        for(NativeTarget t:deniedRules())if(t.packageName.equals(choice.packageName)){active=t;break;}
        if(active==null||!active.policy.sharedRest()||pad!=null&&verified(pad)==null)return;
        if(pad==null)mask&=~SharedBasket.HOME_REDIRECT;
        if(mask==0)return;
        long now=SystemClock.elapsedRealtime();
        boolean pulse=(mask&FeedbackBasket.HAPTIC)!=0&&doublePulse();
        boolean load=(mask&SharedBasket.HOME_LOAD)!=0,count=(mask&FeedbackBasket.COUNTDOWN)!=0;
        lastComposite=mask;feedbackCount++;lastChoice=count?FeedbackBasket.COUNTDOWN:(mask&FeedbackBasket.NOTICE)!=0?FeedbackBasket.NOTICE:(mask&FeedbackBasket.HAPTIC);
        lastEntry=pad==null?"return":"icon";lastFeedbackPackage=active.packageName;
        if(!load&&!count&&(mask&FeedbackBasket.NOTICE)==0 && (mask&SharedBasket.HOME_REDIRECT)!=0 && pulse){
            if(pad!=null&&quietLockSurface())openAlternative(pad,active.policy.sharedBasket.alternate);
            return;
        }
        boolean spinnerOnly=load&&!count&&(mask&FeedbackBasket.NOTICE)==0&&((mask&FeedbackBasket.HAPTIC)==0||pulse);
        render(active,pad,DESKTOP_NOTE,new FeedbackReceipt("",""),0,spinnerOnly);
        if(note==null){journal.add(active.label+" · 桌面反馈未显示","未取得提示窗口");return;}
        journal.add(active.label+(pad==null?" · 已退回桌面":" · 原图标未打开"),"桌面反馈已显示"+((mask&SharedBasket.HOME_REDIRECT)!=0?" · 等待改道":""));
        sequencePolicy=active;sequencePad=pad;sequenceMask=mask;sequenceStarted=now;sequencePulse=pulse;
        if(load){
            desktopLoading.begin(active.packageName,Rule.EXIT_LOAD,now);
            android.graphics.drawable.Drawable spin=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleSmall)
                    .getIndeterminateDrawable().mutate();
            spin.setTint(0xffebf7ee);spin.setBounds(0,0,dp(22),dp(22));
            // Inline keeps this compact receipt within its measured height even beside a countdown.
            note.setCompoundDrawables(spin,null,null,null);note.setCompoundDrawablePadding(dp(8));
            if(spin instanceof android.graphics.drawable.Animatable)((android.graphics.drawable.Animatable)spin).start();
            sequenceDue=desktopLoading.nextEdge(now);
        }else sequenceDue=now+(count?FeedbackBasket.COUNT_MS:(mask&FeedbackBasket.NOTICE)!=0?2400:250);
        updateDesktopSequence();
    }
    private void updateDesktopSequence(){
        NativeTarget policy=sequencePolicy;Pad pad=sequencePad;int picked=sequenceMask;
        if(policy==null)return;
        long now=SystemClock.elapsedRealtime();
        // Also recheck at the deadline in case no window event was delivered.
        boolean current=false;
        for(NativeTarget t:deniedRules())if(policy.samePolicy(t)&&t.packageName.equals(policy.packageName)){current=true;break;}
        if(!current || !quietLockSurface() || pad!=null && verified(pad)==null){hideNote();return;}
        if(now>=sequenceDue){
            boolean redirect=(picked&SharedBasket.HOME_REDIRECT)!=0 && pad!=null;
            hideNote();
            if(redirect && quietLockSurface())openAlternative(pad,policy.policy.sharedBasket.alternate);
            return;
        }
        int seconds=(picked&FeedbackBasket.COUNTDOWN)==0?0:(int)Math.max(0,(sequenceStarted+FeedbackBasket.COUNT_MS-now+999)/1000);
        countdownSecond=seconds;
        String title=seconds>0?seconds+" 秒":(picked&FeedbackBasket.NOTICE)!=0?policy.notice:"";
        String sub=seconds>0&&(picked&FeedbackBasket.NOTICE)!=0?policy.notice:"";
        if((picked&FeedbackBasket.HAPTIC)!=0&&!sequencePulse)sub=sub.isEmpty()?"系统触感不可用":sub+" · 系统触感不可用";
        if(title.isEmpty() && sub.isEmpty()) { if(note.length()!=0)note.setText(""); }
        else { String text=title+"\n"+sub;
            if(!text.contentEquals(note.getText()))setMessage(note,title,sub);
        }
        long next=seconds>0?Math.min(sequenceDue,sequenceStarted+FeedbackBasket.COUNT_MS-(seconds-1)*1000L):sequenceDue;
        main.removeCallbacks(sequenceTick);main.postDelayed(sequenceTick,Math.max(1,next-now));
    }
    private boolean compositePulsed;
    private FeedbackReceipt combinedMessage(NativeTarget rule,int picked,int seconds,boolean pulse){
        boolean notice=(picked&FeedbackBasket.NOTICE)!=0,missing=(picked&FeedbackBasket.HAPTIC)!=0&&!pulse;
        String extra=missing?"系统触感不可用":"";
        if(seconds>0)return new FeedbackReceipt(seconds+" 秒",notice?rule.notice+(missing?" · "+extra:""):missing?extra:"提示倒数，结束不会放行");
        return new FeedbackReceipt(rule.notice,missing?extra:"已拦住这次打开");
    }
    private void pulseFor(Rule r){
        boolean sent=doublePulse();journal.add(r.label+" · 轻振两下",sent?"已请求系统触感":"系统触感不可用");
    }
    private void episodeReceipt(NativeTarget target,Pad pad,int choice,long measured){
        episodeReceipt(target,pad,choice,measured,pad==null);
    }
    private void episodeReceipt(NativeTarget target,Pad pad,int choice,long measured,boolean returned){
        boolean external=pad==null;journal.block(target,returned,GuardLog.responses(choice));
        if(choice==0)return;
        FeedbackBasket.Round round=rounds.computeIfAbsent(target.packageName,k->new FeedbackBasket.Round());
        int kind=round.draw(choice,external,SystemClock.elapsedRealtime(),random);if(kind==0)return;
        lastChoice=kind;feedbackCount++;lastEntry=returned?"return":"icon";lastFeedbackPackage=target.packageName;lastComposite=0;
        int seconds=round.seconds(SystemClock.elapsedRealtime());
        FeedbackReceipt msg=FeedbackReceipt.forChoice(kind,target.label,target.notice,returned,measured,seconds,false);
        if(msg!=null)render(target,pad,kind,msg,seconds);
    }
    private boolean doublePulse() {
        if (Settings.System.getInt(getContentResolver(),Settings.System.HAPTIC_FEEDBACK_ENABLED,1)==0) return false;
        Vibrator motor = getSystemService(VibratorManager.class).getDefaultVibrator();
        if (!motor.hasVibrator()) return false;
        // System touch usage preserves the OS touch-haptics / intensity policy.
        long[] timings = {0,20,85,20};
        VibrationEffect effect = motor.hasAmplitudeControl()
                ? VibrationEffect.createWaveform(timings,new int[]{0,78,0,78},-1)
                : VibrationEffect.createWaveform(timings,-1);
        AudioAttributes attr = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        motor.vibrate(effect,attr);
        return true;
    }
    private void tickCountdown() {
        TextView old = note;
        if (old == null || activeKind != FeedbackBasket.COUNTDOWN || activeNote == null) return;
        scanNow(); // recheck permission, real HOME, time window and anchor before drawing the next second
        if (old != note || activeNote == null || !usable()) { if (old == note) hideNote(); return; }
        FeedbackBasket.Round r = rounds.get(activeNote.packageName);
        long now = SystemClock.elapsedRealtime();
        int second = r == null ? 0 : r.seconds(now);
        if (second == 0) { hideNote(); return; }
        countdownSecond = second;
        FeedbackReceipt message=activeComposite!=0?combinedMessage(activeNote,activeComposite,second,compositePulsed):FeedbackReceipt.countdown(second);
        setMessage(note,message.title,message.detail);
        main.postDelayed(tickNote,Math.max(1,r.end()-now-(second-1)*1000L));
    }
    private void setMessage(TextView label, String title,String subtitle) {
        android.text.SpannableString styled=new android.text.SpannableString(title+"\n"+subtitle);
        styled.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),0,title.length(),0);
        label.setText(styled);
    }
    private void render(NativeTarget rule, Pad pad, int kind, FeedbackReceipt message, int seconds) {
        render(rule,pad,kind,message,seconds,false);
    }
    private void render(NativeTarget rule, Pad pad, int kind, FeedbackReceipt message, int seconds,boolean spinnerOnly) {
        hideNote();
        activeComposite=0; activeNote = rule; activeKind = kind; iconBound = pad != null; countdownSecond = seconds;
        TextView label = new TextView(this) {
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (kind == FeedbackBasket.LOCK && this == note && visibleSince < 0) visibleSince = SystemClock.uptimeMillis();
            }
        };
        setMessage(label,message.title,message.detail);
        label.setTextSize(15); label.setTextColor(0xfff5fcf6);
        label.setGravity(Gravity.CENTER); label.setPadding(dp(12),dp(4),dp(12),dp(4)); label.setMaxLines(3);
        GradientDrawable bg=new GradientDrawable(); bg.setColor(0xff253d37); bg.setCornerRadius(dp(16));
        label.setBackground(bg); label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        Rect display=windows.getCurrentWindowMetrics().getBounds();
        int width=Math.min(dp(spinnerOnly?46:270),display.width()-dp(20));
        label.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int height=spinnerOnly?dp(46):Math.max(dp(64),label.getMeasuredHeight());
        int x=display.centerX()-width/2, y=display.bottom-height-dp(90);
        if (pad != null) {
            Rect b=pad.box; x=Math.max(display.left+dp(10),Math.min(b.centerX()-width/2,display.right-width-dp(10)));
            y=b.top-height-dp(10); if (y < display.top+dp(36)) y=b.bottom+dp(10);
        }
        note=label;
        boolean actionable=kind==ACTION_NOTE && pad!=null && (iconSession.mode==Rule.EXIT_ALTERNATIVE || iconSession.mode==Rule.EXIT_LOCK_DELAY);
        if (actionable) label.setOnClickListener(v -> {
            if (v!=note || actionPad!=pad) return;
            if (iconSession.mode==Rule.EXIT_ALTERNATIVE) launchFrom(pad,true); else cancelIconAction();
        });
        if (!place(label,x,y,width,height,actionable)) { hideNote(); return; }
        if (pad != null) pad.invalidate();
        if (kind == ACTION_NOTE || kind==DESKTOP_NOTE) return;
        if (kind == FeedbackBasket.COUNTDOWN) {
            FeedbackBasket.Round r = rounds.get(rule.packageName);
            long delay = r.end()-SystemClock.elapsedRealtime()-(seconds-1)*1000L;
            main.postDelayed(tickNote,Math.max(1,delay));
        } else if (kind != FeedbackBasket.LOCK) main.postDelayed(dismissNote,2400);
        else if (rule.feedbackMask != FeedbackBasket.LOCK) main.postDelayed(dismissNote,5000);
    }
    private boolean place(View v,int x,int y,int width,int height,boolean touchable) {
        int f=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!touchable) f|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,f,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.LEFT|Gravity.TOP; p.x=x; p.y=y;
        p.setFitInsetsTypes(0);
        try { windows.addView(v,p); }
        catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException e) {
            Log.w(TAG,"overlay_unavailable"); return false;
        }
        v.post(()->{
            if (!v.isAttachedToWindow()) return;
            int[] actual=new int[2]; v.getLocationOnScreen(actual);
            if (actual[0]!=x || actual[1]!=y) { p.x+=x-actual[0]; p.y+=y-actual[1]; windows.updateViewLayout(v,p); }
        });
        return true;
    }
    private void forwardSwipe(Pad pad, float x, float y, long duration) {
        // Replay only the user's finished, clearly displaced single-finger swipe. Never replay
        // taps, held drags, or multi-touch. Validate the same launcher cell after removing the pads.
        final float sx = pad.downX, sy = pad.downY;
        replayingSwipe = true; clearWindows();
        main.post(() -> {
            if (!replayingSwipe) return;
            if (!usable()) { swipeDone(); return; }
            NativeIconFinder.Hit hit=finder.visible(deniedRules()).get(pad.rule.packageName);
            if (hit == null || !pad.box.equals(hit.bounds)) { swipeDone(); return; }
            Path path=new Path(); path.moveTo(sx,sy); path.lineTo(x,y);
            GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(
                    path,0,Math.max(110,Math.min(duration,340)))).build();
            boolean accepted=dispatchGesture(g,new GestureResultCallback(){
                @Override public void onCompleted(GestureDescription g) { swipeDone(); }
                @Override public void onCancelled(GestureDescription g) { swipeDone(); }
            },main);
            if (!accepted) swipeDone();
        });
    }
    private void swipeDone() { replayingSwipe=false; queueScan(); }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
}
