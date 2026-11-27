package dev.local.appdodge;

import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import java.util.*;

/**
 * Pure Track-Line aesthetic + Apple-grade 1:1 finger-tracking horizontal swipe pager.
 * Primary tabs (应用 ↔ 报告 ↔ 设置) glide smoothly with real-time bottom track cursor stretch.
 */
final class DailyOverview extends JournalPage {
    enum Screen {
        APPS("应用","应用"), SETTINGS("设置","设置"), DEFENSE("防反悔","防反悔"), SERVICE("运行与权限","运行与权限"), LOG("运行日志","运行日志"), ACHIEVEMENTS("报告","个人报告");
        final String label, desc;
        Screen(String label,String desc){this.label=label;this.desc=desc;}
        boolean primary(){return this==APPS||this==SETTINGS||this==ACHIEVEMENTS;}
        int primaryIndex(){return this==APPS?0:this==ACHIEVEMENTS?1:this==SETTINGS?2:-1;}
        static Screen restore(String name){
            for(Screen s:values())if(s.name().equals(name))return s;
            return APPS;
        }
    }

    private final NativeRestConfig config;
    final LinearLayout root;
    private final FrameLayout stage;
    private final FluidPager pager;
    private final ScrollView[] primaryScrolls=new ScrollView[3];
    private final LinearLayout[] primaryPages=new LinearLayout[3];
    private final ScrollView detailScroll;
    private final LinearLayout detailPage;
    private final LinearLayout nav;
    private View navCursor;
    private FrameLayout navBarFrame;
    private final LinearLayout[] navTabs=new LinearLayout[3];
    private final ImageView[] navIcons=new ImageView[3];
    private final TextView[] navLabels=new TextView[3];
    private static final Screen[] PRIMARY_TABS=new Screen[]{Screen.APPS,Screen.ACHIEVEMENTS,Screen.SETTINGS};

    private final SwipeRuleRow.Group swipes=new SwipeRuleRow.Group();
    private Screen screen=Screen.APPS;
    private final int[] positions=new int[Screen.values().length];
    private boolean drawNew=true;
    private final ReportPage report;
    private final ReportPeriod period;
    private boolean diagnostics=false;
    private int logPage;
    private Screen logParent=Screen.SETTINGS;

    DailyOverview(MainActivity a,NativeRestConfig config,Bundle saved){
        super(a);this.config=config;period=new ReportPeriod(a,saved);report=new ReportPage(a,period);
        if(saved!=null){
            logParent=Screen.restore(saved.getString("logParent",Screen.SETTINGS.name()));
            diagnostics=saved.getBoolean("diagnostics");logPage=saved.getInt("logPage");
            screen=Screen.restore(saved.getString("screen"));
            int[] old=saved.getIntArray("positions");
            if(old!=null&&old.length==positions.length)System.arraycopy(old,0,positions,0,old.length);
        }

        root=col();
        root.setBackgroundColor(color(R.color.daily_paper));
        root.setOnApplyWindowInsetsListener((v,in)->{
            android.graphics.Insets bars=in.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return in;
        });

        stage=new FrameLayout(a);
        pager=new FluidPager(a);
        for(int i=0;i<3;i++){
            ScrollView sv=new ScrollView(a);
            sv.setFillViewport(true);
            LinearLayout pg=col();
            pg.setPadding(d(22),d(12),d(22),d(28));
            sv.addView(pg);
            primaryScrolls[i]=sv;
            primaryPages[i]=pg;
            pager.addView(sv);
        }
        pager.setListener(new FluidPager.Listener(){
            @Override public void onProgress(float progress){
                updateTrackCursor(progress);
            }
            @Override public void onSettled(int idx){
                Screen settled=PRIMARY_TABS[idx];
                if(screen.primary()&&screen!=settled){
                    screen=settled;
                    syncNavStyles(idx,false);
                }
            }
        });
        stage.addView(pager,new FrameLayout.LayoutParams(-1,-1));

        detailScroll=new ScrollView(a);
        detailScroll.setFillViewport(true);
        detailScroll.setBackgroundColor(color(R.color.daily_paper));
        detailScroll.setVisibility(View.GONE);
        detailPage=col();
        detailPage.setPadding(d(22),d(12),d(22),d(28));
        detailScroll.addView(detailPage);
        stage.addView(detailScroll,new FrameLayout.LayoutParams(-1,-1));

        root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));

        nav=col();
        nav.setBackgroundColor(color(R.color.daily_paper));
        root.addView(nav);
        buildChrome();
    }

    void save(Bundle out){
        saveCurrentScroll();
        out.putString("screen",screen.name());
        out.putIntArray("positions",positions);
        period.save(out);
        out.putString("logParent",logParent.name());
        out.putBoolean("diagnostics",diagnostics);
        out.putInt("logPage",logPage);
    }

    private void saveCurrentScroll(){
        int idx=screen.primaryIndex();
        positions[screen.ordinal()]=idx>=0?primaryScrolls[idx].getScrollY():detailScroll.getScrollY();
    }

    void showService(){go(Screen.SERVICE,true);}
    void detach(){}

    private void go(Screen next,boolean reset){
        if(screen==next||!a.canNavigate())return;
        saveCurrentScroll();
        if(reset)positions[next.ordinal()]=0;
        boolean bothPrimary=screen.primary()&&next.primary();
        screen=next;
        if(bothPrimary){
            int targetIdx=next.primaryIndex();
            syncNavStyles(targetIdx,true);
            pager.setPage(targetIdx,true);
            // Keep target primary page fresh
            renderPrimary(targetIdx,null);
        }else{
            drawNew=true;
            render();
        }
    }

    boolean back(){
        switch(screen){
            case APPS:return false;
            case LOG:go(logParent,false);break;
            case ACHIEVEMENTS:case SETTINGS:go(Screen.APPS,false);break;
            default:go(Screen.SETTINGS,false);
        }return true;
    }

    void touchScreen(MotionEvent event){if(screen==Screen.APPS)swipes.touchScreen(event);}

    /** Pure paper bottom bar: 1dp horizontal track + 2dp liquid-stretching moss cursor. */
    private void buildChrome(){
        nav.removeAllViews();
        navBarFrame=new FrameLayout(a);

        View baseTrack=new View(a);
        baseTrack.setBackgroundColor(line);
        FrameLayout.LayoutParams baseLp=new FrameLayout.LayoutParams(-1,Math.max(1,d(1)));
        baseLp.gravity=Gravity.TOP;
        navBarFrame.addView(baseTrack,baseLp);

        navCursor=new View(a);
        navCursor.setBackgroundColor(green);
        FrameLayout.LayoutParams curLp=new FrameLayout.LayoutParams(d(28),d(2));
        curLp.gravity=Gravity.TOP|Gravity.START;
        navBarFrame.addView(navCursor,curLp);

        LinearLayout bar=row();
        for(int i=0;i<PRIMARY_TABS.length;i++){
            final int idx=i;
            Screen dest=PRIMARY_TABS[i];
            LinearLayout tab=row();
            tab.setGravity(Gravity.CENTER);
            tab.setMinimumHeight(d(54));
            tab.setFocusable(true);
            tab.setClickable(true);
            tab.setContentDescription(dest.desc);

            ImageView icon=new ImageView(a);
            icon.setImageResource(dest==Screen.APPS?R.drawable.nav_apps:dest==Screen.ACHIEVEMENTS?R.drawable.nav_growth:R.drawable.nav_settings);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tab.addView(icon,new LinearLayout.LayoutParams(d(18),d(18)));

            TextView label=text(dest.label,13,muted,false);
            label.setPadding(d(6),0,0,0);
            tab.addView(label);

            FluidMotion.attachPress(tab,0.94f);
            tab.setOnClickListener(v->{
                if(screen!=dest){
                    FluidMotion.pop(navIcons[idx]);
                    go(dest,false);
                }
            });
            bar.addView(tab,new LinearLayout.LayoutParams(0,-2,1));
            navTabs[i]=tab;navIcons[i]=icon;navLabels[i]=label;
        }
        navBarFrame.addView(bar,new FrameLayout.LayoutParams(-1,-2));
        nav.addView(navBarFrame,new LinearLayout.LayoutParams(-1,-2));
    }

    private void syncNavStyles(int activeIdx,boolean popIcon){
        for(int i=0;i<PRIMARY_TABS.length;i++){
            boolean selected=i==activeIdx;
            navTabs[i].setSelected(selected);
            navIcons[i].setColorFilter(selected?green:muted);
            navLabels[i].setTextColor(selected?green:muted);
            navLabels[i].setTypeface(Typeface.create(selected?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
            if(selected&&popIcon)FluidMotion.pop(navIcons[i]);
        }
    }

    private void updateTrackCursor(float progress){
        if(navBarFrame==null||navCursor==null)return;
        int w=navBarFrame.getWidth();
        if(w<=0)return;
        float slot=w/3f;
        float clamped=Math.max(0f,Math.min(2f,progress));
        float cx=slot*(clamped+0.5f);
        float frac=Math.abs(clamped-Math.round(clamped));
        FluidMotion.trackCursorAt(navCursor,cx,frac,d(28),d(28));
    }

    private void renderPrimary(int idx,String revealedApps){
        LinearLayout target=primaryPages[idx];
        target.removeAllViews();
        if(idx==0)apps(target,revealedApps);
        else if(idx==1)report.show(target,()->{logParent=Screen.ACHIEVEMENTS;diagnostics=false;logPage=0;go(Screen.LOG,true);},()->renderPrimary(1,null));
        else settings(target);
    }

    void render(){
        boolean entering=drawNew;drawNew=false;
        String revealed=swipes.beforeRender();
        boolean primary=screen.primary();
        nav.setVisibility(primary?View.VISIBLE:View.GONE);
        pager.setPagingEnabled(primary);

        if(primary){
            detailScroll.setVisibility(View.GONE);
            pager.setVisibility(View.VISIBLE);
            int activeIdx=screen.primaryIndex();
            syncNavStyles(activeIdx,false);
            renderPrimary(0,entering?null:revealed);
            renderPrimary(1,null);
            renderPrimary(2,null);
            pager.setPage(activeIdx,false);
            navBarFrame.post(()->updateTrackCursor(activeIdx));
            if(entering){
                final int y=positions[screen.ordinal()];
                final int idx=activeIdx;
                primaryScrolls[idx].post(()->primaryScrolls[idx].scrollTo(0,y));
            }
        }else{
            pager.setVisibility(View.GONE);
            detailScroll.setVisibility(View.VISIBLE);
            detailPage.removeAllViews();
            TextView back=text("‹  返回",14,green,true);
            back.setMinHeight(d(40));
            back.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            back.setPadding(0,d(4),d(12),d(4));
            FluidMotion.attachPress(back,0.95f);
            back.setOnClickListener(v->back());
            detailPage.addView(back,new LinearLayout.LayoutParams(-2,-2));

            switch(screen){
                case DEFENSE:defense(detailPage);break;
                case SERVICE:service(detailPage);break;
                case LOG:journal(detailPage);break;
                default:break;
            }
            if(entering){
                FluidMotion.enterPage(detailPage,d(8));
                final Screen target=screen;
                final int y=positions[screen.ordinal()];
                detailScroll.post(()->{if(screen==target)detailScroll.scrollTo(0,y);});
            }
        }
    }

    private enum Health {RUNNING,PAUSED,DISCONNECTED,UNGRANTED}
    private Health health(){
        if(config.paused())return Health.PAUSED;
        if(NativeRestService.isConnected())return Health.RUNNING;
        return config.serviceAuthorized()?Health.DISCONNECTED:Health.UNGRANTED;
    }
    private String state(Health health){
        switch(health){
            case PAUSED:return "保护已暂停";case DISCONNECTED:return "已授权，服务未连接";
            case UNGRANTED:return "未开启无障碍服务";default:return "保护运行中";
        }
    }

    /** Restraint: soft left-rail alert block when protection is inactive. */
    private void warningBanner(LinearLayout p,Health h,boolean locked){
        LinearLayout block=col();
        block.setPadding(d(14),d(8),0,d(8));
        block.setBackground(new JournalLine(a,warm,2,true));
        block.addView(text("保护未运行，现在不会拦截",15,warm,true));
        LinearLayout controls=row();
        CoolingChange resume=h==Health.PAUSED?a.pendingKind("resume"):null;
        String reason=resume!=null?MainActivity.when(resume.effectiveAt)+" 恢复":state(h);
        weight(controls,text(reason,13,muted,false));
        if(!locked){
            String action=resume!=null?"查看预约":h==Health.PAUSED?(config.coolingEnabled()?"预约恢复":"恢复保护"):h==Health.UNGRANTED?"去开启":"检查服务";
            TextView btn=text(action,14,warm,true);
            btn.setMinHeight(d(40));btn.setGravity(Gravity.CENTER);btn.setPadding(d(4),0,d(4),0);
            btn.setBackground(new JournalLine(a,warm,1,false));
            FluidMotion.attachPress(btn,0.95f);
            btn.setOnClickListener(v->a.fixService());
            controls.addView(btn);
        }
        block.addView(controls);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=d(18);
        p.addView(block,lp);
    }

    private void apps(LinearLayout p,String revealed){
        Health h=health();boolean locked=config.lockedFor()>0;
        List<Rule> active=new ArrayList<>(config.rules());Map<String,CoolingChange> pending=new LinkedHashMap<>();
        for(CoolingChange c:config.pendingChanges())if(!c.pkg.isEmpty())pending.put(c.pkg,c);
        java.text.Collator collation=java.text.Collator.getInstance(Locale.CHINA);
        active.sort(Comparator.comparing(r->r.label,collation));
        boolean empty=active.isEmpty()&&pending.isEmpty();

        TextView add=null;
        if(!locked&&!empty){
            add=text("＋ 添加应用",14,green,true);
            add.setMinHeight(d(40));add.setGravity(Gravity.CENTER);add.setPadding(d(2),0,d(2),0);
            add.setBackground(new JournalLine(a,green,1,false));
            add.setContentDescription("添加应用");
            FluidMotion.attachPress(add,0.95f);
            add.setOnClickListener(v->a.chooseApp());
        }
        title(p,"我的应用",add);
        if(h!=Health.RUNNING)warningBanner(p,h,locked);
        if(locked){
            TextView lock=text("规则已锁定 · "+MainActivity.when(System.currentTimeMillis()+config.lockedFor())+" 后可修改",13,muted,false);
            lock.setPadding(d(12),d(6),0,d(6));
            lock.setBackground(new JournalLine(a,color(R.color.daily_line_strong),2,true));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=d(16);
            p.addView(lock,lp);
        }
        if(empty){
            divider(p);
            LinearLayout emptyBox=col();
            emptyBox.setPadding(0,d(32),0,0);
            emptyBox.addView(text("还没有受控应用",18,ink,true));
            TextView emptySub=text("选定容易分心的应用，设定休息时段或每日上限。",13,muted,false);
            emptySub.setPadding(0,d(6),0,d(22));
            emptyBox.addView(emptySub);
            if(!locked){
                TextView cta=text("＋ 添加第一个应用",15,green,true);
                cta.setMinHeight(d(48));cta.setGravity(Gravity.CENTER_VERTICAL);cta.setPadding(0,d(6),d(6),d(6));
                cta.setBackground(new JournalLine(a,green,2,false));
                FluidMotion.attachPress(cta,0.96f);
                cta.setOnClickListener(v->a.chooseApp());
                emptyBox.addView(cta,new LinearLayout.LayoutParams(-2,-2));
            }
            p.addView(emptyBox);
            return;
        }
        int now=Store.now();divider(p);
        for(Rule rule:active)ruleRow(p,rule,pending.get(rule.packageName),false,h,now,locked,rule.packageName.equals(revealed));
        for(CoolingChange c:pending.values())if("add".equals(c.kind)){
            Rule draft=config.draftRule(c.pkg);
            if(draft!=null)ruleRow(p,draft,c,true,h,now,locked,false);
        }
    }

    private void ruleRow(LinearLayout p,Rule r,CoolingChange pending,boolean addition,Health health,int now,boolean locked,boolean revealed){
        LinearLayout face=col(),header=row(),open=row();open.setMinimumHeight(d(72));
        if(!locked)hit(open,()->a.editRule(r.packageName,r.label,!addition&&r.allows(Store.now())));
        ImageView icon=new ImageView(a);boolean missing=false,suspended=false;PackageManager pm=a.getPackageManager();
        try{android.content.pm.ApplicationInfo info=pm.getApplicationInfo(r.packageName,0);
            icon.setImageDrawable(pm.getApplicationIcon(info));suspended=(info.flags&android.content.pm.ApplicationInfo.FLAG_SUSPENDED)!=0;
        }catch(PackageManager.NameNotFoundException e){missing=true;icon.setImageResource(R.drawable.ic_app);}
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        open.addView(icon,new LinearLayout.LayoutParams(d(36),d(36)));
        LinearLayout name=col();name.setPadding(d(12),d(10),d(8),d(10));
        TextView label=text(r.label,16,ink,true);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);name.addView(label);
        TextView time=text((r.limits.noSchedule?"":r.periods.summaryAt(now)+(r.limits.timed()?" · ":""))+(r.limits.timed()?r.limits.summary():r.limits.noSchedule?"无休息时段":""),12,muted,false);
        time.setPadding(0,d(3),0,0);name.addView(time);weight(open,name);
        String state=missing?"不可用":suspended?"已暂停":addition?"待生效":health!=Health.RUNNING?"未保护":
                !r.allows(now)?"休息中":r.daytime!=null?"拦截 "+r.dailyChance+"%":"可用";
        if(health==Health.RUNNING&&!addition&&!missing&&r.limits.timed()){
            QuotaBudget b=new QuotaClock(a).view(r.packageName);
            if(r.limits.day>0&&b.spent>=r.limits.day*60000L)state="今日已满";
            else if(r.limits.session>0&&b.restUntil>System.currentTimeMillis()&&r.limits.rest>0)state="限时休息";
            else if(r.limits.day>0&&r.allows(now))state="剩 "+Math.max(0,(r.limits.day*60000L-b.spent+59999)/60000)+" 分";
        }
        boolean activeState=health==Health.RUNNING&&!addition&&!missing;
        open.addView(text(state,13,activeState?green:muted,activeState));
        weight(header,open);
        if(!locked&&!addition){
            TextView more=link("···",()->a.confirmRemoveRule(r.packageName,r.label,null));
            more.setTextSize(18);more.setTextColor(faint);
            boolean removing=pending!=null&&"remove".equals(pending.kind);
            more.setContentDescription(removing?"撤回"+r.label+"的移除预约":"移除"+r.label+"的规则");
            header.addView(more);
        }
        face.addView(header);
        if(pending!=null){
            String verb=addition?"添加":"remove".equals(pending.kind)?"移除":"新规则生效";
            LinearLayout note=row();note.setMinimumHeight(d(40));
            weight(note,text(MainActivity.when(pending.effectiveAt)+" "+verb,13,warm,false));
            if(!locked){TextView undo=link("撤回",()->a.undo(pending));undo.setContentDescription("撤回："+pending.title);note.addView(undo);}
            face.addView(note);
        }
        if(!locked&&!addition)p.addView(new SwipeRuleRow(a,swipes,r.packageName,r.label,face,open,
                pending!=null&&"remove".equals(pending.kind),()->a.confirmRemoveRule(r.packageName,r.label,null),revealed));
        else p.addView(face);
        divider(p);
    }

    private void settings(LinearLayout p){
        title(p,"设置",null);divider(p);
        boolean locked=config.lockedFor()>0;
        item(p,"防反悔",config.coolingEnabled()?"冷静期已开启":"未开启",()->go(Screen.DEFENSE,true));
        int pending=config.scheduledChanges().size();
        item(p,"待生效的更改",pending+" 项",locked?null:a::showPending);
        item(p,"运行与权限",state(health()),()->go(Screen.SERVICE,true));
        item(p,"运行日志","查看记录",()->{logParent=Screen.SETTINGS;diagnostics=false;logPage=0;go(Screen.LOG,true);});
    }

    private void defense(LinearLayout p){
        title(p,"防反悔",null);divider(p);
        long remaining=config.lockedFor();boolean locked=remaining>0;
        ManagementWindow.State w=config.managementWindow();
        String hours=w.enabled?Rule.time(w.start)+"–"+(w.end<w.start?"次日 ":"")+Rule.time(w.end):"未开启";
        item(p,"管理时段",hours+(w.pending?" · 待变更":""),locked?null:a::manageWindow);
        item(p,"24 小时冷静期",config.coolingEnabled()?"已开启":"未开启",locked?null:a::showCooling);
        boolean canLock=health()==Health.RUNNING&&!config.rules().isEmpty();
        if(locked)item(p,"规则已锁定",MainActivity.when(System.currentTimeMillis()+remaining)+" 后可修改",null);
        else if(canLock)item(p,"锁定规则 15 分钟",null,a::confirmRuleLock);
    }

    private void journal(LinearLayout p){
        TextView toggleBtn=text(diagnostics?"看拦截":"诊断日志",14,green,true);
        toggleBtn.setMinHeight(d(40));toggleBtn.setGravity(Gravity.CENTER);toggleBtn.setPadding(d(2),0,d(2),0);
        toggleBtn.setBackground(new JournalLine(a,green,1,false));
        FluidMotion.attachPress(toggleBtn,0.95f);
        toggleBtn.setOnClickListener(v->{diagnostics=!diagnostics;render();});
        title(p,"运行日志",toggleBtn);

        BlockLedger blocks=BlockLedger.get(a);
        Long key=period.key();long count=0;
        if(!diagnostics){
            period.controls(p,()->{logPage=0;render();});
            BlockLedger.Totals day=new BlockLedger.Totals();for(BlockLedger.Totals r:blocks.apps(key))day.add(r);
            count=day.count;logPage=(int)Math.min(logPage,Math.max(0,count-1)/BlockLedger.PAGE);
            item(p,key==null?"全部拦截":"当天拦截",day.count+" 次",null);
            item(p,"退出前平均用时",day.average(),null);
            if(key!=null)item(p,"累计拦截",blocks.count(null)+" 次",()->{period.all();go(Screen.ACHIEVEMENTS,true);});
        }
        TextView summary=text(state(health())+" · 最近 "+GuardLog.LIMIT+" 条",13,muted,false);
        summary.setPadding(0,d(6),0,d(12));if(diagnostics){p.addView(summary);divider(p);}
        List<GuardLog.Entry> entries=diagnostics?new GuardLog(a).list():blocks.recent(key,logPage);
        if(entries.isEmpty()){
            TextView empty=text(diagnostics?"还没有保护记录":key==null?"还没有拦截记录":"当天没有拦截记录",14,faint,false);
            empty.setPadding(0,d(24),0,0);
            p.addView(empty);return;
        }
        java.text.DateFormat format=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.ROOT);
        for(GuardLog.Entry entry:entries){
            LinearLayout line=col();line.setPadding(0,d(12),0,d(12));
            line.addView(text(format.format(new Date(entry.at)),12,faint,false));
            TextView heading=text(entry.title,15,ink,true);heading.setPadding(0,d(3),0,d(3));line.addView(heading);
            if(!entry.detail.isEmpty())line.addView(text(entry.detail,13,muted,false));
            p.addView(line);divider(p);
        }
        if(!diagnostics && count>BlockLedger.PAGE){
            LinearLayout paging=row();p.addView(paging);
            if(logPage>0)weight(paging,link("上一页",()->moveLogPage(-1)));
            if((long)(logPage+1)*BlockLedger.PAGE<count)weight(paging,link("下一页",()->moveLogPage(1)));
        }
    }
    private void moveLogPage(int delta){logPage+=delta;render();detailScroll.scrollTo(0,0);}

    private void service(LinearLayout p){
        title(p,"运行与权限",null);divider(p);
        Health h=health();boolean locked=config.lockedFor()>0;
        item(p,"保护状态",state(h),null);
        item(p,"补齐当天已用额度",UsageReport.allowed(a)?"已授权":"使用情况访问",()->{
            try{a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS));}
            catch(android.content.ActivityNotFoundException e){a.feedback("请在系统设置搜索「使用情况访问权限」");}
        });
        boolean granted=NativeRestService.isConnected()||config.serviceAuthorized();item(p,"原桌面点按保护",granted?"已授权":"未授权",locked?null:a::openServiceSettings);
        item(p,"停止后台运行",StopSetup.status(a),locked?null:a::configureStopAccess);
        CoolingChange pending=h==Health.PAUSED?a.pendingKind("resume"):a.pendingKind("pause");
        if(pending!=null)item(p,pending.title,MainActivity.when(pending.effectiveAt)+" 生效",locked?null:a::showPending);
        else if(h==Health.RUNNING||h==Health.PAUSED){boolean pause=h==Health.RUNNING;
            String verb=pause?"暂停保护":"恢复保护";item(p,(config.coolingEnabled()?"预约":"")+verb,null,locked?null:()->a.changePaused(pause));}
    }
}
