package dev.local.appdodge;

import java.util.Random;

/** Volatile consent for ONE icon on the current HOME page. Nothing survives leaving that surface. */
final class IconIntervention {
    static final long OPEN_DELAY=10000, OPEN_WINDOW=60000, LOCK_FRESHNESS=1000;
    static final int IDLE=0, WAIT=1, READY=2, EXPIRED=3, LOCK_DUE=4, OFFER=5;
    int mode=-1, episode;
    long due, expires;
    boolean active() { return mode!=-1; }
    void start(int selected,long now,Random random) {
        start(selected,0,now,random);
    }
    void start(int selected,int chosenEpisode,long now,Random random) {
        if (active()) throw new IllegalStateException("Cancel the previous icon first");
        mode=selected; episode=chosenEpisode;
        if (mode==Rule.EXIT_WAIT_OPEN) { due=now+OPEN_DELAY; expires=due+OPEN_WINDOW; }
        else if (mode==Rule.EXIT_LOCK_DELAY) { due=now+(5+random.nextInt(6))*1000L; expires=due+LOCK_FRESHNESS; }
        else if (mode==Rule.EXIT_ALTERNATIVE) { due=now; expires=now+30000L; }
        else throw new IllegalArgumentException("Not a pending icon action");
    }
    int phase(long now) {
        if (!active()) return IDLE;
        if (now>=expires) return EXPIRED;
        if (mode==Rule.EXIT_LOCK_DELAY) return now>=due ? LOCK_DUE : WAIT;
        if (mode==Rule.EXIT_ALTERNATIVE) return OFFER;
        return now>=due ? READY : WAIT;
    }
    int seconds(long now) { return (int)Math.max(0,(due-now+999)/1000); }
    void cancel() { mode=-1; episode=0; due=expires=0; }
}
