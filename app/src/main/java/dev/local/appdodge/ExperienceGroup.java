package dev.local.appdodge;

import java.util.Random;

/** A single visit can own multiple effects. Each keeps its own per-app re-entry history.
 * All time values are elapsed realtime; the service rechecks foreground at each proposed edge. */
final class ExperienceGroup {
    static final int LOAD=1<<Rule.EXIT_LOAD, STALL=1<<Rule.EXIT_STALL,
            NARROW=1<<Rule.EXIT_NARROW, MUTE=1<<Rule.EXIT_MUTE;
    private static final long PERCEPTIBLE_MS=800;
    private final ExperienceSchedule load,stall,narrow;
    private String pkg;
    private int mask, active, presented;
    private long next=Long.MAX_VALUE, stallSince=-1, muteSince=-1;
    private boolean loadDone;

    ExperienceGroup(Random random) {
        load=new ExperienceSchedule(random);
        stall=new ExperienceSchedule(random);
        narrow=new ExperienceSchedule(random);
    }
    boolean same(String target,int selections) { return mask==selections && target.equals(pkg); }
    int mask() {return mask;}
    int active() {return active;}
    int singleMode() {return Integer.bitCount(mask)==1?Integer.numberOfTrailingZeros(mask):-1;}
    boolean pastLoading() {return (mask&LOAD)==0 || loadDone;}
    boolean contentExperienced() {return mask!=0 && (presented&mask)==mask;}
    long nextEdge() {return next;}
    float fraction(long now){return narrow.fraction(now);}

    void advance(String target,int selections,long now,boolean visualsAllowed) {
        if(selections==0 || (selections&~(LOAD|STALL|NARROW|MUTE))!=0 || Integer.bitCount(selections)>3)
            throw new IllegalArgumentException("Expected one to three content effects");
        if(!same(target,selections)){leave(now);pkg=target;mask=selections;}
        active=mask&MUTE;next=Long.MAX_VALUE;
        if(!visualsAllowed)return; // do not start a new visual phase behind somebody else's window
        if ((mask&LOAD)!=0) {
            load.begin(pkg,Rule.EXIT_LOAD,now);
            if(load.phase(now)==ExperienceSchedule.LOADING) {
                active|=LOAD;
                next=load.nextEdge(now);
                return; // preserve the full freeze/narrow experience until content is revealed
            }
            loadDone=true;
        }
        if((mask&STALL)!=0) {
            stall.begin(pkg,Rule.EXIT_STALL,now);
            if(stall.phase(now)==ExperienceSchedule.FROZEN)active|=STALL;
            next=stall.nextEdge(now);
        }
        if((mask&NARROW)!=0) {
            narrow.begin(pkg,Rule.EXIT_NARROW,now);
            active|=NARROW;
        }
    }
    /** A zero-second return must not preempt first presentation. "Applied" comes from
     * actual attached surfaces and the owned media lease, not just the random ticket.
     * Expired timers never treat a covered application as visible. */
    void observe(int applied,long now) {
        if((applied&LOAD)!=0)presented|=LOAD;
        if((applied&NARROW)!=0 && (presented&NARROW)==0) {
            long firstVisible=(narrow.ramp()+9)/10;
            long left=firstVisible-narrow.narrowTime(now);
            if(left<=0)presented|=NARROW; else next=Math.min(next,now+left);
        }
        stallSince=observeDuration(STALL,stallSince,applied,now);
        muteSince=observeDuration(MUTE,muteSince,applied,now);
    }
    private long observeDuration(int bit,long start,int applied,long now) {
        if((applied&bit)==0 || (presented&bit)!=0)return -1;
        if(start<0)start=now;
        long due=start+PERCEPTIBLE_MS;
        if(now>=due)presented|=bit;else next=Math.min(next,due);
        return start;
    }
    void suspend() {active=0;next=Long.MAX_VALUE;stallSince=muteSince=-1;}
    void leave(long now) {
        load.leave(now);stall.leave(now);narrow.leave(now);
        suspend();pkg=null;mask=presented=0;loadDone=false;
    }
    void reset(long now) {
        leave(now);load.reset(now);stall.reset(now);narrow.reset(now);
    }
}
