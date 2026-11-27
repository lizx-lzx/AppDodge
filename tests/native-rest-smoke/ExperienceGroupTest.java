package dev.local.appdodge;

import java.util.Random;

public final class ExperienceGroupTest {
    private static final int L=ExperienceGroup.LOAD,F=ExperienceGroup.STALL,
            N=ExperienceGroup.NARROW,M=ExperienceGroup.MUTE;
    private static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    private static int show(ExperienceGroup g,String pkg,int bits,long now,boolean visible){
        g.advance(pkg,bits,now,visible);int applied=g.active();g.observe(applied,now);return applied;
    }
    public static void main(String[] args) {
        for(int mask=1;mask<16;mask++){
            if(Integer.bitCount(mask)>3)continue;
            int bits=mask<<Rule.EXIT_LOAD; ExperienceGroup g=new ExperienceGroup(new Random(133));
            for(int second=0;second<=95;second++) {
                long now=second*1000L;
                int applied=show(g,"one",bits,now,true);
                check((applied&~bits)==0,"did not select this effect");
                if((bits&L)!=0 && second<15) {
                    check((applied&L)!=0 && (applied&(N|F))==0 && !g.pastLoading(),"opaque until load finishes");
                    check(g.fraction(now)==0,"narrow waited behind spinner");
                }
                if((bits&M)!=0)check((applied&M)!=0,"media concurrent, even while loading");
                if((bits&N)!=0 && ((bits&L)==0 || second>=15))check((applied&N)!=0,"curtain remains while frozen");
                check(show(g,"one",bits,now,true)==applied,"duplicate same-window scan");
            }
            check(g.pastLoading() && g.contentExperienced(),"every selected phase eventually delivered for "+bits);
            g.leave(95_001);check(g.mask()==0 && g.active()==0,"group is no longer owned by previous application");
        }
        ExperienceGroup g=new ExperienceGroup(new Random(17));int all=L|N|M;
        check(show(g,"a",all,0,false)==M && !g.pastLoading(),"under system surface only media, no hidden spinner");
        check(show(g,"a",all,20000,true)==(L|M),"first verified visual frame starts loading");
        check(show(g,"a",all,34999,true)==(L|M),"15 full seconds, no narrowing in background");
        check(show(g,"a",all,35000,true)==(N|M) && g.fraction(35000)==0,"initial clear content frame");
        check(show(g,"a",all,39500,true)==(N|M) && g.contentExperienced(),"visible narrow frame delivered");
        float frac=g.fraction(39500);
        g.leave(39500);
        check(show(g,"a",all,40000,true)==(L|M),"real re-entry starts longer loading");
        check(show(g,"a",all,69999,true)==(L|M),"full 30 second revisit delay");
        check(show(g,"a",all,70000,true)==(N|M) && g.fraction(70000)==frac,"restore narrowing only after re-entry load");
        g.suspend();
        check(show(g,"a",all,71000,true)==(N|M) && g.fraction(71000)>=frac,"temporary lost focus did not reload");
        int nf=N|F;
        g.reset(73000);show(g,"a",nf,74000,true);
        long st=74000;
        while((show(g,"a",nf,st,true)&F)==0)st+=100;
        check(!g.contentExperienced(),"0s exit cannot cancel first freeze before it is perceptible");
        show(g,"a",nf,st+1000,true);
        check(g.contentExperienced(),"freeze and visible narrowing can actually coincide");
        g.leave(st+1001);
        check(show(g,"a",nf,st+1010,true)==nf,"short re-entry freezes and narrows simultaneously");
        g.reset(st+1011);
        show(g,"a",L|F,0,true);g.leave(1000);
        check(show(g,"a",F,1200,true)==0,"never-shown freeze behind spinner did not get false re-entry history");
        g.reset(1300);
        g.advance("x",N|M,1400,true);g.observe(0,1400);
        g.advance("x",N|M,5400,true);g.observe(0,5400);
        check(!g.contentExperienced(),"unavailable output surfaces are not counted as presented");
        System.out.println("ExperienceGroupTest passed: each valid 1-3 effect subset; exclusion, concurrency, actual presentation, recovery and quick re-entry");
    }
}
