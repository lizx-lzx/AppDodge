package dev.local.appdodge;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;

/** Exercises the real mutation entry points against a fresh, isolated preference file.
 * Does not grant permissions, open activities, or write the user's actual rules. */
public final class RuleLockDeviceTests extends Instrumentation {
    private void checkBlocks(Context ctx,String db){
        BlockLedger ledger=new BlockLedger(ctx,db);
        try{
            ledger.record("game","游戏","休息",-1);
            check(ledger.apps(null).get(0).average().equals("—"),"unopened has no duration");
            ledger.record("game","游戏","规则退出",12*60000);
            ledger.record("game","游戏","规则退出",18*60000);
            ledger.close();ledger=new BlockLedger(ctx,db);
            BlockLedger.Totals t=ledger.apps(java.time.LocalDate.now().toEpochDay()).get(0);
            check(t.count==3 && t.exits==2 && t.ms==30*60000 && t.average().equals("15 分钟"),"real exit denominator");
            check(ledger.count(java.time.LocalDate.now().minusDays(1).toEpochDay())==0,"local day filter");
            for(int i=0;i<100;i++)ledger.record("other","另一个","休息",-1);
            check(ledger.recent(null,0).size()==100 && ledger.count(null)==103 && ledger.apps(null).size()==2 && ledger.recent(null,1).size()==3,"durable app totals beyond recent list");
        }finally{ledger.close();ctx.deleteDatabase(db);}
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int status = Activity.RESULT_OK;
        Context target = getTargetContext();
        String unique = "rule_lock_test_" + java.util.UUID.randomUUID();
        SharedPreferences fixture = target.getSharedPreferences(unique, Context.MODE_PRIVATE);
        SharedPreferences management = target.getSharedPreferences(unique+"_window", Context.MODE_PRIVATE);
        Context isolated = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                if (name.equals(NativeRestConfig.MANAGEMENT_PREFS)) return management;
                if (!name.equals(NativeRestConfig.PREFS)) throw new AssertionError("Unexpected prefs: "+name);
                return fixture;
            }
        };
        try {
            checkBlocks(target,unique);
            NativeRestConfig openEditor = new NativeRestConfig(isolated);
            Rule initial = new Rule("test.enrolled","已选择",600,601,false);
            Rule edited = new Rule(initial.packageName,initial.label,700,800,true);
            Rule addition = new Rule("test.added","另一个",0,30,true);
            check(!openEditor.lockRules(), "no lock with an empty rule list");
            checkLayerMigration(fixture,openEditor,initial);
            Rule unusedStrong=Rule.pool(addition.packageName,addition.label,0,30,true,MethodPool.bit(Rule.EXIT_LOCK_NOW),0,"",false);
            check(openEditor.put(unusedStrong,false) && openEditor.find(addition.packageName).exitMode==Rule.EXIT_ICON
                    && openEditor.remove(addition.packageName),"legacy bool-only addition does not authorize a new lock");
            fixture.edit().putString("rules", "[{\"package\":\"test.enrolled\",\"label\":\"已选择\",\"start\":600,\"end\":601,\"allowInside\":false}]").commit();
            check(openEditor.find(initial.packageName).feedbackMask == FeedbackBasket.LOCK, "0.4.1 record migrates to existing warning only");
            check(openEditor.find(initial.packageName).exitMode==Rule.EXIT_ICON,"pre-return record remains icon-only");
            fixture.edit().putString("rules", "[{\"package\":\"test.enrolled\",\"label\":\"已选择\",\"start\":600,\"end\":601,\"allowInside\":false,\"returnHome\":true,\"feedbackMask\":2}]").commit();
            check(openEditor.find(initial.packageName).exitMode==Rule.EXIT_NOW
                    && openEditor.find(initial.packageName).feedbackMask==FeedbackBasket.HAPTIC,"0.5 true remains immediate");
            check(!openEditor.put(initial, Rule.EXIT_SHORT,FeedbackBasket.LOCK)
                    && !openEditor.put(initial,Rule.EXIT_LONG,FeedbackBasket.NOTICE|FeedbackBasket.LOCK),"random exit never locks");
            check(!openEditor.put(initial,Rule.EXIT_ICON,FeedbackBasket.DURATION)
                    && !openEditor.put(initial,Rule.EXIT_NOW,FeedbackBasket.HAPTIC|FeedbackBasket.DURATION),"duration requires an actual timed visit");
            check(openEditor.put(initial,Rule.EXIT_SHORT,FeedbackBasket.DURATION)
                    && new NativeRestConfig(isolated).find(initial.packageName).feedbackMask==FeedbackBasket.DURATION,"single duration survives reopen");
            check(openEditor.put(initial,false) && openEditor.find(initial.packageName).feedbackMask==FeedbackBasket.NOTICE,
                    "old bool opt-out can still leave a duration-only rule without granting lock");
            fixture.edit().putString("rules", "[{\"package\":\"test.enrolled\",\"label\":\"已选择\",\"start\":600,\"end\":601,\"allowInside\":false,\"exitMode\":0,\"feedbackMask\":18}]").commit();
            check(openEditor.find(initial.packageName).feedbackMask==FeedbackBasket.HAPTIC,
                    "invalid mode combination cannot enable duration or discard other valid choices");
            check(openEditor.put(initial,false,FeedbackBasket.NEW_DEFAULT),"begin icon-only");
            check(!openEditor.put(initial,false,0) && !openEditor.put(initial,false,32), "reject empty and unknown choices");
            check(openEditor.put(initial,false,FeedbackBasket.NEW_DEFAULT), "explicit feedback basket");
            check(openEditor.put(initial) && !openEditor.returnsHome(initial.packageName), "existing/default icon-only");
            check(openEditor.put(initial,true) && openEditor.put(initial)
                    && openEditor.returnsHome(initial.packageName) && openEditor.find(initial.packageName).feedbackMask==FeedbackBasket.NEW_DEFAULT, "opt-in and selected feedback survive old save methods");
            check(openEditor.put(initial,false) && !openEditor.returnsHome(initial.packageName),"explicit opt-out");
            for(int action:new int[]{Rule.EXIT_WAIT_OPEN,Rule.EXIT_LOCK_NOW,Rule.EXIT_LOCK_DELAY}) {
                check(openEditor.put(initial,action,0,"",false) && openEditor.put(initial) && openEditor.put(initial,false),"new icon method preserved by old callers");
                Rule copy=new NativeRestConfig(isolated).find(initial.packageName);
                check(copy.exitMode==action && copy.feedbackMask==0 && !copy.returnHome && !copy.curtain,"new icon methods have no feedback basket");
            }
            check(!openEditor.put(initial,Rule.EXIT_LOCK_NOW,FeedbackBasket.LOCK,"",false)
                    && !openEditor.put(initial,Rule.EXIT_LOCK_DELAY,0,"",true), "incompatible settings never arm a lock");
            check(!openEditor.put(initial,Rule.EXIT_ALTERNATIVE,0,initial.packageName,false)
                    && !openEditor.put(initial,Rule.EXIT_ALTERNATIVE,0,target.getPackageName(),false)
                    && !openEditor.put(initial,Rule.EXIT_ALTERNATIVE,0,"dev.local.appdodge.missing",false),"self, protected or missing alternative rejected");
            String alt=null;
            android.content.Intent launcher=new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            for(android.content.pm.ResolveInfo ri:target.getPackageManager().queryIntentActivities(launcher,0)) {
                if(ri.activityInfo!=null && openEditor.validAlternative(initial.packageName,ri.activityInfo.packageName)) {
                    alt=ri.activityInfo.packageName; break;
                }
            }
            if(alt!=null) {
                check(openEditor.put(initial,Rule.EXIT_ALTERNATIVE,0,alt,false) && openEditor.put(initial,false)
                        && new NativeRestConfig(isolated).find(initial.packageName).alternatePackage.equals(alt),"saved explicit alternate survives editor restart");
                int minute=Store.now();
                Rule blocked=new Rule(alt,"备选",minute,(minute+2)%1440,false);
                check(openEditor.put(blocked) && !openEditor.validAlternative(initial.packageName,alt),"currently resting alternate rejected");
                Rule daytimeAlt=new Rule(alt,"备选",minute,(minute+2)%1440,true);
                check(openEditor.savePlan(daytimeAlt,1<<Rule.EXIT_ICON,FeedbackBasket.NOTICE,"",false,
                        100,1<<Rule.EXIT_LOAD,0,"",false) && !openEditor.validAlternative(initial.packageName,alt),
                        "redirect cannot start another app that has an active foreground effect");
                check(openEditor.remove(alt),"remove isolated fixture");
            }
            int mixed=(1<<Rule.EXIT_WAIT_OPEN)|(1<<Rule.EXIT_SHORT)|(1<<Rule.EXIT_LOCK_DELAY);
            check(openEditor.putPool(initial,mixed,FeedbackBasket.DURATION,"",true)
                    && openEditor.put(edited) && openEditor.put(edited,true),"schedule-only and compatible legacy writes preserve the whole pool");
            Rule pool=new NativeRestConfig(isolated).find(initial.packageName);
            check(pool.methodMask==mixed && pool.singleMode()==-1 && pool.start==700 && pool.curtain
                    && pool.forMode(Rule.EXIT_SHORT).feedbackMask==FeedbackBasket.DURATION,"pool and settings round trip through storage");
            check(!openEditor.putPool(initial,MethodPool.all(Rule.EXIT_SHORT),FeedbackBasket.NEW_DEFAULT,"",false)
                    && !openEditor.putPool(initial,0,0,"",false)
                    && !openEditor.putPool(initial,mixed,FeedbackBasket.LOCK,"",false)
                    && !openEditor.putPool(initial,(1<<Rule.EXIT_NOW)|(1<<Rule.EXIT_WAIT_OPEN),FeedbackBasket.LOCK,"",false),
                    "bad draft or impossible after-return effect cannot replace pool");
            String savedPool=fixture.getString("rules","");
            org.json.JSONArray bad=new org.json.JSONArray(savedPool);
            bad.getJSONObject(0).put("methodMask",0).put("exitMode",Rule.EXIT_LOCK_NOW);
            fixture.edit().putString("rules",bad.toString()).commit();
            check(openEditor.find(initial.packageName)==null,"invalid explicit pool cannot use a legacy lock");
            fixture.edit().putString("rules",savedPool).commit();
            if(alt!=null) {
                check(openEditor.putPool(initial,MethodPool.all(Rule.EXIT_SHORT),FeedbackBasket.ALL,alt,true)
                        && openEditor.find(initial.packageName).methodMask==MethodPool.all(Rule.EXIT_SHORT)
                        && openEditor.find(initial.packageName).forMode(Rule.EXIT_REDIRECT).alternatePackage.equals(alt),
                        "all methods including auto-redirect round trip with explicit destination");
            }
            int experiences=MethodPool.EXPERIENCES;
            check(openEditor.savePlan(initial,mixed,FeedbackBasket.DURATION,"",true,50,experiences,0,"",false)
                    && openEditor.put(edited),"save two layers and move schedule through legacy API");
            Rule saved=new NativeRestConfig(isolated).find(initial.packageName);
            check(saved.dailyChance==50 && saved.dailyMask==experiences && saved.start==700
                    && saved.during(710).daily && saved.during(710).forMode(Rule.EXIT_NARROW).chance==50
                    && saved.during(800).methodMask==mixed && saved.methodMask==mixed,"reopen retains independently selected rest and daily rules");
            check(!openEditor.savePlan(initial,experiences,0,"",false,0,0,0,"",false)
                    && !openEditor.savePlan(initial,mixed,FeedbackBasket.DURATION,"",true,101,experiences,0,"",false)
                    && !openEditor.savePlan(initial,mixed,FeedbackBasket.DURATION,"",true,0,experiences,0,"",false),
                    "no daytime effect in rest pool, no invalid or unapproved chance");
            int bundle=experiences | 1<<Rule.EXIT_LONG;
            check(!saved.combo && !saved.daytime.combo,"an existing dailyPlan without a combo field remains single");
            check(openEditor.savePlan(initial,mixed,FeedbackBasket.DURATION,"",true,75,bundle,FeedbackBasket.DURATION,"",true,true)
                    && openEditor.put(edited), "write a five-way selection; old schedule-only write retains combo");
            Rule combined=new NativeRestConfig(isolated).find(initial.packageName);
            check(combined.combo && combined.during(710).combo && combined.daytime.methodMask==bundle
                    && combined.during(800).methodMask==mixed && !combined.during(800).combo
                    && combined.daytime.curtain && combined.modeSettings(false).methodMask==experiences,
                    "both sets, duration and rest priority round trip independently");
            check(!openEditor.savePlan(edited,mixed,FeedbackBasket.DURATION,"",true,75,1<<Rule.EXIT_MUTE,0,"",false,true)
                    && !openEditor.savePlan(edited,mixed,FeedbackBasket.DURATION,"",true,75,bundle|1<<Rule.EXIT_LOCK_NOW,FeedbackBasket.DURATION,"",true,true)
                    && openEditor.find(initial.packageName).dailyMask==bundle,"impossible combos refused without changing current rule");
            String comboRecord=fixture.getString("rules","");
            org.json.JSONArray corrupt=new org.json.JSONArray(comboRecord);
            corrupt.getJSONObject(0).getJSONObject("dailyPlan").getJSONObject("otherMode").put("methods",0);
            fixture.edit().putString("rules",corrupt.toString()).commit();
            check(openEditor.find(initial.packageName).combo && openEditor.find(initial.packageName).standby==null,
                    "malformed inactive selection cannot erase active combo");
            corrupt.getJSONObject(0).getJSONObject("dailyPlan").put("methods",1<<Rule.EXIT_MUTE);
            fixture.edit().putString("rules",corrupt.toString()).commit();
            check(openEditor.find(initial.packageName).daytime==null && openEditor.find(initial.packageName).methodMask==mixed,
                    "malformed active combo cannot enable an unintended singleton");
            fixture.edit().putString("rules",comboRecord).commit();
            check(openEditor.savePlan(initial,mixed,FeedbackBasket.DURATION,"",true,25,experiences,0,"",false)
                    && openEditor.find(initial.packageName).modeSettings(true).methodMask==bundle && !openEditor.find(initial.packageName).combo,
                    "switch back to one; stored combination remains available");
            if(alt!=null){
                int allDay=MethodPool.allDaily(Rule.EXIT_LONG);
                check(openEditor.savePlan(initial,mixed,FeedbackBasket.DURATION,"",true,100,allDay,FeedbackBasket.ALL,alt,true)
                        && new NativeRestConfig(isolated).find(initial.packageName).daytime.forMode(Rule.EXIT_REDIRECT).alternatePackage.equals(alt)
                        && openEditor.find(initial.packageName).daytime.forMode(Rule.EXIT_LONG).curtain,"full 12-choice pool and its destination round trip");
            }
            check(openEditor.savePlan(initial,mixed,FeedbackBasket.DURATION,"",true,0,0,0,"",false)
                    && openEditor.find(initial.packageName).during(initial.start).methodMask==mixed
                    && openEditor.find(initial.packageName).during(initial.end)==null,"disable daily restores old pool without daytime interventions");
            int timedFeedback = FeedbackBasket.NEW_DEFAULT|FeedbackBasket.DURATION;
            check(openEditor.put(initial,Rule.EXIT_SHORT,timedFeedback,"",true) && openEditor.put(initial,Rule.EXIT_LONG,timedFeedback) && openEditor.put(initial)
                    && openEditor.put(initial,true) && openEditor.find(initial.packageName).exitMode==Rule.EXIT_LONG,
                    "old schedule-only and bool writes preserve an already-authorized delay");
            check(new NativeRestConfig(isolated).find(initial.packageName).exitMode==Rule.EXIT_LONG
                    && new NativeRestConfig(isolated).find(initial.packageName).feedbackMask==timedFeedback
                    && new NativeRestConfig(isolated).find(initial.packageName).curtain,"delay and duration basket persisted");
            check(openEditor.setPaused(true) && !openEditor.lockRules(), "no lock while paused");
            check(openEditor.setPaused(false), "resume");
            int boot = Settings.Global.getInt(target.getContentResolver(), Settings.Global.BOOT_COUNT,-1);
            check(boot >= 0, "boot identity");
            if (openEditor.serviceEnabled()) check(openEditor.lockRules(), "eligible lock");
            else {
                check(!openEditor.lockRules(), "no lock when service is off");
                RuleLock snapshot = RuleLock.start(null,boot,SystemClock.elapsedRealtime(),System.currentTimeMillis());
                fixture.edit().putInt("rule_lock_boot",snapshot.boot).putLong("rule_lock_elapsed",snapshot.elapsedUntil)
                        .putLong("rule_lock_wall",snapshot.wallUntil).commit();
            }
            long until = fixture.getLong("rule_lock_elapsed",0);
            NativeRestConfig reopened = new NativeRestConfig(isolated);
            check(reopened.lockedFor()>0 && !reopened.lockRules(), "reopen and cannot extend");
            check(!openEditor.put(edited) && !openEditor.put(addition) && !openEditor.remove(initial.packageName)
                    && !openEditor.put(edited,false) && !openEditor.put(edited,Rule.EXIT_NOW,FeedbackBasket.NOTICE)
                    && !openEditor.put(edited,true,FeedbackBasket.LOCK)
                    && !openEditor.put(edited,Rule.EXIT_LOCK_NOW,0,"",false) && !openEditor.put(edited,Rule.EXIT_LONG,timedFeedback,"",false)
                    && !openEditor.putPool(edited,mixed,FeedbackBasket.DURATION,"",false)
                    && !openEditor.savePlan(edited,mixed,FeedbackBasket.DURATION,"",true,100,experiences,0,"",false)
                    && !openEditor.savePlan(edited,mixed,FeedbackBasket.DURATION,"",true,100,bundle,FeedbackBasket.DURATION,"",true,true)
                    && !openEditor.setPaused(true), "stale editor: save / add / delete / pause rejected");
            check(!reopened.setPaused(true) && !reopened.paused(), "reopened editor also cannot pause");
            check(!reopened.setManagementWindow(true,10,60), "a manually locked editor cannot change the management window");
            check(until == fixture.getLong("rule_lock_elapsed",-1) && reopened.rules().size()==1
                    && reopened.find(initial.packageName).start == 600 && reopened.find(initial.packageName).exitMode==Rule.EXIT_LONG
                    && reopened.find(initial.packageName).feedbackMask==timedFeedback && reopened.find(initial.packageName).curtain && reopened.returnsHome(initial.packageName), "original rule and deadline preserved");
            fixture.edit().putLong("rule_lock_elapsed",SystemClock.elapsedRealtime()-1).commit(); // simulate expiry
            check(reopened.lockedFor()==0 && !fixture.contains("rule_lock_boot"), "expiry releases on read");
            check(reopened.put(edited) && openEditor.put(addition) && reopened.remove(initial.packageName)
                    && reopened.setPaused(true), "editing resumes on expiration");
            check(!openEditor.returnsHome(initial.packageName) && openEditor.put(initial)
                    && !openEditor.returnsHome(initial.packageName) && openEditor.find(initial.packageName).feedbackMask==FeedbackBasket.LOCK, "delete and re-add cannot inherit opt-in");
            checkManagementWindow(isolated, management, initial);
            CoolingDeviceChecks.run(isolated,fixture,management);
            result.putString("stream","\nPASS: rule locking and migration, user-set management window, persisted entry, stale add/edit/remove/pause rejection deferred disable, per-object 24h edits/removal, withdrawal, background maturity and reboot.\n");
        } catch(Throwable error) {
            status = Activity.RESULT_CANCELED; result.putString("stream","\nFAIL: "+android.util.Log.getStackTraceString(error));
        } finally { target.deleteSharedPreferences(unique); target.deleteSharedPreferences(unique+"_window"); }
        finish(status,result);
    }
    private void checkManagementWindow(Context isolated, SharedPreferences management, Rule rule) {
        NativeRestConfig oldEditor = new NativeRestConfig(isolated);
        int minute = Store.now(), outsideStart = (minute+30)%1440, outsideEnd = (minute+45)%1440;
        check(!oldEditor.managementWindow().enabled && oldEditor.managementWindow().open && oldEditor.setPaused(false)
                && oldEditor.put(rule), "upgrade starts unlocked and keeps real write paths");
        check(!oldEditor.setManagementWindow(true,minute,minute), "zero duration cannot lock the user out");
        check(oldEditor.setManagementWindow(true,outsideStart,outsideEnd), "initial opt-in takes effect now");
        NativeRestConfig fresh = new NativeRestConfig(isolated);
        check(fresh.managementWindow().enabled && !fresh.managementWindow().open
                && fresh.managementWindow().nextOpen > System.currentTimeMillis(), "entry remains closed on reopen");
        Rule other = new Rule("test.new.entry","Another",0,30,false);
        check(!oldEditor.put(other) && !oldEditor.put(rule,Rule.EXIT_NOW,FeedbackBasket.NOTICE)
                && !oldEditor.remove(rule.packageName) && !oldEditor.setPaused(true)
                && !oldEditor.setManagementWindow(true,(minute+1435)%1440,(minute+5)%1440)
                && !fresh.setManagementWindow(false,0,0) && !fresh.lockRules()
                && fresh.find(other.packageName)==null && fresh.find(rule.packageName)!=null && !fresh.paused(),
                "stale editor cannot add, save, remove, pause, disable or widen an active management restriction");
        // Independent fixture for an already-open window: genuine config writes, no device clock changes.
        management.edit().clear().commit();
        int start=(minute+1435)%1440,end=(minute+5)%1440;
        check(fresh.setManagementWindow(true,start,end) && oldEditor.managementWindow().open
                && oldEditor.setPaused(true) && fresh.setPaused(false), "shared management window allows normal writes while open");
        check(oldEditor.setManagementWindow(false,start,end), "disable requested within old window");
        ManagementWindow.State state = fresh.managementWindow();
        check(state.enabled && state.start==start && state.end==end && state.pending && !state.pendingEnabled
                && state.effectiveAt > System.currentTimeMillis(), "disable schedules rather than removing the current window");
    }
    private void checkLayerMigration(SharedPreferences prefs,NativeRestConfig cfg,Rule base) throws Exception {
        int timer=1<<Rule.EXIT_LONG, now=1<<Rule.EXIT_NOW, combo=1<<Rule.EXIT_MUTE|1<<Rule.EXIT_NARROW;
        int atNight=base.start, atDay=base.end;
        org.json.JSONObject row=new org.json.JSONObject().put("package",base.packageName).put("label",base.label)
                .put("start",base.start).put("end",base.end).put("allowInside",false).put("methodMask",timer)
                .put("curtain",true).put("feedbackMask",FeedbackBasket.DURATION);
        String withoutDay=new org.json.JSONArray().put(row).toString();
        prefs.edit().putString("rules",withoutDay).commit();
        Rule simple=cfg.find(base.packageName);
        check(simple.during(atNight).methodMask==timer && simple.during(atDay)==null && simple.previousRest==null,
                "0.10 daily-off keeps its effective rest timer, not newly strict");
        org.json.JSONObject oldSingle=new org.json.JSONObject().put("methods",1<<Rule.EXIT_REDIRECT).put("feedback",0)
                .put("alternatePackage","old.uninstalled.reader");
        row.put("dailyPlan",new org.json.JSONObject().put("chance",75).put("methods",combo).put("feedback",0)
                .put("combo",true).put("otherMode",oldSingle));
        String original=new org.json.JSONArray().put(row).toString();
        prefs.edit().putString("rules",original).commit();
        Rule migrated=cfg.find(base.packageName);
        check(original.equals(prefs.getString("rules",null)) && migrated.during(atNight).methodMask==now
                && migrated.during(atNight).feedbackMask==FeedbackBasket.NOTICE && !migrated.during(atNight).combo
                && migrated.during(atDay).methodMask==combo && migrated.previousRest.methodMask==timer
                && migrated.previousRest.curtain && migrated.previousRest.feedbackMask==FeedbackBasket.DURATION,
                "view migration has no side effects and preserves BOTH effective strict rest and prior preset");
        check(cfg.savePlan(base,now,FeedbackBasket.NOTICE,"",false,0,0,0,"",false),"close daily on migrated plan");
        org.json.JSONObject record=new org.json.JSONArray(prefs.getString("rules","")).getJSONObject(0);
        check(record.getInt("planVersion")==Store.PLAN_VERSION && !record.has("dailyPlan") && record.has("pausedDailyPlan"),
                "new format preserves off choices without enabling them for an old reader");
        Rule off=cfg.find(base.packageName);
        check(off.during(atNight).methodMask==now && off.during(atDay)==null && off.preferredDaily().chance==75
                && off.preferredDaily().combo && off.previousRest.curtain && off.modeSettings(false).alternatePackage.equals("old.uninstalled.reader"),
                "turning daily off cannot restore hidden timer, erase options or discard prior rest");
        check(cfg.savePlan(base,now,FeedbackBasket.NOTICE,"",false,25,combo,0,"",false,true)
                && cfg.find(base.packageName).during(atNight).methodMask==now,"reenabling or changing chance leaves rest alone");
        check(cfg.savePlan(base,timer,FeedbackBasket.DURATION,"",true,25,combo,0,"",false,true),"explicitly restore formerly dormant timer");
        Rule restored=cfg.find(base.packageName);
        check(restored.during(atNight).methodMask==timer && restored.during(atNight).curtain && restored.previousRest==null
                && restored.daytime.methodMask==combo && restored.daytime.chance==25,"explicit restoration survives reread without remigration");
        // An unavailable, already-selected destination is a safe no-op at use time. Do not
        // make daily-off dependent on finding or replacing a different period's destination.
        String missing="invalid.uninstalled.reader";
        record=new org.json.JSONArray(prefs.getString("rules","")).getJSONObject(0);
        record.remove("curtain");
        record.put("methodMask",1<<Rule.EXIT_REDIRECT).put("feedbackMask",0).put("alternatePackage",missing);
        prefs.edit().putString("rules",new org.json.JSONArray().put(record).toString()).commit();
        check(cfg.savePlan(base,1<<Rule.EXIT_REDIRECT,0,missing,false,0,0,0,"",false),"unrelated day edit keeps old rest destination");
        check(!cfg.savePlan(base,1<<Rule.EXIT_REDIRECT,0,"invalid.different.reader",false,0,0,0,"",false),"new destination still checked");
        // Likewise, a still-active daytime destination must not block an independent rest edit.
        record.remove("alternatePackage");record.put("methodMask",now).put("feedbackMask",FeedbackBasket.NOTICE);
        record.put("dailyPlan",new org.json.JSONObject().put("methods",1<<Rule.EXIT_REDIRECT).put("feedback",0)
                .put("chance",75).put("alternatePackage",missing));
        prefs.edit().putString("rules",new org.json.JSONArray().put(record).toString()).commit();
        check(cfg.savePlan(base,timer,FeedbackBasket.DURATION,"",true,75,1<<Rule.EXIT_REDIRECT,0,missing,false),
                "unrelated rest edit keeps old day destination");
        check(!cfg.savePlan(base,timer,FeedbackBasket.DURATION,"",true,75,1<<Rule.EXIT_REDIRECT,0,"invalid.different.reader",false),
                "new day destination still checked");
        prefs.edit().remove("rules").commit();
    }
    private void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
