package dev.local.appdodge;

import java.util.Random;
public final class ExperienceScheduleTest {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] args) {
        ExperienceSchedule s=new ExperienceSchedule(new Random(7));
        s.begin("a",Rule.EXIT_LOAD,0);check(s.phase(14999)==s.LOADING,"15 seconds");
        s.begin("a",Rule.EXIT_LOAD,14999);check(s.phase(15000)==s.OPEN,"window refresh must not restart delay");
        // An Activity slide or unknown frame only detaches surfaces. No leave/entry is recorded.
        s.begin("a",Rule.EXIT_LOAD,15001);check(s.phase(15001)==s.OPEN && s.nextEdge(15001)==Long.MAX_VALUE,
                "a resumed verified window after loading must not start 30s again");
        s.leave(15001);s.begin("a",Rule.EXIT_LOAD,15100);check(s.nextEdge(15100)==45100,"repeat load 30 seconds");s.leave(15101);
        s.begin("b",Rule.EXIT_LOAD,16000);check(s.nextEdge(16000)==31000,"another package independent");s.leave(16200);
        s.begin("a",Rule.EXIT_LOAD,315101);check(s.nextEdge(315101)==330101,"five minute expiry");
        s.reset(320000);
        for (int i=0;i<100;i++){
            s.begin("a",Rule.EXIT_STALL,0);long gap=s.nextEdge(0);
            check(gap>=3000 && gap<=6000 && s.phase(gap-1)==s.OPEN,"initial fluent interval");
            check(s.phase(gap)==s.FROZEN,"first stall begins");long end=s.nextEdge(gap);
            check(end-gap>=4000 && end-gap<=6000 && s.phase(end-1)==s.FROZEN,"4-6 second touch intercept");
            check(s.phase(end)==s.OPEN,"touch releases");
            s.leave(end+1);s.begin("a",Rule.EXIT_STALL,end+2);check(s.phase(end+2)==s.FROZEN,"quick reentry stalls immediately");
            s.reset(end+3);
        }
        s.begin("a",Rule.EXIT_NARROW,1000);
        long ramp=s.ramp();check(ramp>=20000 && ramp<=40000,"narrow time");
        float halfway=s.fraction(1000+ramp/2);check(Math.abs(halfway-.5)<.001,"halfway");
        s.leave(1000+ramp/2);s.begin("a",Rule.EXIT_NARROW,100000);
        check(s.ramp()==ramp && s.fraction(100000)==halfway,"resume previously reached width; time away does not count");
        check(s.fraction(200000)==1,"slit holds");
        s.leave(200000);s.begin("a",Rule.EXIT_NARROW,500000);check(s.fraction(500000)==0,"slit memory expires");
        s.reset(500100);s.begin("a",Rule.EXIT_LOAD,500101);check(s.nextEdge(500101)==515101,"reset clears memories");
        System.out.println("ExperienceScheduleTest passed: 15/30s loading; 4-6s freezes; 20-40s narrowing, re-entry and cleanup");
    }
}
