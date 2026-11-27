package dev.local.appdodge;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/** Find one advertised system port on this handset, ignoring neighboring Wi-Fi devices. */
final class LocalAdbDiscovery implements NsdManager.DiscoveryListener {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final NsdManager nsd;private final String type;
    private final Consumer<Integer> found;private final Consumer<String> failed;
    private final ArrayDeque<NsdServiceInfo> queue=new ArrayDeque<>();
    private boolean done,started,resolving;
    private int seen,nonlocal,empty,busy,errors;
    private void diag(String event){
        android.util.Log.i("AppDodgeLocal","discover="+(type.contains("pairing")?"pair":"connect")+" event="+event
            +" seen="+seen+" nonlocal="+nonlocal+" empty="+empty+" busy="+busy+" resolveErrors="+errors);
    }
    private final Runnable timeout=()->end(0,"未找到本机端口，可以手动输入系统显示的端口");
    static Runnable start(Context c,boolean pair,Consumer<Integer> ok,Consumer<String> no){
        LocalAdbDiscovery d=new LocalAdbDiscovery(c,pair,ok,no);d.main.post(d::begin);return ()->d.main.post(()->d.end(-1,null));
    }
    private LocalAdbDiscovery(Context c,boolean pair,Consumer<Integer> ok,Consumer<String> no){
        nsd=c.getSystemService(NsdManager.class);type=pair?"_adb-tls-pairing._tcp":"_adb-tls-connect._tcp";found=ok;failed=no;
    }
    private void begin(){
        if(done)return;
        try{nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,this);main.postDelayed(timeout,8500);}
        catch(RuntimeException e){end(0,"未能查找本机端口");}
    }
    private void end(int port,String msg){
        if(done)return;done=true;main.removeCallbacks(timeout);queue.clear();
        if(started)try{nsd.stopServiceDiscovery(this);}catch(RuntimeException ignored){}
        diag(port>0?"local-found":msg==null?"cancelled":"unavailable");
        if(port>0)found.accept(port);else if(msg!=null)failed.accept(msg);
    }
    @Override public void onDiscoveryStarted(String t){main.post(()->{started=true;if(done)try{nsd.stopServiceDiscovery(this);}catch(RuntimeException ignored){}});}
    @Override public void onDiscoveryStopped(String t){}
    @Override public void onStartDiscoveryFailed(String t,int e){main.post(()->{diag("start-failed-"+e);end(0,"无法查找本机端口");});}
    @Override public void onStopDiscoveryFailed(String t,int e){}
    @Override public void onServiceLost(NsdServiceInfo s){}
    @Override public void onServiceFound(NsdServiceInfo s){main.post(()->{if(!done&&queue.size()<48){seen++;queue.add(s);resolve();}});}
    private void resolve(){
        if(done||resolving||queue.isEmpty())return;
        NsdServiceInfo item=queue.remove();resolving=true;
        try{nsd.resolveService(item,new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo r,int code){main.post(()->{
                resolving=false;if(done)return;
                if(code==NsdManager.FAILURE_ALREADY_ACTIVE && ++busy<=8){
                    queue.addLast(item);main.postDelayed(LocalAdbDiscovery.this::resolve,320);
                }else{errors++;diag("resolve-failed-"+code);resolve();}
            });}
            public void onServiceResolved(NsdServiceInfo r){main.post(()->{
                resolving=false;if(done)return;
                InetAddress host=r.getHost();
                try{if(r.getPort()>=1024 && r.getPort()<=65535 && host!=null
                       && (host.isLoopbackAddress()||NetworkInterface.getByInetAddress(host)!=null)){end(r.getPort(),null);return;}}
                catch(SocketException ignored){}
                if(host==null)empty++;else nonlocal++;
                resolve();
            });}
        });}catch(RuntimeException ex){resolving=false;resolve();}
    }
}
