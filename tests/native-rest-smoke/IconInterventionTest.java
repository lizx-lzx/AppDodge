package dev.local.appdodge;

import java.util.Random;

public final class IconInterventionTest {
    private static void check(boolean ok,String why) { if(!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        for(int i=0;i<4;i++) check(!Rule.iconAction(i),"old exit numbering");
        for(int i=4;i<=7;i++){
            check(Rule.iconAction(i) && Rule.validChoices(i,0) && !Rule.validChoices(i,FeedbackBasket.NOTICE),"independent action, no basket");
            Rule r=new Rule("target","示例",360,370,false,i,0,i==Rule.EXIT_ALTERNATIVE ? "other" : "",false);
            check(!r.returnHome && NativeTarget.from(r).interceptIcon,"only armed at the original icon");
            try { new Rule("target","示例",360,370,false,i,0,"",true); throw new AssertionError("no curtain outside delayed"); }
            catch(IllegalArgumentException expected) {}
        }
        IconIntervention s=new IconIntervention(); Random r=new Random(9);
        s.start(Rule.EXIT_WAIT_OPEN,100,r);
        for(long t:new long[]{100,101,4000,10099}) check(s.phase(t)==IconIntervention.WAIT,"no early open");
        check(s.phase(10100)==IconIntervention.READY && s.phase(70099)==IconIntervention.READY
                && s.phase(70100)==IconIntervention.EXPIRED,"10 second wait then 60 second affirmative window");
        s.cancel(); check(s.phase(10101)==IconIntervention.IDLE,"no residual authorization");
        int[] tickets=new int[6];
        for(int n=0;n<128;n++) {
            s.start(Rule.EXIT_LOCK_DELAY,100,r); long due=s.due;
            check(due>=5100 && due<=10100 && (due-100)%1000==0,"5..10 inclusive seconds");
            tickets[(int)(due-5100)/1000]++;
            check(s.phase(due-1)==IconIntervention.WAIT && s.phase(due)==IconIntervention.LOCK_DUE,"not early");
            check(s.phase(due+1000)==IconIntervention.EXPIRED,"stale callback must not unexpectedly lock after waking");
            s.cancel(); check(s.phase(due+1)==IconIntervention.IDLE,"leaving cancels old lock");
        }
        for(int n:tickets) check(n>0,"whole range reachable");
        s.start(Rule.EXIT_ALTERNATIVE,0,r);
        check(s.phase(29999)==IconIntervention.OFFER && s.phase(30000)==IconIntervention.EXPIRED,"no automatic alternative open");
        System.out.println("IconInterventionTest passed: no basket, single intentional open, offer, lock delay and cancellations");
    }
}
