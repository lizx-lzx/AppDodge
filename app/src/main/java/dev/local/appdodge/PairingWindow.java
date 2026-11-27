package dev.local.appdodge;

/** A notification grants one setup window, never extends it when a message is edited or retried. */
final class PairingWindow {
    enum Reply { FRESH, EXPIRED, CLOSED, REPLACED, BUSY }
    static Reply check(long sentToken,long sentUntil,long liveToken,long liveUntil,long now,boolean owner,boolean waiting){
        if(liveToken!=0 && sentToken!=liveToken)return Reply.REPLACED;
        if(sentUntil<=now || (liveUntil!=0 && liveUntil<=now))return Reply.EXPIRED;
        if(!owner||liveToken==0||sentToken==0)return Reply.CLOSED;
        if(!waiting)return Reply.BUSY;
        return Reply.FRESH;
    }
}
