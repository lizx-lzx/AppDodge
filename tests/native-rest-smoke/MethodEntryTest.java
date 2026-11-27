package dev.local.appdodge;

import java.util.Random;

public final class MethodEntryTest {
    static void check(boolean ok,String why){ if(!ok) throw new AssertionError(why); }
    static Rule rule(int mask) {
        return Rule.pool("target","计算器",1,2,false,mask,MethodPool.allowedFeedback(mask)&FeedbackBasket.NOTICE,"",false);
    }
    static int foreground(MethodEntry e,Rule rule,int w,int h,long at,Random random) {
        return MethodEntry.singleMode(e.foregroundMask(rule,w,h,at,random));
    }
    static void dailyChecks(){
        final int[] calls={0};
        Random miss=new Random(){public int nextInt(int n){calls[0]++;return n-1;}};
        Rule base=Rule.defaultRest("target","目标",100,200,false);
        Rule saved=base.withDaily(25,MethodPool.bit(Rule.EXIT_NARROW)|MethodPool.bit(Rule.EXIT_NOW),FeedbackBasket.NOTICE,"",false);
        Rule day=saved.during(20), rest=saved.during(100);
        MethodEntry e=new MethodEntry(new ForegroundVisit());
        check(e.icon(day,0,miss)==MethodEntry.PASS,"daily miss permits exactly this open");
        e.launching("target",MethodEntry.PASS,true,1); e.home(100);
        for(int i=0;i<25;i++)check(foreground(e,day,100,200,120+i,miss)==MethodEntry.PASS,"no reroll in foreground");
        check(calls[0]==1 && e.draws==0 && e.passes==1,"one probability roll");
        check(foreground(e,rest,100,200,200,miss)==Rule.EXIT_NOW,"daily permission cannot pass rest boundary");
        e.home(500); e.reset();
        Random hit=new Random(){public int nextInt(int n){return n==100?0:n-1;}};
        check(foreground(e,day,100,200,1000,hit)==Rule.EXIT_NARROW,"external foreground draws an eligible experience");
        long quiet=2*MethodEntry.RETRY_WINDOW; // target stayed open without emitting events
        e.home(quiet+2000);
        check(e.icon(day,quiet+3000,miss)==MethodEntry.PASS && e.dailyDecisions==3,"a quick genuine reopen gets its own probability decision");
        e.home(quiet+4000);
        check(e.icon(day,quiet+MethodEntry.RETRY_WINDOW+3000,miss)==MethodEntry.PASS,"later independent attempt uses probability again");
    }
    static void comboChecks() {
        int mute=MethodPool.bit(Rule.EXIT_MUTE), narrow=MethodPool.bit(Rule.EXIT_NARROW);
        int all=MethodPool.EXPERIENCES|MethodPool.bit(Rule.EXIT_SHORT);
        Rule base=Rule.defaultRest("target","计算器",100,200,false);
        Rule saved=base.withDaily(25,all,FeedbackBasket.NOTICE,"",false,true);
        Rule day=saved.during(20), rest=saved.during(100);
        Random miss=new Random(){public int nextInt(int n){return n-1;}};
        ForegroundVisit budget=new ForegroundVisit(); MethodEntry entry=new MethodEntry(budget);
        check(entry.foregroundMask(day,100,200,1000,miss)==MethodEntry.PASS,"a missed trigger skips the entire combo");
        check(entry.foregroundMask(day,100,200,1001,miss)==MethodEntry.PASS && entry.passes==1,"miss is one decision");
        entry.home(1002);
        Random seeded=new Random(204);
        int selected=MethodEntry.PASS;
        for (int i=0;i<100 && selected==MethodEntry.PASS;i++) {
            entry.home(2000+i*2);
            selected=entry.foregroundMask(day,100,200,2001+i*2,seeded);
        }
        check(selected>0 && (selected&~all)==0 && Integer.bitCount(selected)>=2,"real multi-selection on hit");
        check(entry.foregroundMask(day,100,200,2300,miss)==selected && entry.draws==1,"activity change doesn't redraw members");
        if((selected&MethodPool.TIMED)!=0){budget.enter(day,selected,1,2300,2300,seeded);budget.leave(3000,3000);}
        entry.home(3000);
        check(entry.foregroundMask(day,100,200,3100,miss)==MethodEntry.PASS && entry.draws==1,"independent miss is never overridden by the previous combination");
        check(entry.foregroundMask(rest,100,200,3200,miss)==MethodPool.bit(Rule.EXIT_NOW),"rest overrides a live combo");
        check(MethodEntry.sample(mute|narrow,true,seeded)==(mute|narrow),"two chosen means both actually selected");
        java.util.Set<Integer> possibilities=new java.util.HashSet<>();
        int triples=0;
        for(int i=0;i<8000;i++){
            int group=MethodEntry.sample(all,true,seeded), n=Integer.bitCount(group);
            check((n==2 || n==3) && (group&~all)==0,"no duplicates, no hidden sixth member");
            if(n==3)triples++;
            possibilities.add(group);
        }
        check(possibilities.size()==20 && triples>3600 && triples<4400,"all ten pairs and ten triples are reachable; both sizes occur");
    }
    static void realDailyVisits(){
        int pair=MethodPool.bit(Rule.EXIT_NARROW)|MethodPool.bit(Rule.EXIT_MUTE);
        Rule day=Rule.shared("target","视频",RestPeriods.single(100,200),new SharedBasket(pair,0,"",false),
                60,false,true,false,true).enforcedAt(201);
        // 0.20.1 "retry" returned 6144 on all later entries after just one success. Sweep all
        // possible percentage tickets on the SAME entry object, in less than its 5-min budget.
        ForegroundVisit v=new ForegroundVisit();MethodEntry e=new MethodEntry(v);
        Random noRoll=new Random(){public int nextInt(int n){throw new AssertionError("duplicate decision in one visit");}};
        for(int i=0;i<100;i++){
            final int roll=(59+i)%100; final int[] rateCalls={0};
            Random r=new Random(){public int nextInt(int n){if(n==100){rateCalls[0]++;return roll;}return 0;}};
            long now=1000+1500L*i;boolean icon=i%2==0;
            int p=icon?e.episodeIcon(day,now,r):e.foregroundMask(day,400,900,now,r);
            check(p==(roll<60?6144:MethodEntry.PASS) && rateCalls[0]==1,"one 60% gate per real open: "+i);
            if(icon){
                e.launchingEpisode(day,p,now+1,false);
                e.interrupted(true,now+30); // outgoing launcher / incoming app briefly coexist
                check(e.foregroundMask(day,400,900,now+100,noRoll)==p,"original icon and foreground share ticket");
            }
            e.interrupted(false,now+140); // keyboard or notification shade
            e.interrupted(true,now+180);  // App Activity transition
            check(e.foregroundMask(day,900,400,now+250,noRoll)==p,"unconfirmed surface/rotation cannot reroll");
            e.home(now+900); // user/Home request really returned to the launcher
        }
        check(e.dailyDecisions==100&&e.dailyHits==60&&e.passes==40&&e.draws==60&&v.draws==0,
                "observed 100 decisions, 60 hit, 40 pass, no invented timer");
    }
    public static void main(String[] args) {
        Random random=new Random(104);
        Rule mixed=rule(MethodPool.bit(Rule.EXIT_WAIT_OPEN)|MethodPool.bit(Rule.EXIT_SHORT)|MethodPool.bit(Rule.EXIT_NOW));
        MethodEntry entry=new MethodEntry(new ForegroundVisit()); long at=100;
        for(int mode:new int[]{Rule.EXIT_WAIT_OPEN,Rule.EXIT_SHORT,Rule.EXIT_NOW}) {
            while(entry.icon("target",mixed.methodMask,at,random)!=mode) at+=1000;
            check(!entry.mayTap("target",at+400),"ignore double tap, no new draw");
            int before=entry.draws;
            entry.launching("target",mode,at);
            entry.home(at+100); entry.interrupted(true,at+200);
            check(!entry.mayTap("target",at+2000),"suppress a second launch before window arrives");
            for(int i=0;i<30;i++) check(foreground(entry,mixed,1080,2400,at+2001+i,random)==mode,"actual app inherits icon choice");
            check(entry.sameExtent("target",1080,2400) && !entry.sameExtent("other",1080,2400) && !entry.sameExtent(null,1080,2400) && !entry.sameExtent("target",540,2400) && !entry.sameExtent("target",1080,1200),"track actual owner across Activity change");
            check(entry.draws==before,"one draw for whole visit");
            entry.home(at+9000);
            for(int i=0;i<30;i++) {
                int observed=foreground(entry,mixed,1080,2400,at+10000+i,random);
                check(observed==Rule.EXIT_SHORT || observed==Rule.EXIT_NOW,"only checked foreground choices");
            }
            check(entry.draws==before+1 && entry.lastSource.equals("foreground"),"one fresh draw on external entry");
            entry.home(at+11000);
            check(entry.mayTap("target",at+11001),"new icon visit");
            at+=12000;
        }
        Rule iconOnly=rule(MethodPool.bit(Rule.EXIT_WAIT_OPEN));
        int before=entry.draws;
        check(foreground(entry,iconOnly,1080,2400,at,random)==-1 && entry.draws==before,"external entry cannot invent a method");
        entry.launching("target",Rule.EXIT_WAIT_OPEN,at);
        check(foreground(entry,mixed,1080,2400,at+MethodEntry.LAUNCH_WINDOW,random)!=Rule.EXIT_WAIT_OPEN,"unobserved launch cannot exempt unrelated later access");
        entry.launching("target",Rule.EXIT_WAIT_OPEN,at); entry.interrupted(false,at+100);
        check(foreground(entry,mixed,1080,2400,at+101,random)!=Rule.EXIT_WAIT_OPEN,"confirmed interruption revokes pending launch");
        dailyChecks(); comboChecks(); realDailyVisits();
        System.out.println("MethodEntryTest passed: icon-to-window handoff, WAIT consent, repeat taps, external eligibility, interruptions, expiry");
    }
}
