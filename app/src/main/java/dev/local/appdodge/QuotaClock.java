package dev.local.appdodge;

import android.content.*;
import android.os.SystemClock;
import java.time.*;
import java.util.*;
import org.json.*;

/** Service-main-thread writer. Only enrolled app counters, no unrelated activity trace. */
final class QuotaClock {
    private final SharedPreferences prefs;
    private final Map<String,QuotaBudget> budgets=new HashMap<>();
    private String owner;private boolean sessionOn;private long from,lastSave;
    QuotaClock(Context c){prefs=c.getSharedPreferences("quota_clocks",Context.MODE_PRIVATE);}
    private QuotaBudget get(String pkg){
        QuotaBudget b=budgets.get(pkg);if(b!=null)return b;
        b=new QuotaBudget();
        try{JSONArray v=new JSONArray(prefs.getString(pkg,"[]"));if(v.length()==4){
            b.date=v.getLong(0);b.spent=v.getLong(1);b.round=v.getLong(2);b.restUntil=v.getLong(3);
        }}catch(JSONException ignored){}
        budgets.put(pkg,b);return b;
    }
    private void save(String pkg){QuotaBudget b=get(pkg);prefs.edit().putString(pkg,new JSONArray().put(b.date).put(b.spent).put(b.round).put(b.restUntil).toString()).apply();}
    private void account(QuotaBudget b,long ms){
        ZonedDateTime now=ZonedDateTime.now();LocalDate date=now.toLocalDate();
        b.account(date.toEpochDay(),ms,now.toInstant().toEpochMilli()-date.atStartOfDay(now.getZone()).toInstant().toEpochMilli());
    }
    void clearRound(String pkg){
        if(pkg.equals(owner))focus(null);
        prefs.edit().remove("credit/"+pkg).apply();
        QuotaBudget b=get(pkg);if(b.round!=0||b.restUntil!=0){b.round=0;b.restUntil=0;save(pkg);}
    }
    void focus(String pkg){focus(pkg,sessionOn);}
    void focus(String pkg,boolean session){
        long t=SystemClock.uptimeMillis();boolean changed=!Objects.equals(owner,pkg);
        if(owner!=null){QuotaBudget b=get(owner);long old=b.round;account(b,Math.max(0,t-from));if(!sessionOn)b.round=old;if(changed||t-lastSave>=5000){save(owner);lastSave=t;}}
        if(changed&&pkg!=null){account(get(pkg),0);lastSave=t;}
        owner=pkg;from=t;sessionOn=session;
    }
    QuotaBudget view(String pkg){QuotaBudget b=get(pkg);account(b,0);return b;}
    void floor(String pkg,long date,long observed){
        if(pkg.equals(owner))focus(owner);
        QuotaBudget b=view(pkg);if(b.date==date&&b.spent<observed){b.spent=observed;save(pkg);}
    }
    long unreportedRound(String pkg){QuotaBudget b=view(pkg);return Math.max(0,b.round-prefs.getLong("credit/"+pkg,0));}
    void credit(String pkg){prefs.edit().putLong("credit/"+pkg,view(pkg).round).apply();}
    void returned(String pkg,int reason,UseLimits l){
        focus(null);QuotaBudget b=view(pkg);b.returned(reason,l,System.currentTimeMillis());if(reason!=QuotaBudget.REST)prefs.edit().remove("credit/"+pkg).apply();save(pkg);
    }
}
