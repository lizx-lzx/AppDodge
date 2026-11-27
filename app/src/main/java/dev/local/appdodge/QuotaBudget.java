package dev.local.appdodge;

/** Day, session and post-session rest are different clocks. Only a confirmed return ends a round. */
final class QuotaBudget {
    static final int SESSION=-21,DAY=-22,REST=-23;
    long date,spent,round,restUntil;
    void account(long today,long elapsed,long sinceMidnight){
        if(date!=today){date=today;spent=0;spent+=Math.min(elapsed,sinceMidnight);}else spent+=elapsed;
        round+=elapsed;
    }
    int reason(UseLimits l,long now){
        if(l.day>0 && spent>=l.day*60000L)return DAY;
        if(l.session>0&&l.rest>0&&restUntil>now)return REST;
        if(l.session>0&&round>=l.session*60000L)return SESSION;
        return 0;
    }
    long next(UseLimits l){
        return Math.min(l.session==0?Long.MAX_VALUE:l.session*60000L-round,
                        l.day==0?Long.MAX_VALUE:l.day*60000L-spent);
    }
    void returned(int reason,UseLimits l,long wall){
        if(reason==SESSION || reason==DAY){round=0;restUntil=reason==SESSION?wall+l.rest*60000L:0;}
    }
}
