package dev.local.appdodge;
import java.time.*;

public final class UsageLimitsTest {
    static void check(boolean yes){if(!yes)throw new AssertionError();}
    public static void main(String[] args){
        UseLimits l=new UseLimits(30,90,10,true);QuotaBudget b=new QuotaBudget();
        b.account(50,80*60000L,10000000);b.round=18*60000;
        check(b.next(l)==10*60000);b.account(50,10*60000,10000000);check(b.reason(l,9000)==QuotaBudget.DAY);
        b.returned(QuotaBudget.DAY,l,9000);check(b.reason(l,10000)==QuotaBudget.DAY);
        b.account(51,2000,500);check(b.spent==500&&b.round==2000&&b.reason(l,10000)==0);
        b.round=30*60000;check(b.reason(l,10000)==QuotaBudget.SESSION);
        b.returned(QuotaBudget.SESSION,l,10000);check(b.reason(l,609999)==QuotaBudget.REST);check(b.reason(l,610000)==0);
        ZoneId z=ZoneId.of("America/Los_Angeles");UsageSeries s=new UsageSeries(z);
        LocalDate date=LocalDate.of(2026,11,1);long d=date.toEpochDay(),m=date.atStartOfDay(z).toInstant().toEpochMilli();
        s.event(m-3000,1,"a","A");s.event(m+1000,1,"b","B");s.event(m+1500,2,"a","A");
        s.event(m+2000,16,null,null);s.event(m+4000,15,null,null);s.event(m+5000,17,null,null);
        s.event(m+8000,18,null,null);s.event(m+9000,2,"b","B");s.finish(m+26*3600000L);
        check(s.days.get(d-1).total==3000 && !s.days.get(d-1).complete);
        check(s.days.get(d).complete&&s.days.get(d).apps.get("a")==1000&&s.days.get(d).apps.get("b")==3000);
        UsageSeries dst=new UsageSeries(z);dst.event(m,1,"a","A");dst.finish(date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli());
        check(dst.days.get(d).total==25*3600000L&&dst.days.get(d).hours[1]==7200000L);
        System.out.println("UsageLimits: daily priority, midnight, session rest, time union, lock, DST, partial day passed");
    }
}
