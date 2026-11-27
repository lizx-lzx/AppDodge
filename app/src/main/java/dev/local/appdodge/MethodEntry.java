package dev.local.appdodge;

import java.util.Map;
import java.util.Random;

/** One decision per visit. A positive selection is a bitmask; PASS and NONE are never masks. */
final class MethodEntry {
    static final long LAUNCH_WINDOW=5000, RETRY_WINDOW=5*60_000L;
    static final int NONE=-1, PASS=-2;
    private final ForegroundVisit budgets;
    MethodEntry(ForegroundVisit budgets) { this.budgets=budgets; }
    private Rule visitPolicy;
    private String pkg, lastIcon;
    private int mask=NONE, width, height;
    private boolean daily,entryPulse,ownIcon;
    private long expectUntil, nextIcon, resumeUntil;
    int draws, passes, lastMask=NONE;
    String lastSource="none";
    int dailyDecisions,dailyHits,lastChance=-1;
    String decisionTarget,decisionEntry="none",decisionResult="none";
    void reset() { clearVisit(); lastIcon=null; nextIcon=0; }
    void endSurface(long now) {
        // Sleep suspends the visible visit. Unlocking straight into the same app is not an
        // independent opening. Never carry an unverified launch or a later lock/redirect over sleep.
        if(expectUntil!=0)clearVisit(); else if(pkg!=null){cancelAppTerminal();resumeUntil=now+RETRY_WINDOW;}
        lastIcon=null;nextIcon=0;
    }
    void clearVisit() { pkg=null; visitPolicy=null; mask=NONE; width=height=0; expectUntil=resumeUntil=0; daily=false; entryPulse=false;ownIcon=false; }
    /** The only probability gate, entered once for a new visit. A saved allowance chooses the
     * intervention AFTER a hit; it cannot authorize a hit. A miss stays valid until confirmed exit. */
    private boolean trigger(String target,boolean isDaily,int chance,String source,Random random) {
        lastSource=source;
        if(!isDaily)return true;
        dailyDecisions++;decisionTarget=target;lastChance=chance;decisionEntry=source;
        boolean hit=chance==100 || chance>0 && random.nextInt(100)<chance;
        if(hit){dailyHits++;decisionResult="hit";}
        else {passes++;lastMask=PASS;decisionResult="pass";}
        return hit;
    }
    private boolean pending(long now) {
        if (expectUntil!=0 && now>=expectUntil) clearVisit();
        return expectUntil!=0;
    }
    boolean mayTap(String target,long now) {
        if (pending(now) && target.equals(pkg)) return false;
        return !target.equals(lastIcon) || now>=nextIcon;
    }
    static int singleMode(int picked) { return picked<=0?picked:MethodPool.single(picked); }
    private static boolean fits(int picked,int eligible,boolean combo) {
        return picked>0 && (picked&~eligible)==0 && (Integer.bitCount(picked)>1)==combo;
    }
    static int sample(int eligible,boolean combo,Random random) {
        if (!combo) return MethodPool.bit(MethodPool.draw(eligible,random));
        if (!MethodPool.validCombo(eligible)) throw new IllegalArgumentException("组合至少需要两种可搭配的方式");
        return RandomTickets.combination(eligible,random);
    }
    private int draw(String target,int eligible,boolean isDaily,boolean combo,int chance,String source,long now,Random random,Rule policy) {
        if (eligible==0) return NONE;
        if(!trigger(target,isDaily,chance,source,random))return PASS;
        int timed=policy==null?0:budgets.savedMask(policy,now);
        if (timed!=0 && fits(timed,eligible,combo)) {
            lastMask=timed; lastSource="retry"; return timed;
        }
        lastMask=sample(eligible,combo,random); draws++;return lastMask;
    }
    // Icon actions never stack: combinations are entirely foreground effects and use the real launcher.
    int icon(String target,int pool,long now,Random random) { return icon(target,pool,false,100,now,random,null); }
    int icon(Rule rule,long now,Random random) { return icon(rule.packageName,rule.methodMask,rule.daily,rule.chance,now,random,rule); }
    private int icon(String target,int pool,boolean isDaily,int chance,long now,Random random,Rule rule) {
        clearVisit(); lastIcon=target; nextIcon=now+FeedbackBasket.REPEAT_MS;
        return singleMode(draw(target,pool,isDaily,false,chance,"icon",now,random,rule));
    }
    void launching(String target,int mode,long now) { launching(target,mode,false,now); }
    void launching(Rule rule,int mode,long now) { launching(rule.packageName,mode,rule.daily,now); visitPolicy=rule; }
    void launching(String target,int mode,boolean isDaily,long now) {
        clearVisit(); pkg=target; mask=mode==PASS?PASS:MethodPool.bit(mode); daily=isDaily; expectUntil=now+LAUNCH_WINDOW;
    }
    void home(long now) {
        if (!pending(now) && pkg!=null) { clearVisit(); lastIcon=null; nextIcon=0; }
    }
    void interrupted(boolean launchTransition,long now) {
        // A shade, keyboard, rotation or an unidentifiable surface is not a verified departure.
        // Actual HOME, another confirmed app or a changed policy will end this visit instead.
        if (mask==PASS)return;
        if (pending(now)){if(!launchTransition)clearVisit();}
        else cancelAppTerminal();
    }
    private int chooseEpisode(Rule r,boolean icon,long now,Random random) {
        int eligible=icon?r.methodMask:r.methodMask&MethodPool.FOREGROUND;
        if(!trigger(r.packageName,r.daily,r.chance,icon?"icon":"foreground",random))return PASS;
        int old=budgets.savedMask(r,now);
        // Only an admitted gate creates this budget. Recents resumes its remainder, even
        // though it cannot reproduce the desktop wait or authorize a new gate opening.
        if(!icon && old>0 && (old&EffectPlan.GATE)!=0){lastSource="retry";return lastMask=old;}
        if(old>0 && (EffectPlan.methods(old)&~eligible)==0 && EffectPlan.legal(old,icon)){
            lastSource="retry";return lastMask=old;
        }
        int plan=!icon && eligible==0?EffectPlan.HOME:EffectPlan.draw(r.sharedBasket,icon,r.combo,random);
        if(plan>0)draws++;
        return lastMask=plan;
    }
    int episodeIcon(Rule r,long now,Random random){
        clearVisit();lastIcon=r.packageName;nextIcon=now+FeedbackBasket.REPEAT_MS;
        return chooseEpisode(r,true,now,random);
    }
    void launchingEpisode(Rule r,int picked,long now,boolean pulsed){
        clearVisit();pkg=r.packageName;mask=picked;daily=r.daily;visitPolicy=r;
        ownIcon=true;entryPulse=pulsed;expectUntil=now+LAUNCH_WINDOW;
    }
    boolean fromIcon(){return ownIcon;}
    boolean takePulse(){if(entryPulse)return false;entryPulse=true;return true;}
    void cancelAppTerminal(){
        if(visitPolicy!=null && visitPolicy.byEffects() && mask>0 && (mask&EffectPlan.CONTENT)!=0)
            mask&=~EffectPlan.TERMINALS;
        ownIcon=false;
    }
    int foregroundMask(Rule rule,int w,int h,long now,Random random) {
        if(resumeUntil!=0 && now>=resumeUntil)clearVisit();
        resumeUntil=0;
        if(rule.byEffects()){
            if(mask!=PASS)pending(now);
            if(!rule.packageName.equals(pkg)||visitPolicy==null||!ForegroundVisit.samePolicy(rule,visitPolicy)){
                clearVisit();
                int picked=chooseEpisode(rule,false,now,random);if(picked==NONE)return NONE;
                mask=picked;pkg=rule.packageName;daily=rule.daily;visitPolicy=rule;
            }
            expectUntil=0;width=w;height=h;return mask;
        }
        if(rule.sharedRest()){
            // Notifications, shortcuts and recent tasks have no icon to intercept.
            // Never open the target just to reproduce a selected effect in a hard block.
            int immediate=MethodPool.bit(Rule.EXIT_NOW);
            if(mask!=PASS)pending(now);
            if(!rule.packageName.equals(pkg)||visitPolicy==null||!ForegroundVisit.samePolicy(rule,visitPolicy)){
                clearVisit();pkg=rule.packageName;daily=rule.daily;
                if(!trigger(pkg,daily,rule.chance,"foreground",random))mask=PASS;
                else {mask=lastMask=immediate;draws++;}
            }else if(mask!=PASS){mask=immediate;
            }
            expectUntil=0;visitPolicy=rule;width=w;height=h;return mask;
        }
        if(mask!=PASS)pending(now);
        boolean inherited=rule.packageName.equals(pkg) && rule.daily==daily
                && (visitPolicy==null || ForegroundVisit.samePolicy(rule,visitPolicy))
                && (fits(mask,rule.methodMask,rule.combo) || mask==PASS && rule.daily);
        if (!inherited) {
            clearVisit();
            int picked=draw(rule.packageName,rule.methodMask & MethodPool.FOREGROUND,rule.daily,rule.combo,rule.chance,"foreground",now,random,rule);
            if (picked==NONE) return NONE;
            mask=picked; pkg=rule.packageName; daily=rule.daily;
        }
        expectUntil=0; visitPolicy=rule; width=w; height=h;
        return mask;
    }
    boolean reconcile(Map<String,NativeTarget> policies) {
        if(visitPolicy!=null) {
            NativeTarget p=policies.get(pkg);
            if(p==null || !ForegroundVisit.samePolicy(p.policy,visitPolicy)){clearVisit();return true;}
        }
        return false;
    }
    boolean verifiedPackage(String target) { return expectUntil==0 && pkg!=null && pkg.equals(target); }
    boolean sameExtent(String target,int w,int h) {
        return verifiedPackage(target) && width==w && height==h;
    }
}
