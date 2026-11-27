package dev.local.appdodge;

import java.util.Random;
public final class ForegroundVisitTest {
    private static void check(boolean ok,String hint) { if (!ok) throw new AssertionError(hint); }
    private static final class Edge extends Random {
        int draws; final boolean last;
        Edge(boolean last) { this.last=last; }
        @Override public int nextInt(int bound) { draws++; return last ? bound-1 : 0; }
    }
    private static Rule timed(String pkg,int mode){return new Rule(pkg,pkg,100,200,false,mode,FeedbackBasket.NOTICE);}
    private static void budgets(){
        Rule r=timed("a",Rule.EXIT_LONG), other=timed("b",Rule.EXIT_LONG);
        int pick=MethodPool.bit(Rule.EXIT_LONG);
        Edge edge=new Edge(false); ForegroundVisit v=new ForegroundVisit();
        v.enter(r,pick,1,10000,1000,edge); // samples the minimum: three minutes
        v.suspend(31000); // same-app panel; time while it covers the target is not spent
        check(v.remaining(59000)==150000 && !v.running(),"hidden panel pauses the running clock");
        v.enter(r,pick,3,70000,61000,edge);v.leave(100000,91000);
        check(v.parkedRemaining("a",101000)==120000,"only the two 30s visible intervals are billed");
        v.enter(other,pick,4,100050,91020,edge);v.leave(105000,95000);
        // Elapsed realtime includes deep sleep; uptime (and foreground spending) does not.
        long back=100000+MethodEntry.RETRY_WINDOW-1;
        v.enter(r,pick,9,back,96000,edge);
        check(v.remaining(96000)==120000 && v.seconds==180 && v.rejoined && v.draws==2,"299999ms preserves the same ticket without sharing between apps");
        v.leave(back+120000,216000);
        check(v.parkedRemaining("a",back+120100)==0, "spent allowance is retained");
        v.enter(r,pick,10,back+120150,216150,edge);
        check(v.rejoinedExhausted && v.remaining(216150)==0 && edge.draws==2, "spent retry never receives new content grace");
        long left=back+120151;v.leave(left,216151);
        v.enter(r,pick,11,left+MethodEntry.RETRY_WINDOW,216200,edge);
        check(!v.rejoined && v.remaining(216200)==180000 && edge.draws==3,"exactly five continuous minutes away permits a new draw");
        v.leave(left+MethodEntry.RETRY_WINDOW+100,216300);
        java.util.Map<String,NativeTarget> policies=new java.util.HashMap<>();
        policies.put("a",NativeTarget.from(timed("a",Rule.EXIT_LONG)));
        v.reconcile(policies,left+MethodEntry.RETRY_WINDOW+101);
        check(v.savedMask(r,left+MethodEntry.RETRY_WINDOW+102)==pick,"unrelated settings/structurally identical policy keep ticket");
        policies.put("a",NativeTarget.from(Rule.defaultRest("a","a",100,200,false)));
        v.reconcile(policies,left+MethodEntry.RETRY_WINDOW+103);
        check(v.savedMask(r,left+MethodEntry.RETRY_WINDOW+104)==0,"new effective policy cannot reuse previous ticket");
        Rule rest=timed("a",Rule.EXIT_SHORT);
        v.enter(rest,MethodPool.bit(Rule.EXIT_SHORT),1,5000000,300000,edge);
        check(!v.rejoinedExhausted && v.remaining(300000)==0, "first zero-second sample may present its original combo");
        v.leave(5000000,300000);
        v.enter(rest,MethodPool.bit(Rule.EXIT_SHORT),1,5000010,300010,edge);
        check(v.rejoinedExhausted && v.remaining(300010)==0,"zero-second re-entry has no new combination grace");
        // A previously scheduled rest timer in a mixed pool forces the same method/bin on both entries.
        Rule mixed=Rule.pool("x","x",100,200,false,MethodPool.bit(Rule.EXIT_LONG)|MethodPool.bit(Rule.EXIT_NOW),FeedbackBasket.NOTICE,"",false);
        v.enter(mixed,pick,1,6000000,400000,edge);v.leave(6000100,400100);
        MethodEntry entry=new MethodEntry(v);
        check(entry.icon(mixed,6001000,edge)==Rule.EXIT_LONG && entry.draws==0,"rest icon reuses timed ticket");
        check(entry.foregroundMask(mixed,1,1,6001001,edge)==pick && entry.draws==0,"rest external entry reuses same ticket");
        Rule combo=Rule.defaultRest("x","x",100,200,false).withDaily(25,pick|MethodPool.bit(Rule.EXIT_LOAD),FeedbackBasket.NOTICE,"",false,true).during(20);
        int combined=combo.methodMask;
        v.enter(combo,combined,2,6002000,401000,edge);v.leave(6002050,401050);
        check(entry.foregroundMask(combo,1,1,6002020,new Edge(false))==combined,"fresh hit resumes the parked timer");
        entry.endSurface(6002050);
        check(entry.foregroundMask(combo,1,1,6005000,new Edge(true))==combined && entry.draws==0 && entry.passes==0,"short sleep in the same verified visit does not reroll probability or group");
        entry.home(6005100);
        check(entry.foregroundMask(combo,1,1,6005200,new Edge(true))==MethodEntry.PASS,"new app entry may miss even with a parked timer");
        check(entry.foregroundMask(Rule.defaultRest("x","x",100,200,false),1,1,6006000,edge)==MethodPool.bit(Rule.EXIT_NOW),"rest priority replaces retained combo");
    }
    public static void main(String[] args) {
        String pkg="selected"; long now=10000;
        for(int mode : new int[]{Rule.EXIT_SHORT,Rule.EXIT_LONG}) {
            for(boolean max : new boolean[]{false,true}) {
                ForegroundVisit v = new ForegroundVisit(); Edge edge=new Edge(max);
                Rule policy=timed(pkg,mode); int pick=MethodPool.bit(mode);
                v.enter(policy,pick,80,now,now,edge);
                int seconds = mode==Rule.EXIT_SHORT ? (max ? 60:0) : (max?600:180);
                check(v.seconds==seconds && v.remaining(now)==seconds*1000L, "inclusive bounds");
                check(!v.enter(policy,pick,80,now+12,now+12,edge) && edge.draws==1, "no redraw on repeated events");
                if (seconds>0) check(v.remaining(now+seconds*1000L-1)==1,"not one ms early");
                check(v.matches(policy,pick) && v.remaining(now+seconds*1000L)==0, "same app due");
                long due=v.due;
                check(!v.enter(policy,pick,81,now+12,now+12,edge) && v.window==81 && v.due==due && v.draws==1,
                        "another full-screen Activity in same app: original deadline, one draw");
                check(v.elapsed(now+seconds*1000L+1500)==seconds*1000L+1500, "actual elapsed, not sampled time; same-app navigation preserves start");
                check(!v.matches(timed("unselected",mode),pick), "never transfer deadline to another app");
                v.leave(now+20,now+20); check(v.remaining(now+999999)==-1 && v.elapsed(now+999999)==-1 && !v.matches(policy,pick),"leave cancels");
                v.enter(policy,pick,81,now+999999,now+999999,edge); check(edge.draws==2 && v.elapsed(now+999999)==0,"new visit after actual leave gets new draw");
            }
            check(!Rule.validChoices(mode,FeedbackBasket.LOCK|FeedbackBasket.NOTICE)
                    && !Rule.validChoices(mode,0) && Rule.validChoices(mode,FeedbackBasket.NEW_DEFAULT),"mode validation");
            check(Rule.validChoices(mode,FeedbackBasket.DURATION) && Rule.validChoices(mode,FeedbackBasket.DURATION|FeedbackBasket.NEW_DEFAULT), "measured-duration authorization on delayed visits");
            Rule r=new Rule(pkg,"label",10,20,false,mode,FeedbackBasket.NOTICE);
            check(!NativeTarget.from(r).interceptIcon && r.returnHome,"no pad for delayed launch");
        }
        for (int mode : new int[]{Rule.EXIT_ICON,Rule.EXIT_NOW}) {
            check(!Rule.validChoices(mode,FeedbackBasket.DURATION|FeedbackBasket.NEW_DEFAULT), "no duration on an immediate or icon intervention");
            Rule r=new Rule(pkg,"label",10,20,false,mode,FeedbackBasket.LOCK);
            check(NativeTarget.from(r).interceptIcon && (r.returnHome==(mode==Rule.EXIT_NOW)), "old choices unchanged");
        }
        check(!Rule.validChoices(50,FeedbackBasket.NOTICE),"no unknown mode");
        budgets();
        System.out.println("ForegroundVisitTest passed: inclusive ranges, visit identity, exact deadlines, cancellations, no delayed lock or pad");
    }
}
