package dev.local.appdodge;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.accessibility.AccessibilityManager;
import java.time.ZoneId;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Selected daily rules, including their optional foreground and edit protections. */
final class NativeRestConfig {
    static final String PREFS = "native_rest_v1";
    // Separate from PREFS: changing the manager must not reset the service's active intervention.
    static final String MANAGEMENT_PREFS = "management_window_v1";
    private final Context context;
    private final SharedPreferences prefs;
    private final SharedPreferences management;
    private final Store store;
    private final CoolingLedger ledger;
    private final CoolingLedger.Clock clock;
    static final String LOCK_PREFIX = "rule_lock_";
    private static final String BOOT = LOCK_PREFIX+"boot", ELAPSED = LOCK_PREFIX+"elapsed", WALL = LOCK_PREFIX+"wall";

    NativeRestConfig(Context context) {this(context,null);}
    NativeRestConfig(Context context,CoolingLedger.Clock clock) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        management = this.context.getSharedPreferences(MANAGEMENT_PREFS, Context.MODE_PRIVATE);
        store = new Store(prefs);
        this.clock=clock!=null?clock:new CoolingLedger.Clock(){
            public long wall(){return System.currentTimeMillis();}
            public long elapsed(){return android.os.SystemClock.elapsedRealtime();}
            public int boot(){return NativeRestConfig.this.boot();}
        };
        ledger=new CoolingLedger(prefs,this.clock,(start,end)->writeManagement(new ManagementWindow(start,end,-1,-1,0,0)));
    }
    List<Rule> rules() { ledger.settle();return store.rules(); }
    boolean paused() { ledger.settle();return prefs.getBoolean("paused", false); }
    Rule find(String pkg) { ledger.settle();return store.find(pkg); }

    boolean coolingEnabled(){return ledger.enabled();}
    boolean setCoolingEnabled(boolean enabled){
        if(!canEdit())return false;
        boolean active=coolingEnabled();
        return ledger.submit(CoolingLedger.MODE,enabled?"enable":"disable",enabled?"开启规则防改":"关闭规则防改",
                String.valueOf(enabled),active==enabled);
    }
    List<CoolingChange> pendingChanges(){return ledger.pending();}
    /** The user's agenda includes the manager's previously approved next opening as well as
     * 24-hour changes. Viewing it never imports a local-time commitment into the cooling queue. */
    List<CoolingChange> scheduledChanges(){
        List<CoolingChange> result=new ArrayList<>(pendingChanges());
        ManagementWindow current=readManagement(clock.wall());
        if(current.effectiveAt==0)return result;
        for(CoolingChange c:result)
            if(CoolingLedger.WINDOW.equals(c.key)&&c.effectiveAt<=current.effectiveAt)return result;
        int start=current.pendingStart,end=current.pendingEnd;
        String title=start==ManagementWindow.OFF?"停用管理时段":
                "管理时段改为 "+Rule.time(start)+"–"+(end<start?"次日 ":"")+Rule.time(end);
        result.add(new CoolingChange("window_handover","window_handover",title,
                CoolingLedger.windowValue(start,end),current.effectiveAt));
        result.sort(Comparator.comparingLong(c->c.effectiveAt));
        return result;
    }
    CoolingChange pendingRule(String pkg){return ledger.pending(CoolingLedger.ruleKey(pkg));}
    boolean cancelChange(String key){return canEdit()&&ledger.cancel(key);}
    long nextCoolingAt(){return ledger.nextAt();}
    Rule draftRule(String pkg){
        CoolingChange p=pendingRule(pkg);
        if(p!=null&&("add".equals(p.kind)||"edit".equals(p.kind))){
            try{return Store.decode(new JSONObject(p.value));}
            catch(JSONException e){throw new IllegalStateException("无法读取预约规则",e);}
        }
        return find(pkg);
    }
    boolean returnsHome(String pkg) {
        Rule r = find(pkg);
        return r != null && r.returnHome;
    }

    private ManagementWindow readManagement(long now) {
        int off=ManagementWindow.OFF;
        ManagementWindow stored=new ManagementWindow(management.getInt("start",off),management.getInt("end",off),
                management.getInt("next_start",off),management.getInt("next_end",off),management.getLong("effective_at",0),
                management.getLong("old_until",0));
        ManagementWindow current=stored.at(now);
        // Promote on first observation so a later clock correction cannot resurrect an old schedule.
        if(current!=stored)writeManagement(current);
        return current;
    }
    private boolean writeManagement(ManagementWindow w) {
        SharedPreferences.Editor e=management.edit();
        if(w.start==ManagementWindow.OFF)e.remove("start").remove("end");
        else e.putInt("start",w.start).putInt("end",w.end);
        if(w.oldUntil==0)e.remove("old_until");else e.putLong("old_until",w.oldUntil);
        if(w.effectiveAt==0)e.remove("next_start").remove("next_end").remove("effective_at");
        else e.putInt("next_start",w.pendingStart).putInt("next_end",w.pendingEnd).putLong("effective_at",w.effectiveAt);
        return e.commit();
    }
    private ManagementWindow.State projected(ManagementWindow old,long now,int start,int end,long when) {
        return old.scheduledState(now,ZoneId.systemDefault(),start,end,when);
    }
    ManagementWindow.State managementWindow() {
        CoolingChange waiting=ledger.pending(CoolingLedger.WINDOW);
        long now=clock.wall();ManagementWindow old=readManagement(now);
        if(waiting==null)return old.state(now,ZoneId.systemDefault());
        try{
            JSONObject v=new JSONObject(waiting.value);
            return projected(old,now,v.getInt("start"),v.getInt("end"),waiting.effectiveAt);
        }catch(JSONException e){throw new IllegalStateException("无法读取预约时段",e);}
    }
    private boolean sameWindow(ManagementWindow old,int start,int end){
        return old.effectiveAt==0 ? old.start==start&&old.end==end : old.pendingStart==start&&old.pendingEnd==end;
    }
    ManagementWindow.State previewManagementWindow(boolean enabled,int start,int end) {
        if(!canEdit())return null;
        long now=clock.wall(); ZoneId zone=ZoneId.systemDefault();
        ManagementWindow old=readManagement(now);
        ManagementWindow chosen=old.change(enabled,start,end,now,zone);
        if(chosen==null)return null;
        if(!coolingEnabled())return chosen.state(now,zone);
        if(!enabled){start=-1;end=-1;}
        if(sameWindow(old,start,end))return old.state(now,zone);
        CoolingChange c=ledger.pending(CoolingLedger.WINDOW);
        String v=CoolingLedger.windowValue(start,end);
        long at=c!=null && v.equals(c.value)?c.effectiveAt:now+CoolingLedger.DAY;
        return projected(old,now,start,end,at);
    }
    boolean setManagementWindow(boolean enabled,int start,int end) {
        if(!canEdit())return false;
        long now=clock.wall();ManagementWindow old=readManagement(now);
        ManagementWindow chosen=old.change(enabled,start,end,now,ZoneId.systemDefault());
        if(chosen==null)return false;
        if(!enabled){start=-1;end=-1;}
        if(coolingEnabled())return ledger.submit(CoolingLedger.WINDOW,"window",
                enabled?"管理时段改为 "+Rule.time(start)+"–"+Rule.time(end):"停用管理时段",
                CoolingLedger.windowValue(start,end),sameWindow(old,start,end));
        boolean ok=chosen==old || writeManagement(chosen);
        if(ok&&ledger.pending(CoolingLedger.WINDOW)!=null)ledger.cancel(CoolingLedger.WINDOW);
        return ok;
    }
    boolean canEdit() {
        ledger.settle();
        if(lockedFor()>0)return false;
        long now=clock.wall();
        return readManagement(now).open(now,ZoneId.systemDefault());
    }

    // Every native configuration mutation enters here. UI listeners must not hold the mutable store.
    boolean setPaused(boolean value) {
        if (!canEdit()) return false;
        return ledger.submit(CoolingLedger.PROTECTION,value?"pause":"resume",value?"暂停保护":"恢复保护",
                String.valueOf(value),value==paused());
    }
    boolean put(Rule rule) {
        Rule prior = draftRule(rule.packageName);
        if(prior!=null && prior.sharedBasket!=null)return saveSchedule(rule,rule.restPeriods());
        Rule selected = prior == null ? rule : prior;
        return putPool(rule,selected.methodMask,selected.feedbackMask,selected.alternatePackage,selected.curtain);
    }
    // A legacy caller changing only the time or the existing foreground opt-in must keep the entire pool.
    boolean put(Rule rule, boolean returnsHome) {
        Rule prior=draftRule(rule.packageName), selected=prior==null ? rule : prior;
        if (prior!=null && prior.returnHome==returnsHome) return put(rule);
        int mode=returnsHome ? Rule.EXIT_NOW : Rule.EXIT_ICON;
        int mask=selected.feedbackMask & Rule.allowedFeedback(mode);
        return put(rule,mode,mask==0 ? FeedbackBasket.NOTICE : mask);
    }
    boolean put(Rule rule, boolean returnsHome, int feedbackMask) {
        Rule prior=draftRule(rule.packageName);
        if (prior!=null && prior.returnHome==returnsHome)
            return putPool(rule,prior.methodMask,feedbackMask,prior.alternatePackage,prior.curtain);
        return put(rule,returnsHome ? Rule.EXIT_NOW : Rule.EXIT_ICON,feedbackMask);
    }
    boolean put(Rule rule, int exitMode, int feedbackMask) {
        Rule saved=draftRule(rule.packageName);
        Rule source=saved!=null && saved.hasMode(exitMode) ? saved : rule;
        return put(rule,exitMode,feedbackMask,(MethodPool.bit(exitMode) & MethodPool.ALTERNATIVES)!=0 ? source.alternatePackage : "",
                Rule.delayed(exitMode) && (saved!=null && (saved.methodMask & MethodPool.TIMED)!=0 ? saved.curtain : source.curtain));
    }
    boolean put(Rule rule,int exitMode,int feedbackMask,String alternatePackage,boolean curtain) {
        return putPool(rule,MethodPool.bit(exitMode),feedbackMask,alternatePackage,curtain);
    }
    boolean putPool(Rule rule,int methods,int mask,String alternatePackage,boolean curtain) {
        Rule prior=draftRule(rule.packageName);
        return savePlan(rule,methods,mask,alternatePackage,curtain,prior==null?0:prior.dailyChance,
                prior==null?0:prior.dailyMask,prior==null||prior.daytime==null?0:prior.daytime.feedbackMask,
                prior==null?"":prior.dailyAlternatePackage,prior!=null&&prior.daytime!=null&&prior.daytime.curtain,prior!=null&&prior.combo);
    }
    boolean savePlan(Rule rule,int methods,int mask,String alternatePackage,boolean curtain,
                     int dailyChance,int dailyMethods,int dailyFeedback,String dailyAlt,boolean dailyCurtain) {
        return savePlan(rule,methods,mask,alternatePackage,curtain,dailyChance,dailyMethods,dailyFeedback,dailyAlt,dailyCurtain,false);
    }
    boolean savePlan(Rule rule,int methods,int mask,String alternatePackage,boolean curtain,
                     int dailyChance,int dailyMethods,int dailyFeedback,String dailyAlt,boolean dailyCurtain,boolean combo) {
        if (!canEdit() || !MethodPool.validRest(methods) || !MethodPool.validFeedback(methods,mask) || curtain && (methods & MethodPool.TIMED)==0) return false;
        String alt=alternatePackage==null ? "" : alternatePackage;
        Rule prior=draftRule(rule.packageName);
        boolean sameRestDestination=prior!=null && (methods & MethodPool.ALTERNATIVES)==(prior.methodMask & MethodPool.ALTERNATIVES)
                && prior.alternatePackage.equals(alt);
        if ((methods & MethodPool.ALTERNATIVES)!=0 ? !(sameRestDestination||validAlternative(rule.packageName,alt)) : !alt.isEmpty()) return false;
        String dest=dailyAlt==null?"":dailyAlt;
        if (dailyChance<0 || dailyChance>100 || (dailyChance==0 ? dailyMethods!=0 || dailyFeedback!=0 || dailyCurtain || combo :
                !(combo?MethodPool.validCombo(dailyMethods):MethodPool.validDaily(dailyMethods)) || !MethodPool.validFeedback(dailyMethods,dailyFeedback)
                || dailyCurtain && (dailyMethods & MethodPool.TIMED)==0)) return false;
        boolean sameDayDestination=prior!=null && prior.daytime!=null
                && (dailyMethods & MethodPool.ALTERNATIVES)==(prior.dailyMask & MethodPool.ALTERNATIVES)
                && prior.dailyAlternatePackage.equals(dest);
        if ((dailyMethods & MethodPool.ALTERNATIVES)!=0 ? !(sameDayDestination||validAlternative(rule.packageName,dest)) : !dest.isEmpty()) return false;
        Rule rest=Rule.pool(rule.packageName,rule.label,rule.start,rule.end,rule.allowInside,methods,mask,alt,curtain);
        Rule changed=(prior==null?rest:prior.withRest(rest)).withDaily(dailyChance,dailyMethods,dailyFeedback,dest,dailyCurtain,combo);
        return submitRule(changed.withStopAfterReturn(prior==null?rule.stopAfterReturn:prior.stopAfterReturn));
    }
    /** Unchanged legacy baskets (including the other period and pending plan) remain intact. */
    boolean saveSchedule(Rule schedule,int[][] intervals) {return saveSchedule(schedule,intervals,null);}
    boolean saveSchedule(Rule schedule,int[][] intervals,Boolean stopOverride) {
        return saveSchedule(schedule,intervals,stopOverride,null);
    }
    boolean saveSchedule(Rule schedule,int[][] intervals,Boolean stopOverride,UseLimits limits){
        if(!canEdit())return false;
        Rule prior=draftRule(schedule.packageName);
        if(prior==null || !RestPeriods.validate(intervals))return false;
        return submitRule(prior.reschedule(RestPeriods.of(intervals)).withStopAfterReturn(stopOverride==null?prior.stopAfterReturn:stopOverride),limits);
    }
    boolean saveSharedPlan(Rule schedule,int[][] intervals,SharedBasket basket,int dailyChance,boolean restCombo,boolean dayCombo) {
        return saveSharedPlan(schedule,intervals,basket,dailyChance,restCombo,dayCombo,false,false,true);
    }
    boolean saveSharedPlan(Rule schedule,int[][] intervals,SharedBasket basket,int dailyChance,boolean restCombo,boolean dayCombo,boolean restFx,boolean dayFx) {
        return saveSharedPlan(schedule,intervals,basket,dailyChance,restCombo,dayCombo,true,restFx,dayFx);
    }
    private boolean saveSharedPlan(Rule schedule,int[][] intervals,SharedBasket basket,int dailyChance,boolean restCombo,boolean dayCombo,
                                   boolean modern,boolean restFx,boolean dayFx) {
        if(!canEdit() || basket==null || !RestPeriods.validate(intervals))return false;
        Rule prior=draftRule(schedule.packageName);
        try{
            Rule next=modern?Rule.shared(schedule.packageName,schedule.label,RestPeriods.of(intervals),basket,dailyChance,restCombo,dayCombo,restFx,dayFx)
                    :Rule.shared(schedule.packageName,schedule.label,RestPeriods.of(intervals),basket,dailyChance,restCombo,dayCombo);
            return saveCheckedShared(next.withStopAfterReturn(prior==null?schedule.stopAfterReturn:prior.stopAfterReturn),prior);
        }catch(IllegalArgumentException invalid){return false;}
    }
    boolean saveEditedShared(Rule next){return saveEditedShared(next,null);}
    boolean saveEditedShared(Rule next,UseLimits limits){
        if(!canEdit()||next.sharedBasket==null)return false;
        return saveCheckedShared(next,draftRule(next.packageName),limits);
    }
    private boolean saveCheckedShared(Rule next,Rule prior){return saveCheckedShared(next,prior,null);}
    private boolean saveCheckedShared(Rule next,Rule prior,UseLimits limits){
        SharedBasket basket=next.sharedBasket;
        if(!basket.alternate.isEmpty()&&!validAlternative(next.packageName,basket.alternate)){
            // A missing destination from the identical saved basket already leaves the user on Home.
            if(prior==null||prior.sharedBasket==null||!prior.sharedBasket.alternate.equals(basket.alternate)
                    || (prior.sharedBasket.methods&MethodPool.ALTERNATIVES)!=(basket.methods&MethodPool.ALTERNATIVES))return false;
        }
        return submitRule(next,limits);
    }
    private boolean submitRule(Rule changed){return submitRule(changed,null);}
    private boolean submitRule(Rule changed,UseLimits limits){
        if(!canEdit())return false; // Resolve time after validation, immediately before committing the edit.
        try {
            Rule effective=find(changed.packageName);
            Rule prior=limits==null?draftRule(changed.packageName):null;
            String record=Store.toRecord(changed,limits!=null?limits:prior!=null?prior.limits:changed.limits).toString();
            return ledger.submit(CoolingLedger.ruleKey(changed.packageName),effective==null?"add":"edit",
                    (effective==null?"添加":"修改")+changed.label+"规则",record,effective!=null&&record.equals(Store.toRecord(effective).toString()));
        }catch(JSONException e){throw new IllegalStateException("无法保存规则",e);}
    }
    boolean validAlternative(String target,String alternative) {
        if (alternative==null || alternative.isEmpty() || alternative.equals(target)
                || essentialPackages().contains(alternative) || launchIntent(alternative)==null) return false;
        Rule other=find(alternative);
        if (other==null) return true;
        int minute=Store.now();
        if (!other.allows(minute)) return false;
        Rule active=other.enforcedAt(minute);
        // An explicitly launched destination does not pass through its original icon.
        // Never redirect into another active foreground intervention.
        return active==null || !active.returnHome;
    }
    Intent launchIntent(String pkg) {
        PackageManager pm=context.getPackageManager();
        Intent intent=pm.getLaunchIntentForPackage(pkg);
        if (intent==null || intent.getComponent()==null || !pkg.equals(intent.getComponent().getPackageName())) return null;
        try {
            android.content.pm.ApplicationInfo a=pm.getApplicationInfo(pkg,0);
            return a.enabled && (a.flags & android.content.pm.ApplicationInfo.FLAG_SUSPENDED)==0
                    ? intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) : null;
        } catch (PackageManager.NameNotFoundException e) { return null; }
    }
    boolean remove(String pkg) {
        if (!canEdit()) return false;
        Rule current=find(pkg);
        return ledger.submit(CoolingLedger.ruleKey(pkg),"remove","移除"+(current==null?pkg:current.label)+"规则","",current==null);
    }
    private int boot() { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
    private RuleLock readLock() {
        return prefs.contains(ELAPSED) ? new RuleLock(prefs.getInt(BOOT, -1), prefs.getLong(ELAPSED, 0),
                prefs.getLong(WALL, 0)) : null;
    }
    private boolean writeLock(RuleLock lock) {
        SharedPreferences.Editor edit = prefs.edit();
        if (lock == null) return edit.remove(BOOT).remove(ELAPSED).remove(WALL).commit();
        return edit.putInt(BOOT,lock.boot).putLong(ELAPSED,lock.elapsedUntil).putLong(WALL,lock.wallUntil).commit();
    }
    long lockedFor() {
        RuleLock stored = readLock();
        if (stored == null) return 0;
        long now = android.os.SystemClock.elapsedRealtime();
        RuleLock current = stored.at(boot(), now, System.currentTimeMillis());
        if (current != stored) writeLock(current);
        return current == null ? 0 : current.elapsedUntil - now;
    }
    boolean lockRules() {
        if (!serviceEnabled() || paused() || rules().isEmpty() || !canEdit()) return false;
        int boot = boot();
        if (boot < 0) return false;
        return writeLock(RuleLock.start(null, boot, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis()));
    }

    /** Android's list of live services omits a granted service while it is binding or disconnected.
     * Read the user's switch separately so the recovery action doesn't ask for an existing grant. */
    boolean serviceAuthorized(){
        String set=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(set==null)return false;
        ComponentName own=new ComponentName(context,NativeRestService.class);
        for(String entry:set.split(":"))if(own.equals(ComponentName.unflattenFromString(entry)))return true;
        return false;
    }
    boolean serviceEnabled() {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager == null) return false;
        String component = context.getPackageName() + ".NativeRestService";
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ResolveInfo r = info.getResolveInfo();
            if (r != null && r.serviceInfo != null && context.getPackageName().equals(r.serviceInfo.packageName)
                    && component.equals(r.serviceInfo.name)) return true;
        }
        return false;
    }

    Set<String> essentialPackages() {
        Set<String> result = new HashSet<>(Arrays.asList(context.getPackageName(), "android", "com.android.systemui",
                "com.android.settings", "com.android.phone", "com.android.emergency"));
        PackageManager pm = context.getPackageManager();
        for (Intent intent : new Intent[]{new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                new Intent(Intent.ACTION_DIAL), new Intent(Settings.ACTION_SETTINGS)}) {
            for (ResolveInfo info : pm.queryIntentActivities(intent, 0))
                if (info.activityInfo != null) result.add(info.activityInfo.packageName);
        }
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        if (telecom != null) {
            String dialer = telecom.getDefaultDialerPackage();
            if (dialer != null) result.add(dialer);
            String system = telecom.getSystemDialerPackage();
            if (system != null) result.add(system);
        }
        return result;
    }
}
