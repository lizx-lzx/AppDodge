package dev.local.appdodge;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Edits a draft; only an explicit confirmation changes the management window. */
final class ManagementEditor {
    private final MainActivity a;
    private final NativeRestConfig config;
    private final int ink, muted, accent, paper, line, warning;
    private final int[] minute=new int[2];
    private final TextView[] time=new TextView[2];
    private final LinearLayout[] cells=new LinearLayout[2];
    private Dialog sheet;
    private AlertDialog stopDialog;
    private TextView status, note, confirm, stop;

    ManagementEditor(MainActivity activity,NativeRestConfig config) {
        this.a=activity; this.config=config;
        ink=a.getColor(R.color.daily_ink); muted=a.getColor(R.color.daily_muted);
        accent=a.getColor(R.color.daily_green); paper=a.getColor(R.color.daily_sheet);
        line=a.getColor(R.color.daily_line); warning=a.getColor(R.color.daily_warm_ink);
    }
    private int d(int dp){return Math.round(dp*a.getResources().getDisplayMetrics().density);}
    private TextView text(String words,int size,int color,boolean strong){
        TextView v=new TextView(a);v.setText(words);v.setTextSize(size);v.setTextColor(color);
        v.setIncludeFontPadding(false);v.setLineSpacing(d(3),1);
        if(strong)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        return v;
    }
    private void add(LinearLayout parent,View v,int margin){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=d(margin);parent.addView(v,p);
    }
    private void action(View v,int color,int stroke,Runnable run){
        v.setBackground(JournalLine.hit(a,color,stroke));v.setFocusable(true);v.setOnClickListener(it->run.run());
    }
    private String range(int start,int end){return Rule.time(start)+"–"+(end<start?"次日 ":"")+Rule.time(end);}

    void show(){
        ManagementWindow.State state=config.managementWindow();
        minute[0]=state.pending&&state.pendingEnabled?state.pendingStart:state.enabled?state.start:20*60;
        minute[1]=state.pending&&state.pendingEnabled?state.pendingEnd:state.enabled?state.end:20*60+15;
        sheet=new Dialog(a,R.style.DailyDialogTheme);
        Window w=sheet.getWindow();w.requestFeature(Window.FEATURE_NO_TITLE);w.setGravity(Gravity.BOTTOM);
        w.setBackgroundDrawable(new ColorDrawable(0));w.setNavigationBarColor(paper);
        WindowManager.LayoutParams attrs=w.getAttributes();attrs.dimAmount=.30f;w.setAttributes(attrs);
        ScrollView scroll=new ScrollView(a);
        GradientDrawable bg=new GradientDrawable();float radius=d(14);
        bg.setCornerRadii(new float[]{radius,radius,radius,radius,0,0,0,0});bg.setColor(paper);scroll.setBackground(bg);
        LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(d(24),d(9),d(24),d(18));
        scroll.addView(body);
        View handle=new View(a);handle.setBackgroundColor(line);
        LinearLayout.LayoutParams grab=new LinearLayout.LayoutParams(d(36),d(2));grab.gravity=Gravity.CENTER_HORIZONTAL;body.addView(handle,grab);
        LinearLayout head=new LinearLayout(a);head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("管理时段",23,ink,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView back=text("取消",16,muted,false);back.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        action(back,line,0,sheet::dismiss);head.addView(back,new LinearLayout.LayoutParams(d(54),d(48)));add(body,head,4);
        status=text("",16,ink,false);add(body,status,9);
        LinearLayout hours=new LinearLayout(a);add(body,hours,12);
        for(int i=0;i<2;i++){
            final int index=i;
            LinearLayout cell=cells[i]=new LinearLayout(a);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(0,d(9),0,d(8));cell.addView(text(i==0?"开始":"结束",14,muted,false));
            time[i]=text("",28,ink,false);time[i].setFontFeatureSettings("tnum");time[i].setPadding(0,d(2),0,0);cell.addView(time[i]);
            action(cell,line,1,()->pick(index));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
            if(i==1)p.leftMargin=d(18);hours.addView(cell,p);
        }
        note=text("",15,muted,false);note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(body,note,14);
        confirm=text("",17,accent,true);confirm.setGravity(Gravity.CENTER);confirm.setMinimumHeight(d(54));
        action(confirm,accent,2,()->persist(true));add(body,confirm,13);
        stop=text("停用管理时段",15,warning,false);stop.setGravity(Gravity.CENTER);stop.setMinimumHeight(d(48));
        action(stop,line,0,this::confirmStop);add(body,stop,4);
        // A first-time setup can stay open across a proposed start/end. Its consent
        // should still describe what enabling the draft would do at this minute.
        scroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            private final Runnable sync=new Runnable(){public void run(){
                scroll.removeCallbacks(this);bind();
                if(stopDialog!=null&&stopDialog.isShowing()){
                    ManagementWindow.State p=config.previewManagementWindow(false,0,0);
                    if(p!=null&&p.pending)stopDialog.setMessage(stopMessage(p));
                    else stopDialog.dismiss();
                }
                scroll.postDelayed(this,60_000-System.currentTimeMillis()%60_000);
            }};
            public void onViewAttachedToWindow(View v){a.managementClockListener(sync);sync.run();}
            public void onViewDetachedFromWindow(View v){a.managementClockListener(null);scroll.removeCallbacks(sync);}
        });
        sheet.setContentView(scroll);
        if(a.showLayer(sheet))w.setLayout(Math.min(a.getWindowManager().getCurrentWindowMetrics().getBounds().width(),d(560)),WindowManager.LayoutParams.WRAP_CONTENT);
    }
    private void pick(int i){
        int value=minute[i];
        a.showLayer(new TimePickerDialog(a,(picker,h,m)->{minute[i]=h*60+m;bind();},value/60,value%60,true));
    }
    private void bind(){
        ManagementWindow.State old=config.managementWindow();
        boolean valid=minute[0]!=minute[1];
        for(int i=0;i<2;i++){
            String value=(i==1&&minute[1]<minute[0]?"次日 ":"")+Rule.time(minute[i]);
            time[i].setText(value);cells[i].setContentDescription((i==0?"开始 ":"结束 ")+value);
        }
        boolean same=old.pending ? old.pendingEnabled&&old.pendingStart==minute[0]&&old.pendingEnd==minute[1]
                :old.enabled&&old.start==minute[0]&&old.end==minute[1];
        status.setText(old.pending ? (old.enabled?"当前开放："+range(old.start,old.end):"当前可随时打开")+"\n"+MainActivity.when(old.effectiveAt)
                +(old.pendingEnabled?" 起改为 "+range(old.pendingStart,old.pendingEnd):" 起停用")
                : "每天只在这个时段可以打开反人性软件。");
        ManagementWindow.State preview=valid?config.previewManagementWindow(true,minute[0],minute[1]):null;
        String hint;
        if(!valid)hint="开始和结束不能相同。";
        else if(preview==null)hint="当前不能修改。";
        else if(preview.pending&&!same)hint="新时段从"+MainActivity.when(preview.effectiveAt)+"起生效。";
        else if(!old.enabled&&!old.pending)hint=preview.open?"启用后，管理页将在"+MainActivity.when(preview.nextBoundary)+"关闭。"
                :"启用后会立即退出。下次开放："+MainActivity.when(preview.nextOpen)+"。";
        else if(same)hint=old.pending?"":"到 "+Rule.time(old.end)+" 自动退出，已有规则继续执行。";
        else if(!preview.pending)hint="保存后撤回待生效的更改，沿用当前时段。";
        else hint="新时段从"+MainActivity.when(preview.effectiveAt)+"起生效。";
        note.setText(hint);note.setVisibility(hint.isEmpty()?View.GONE:View.VISIBLE);
        note.setTextColor(valid?muted:warning);
        confirm.setText(old.enabled||old.pending?"保存更改":preview!=null&&preview.pending?"预约启用":"启用管理时段");confirm.setEnabled(preview!=null&&!same);
        confirm.setAlpha(confirm.isEnabled()?1f:.45f);
        stop.setVisibility(old.enabled&&(!old.pending||old.pendingEnabled)?View.VISIBLE:View.GONE);
    }
    private String stopMessage(ManagementWindow.State preview){
        return "从"+MainActivity.when(preview.effectiveAt)+"起，可以随时打开反人性软件。已有应用规则不变。";
    }
    private void confirmStop(){
        if(!a.editable())return;
        ManagementWindow.State preview=config.previewManagementWindow(false,0,0);
        if(preview==null||!preview.pending)return;
        stopDialog=new AlertDialog.Builder(a).setTitle("停用管理时段？").setMessage(stopMessage(preview))
                .setNegativeButton("保留时段",null).setPositiveButton("确认停用",(d,w)->persist(false)).create();
        a.showLayer(stopDialog);
    }
    private void persist(boolean enabled){
        if(!a.editable())return;
        if(config.setManagementWindow(enabled,minute[0],minute[1])){
            sheet.dismiss();a.managementChanged();
        } else {
            if(!a.editable())return;
            note.setText("未能保存管理时段，请重试。");note.setVisibility(View.VISIBLE);note.setTextColor(warning);
        }
    }
}
