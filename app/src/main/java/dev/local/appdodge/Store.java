package dev.local.appdodge;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public final class Store {
    static final int PLAN_VERSION=2; // first on-disk version with independent rest and daytime policies
    private static final int SHARED=3, MULTI_LEGACY=4, STRATEGIES=5;
    private static RestPeriods readTimes(JSONObject o) throws org.json.JSONException {
        JSONArray values=o.getJSONArray("restPeriods");int[][] a=new int[values.length()][2];
        for(int i=0;i<a.length;i++){
            JSONArray pair=values.getJSONArray(i);
            if(pair.length()!=2)throw new IllegalArgumentException("休息时段无效");
            a[i][0]=pair.getInt(0);a[i][1]=pair.getInt(1);
        }
        return RestPeriods.of(a);
    }
    private static JSONArray times(Rule rule) {
        JSONArray a=new JSONArray(); for(int[] pair:rule.restPeriods())a.put(new JSONArray().put(pair[0]).put(pair[1]));
        return a;
    }
    private final SharedPreferences prefs;
    Store(SharedPreferences preferences) { prefs = preferences; }
    public List<Rule> rules() {
        ArrayList<Rule> result = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(prefs.getString("rules", "[]"));
            for (int i = 0; i < data.length(); i++) try {
                JSONObject o = data.getJSONObject(i);
                Rule rule=decode(o);
                if(rule!=null)result.add(rule);
            } catch (org.json.JSONException | IllegalArgumentException malformed) { /* Ignore this row only. */ }
        } catch (Exception ignored) { /* Do not create protection for an unreadable entry. */ }
        return result;
    }
    static Rule decode(JSONObject o) throws org.json.JSONException {
        Rule r=decodePolicy(o);
        // Only an explicit JSON boolean opts in. Missing and malformed values stay off.
        if(r!=null){
            r=r.withStopAfterReturn(Boolean.TRUE.equals(o.opt("stopAfterReturn")));
            JSONObject q=o.optJSONObject("limits");
            if(q!=null)try{r.limits=new UseLimits(q.optInt("session"),q.optInt("day"),q.optInt("rest"),q.optBoolean("noSchedule"));}
            catch(IllegalArgumentException ignored){ /* Keep the rest of the enrollment. */ }
        }
        return r;
    }
    private static Rule decodePolicy(JSONObject o) throws org.json.JSONException {
        boolean independent=o.has("planVersion");
        int version=independent?o.getInt("planVersion"):0;
        if(version==SHARED||version==STRATEGIES){
            JSONObject b=o.getJSONObject("basket");
            SharedBasket basket=new SharedBasket(b.getInt("methods"),b.getInt("feedback"),b.optString("alternatePackage",""),b.getBoolean("curtain"));
            RestPeriods times=readTimes(o);
            String pkg=o.getString("package"),label=o.getString("label");int chance=o.getInt("dailyChance");
            boolean rc=o.getBoolean("restCombo"),dc=o.getBoolean("dayCombo");
            return version==STRATEGIES?Rule.shared(pkg,label,times,basket,chance,rc,dc,o.getBoolean("restByEffects"),o.getBoolean("dayByEffects"))
                    :Rule.shared(pkg,label,times,basket,chance,rc,dc);
        }
        if (independent && version!=PLAN_VERSION && version!=MULTI_LEGACY) return null;
        if(version==MULTI_LEGACY&&!o.has("methodMask"))return null;
        int mask = o.has("feedbackMask") ? o.optInt("feedbackMask",0) : FeedbackBasket.LOCK;
        if (o.has("methodMask")) {
            // A malformed new-format row may never be interpreted as legacy lock consent.
            if (!o.has("feedbackMask")) return null;
            Rule rule=Rule.pool(o.getString("package"),o.getString("label"),o.getInt("start"),o.getInt("end"),
                    o.getBoolean("allowInside"),o.getInt("methodMask"),o.getInt("feedbackMask"),
                    o.optString("alternatePackage",""),o.optBoolean("curtain",false));
            Rule formerlyDormant=rule;
            if (o.has("dailyPlan") || independent && o.has("pausedDailyPlan")) {
                // A corrupt optional plan must not enable new effects or erase the old schedule.
                try {
                    boolean enabled=o.has("dailyPlan");
                    JSONObject day=o.getJSONObject(enabled?"dailyPlan":"pausedDailyPlan");
                    rule=rule.withDaily(day.getInt("chance"),day.getInt("methods"),day.getInt("feedback"),day.optString("alternatePackage",""),day.optBoolean("curtain",false),day.has("combo") && day.getBoolean("combo"));
                    if (!enabled) rule=rule.withDaily(0,0,0,"",false);
                    JSONObject other=day.optJSONObject("otherMode");
                    if(other!=null) rule=rule.withStandby(other.getInt("methods"),other.getInt("feedback"),
                            other.optString("alternatePackage",""),other.optBoolean("curtain",false));
                } catch (org.json.JSONException | IllegalArgumentException ignored) {}
            }
            // 0.9/0.10 enabled daily meant immediate return regardless of the
            // top-level selection. Preserve both the observed behavior and old preset.
            if (!independent && rule.daytime!=null)
                rule=rule.withRest(Rule.defaultRest(rule.packageName,rule.label,rule.start,rule.end,rule.allowInside))
                        .withPreviousRest(formerlyDormant);
            if (independent && o.has("previousRest")) try {
                JSONObject old=o.getJSONObject("previousRest");
                Rule previous=Rule.pool(rule.packageName,rule.label,rule.start,rule.end,rule.allowInside,old.getInt("methods"),
                        old.getInt("feedback"),old.optString("alternatePackage",""),old.optBoolean("curtain",false));
                rule=rule.withPreviousRest(previous);
            } catch (org.json.JSONException | IllegalArgumentException ignored) { /* optional, never active */ }
            return version==MULTI_LEGACY?rule.reschedule(readTimes(o)):rule;
        }
        // An unreadable explicit selection must never silently authorize a lock.
        int mode = o.has("exitMode") ? o.optInt("exitMode",-1)
                : o.optBoolean("returnHome",false) ? Rule.EXIT_NOW : Rule.EXIT_ICON;
        if (!Rule.validExit(mode) || mode>=Rule.EXIT_REDIRECT) return null; // redirect never existed in legacy records
        if (Rule.iconAction(mode)) {
            if (!o.has("feedbackMask") || o.optInt("feedbackMask",-1)!=0) return null; // explicit new action only
        } else {
            if (!FeedbackBasket.valid(mask)) mask=FeedbackBasket.NOTICE;
            mask &= Rule.allowedFeedback(mode);
            if (mask==0) mask=FeedbackBasket.NOTICE;
        }
        String alt=mode==Rule.EXIT_ALTERNATIVE ? o.optString("alternatePackage","") : "";
        return new Rule(o.getString("package"), o.getString("label"), o.getInt("start"), o.getInt("end"),
                o.getBoolean("allowInside"),mode,mask,alt,Rule.delayed(mode) && o.optBoolean("curtain",false));
    }
    public Rule find(String pkg) { for (Rule r : rules()) if (r.packageName.equals(pkg)) return r; return null; }
    // Preserve other rows byte-for-value, including unknown records. Viewing a rule
    // never writes; only editing that particular rule serializes its migrated version.
    static void replace(JSONArray all,String pkg,JSONObject entry) throws org.json.JSONException {
        for(int i=all.length()-1;i>=0;i--) {
            JSONObject row=all.optJSONObject(i);
            if(row!=null && pkg.equals(row.optString("package"))) {
                if(entry!=null){all.put(i,entry);return;}
                all.remove(i);
            }
        }
        if(entry!=null)all.put(entry);
    }
    private static JSONObject modeSettings(Rule plan) throws org.json.JSONException {
        JSONObject settings=new JSONObject().put("methods",plan.methodMask).put("feedback",plan.feedbackMask);
        if (plan.curtain) settings.put("curtain",true);
        if (!plan.alternatePackage.isEmpty()) settings.put("alternatePackage",plan.alternatePackage);
        return settings;
    }
    static JSONObject toRecord(Rule r) throws org.json.JSONException {
        return toRecord(r,r.limits);
    }
    static JSONObject toRecord(Rule r,UseLimits limits) throws org.json.JSONException {
        JSONObject row=new JSONObject().put("package",r.packageName).put("label",r.label);
        if(limits.timed()||limits.noSchedule)row.put("limits",new JSONObject().put("session",limits.session).put("day",limits.day).put("rest",limits.rest).put("noSchedule",limits.noSchedule));
        if(r.stopAfterReturn)row.put("stopAfterReturn",true);
        if(r.sharedBasket!=null){
            SharedBasket b=r.sharedBasket;
            JSONObject sel=new JSONObject().put("methods",b.methods).put("feedback",b.feedback).put("curtain",b.curtain);
            if(!b.alternate.isEmpty())sel.put("alternatePackage",b.alternate);
            if(r.modernPolicy)row.put("restByEffects",r.restByEffects).put("dayByEffects",r.dayByEffects);
            return row.put("planVersion",r.modernPolicy?STRATEGIES:SHARED).put("restPeriods",times(r)).put("basket",sel).put("dailyChance",r.dailyChance)
                    .put("restCombo",r.restCombo).put("dayCombo",r.dayCombo);
        }
        boolean multi=r.restPeriods().length>1;
        if(multi)row.put("restPeriods",times(r));
        row.put("planVersion",multi?MULTI_LEGACY:PLAN_VERSION).put("start",r.start).put("end",r.end).put("allowInside",r.allowInside)
                .put("feedbackMask",r.feedbackMask).put("methodMask",r.methodMask).put("exitMode",r.singleMode());
        if (r.curtain) row.put("curtain",true);
        if (!r.alternatePackage.isEmpty()) row.put("alternatePackage",r.alternatePackage);
        Rule preferred=r.preferredDaily();
        if (preferred!=null) {
            JSONObject day=modeSettings(preferred).put("chance",preferred.chance);
            if (preferred.combo) day.put("combo",true);
            if (r.standby!=null) day.put("otherMode",modeSettings(r.standby));
            // Do not store a disabled profile in the old key: a 0.10 reader would
            // ignore an enabled=false flag there and apply previously disabled effects.
            row.put(r.daytime==null?"pausedDailyPlan":"dailyPlan",day);
        }
        if (r.previousRest!=null) row.put("previousRest",modeSettings(r.previousRest));
        return row;
    }
    public static int now() { Calendar c = Calendar.getInstance(); return c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE); }
}
