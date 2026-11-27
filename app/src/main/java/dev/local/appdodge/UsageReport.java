package dev.local.appdodge;

import android.app.AppOpsManager;
import android.app.usage.*;
import android.content.*;
import android.os.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Optional daily quota reconciliation for selected packages only; no report history is written. */
final class UsageReport {
    static final ExecutorService io=Executors.newSingleThreadExecutor();
    final Context ctx;
    UsageReport(Context c){ctx=c.getApplicationContext();}
    static boolean allowed(Context c){return c.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;}
    void read(Set<String> targets,Consumer<Map<Long,UsageSeries.Day>> ready){
        io.execute(()->{Map<Long,UsageSeries.Day> days=load(targets);new Handler(Looper.getMainLooper()).post(()->ready.accept(days));});
    }
    private Map<Long,UsageSeries.Day> load(Set<String> targets){
        ZoneId zone=ZoneId.systemDefault();UsageSeries series=new UsageSeries(zone);
        if(!allowed(ctx))return series.days;
        long now=System.currentTimeMillis(),from=LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        UsageEvents stream;try{stream=ctx.getSystemService(UsageStatsManager.class).queryEvents(from,now);}catch(SecurityException e){return series.days;}
        if(stream!=null){UsageEvents.Event e=new UsageEvents.Event();while(stream.getNextEvent(e)){
            String pkg=e.getPackageName();boolean selected=targets.contains(pkg);
            series.event(e.getTimeStamp(),e.getEventType(),selected?pkg:null,selected?e.getClassName():null);
        }series.finish(now);}
        return series.days;
    }
}
