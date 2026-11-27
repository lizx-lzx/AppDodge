package dev.local.appdodge;

import java.util.*;
public class EffectPlanTest {
    static void ok(boolean b,String why){if(!b)throw new AssertionError(why);}
    static int b(int mode){return MethodPool.bit(mode);}
    static Rule make(SharedBasket basket,int percent,boolean rc,boolean dc,boolean rf,boolean df){
        return Rule.shared("video","短视频",RestPeriods.single(100,200),basket,percent,rc,dc,rf,df);
    }
    public static void main(String[] argv){
        Random rng=new Random(99);int full=MethodPool.allDaily(Rule.EXIT_SHORT);
        SharedBasket everything=new SharedBasket(full,FeedbackBasket.ALL,"reader",true);
        int seen=EffectPlan.participantMask(everything);
        ok(EffectPlan.methods(seen)==full && EffectPlan.replies(seen)==FeedbackBasket.ALL,"all twelve methods and five receipts actually have a partner");
        for(boolean icon:new boolean[]{true,false})for(int n=2;n<=3;n++){
            List<Integer> all=EffectPlan.choices(everything,icon,n);Set<Integer> unique=new HashSet<>(all);
            ok(unique.size()==all.size(),"no duplicated combinations");
            for(int plan:all){ok(Integer.bitCount(plan)==n && EffectPlan.legal(plan,icon),"feasible stage count");
                ok(Integer.bitCount(EffectPlan.methods(plan)&~MethodPool.EXPERIENCES)<=1,"mutually exclusive outcome");
                if(!icon)ok((plan&EffectPlan.TERMINALS)==0,"a notification cannot lock or redirect");}
        }
        ok(!EffectPlan.legal(EffectPlan.LOCK|EffectPlan.REDIRECT,true),"never redirect and lock in the same draw");
        ok(!EffectPlan.legal(b(Rule.EXIT_LOAD)|EffectPlan.post(FeedbackBasket.DURATION),true),"no duration without clock");
        SharedBasket diversion=new SharedBasket(b(Rule.EXIT_NARROW)|EffectPlan.REDIRECT,0,"reader",false);
        Rule schedule=make(diversion,100,true,true,true,true),r=schedule.enforcedAt(201);
        ok(schedule.enforcedAt(101).sharedRest() && schedule.enforcedAt(101).chance==100,
                "even an imported content-plus-redirect rest rule must never launch its target");
        MethodEntry e=new MethodEntry(new ForegroundVisit());
        int mask=e.episodeIcon(r,400,rng);ok(mask==diversion.methods,"actual cross-stage pair, not old five");
        e.launchingEpisode(r,mask,400,false);
        ok(e.foregroundMask(r,400,900,500,rng)==mask && e.fromIcon(),"same icon authorization inherited once");
        e.cancelAppTerminal();ok(e.foregroundMask(r,400,900,550,rng)==b(Rule.EXIT_NARROW)&&!e.fromIcon(),"occlusion cancels deferred redirect");
        e.clearVisit();ok(e.episodeIcon(r,8900,rng)==mask,"later deliberate icon attempt gets a valid pair");
        e.reset();ok(e.foregroundMask(r,400,900,600,rng)==b(Rule.EXIT_NARROW)&&!e.fromIcon(),"external draw cannot redirect");
        SharedBasket withPulse=new SharedBasket(b(Rule.EXIT_MUTE),FeedbackBasket.HAPTIC,"",false);
        Rule resting=make(withPulse,0,true,false,true,true);
        ok(resting.enforcedAt(250)==null,"0% daytime still allows");
        Rule enforcedRest=resting.enforcedAt(140);
        ok(enforcedRest.sharedRest() && !enforcedRest.byEffects(),"0.18 soft rest is read as a hard block");
        ok(new MethodEntry(new ForegroundVisit()).icon(enforcedRest,1,rng)==Rule.EXIT_NOW,
                "content-only basket during rest cannot open the target");
        Rule activeDay=make(withPulse,100,false,true,false,true).enforcedAt(201);
        MethodEntry pulses=new MethodEntry(new ForegroundVisit());int m=pulses.episodeIcon(activeDay,1,rng);
        ok(m==(b(Rule.EXIT_MUTE)|EffectPlan.post(FeedbackBasket.HAPTIC)),"daytime hit can present a selected pair");
        pulses.launchingEpisode(activeDay,m,1,true);
        ok(pulses.foregroundMask(activeDay,100,220,300,rng)==m&&!pulses.takePulse(),"no second haptic during handoff");
        Rule hard=make(withPulse,37,false,false,false,false);ok(hard.enforcedAt(140).sharedRest()&&hard.enforcedAt(300).sharedRest(),"saved harder daytime rule remains hard");
        Rule day=hard.enforcedAt(300);int passed=0;
        for(int i=0;i<100;i++){
            final int value=i;
            Random fixed=new Random(){@Override public int nextInt(int bound){return value%bound;}};
            MethodEntry entry=new MethodEntry(new ForegroundVisit());
            int which=entry.icon(day,1,fixed);
            if(which==MethodEntry.PASS){
                passed++;entry.launching(day,which,1);
                ok(entry.foregroundMask(day,50,100,3,fixed)==MethodEntry.PASS,"hard day missed probability stays passed");
            }else ok(which==Rule.EXIT_NOW,"hard on hit");
        }
        ok(passed==63,"exact 37% even for hard day");
        SharedBasket timed=new SharedBasket(b(Rule.EXIT_SHORT),FeedbackBasket.DURATION,"",false);
        Rule timedRule=make(timed,100,true,true,true,true).enforcedAt(210);
        ForegroundVisit visit=new ForegroundVisit();MethodEntry entry=new MethodEntry(visit);
        int ticket=entry.foregroundMask(timedRule,250,500,1,rng);
        ok(ticket==(b(Rule.EXIT_SHORT)|EffectPlan.post(FeedbackBasket.DURATION)),"external eligible timer + actual duration");
        visit.enter(timedRule,ticket,12,1,1,rng);visit.leave(1130,1130);entry.clearVisit();
        ok(entry.foregroundMask(timedRule,250,500,1600,rng)==ticket,"retain entire timed plan within grace");
        ok(!visit.enter(timedRule,ticket,22,1600,1600,rng),"resume original sampled time, no new budget");
        ok(timedRule.reschedule(RestPeriods.single(300,400)).modernPolicy,"time edit does not revert strategies");
        Set<Integer> sampled=new HashSet<>();boolean repeated=false;int prev=0;
        for(int i=0;i<25000;i++){int v=EffectPlan.draw(everything,true,true,rng);sampled.add(v);if(v==prev)repeated=true;prev=v;}
        int total=EffectPlan.choices(everything,true,2).size()+EffectPlan.choices(everything,true,3).size();
        ok(sampled.size()==total && repeated,"all feasible sets reached, independent repeats permitted");
        System.out.println("EffectPlan: "+total+" feasible original-icon pairs/triples across all 12+5; gating, phase handoff, cancellation and timer retention passed.");
    }
}
