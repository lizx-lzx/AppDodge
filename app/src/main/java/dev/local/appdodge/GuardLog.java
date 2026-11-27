package dev.local.appdodge;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.*;

/** Only this process's lifecycle and user-enrolled rule actions. No AccessibilityEvent payloads. */
final class GuardLog {
    static final int LIMIT=100;
    static final class Entry {
        final long at; final String title,detail;
        Entry(long at,String title,String detail){this.at=at;this.title=title;this.detail=detail;}
    }
    private final Context app;
    private final SharedPreferences prefs;
    GuardLog(Context context){
        app=context.getApplicationContext();prefs=app.getSharedPreferences("guard_journal",Context.MODE_PRIVATE);
    }
    void add(String title,String detail){
        // All writers are on the application main thread. One updated slot, not a rewrite of the log.
        long seq=prefs.getLong("next",0),now=System.currentTimeMillis();
        JSONArray row=new JSONArray().put(now).put(title).put(detail);
        prefs.edit().putString("e"+(seq%LIMIT),row.toString()).putLong("next",seq+1).apply();
    }
    List<Entry> list(){
        ArrayList<Entry> entries=new ArrayList<>();
        long next=prefs.getLong("next",0);
        for(long i=next-1;i>=Math.max(0,next-LIMIT);i--){
            String raw=prefs.getString("e"+(i%LIMIT),null);
            if(raw!=null)try{JSONArray row=new JSONArray(raw);
                entries.add(new Entry(row.getLong(0),row.getString(1),row.getString(2)));
            }catch(JSONException ignored){/* one damaged event must not hide the later ones */}
        }
        ActivityManager am=app.getSystemService(ActivityManager.class);
        if(am!=null)for(ApplicationExitInfo info:am.getHistoricalProcessExitReasons(app.getPackageName(),0,LIMIT)){
            if(!app.getPackageName().equals(info.getProcessName()))continue;
            Entry row=exit(info.getTimestamp(),info.getReason(),info.getDescription());
            if(row!=null)entries.add(row);
        }
        return recent(entries);
    }
    static List<Entry> recent(List<Entry> events){
        ArrayList<Entry> ordered=new ArrayList<>(events);
        ordered.sort((left,right)->Long.compare(right.at,left.at));
        return ordered.subList(0,Math.min(LIMIT,ordered.size()));
    }
    static Entry exit(long at,int reason,String description){
        String d=description==null?"":description.toLowerCase(Locale.ROOT);
        if(reason==ApplicationExitInfo.REASON_PACKAGE_UPDATED || (reason==ApplicationExitInfo.REASON_USER_REQUESTED && d.contains("installpackageli")))
            return new Entry(at,"反人性软件已更新","系统记录 · 安装新版时结束了旧进程");
        if(reason==ApplicationExitInfo.REASON_USER_REQUESTED){
            if(d.contains("single-cleaner"))return new Entry(at,"系统强行停止了反人性软件","系统记录 · 系统清理工具停止了进程");
            if(d.contains("force stop")||d.contains("force-stop"))
                return new Entry(at,"系统强行停止了反人性软件","系统记录 · 保护在进程停止时中断");
            return new Entry(at,"反人性软件进程已停止","系统记录 · 收到停止请求，具体操作未知");
        }
        if(reason==ApplicationExitInfo.REASON_LOW_MEMORY)return new Entry(at,"系统回收了反人性软件","系统记录 · 内存不足");
        if(reason==ApplicationExitInfo.REASON_CRASH||reason==ApplicationExitInfo.REASON_CRASH_NATIVE||reason==ApplicationExitInfo.REASON_ANR)
            return new Entry(at,reason==ApplicationExitInfo.REASON_ANR?"反人性软件未响应":"反人性软件异常退出","系统记录");
        return null;
    }
    void block(NativeTarget target,boolean external,String choice){
        String entry=external?"已退回桌面":"原图标未打开";
        add("已拦截"+target.label,choice.isEmpty()?entry:entry+" · 抽中"+choice);
    }
    static String responses(int mask){
        ArrayList<String> parts=new ArrayList<>();
        if((mask&SharedBasket.HOME_LOAD)!=0)parts.add("假装加载");
        if((mask&SharedBasket.HOME_REDIRECT)!=0)parts.add("自动改道");
        for(FeedbackChoices opt:FeedbackChoices.values())if((mask&opt.bit)!=0)parts.add(opt.label);
        return String.join("＋",parts);
    }
}
