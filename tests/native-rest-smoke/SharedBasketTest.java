package dev.local.appdodge;

import java.util.Random;

/** Exercise actual entry selection, schedules, combinations and timed-policy identity without an Android runtime. */
public final class SharedBasketTest {
    private static int checks;
    private static final RestPeriods HOURS=RestPeriods.of(new int[][]{{1380,180},{480,600},{540,780}});
    private static void ok(boolean value,String msg){checks++;if(!value)throw new AssertionError(msg);}
    private static int bit(int mode){return MethodPool.bit(mode);}
    static Rule plan(SharedBasket b,int chance,boolean rest,boolean day){
        return Rule.shared("one","甲",HOURS,b,chance,rest,day);
    }
    public static void main(String[] ignored) {
        SharedBasket media=new SharedBasket(bit(Rule.EXIT_MUTE)|bit(Rule.EXIT_NARROW),0,"",false);
        Rule one=plan(media,0,false,false);
        ok(one.sharedBasket==media && one.during(900)==null && one.during(60)!=null,"0% retains basket without daytime activity");
        ok(one.during(60).forMode(Rule.EXIT_NOW).feedbackMask==0,"quiet fallback");
        ok(NativeTarget.from(one.during(60),60).notice.contains("03:00")
                &&NativeTarget.from(one.during(590),590).notice.contains("13:00"),"current union end, never first row's end");
        Rule both=plan(media,41,false,true);
        ok(both.during(1380).sharedBasket==both.during(900).sharedBasket,"one actual selection source");
        ok(both.during(0).returnHome && both.during(0).chance==100 && !both.during(0).combo
                && both.during(900).combo&&both.during(900).chance==41,"separate probabilities and counts");
        Random rand=new Random(43);
        for(int mode=0;mode<=Rule.EXIT_MUTE;mode++){
            int flag=bit(mode);String dest=(flag&MethodPool.ALTERNATIVES)!=0?"reader":"";
            SharedBasket b=new SharedBasket(flag,0,dest,false);
            Rule r=plan(b,100,false,false).during(570);
            ok(NativeTarget.from(r).interceptIcon && r.returnHome,"strict protects both entries "+mode);
            MethodEntry entries=new MethodEntry(new ForegroundVisit());
            int icon=entries.icon(r,1000,rand);
            ok((bit(icon)&SharedBasket.DIRECT)!=0,"strict never opens for a content effect "+mode);
            ok(entries.foregroundMask(r,300,600,1100,rand)==bit(Rule.EXIT_NOW),"external never escapes "+mode);
            ok(r.forMode(Rule.EXIT_NOW).singleMode()==Rule.EXIT_NOW,"even icon-only method can return "+mode);
        }
        int choose=bit(Rule.EXIT_LOCK_NOW)|bit(Rule.EXIT_SHORT)|media.methods|bit(Rule.EXIT_REDIRECT);
        SharedBasket shared=new SharedBasket(choose,FeedbackBasket.ALL,"reader",true);
        ok(shared.canRestCombo()&&shared.canDayCombo(),"both compatible subsets available");
        Rule all=plan(shared,37,true,true);
        ok(all.during(60).methodMask==bit(Rule.EXIT_NOW)&&all.during(60).restCombo,"strict composite never locks or redirects");
        ok(all.during(900).methodMask==(choose&MethodPool.COMBINABLE)&&all.daytime.curtain,"daily composite only compatible tickets");
        boolean sawTwo=false,sawThree=false;
        for(int i=0;i<100;i++){int mask=shared.drawRestResponses(rand,true);
            ok((mask&~shared.restTickets(true))==0,"no incompatible or unselected response");
            int n=Integer.bitCount(mask);if(n==2)sawTwo=true;if(n==3)sawThree=true;
        }
        ok(sawTwo&&sawThree,"actual random sizes for strict basket");
        Rule independent=plan(shared,37,true,false);
        ok(independent.restCombo&&!independent.during(900).combo&&independent.daytime.hasMode(Rule.EXIT_REDIRECT),"independent single draw retains terminal actions");
        SharedBasket detour=new SharedBasket(bit(Rule.EXIT_LOAD)|bit(Rule.EXIT_REDIRECT),0,"reader",false);
        ok(detour.canRestCombo()&&!detour.canDayCombo(),"genuine Home-only pairing is available even with no receipts");
        for(int i=0;i<20;i++){
            ok(detour.drawRestResponses(rand,true)==(SharedBasket.HOME_LOAD|SharedBasket.HOME_REDIRECT),"load then redirect both selected");
            ok(detour.drawRestResponses(rand,false)==SharedBasket.HOME_LOAD,"external entry cannot redirect on user's behalf");
        }
        ok(plan(detour,100,true,false).during(600).sharedBasket.alternate.equals("reader"),"strict sequence can resolve only saved alternative");
        Rule other=Rule.shared("two","乙",HOURS,media,100,false,true);
        ok(!other.sharedBasket.equals(all.sharedBasket)&&other.sharedBasket.equals(media)&&all.sharedBasket.equals(shared),"changing one basket cannot mutate another app’s rule");
        try{plan(media,37,true,false);throw new AssertionError("strict fake combination accepted");}catch(IllegalArgumentException expected){}
        try{plan(new SharedBasket(bit(Rule.EXIT_LOAD),0,"",false),37,false,true);throw new AssertionError("daily fake combination accepted");}catch(IllegalArgumentException expected){}
        try{plan(media,101,false,false);throw new AssertionError("out of range");}catch(IllegalArgumentException expected){}
        Rule legacy=Rule.defaultRest("old","旧",60,120,false).withDaily(25,bit(Rule.EXIT_LOAD),0,"",false);
        Rule edited=legacy.reschedule(HOURS);
        ok(edited.sharedBasket==null && edited.restPolicy().hasMode(Rule.EXIT_NOW)
                && edited.during(900).hasMode(Rule.EXIT_LOAD)&&edited.dailyChance==25,"legacy time-only edit keeps independent policies");
        RuleChangeText diff=RuleChangeText.compare(one,plan(new SharedBasket(bit(Rule.EXIT_LOAD),0,"",false),0,false,false),p->p);
        ok(diff.before.contains("强行消音")&&diff.after.contains("假装加载"),"cooldown compares hidden-at-0% selection, not derived fallbacks");
        ok(!ForegroundVisit.samePolicy(all.during(900),all.reschedule(RestPeriods.single(480,600)).during(900)),"old timer cannot survive rule coverage change");
        System.out.println("SharedBasket: "+checks+" checks (all strict entry types, union, saved-at-zero, subsets, cooldown)");
    }
}
