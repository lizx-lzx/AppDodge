package dev.local.appdodge;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Only selected target packages in this process; short re-entry memory expires after five minutes.
 * All times are elapsed realtime. Nobody sleeps, restarts a visit on repaint, or schedules a
 * device action here. At a deadline the service must establish the foreground again. */
final class ExperienceSchedule {
    static final long REMEMBER_MS=5*60_000L;
    static final int OPEN=0, LOADING=1, FROZEN=2, NARROW=3, MUTED=4;
    private static final class Last {
        long left, progress, ramp;
    }
    private final Map<String,Last> recent=new HashMap<>();
    private final Random random;
    private long since, stepAt, ramp, progress;
    private boolean frozen;
    private String pkg;
    private int mode=-1;

    ExperienceSchedule(Random random) { this.random=random; }
    private long seconds(int min,int max) { return (min+random.nextInt(max-min+1))*1000L; }
    boolean same(String target,int selected) { return selected==mode && target.equals(pkg); }
    int mode() { return mode; }
    void begin(String target,int selected,long now) {
        if(same(target,selected))return;
        leave(now);
        recent.entrySet().removeIf(e->now-e.getValue().left>=REMEMBER_MS);
        pkg=target; mode=selected; since=now; stepAt=Long.MAX_VALUE;
        Last last=recent.get(key());
        switch(mode) {
        case Rule.EXIT_LOAD:
            stepAt=now+(last==null?15_000:30_000); break;
        case Rule.EXIT_STALL:
            frozen=last!=null;
            stepAt=now+(frozen?seconds(4,6):seconds(3,6)); break;
        case Rule.EXIT_NARROW:
            ramp=last==null?seconds(20,40):last.ramp;
            progress=last==null?0:last.progress; break;
        case Rule.EXIT_MUTE: break;
        default: throw new IllegalArgumentException("Unknown daily effect");
        }
    }
    private String key() { return mode+":"+pkg; }
    long ramp() { return ramp; }
    long narrowTime(long now) { return Math.min(ramp,progress+Math.max(0,now-since)); }
    float fraction(long now) { return mode==Rule.EXIT_NARROW ? (float)narrowTime(now)/ramp : 0; }
    int phase(long now) {
        if(mode==Rule.EXIT_LOAD) return now<stepAt?LOADING:OPEN;
        if(mode==Rule.EXIT_NARROW)return NARROW;
        if(mode==Rule.EXIT_MUTE)return MUTED;
        if(mode==Rule.EXIT_STALL) {
            if(now>=stepAt) {
                frozen=!frozen; stepAt=now+(frozen?seconds(4,6):seconds(3,6));
            }
            return frozen?FROZEN:OPEN;
        }
        return OPEN;
    }
    long nextEdge(long now) {
        if(mode==Rule.EXIT_STALL || (mode==Rule.EXIT_LOAD && now<stepAt))return stepAt;
        return Long.MAX_VALUE;
    }
    void leave(long now) {
        if(pkg==null) return;
        if(mode!=Rule.EXIT_MUTE) {
            Last last=new Last();last.left=now;
            if(mode==Rule.EXIT_NARROW){last.ramp=ramp;last.progress=narrowTime(now);}
            recent.put(key(),last);
        }
        pkg=null;mode=-1;ramp=0;progress=0;stepAt=Long.MAX_VALUE;
    }
    void reset(long now) { leave(now);recent.clear(); }
}
