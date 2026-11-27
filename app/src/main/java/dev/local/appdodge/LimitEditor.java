package dev.local.appdodge;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.*;
import java.util.function.Consumer;

final class LimitEditor extends JournalPage {
    LimitEditor(MainActivity a){super(a);}
    EditText number(LinearLayout p,String name,int value){
        TextView label=text(name,14,muted,false);label.setPadding(0,d(10),0,d(2));p.addView(label);
        EditText e=new EditText(a);
        e.setSingleLine();e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setContentDescription(name);
        e.setHint("不设置");e.setHintTextColor(color(R.color.daily_faint));
        e.setTextColor(ink);e.setTextSize(17);e.setFontFeatureSettings("tnum");
        e.setMinimumHeight(d(46));e.setPadding(0,d(6),0,d(8));
        e.setBackground(new JournalLine(a,color(R.color.daily_line_strong),1,false));
        e.setOnFocusChangeListener((v,focused)->v.setBackground(new JournalLine(a,focused?green:color(R.color.daily_line_strong),focused?2:1,false)));
        if(value>0)e.setText(String.valueOf(value));p.addView(e);return e;
    }
    static int read(EditText e){
        String raw=e.getText().toString().trim();if(raw.isEmpty())return 0;
        try{int n=Integer.parseInt(raw);if(n>=0&&n<=1440)return n;}catch(NumberFormatException ignored){}
        e.setError("可填 1～1440 分钟，或留空");e.requestFocus();throw new IllegalArgumentException();
    }
    void show(UseLimits initial,Consumer<UseLimits> save){
        LinearLayout p=col();p.setPadding(d(24),d(12),d(24),d(10));p.setFocusableInTouchMode(true);
        EditText one=number(p,"单次使用上限（分钟）",initial.session);
        EditText day=number(p,"单日使用上限（分钟）",initial.day);
        EditText rest=number(p,"单次用满后休息（分钟）",initial.rest);
        Switch schedule=new Switch(a);schedule.setText("同时启用休息时段");schedule.setChecked(!initial.noSchedule);p.addView(schedule);
        p.addView(text("离开或锁屏暂停计时。单日用满后，当天不再放行。只想限时，可关闭休息时段。",14,muted,false));
        ScrollView scroll=new ScrollView(a);scroll.addView(p);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("使用上限").setView(scroll).setNegativeButton("取消",null).setPositiveButton("确定",null).create();
        if(a.showLayer(dialog))dialog.getButton(-1).setOnClickListener(v->{try{
            int s=read(one),d=read(day),r=read(rest);if(s==0&&r>0){one.setError("先设置单次上限");return;}
            save.accept(new UseLimits(s,d,r,!schedule.isChecked()));dialog.dismiss();
        }catch(IllegalArgumentException invalid){}});
    }
}
