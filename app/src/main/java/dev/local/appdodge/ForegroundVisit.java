package dev.local.appdodge;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Keeps one sampled timed ticket and its foreground-only consumption per target.
 * Uptime measures confirmed use; elapsed realtime measures continuous absence, including sleep.
 * Records are session-local: a stopped service must not estimate use that it never observed. */
final class ForegroundVisit {
    private static final class Budget {
        final Rule policy; final int mask, seconds;
        long consumed, credited, leftAt=-1;
        Budget(Rule policy,int mask,int seconds) {this.policy=policy;this.mask=mask;this.seconds=seconds;}
        long remaining() {return Math.max(0,seconds*1000L-consumed);}
    }
    private final Map<String,Budget> parked=new HashMap<>();
    private Budget current;
    String target, lastTarget;
    int window=-1, mode=-1, seconds=-1, draws, lastSeconds=-1;
    private long startedUp=-1;
    int resumes,exhaustedResumes;
    boolean rejoined, rejoinedExhausted;
    long due;

    static boolean samePolicy(Rule a,Rule b) {
        return a!=null && b!=null && a.packageName.equals(b.packageName) && a.daily==b.daily && a.combo==b.combo
                && a.methodMask==b.methodMask && a.chance==b.chance && a.feedbackMask==b.feedbackMask
                && a.modernPolicy==b.modernPolicy && a.byEffects()==b.byEffects() && a.periods.equals(b.periods) && java.util.Objects.equals(a.sharedBasket,b.sharedBasket) && a.restCombo==b.restCombo
                && a.curtain==b.curtain && a.alternatePackage.equals(b.alternatePackage);
    }
    boolean active(){return current!=null;}
    boolean running(){return startedUp>=0;}
    boolean matches(Rule r,int mask){return current!=null && current.mask==mask && samePolicy(current.policy,r);}
    private Budget remembered(Rule r,long now) {
        Budget b=parked.get(r.packageName);
        if (b!=null && (now-b.leftAt>=MethodEntry.RETRY_WINDOW || !samePolicy(r,b.policy))) {
            parked.remove(r.packageName); return null;
        }
        return b;
    }
    int savedMask(Rule r,long now){
        if(current!=null && samePolicy(current.policy,r))return current.mask;
        Budget b=remembered(r,now);return b==null?0:b.mask;
    }
    /** True only for a newly sampled duration. An application-owned panel can pause a current budget. */
    boolean enter(Rule r,int mask,int win,long now,long uptime,Random random) {
        int timer=MethodPool.single(EffectPlan.clock(r,mask));
        if(timer<0 || win<0 || (EffectPlan.methods(mask)&~r.methodMask)!=0)throw new IllegalArgumentException("Requires a selected timed target");
        boolean fresh=false;
        if(!matches(r,mask)){
            leave(now,uptime);
            Budget b=remembered(r,now);parked.remove(r.packageName);
            if(b==null || b.mask!=mask) {
                int sampled=timer==Rule.EXIT_WAIT_OPEN?1+random.nextInt(2):timer==Rule.EXIT_SHORT?random.nextInt(61):180+random.nextInt(421);
                b=new Budget(r,mask,sampled);draws++;fresh=true;
            }
            current=b;rejoined=!fresh;rejoinedExhausted=rejoined && b.remaining()==0;
            if(rejoined)resumes++; if(rejoinedExhausted)exhaustedResumes++;
            b.leftAt=-1;target=lastTarget=r.packageName;mode=timer;seconds=lastSeconds=b.seconds;
        }
        if(!running())startedUp=uptime;
        window=win;due=now+remaining(uptime);
        return fresh;
    }
    long elapsed(long uptime){return current==null?-1:current.consumed+(running()?Math.max(0,uptime-startedUp):0);}
    long unreported(long uptime){return current==null?-1:Math.max(0,elapsed(uptime)-current.credited);}
    void credit(String pkg){Budget b=current!=null&&pkg.equals(target)?current:parked.get(pkg);if(b!=null)b.credited=b.consumed;}
    long remaining(long uptime){return current==null?-1:Math.max(0,current.seconds*1000L-elapsed(uptime));}
    void suspend(long uptime){
        if(!running())return;
        current.consumed=elapsed(uptime);startedUp=-1;due=0;
    }
    void leave(long now,long uptime){
        if(current==null)return;
        suspend(uptime);current.leftAt=now;parked.put(target,current);clearCurrent();
    }
    private void clearCurrent(){current=null;target=null;window=mode=seconds=-1;startedUp=-1;due=0;rejoined=rejoinedExhausted=false;}
    void reset(){clearCurrent();parked.clear();}
    void reconcile(Map<String,NativeTarget> policies,long now) {
        parked.entrySet().removeIf(e->{
            NativeTarget p=policies.get(e.getKey());Budget b=e.getValue();
            return now-b.leftAt>=MethodEntry.RETRY_WINDOW || p==null || !samePolicy(b.policy,p.policy);
        });
        if(current!=null){
            NativeTarget p=policies.get(target);
            if(p==null || !samePolicy(current.policy,p.policy))clearCurrent();
        }
    }
    long parkedRemaining(String pkg,long now){
        Budget b=parked.get(pkg);
        return b==null || now-b.leftAt>=MethodEntry.RETRY_WINDOW?-1:b.remaining();
    }
}
