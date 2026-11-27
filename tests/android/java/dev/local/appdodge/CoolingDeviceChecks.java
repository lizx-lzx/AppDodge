package dev.local.appdodge;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Deterministic clock, genuine Android SharedPreferences, the same config and deadline
 * entry point as the accessibility service. Never adjusts the emulator's system clock. */
final class CoolingDeviceChecks {
    private static final long HOUR=3600000L, DAY=24*HOUR;
    private static final class Time implements CoolingLedger.Clock {
        long wall=LocalDateTime.of(2026,11,24,22,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), elapsed=600000;
        int boot=13;
        public long wall(){return wall;} public long elapsed(){return elapsed;} public int boot(){return boot;}
        void step(long ms){wall+=ms;elapsed+=ms;}
    }
    private static void ok(boolean b,String why){if(!b)throw new AssertionError(why);}
    private static void stopOptIn(Context context,SharedPreferences fixture,SharedPreferences management) {
        fixture.edit().clear().commit();management.edit().clear().commit();
        Time t=new Time();NativeRestConfig c=new NativeRestConfig(context,t);
        Rule a=Rule.defaultRest("cool.stop","游戏",60,120,false),b=Rule.defaultRest("cool.other","其他",70,180,false);
        SharedBasket basket=new SharedBasket(MethodPool.bit(Rule.EXIT_MUTE)|MethodPool.bit(Rule.EXIT_SHORT),0,"",false);
        int[][] times={{60,120},{180,240}};
        try{
            // Older records have no consent. Do not reinterpret a string, or migrate the selection to add the flag.
            org.json.JSONObject oldest=new org.json.JSONObject().put("package",a.packageName).put("label",a.label)
                    .put("start",60).put("end",120).put("allowInside",false);
            Rule shared=Rule.shared(a.packageName,a.label,RestPeriods.of(times),basket,60,false,false);
            Rule modern=Rule.shared(a.packageName,a.label,RestPeriods.of(times),basket,60,false,false,true,true);
            for(org.json.JSONObject record:new org.json.JSONObject[]{oldest,Store.toRecord(a),Store.toRecord(a.reschedule(RestPeriods.of(times))),
                    Store.toRecord(shared),Store.toRecord(modern)}){
                Rule original=Store.decode(record);
                ok(original!=null&&!original.stopAfterReturn,"missing permission stays off for every record family");
                record.put("stopAfterReturn","true");ok(!Store.decode(record).stopAfterReturn,"malformed consent never enables stopping");
                org.json.JSONObject encoded=Store.toRecord(original.withStopAfterReturn(true));
                Rule opted=Store.decode(encoded);
                ok(opted.stopAfterReturn&&opted.enforcedAt(60).stopAfterReturn,"rest enforcement preserves explicit consent");
                if(opted.dailyChance>0)ok(opted.enforcedAt(300).forMode(Rule.EXIT_MUTE).stopAfterReturn,"day and single-mode views preserve consent");
                ok(Store.toRecord(opted.withStopAfterReturn(false)).toString().equals(Store.toRecord(original).toString()),
                    "disabling stop round-trips all original rule data");
            }
        }catch(org.json.JSONException e){throw new AssertionError(e);}
        ok(c.put(b)&&c.saveSharedPlan(a,times,basket,60,false,false,false,true)&&!c.find(a.packageName).stopAfterReturn,"old save path does not opt in");
        ok(c.setCoolingEnabled(true)&&c.saveSchedule(a,times,true),"opt in through the same 24h rule queue");
        ok(!c.find(a.packageName).stopAfterReturn&&c.draftRule(a.packageName).stopAfterReturn&&!c.find(b.packageName).stopAfterReturn,"pending is not active and is isolated by package");
        NativeRestConfig next=new NativeRestConfig(context,t);
        t.step(DAY-1);
        ok(!next.find(a.packageName).stopAfterReturn,"not one millisecond early");
        t.step(1);
        ok(next.find(a.packageName).stopAfterReturn&&next.find(a.packageName).dailyChance==60,"effective after 24h without opening the editor");
        ok(next.saveSharedPlan(a,times,basket,75,false,false,false,true)&&next.draftRule(a.packageName).stopAfterReturn,"editing another field keeps the opt-in");
        ok(next.saveSchedule(a,times,false)&&!next.draftRule(a.packageName).stopAfterReturn&&next.find(a.packageName).stopAfterReturn,"opting out is also cooled");
        ok(next.cancelChange(next.pendingRule(a.packageName).key)&&next.find(a.packageName).stopAfterReturn,"withdrawal leaves effective rule alone");
        ok(next.remove(a.packageName)&&next.find(a.packageName).stopAfterReturn,"removal does not turn off early");
        t.step(DAY);
        ok(next.find(a.packageName)==null&&!next.find(b.packageName).stopAfterReturn,"removal applies to this app alone");
    }
    static void run(Context context,SharedPreferences fixture,SharedPreferences management){
        stopOptIn(context,fixture,management);
        fixture.edit().clear().commit();management.edit().clear().commit();
        Time t=new Time();NativeRestConfig c=new NativeRestConfig(context,t);
        Rule a=Rule.defaultRest("cool.a","甲",60,120,false), b=Rule.defaultRest("cool.b","乙",120,180,false);
        Rule a2=Rule.defaultRest("cool.a","甲",70,135,false);
        ok(!c.coolingEnabled()&&c.put(a)&&c.find(a.packageName)!=null,"off by default; original write contract");
        ok(c.setCoolingEnabled(true)&&c.coolingEnabled()&&c.nextCoolingAt()==0,"opt-in is immediate");
        ok(c.remove(a.packageName)&&c.find(a.packageName)!=null,"pending removal retains active rule");
        CoolingChange remove=c.pendingRule(a.packageName);
        ok(remove!=null&&remove.kind.equals("remove")&&remove.pkg.equals(a.packageName)
                &&remove.effectiveAt==t.wall()+DAY,"removal deadline is 24 hours, not midnight");
        t.step(HOUR);ok(c.remove(a.packageName)&&c.pendingRule(a.packageName).effectiveAt==remove.effectiveAt,"repeat delete does not restart");
        ok(c.cancelChange(remove.key)&&c.find(a.packageName)!=null&&c.pendingRule(a.packageName)==null,"withdrawal retains rule");
        ok(c.remove(a.packageName)&&c.put(b)&&c.setPaused(true)&&c.setCoolingEnabled(false),"separate user intentions");
        long original=c.pendingRule(b.packageName).effectiveAt;
        ok(c.pendingChanges().size()==4&&!c.paused()&&c.coolingEnabled()&&c.find(b.packageName)==null
                &&c.draftRule(b.packageName).start==120,"queued add readable as draft but not yet active");
        t.step(HOUR);ok(c.put(a2),"new intent for same app");
        CoolingChange edited=c.pendingRule(a.packageName);
        ok(edited.kind.equals("edit")&&edited.effectiveAt==original+HOUR&&c.find(a.packageName).start==60
                &&c.draftRule(a.packageName).start==70,"replacement resets only its own clock");
        t.wall+=3*DAY;
        NativeRestConfig reopened=new NativeRestConfig(context,t);
        ok(reopened.coolingEnabled()&&!reopened.paused()&&reopened.rules().size()==1
                &&reopened.pendingRule(b.packageName).effectiveAt==original+3*DAY,"forward wall jump plus restart is not approval");
        t.wall-=3*DAY;
        t.step(23*HOUR-1);
        ok(reopened.coolingEnabled()&&!reopened.paused()&&reopened.rules().size()==1,"one millisecond short");
        t.step(1);long next=reopened.nextCoolingAt();
        ok(next==edited.effectiveAt&&!reopened.coolingEnabled()&&reopened.paused()&&new Store(fixture).find(b.packageName)!=null
                &&new Store(fixture).find(a.packageName).start==60,"service edge commits due add/pause/disable together; later request stays pending");
        t.step(HOUR);ok(reopened.nextCoolingAt()==0&&new Store(fixture).find(a.packageName).start==70,"remaining edit commits without manager entry");
        ok(reopened.remove(a.packageName)&&reopened.find(a.packageName)==null,"unprotected removal immediate again");
        // The list's removal request must really stop protecting *only* that package, including
        // if the manager is closed when its day expires. The manual 15-minute lock still gates edits.
        t=new Time();c=new NativeRestConfig(context,t);
        ok(c.setManagementWindow(true,21*60,23*60)&&c.setCoolingEnabled(true)&&c.remove(b.packageName),"remove while open");
        String deleteKey=c.pendingRule(b.packageName).key;
        int realBoot=android.provider.Settings.Global.getInt(context.getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);
        RuleLock lock=RuleLock.start(null,realBoot,android.os.SystemClock.elapsedRealtime(),System.currentTimeMillis());
        fixture.edit().putInt("rule_lock_boot",lock.boot).putLong("rule_lock_elapsed",lock.elapsedUntil).putLong("rule_lock_wall",lock.wallUntil).commit();
        ok(!c.cancelChange(deleteKey)&&!c.setCoolingEnabled(false)&&!c.put(b)&&c.pendingRule(b.packageName)!=null,"manual lock also blocks cooling edits and withdrawal");
        fixture.edit().remove("rule_lock_boot").remove("rule_lock_elapsed").remove("rule_lock_wall").commit();
        t.step(DAY-1);ok(c.find(b.packageName)!=null,"removal not one millisecond early");
        t.wall+=3*HOUR;t.step(1);ok(!c.managementWindow().open&&new Store(fixture).find(b.packageName)==null
                &&c.pendingChanges().isEmpty(),"due removal effective behind closed management gate");
        fixture.edit().clear().commit();
        // At 22:00 again: keep a legacy overnight handover, even while a 24h replacement is queued then withdrawn.
        t=new Time();c=new NativeRestConfig(context,t);management.edit().clear().commit();
        ok(c.setManagementWindow(true,21*60,23*60)&&c.setManagementWindow(true,10*60,11*60),"legacy old schedule and next-day change");
        ManagementWindow.State legacy=c.managementWindow();
        ok(legacy.pending&&c.setCoolingEnabled(true)&&c.setManagementWindow(false,0,0),"protected change from open old window");
        CoolingChange pending=c.pendingChanges().get(0);
        ok(pending.kind.equals("window")&&pending.effectiveAt==t.wall()+DAY&&c.managementWindow().open
                &&!c.managementWindow().pendingEnabled,"cooldown window does not prematurely replace legacy gate");
        java.util.List<CoolingChange> both=c.scheduledChanges();
        ok(c.pendingChanges().size()==1 && both.size()==2 && both.get(0).kind.equals("window_handover")
                && both.get(0).effectiveAt==legacy.effectiveAt && both.get(1).key.equals(pending.key)
                && both.get(0).effectiveAt<both.get(1).effectiveAt,"list both actual deadlines chronologically, without duplicating a cooling change");
        ok(c.cancelChange(pending.key)&&c.managementWindow().effectiveAt==legacy.effectiveAt
                &&c.managementWindow().pendingStart==legacy.pendingStart,"withdrawal restores original approved reservation");
        ok(c.pendingChanges().isEmpty() && c.scheduledChanges().size()==1 && c.scheduledChanges().get(0).kind.equals("window_handover"),
                "withdrawing cooling does not hide the remaining overnight handover");
        ok(c.setManagementWindow(false,0,0),"second deferred disable");
        t.step(3*HOUR);
        ok(!c.managementWindow().open&& !c.setCoolingEnabled(false)&&!c.cancelChange(pending.key)
                &&!c.setPaused(false)&&!c.remove(b.packageName),"midnight legacy change closes gate; cannot withdraw, weaken or destroy outside");
        ok(c.scheduledChanges().size()==1 && c.scheduledChanges().get(0).key.equals(pending.key),
                "effective overnight handover disappears while future cooling entry remains");
        t.step(21*HOUR);
        ok(c.managementWindow().open&&!c.managementWindow().enabled&&c.pendingChanges().isEmpty(),"mature window disable applies behind closed UI");
        ok(c.scheduledChanges().isEmpty(),"no completed changes remain in the public schedule");
        // First management window is also a normal setting when self-protection is on.
        ok(c.setManagementWindow(true,8*60,9*60)&&!c.managementWindow().enabled&&c.managementWindow().open&&c.managementWindow().pending,
                "first request does not prematurely activate the management gate");
        t.step(HOUR);t.boot++;t.elapsed=5000; // Normal system restart; 23h remain.
        reopened=new NativeRestConfig(context,t);
        ok(reopened.nextCoolingAt()==t.wall()+23*HOUR&&reopened.managementWindow().open,"persisted reboot bridge");
        t.wall-=HOUR;t.step(23*HOUR);
        ok(reopened.managementWindow().enabled&&!reopened.managementWindow().open,"bridge reanchored to elapsed time after reboot");
    }
}
