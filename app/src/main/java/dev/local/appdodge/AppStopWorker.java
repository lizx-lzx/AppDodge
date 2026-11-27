package dev.local.appdodge;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * Private Shizuku v13 UserService: not a manifest service, receiver, or shell interface.
 * Its binder accepts only this installation's UID. The target user always comes from that UID.
 */
public final class AppStopWorker extends Binder {
    static final String DESCRIPTOR="dev.local.appdodge.AppStopWorker";
    static final int CHECK=IBinder.FIRST_CALL_TRANSACTION, STOP=CHECK+1;
    static final int EXPIRED=3, FAILURE=4;
    private final int owner;
    private final String own;
    private String checked;
    private long checkedUntil;
    public AppStopWorker(Context packageContext){
        owner=packageContext.getApplicationInfo().uid;own=packageContext.getPackageName();
        attachInterface(null,DESCRIPTOR);
    }
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
        if(code==INTERFACE_TRANSACTION){reply.writeString(DESCRIPTOR);return true;}
        final int caller=Binder.getCallingUid();
        if(code==16777115){ // Shizuku's reserved destroy transaction.
            if(caller!=0 && caller!=2000 && caller!=owner)throw new SecurityException("Wrong caller");
            System.exit(0);return true;
        }
        if(code!=CHECK&&code!=STOP)return super.onTransact(code,data,reply,flags);
        data.enforceInterface(DESCRIPTOR);
        if(caller!=owner)throw new SecurityException("Wrong caller");
        String pkg=data.readString();long deadline=data.readLong();int codeOut=FAILURE;
        if(StopPackageState.validName(pkg)&&!own.equals(pkg)&&!StopBridge.MANAGER.equals(pkg)
                &&!pkg.equals("com.android.systemui")&&!pkg.equals("com.android.settings")
                &&!pkg.equals("com.android.phone")&&!pkg.equals("com.android.emergency")){
            int user=caller/StopPackageState.PER_USER_RANGE;
            try {
                if(deadline<SystemClock.elapsedRealtime())codeOut=EXPIRED;
                else if(code==CHECK){
                    codeOut=inspect(pkg,user);
                    synchronized(this){checked=codeOut==StopPackageState.RUNNABLE?pkg:null;checkedUntil=deadline;}
                }else if(takeChecked(pkg)){
                    // CHECK already excluded other profiles and mandatory system packages client-side.
                    // No arbitrary command, username, flags, process name or shell string comes over IPC.
                    Process p=new ProcessBuilder("/system/bin/cmd","activity","force-stop","--user",Integer.toString(user),pkg)
                            .redirectErrorStream(true).start();
                    try{
                        if(p.waitFor(3,TimeUnit.SECONDS)&&p.exitValue()==0)codeOut=inspect(pkg,user);
                    }finally{p.destroy();}
                }
            }catch(IOException|RuntimeException e){codeOut=FAILURE;}
             catch(InterruptedException e){Thread.currentThread().interrupt();codeOut=FAILURE;}
        }
        reply.writeNoException();reply.writeInt(codeOut);return true;
    }
    private synchronized boolean takeChecked(String pkg){
        boolean ok=pkg.equals(checked)&&SystemClock.elapsedRealtime()<checkedUntil;
        checked=null;return ok;
    }
    private static int inspect(String pkg,int user)throws IOException,InterruptedException {
        Process p=new ProcessBuilder("/system/bin/dumpsys","-t","2","package",pkg).redirectErrorStream(true).start();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8"))){
            return StopPackageState.read(reader,pkg,user);
        }finally{p.destroy();}
    }
}
