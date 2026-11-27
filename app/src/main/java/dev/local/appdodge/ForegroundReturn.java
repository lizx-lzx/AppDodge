package dev.local.appdodge;

/** A HOME decision is kept separate from its Binder result and its observed launcher receipt. */
final class ForegroundReturn {
    static final int MAX_ATTEMPTS=3;
    static final long RECEIPT_WINDOW=4000;
    // Do not turn an earlier opt-in, a missing grant or a late manual HOME into authority.
    enum StopGate { OFF, UNAVAILABLE, UNVERIFIED, ARMED }
    static final long STOP_WINDOW=1500;
    static final class Receipt {
        final String pkg; final long visitMillis; final int mode; final boolean daily;
        final StopGate stop; final long arrivedAt, acceptedAt, bridgeEpoch;
        Receipt(String pkg,long ms,int mode,boolean daily,StopGate stop,long arrived,long accepted,long epoch){
            this.pkg=pkg;visitMillis=ms;this.mode=mode;this.daily=daily;
            this.stop=stop;arrivedAt=arrived;acceptedAt=accepted;bridgeEpoch=epoch;
        }
        long expiresAt(){return Math.min(arrivedAt+STOP_WINDOW,acceptedAt+RECEIPT_WINDOW);}
    }
    private String target;
    private StopGate stopGate=StopGate.OFF;
    private long stopEpoch=-1;
    private Receipt stopReceipt;
    // Only the same, still visible Home arrival may authorize an asynchronous dispatch.
    boolean canStop(Receipt r,long now,long activeEpoch){
        return r!=null && stopReceipt==r && activeEpoch>=0 && activeEpoch==r.bridgeEpoch
                && now>=r.arrivedAt && now<r.expiresAt();
    }
    boolean confirmingStop(){return stopReceipt!=null;}
    boolean armedForHome(){return target!=null && stopGate==StopGate.ARMED;}
    void leftConfirmedHome(){stopReceipt=null;}
    void disarmStop(){
        if(stopGate==StopGate.ARMED)stopGate=StopGate.UNVERIFIED;
        leftConfirmedHome();
    }
    void finishStop(Receipt r){if(stopReceipt==r)stopReceipt=null;}
    private boolean daily;
    private int mode, attempts;
    private long acceptedAt=-1, lastAttemptAt=-1, measured=-1;
    String lastRequested, lastReturned, outcome="none";
    long lastReturnedAt, lastRequestAt=-1;
    boolean lastAccepted;
    int requests, observedReturns, retries, unconfirmed;

    boolean pending(){return target!=null && attempts>0 && !exhausted();}
    boolean exhausted(){return "still-target".equals(outcome);}
    boolean attempted(String pkg){return pkg.equals(target);}
    void observedApp(String pkg){leftConfirmedHome();if(target!=null && !target.equals(pkg))leave("another-app");}
    boolean begin(String pkg,int mode,boolean daily){
        if(attempted(pkg))return false;
        leave("replaced");target=pkg;this.mode=mode;this.daily=daily;return true;
    }
    void requested(boolean accepted,long now,long visitMillis){requested(accepted,now,visitMillis,StopGate.OFF,-1);}
    void requested(boolean accepted,long now,long visitMillis,StopGate gate,long epoch){
        // Retries can revoke, but never acquire a stronger permission for an old episode.
        if(attempts==0){stopGate=gate==StopGate.ARMED && epoch<0?StopGate.UNAVAILABLE:gate;stopEpoch=epoch;}
        else if(stopGate==StopGate.ARMED)stopGate=epoch==stopEpoch?gate:StopGate.UNAVAILABLE;
        if(attempts++==0)measured=visitMillis;else retries++;
        requests++;lastRequested=target;lastAccepted=accepted;
        if(accepted)acceptedAt=now;
        lastRequestAt=lastAttemptAt=now;outcome=accepted?"waiting":"rejected";
    }
    long nextCheckDelay(){
        // Slow launcher transitions should not be hammered. A rejected dispatch can be retried earlier.
        return attempts==1 ? (lastAccepted?650:250) : (attempts==2?800:1100);
    }
    /** Only called with a fresh, independently verified same target after rule and focus checks. */
    boolean retryDue(String pkg,long now){
        if(!attempted(pkg)||!pending()||now-lastAttemptAt<nextCheckDelay())return false;
        if(attempts<MAX_ATTEMPTS)return true;
        outcome="still-target";unconfirmed++;return false;
    }
    Receipt home(long now){
        if(target==null)return null; // multiple genuine Home snapshots preserve a short-lived receipt
        Receipt r=acceptedAt>=0 && now-acceptedAt<=RECEIPT_WINDOW?
                new Receipt(target,measured,mode,daily,exhausted() || !lastAccepted ? StopGate.OFF : stopGate,now,acceptedAt,stopEpoch):null;
        if(r!=null){observedReturns++;lastReturned=r.pkg;lastReturnedAt=now;}
        leave(r!=null?"confirmed":"expired");
        if(r!=null && r.stop==StopGate.ARMED)stopReceipt=r;
        return r;
    }
    void leave(String why){
        if(target!=null && (attempts>0 || acceptedAt>=0) && (!exhausted() || "confirmed".equals(why)))outcome=why;
        stopGate=StopGate.OFF;stopEpoch=-1;leftConfirmedHome();
        target=null;attempts=0;acceptedAt=lastAttemptAt=measured=-1;
    }
}
