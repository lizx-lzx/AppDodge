package dev.local.appdodge;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** The effective rules live in their original fields. The queue contains one intended change per object. */
final class CoolingLedger {
    static final long DAY = 24L*60*60*1000;
    static final String QUEUE = "cooling_queue_v1", ENABLED = "cooling_enabled";
    static final String WINDOW="management", PROTECTION="protection", MODE="cooling";
    interface Clock { long wall(); long elapsed(); int boot(); }
    interface WindowWriter { boolean apply(int start,int end); }
    private final Clock clock;
    private final WindowWriter window;
    private final SharedPreferences prefs;
    private String cachedRaw;
    private long nextElapsed;

    CoolingLedger(SharedPreferences prefs,Clock clock,WindowWriter window){
        this.prefs=prefs;this.clock=clock;this.window=window;
    }
    static String ruleKey(String pkg){return "rule:"+pkg;}
    private static JSONObject json(String s){try{return new JSONObject(s);}catch(JSONException e){throw new IllegalStateException("无法读取规则防改记录",e);}}
    private JSONObject entries(){return json(prefs.getString(QUEUE,"{}"));}
    private static List<String> keys(JSONObject q){
        ArrayList<String> keys=new ArrayList<>();for(Iterator<String> i=q.keys();i.hasNext();)keys.add(i.next());return keys;
    }
    private static void saveQueue(SharedPreferences.Editor e,JSONObject q){
        if(q.length()==0)e.remove(QUEUE);else e.putString(QUEUE,q.toString());
    }
    private boolean enabledRaw(){return prefs.getBoolean(ENABLED,false);}
    boolean enabled(){settle();return enabledRaw();}
    /** Called on first read, real time events and on the service's scheduled due edge. No polling when empty. */
    void settle(){
        String raw=prefs.getString(QUEUE,null);
        if(raw==null){cachedRaw=null;nextElapsed=0;return;}
        long elapsed=clock.elapsed();
        if(raw.equals(cachedRaw)&&elapsed<nextElapsed)return;
        long wall=clock.wall();int boot=clock.boot();
        JSONObject q=json(raw);long next=Long.MAX_VALUE;boolean changed=false;
        SharedPreferences.Editor edit=prefs.edit(); JSONArray rules=null;
        try{
            for(String key:keys(q)){
                JSONObject j=q.getJSONObject(key);
                long due=j.getLong("due");
                if(boot!=j.getInt("boot")){
                    // No elapsed clock survives a reboot. Bridge once per boot from its saved wall
                    // deadline and immediately re-anchor, so later in-boot wall edits are irrelevant.
                    long left=Math.max(0,Math.min(j.getLong("span"),j.getLong("wall")-wall));
                    due=elapsed+left;j.put("boot",boot).put("due",due).put("wall",wall+left).put("span",left);
                    changed=true;
                }
                if(due>elapsed){next=Math.min(next,due);continue;}
                if(key.startsWith("rule:")&&rules==null)rules=new JSONArray(prefs.getString("rules","[]"));
                apply(edit,rules,key,j.getString("kind"),j.getString("value"));
                q.remove(key);changed=true;
            }
            if(changed){
                if(rules!=null)edit.putString("rules",rules.toString());
                saveQueue(edit,q);
                if(!edit.commit())throw new IllegalStateException("未能保存到期的规则更改");
            }
        }catch(JSONException e){throw new IllegalStateException("无法应用预约更改",e);}
        // A listener may have queried the config synchronously during commit. The queue and all
        // changed rule records were committed together; independent objects are never forgotten.
        cachedRaw=q.length()==0?null:q.toString();nextElapsed=next;
    }
    private void apply(SharedPreferences.Editor edit,JSONArray rules,String key,String kind,String value)throws JSONException{
        if(key.startsWith("rule:"))Store.replace(rules,key.substring(5),"remove".equals(kind)?null:json(value));
        else if(PROTECTION.equals(key))edit.putBoolean("paused","pause".equals(kind));
        else if(MODE.equals(key))edit.putBoolean(ENABLED,"enable".equals(kind));
        else if(WINDOW.equals(key)){
            JSONObject v=json(value);
            if(!window.apply(v.getInt("start"),v.getInt("end")))throw new IllegalStateException("未能保存管理时段");
        }else throw new IllegalStateException("未知规则设置："+key);
    }
    static String windowValue(int s,int e){
        try{return new JSONObject().put("start",s).put("end",e).toString();}catch(JSONException x){throw new IllegalStateException(x);}
    }
    /** All callers have authorized the write against the OLD management window and manual lock. */
    boolean submit(String key,String kind,String title,String value,boolean unchanged){
        settle();JSONObject q=entries(),prior=q.optJSONObject(key);
        boolean defer=enabledRaw()||"disable".equals(kind);
        if(unchanged){if(prior==null)return true;return cancel(key);}
        if(defer&&prior!=null&&kind.equals(prior.optString("kind"))&&value.equals(prior.optString("value")))return true;
        SharedPreferences.Editor edit=prefs.edit();
        try{
            if(defer){
                int boot=clock.boot();if(boot<0)return false;
                q.put(key,new JSONObject().put("kind",kind).put("title",title).put("value",value)
                        .put("boot",boot).put("due",clock.elapsed()+DAY).put("wall",clock.wall()+DAY).put("span",DAY));
            }else{
                JSONArray rules=key.startsWith("rule:")?new JSONArray(prefs.getString("rules","[]")):null;
                apply(edit,rules,key,kind,value);
                if(rules!=null)edit.putString("rules",rules.toString());
                q.remove(key);
            }
            if(defer||prior!=null)saveQueue(edit,q);
            return edit.commit();
        }catch(JSONException e){throw new IllegalStateException("无法保存规则更改",e);}
    }
    boolean cancel(String key){
        settle();JSONObject q=entries();if(q.remove(key)==null)return false;
        SharedPreferences.Editor edit=prefs.edit();saveQueue(edit,q);return edit.commit();
    }
    private CoolingChange view(String key,JSONObject j,long offset){
        return new CoolingChange(key,j.optString("kind"),j.optString("title"),j.optString("value"),offset+j.optLong("due"));
    }
    CoolingChange pending(String key){
        settle();if(!prefs.contains(QUEUE))return null;
        JSONObject j=entries().optJSONObject(key);
        return j==null?null:view(key,j,clock.wall()-clock.elapsed());
    }
    List<CoolingChange> pending(){
        settle();if(!prefs.contains(QUEUE))return Collections.emptyList();
        JSONObject q=entries();long offset=clock.wall()-clock.elapsed();
        ArrayList<CoolingChange> result=new ArrayList<>();
        for(String k:keys(q))result.add(view(k,q.optJSONObject(k),offset));
        result.sort(Comparator.comparingLong(c->c.effectiveAt));return result;
    }
    long nextAt(){
        settle();return prefs.contains(QUEUE)?clock.wall()+Math.max(0,nextElapsed-clock.elapsed()):0;
    }
}
