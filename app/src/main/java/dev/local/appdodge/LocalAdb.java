package dev.local.appdodge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import io.github.muntashirakon.adb.*;

/** On-device, user-paired TLS only. No public shell, destination parameter or LAN connection.
 * Commands never reconnect; setup/discovery are independent of an interception receipt. */
final class LocalAdb {
    private LocalAdb(){}
    private static final String HOST="127.0.0.1", NAME="AppDodge", ALIAS="AppDodge.LocalPair.1";
    private static final Object AUTH=new Object(), SETUP=new Object();
    private static final ScheduledExecutorService WATCH=Executors.newSingleThreadScheduledExecutor();
    private static final ExecutorService RECOVER=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static volatile AdbConnection live;
    private static volatile long generation;
    private static long intent;
    private static int manual;
    private static AdbConnection automaticProbe;
    private static void userStarts(){
        synchronized(AUTH){intent++;manual++;closeLater(automaticProbe);automaticProbe=null;}
    }
    private static void userEnds(){synchronized(AUTH){manual--;}}
    private static long lastPrepare=-60000;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("local_wireless",Context.MODE_PRIVATE);}
    static boolean paired(Context c){return prefs(c).getBoolean("paired",false);}
    static boolean connected(){AdbConnection c=live;return c!=null && c.isConnectionEstablished();}
    static long epoch(){return generation;}
    private static void failure(String stage,Throwable e){
        StringBuilder t=new StringBuilder();
        for(int i=0;i<5 && e!=null;i++,e=e.getCause()){
            if(i!=0)t.append('/');t.append(e.getClass().getSimpleName());
        }
        android.util.Log.w("AppDodgeLocal","stage="+stage+" error="+t);
    }

    private static void validatePort(int port)throws IOException{if(port<1024 || port>65535)throw new IOException("请输入系统显示的端口");}
    private static KeyStore.PrivateKeyEntry identity(boolean create)throws GeneralSecurityException,IOException {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)){
            if(!create)throw new KeyStoreException("pairing identity missing");
            KeyPairGenerator gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
            gen.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE,KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1).build());
            gen.generateKeyPair();
        }
        return (KeyStore.PrivateKeyEntry)ks.getEntry(ALIAS,null);
    }
    private static final class PairFailure extends IOException {
        PairFailure(String message,Throwable cause){super(message,cause);}
    }
    static String pairingMessage(Throwable error){
        return error instanceof PairFailure ? error.getMessage() : "未能配对。请保持系统配对弹窗打开，再试一次。";
    }
    private static PairFailure pairingFailure(String stage,Throwable e){
        failure(stage,e);
        String message;
        switch(stage){
            case "pair-socket":message="未连到本机配对端口。请保持配对弹窗打开，使用弹窗里的端口。";break;
            case "pair-key":message="手机未能读取或建立应用的配对身份。请解锁手机后再试。";break;
            case "pair-tls-provider":case "pair-tls-handshake":case "pair-tls-export":
                message="本机配对的安全连接未建立。请确认填的是配对弹窗里的端口，而不是无线调试主页端口。";break;
            case "pair-peer-auth":message="系统未确认这次配对。请重新生成配对码，保持弹窗打开后再试。";break;
            default:message="本机配对协商未完成。请保持系统配对弹窗打开，再试一次。";
        }
        return new PairFailure(message,e);
    }
    static void pair(Context c,int port,String code)throws IOException {
        if(code==null||!code.matches("[0-9]{6}"))throw new IOException("请输入系统显示的六位配对码");
        validatePort(port);
        userStarts();try{synchronized(SETUP){
            byte[] pass=code.getBytes(StandardCharsets.US_ASCII);
            String stage="pair-key";
            android.util.Log.i("AppDodgeLocal","pair=requested");
            try{
                KeyStore.PrivateKeyEntry k=identity(true);
                stage="pair-tls-provider";
                try(PairingConnectionCtx p=new PairingConnectionCtx(HOST,port,pass,k.getPrivateKey(),k.getCertificate(),NAME)){
                    try{p.start();}finally{stage=p.diagnosticPhase();}
                }
                stage="pair-store";prefs(c).edit().putBoolean("paired",true).apply();
                android.util.Log.i("AppDodgeLocal","pair=confirmed");
            }catch(IOException|GeneralSecurityException|RuntimeException|LinkageError e){throw pairingFailure(stage,e);}
            finally{Arrays.fill(pass,(byte)0);}
        }}finally{userEnds();}
    }
    static void connect(Context c,int port)throws IOException {
        userStarts();try{connectWith(c,port,-1);}finally{userEnds();}
    }
    private static void connectWith(Context c,int port,long automatic)throws IOException {
        validatePort(port);
        synchronized(SETUP){
            synchronized(AUTH){if(automatic>=0 && (automatic!=intent||manual!=0))return;}
            if(connected())return;
            if(!paired(c))throw new IOException("请先用系统配对码配对");
            AdbConnection next=null;String stage="key";
            try{
                KeyStore.PrivateKeyEntry k=identity(false);
                stage="loopback";next=new AdbConnection.Builder(HOST,port).setApi(Build.VERSION.SDK_INT).setDeviceName(NAME)
                        .setPrivateKey(k.getPrivateKey()).setCertificate(k.getCertificate()).build();
                synchronized(AUTH){
                    if(automatic>=0){if(automatic!=intent||manual!=0)return;automaticProbe=next;}
                }
                stage="tls";if(!next.connect(6500,TimeUnit.MILLISECONDS,true))throw new IOException("未连接到本机无线调试");
                stage="shell-proof";String proof=UUID.randomUUID().toString();
                LocalAdbProof.pending=proof;
                byte[] identity=exec(next,"/system/bin/content call --user "+user()+" --uri content://dev.local.appdodge.localpair --method prove",now()+6000,8192);
                stage="verify-proof";if(!new String(identity,StandardCharsets.UTF_8).contains("proof="+proof))throw new IOException("无法确认本机系统调试服务");
                AdbConnection stale;
                synchronized(AUTH){if(automatic>=0 && (automatic!=intent||manual!=0))return;
                    stale=live;live=next;next=null;generation++;}
                closeLater(stale);
                prefs(c).edit().putInt("last_port",port).apply();
            }catch(IOException e){failure(stage,e);throw e;}
            catch(GeneralSecurityException|AdbPairingRequiredException|InterruptedException|RuntimeException e){
                failure(stage,e);
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                throw new IOException("无法连接，请检查本机无线调试和配对状态",e);
            }finally{LocalAdbProof.pending=null;synchronized(AUTH){automaticProbe=null;}close(next);}
        }
    }
    static void disconnect(){
        AdbConnection stale;synchronized(AUTH){intent++;stale=live;live=null;generation++;closeLater(automaticProbe);}
        closeLater(stale);
    }
    private static void close(AdbConnection c){if(c!=null)try{c.close();}catch(IOException|RuntimeException ignored){}}
    private static void closeLater(AdbConnection c){if(c!=null)WATCH.execute(()->close(c));}
    private static int user(){return Process.myUid()/StopPackageState.PER_USER_RANGE;}
    private static long now(){return SystemClock.elapsedRealtime();}
    private static AdbConnection session(String pkg,int user,long expected,long deadline)throws IOException{
        synchronized(AUTH){
            if(user!=user() || !StopPackageState.validName(pkg) || pkg.equals("dev.local.appdodge") || now()>=deadline
                    || generation!=expected || !connected()) throw new IOException("本次连接或目标已失效");
            return live;
        }
    }
    static int inspect(String pkg,int user,long expected,long deadline)throws IOException{
        AdbConnection c=session(pkg,user,expected,deadline);
        // Validated package grammar admits only letters, digits, underscores and dots.
        byte[] out=exec(c,"/system/bin/dumpsys -t 2 package "+pkg,deadline,4*1024*1024);
        if(generation!=expected || !connected())return StopPackageState.UNKNOWN;
        try(BufferedReader in=new BufferedReader(new InputStreamReader(new ByteArrayInputStream(out),StandardCharsets.UTF_8))){
            return StopPackageState.read(in,pkg,user);
        }
    }
    static boolean stop(String pkg,int user,long expected,long deadline)throws IOException {
        AdbConnection c=session(pkg,user,expected,deadline);
        // The admission deadline applies before sending only; the issued cmd may need time to finish.
        exec(c,"/system/bin/cmd activity force-stop --user "+user+" "+pkg,now()+2200,8192,()->c==live&&generation==expected&&connected()&&now()<deadline);
        return generation==expected && connected();
    }
    // Only internal fixed callers above. shell,v2 gives a typed exit packet, not echoed text.
    private static byte[] exec(AdbConnection c,String cmd,long deadline,int limit)throws IOException {
        return exec(c,cmd,deadline,limit,()->now()<deadline);
    }
    private static byte[] exec(AdbConnection c,String cmd,long deadline,int limit,BooleanSupplier gate)throws IOException {
        long millis=deadline-now();
        if(millis<=0 || !c.isConnectionEstablished())throw new IOException("连接已过期");
        ScheduledFuture<?> watchdog=WATCH.schedule(()->close(c),millis,TimeUnit.MILLISECONDS);
        try(AdbStream stream=c.open("shell,v2,raw:"+cmd,AUTH,gate);InputStream in=stream.openInputStream()){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] head=new byte[5],chunk=new byte[32768];
            while(true){
                read(in,head,5);
                int len=(head[1]&255)|((head[2]&255)<<8)|((head[3]&255)<<16)|((head[4]&255)<<24);
                if(len<0||len>limit-out.size())throw new IOException("系统回执过长");
                if(head[0]==3){if(len!=1 || in.read()!=0)throw new IOException("系统未完成操作");return out.toByteArray();}
                if(head[0]!=1 && head[0]!=2)throw new IOException("系统回执无效");
                for(int left=len;left>0;){int n=Math.min(left,chunk.length);read(in,chunk,n);out.write(chunk,0,n);left-=n;}
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("连接中断",e);}
         catch(AdbPairingRequiredException|RuntimeException e){throw new IOException("本机连接已失效",e);}
        finally{watchdog.cancel(false);}
    }
    private static void read(InputStream in,byte[] buf,int n)throws IOException {
        for(int p=0;p<n;){int got=in.read(buf,p,n-p);if(got<=0)throw new EOFException("系统回执不完整");p+=got;}
    }
    static Runnable discover(Context c,boolean pairing,Consumer<Integer> found,Consumer<String> failed){
        return LocalAdbDiscovery.start(c,pairing,found,failed);
    }
    static void prepare(Context c){
        if(!paired(c)||connected())return;
        final long ticket;
        synchronized(AUTH){if(manual!=0||now()-lastPrepare<20000)return;lastPrepare=now();ticket=intent;}
        Context app=c.getApplicationContext();
        RECOVER.execute(()->{
            int last=prefs(app).getInt("last_port",0);
            try{if(last>0){connectWith(app,last,ticket);return;}}catch(IOException ignored){}
            MAIN.post(()->discover(app,false,p->RECOVER.execute(()->{try{connectWith(app,p,ticket);}catch(IOException ignored){}}),error->{}));
        });
    }
}
