package dev.local.appdodge;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.function.Consumer;

/** One-phone setup. Android provides the pairing secret; it never leaves volatile memory here. */
final class StopSetup extends JournalPage {
    static final String REPLY="dev.local.appdodge.LOCAL_PAIR_REPLY";
    static final String OPEN="dev.local.appdodge.OPEN_STOP_SETUP";
    static final int NOTICE_PERMISSION=5741;
    private static final String CHANNEL="local_pairing";
    private static final int NOTE=5742;
    private static final long SESSION_MS=300000L;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"local-pair-ui");t.setDaemon(true);return t;});
    private static WeakReference<StopSetup> current=new WeakReference<>(null);
    private static final Pattern INPUT=Pattern.compile("(?:(\\d{1,5})\\s+)?(\\d{6})");
    private enum Page {START,PAIR,CODE,RECOVER,PORT,BUSY,READY}
    private Page page;
    private final Context app;
    private final NotificationManager notices;
    private Dialog dialog;
    private LinearLayout body;
    private EditText portField,codeField;
    private TextView problem;
    private long token,until,sequence;
    private boolean notifying,awaitingPermission;
    private String status="";
    private Runnable cancelSearch;
    private final Runnable timeout=()->{
        if(token==0)return;
        expired();
    };
    private void expired(){
        sequence++;clearSearch();stopNotification();stage("expired");
        page=LocalAdb.paired(app)?Page.RECOVER:Page.START;
        status="本次配对已过 5 分钟，请重新开始。";render();
        restartNotice(app,status);
    }
    static void stage(String value){android.util.Log.i("AppDodgePair","stage="+value);}
    private static PendingIntent openSetup(Context c){
        return PendingIntent.getActivity(c,NOTE,new Intent(c,MainActivity.class).setAction(OPEN),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private static void restartNotice(Context c,String why){
        NotificationManager n=c.getSystemService(NotificationManager.class);
        if(!n.areNotificationsEnabled()||n.getNotificationChannel(CHANNEL)==null)return;
        // Replace the inline reply and its System UI history; never echo its contents.
        n.notify(NOTE,new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.notice_pair)
                .setContentTitle("本机配对未完成").setContentText(why+" 点此返回设置。")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(openSetup(c))
                .setOnlyAlertOnce(true).setAutoCancel(true).setTimeoutAfter(60000).build());
    }
    StopSetup(MainActivity a){
        super(a);app=a.getApplicationContext();notices=a.getSystemService(NotificationManager.class);
    }
    static String status(Context c){
        if(LocalAdb.connected())return "本机已连接";
        if(StopBridge.legacyState(c)==StopBridge.State.READY)return "Shizuku 已连接";
        return LocalAdb.paired(c)?"待恢复连接":"未配置";
    }
    void show(){
        page=StopBridge.state(app)==StopBridge.State.READY?Page.READY:LocalAdb.paired(app)?Page.RECOVER:Page.START;
        dialog=new Dialog(a,R.style.DailyAppTheme);
        LinearLayout shell=col();shell.setBackgroundColor(color(R.color.daily_paper));
        shell.setOnApplyWindowInsetsListener((v,i)->{
            android.graphics.Insets bar=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            v.setPadding(bar.left,bar.top,bar.right,bar.bottom);return i;});
        LinearLayout heading=row();heading.setPadding(d(22),d(12),d(16),d(9));
        TextView title=text("停止后台运行",25,ink,true);title.setAccessibilityHeading(true);weight(heading,title);
        heading.addView(link("返回",dialog::dismiss));shell.addView(heading);divider(shell);
        ScrollView scroll=new ScrollView(a);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));body=col();
        body.setPadding(d(22),d(18),d(22),d(26));scroll.addView(body);
        shell.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){current=new WeakReference<>(StopSetup.this);a.addCoolingListener(refreshConnection);}
            public void onViewDetachedFromWindow(View v){
                sequence++;a.removeCoolingListener(refreshConnection);clearSearch();stopNotification();if(current.get()==StopSetup.this)current.clear();
                a.stopSetupClosed(StopSetup.this);
            }
        });
        dialog.setContentView(shell);render();
        if(a.showLayer(dialog)){
            dialog.getWindow().setDecorFitsSystemWindows(false);dialog.getWindow().setStatusBarColor(color(R.color.daily_paper));
            dialog.getWindow().setNavigationBarColor(color(R.color.daily_paper));dialog.getWindow().setLayout(-1,-1);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }
    private final Runnable refreshConnection=this::syncConnection;
    private void syncConnection(){
        if(!active())return;
        if((page==Page.RECOVER||page==Page.PORT)&&LocalAdb.connected())go(Page.READY);
        else if(page==Page.READY&&StopBridge.state(app)!=StopBridge.State.READY)go(LocalAdb.paired(app)?Page.RECOVER:Page.START);
    }
    private void render(){
        body.removeAllViews();portField=null;codeField=null;problem=null;
        switch(page){
            case READY:
                headline(LocalAdb.connected()?"本机已连接":"已通过 Shizuku 连接");
                paragraph("只停止规则里已勾选的应用。你自己切走，不会停止。");
                primary("完成",dialog::dismiss);
                if(!LocalAdb.connected())secondary("改用本机连接",()->go(LocalAdb.paired(app)?Page.RECOVER:Page.START));
                break;
            case START:
                headline("本机无线配对");
                paragraph("连上 Wi-Fi，打开「无线调试 → 使用配对码配对设备」。保持弹窗打开，下拉通知，输入 6 位配对码。");
                paragraph("配对会授予系统调试权限，用于停止你勾选的应用。");
                primary("打开系统配对",this::begin);
                secondary("手动输入",()->go(Page.CODE));
                legacy();break;
            case PAIR:
                headline("等待系统配对码");
                paragraph("在「无线调试」点「使用配对码配对设备」。保持系统配对框打开，下拉通知，回复 6 位数字。");
                primary("打开开发者选项",this::settings);
                secondary("手动输入",()->{stopNotification();go(Page.CODE);});break;
            case CODE:
                headline("手动配对");
                paragraph("将设置和反人性软件分屏。在无线调试的「使用配对码配对设备」弹窗内，找到冒号后的端口和 6 位码。不要关闭弹窗。");
                portField=number("配对端口",5,false);codeField=number("六位配对码",6,true);
                primary("配对",this::submitManual);secondary("分屏打开系统设置",this::settings);
                secondary("改用通知输入",this::begin);break;
            case RECOVER:
                headline("恢复本机连接");
                paragraph("连上 Wi-Fi，打开系统「无线调试」，然后恢复连接。系统仍记得这台手机时，不必重新配对。");
                primary("恢复连接",()->connect(0));secondary("打开开发者选项",this::settings);
                secondary("手动输入连接端口",()->go(Page.PORT));secondary("重新配对",()->go(Page.START));break;
            case PORT:
                headline("输入连接端口");
                paragraph("在「无线调试」主页的「IP 地址和端口」中，输入冒号后的数字。这里不用配对弹窗里的端口。");
                portField=number("连接端口",5,false);primary("连接",()->{
                    int port=port(portField.getText().toString());
                    if(port==0){portField.setError("端口应为 1024–65535");return;}connect(port);});
                secondary("打开开发者选项",this::settings);secondary("重新配对",()->go(Page.START));break;
            case BUSY:
                headline("正在设置本机连接");break;
        }
        if((page==Page.START||page==Page.PAIR||page==Page.CODE||page==Page.RECOVER||page==Page.PORT)&&!developerOptions())
            secondary("找不到开发者选项？",this::developerHelp);
        if(!status.isEmpty()){
            problem=text(status,16,page==Page.BUSY?muted:warm,false);problem.setPadding(0,d(12),0,d(4));
            problem.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(problem);
        }
    }
    private void headline(String title){TextView v=text(title,21,ink,true);v.setAccessibilityHeading(true);v.setPadding(0,d(4),0,d(8));body.addView(v);}
    private void paragraph(String message){TextView v=text(message,16,muted,false);v.setLineSpacing(d(3),1);v.setPadding(0,0,0,d(14));body.addView(v);}
    private void primary(String label,Runnable action){
        TextView v=link(label,action);v.setMinHeight(d(52));v.setBackground(JournalLine.hit(a,green,2));body.addView(v);
    }
    private void secondary(String label,Runnable action){TextView v=link(label,action);v.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);body.addView(v);}
    private EditText number(String hint,int digits,boolean secret){
        EditText e=new EditText(a);e.setSingleLine(true);e.setTextColor(ink);e.setTextSize(17);e.setHint(hint);e.setContentDescription(hint);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);e.setSaveEnabled(false);
        e.setInputType(InputType.TYPE_CLASS_NUMBER|(secret?InputType.TYPE_NUMBER_VARIATION_PASSWORD:0));
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(digits)});e.setMinHeight(d(48));body.addView(e);return e;
    }
    private void go(Page next){page=next;status="";render();}
    private static int port(String digits){
        if(digits==null||!digits.matches("[0-9]{1,5}"))return 0;
        int n=Integer.parseInt(digits);return n>=1024&&n<=65535?n:0;
    }
    private void submitManual(){
        String code=codeField.getText().toString();int p=port(portField.getText().toString());
        if(p==0){portField.setError("端口应为 1024–65535");return;}
        if(!code.matches("[0-9]{6}")){codeField.setError("需要 6 位数字");return;}
        codeField.setText("");pair(p,code);
    }
    private void legacy(){
        StopBridge.State legacy=StopBridge.legacyState(app);
        if(legacy==StopBridge.State.MISSING)return;
        if(legacy==StopBridge.State.READY){
            secondary("继续使用已连接的 Shizuku",dialog::dismiss);return;
        }
        secondary("使用已安装的 Shizuku",()->{
            if(!a.editable())return;
            AlertDialog.Builder b=new AlertDialog.Builder(a).setTitle("使用已安装的 Shizuku").setNegativeButton("返回",null);
            if(StopBridge.legacyState(app)==StopBridge.State.DENIED){
                b.setMessage("将停止后台运行的权限授予反人性软件。")
                    .setPositiveButton("请求授权",(d,w)->{if(a.editable())StopBridge.authorize(a,()->{
                        if(!active())return;
                        if(StopBridge.state(app)==StopBridge.State.READY)go(Page.READY);
                        else {status="未获授权。可在 Shizuku 的应用列表里重新允许。";render();}
                    });});
            }else b.setMessage("先在 Shizuku 中启动兼容的服务，然后返回请求授权。");
            b.setNeutralButton("打开 Shizuku",(d,w)->{if(a.editable()&&!StopBridge.manage(a))a.feedback("请从桌面打开 Shizuku");});
            a.showLayer(b.create());
        });
    }
    private boolean developerOptions(){return Settings.Global.getInt(a.getContentResolver(),Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)!=0;}
    // Some ROMs unlock the Settings page before the master switch sets the global flag.
    // The flag is a hint for showing help, never a gate on opening that page.
    private void settings(){
        if(!a.editable())return;
        if(!openSystemPage(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))developerHelp();
        else if(!developerOptions())a.feedback("进入后，先打开页面顶部的开发者选项总开关。");
    }
    private boolean openSystemPage(String action){
        Intent intent=new Intent(action);
        if(page==Page.CODE)intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        try{a.startActivity(intent);return true;}
        catch(ActivityNotFoundException|SecurityException e){return false;}
    }
    private void developerHelp(){
        if(!a.editable())return;
        a.showLayer(new AlertDialog.Builder(a).setTitle("开启开发者选项")
                .setMessage("在「关于手机 → 版本信息」连续点 7 次软件版本号（部分手机显示为「版本号」）。如果已经点过，直接打开开发者选项，开启页面顶部的总开关，再选「无线调试」。")
                .setNegativeButton("返回",null)
                .setNeutralButton("关于手机",(d,w)->{
                    if(a.editable()&&!openSystemPage(Settings.ACTION_DEVICE_INFO_SETTINGS))a.feedback("请在系统设置中打开「关于手机」。");
                })
                .setPositiveButton("已点版本号，打开开发者选项",(d,w)->{
                    if(a.editable()&&!openSystemPage(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))a.feedback("请在系统设置中搜索「开发者选项」。");
                }).create());
    }
    private boolean active(){return dialog!=null&&dialog.isShowing()&&!a.isDestroyed()&&!a.isFinishing();}
    private void begin(){
        if(!a.editable())return;
        if(Build.VERSION.SDK_INT>=33&&a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            awaitingPermission=true;a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTICE_PERMISSION);return;
        }
        openPairing();
    }
    void onNotificationPermission(){
        if(!awaitingPermission||!active())return;awaitingPermission=false;
        openPairing();
    }
    private void openPairing(){
        if(!a.editable())return;
        if(Build.VERSION.SDK_INT>=33&&a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            page=Page.CODE;status="通知未开启，可以分屏输入。";render();return;
        }
        NotificationChannel ch=new NotificationChannel(CHANNEL,"本机配对",NotificationManager.IMPORTANCE_DEFAULT);
        ch.setShowBadge(false);notices.createNotificationChannel(ch);
        if(!notices.areNotificationsEnabled()||notices.getNotificationChannel(CHANNEL).getImportance()==NotificationManager.IMPORTANCE_NONE){
            page=Page.CODE;status="配对通知已关闭，可以分屏输入。";render();return;
        }
        stopNotification();token=new SecureRandom().nextLong();if(token==0)token=1;
        until=SystemClock.elapsedRealtime()+SESSION_MS;notifying=true;MAIN.postDelayed(timeout,SESSION_MS);go(Page.PAIR);stage("waiting");
        notify("请在 5 分钟内保持系统配对框打开，下拉此通知，输入 6 位配对码。",true);settings();
    }
    private void notify(String detail,boolean reply){
        if(!notifying)return;
        Notification.Builder b=new Notification.Builder(app,CHANNEL).setSmallIcon(R.drawable.notice_pair)
             .setContentTitle("反人性软件 · 本机配对").setContentText(detail)
             .setStyle(new Notification.BigTextStyle().bigText(detail))
             .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
             .setTimeoutAfter(page==Page.READY?30000:Math.max(1000,until-SystemClock.elapsedRealtime())).setAutoCancel(page==Page.READY).setCategory(Notification.CATEGORY_STATUS);
        b.setContentIntent(openSetup(a));
        if(reply){
            Intent i=new Intent(a,StopPairingReply.class).setAction(REPLY).putExtra("session",token).putExtra("until",until);
            PendingIntent p=PendingIntent.getBroadcast(a,NOTE,i,PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_MUTABLE);
            RemoteInput input=new RemoteInput.Builder(StopPairingReply.KEY).setLabel("六位配对码").setAllowFreeFormInput(true).build();
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(a,R.drawable.notice_pair),"输入配对码",p)
                    .addRemoteInput(input).setAllowGeneratedReplies(false).build());
        }
        notices.notify(NOTE,b.build());
    }
    private void stopNotification(){MAIN.removeCallbacks(timeout);if(notifying)notices.cancel(NOTE);notifying=false;token=until=0;}
    static void reply(Context app,long sent,long sentUntil,CharSequence input){
        StopSetup self=current.get();long now=SystemClock.elapsedRealtime();
        boolean owner=self!=null && !self.a.isDestroyed() && !self.a.isFinishing();
        PairingWindow.Reply decision=PairingWindow.check(sent,sentUntil,self==null?0:self.token,self==null?0:self.until,
                now,owner,self!=null&&self.page==Page.PAIR);
        switch(decision){
            case EXPIRED:
                if(self!=null&&self.token==sent&&sent!=0)self.expired();
                else {stage("reply-expired");restartNotice(app,"配对通知已过期，请重新生成配对码。");}
                return;
            case CLOSED:stage("reply-closed");restartNotice(app,"上次配置页面已关闭，请重新开始配对。");return;
            case REPLACED:stage("reply-replaced");return; // Keep the new notification and its fresh reply action.
            case BUSY:stage("reply-busy");return;
            default:break;
        }
        if(!self.a.editable()){stage("reply-locked");self.stopNotification();restartNotice(app,"当前不允许修改设置，未执行配对。");return;}
        if(input==null||input.length()>32){
            stage("reply-missing");self.notify("未收到配对码。展开通知，点「输入配对码」并发送六位数字。",true);return;
        }
        Matcher m=INPUT.matcher(input.toString().trim());
        if(!m.matches()||(m.group(1)!=null&&port(m.group(1))==0)){
            stage("reply-format");self.notify("请输入 6 位码；若需指定端口，填写「配对端口 空格 六位码」。",true);return;
        }
        stage("reply-accepted");self.pair(m.group(1)==null?0:port(m.group(1)),m.group(2));
    }
    private void clearSearch(){Runnable r=cancelSearch;cancelSearch=null;if(r!=null)r.run();}
    // A valid reply may arrive while Settings is on top and the dialog is not visible.
    private boolean editing(){return (active()||(notifying&&token!=0&&!a.isDestroyed()&&!a.isFinishing()))&&a.editable();}
    private boolean usable(long id){
        if(id!=sequence)return false;
        if(token!=0&&SystemClock.elapsedRealtime()>=until){expired();return false;}
        return editing();
    }
    private void enterBusy(String text){
        clearSearch();page=Page.BUSY;status=text;render();notify(text,false);
    }
    private void pair(int p,String code){
        if(!editing())return;
        stage(p==0?"discover-pair":"manual-pair");long id=++sequence;enterBusy("正在查找本机配对窗口…");
        Consumer<Integer> found=port->{
            if(!usable(id))return;
            stage("pair-start");status="正在验证配对码…";render();
            IO.execute(()->{
                try{LocalAdb.pair(app,port,code);MAIN.post(()->{if(usable(id)){stage("paired");connect(0);}});}
                catch(IOException|RuntimeException e){MAIN.post(()->failedPair(id,LocalAdb.pairingMessage(e)));}
            });
        };
        if(p!=0)found.accept(p);
        else cancelSearch=LocalAdb.discover(app,true,found,ignored->failedPair(id,"未找到配对端口。请在此通知中输入配对弹窗的端口、一个空格，再输入六位码。"));
    }
    private void failedPair(long id,String reason){
        if(!usable(id))return;stage("pair-failed");clearSearch();page=notifying?Page.PAIR:Page.CODE;status=reason;render();notify(reason,true);
    }
    private void connect(int p){
        if(!editing())return;
        stage("connect-start");if(LocalAdb.connected()){go(Page.READY);stopNotification();return;}
        long id=++sequence;enterBusy("正在连接这台手机…");
        Consumer<Integer> found=port->{
            if(!usable(id))return;
            IO.execute(()->{
                try{LocalAdb.connect(app,port);MAIN.post(()->{
                    if(!usable(id))return;
                    if(!LocalAdb.connected()){failedConnect(id);return;}
                    stage("connected");clearSearch();MAIN.removeCallbacks(timeout);token=0;go(Page.READY);
                    notify("本机已连接。点此回到反人性软件，继续设置规则。",false);
                    a.feedback("停止后台运行 · 本机已连接");
                });}
                catch(IOException|RuntimeException e){MAIN.post(()->failedConnect(id));}
            });
        };
        if(p!=0)found.accept(p);
        else cancelSearch=LocalAdb.discover(app,false,found,ignored->failedConnect(id));
    }
    private void failedConnect(long id){
        if(!usable(id))return;stage("connect-failed");clearSearch();
        status="请确认无线调试已打开，可输入系统主页上的连接端口。若系统已忘记授权，再重新配对。";
        page=Page.PORT;render();notify("配对身份已保留。点此返回，输入无线调试主页上的连接端口。",false);
    }
}
