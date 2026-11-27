package dev.local.appdodge;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.time.*;
import java.util.*;

/** One row per actual intervention. NULL duration means the target did not open. */
final class BlockLedger extends SQLiteOpenHelper {
    private static BlockLedger shared;
    static synchronized BlockLedger get(Context c){if(shared==null)shared=new BlockLedger(c.getApplicationContext(),"interceptions.db");return shared;}
    BlockLedger(Context c,String file){super(c,file,null,1);}
    public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE blocks(id INTEGER PRIMARY KEY, at INTEGER NOT NULL, day INTEGER NOT NULL, pkg TEXT NOT NULL, label TEXT NOT NULL, reason TEXT NOT NULL, ms INTEGER)");
        db.execSQL("CREATE INDEX by_day_app ON blocks(day,pkg)");
    }
    public void onUpgrade(SQLiteDatabase db,int from,int to){throw new IllegalStateException();}
    void record(String pkg,String label,String reason,long ms){
        long at=System.currentTimeMillis();ContentValues r=new ContentValues();
        r.put("at",at);r.put("day",Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay());
        r.put("pkg",pkg);r.put("label",label);r.put("reason",reason);if(ms>=0)r.put("ms",ms);
        getWritableDatabase().insertOrThrow("blocks",null,r);
    }
    static final class Totals{
        String name;long count,exits,ms;
        void add(Totals t){count+=t.count;exits+=t.exits;ms+=t.ms;}
        String average(){return exits==0?"—":duration(ms/exits);}
    }
    static String duration(long ms){
        if(ms<1000)return "不足 1 秒";
        long sec=ms/1000,min=sec/60,h=min/60;
        return h>0?h+" 小时"+(min%60==0?"":" "+min%60+" 分钟"):min>0?min+" 分钟"+(sec%60==0?"":" "+sec%60+" 秒"):sec+" 秒";
    }
    List<Totals> apps(Long day){
        ArrayList<Totals> rows=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery(
          "SELECT label,count(*),count(ms),coalesce(sum(ms),0),max(id) FROM blocks "+(day==null?"":"WHERE day=? ")+"GROUP BY pkg ORDER BY count(*) DESC, max(id) DESC",
          day==null?null:new String[]{day.toString()})){while(c.moveToNext()){Totals t=new Totals();t.name=c.getString(0);t.count=c.getLong(1);t.exits=c.getLong(2);t.ms=c.getLong(3);rows.add(t);}}return rows;
    }
    long count(Long day){return android.database.DatabaseUtils.longForQuery(getReadableDatabase(),"SELECT count(*) FROM blocks"+(day==null?"":" WHERE day=?"),day==null?null:new String[]{day.toString()});}
    static final int PAGE=100;
    List<GuardLog.Entry> recent(Long day,int page){ArrayList<GuardLog.Entry> rows=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT at,label,reason,ms FROM blocks"+(day==null?"":" WHERE day=?")+" ORDER BY id DESC LIMIT "+PAGE+" OFFSET ?",day==null?new String[]{Long.toString((long)page*PAGE)}:new String[]{day.toString(),Long.toString((long)page*PAGE)})){
        while(c.moveToNext())rows.add(new GuardLog.Entry(c.getLong(0),c.getString(1)+" · "+c.getString(2),c.isNull(3)?"打开前拦住":"使用后退出 · "+duration(c.getLong(3))));}return rows;}
}
