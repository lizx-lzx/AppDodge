package dev.local.appdodge;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.*;
import java.util.ArrayList;
import java.util.Arrays;

/** One working basket per application. Time periods change when it fires, never which boxes are checked. */
final class DailyRuleEditor {
    private final MainActivity a;
    private final NativeRestConfig config;
    private final String pkg,label;
    private final Runnable refresh;
    private final Rule existing;
    private final int ink,muted,faint,accent,line,paper;
    private final ArrayList<int[]> periods=new ArrayList<>();
    private boolean editingDay,restCombo,dayCombo,dayByEffects,adopted,policyPicked,stopAfterReturn;
    private int dailyChance;
    private UseLimits limits;
    private SharedBasket basket;
    private final SharedBasket legacyRest,legacyDay;
    private final String initial,initialPolicy;

    DailyRuleEditor(MainActivity a,NativeRestConfig config,String pkg,String label,Runnable refresh,boolean editingDay){
        this.a=a;this.config=config;this.pkg=pkg;this.label=label;this.refresh=refresh;this.editingDay=editingDay;
        existing=config.draftRule(pkg);limits=existing==null?UseLimits.NONE:existing.limits;
        int now=Store.now();
        if(existing==null) periods.add(new int[]{now,(now+30)%1440});else for(int[] p:existing.restPeriods())periods.add(p.clone());
        boolean legacy=legacy();
        legacyRest=legacy?SharedBasket.fromPolicy(existing.restPolicy()):null;
        legacyDay=legacy&&existing.preferredDaily()!=null?SharedBasket.fromPolicy(existing.preferredDaily()):null;
        basket=existing==null?new SharedBasket(MethodPool.bit(Rule.EXIT_NOW),FeedbackBasket.NEW_DEFAULT,"",false)
                :legacy?legacyRest:existing.sharedBasket;
        stopAfterReturn=existing!=null&&existing.stopAfterReturn;
        dailyChance=existing==null?0:existing.dailyChance;
        dayByEffects=existing==null||!existing.modernPolicy||existing.dayByEffects;
        restCombo=existing!=null&&existing.restCombo;
        dayCombo=existing!=null&&(legacy?existing.preferredDaily()!=null&&existing.preferredDaily().combo:existing.dayCombo);
        ink=a.getColor(R.color.daily_ink);muted=a.getColor(R.color.daily_muted);faint=a.getColor(R.color.daily_faint);accent=a.getColor(R.color.daily_green);
        line=a.getColor(R.color.daily_line);paper=a.getColor(R.color.daily_paper);initialPolicy=policyStamp();initial=stamp();
    }
    private boolean legacy(){return existing!=null&&existing.sharedBasket==null;}
    private boolean changingBehavior(){return !legacy()||adopted||policyPicked||dailyChance!=existing.dailyChance||restCombo!=existing.restCombo
            ||dayCombo!=(existing.preferredDaily()!=null&&existing.preferredDaily().combo);}
    private boolean modernDraft(){return existing==null||existing.modernPolicy||adopted||policyPicked
            ||restCombo!=existing.restCombo||dayCombo!=(legacy()?existing.preferredDaily()!=null&&existing.preferredDaily().combo:existing.dayCombo)
            ||(!legacy()&&!basket.equals(existing.sharedBasket))||legacy()&&changingBehavior();}
    private boolean byEffects(){return editingDay&&dayByEffects;}
    private int[][] ranges(){return periods.toArray(new int[0][]);}
    private static String stamp(SharedBasket b){return b==null?"":b.methods+":"+b.feedback+":"+b.alternate+":"+b.curtain;}
    private String stamp(){return Arrays.deepToString(ranges())+"|"+limits+"|"+stopAfterReturn+"|"+policyStamp();}
    private String policyStamp(){return stamp(basket)+"|"+adopted+"|"+dailyChance+"|"+restCombo+"|"+dayCombo+"|"+dayByEffects+"|"+policyPicked;}
    private boolean changed(){return !initial.equals(stamp());}
    private void discard(String title,Runnable yes){a.showLayer(new AlertDialog.Builder(a).setTitle(title).setNegativeButton("继续编辑",null)
            .setPositiveButton("放弃修改",(x,y)->yes.run()).create());}
    private void leave(Dialog dialog){if(!changed())dialog.dismiss();else discard("放弃未保存的修改？",dialog::dismiss);}
    private int d(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    private TextView t(String s,float sp,int c,boolean strong){
        TextView v=new TextView(a);v.setText(s);v.setTextColor(c);v.setTextSize(sp);v.setIncludeFontPadding(false);
        v.setFontFeatureSettings("tnum");v.setLineSpacing(0f,sp<=13f?1.48f:1.34f);
        v.setTypeface(Typeface.create(strong?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        return v;
    }
    private TextView tLight(String s,float sp,int c){
        TextView v=t(s,sp,c,false);
        v.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
        v.setLetterSpacing(-0.03f);
        return v;
    }
    private TextView eyebrow(String s){
        TextView v=t(s,11.5f,faint,true);
        v.setLetterSpacing(0.08f);
        return v;
    }
    private void add(LinearLayout p,View v,int margin){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=d(margin);p.addView(v,lp);}
    private void bindAction(TextView v,int color,float stroke,Runnable f){
        v.setBackground(JournalLine.hit(a,color,stroke));v.setFocusable(true);
        FluidMotion.attachPress(v,0.96f);v.setOnClickListener(x->f.run());
    }
    private RadioButton tab(String s){
        RadioButton v=new RadioButton(a);v.setId(View.generateViewId());v.setText(s);v.setTextSize(14);v.setMinHeight(d(44));
        v.setPadding(0,0,0,0);v.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{accent,muted}));
        v.setButtonDrawable(null);v.setGravity(Gravity.CENTER);
        FluidMotion.attachPress(v,0.96f);
        return v;
    }
    private void state(RadioButton b,boolean active){
        b.setTypeface(Typeface.create(active?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
    }
    private final class Field extends LinearLayout {
        final TextView title,value;
        Field(String label){
            super(a);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(d(54));setBackground(JournalLine.hit(a,line,1));setFocusable(true);
            FluidMotion.attachPress(this,0.985f);
            title=t(label,15.5f,ink,true);addView(title);
            value=t("",13.5f,muted,false);value.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);value.setPadding(d(10),d(8),0,d(8));
            addView(value,new LinearLayout.LayoutParams(0,-2,1));
            TextView arrow=t("›",19,faint,false);arrow.setPadding(d(7),0,0,0);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(arrow);
        }
        void setValue(String text,boolean valid){value.setText(text);value.setTextColor(valid?muted:a.getColor(R.color.daily_warm_ink));}
    }
    private LinearLayout column(){LinearLayout col=new LinearLayout(a);col.setOrientation(LinearLayout.VERTICAL);return col;}
    private LinearLayout row(){LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
    private TextView action(String text,Runnable click){TextView v=t(text,14,accent,true);v.setGravity(Gravity.CENTER);v.setMinHeight(d(44));bindAction(v,line,0,click);return v;}
    private LinearLayout surface(Dialog dialog){
        Window w=dialog.getWindow();w.requestFeature(Window.FEATURE_NO_TITLE);w.setDecorFitsSystemWindows(false);
        w.setBackgroundDrawable(new ColorDrawable(paper));w.setStatusBarColor(paper);w.setNavigationBarColor(paper);
        LinearLayout shell=column();shell.setBackgroundColor(paper);
        shell.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(d(24)+i.left,d(10)+i.top,d(24)+i.right,d(14)+i.bottom);return insets;});return shell;
    }
    private void heading(LinearLayout shell,String title,String back,Runnable click){
        LinearLayout bar=row();
        TextView h=t(title,26,ink,true);h.setLetterSpacing(-0.025f);
        bar.addView(h,new LinearLayout.LayoutParams(0,-2,1));
        TextView nav=action(back,click);nav.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);bar.addView(nav,new LinearLayout.LayoutParams(d(56),d(48)));add(shell,bar,0);
    }
    private LinearLayout scrolling(LinearLayout shell){
        ScrollView scroll=new ScrollView(a);LinearLayout body=column();body.setPadding(0,0,0,d(16));scroll.addView(body);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));return body;
    }
    private void pickTime(int minute,String title,java.util.function.IntConsumer accept){
        TimePickerDialog picker=new TimePickerDialog(a,(p,h,m)->accept.accept(h*60+m),minute/60,minute%60,true);
        picker.setTitle(title);a.showLayer(picker);
    }
    private void drawHours(LinearLayout body,Runnable bind){
        body.removeAllViews();int n=0;
        for(int[] pair:periods){
            final int index=n++;
            LinearLayout strip=row();
            for(int j=0;j<2;j++){
                int side=j;String name=j==0?"开始":"结束";
                LinearLayout cell=column();cell.setBackground(JournalLine.hit(a,line,1));cell.setPadding(0,d(8),0,d(10));
                cell.addView(eyebrow(name));
                String time=(j==1&&pair[1]<pair[0]?"次日 ":"")+Rule.time(pair[j]);
                TextView val=tLight(time,30,ink);val.setPadding(0,d(4),0,0);cell.addView(val);
                cell.setContentDescription("第 "+(index+1)+" 段"+name+" "+time);
                cell.setFocusable(true);FluidMotion.attachPress(cell,0.97f);
                cell.setOnClickListener(v->pickTime(pair[side],name+"时间",timeValue->{pair[side]=timeValue;bind.run();}));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(j==1)lp.leftMargin=d(18);strip.addView(cell,lp);
            }
            if(periods.size()>1){TextView remove=action("移除",()->{periods.remove(index);bind.run();});remove.setTextColor(faint);remove.setTextSize(13);
                remove.setContentDescription("移除第 "+(index+1)+" 段");strip.addView(remove,new LinearLayout.LayoutParams(d(48),d(48)));}
            add(body,strip,index==0?0:8);
        }
        TextView plus=action("＋ 添加时段",()->{int last=periods.get(periods.size()-1)[1];
            pickTime(last,"新增时段 · 开始",start->pickTime((start+30)%1440,"新增时段 · 结束",end->{periods.add(new int[]{start,end});bind.run();}));});
        plus.setTextSize(13.5f);plus.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);add(body,plus,6);
    }
    private static ArrayList<String> methodNames(int mask){
        ArrayList<String> names=new ArrayList<>();
        for(DailyExitMethod m:DailyExitMethod.displayOrder(true))if((mask&(m==DailyExitMethod.RANDOM?MethodPool.TIMED:MethodPool.bit(m.mode)))!=0)names.add(m.label);
        return names;
    }
    private static ArrayList<String> replyNames(int mask){
        ArrayList<String> names=new ArrayList<>();for(FeedbackChoices e:FeedbackChoices.values())if((mask&e.bit)!=0)names.add(e.label);return names;
    }
    private String noDesktopFeedback(){
        int dailyOnly=basket.methods&~SharedBasket.DIRECT;
        if(!editingDay&&dailyOnly!=0){
            String which=Integer.bitCount(dailyOnly)==1?DailyExitMethod.selectionName(dailyOnly):"使用中反馈";
            return "休息时直接拦住；"+which+"仅在其余时间使用。";
        }
        return "点原图标直接拦住；从最近任务进入则退回桌面。";
    }
    private String periodSummary(){
        if(editingDay&&dailyChance==0)return "0% · 其余时间正常使用。";
        boolean combo=editingDay?dayCombo:restCombo;
        if(byEffects()){
            int eligible=combo?(modernDraft()?basket.effectComboMethodMask():basket.dayMethods(true)):basket.methods;
            ArrayList<String> ways=methodNames(eligible);
            if(modernDraft()&&combo)ways.addAll(replyNames(basket.effectComboFeedbackMask()));
            if(ways.isEmpty())return "没有可搭配的反馈。";
            if(ways.size()==1&&eligible==MethodPool.bit(Rule.EXIT_WAIT_OPEN))return "等 10 秒再点图标，进入 1～2 秒后退回桌面。";
            if(ways.size()==1)return "触发时用「"+ways.get(0)+"」。";
            if(combo&&ways.size()==2)return "触发时一起用"+String.join("＋",ways)+"。";
            return combo?"触发时从 "+ways.size()+" 项里搭配 2～3 项。":"触发时从 "+ways.size()+" 种方式里抽一种。";
        }
        if(combo){
            int tickets=basket.restTickets(true);
            ArrayList<String> options=replyNames(tickets&SharedBasket.REST_RESPONSES);
            if((tickets&SharedBasket.HOME_LOAD)!=0)options.add(DailyExitMethod.LOAD.label);
            if((tickets&SharedBasket.HOME_REDIRECT)!=0)options.add(DailyExitMethod.REDIRECT.label);
            if(options.isEmpty())return noDesktopFeedback();
            String lead=options.size()==1?"桌面只用":options.size()==2?"桌面一起用":"桌面抽 2～3 项：";
            return lead+String.join("、",options)+"。"+(!editingDay&&(basket.methods&(MethodPool.EXPERIENCES|MethodPool.TIMED))!=0?"使用中反馈留给其余时间。":"");
        }
        int immediate=MethodPool.bit(Rule.EXIT_NOW)|MethodPool.bit(Rule.EXIT_ICON);
        if((basket.restIconMethods()&~immediate)!=0)
            return "点原图标时从桌面拦截方式里抽一种。";
        ArrayList<String> responses=replyNames(basket.feedback&~FeedbackBasket.DURATION);
        if(responses.isEmpty())return noDesktopFeedback();
        return responses.size()==1?"拦截后给「"+responses.get(0)+"」。":"拦截后抽一种回应："+String.join("、",responses)+"。";
    }
    private boolean validCombo(boolean daily){
        if(!(daily?dayCombo:restCombo))return true;
        if(!daily || !dayByEffects)return true; // Hard blocks still work with zero or one desktop reply.
        return modernDraft()?basket.canEffectCombo():basket.canDayCombo();
    }
    private String combinationError(boolean daily){
        if(validCombo(daily))return "";
        String period=daily?"其余时间":"休息时段";
        ArrayList<String> ends=methodNames(basket.methods&~MethodPool.EXPERIENCES);
        if(ends.size()>1)return "「"+ends.get(0)+"」和「"+ends.get(1)+"」会给同一次打开不同的结果，不能叠加。可再选轻振，或改为每次一种。";
        ArrayList<String> methods=methodNames(basket.methods);
        if(basket.feedback==0&&methods.size()==1)return period+"只选了「"+methods.get(0)+"」。再选一个能搭配的反馈，或改为每次一种。";
        if((basket.feedback&(FeedbackBasket.NOTICE|FeedbackBasket.COUNTDOWN|FeedbackBasket.LOCK))!=0)
            return "勾选的回应需要先拦住或退回。可勾「桌面提醒」，或把使用中方式与轻振搭配。";
        return period+"还没有能一起执行的两项。可以选卡顿＋消音，或改为每次一种。";
    }
    void show(){
        Dialog dialog=new Dialog(a,R.style.DailyAppTheme){@Override public void onBackPressed(){leave(this);}};
        LinearLayout shell=surface(dialog);heading(shell,label,"返回",()->leave(dialog));LinearLayout body=scrolling(shell),pendingBox=column();
        add(body,pendingBox,4);LinearLayout editor=column();add(body,editor,0);
        Field cap=new Field("使用上限");add(editor,cap,4);

        // Two-tab horizontal track with liquid-stretching moss cursor line
        FrameLayout tabWrap=new FrameLayout(a);
        RadioGroup tabs=new RadioGroup(a);tabs.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton rest=tab("休息时段"),day=tab("其余时间");
        tabs.addView(rest,new RadioGroup.LayoutParams(0,-2,1));
        tabs.addView(day,new RadioGroup.LayoutParams(0,-2,1));
        tabs.check(editingDay?day.getId():rest.getId());
        tabWrap.addView(tabs,new FrameLayout.LayoutParams(-1,-2));
        View tabBaseTrack=new View(a);tabBaseTrack.setBackgroundColor(line);
        FrameLayout.LayoutParams tbLp=new FrameLayout.LayoutParams(-1,Math.max(1,d(1)));tbLp.gravity=Gravity.BOTTOM;
        tabWrap.addView(tabBaseTrack,tbLp);
        View tabCursor=new View(a);tabCursor.setBackgroundColor(accent);
        FrameLayout.LayoutParams tcLp=new FrameLayout.LayoutParams(d(80),d(2));tcLp.gravity=Gravity.BOTTOM|Gravity.START;
        tabWrap.addView(tabCursor,tcLp);
        add(editor,tabWrap,10);

        // Footnote-tier mode explanation (12.5sp muted with quiet left rail)
        TextView policy=t("",12.5f,muted,false);
        policy.setPadding(d(10),d(2),0,d(2));
        policy.setBackground(new JournalLine(a,a.getColor(R.color.daily_line_strong),2,true));
        add(editor,policy,14);
        TextView adopt=t("改用所选反馈",13.5f,accent,true);adopt.setMinHeight(d(44));adopt.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);add(editor,adopt,4);

        LinearLayout hours=column();add(editor,hours,12);
        LinearLayout frequency=column();
        LinearLayout freqHead=row();
        freqHead.setGravity(Gravity.BOTTOM);
        freqHead.addView(eyebrow("日常触发概率"),new LinearLayout.LayoutParams(0,-2,1));
        TextView percent=tLight("",30,accent);freqHead.addView(percent);
        add(frequency,freqHead,0);
        SeekBar slider=new SeekBar(a);slider.setMin(0);slider.setMax(100);slider.setKeyProgressIncrement(1);slider.setMinHeight(d(44));
        slider.setProgressTintList(ColorStateList.valueOf(accent));slider.setThumbTintList(ColorStateList.valueOf(accent));slider.setContentDescription("日常触发概率");
        add(frequency,slider,2);add(editor,frequency,12);

        add(editor,eyebrow("拦截与反馈"),16);
        Field number=new Field("每次触发");add(editor,number,2);
        Field pool=new Field("负反馈");add(editor,pool,0);
        TextView scope=t("",12.5f,muted,false);add(editor,scope,10);

        Switch stop=new Switch(a);stop.setText("拦截后停止后台运行");stop.setTextColor(ink);stop.setTextSize(15.5f);
        stop.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        stop.setMinHeight(d(54));stop.setChecked(stopAfterReturn);stop.setBackground(JournalLine.hit(a,line,1));add(editor,stop,16);
        TextView stopNote=t("只在规则把你退回桌面后停止。自己切走不停止；未保存的进度可能丢失。",12.5f,muted,false);
        add(editor,stopNote,6);
        TextView stopAccess=action("",a::configureStopAccess);stopAccess.setTextSize(13);stopAccess.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);add(editor,stopAccess,2);
        TextView error=t("",12.5f,a.getColor(R.color.daily_warm_ink),false);add(editor,error,8);
        if(existing!=null&&config.find(pkg)!=null){TextView remove=action("移除规则",()->a.confirmRemoveRule(pkg,label,dialog::dismiss));
            remove.setTextSize(13.5f);remove.setTextColor(a.getColor(R.color.daily_warm_ink));remove.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);add(editor,remove,16);}
        TextView save=t("",15.5f,accent,true);save.setGravity(Gravity.CENTER);save.setMinHeight(d(54));add(shell,save,6);
        final boolean[] lastTabDay=new boolean[]{editingDay};
        class Bind implements Runnable{
            String lastRanges="";
            public void run(){
                CoolingChange pending=config.pendingRule(pkg);
                if(existing!=null&&pending==null&&config.find(pkg)==null){
                    if(dialog.isShowing()){dialog.dismiss();a.feedback("这条规则已移除。");}return;
                }
                boolean removing=pending!=null&&"remove".equals(pending.kind);
                renderPending(pendingBox,pending,dialog);editor.setVisibility(removing?View.GONE:View.VISIBLE);
                save.setVisibility(removing?View.GONE:View.VISIBLE);
                state(rest,!editingDay);state(day,editingDay);
                final boolean wasDay=lastTabDay[0];
                final boolean nowDay=editingDay;
                tabWrap.post(()->{
                    int w=tabWrap.getWidth();if(w<=0)return;
                    float slot=w/2f;
                    int barW=Math.round(slot*0.58f);
                    float fromCx=slot*(wasDay?1.5f:0.5f);
                    float toCx=slot*(nowDay?1.5f:0.5f);
                    FluidMotion.glideTrackLine(tabCursor,fromCx,toCx,barW,wasDay!=nowDay);
                    lastTabDay[0]=nowDay;
                });
                hours.setVisibility(editingDay?View.GONE:View.VISIBLE);
                String key=Arrays.deepToString(ranges());if(!lastRanges.equals(key)){drawHours(hours,this);lastRanges=key;}
                frequency.setVisibility(editingDay?View.VISIBLE:View.GONE);if(slider.getProgress()!=dailyChance)slider.setProgress(dailyChance);
                percent.setText(dailyChance+"%");slider.setStateDescription(dailyChance+"%");
                number.setValue((editingDay?dayCombo:restCombo)?"随机 2～3 种":"一种",true);
                int count=Integer.bitCount(basket.methods),replies=Integer.bitCount(basket.feedback);
                String methods=count==1?DailyExitMethod.selectionName(basket.methods):count+" 种方式";
                pool.setValue(methods+(replies==0?"":" · "+replies+" 项回应"),true);
                boolean oldHard=editingDay&&!dayByEffects;
                policy.setText(!editingDay?"每次都拦，不打开应用":oldHard?"抽中就直接拦住":"抽中就拦；进了应用也会退出");
                adopt.setVisibility(oldHard?View.VISIBLE:View.GONE);
                String note=periodSummary();
                if(editingDay&&legacy()&&!changingBehavior()){
                    Rule actual=existing.daytime;
                    pool.setValue(actual==null?"未开启":DailyExitMethod.summary(actual,a)+(actual.feedbackMask==0?"":" · "+Integer.bitCount(actual.feedbackMask)+" 项回应"),true);
                    note=actual==null?"日常拦截已关闭。":"触发后执行原日常反馈。";
                }
                cap.setValue(limits.summary(),true);
                if(!editingDay&&limits.noSchedule){policy.setText("休息时段已关闭");hours.setVisibility(View.GONE);}
                if(editingDay&&dailyChance==0&&limits.timed())note="不随机拦截；用满使用上限仍会退出。";
                scope.setText(note);
                stopNote.setVisibility(stopAfterReturn?View.VISIBLE:View.GONE);
                boolean needsAccess=stopAfterReturn&&StopBridge.state(a)!=StopBridge.State.READY;
                stopAccess.setVisibility(needsAccess?View.VISIBLE:View.GONE);
                if(needsAccess)stopAccess.setText(LocalAdb.paired(a)?"当前只退回桌面 · 恢复连接 ›":"配置停止后台运行 ›");
                String message=!RestPeriods.validate(ranges())?"每段休息的开始和结束不能相同。":
                        !changingBehavior()?"":!validCombo(false)?combinationError(false):combinationError(true);
                error.setText(message);error.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);
                boolean dirty=changed(),enabled=message.isEmpty()&&!removing&&(existing==null||dirty);
                save.setEnabled(enabled);save.setAlpha(enabled?1f:.5f);save.setText(config.coolingEnabled()?
                        (pending!=null&&!dirty?"预约已保存":pending!=null?"更新预约 · 重新等 24 小时":"预约保存 · 24 小时后生效"):"保存规则");
            }
        }
        Bind bind=new Bind();cap.setOnClickListener(v->new LimitEditor(a).show(limits,x->{limits=x;bind.run();}));stop.setOnCheckedChangeListener((v,enabled)->{stopAfterReturn=enabled;bind.run();});tabs.setOnCheckedChangeListener((g,id)->{editingDay=id==day.getId();bind.run();((ScrollView)body.getParent()).scrollTo(0,0);});
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            private boolean dragging;
            public void onProgressChanged(SeekBar s,int value,boolean byUser){
                if(!byUser||dailyChance==value)return;
                dailyChance=value;percent.setText(value+"%");s.setStateDescription(value+"%");
                if(!dragging)bind.run(); // Also update for keyboard and accessibility actions.
            }
            public void onStartTrackingTouch(SeekBar s){dragging=true;}
            public void onStopTrackingTouch(SeekBar s){dragging=false;bind.run();}
        });
        bindAction(adopt,line,0,()->{dayByEffects=true;policyPicked=true;bind.run();});
        number.setOnClickListener(v->a.showLayer(new AlertDialog.Builder(a).setTitle("每次触发").setSingleChoiceItems(new String[]{"一种","随机 2～3 种"},
                (editingDay?dayCombo:restCombo)?1:0,(picker,index)->{if(editingDay)dayCombo=index==1;else restCombo=index==1;picker.dismiss();bind.run();})
                .setNegativeButton("取消",null).create()));
        pool.setOnClickListener(v->new Picker(bind).show());
        bindAction(save,accent,2,()->confirmSave(dialog,bind));
        shell.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){a.addCoolingListener(bind);}public void onViewDetachedFromWindow(View v){a.removeCoolingListener(bind);}
        });
        bind.run();dialog.setContentView(shell);if(a.showLayer(dialog))dialog.getWindow().setLayout(-1,-1);
    }
    private void confirmSave(Dialog dialog,Runnable bind){
        if(!a.editable()||!RestPeriods.validate(ranges())||existing!=null&&!changed())return;
        boolean useShared=changingBehavior(),migrate=legacy()&&useShared;
        Runnable save=()->{
            if(!a.editable())return;
            try {
                int[] first=periods.get(0);
                boolean inverse=existing!=null&&existing.allowInside;
                Rule schedule=new Rule(pkg,label,first[inverse?1:0],first[inverse?0:1],inverse);
                boolean ok;
                if(existing!=null&&initialPolicy.equals(policyStamp()))ok=config.saveSchedule(schedule,ranges(),stopAfterReturn,limits);
                else if(modernDraft()||useShared&&dailyChance!=existing.dailyChance){
                    RestPeriods times=RestPeriods.of(ranges());
                    Rule plan=modernDraft()?Rule.shared(pkg,label,times,basket,dailyChance,restCombo,dayCombo,false,dayByEffects)
                            :Rule.shared(pkg,label,times,basket,dailyChance,restCombo,dayCombo);
                    ok=config.saveEditedShared(plan.withStopAfterReturn(stopAfterReturn),limits);
                }
                else ok=config.saveSchedule(schedule,ranges(),stopAfterReturn,limits);
                if(!ok){a.feedback(config.lockedFor()>0?"规则已锁定，未保存修改":"未能保存，请检查规则。");bind.run();return;}
                CoolingChange pending=config.pendingRule(pkg);a.feedback(pending==null?"已保存"+label+"的每日规则":"已预约："+MainActivity.when(pending.effectiveAt)+"生效");
                dialog.dismiss();refresh.run();
            }catch(RuntimeException e){a.feedback("规则未保存，请重试。");}
        };
        StringBuilder notice=new StringBuilder();
        boolean upgrading=existing!=null&&!existing.modernPolicy&&modernDraft();
        boolean permitting=existing!=null&&existing.modernPolicy&&dayByEffects&&!existing.dayByEffects;
        if(migrate)append(notice,"原来两套选择将由这一篮取代。");
        if(permitting)append(notice,"其余时间触发后，抽到卡顿、消音等反馈时会先进应用，执行完再退回。休息时仍然不放行。");
        else if(upgrading)append(notice,"保存后，休息时一定拦截，日常按设置的概率拦截。");
        if(upgrading&&existing.dayCombo&&dailyChance>0&&dayByEffects)append(notice,"其余时间原本的使用中组合，也会改按这一篮里能搭配的方式和回应抽取。");
        boolean lock=(basket.methods&(MethodPool.bit(Rule.EXIT_LOCK_NOW)|MethodPool.bit(Rule.EXIT_LOCK_DELAY)))!=0||(basket.feedback&FeedbackBasket.LOCK)!=0;
        boolean editingPolicy=existing==null||!initialPolicy.equals(policyStamp());
        if(editingPolicy&&(modernDraft()||useShared&&dailyChance!=existing.dailyChance)&&lock)append(notice,"抽到直接锁屏、延时锁屏或确认「再点锁屏」时，会锁住整部手机。");
        if(editingPolicy&&(modernDraft()||useShared&&dailyChance!=existing.dailyChance)&&(basket.methods&MethodPool.bit(Rule.EXIT_MUTE))!=0)append(notice,"强行消音会暂时静音整个媒体声道，包括后台音乐；离开目标应用后恢复。");
        if(notice.length()==0){save.run();return;}
        a.showLayer(new AlertDialog.Builder(a).setTitle(migrate?"改用共用负反馈？":permitting?"日常改用所选反馈？":upgrading?"采用新的时段策略？":"保存这条规则？").setMessage(notice.toString())
                .setNegativeButton("取消",null).setPositiveButton("确认保存",(d,w)->save.run()).create());
    }
    private void append(StringBuilder s,String text){if(s.length()>0)s.append("\n\n");s.append(text);}
    private void renderPending(LinearLayout box,CoolingChange change,Dialog dialog){
        box.removeAllViews();
        if(change==null){box.setVisibility(View.GONE);return;}
        box.setVisibility(View.VISIBLE);
        Rule current=config.find(pkg),upcoming="remove".equals(change.kind)?null:config.draftRule(pkg);
        RuleChangeText diff=RuleChangeText.compare(current,upcoming,a::appLabel);
        boolean removal="remove".equals(change.kind);
        LinearLayout heading=new LinearLayout(a);heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(t(removal?"已预约移除":"正在编辑预约",15,ink,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView undo=t(removal?"撤回移除":"add".equals(change.kind)?"撤回添加":"撤回修改",13.5f,accent,true);
        undo.setMinHeight(d(44));undo.setMinWidth(d(60));undo.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        Runnable revoke=()->{
            if(!a.editable())return;
            boolean ok=config.cancelChange(change.key);
            a.feedback(ok?"已撤回":"这项更改已不在等待中。");
            dialog.dismiss();refresh.run();
        };
        bindAction(undo,line,0,()->{if(changed())discard("放弃草稿并撤回预约？",revoke);else revoke.run();});
        heading.addView(undo);add(box,heading,0);
        add(box,t("当前："+diff.before,12.5f,muted,false),2);
        add(box,t(MainActivity.when(change.effectiveAt)+" 起："+diff.after,12.5f,a.getColor(R.color.daily_warm_ink),false),4);
        box.setPadding(d(12),d(4),0,d(12));
        box.setBackground(new JournalLine(a,a.getColor(R.color.daily_warm_ink),2,true));
    }

    /** A cancellable local selection. Only the outer editor commits or schedules an effective rule. */
    private final class Picker {
        final Runnable bindEditor;
        int methods,feedback,delay;
        boolean curtain;
        String alternate,source;
        String entered;
        Dialog dialog;
        LinearLayout body,parameters,receipts;
        TextView count,all,allReceipts,done,note,openHelp;
        ImageButton openToggle;
        ScrollView scroll;
        Field origin,span,destination;
        CheckBox narrow;
        final java.util.Map<DailyExitMethod,CheckBox> boxes=new java.util.LinkedHashMap<>();
        final java.util.Map<FeedbackChoices,LinearLayout> receiptRows=new java.util.LinkedHashMap<>();
        final java.util.Map<FeedbackChoices,CheckBox> receiptBoxes=new java.util.LinkedHashMap<>();
        Picker(Runnable bindEditor){this.bindEditor=bindEditor;load(basket);source=adopted?"当前草稿":"原休息选择";}
        void load(SharedBasket selection){methods=selection.methods;feedback=selection.feedback;alternate=selection.alternate;curtain=selection.curtain;
            delay=(methods&MethodPool.bit(Rule.EXIT_LONG))!=0?Rule.EXIT_LONG:Rule.EXIT_SHORT;}
        int bit(DailyExitMethod m){return MethodPool.bit(m==DailyExitMethod.RANDOM?delay:m.mode);}
        boolean alt(){return (methods&MethodPool.ALTERNATIVES)!=0;}
        boolean timed(){return (methods&MethodPool.TIMED)!=0;}
        String stampLocal(){return methods+":"+activeFeedback()+":"+(alt()?alternate:"")+":"+(timed()&&curtain);}
        int activeFeedback(){return feedback&SharedBasket.allowedFeedback(methods);}
        void cancel(){if(!entered.equals(stampLocal()))discard("放弃本次选择？",dialog::dismiss);else dialog.dismiss();}
        void show(){
            entered=stampLocal();
            dialog=new Dialog(a,R.style.DailyAppTheme){@Override public void onBackPressed(){Picker.this.cancel();}};
            LinearLayout shell=surface(dialog);heading(shell,"负反馈","取消",this::cancel);
            body=scrolling(shell);scroll=(ScrollView)body.getParent();
            add(body,t(label+" · 两个时段共用",12.5f,muted,false),2);
            if(legacy()&&(legacyDay!=null&&!stamp(legacyDay).equals(stamp(legacyRest))||existing.previousRest!=null||existing.standby!=null)){
                origin=new Field("起始选择");origin.setOnClickListener(v->chooseOrigin());add(body,origin,6);
            }
            LinearLayout bar=row();count=t("",12.5f,faint,true);count.setLetterSpacing(0.05f);bar.addView(count,new LinearLayout.LayoutParams(0,-2,1));
            all=action("全选",()->{int choices=MethodPool.allDaily(delay);methods=methods==choices?0:choices;update();});
            all.setTextSize(13.5f);
            bar.addView(all,new LinearLayout.LayoutParams(d(72),d(44)));add(body,bar,8);
            for(DailyExitMethod m:DailyExitMethod.displayOrder(true)){
                boolean dayOnly=m==DailyExitMethod.WAIT || m==DailyExitMethod.RANDOM || m==DailyExitMethod.STALL
                        || m==DailyExitMethod.NARROW || m==DailyExitMethod.MUTE;
                CheckBox cb=choice(body,m.label,!editingDay&&dayOnly?"仅日常":m.stage(),()->explain(bit(m),m.explanation()),()->{methods^=bit(m);update();});
                boxes.put(m,cb);
            }
            parameters=column();add(body,parameters,4);
            span=new Field("累计可用时长");span.setOnClickListener(v->a.showLayer(new AlertDialog.Builder(a).setTitle("累计可用时长")
                    .setSingleChoiceItems(new String[]{"0～1分钟","3～10分钟"},delay==Rule.EXIT_SHORT?0:1,(p,i)->{
                        delay=i==0?Rule.EXIT_SHORT:Rule.EXIT_LONG;methods=(methods&~MethodPool.TIMED)|MethodPool.bit(delay);p.dismiss();update();
                    }).setNegativeButton("取消",null).create()));add(parameters,span,4);
            narrow=new CheckBox(a);narrow.setText("退出前画面收拢");narrow.setTextColor(ink);narrow.setTextSize(15);narrow.setMinHeight(d(48));
            narrow.setOnClickListener(v->{curtain=narrow.isChecked();update();});add(parameters,narrow,0);
            destination=new Field("备选应用");destination.setOnClickListener(v->chooseDestination());add(parameters,destination,2);
            receipts=column();add(body,receipts,20);
            LinearLayout responseBar=row();responseBar.addView(eyebrow("搭配回应"),new LinearLayout.LayoutParams(0,-2,1));
            allReceipts=action("全选回应",()->{int possible=SharedBasket.allowedFeedback(methods);
                feedback=activeFeedback()==possible?feedback&~possible:feedback|possible;update();});
            allReceipts.setTextSize(13.5f);
            responseBar.addView(allReceipts);add(receipts,responseBar,0);
            for(FeedbackChoices e:FeedbackChoices.values()){
                LinearLayout wrap=column();receipts.addView(wrap);receiptRows.put(e,wrap);
                receiptBoxes.put(e,choice(wrap,e.label,!editingDay&&e==FeedbackChoices.DURATION?"仅日常":"",()->explain(EffectPlan.post(e.bit),e.explanation),()->{feedback^=e.bit;update();}));
            }
            note=t("",12.5f,a.getColor(R.color.daily_warm_ink),false);add(body,note,8);
            done=t("完成选择",15.5f,accent,true);done.setGravity(Gravity.CENTER);done.setMinHeight(d(54));bindAction(done,accent,2,this::accept);add(shell,done,6);
            update();dialog.setContentView(shell);if(a.showLayer(dialog))dialog.getWindow().setLayout(-1,-1);
        }
        void chooseOrigin(){
            ArrayList<String> names=new ArrayList<>();ArrayList<SharedBasket> options=new ArrayList<>();
            names.add("原休息选择");options.add(legacyRest);
            if(legacyDay!=null){names.add("原日常选择");options.add(legacyDay);}
            if(existing.standby!=null){names.add(existing.standby.combo?"原日常组合预设":"原日常单项预设");options.add(SharedBasket.fromPolicy(existing.standby));}
            if(existing.previousRest!=null){names.add("早期休息选择");options.add(SharedBasket.fromPolicy(existing.previousRest));}
            a.showLayer(new AlertDialog.Builder(a).setTitle("用哪套选择作为起点？").setItems(names.toArray(new String[0]),(p,i)->{
                Runnable apply=()->{load(options.get(i));source=names.get(i);update();};
                if(entered.equals(stampLocal()))apply.run();else discard("替换这页的未保存选择？",apply);
            }).setNegativeButton("取消",null).create());
        }
        private String explain(int ticket,String description){
            // Start with a concrete, named example only when these exact tickets can run together.
            if(!byEffects()){
                int home=hardTicket(ticket),available=hardTicket(methods|EffectPlan.post(activeFeedback()));
                if(home!=0){
                    int others=available&~home;
                    if(others!=0)return description+" 禁止打开时，可在桌面与"+String.join("、",hardNames(others))+"组合。";
                    return description+" 本段可以在桌面执行。";
                }
                if((ticket&(MethodPool.EXPERIENCES|MethodPool.TIMED|MethodPool.bit(Rule.EXIT_WAIT_OPEN)))!=0)
                    return editingDay?"这条日常规则保留了直接拦截，不执行此项。":"休息时不执行。其余时间："+description;
                return description;
            }
            ArrayList<String> other=new ArrayList<>();int pool=(methods|EffectPlan.post(activeFeedback()))&~ticket;
            for(int scan=pool;scan!=0;scan&=scan-1){
                int partner=Integer.lowestOneBit(scan);
                if(EffectPlan.legal(ticket|partner,true))other.add(ticketName(partner));
            }
            String pair=other.isEmpty()?"":" 在原图标上，可与已选的"+String.join("、",other)+"组成两项。";
            if((ticket&EffectPlan.TERMINALS)!=0 || ticket==MethodPool.bit(Rule.EXIT_LOCK_DELAY))
                return description+pair+" 锁屏和改道是不同去向，不会在同一次触发中叠加。";
            if(other.isEmpty()&&pool!=0)return description+" 已选项中没有能与它单独配成两项的反馈。";
            return description+pair;
        }
        private int hardTicket(int selection){return EffectPlan.replies(selection)&SharedBasket.REST_RESPONSES
                |((selection&MethodPool.bit(Rule.EXIT_LOAD))!=0?SharedBasket.HOME_LOAD:0)
                |((selection&MethodPool.bit(Rule.EXIT_REDIRECT))!=0?SharedBasket.HOME_REDIRECT:0);}
        private ArrayList<String> hardNames(int mask){
            ArrayList<String> items=replyNames(mask&SharedBasket.REST_RESPONSES);
            if((mask&SharedBasket.HOME_LOAD)!=0)items.add(DailyExitMethod.LOAD.label);
            if((mask&SharedBasket.HOME_REDIRECT)!=0)items.add(DailyExitMethod.REDIRECT.label);
            return items;
        }
        private String ticketName(int ticket){
            return EffectPlan.methods(ticket)!=0?DailyExitMethod.forMode(Integer.numberOfTrailingZeros(ticket)).label:
                    replyNames(EffectPlan.replies(ticket)).get(0);
        }
        java.util.function.Supplier<String> openExplanation;
        CheckBox choice(LinearLayout host,String name,String stage,java.util.function.Supplier<String> explanation,Runnable toggle){
            LinearLayout strip=row();strip.setBackground(new JournalLine(a,line,1,false));host.addView(strip);
            CheckBox cb=new CheckBox(a);cb.setText(name);cb.setTextSize(15.5f);cb.setTextColor(ink);cb.setMinHeight(d(50));cb.setPadding(d(8),0,0,0);
            cb.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            cb.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{accent,faint}));
            cb.setBackground(JournalLine.hit(a,line,0));FluidMotion.attachPress(cb,0.985f);cb.setOnClickListener(v->toggle.run());strip.addView(cb,new LinearLayout.LayoutParams(0,-2,1));
            if(!stage.isEmpty()){TextView tag=t(stage,12,faint,false);tag.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);strip.addView(tag);}
            ImageButton help=new ImageButton(a);help.setContentDescription(name+"说明");help.setImageResource(R.drawable.option_info);help.setColorFilter(faint);
            help.setBackground(JournalLine.hit(a,line,0));help.setScaleType(ImageView.ScaleType.CENTER);FluidMotion.attachPress(help,0.92f);strip.addView(help,new LinearLayout.LayoutParams(d(46),d(50)));
            TextView explanationView=t("",12.5f,muted,false);
            explanationView.setPadding(d(12),d(6),d(6),d(10));
            explanationView.setBackground(new JournalLine(a,accent,2,true));
            explanationView.setVisibility(View.GONE);
            LinearLayout.LayoutParams eLp=new LinearLayout.LayoutParams(-1,-2);eLp.topMargin=d(6);eLp.bottomMargin=d(8);
            host.addView(explanationView,eLp);
            help.setStateDescription("已收起");help.setOnClickListener(v->{boolean opening=openHelp!=explanationView;
                FluidMotion.morph(body,opening);
                if(openHelp!=null){openHelp.setVisibility(View.GONE);openToggle.setStateDescription("已收起");}
                openHelp=opening?explanationView:null;openToggle=opening?help:null;openExplanation=opening?explanation:null;
                if(opening){explanationView.setText(explanation.get());explanationView.setVisibility(View.VISIBLE);help.setStateDescription("已展开");}});
            return cb;
        }
        void chooseDestination(){new DailyAppPicker(a,config).show(pkg,(chosen,name)->{alternate=chosen;update();});}
        boolean validDestination(){
            if(!alt())return true;
            SharedBasket old=existing==null?null:existing.sharedBasket;
            if(old!=null&&!alternate.isEmpty()&&alternate.equals(old.alternate)&&(old.methods&MethodPool.ALTERNATIVES)==(methods&MethodPool.ALTERNATIVES))return true;
            return config.validAlternative(pkg,alternate);
        }
        void update(){
            if(origin!=null)origin.setValue(source,true);
            if(openHelp!=null)openHelp.setText(openExplanation.get());
            for(DailyExitMethod m:boxes.keySet())boxes.get(m).setChecked((methods&bit(m))!=0);
            int allowed=SharedBasket.allowedFeedback(methods);
            allReceipts.setText(activeFeedback()==allowed?"取消全选回应":"全选回应");
            for(FeedbackChoices e:receiptRows.keySet()){receiptRows.get(e).setVisibility((allowed&e.bit)!=0?View.VISIBLE:View.GONE);
                receiptBoxes.get(e).setChecked((feedback&e.bit)!=0);}
            count.setText("已选 "+Integer.bitCount(methods)+" / "+Integer.bitCount(MethodPool.allDaily(delay))+" 种方式");
            all.setText(methods==MethodPool.allDaily(delay)?"取消全选":"全选");
            span.setVisibility(timed()?View.VISIBLE:View.GONE);span.setValue(delay==Rule.EXIT_SHORT?"0～1分钟":"3～10分钟",true);
            narrow.setVisibility(timed()?View.VISIBLE:View.GONE);narrow.setChecked(curtain);
            destination.setVisibility(alt()?View.VISIBLE:View.GONE);boolean destinationOK=validDestination();
            destination.setValue(alternate.isEmpty()?"请选择应用":a.appLabel(alternate),destinationOK);
            String message=methods==0?"至少选择一种方式。":!destinationOK?"改道或换个去处，需要一个备选应用。":"";
            note.setText(message);note.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);
            boolean valid=SharedBasket.validSelection(methods,activeFeedback());
            done.setEnabled(valid);done.setAlpha(valid?1f:.45f);done.setText(valid&&!destinationOK?"选择备选应用":legacy()&&!adopted&&entered.equals(stampLocal())?"采用这一篮":"完成选择");
        }
        void accept(){
            if(!SharedBasket.validSelection(methods,activeFeedback()))return;
            if(!validDestination()){chooseDestination();return;}
            basket=new SharedBasket(methods,activeFeedback(),alt()?alternate:"",timed()&&curtain);adopted=legacy();
            dialog.dismiss();bindEditor.run();
        }
    }
}
