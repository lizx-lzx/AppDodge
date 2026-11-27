package dev.local.appdodge;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Foreground union: attribute one interval once, even if two activities were resumed. */
final class UsageSeries {
    static class Day {
        final Map<String,Long> apps=new HashMap<>();
        final long[] hours=new long[24];
        long total;boolean complete;
    }
    final TreeMap<Long,Day> days=new TreeMap<>();final ZoneId zone;
    String pkg,activity; long cursor=-1,first=Long.MAX_VALUE;boolean lit=true,unlocked=true;
    UsageSeries(ZoneId zone){this.zone=zone;}
    private Day day(long id){Day d=days.get(id);if(d==null){d=new Day();days.put(id,d);}return d;}
    void advance(long end){
        if(cursor>=0 && lit&&unlocked&&pkg!=null){
            for(long t=cursor;t<end;){
                ZonedDateTime at=Instant.ofEpochMilli(t).atZone(zone);
                long edge=Math.min(end,at.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli());
                Day d=day(at.toLocalDate().toEpochDay());long n=edge-t;
                d.total+=n;d.hours[at.getHour()]+=n;d.apps.merge(pkg,n,Long::sum);t=edge;
            }
        }
        cursor=end;
    }
    void event(long t,int type,String owner,String cls){
        // Android UsageEvents constants; keeping this reducer Android-independent makes it testable.
        if(type!=1&&type!=2&&type!=23&&type!=15&&type!=16&&type!=17&&type!=18&&type!=26&&type!=27)return;
        if(cursor>t)return;
        first=Math.min(first,t);advance(t);
        if(type==1){pkg=owner;activity=cls;}
        if((type==2||type==23)&&Objects.equals(pkg,owner)&&Objects.equals(activity,cls)){pkg=null;activity=null;}
        if(type==15)lit=true;if(type==16)lit=false;
        if(type==17)unlocked=false;if(type==18)unlocked=true;
        if(type==26||type==27){pkg=null;activity=null;lit=type==27;unlocked=type==27;}
    }
    void finish(long at){
        advance(at);
        for(Map.Entry<Long,Day> e:days.entrySet()){
            ZonedDateTime start=LocalDate.ofEpochDay(e.getKey()).atStartOfDay(zone);
            e.getValue().complete=first<=start.toInstant().toEpochMilli() && at>=start.plusDays(1).toInstant().toEpochMilli();
        }
    }
}
