package dev.local.appdodge;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;

/** The user-facing manager's local civil-time schedule; independent of the service and its edit lock. */
final class ManagementWindow {
    static final int OFF=-1;
    static final ManagementWindow DEFAULT=new ManagementWindow(OFF,OFF,OFF,OFF,0,0);
    final int start,end,pendingStart,pendingEnd;
    final long effectiveAt,oldUntil;
    ManagementWindow(int start,int end,int pendingStart,int pendingEnd,long effectiveAt,long oldUntil) {
        this.start=start; this.end=end; this.pendingStart=pendingStart; this.pendingEnd=pendingEnd; this.effectiveAt=effectiveAt;
        this.oldUntil=oldUntil;
    }
    static final class State {
        final boolean enabled,open,pending,pendingEnabled;
        final int start,end,pendingStart,pendingEnd;
        // All times are epoch milliseconds. 0 means not applicable, including nextOpen while open.
        final long nextOpen,nextBoundary,effectiveAt;
        private State(ManagementWindow w,boolean open,long nextOpen,long nextBoundary) {
            enabled=w.start!=OFF; this.open=open; start=w.start; end=w.end;
            pending=w.effectiveAt!=0; pendingEnabled=pending&&w.pendingStart!=OFF;
            pendingStart=w.pendingStart; pendingEnd=w.pendingEnd; effectiveAt=w.effectiveAt;
            this.nextOpen=nextOpen; this.nextBoundary=nextBoundary;
        }
    }
    static boolean valid(int start,int end) {
        return start>=0&&start<1440&&end>=0&&end<1440&&start!=end;
    }
    ManagementWindow at(long now) {
        return effectiveAt>0 && now>=effectiveAt ? new ManagementWindow(pendingStart,pendingEnd,OFF,OFF,0,0) : this;
    }
    private static boolean open(int start,int end,long now,ZoneId zone) {
        if(start==OFF)return true;
        ZonedDateTime t=Instant.ofEpochMilli(now).atZone(zone);
        int minute=t.getHour()*60+t.getMinute();
        return start<end ? minute>=start && minute<end : minute>=start || minute<end;
    }
    boolean open(long now,ZoneId zone) {
        ManagementWindow w=at(now);
        return (w.oldUntil==0 || now<w.oldUntil) && open(w.start,w.end,now,zone);
    }

    /** Earliest real change in local-minute membership, including a skipped or repeated hour. */
    private static long nextEdge(int start,int end,long now,ZoneId zone) {
        if(start==OFF)return 0;
        Instant from=Instant.ofEpochMilli(now);
        LocalDate date=from.atZone(zone).toLocalDate();
        ZoneRules rules=zone.getRules();
        long first=Long.MAX_VALUE;
        boolean wasOpen=open(start,end,now,zone);
        ZoneOffsetTransition transition=rules.nextTransition(from);
        if(transition!=null) {
            long jump=transition.getInstant().toEpochMilli();
            if(open(start,end,jump,zone)!=wasOpen)first=jump;
        }
        // Check today's two daily boundaries, then following dates as needed. A gap has no
        // matching instant; a fold has two. Compare membership so a clock jump cannot fake an edge.
        for(int day=0;day<4;day++) {
            LocalDate local=date.plusDays(day);
            if(local.atStartOfDay(zone).toInstant().toEpochMilli()>first)break;
            for(int minute:new int[]{start,end}) {
                LocalDateTime t=local.atTime(minute/60,minute%60);
                for(ZoneOffset offset:rules.getValidOffsets(t)) {
                    long point=t.toInstant(offset).toEpochMilli();
                    if(point>now && point<first && open(start,end,point,zone)!=wasOpen)first=point;
                }
            }
        }
        if(first==Long.MAX_VALUE)throw new IllegalStateException("No next daily management boundary");
        return first;
    }
    private static long followingStart(int start,int end,long after,ZoneId zone) {
        long edge=nextEdge(start,end,after,zone);
        return open(start,end,after,zone) ? nextEdge(start,end,edge,zone) : edge;
    }
    State state(long now,ZoneId zone) {
        ManagementWindow w=at(now);
        if(w!=this)return w.state(now,zone);
        boolean waiting=oldUntil!=0 && now>=oldUntil;
        boolean opened=!waiting && open(start,end,now,zone);
        long edge=waiting?0:nextEdge(start,end,now,zone), firstOpen=opened?0:edge;
        long handover=!waiting && oldUntil!=0 ? oldUntil : effectiveAt;
        if(handover!=0 && (edge==0 || handover<=edge)) {
            edge=handover;
            if(!opened)firstOpen=open(pendingStart,pendingEnd,effectiveAt,zone)
                    ?effectiveAt:nextEdge(pendingStart,pendingEnd,effectiveAt,zone);
        }
        return new State(this,opened,firstOpen,edge);
    }
    /** Overlay a later approved change for UI only. Before that instant the entire old
     * schedule (including a pre-existing 0.12 handover) still controls access and clocks. */
    State scheduledState(long now,ZoneId zone,int from,int to,long at){
        ManagementWindow active=at(now);
        State old=active.state(now,zone);
        long edge=old.nextBoundary==0?at:Math.min(old.nextBoundary,at), opening=old.nextOpen;
        if(!old.open && (opening==0||opening>=at)){
            State newState=new ManagementWindow(from,to,OFF,OFF,0,0).state(at,zone);
            opening=newState.open?at:newState.nextOpen;
        }
        return new State(new ManagementWindow(active.start,active.end,from,to,at,0),old.open,opening,edge);
    }
    /** null = not authorized or invalid. Only the OLD active schedule can authorize a change. */
    ManagementWindow change(boolean enable,int from,int to,long now,ZoneId zone) {
        ManagementWindow old=at(now);
        if(old!=this)return old.change(enable,from,to,now,zone);
        if(enable && !valid(from,to) || !open(now,zone))return null;
        if(!enable){from=OFF;to=OFF;}
        if(from==start && to==end) // Reconfirming the active hours cancels a previously queued change.
            return effectiveAt==0?this:new ManagementWindow(start,end,OFF,OFF,0,0);
        if(start==OFF)return new ManagementWindow(from,to,OFF,OFF,0,0); // Explicit first-time consent.
        if(effectiveAt!=0 && from==pendingStart && to==pendingEnd)return this;
        long tomorrow=Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long close=nextEdge(start,end,now,zone);
        // Tomorrow may begin DURING this very visit (or exactly at its close). Do not join
        // the two sessions. A replacement then starts at its next complete opening; a removal
        // waits for the next time the OLD rule would have let the user back in.
        long activate=tomorrow>close ? tomorrow :
                followingStart(enable?from:start,enable?to:end,close,zone);
        return new ManagementWindow(start,end,from,to,activate,tomorrow>close?0:close);
    }
}
