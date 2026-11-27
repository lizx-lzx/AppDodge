package dev.local.appdodge;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Pure track-line period selector with liquid-stretching ink cursor. */
final class ReportPeriod extends JournalPage {
    private static final String TODAY="today", YESTERDAY="yesterday", DATE="date", ALL="all";
    private static final String[] MODES=new String[]{TODAY,YESTERDAY,ALL};
    private static final String[] LABELS=new String[]{"今天","昨天","累计"};
    private String mode=TODAY;
    private int lastIndex=0;
    private LocalDate chosen=LocalDate.now();
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("M月d日",java.util.Locale.CHINA);

    ReportPeriod(MainActivity a,Bundle saved){
        super(a);
        if(saved!=null){
            mode=saved.getString("blockPeriod",TODAY);
            chosen=LocalDate.ofEpochDay(saved.getLong("blockDate",chosen.toEpochDay()));
            lastIndex=activeIndex();
        }
    }
    void save(Bundle out){out.putString("blockPeriod",mode);out.putLong("blockDate",chosen.toEpochDay());}
    void all(){mode=ALL;}
    private int activeIndex(){
        for(int i=0;i<MODES.length;i++)if(MODES[i].equals(mode))return i;
        return -1;
    }
    private LocalDate date(){
        if(ALL.equals(mode))return null;
        if(DATE.equals(mode))return chosen;
        return YESTERDAY.equals(mode)?LocalDate.now().minusDays(1):LocalDate.now();
    }
    Long key(){LocalDate date=date();return date==null?null:date.toEpochDay();}
    String caption(){
        if(ALL.equals(mode))return "累计拦截";
        if(TODAY.equals(mode))return "今日拦截";
        if(YESTERDAY.equals(mode))return "昨日拦截";
        return STAMP.format(chosen)+" 拦截";
    }

    void controls(LinearLayout page,Runnable redraw){
        FrameLayout trackWrap=new FrameLayout(a);

        LinearLayout outer=row();
        final TextView[] tabs=new TextView[MODES.length];
        for(int i=0;i<MODES.length;i++){
            final String kind=MODES[i];
            boolean active=kind.equals(mode);
            TextView tab=text(LABELS[i],14,active?green:muted,active);
            tab.setMinHeight(d(42));
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(d(2),0,d(2),0);
            tab.setSelected(active);
            FluidMotion.attachPress(tab,0.95f);
            tab.setOnClickListener(v->{
                if(kind.equals(mode))return;
                lastIndex=activeIndex();
                mode=kind;
                redraw.run();
            });
            tabs[i]=tab;
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
            lp.setMarginEnd(d(22));
            outer.addView(tab,lp);
        }

        View spacer=new View(a);
        outer.addView(spacer,new LinearLayout.LayoutParams(0,d(1),1));

        LocalDate date=date();
        boolean customDate=DATE.equals(mode);
        TextView calendar=text((date==null?"选日期":STAMP.format(date))+" ▾",13,customDate?green:muted,customDate);
        calendar.setMinHeight(d(42));
        calendar.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        calendar.setPadding(d(4),0,0,0);
        if(customDate)calendar.setBackground(new JournalLine(a,green,2,false));
        calendar.setContentDescription("选择日期");
        calendar.setSelected(customDate);
        FluidMotion.attachPress(calendar,0.95f);
        calendar.setOnClickListener(v->pick(redraw));
        outer.addView(calendar,new LinearLayout.LayoutParams(-2,-2));

        trackWrap.addView(outer,new FrameLayout.LayoutParams(-1,-2));

        // Base 1dp horizontal track rule at bottom
        View baseTrack=new View(a);
        baseTrack.setBackgroundColor(line);
        FrameLayout.LayoutParams baseLp=new FrameLayout.LayoutParams(-1,Math.max(1,d(1)));
        baseLp.gravity=Gravity.BOTTOM;
        trackWrap.addView(baseTrack,baseLp);

        // Moving 2dp moss-green track cursor line
        View cursor=new View(a);
        cursor.setBackgroundColor(green);
        int current=activeIndex();
        cursor.setVisibility(current>=0?View.VISIBLE:View.INVISIBLE);
        FrameLayout.LayoutParams curLp=new FrameLayout.LayoutParams(d(26),d(2));
        curLp.gravity=Gravity.BOTTOM|Gravity.START;
        trackWrap.addView(cursor,curLp);

        if(current>=0){
            final int fromIdx=lastIndex>=0?lastIndex:current;
            final int toIdx=current;
            trackWrap.post(()->{
                TextView target=tabs[toIdx];
                if(target.getWidth()<=0)return;
                float toCx=target.getLeft()+target.getWidth()/2f;
                int baseW=Math.max(d(24),target.getWidth()-d(2));
                if(fromIdx!=toIdx&&fromIdx>=0&&fromIdx<tabs.length&&tabs[fromIdx].getWidth()>0){
                    float fromCx=tabs[fromIdx].getLeft()+tabs[fromIdx].getWidth()/2f;
                    FluidMotion.glideTrackLine(cursor,fromCx,toCx,baseW,true);
                }else{
                    FluidMotion.glideTrackLine(cursor,toCx,toCx,baseW,false);
                }
                lastIndex=toIdx;
            });
        }

        page.addView(trackWrap,new LinearLayout.LayoutParams(-1,-2));
    }

    private void pick(Runnable redraw){
        LocalDate selected=date();if(selected==null)selected=LocalDate.now();
        DatePickerDialog dialog=new DatePickerDialog(a,(view,y,m,d)->{
            lastIndex=activeIndex();
            chosen=LocalDate.of(y,m+1,d);mode=DATE;redraw.run();
        },selected.getYear(),selected.getMonthValue()-1,selected.getDayOfMonth());
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.setButton(android.content.DialogInterface.BUTTON_POSITIVE,"确定",dialog);
        dialog.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,"取消",dialog);
        a.showReadOnlyLayer(dialog);
    }
}
