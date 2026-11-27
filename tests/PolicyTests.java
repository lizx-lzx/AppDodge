package dev.local.appdodge;

public final class PolicyTests {
    static int assertions;
    static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        for(int[] range:new int[][]{{480,600},{1380,60},{0,1439},{1439,0}}){
            Rule allow=new Rule("app","App",range[0],range[1],true);
            Rule block=new Rule("app","App",range[0],range[1],false);
            for(int m=0;m<1440;m++){
                // Independent reference: the distance forward from start is shorter than the window.
                boolean expected=(m-range[0]+1440)%1440 < (range[1]-range[0]+1440)%1440;
                check(allow.allows(m)==expected,"allowed window at "+m);
                check(block.allows(m)!=expected,"blocked window at "+m);
            }
            check(allow.allows(range[0]),"start inclusive");check(!allow.allows(range[1]),"end exclusive");
        }
        try{new Rule("app","App",600,600,true);throw new AssertionError("equal times accepted");}catch(IllegalArgumentException expected){assertions++;}
        Rule original = new Rule("sample","示例",300,390,false);
        Rule returned = new Rule("sample","示例",300,390,false,true);
        check(!original.returnHome, "default is unchanged");
        check(returned.returnHome, "opt-in saved with its rule");
        for(int m=0;m<1440;m++)check(original.allows(m)==returned.allows(m),"method does not change schedule");
        methodPools(); dailyLayers(); comboPlans(); enforcedVisits(); stopViews();
        System.out.println("PASS: "+assertions+" checks (daily schedules and method-pool selections)");
    }
    static void stopViews(){
        Rule legacy=Rule.defaultRest("x","游戏",100,200,false).withDaily(60,MethodPool.bit(Rule.EXIT_MUTE),0,"",false)
                .withStandby(MethodPool.bit(Rule.EXIT_MUTE)|MethodPool.bit(Rule.EXIT_STALL),0,"",false);
        for(Rule r:new Rule[]{legacy,Rule.shared("x","游戏",RestPeriods.single(100,200),new SharedBasket(MethodPool.bit(Rule.EXIT_MUTE),0,"",false),60,false,false,false,true)}){
            Rule enabled=r.withStopAfterReturn(true), newTime=enabled.reschedule(RestPeriods.single(130,180));
            check(!r.stopAfterReturn&&enabled.stopAfterReturn,"per-app immutable opt-in, old default off");
            for(Rule view:new Rule[]{newTime.restPolicy(),newTime.during(150),newTime.enforcedAt(150),newTime.enforcedAt(300),newTime.enforcedAt(300).forMode(Rule.EXIT_MUTE)})
                check(view.stopAfterReturn,"policy projection/schedule does not drop the user's choice");
            check(!enabled.withStopAfterReturn(false).enforcedAt(300).stopAfterReturn,"opt out clears all views");
        }
        Rule on=legacy.withStopAfterReturn(true);
        check(on.withDaily(0,0,0,"",false).standby.stopAfterReturn&&on.withDaily(0,0,0,"",false).preferredDaily().stopAfterReturn,"retain opt-in for dormant choices");
        check(on.withRest(Rule.defaultRest("x","游戏",90,100,false)).enforcedAt(95).stopAfterReturn,"legacy edits preserve consent");
    }
    static void enforcedVisits(){
        int wait=EffectPlan.GATE,now=EffectPlan.HOME,mute=MethodPool.bit(Rule.EXIT_MUTE);
        RestPeriods periods=RestPeriods.single(100,200);
        Rule saved=Rule.shared("target","游戏",periods,new SharedBasket(wait,FeedbackBasket.HAPTIC,"",false),
                45,true,true,true,true);
        Rule rest=saved.enforcedAt(100), day=saved.enforcedAt(200);
        check(rest.sharedRest() && !rest.byEffects() && rest.hardCombo() && rest.returnHome && rest.methodMask==now,
                "an old rest-by-effects gate now blocks; the same saved basket still drives both times");
        check(day.byEffects()&&day.daily&&day.chance==45&&day.methodMask==wait,"daily gate unchanged");
        Rule oldOnly=Rule.pool("x","x",100,200,false,wait,0,"",false);
        check(oldOnly.enforcedAt(199).sharedRest()&&oldOnly.enforcedAt(199).returnHome
                &&oldOnly.enforcedAt(199).exitMode==Rule.EXIT_NOW,"legacy icon-only wait is no hole during rest");
        check(oldOnly.enforcedAt(200)==null,"an entirely unconfigured allowed period is still allowed");
        Rule rare=Rule.shared("x","x",periods,new SharedBasket(mute,0,"",false),0,true,false,false,true);
        check(rare.enforcedAt(110).sharedRest() &&rare.enforcedAt(110).hardCombo()&&rare.enforcedAt(200)==null,
                "zero desktop-compatible receipts still blocks; zero percent outside still passes");
        Rule hardDay=Rule.shared("x","x",periods,new SharedBasket(mute,0,"",false),45,false,false,false,false);
        check(hardDay.enforcedAt(300).sharedRest(),"explicit hard outside-rest rule was not loosened");

        java.util.Random never=new java.util.Random(){public int nextInt(int n){throw new AssertionError("unexpected new draw");}};
        java.util.Random hit=new java.util.Random(){public int nextInt(int n){return 0;}};
        java.util.Random miss=new java.util.Random(){public int nextInt(int n){return n-1;}};
        for(int sample=1;sample<=2;sample++){
            final int value=sample;
            java.util.Random duration=new java.util.Random(){public int nextInt(int n){check(n==2,"gate window 1–2s");return value-1;}};
            ForegroundVisit budget=new ForegroundVisit();MethodEntry entry=new MethodEntry(budget);
            int ticket=entry.episodeIcon(day,0,hit);
            check(EffectPlan.methods(ticket)==wait && EffectPlan.replies(ticket)==FeedbackBasket.HAPTIC,"actual gate plan including optional pulse");
            IconIntervention gate=new IconIntervention();
            gate.start(Rule.EXIT_WAIT_OPEN,ticket,0,never);
            check(gate.phase(9999)==IconIntervention.WAIT&&gate.phase(10000)==IconIntervention.READY
                    &&!budget.active()&&budget.draws==0,"no allowance sampled or spent while waiting on Home");
            entry.launchingEpisode(day,gate.episode,10000,true);gate.cancel();
            check(gate.episode==0&&entry.foregroundMask(day,500,800,10100,never)==ticket&&!entry.takePulse(),"handoff is not another probability or haptic draw");
            check(budget.enter(day,ticket,12,10100,10100,duration)&&budget.remaining(10100)==sample*1000,"confirmed foreground starts original allowance");
            budget.leave(10400,10400);entry.clearVisit();
            check(entry.foregroundMask(day,500,800,15000,hit)==ticket,"recents preserves even an icon-only timed admission");
            budget.enter(day,ticket,14,15000,15000,never);
            check(budget.remaining(15000)==sample*1000-300,"background did not run down or reset the budget");
            long end=15000+budget.remaining(15000);
            budget.leave(end,end);entry.clearVisit();
            check(entry.foregroundMask(day,500,800,end+900,hit)==ticket,"completed gate ticket is not rerolled on re-entry");
            budget.enter(day,ticket,15,end+900,end+900,never);
            check(budget.rejoinedExhausted&&budget.remaining(end+900)==0&&budget.draws==1&&entry.draws==1,"no second allowance after exhaustion");
        }
        MethodEntry external=new MethodEntry(new ForegroundVisit());
        check(external.foregroundMask(day,100,200,0,hit)==now,"fresh external entry must not bypass an icon-only gate on a hit");
        external.home(10);
        check(external.foregroundMask(day,100,200,50,miss)==MethodEntry.PASS&&external.passes==1,"only a probability miss permits normal external use");
        check(EffectPlan.opensFromIcon(now|EffectPlan.post(FeedbackBasket.NOTICE))&&
              !EffectPlan.opensFromIcon(EffectPlan.BLOCK|EffectPlan.post(FeedbackBasket.NOTICE)),"open-then-return is distinct from blocking the icon");
        check(EffectPlan.afterPresentation(0,true,true,true,false)==0,"one content intervention has time to be perceived");
        check(EffectPlan.afterPresentation(0,true,true,true,true)==now,"solo mute, stall, narrow and loading finally exit");
        check(EffectPlan.afterPresentation(EffectPlan.REDIRECT,true,true,true,true)==EffectPlan.REDIRECT,"verified terminal still runs after content");
        check(EffectPlan.afterPresentation(EffectPlan.LOCK,true,false,true,false)==now,"visible target with a non-focusing occluder cannot lock or grant unlimited play");
        check(EffectPlan.afterPresentation(EffectPlan.REDIRECT,false,false,false,true)==0,"mostly hidden target never acts on another surface");
        check(EffectPlan.afterPresentation(0,true,true,false,false)==now,"an unavailable effect is not unlimited permission");
    }
    static void comboPlans(){
        int two=(1<<Rule.EXIT_MUTE)|(1<<Rule.EXIT_NARROW);
        int five=MethodPool.EXPERIENCES|(1<<Rule.EXIT_LONG);
        check(MethodPool.validCombo(five) && Integer.bitCount(five)==5,"all five with one time range");
        for(int invalid:new int[]{0,1<<Rule.EXIT_LOAD,MethodPool.TIMED|two,1<<Rule.EXIT_LOCK_NOW|two,MethodPool.bit(Rule.EXIT_REDIRECT)|two})
            check(!MethodPool.validCombo(invalid),"never save an impossible multi-effect selection");
        Rule rest=Rule.pool("pkg","App",120,180,true,1<<Rule.EXIT_WAIT_OPEN,0,"",false);
        Rule single=rest.withDaily(100,1<<Rule.EXIT_REDIRECT,0,"reader",false);
        Rule set=single.withDaily(50,two,0,"",false,true);
        check(!single.combo && set.combo && set.during(150).combo && !set.during(180).combo,
                "combination configured separately and never projected onto rest");
        check(set.daytime.chance==50 && set.daytime.methodMask==two && set.modeSettings(false).alternatePackage.equals("reader")
                && set.modeSettings(true)==set.daytime,"switching retains the last single selection outside the draw");
        Rule back=set.withDaily(25,1<<Rule.EXIT_REDIRECT,0,"reader",false);
        check(!back.combo && back.during(150).exitMode==Rule.EXIT_REDIRECT && back.modeSettings(true).methodMask==two,
                "switching back retains the last combination and old API still draws one");
        check(!set.withDaily(0,0,0,"",false).combo,"disabling daily cannot activate a combination");
        try{rest.withDaily(75,1<<Rule.EXIT_MUTE,0,"",false,true);throw new AssertionError("a single mute cannot count as a pair");}
        catch(IllegalArgumentException expected){assertions++;}
        try{rest.withDaily(0,0,0,"",false,true);throw new AssertionError("disabled daily cannot draw several");}
        catch(IllegalArgumentException expected){assertions++;}
    }
    static void methodPools(){
        int both=MethodPool.TIMED;
        check(MethodPool.draw(0,new java.util.Random(17))==-1,"no eligible methods means no intervention");
        check(!MethodPool.valid(both)&&!MethodPool.valid(0)&&!MethodPool.valid(1<<20),"reject conflicting ranges, zero and unknown method bits");
        check(Integer.bitCount(MethodPool.all(Rule.EXIT_SHORT))==8 && Integer.bitCount(MethodPool.all(Rule.EXIT_LONG))==8,"one ticket for each of the eight UI methods");
        for(int methods=1;methods<=511;methods++){
            if(!MethodPool.valid(methods))continue;
            final int count=Integer.bitCount(methods);
            int observed=0;
            for(int i=0;i<count;i++){
                final int ticket=i;
                int m=MethodPool.draw(methods,new java.util.Random(){
                    @Override public int nextInt(int bound){check(bound==count,"unbiased range");return ticket;}
                });
                check(((1<<m)&methods)!=0 && (observed&(1<<m))==0,"distinct in-range ticket for each selected mode"); observed|=1<<m;
            }
            check(observed==methods,"every selected mode reachable");
        }
        int all=MethodPool.all(Rule.EXIT_LONG);
        Rule r=Rule.pool("sample","App",0,60,false,all,FeedbackBasket.ALL,"alt",true);
        check(r.singleMode()==-1 && r.returnHome && !r.hasMode(Rule.EXIT_SHORT),"mixed rule is not a single primary method");
        check(r.forMode(Rule.EXIT_LONG).curtain && (r.forMode(Rule.EXIT_LONG).feedbackMask & FeedbackBasket.LOCK)==0,"timed outcome cannot arm a lock");
        check(!r.forMode(Rule.EXIT_WAIT_OPEN).curtain && !r.forMode(Rule.EXIT_WAIT_OPEN).returnHome && r.forMode(Rule.EXIT_WAIT_OPEN).feedbackMask==0,"a chosen icon action cannot arm foreground/feedback effects");
        check(r.forMode(Rule.EXIT_REDIRECT).alternatePackage.equals("alt") && r.forMode(Rule.EXIT_ALTERNATIVE).alternatePackage.equals("alt")
                && r.forMode(Rule.EXIT_LOCK_NOW).alternatePackage.isEmpty(),"only the two alternate-app choices may redirect");
        check(!MethodPool.validFeedback(all,FeedbackBasket.LOCK) && !MethodPool.validFeedback(all,FeedbackBasket.DURATION)
                && !MethodPool.validFeedback(all,FeedbackBasket.LOCK|FeedbackBasket.DURATION)
                && MethodPool.validFeedback(all,FeedbackBasket.HAPTIC|FeedbackBasket.LOCK|FeedbackBasket.DURATION),
                "immediate foreground returns need a chosen applicable effect, never an unselected fallback");
        check(MethodPool.validFeedback(MethodPool.bit(Rule.EXIT_ICON)|MethodPool.bit(Rule.EXIT_LONG),
                FeedbackBasket.LOCK|FeedbackBasket.DURATION),"icon and timed modes may have disjoint but usable feedback");
        check((r.forMode(Rule.EXIT_NOW).feedbackMask & FeedbackBasket.LOCK)==0,"mixed NOW uses only post-return effects");
        try{r.forMode(Rule.EXIT_SHORT);throw new AssertionError("unselected mode accepted");}catch(IllegalArgumentException expected){assertions++;}
        try{Rule.pool("p","app",0,60,false,all,FeedbackBasket.ALL,"",false);throw new AssertionError("missing alternate accepted");}catch(IllegalArgumentException expected){assertions++;}
        try{Rule.pool("p","app",0,60,false,MethodPool.bit(Rule.EXIT_LOCK_NOW),0,"",true);throw new AssertionError("curtain accepted for lock only");}catch(IllegalArgumentException expected){assertions++;}
    }
    static void dailyLayers(){
        int rest=MethodPool.bit(Rule.EXIT_WAIT_OPEN);
        Rule old=Rule.pool("pkg","App",1380,420,false,rest,0,"",false);
        check(old.dailyChance==0 && old.during(1380)==old && old.during(420)==null,"no interventions in formerly permitted period without opt-in");
        Rule defaultRest=Rule.defaultRest("pkg","App",1380,420,false);
        check(defaultRest.exitMode==Rule.EXIT_NOW && defaultRest.feedbackMask==FeedbackBasket.NOTICE,"historical immediate-rest policy remains notice-only");
        Rule fresh=Rule.newAppRest("pkg","App",1380,420,false);
        check(fresh.exitMode==Rule.EXIT_NOW && fresh.feedbackMask==(FeedbackBasket.HAPTIC|FeedbackBasket.COUNTDOWN)
                && fresh.dailyChance==0, "only newly created rules start with varied, non-locking feedback and no daytime intervention");
        int all=MethodPool.allDaily(Rule.EXIT_LONG);
        check(Integer.bitCount(all)==12 && Integer.bitCount(MethodPool.all(Rule.EXIT_LONG))==8,"separate 12-way and 8-way selection");
        Rule configured=old.withDaily(25,all,FeedbackBasket.ALL,"reader",true);
        for(int m=0;m<1440;m++) {
            Rule active=configured.during(m);
            if(old.allows(m))check(active.daily && active.chance==25 && active.methodMask==all,"normal time uses authorized daily pool only");
            else check(!active.daily && active.chance==100 && active.exitMode==Rule.EXIT_WAIT_OPEN && active==old,
                    "explicit rest stays unchanged at night, midnight and both boundaries");
        }
        for(int effect=Rule.EXIT_LOAD;effect<=Rule.EXIT_MUTE;effect++){
            Rule active=configured.during(480).forMode(effect);
            check(active.daily && active.chance==25 && active.returnHome && active.feedbackMask==0 && !active.curtain,
                    "new foreground effects keep daily consent and do not draw more feedback");
            try{Rule.pool("p","P",0,1,true,1<<effect,0,"",false);throw new AssertionError("daily-only method in rest");}
            catch(IllegalArgumentException expected){assertions++;}
        }
        Rule day=configured.during(480);
        check(day.forMode(Rule.EXIT_REDIRECT).alternatePackage.equals("reader") && day.forMode(Rule.EXIT_LONG).curtain,"daily-specific options");
        Rule off=configured.withDaily(0,0,0,"",false);
        check(off.during(1380).singleMode()==Rule.EXIT_WAIT_OPEN && off.during(420)==null,"daily toggle changes only permitted hours");
        check(off.preferredDaily().chance==25 && off.modeSettings(false).curtain && off.modeSettings(false).alternatePackage.equals("reader"),"off retains user choices without executing them");
        Rule changed=configured.withRest(defaultRest);
        Rule stronger=changed.withDaily(100,all,FeedbackBasket.ALL,"reader",true);
        check(changed.during(1380).exitMode==Rule.EXIT_NOW && stronger.withDaily(0,0,0,"",false).during(1380).exitMode==Rule.EXIT_NOW,"independent strict rest across probability changes");
        check(changed.during(480).methodMask==configured.during(480).methodMask && changed.during(480).chance==25,"rest editing does not change daytime");
        check(!MethodPool.validFeedback(all,FeedbackBasket.DURATION|FeedbackBasket.LOCK),"combined foreground effects still need a usable after-return feedback");
        for(int methods=1;methods<(1<<(Rule.EXIT_MUTE+1));methods++){
            if(!MethodPool.validDaily(methods))continue;
            int result=MethodPool.draw(methods&MethodPool.FOREGROUND,new java.util.Random(42));
            check(result<0?(methods&MethodPool.FOREGROUND)==0:((1<<result)&methods&MethodPool.FOREGROUND)!=0,
                    "external entry cannot select an ineligible effect");
        }
    }
}
