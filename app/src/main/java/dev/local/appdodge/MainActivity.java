package dev.local.appdodge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Toast;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;

/** Daily rules on the user's own launcher. */
public final class MainActivity extends Activity {
    private DailyOverview overview;
    private NativeRestConfig config;
    private final ArrayList<Dialog> layers=new ArrayList<>();
    private final Handler clock=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){@Override public void run(){
        refresh();clock.postDelayed(this,60020L-System.currentTimeMillis()%60000L);
    }};
    private final Runnable refreshAfterLock=this::refresh;
    private final Runnable boundary=this::refresh;
    private boolean resumed, watchingClock;
    private long authorizedUntil;
    private Runnable managementClockUpdate;
    private final ArrayList<Runnable> coolingListeners=new ArrayList<>();
    private final Runnable coolingBoundary=this::refresh;
    private final BroadcastReceiver changedClock=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            refresh();
            if(!isFinishing()&&managementClockUpdate!=null)managementClockUpdate.run();
        }
    };
    @Override public void onCreate(Bundle saved){
        setTheme(R.style.DailyAppTheme);super.onCreate(saved);
        if (!NativeRestService.isConnected()) MediaSilence.recover(this);
        config=new NativeRestConfig(this);
        if(!checkEntry(true))return; // Never inflate rules on a denied cold start.
        IntentFilter time=new IntentFilter(Intent.ACTION_TIME_CHANGED);time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(changedClock,time);watchingClock=true;
        int paper=getColor(R.color.daily_paper);getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(paper);getWindow().setNavigationBarColor(paper);
        overview=new DailyOverview(this,config,saved);
        setContentView(overview.root);consumeStopSetupIntent(getIntent());
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);if(overview!=null)overview.save(state);
    }
    @Override public void onBackPressed(){
        if(overview==null||!overview.back())super.onBackPressed();
    }
    @Override public void onResume(){
        super.onResume();resumed=true;
        if(!checkEntry(true))return;
        NativeRestService.setConnectionListener(this::refresh);NativeRestService.refreshUsageAccess();tick.run();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);if(checkEntry(true))consumeStopSetupIntent(intent);}
    private void consumeStopSetupIntent(Intent intent){
        if(intent!=null&&StopSetup.OPEN.equals(intent.getAction())){
            intent.setAction(null); // returning from a notification opens settings; it never starts pairing.
            if(stopSetup==null)configureStopAccess();
        }
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(overview!=null)overview.touchScreen(event);
        return super.dispatchTouchEvent(event);
    }
    @Override public void onPause(){
        resumed=false;NativeRestService.setConnectionListener(null);
        clock.removeCallbacks(tick);clock.removeCallbacks(refreshAfterLock);clock.removeCallbacks(coolingBoundary);super.onPause();
    }
    @Override protected void onDestroy(){
        if(watchingClock)unregisterReceiver(changedClock);
        if(overview!=null)overview.detach();
        clock.removeCallbacksAndMessages(null);closeLayers();super.onDestroy();
    }
    void feedback(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    static String when(long epoch){return when(epoch,false);}
    static String when(long epoch,boolean seconds){
        ZoneId zone=ZoneId.systemDefault();ZonedDateTime at=Instant.ofEpochMilli(epoch).atZone(zone);
        LocalDate day=at.toLocalDate(),today=LocalDate.now(zone);
        String prefix=day.equals(today)?"今天":day.equals(today.plusDays(1))?"明天":day.format(DateTimeFormatter.ofPattern("M月d日",Locale.CHINA));
        return prefix+" "+at.format(DateTimeFormatter.ofPattern(seconds?"HH:mm:ss":"HH:mm",Locale.ROOT));
    }
    private void closeLayers(){
        for(int i=layers.size()-1;i>=0;i--)layers.get(i).dismiss();
        layers.clear();
    }
    boolean showLayer(Dialog dialog){return editable()&&trackLayer(dialog);}
    boolean showReadOnlyLayer(Dialog dialog){return canNavigate()&&trackLayer(dialog);}
    private boolean trackLayer(Dialog dialog){
        dialog.setOnDismissListener(d->layers.remove(dialog));layers.add(dialog);dialog.show();
        if(dialog instanceof AlertDialog){
            AlertDialog ad=(AlertDialog)dialog;
            android.widget.Button pos=ad.getButton(AlertDialog.BUTTON_POSITIVE);
            android.widget.Button neg=ad.getButton(AlertDialog.BUTTON_NEGATIVE);
            android.widget.Button neu=ad.getButton(AlertDialog.BUTTON_NEUTRAL);
            if(pos!=null){pos.setTextColor(getColor(R.color.daily_green));pos.setAllCaps(false);}
            if(neg!=null){neg.setTextColor(getColor(R.color.daily_muted));neg.setAllCaps(false);}
            if(neu!=null){neu.setTextColor(getColor(R.color.daily_muted));neu.setAllCaps(false);}
        }
        return true;
    }
    private boolean checkEntry(boolean notify){
        if(isFinishing()||isDestroyed())return false;
        ManagementWindow.State state=config.managementWindow();
        clock.removeCallbacks(boundary);
        if(!state.open){
            closeLayers();
            if(notify)feedback("下次可打开："+when(state.nextOpen));
            finish();return false;
        }
        long now=System.currentTimeMillis();
        // Deep sleep can skip an entire closed interval. Never revive its unfinished draft.
        if(authorizedUntil!=0&&now>=authorizedUntil)closeLayers();
        authorizedUntil=state.nextBoundary;
        if(authorizedUntil>0)clock.postDelayed(boundary,Math.max(1,authorizedUntil-now));
        if(Build.VERSION.SDK_INT>=33)setRecentsScreenshotEnabled(!state.enabled);
        else if(state.enabled)getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        return true;
    }
    boolean canNavigate(){return checkEntry(true);}
    boolean editable(){
        if(!checkEntry(true))return false;
        if(config.lockedFor()==0)return true;
        feedback("规则已锁定，到时才能修改。");refresh();return false;
    }
    private void refresh(){
        if(!checkEntry(resumed)||!resumed||overview==null)return;
        long lock=config.lockedFor();clock.removeCallbacks(refreshAfterLock);
        if(lock>0)clock.postDelayed(refreshAfterLock,(lock-1)%60_000+16);
        overview.render();
        clock.removeCallbacks(coolingBoundary);
        long due=config.nextCoolingAt();
        if(due>0)clock.postDelayed(coolingBoundary,Math.max(1,due-System.currentTimeMillis()));
        // A child may dismiss itself on expiry. Never drop the list page's independent subscription.
        for(Runnable observer:new ArrayList<>(coolingListeners))
            if(coolingListeners.contains(observer))observer.run();
    }
    void addCoolingListener(Runnable listener){coolingListeners.add(listener);}
    void removeCoolingListener(Runnable listener){coolingListeners.remove(listener);}
    private StopSetup stopSetup;
    void configureStopAccess(){
        if(!editable())return;
        stopSetup=new StopSetup(this);
        stopSetup.show();
    }
    void stopSetupClosed(StopSetup sheet){
        if(stopSetup==sheet)stopSetup=null;
        if(resumed&&!isFinishing()&&!isDestroyed())clock.post(this::refresh);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==StopSetup.NOTICE_PERMISSION && stopSetup!=null)stopSetup.onNotificationPermission();
    }
    void showCooling(){if(editable())new CoolingSheet(this,config).show();}
    void showPending(){if(editable())new CoolingSheet(this,config).showPending();}
    void undo(CoolingChange p){
        if(!editable())return;
        feedback(config.cancelChange(p.key)?"已撤回":"这项更改已不在等待中。");refresh();
    }
    CoolingChange pendingKind(String kind){
        for(CoolingChange c:config.pendingChanges())if(kind.equals(c.kind))return c;
        return null;
    }
    void confirmRemoveRule(String pkg,String label,Runnable onRemoved){
        if(!editable())return;
        CoolingChange p=config.pendingRule(pkg);
        boolean cancel=p!=null&&("remove".equals(p.kind)||"add".equals(p.kind));
        boolean queuedRemoval=cancel&&"remove".equals(p.kind);
        String title=cancel?(queuedRemoval?"撤回移除":"撤回添加")+"「"+label+"」？":"从自控列表移除「"+label+"」？";
        String note=queuedRemoval?"保留原规则。":cancel?"这条规则还没有生效。":
                config.coolingEnabled()?"24 小时后移除。在此之前，原规则继续生效。":"只移除自控规则，不卸载应用。";
        showLayer(new AlertDialog.Builder(this).setTitle(title).setMessage(note).setNegativeButton(cancel?"返回":"保留",null)
            .setPositiveButton(cancel?"撤回":"移除",(d,w)->{
                if(!editable())return;
                if(!(cancel?config.cancelChange(p.key):config.remove(pkg))){feedback("未能更改，请查看当前规则。");refresh();return;}
                CoolingChange next=config.pendingRule(pkg);
                feedback(cancel?"已撤回":next!=null?"将于"+when(next.effectiveAt)+"移除":"已移除"+label+"的规则");
                if(onRemoved!=null)onRemoved.run();refresh();
            }).create());
    }
    void managementChanged(){refresh();}
    void managementClockListener(Runnable listener){managementClockUpdate=listener;}
    void manageWindow(){if(editable())new ManagementEditor(this,config).show();}
    String appLabel(String pkg){
        try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}
        catch(PackageManager.NameNotFoundException e){return "应用不可用";}
    }
    void chooseApp(){if(editable())new DailyAppPicker(this,config).show();}
    void editRule(String pkg,String label){editRule(pkg,label,false);}
    void editRule(String pkg,String label,boolean day){if(editable())new DailyRuleEditor(this,config,pkg,label,this::refresh,day).show();}
    void fixService(){
        if(!editable())return;
        if(config.paused()){
            if(pendingKind("resume")!=null)showPending();else changePaused(false);
        }else if(NativeRestService.isConnected()){refresh();}
        else if(config.serviceAuthorized()){overview.showService();}
        else openServiceSettings();
    }
    void changePaused(boolean value){
        if(!editable())return;
        if(config.setPaused(value)){
            CoolingChange c=pendingKind(value?"pause":"resume");
            feedback(c!=null?c.title+"："+when(c.effectiveAt):value?"已暂停保护，每日规则已保留":"已恢复保护");
        }else feedback("未能更改保护状态。");
        refresh();
    }
    void confirmRuleLock(){
        if(!editable())return;
        showLayer(new AlertDialog.Builder(this).setTitle("锁定规则 15 分钟？")
            .setMessage("这 15 分钟内，不能在反人性软件里暂停保护、增删应用、更改时段、拦截方式或负反馈栏中的选项。已有时段照常生效，到时自动恢复修改。\n\n锁的是规则，不是手机。")
            .setNegativeButton("取消",null).setPositiveButton("锁定 15 分钟",(d,w)->{
                if(!config.lockRules())feedback("没有新增锁定，请检查保护状态和每日规则。");refresh();
            }).create());
    }
    void openServiceSettings(){
        if(!editable())return;
        String name=new ComponentName(this,NativeRestService.class).flattenToString();
        PackageManager pm=getPackageManager();
        int flags=PackageManager.MATCH_DEFAULT_ONLY|PackageManager.MATCH_SYSTEM_ONLY;
        Intent list=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        ResolveInfo hub=pm.resolveActivity(list,flags);
        if(hub!=null&&hub.activityInfo!=null){
            String pkg=hub.activityInfo.packageName;
            Intent details=new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .setPackage(pkg).putExtra(Intent.EXTRA_COMPONENT_NAME,name);
            ResolveInfo detail=pm.resolveActivity(details,flags);
            // Stock Android protects this activity. Only use it if Settings actually admits our UID.
            if(detail!=null&&detail.activityInfo!=null&&detail.activityInfo.exported){
                String permission=detail.activityInfo.permission;
                if(permission==null||checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED){
                    try{startActivity(details);return;}catch(ActivityNotFoundException|SecurityException ignored){}
                }
            }
            list.setPackage(pkg);
        }
        // Settings may use our component key to scroll to its switch. ROMs can ignore this hint.
        String key=":settings:fragment_args_key";
        Bundle hint=new Bundle();hint.putString(key,name);
        list.putExtra(key,name).putExtra(":settings:show_fragment_args",hint);
        try{startActivity(list);feedback("在系统设置中选择「"+getString(R.string.native_guard_label)+"」");}
        catch(ActivityNotFoundException|SecurityException e){feedback("请在系统设置中搜索「无障碍」，打开「"+getString(R.string.native_guard_label)+"」。");}
    }
}
