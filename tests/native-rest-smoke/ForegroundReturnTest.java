package dev.local.appdodge;

public final class ForegroundReturnTest {
    private static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    private static void stopReceipts(){
        ForegroundReturn r=new ForegroundReturn();
        long local=31,legacy=30,nextLocal=33;
        ForegroundReturn.StopGate yes=ForegroundReturn.StopGate.ARMED;
        check(r.home(5)==null,"manual Home, icon-only feedback and a probability miss cannot issue stop receipts");
        r.begin("a",Rule.EXIT_SHORT,true);
        check(r.home(10)==null,"a pending countdown / redirect is not an accepted Home request");
        r.begin("a",Rule.EXIT_SHORT,true);r.requested(false,20,-1,yes,local);
        check(r.home(22)==null,"rejected Home cannot issue a stop");
        r.begin("a",Rule.EXIT_SHORT,true);r.requested(true,30,-1,yes,local);r.observedApp("b");
        check(r.home(32)==null,"do not stop either package after a switch");
        for(ForegroundReturn.StopGate initial: new ForegroundReturn.StopGate[]{ForegroundReturn.StopGate.OFF,
                ForegroundReturn.StopGate.UNAVAILABLE,ForegroundReturn.StopGate.UNVERIFIED}){
            r.begin("a",Rule.EXIT_SHORT,true);r.requested(true,100,-1,initial,-1);
            r.requested(true,800,-1,yes,local);ForegroundReturn.Receipt got=r.home(850);
            check(got!=null && got.stop==initial && !r.canStop(got,850,local),"a retry cannot acquire opt-in or a later grant");
        }
        r.begin("a",Rule.EXIT_SHORT,true);r.requested(true,855,-1,yes,-1);
        check(!r.canStop(r.home(860),860,local),"a missing authority cannot become a local stop");
        r.begin("a",Rule.EXIT_SHORT,true);r.requested(true,1000,-1,yes,legacy);
        r.requested(true,1700,-1,yes,local);check(!r.canStop(r.home(1715),1715,local),"do not upgrade a legacy HOME retry to a local stop");
        r.begin("a",Rule.EXIT_SHORT,true);r.requested(true,1720,-1,yes,local);
        r.requested(true,1800,-1,yes,nextLocal);check(!r.canStop(r.home(1815),1815,nextLocal),"a reconnected local session cannot pick up an old HOME attempt");
        r.begin("a",Rule.EXIT_SHORT,true);r.requested(true,2000,-1,yes,local);r.disarmStop();
        r.requested(true,2700,-1,yes,local);check(!r.canStop(r.home(2750),2751,local),"permission or source revoked before Home");
        r.begin("a",Rule.EXIT_SHORT,true);r.requested(true,3000,-1,yes,local);
        r.requested(false,3700,-1,yes,local);check(!r.canStop(r.home(3750),3750,local),"later rejected attempt is ambiguous");
        r.begin("a",Rule.EXIT_SHORT,true);r.requested(true,4000,-1,yes,local);
        r.requested(true,4800,-1,yes,local);r.requested(true,5600,-1,yes,local);r.retryDue("a",6700);
        check(!r.canStop(r.home(6750),6750,local),"manual Home after retry exhaustion is never a privileged receipt");
        r.begin("a",Rule.EXIT_SHORT,true);r.requested(true,7000,-1,yes,local);
        ForegroundReturn.Receipt a=r.home(7100);
        check(a!=null && r.home(7105)==null && r.canStop(a,7120,local),"one admitted target survives repeated stable Home snapshots");
        check(!r.canStop(a,7100+ForegroundReturn.STOP_WINDOW+1,local),"a stale background binder must not replay");
        check(!r.canStop(a,7120,legacy),"an already-authorized Shizuku fallback cannot inherit a local stop");
        check(!r.canStop(a,7120,nextLocal),"a newly connected local session cannot inherit the old operation");
        check(!r.canStop(a,7120,-1),"disconnect means skip, not queue and replay");
        check(!r.canStop(a,a.expiresAt(),local),"no side effect at or beyond dispatch deadline");
        r.leftConfirmedHome();
        check(!r.canStop(a,7130,local),"leaving Home even briefly cancels a pending dispatch");
        r.begin("b",Rule.EXIT_NOW,false);r.requested(true,7200,-1,yes,local);
        ForegroundReturn.Receipt b=r.home(7210);
        r.finishStop(a);
        check(r.canStop(b,7211,local) && !r.canStop(a,7211,local) && b.pkg.equals("b"),"old asynchronous completion cannot erase or borrow a newer app's ticket");
        r.finishStop(b);
        check(!r.canStop(b,7212,local),"one completed binder action is not repeatable");
        r.begin("a",Rule.EXIT_SHORT,true);r.requested(true,8000,-1,yes,local);a=r.home(11990);
        check(!r.canStop(a,12001,local),"late binder cannot extend the original accepted HOME window");
    }
    public static void main(String[] args){
        stopReceipts();
        ForegroundReturn r=new ForegroundReturn();
        check(r.begin("a",Rule.EXIT_SHORT,true),"first observed foreground visit");
        r.requested(false,100,72);
        check(!r.retryDue("b",400) && !r.retryDue("a",349),"wrong target/too soon");
        check(r.retryDue("a",350),"a rejected request is not successful");
        r.requested(true,350,-1);
        check(!r.retryDue("a",1149)&&r.retryDue("a",1150),"accepted but same actual window still visible");
        r.requested(true,1150,-1);
        check(r.observedReturns==0 && !r.begin("a",Rule.EXIT_NOW,false),"requests/events aren't new draws or receipts");
        ForegroundReturn.Receipt got=r.home(1300);
        check(got!=null && got.mode==Rule.EXIT_SHORT && got.daily && got.visitMillis==72 && got.pkg.equals("a"),"one receipt; initial actual time retained after retries");
        check(r.observedReturns==1 && r.retries==2 && !r.pending() && r.home(1350)==null,"never duplicate receipt");
        check(r.begin("a",Rule.EXIT_NOW,false),"fresh visit after home"); r.requested(false,2000,-1);
        check(r.home(2020)==null,"do not ascribe unrelated HOME after only rejection");
        r.begin("a",Rule.EXIT_NOW,false);r.requested(true,3000,-1);r.observedApp("b");
        check(!r.retryDue("a",3800) && r.home(3900)==null,"intervening app cancels an old decision and its receipt");
        r.begin("a",Rule.EXIT_NOW,false);r.requested(true,4000,-1);r.leave("screen-locked");
        check(!r.retryDue("a",4800) && r.home(4810)==null,"never pull away phone/lockscreen");
        r.begin("a",Rule.EXIT_NOW,false);r.requested(true,5000,-1);
        check(r.home(9500)==null && r.outcome.equals("expired"),"later user navigation is not success");
        r.begin("a",Rule.EXIT_NOW,false);
        for(int i=0;i<3;i++){if(i!=0)check(r.retryDue("a",10000+i*2000),"bounded retry");r.requested(false,10000+i*2000,-1);}
        check(!r.retryDue("a",15500) && r.exhausted() && !r.pending() && r.unconfirmed==1,"verify final request and report actual failure");
        check(!r.retryDue("a",17777)&&r.unconfirmed==1,"events cannot retry or duplicate the failure endlessly");
        check(r.home(17800)==null,"no invented successful HOME");r.begin("a",Rule.EXIT_NOW,false);r.requested(true,18000,-1);
        check(r.home(18100).visitMillis==-1,"new visit permitted, no prior time attached");

        TargetWindowProbe p=new TargetWindowProbe();check(p.arm("a",0,true)&&!p.arm("a",10,true),"same event storm must not push deadline out");
        int checks=0;while(p.nextAt()>=0){check(++checks<=8,"bounded");p.advance();}
        check(checks>=3 && !p.arm("a",6000,false),"uncertain stale root must not self-arm");
        check(p.arm("a",7000,true)&&p.nextAt()>7000,"new target event may start fresh bounded reads");
        p.clear();check(p.target()==null && p.nextAt()<0,"cancel when target identified or interrupted");
        System.out.println("ForegroundReturnTest passed: fresh target rechecks, reject/accepted-not-returned retries, explicit exhaustion, measured receipt and cancellation");
    }
}
