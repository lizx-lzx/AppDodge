package dev.local.appdodge;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.List;

/** The policy switch and the queue are separate pages; neither one changes policy just by opening it. */
final class CoolingSheet {
    private final MainActivity a;
    private final NativeRestConfig config;
    private final int paper,ink,muted,accent,line;
    private Dialog sheet;
    private Switch toggle;
    private TextView explanation;
    private LinearLayout entries;
    private boolean pendingPage;
    CoolingSheet(MainActivity a,NativeRestConfig config){
        this.a=a;this.config=config;paper=color(R.color.daily_sheet);ink=color(R.color.daily_ink);
        muted=color(R.color.daily_muted);accent=color(R.color.daily_green);line=color(R.color.daily_line);
    }
    private int color(int id){return a.getColor(id);}
    private int d(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    private LinearLayout col(){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);
        t.setLineSpacing(d(3),1);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;
    }
    private void weight(LinearLayout row,View child){row.addView(child,new LinearLayout.LayoutParams(0,-2,1));}
    private void hit(View v,Runnable f){v.setFocusable(true);v.setBackground(JournalLine.hit(a,line,0));v.setOnClickListener(w->f.run());}
    private void divider(LinearLayout p){View v=new View(a);v.setBackgroundColor(line);p.addView(v,new LinearLayout.LayoutParams(-1,d(1)));}
    void show(){open(false);}
    void showPending(){open(true);}
    private void open(boolean pending){
        pendingPage=pending;
        sheet=new Dialog(a,R.style.DailyAppTheme);Window w=sheet.getWindow();w.requestFeature(Window.FEATURE_NO_TITLE);
        w.setDecorFitsSystemWindows(false);w.setStatusBarColor(paper);w.setNavigationBarColor(paper);
        LinearLayout body=col();body.setBackgroundColor(paper);
        body.setOnApplyWindowInsetsListener((v,b)->{
            android.graphics.Insets i=b.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(i.left+d(24),i.top+d(8),i.right+d(24),i.bottom+d(12));return b;
        });
        LinearLayout header=new LinearLayout(a);header.setGravity(Gravity.CENTER_VERTICAL);weight(header,text(pending?"待生效的更改":"24 小时冷静期",24,ink,true));
        TextView back=text("返回",16,accent,false);back.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        back.setMinHeight(d(52));back.setMinWidth(d(54));hit(back,sheet::dismiss);header.addView(back);body.addView(header);
        ScrollView scroll=new ScrollView(a);LinearLayout inside=col();scroll.addView(inside);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(!pending){
            toggle=new Switch(a);toggle.setText("开启冷静期");toggle.setTextSize(18);toggle.setTextColor(ink);
            toggle.setMinimumHeight(d(64));toggle.setOnClickListener(v->toggle());inside.addView(toggle);
            divider(inside);explanation=text("",16,muted,false);explanation.setPadding(0,d(14),0,d(8));inside.addView(explanation);
        }
        entries=col();inside.addView(entries);
        scroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            private final Runnable update=CoolingSheet.this::bind;
            public void onViewAttachedToWindow(View v){a.addCoolingListener(update);bind();}
            public void onViewDetachedFromWindow(View v){a.removeCoolingListener(update);}
        });
        sheet.setContentView(body);if(a.showLayer(sheet))w.setLayout(-1,-1);
    }
    private void toggle(){
        boolean was=config.coolingEnabled();toggle.setChecked(was);
        if(!a.editable())return;
        if(was){
            a.showLayer(new AlertDialog.Builder(a).setTitle("预约关闭冷静期？")
                .setMessage("24 小时后关闭。已经预约的规则仍按各自的时间生效。")
                .setNegativeButton("保留",null).setPositiveButton("预约关闭",(dialog,which)->set(false)).create());
        }else set(true);
    }
    private void set(boolean enabled){
        if(!a.editable())return;
        if(!config.setCoolingEnabled(enabled))a.feedback("未能更改冷静期。");
        a.managementChanged();
    }
    private void bind(){
        entries.removeAllViews();
        if(!pendingPage){
            CoolingChange close=config.pendingChanges().stream().filter(c->"disable".equals(c.kind)).findFirst().orElse(null);
            boolean enabled=config.coolingEnabled();toggle.setChecked(enabled);toggle.setEnabled(close==null);
            explanation.setText(enabled?"修改规则、保护状态或管理时段，要等满 24 小时。关闭冷静期也一样。":"开启后立即保护；以后修改设置要等满 24 小时。");
            if(close!=null)entry(close);return;
        }
        List<CoolingChange> changes=config.scheduledChanges();
        if(changes.isEmpty()){
            TextView empty=text("没有待生效的更改",17,muted,false);empty.setPadding(0,d(24),0,0);entries.addView(empty);return;
        }
        for(CoolingChange p:changes)entry(p);
    }
    private void entry(CoolingChange p){
        divider(entries);LinearLayout block=col();block.setPadding(0,d(8),0,d(14));entries.addView(block);
        LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(p.title,17,ink,true);weight(row,title);block.addView(row);
        boolean handover="window_handover".equals(p.kind);
        TextView action=text(handover?"查看时段":"撤回",16,accent,true);
        action.setContentDescription((handover?"查看：":"撤回：")+p.title);
        action.setMinHeight(d(48));action.setMinWidth(d(64));action.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);row.addView(action);
        hit(action,()->{
            if(!a.editable())return;
            if(handover){a.manageWindow();return;}
            a.feedback(config.cancelChange(p.key)?"已撤回":"这项更改已不在等待中。");a.managementChanged();
        });
        if(!p.pkg.isEmpty()){
            Rule current=config.find(p.pkg),next="remove".equals(p.kind)?null:config.draftRule(p.pkg);
            RuleChangeText diff=RuleChangeText.compare(current,next,a::appLabel);
            TextView now=text("当前："+diff.before,15,ink,false);block.addView(now);
            TextView nextLine=text(MainActivity.when(p.effectiveAt)+" 起："+diff.after,15,color(R.color.daily_warm_ink),false);
            nextLine.setPadding(0,d(5),0,0);block.addView(nextLine);
            Rule linked=next!=null?next:current;
            if(linked!=null){title.setText(p.title+"  ›");title.setMinHeight(d(48));title.setGravity(Gravity.CENTER_VERTICAL);
                hit(title,()->a.editRule(p.pkg,linked.label));}
        }else{
            if("window".equals(p.kind)){
                title.setText(p.title+"  ›");title.setMinHeight(d(48));title.setGravity(Gravity.CENTER_VERTICAL);
                hit(title,a::manageWindow);
            }
            block.addView(text(MainActivity.when(p.effectiveAt,true)+" 生效",15,muted,false));
        }
    }
}
