package dev.local.appdodge;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import rikka.shizuku.Shizuku;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.*;

/** One HOME receipt authorizes at most one stop on its original local or legacy connection. */
final class StopBridge {
    static final String MANAGER="moe.shizuku.privileged.api";
    private static final int REQUEST=7316;
    private static final long WINDOW=2400;
    enum State {
        MISSING("未安装 Shizuku"),OFFLINE("未连接"),DENIED("未授权"),READY("已就绪"),UNSUPPORTED("请更新 Shizuku");
        final String label; State(String label){this.label=label;}
    }
    static final class Result {
        enum Status {
            CONFIRMED("confirmed","已停止后台"),RUNNABLE("not-stopped","未保持停止"),UNKNOWN("not-confirmed","未确认后台停止");
            final String code,title; Status(String c,String t){code=c;title=t;}
        }
        final Status status;final String detail;
        Result(boolean ok,String message){this(ok?Status.CONFIRMED:Status.UNKNOWN,message);}
        Result(Status outcome,String message){status=outcome;detail=message;}
        static Result afterCommand(int outcome){
            switch(outcome){
                case StopPackageState.STOPPED:return new Result(true,"系统已确认应用停止");
                case StopPackageState.RUNNABLE:return new Result(Status.RUNNABLE,"系统已执行强停，但复核时应用未保持停止。本次不再重复停止。");
                case StopPackageState.UNKNOWN:
                case StopPackageState.MULTIPLE:return new Result(false,"系统已执行强停，未能核实目标的停止状态");
                case AppStopWorker.EXPIRED:return new Result(false,"本次强停已过期，未执行");
                default:return new Result(false,"未收到系统的强停回执");
            }
        }
    }
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService IPC=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"app-stop");t.setDaemon(true);return t;});
    private static IBinder remote;
    private static long lastBind=-10000;
    private static Request waiting;
    private static Shizuku.OnRequestPermissionResultListener prompt;
    private static long generation;
    static {
        Shizuku.addBinderReceivedListener(()->generation++, MAIN);
        Shizuku.addBinderDeadListener(()->{generation++;remote=null;lastBind=-10000;}, MAIN);
    }
    /** Odd tokens identify a paired local connection; even tokens identify a Shizuku server. */
    static long readyEpoch(){
        long local=LocalAdb.epoch();
        if(LocalAdb.connected() && LocalAdb.epoch()==local)return local<<1|1L;
        return legacyReady()?generation<<1:-1L;
    }
    private static boolean local(long epoch){return (epoch&1L)!=0;}
    static String source(long epoch){return epoch<0?"none":local(epoch)?"local":"shizuku";}
    static State state(Context c){return readyEpoch()>=0?State.READY:State.OFFLINE;}
    private static boolean legacyReady(){
        try{return Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.getVersion()>=13 &&
                Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}
        catch(RuntimeException unavailable){return false;}
    }
    private static final class Request {
        final Context app;final String pkg;final BooleanSupplier eligible;final Consumer<Result> done;
        final long deadline, authority;
        boolean inspecting;
        Request(Context c,String p,long epoch,long expires,BooleanSupplier gate,Consumer<Result> reply){
            app=c;pkg=p;authority=epoch;deadline=expires;eligible=gate;done=reply;
        }
        void finish(Result r){if(waiting==this)waiting=null;done.accept(r);}
        void finish(boolean ok,String s){finish(new Result(ok,s));}
        boolean current(){return waiting==this && readyEpoch()==authority && SystemClock.elapsedRealtime()<deadline && eligible.getAsBoolean();}
    }
    static State legacyState(Context c){
        try{
            if(!Shizuku.pingBinder()){
                try{c.getPackageManager().getApplicationInfo(MANAGER,0);return State.OFFLINE;}
                catch(PackageManager.NameNotFoundException e){return State.MISSING;}
            }
            if(Shizuku.isPreV11()||Shizuku.getVersion()<13)return State.UNSUPPORTED;
            return Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED?State.READY:State.DENIED;
        }catch(RuntimeException unavailable){return State.OFFLINE;}
    }
    static boolean manage(Context c){
        try{
            Intent i=c.getPackageManager().getLaunchIntentForPackage(MANAGER);
            if(i==null)return false;c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;
        }catch(ActivityNotFoundException|SecurityException e){return false;}
    }
    static void authorize(Context c,Runnable changed){
        State s=legacyState(c);
        if(s!=State.DENIED){if(s==State.READY)prepare(c);changed.run();return;}
        if(prompt!=null)Shizuku.removeRequestPermissionResultListener(prompt);
        final Context app=c.getApplicationContext();
        prompt=(code,granted)->{
            if(code!=REQUEST)return;
            generation++;
            Shizuku.removeRequestPermissionResultListener(prompt);prompt=null;
            if(granted==PackageManager.PERMISSION_GRANTED)prepare(app);
            changed.run();
        };
        try{
            if(Shizuku.shouldShowRequestPermissionRationale()){prompt=null;changed.run();return;}
            Shizuku.addRequestPermissionResultListener(prompt,MAIN);Shizuku.requestPermission(REQUEST);
        }catch(RuntimeException e){if(prompt!=null)Shizuku.removeRequestPermissionResultListener(prompt);prompt=null;changed.run();}
    }
    private static final ServiceConnection CONNECTION=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder service){
            remote=service;
            if(waiting!=null && !local(waiting.authority))inspect(waiting);
        }
        @Override public void onServiceDisconnected(ComponentName name){remote=null;lastBind=-10000;}
    };
    private static boolean connected(){return remote!=null && remote.pingBinder();}
    static void prepare(Context c){
        if(LocalAdb.connected() || !legacyReady() || connected())return;
        long now=SystemClock.elapsedRealtime();
        if(now-lastBind<1200)return;
        lastBind=now;
        try{
            Shizuku.UserServiceArgs args=new Shizuku.UserServiceArgs(new ComponentName(c.getPackageName(),AppStopWorker.class.getName()))
                    .daemon(false).tag("selected-app-stop").version(1).processNameSuffix("stop_worker");
            Shizuku.bindUserService(args,CONNECTION);
        }catch(RuntimeException e){lastBind=-10000;}
    }
    static void stop(Context c,String pkg,long receiptEpoch,long receiptDeadline,BooleanSupplier stillEligible,Consumer<Result> done){
        Context app=c.getApplicationContext();
        if(receiptEpoch<0 || receiptEpoch!=readyEpoch()){
            done.accept(new Result(false,"退回时的授权连接已断开，本次未停止"));return;
        }
        if(!ordinaryTarget(app,pkg)||!stillEligible.getAsBoolean()){
            done.accept(new Result(false,"目标不可用或不是本次拦截"));return;
        }
        if(waiting!=null){Request old=waiting;old.finish(false,"已取消上一次强停");}
        Request r=new Request(app,pkg,receiptEpoch,Math.min(receiptDeadline,SystemClock.elapsedRealtime()+WINDOW),stillEligible,done);
        waiting=r;
        if(local(receiptEpoch) || connected())inspect(r);
        else{
            prepare(app); // Legacy binder may already be binding. Never initiate local pairing here.
            MAIN.postDelayed(()->{if(waiting==r)r.finish(false,"未能及时连接，本次已跳过");},
                    Math.max(0,r.deadline-SystemClock.elapsedRealtime()));
        }
    }
    static boolean ordinaryTarget(Context app,String pkg){
        if(!StopPackageState.validName(pkg)||app.getPackageName().equals(pkg)||MANAGER.equals(pkg))return false;
        try{
            PackageManager pm=app.getPackageManager();
            ApplicationInfo a=pm.getApplicationInfo(pkg,0);
            return a.enabled&&a.uid!=android.os.Process.myUid()&&a.uid%StopPackageState.PER_USER_RANGE>=10000
                    &&UserHandle.getUserHandleForUid(a.uid).equals(android.os.Process.myUserHandle())
                    &&(a.flags&ApplicationInfo.FLAG_PERSISTENT)==0 && pm.getLaunchIntentForPackage(pkg)!=null
                    &&!new NativeRestConfig(app).essentialPackages().contains(pkg);
        }catch(PackageManager.NameNotFoundException|RuntimeException e){return false;}
    }
    private static void inspect(Request r){
        if(!r.current()){if(waiting==r)r.finish(false,"已离开本次拦截，未强停");return;}
        if(r.inspecting)return;
        r.inspecting=true;
        final IBinder session=remote;
        IPC.execute(()->{
            int before=inspectTarget(r,session);
            MAIN.post(()->{
                if(waiting!=r)return;
                if(!r.current() || (!local(r.authority) && session!=remote)){r.finish(false,"本次拦截已结束或授权不可用，未强停");return;}
                if(before==StopPackageState.STOPPED){r.finish(true,"系统已是停止状态，没有重复强停");return;}
                if(before!=StopPackageState.RUNNABLE){
                    r.finish(false,before==StopPackageState.MULTIPLE?"存在双开或多个用户，未强停":"无法确认目标用户和运行状态，未强停");return;
                }
                try{
                    if(!local(r.authority) && Shizuku.checkRemotePermission("android.permission.FORCE_STOP_PACKAGES")!=PackageManager.PERMISSION_GRANTED){
                        r.finish(false,"系统未授予强停权限");return;
                    }
                }catch(RuntimeException unavailable){r.finish(false,"强停授权已失效");return;}
                // Last eligible foreground was corroborated on the UI thread above. No reconnect,
                // replay, authorization prompt or queued command survives this dispatch.
                waiting=null;
                long dispatchBy=Math.min(r.deadline,SystemClock.elapsedRealtime()+300);
                IPC.execute(()->{
                    int after=stopAndVerify(r,session,dispatchBy);
                    MAIN.post(()->r.finish(Result.afterCommand(after)));
                });
            });
        });
    }
    private static int user(){return android.os.Process.myUid()/StopPackageState.PER_USER_RANGE;}
    private static int inspectTarget(Request r,IBinder session){
        if(!local(r.authority))return call(session,AppStopWorker.CHECK,r.pkg,r.deadline);
        try{return LocalAdb.inspect(r.pkg,user(),r.authority>>>1,r.deadline);}
        catch(IOException|RuntimeException unavailable){return StopPackageState.UNKNOWN;}
    }
    private static int stopAndVerify(Request r,IBinder session,long dispatchBy){
        if(SystemClock.elapsedRealtime()>=dispatchBy)return AppStopWorker.EXPIRED;
        if(!local(r.authority))return call(session,AppStopWorker.STOP,r.pkg,dispatchBy);
        long id=r.authority>>>1;
        try{if(!LocalAdb.stop(r.pkg,user(),id,dispatchBy))return AppStopWorker.FAILURE;}
        catch(IOException|RuntimeException unavailable){return AppStopWorker.FAILURE;}
        // Command completion and persistence of the stopped state are different. An Android
        // job may already have reactivated the package. Read only: never stop its new process.
        try{return LocalAdb.inspect(r.pkg,user(),id,SystemClock.elapsedRealtime()+WINDOW);}
        catch(IOException|RuntimeException unavailable){return StopPackageState.UNKNOWN;}
    }
    private static int call(IBinder session,int op,String pkg,long deadline){
        Parcel data=Parcel.obtain(),result=Parcel.obtain();
        try{
            data.writeInterfaceToken(AppStopWorker.DESCRIPTOR);data.writeString(pkg);data.writeLong(deadline);
            if(session==null||!session.transact(op,data,result,0))return AppStopWorker.FAILURE;
            result.readException();return result.readInt();
        }catch(RemoteException|RuntimeException e){return AppStopWorker.FAILURE;}
        finally{data.recycle();result.recycle();}
    }
    private StopBridge(){}
}
