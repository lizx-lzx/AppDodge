package dev.local.appdodge;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Instant;

/** Local-date rules tested with deterministic civil time, without touching a device clock. */
public final class ManagementWindowTest {
    private static final ZoneId zone=ZoneId.of("America/Los_Angeles");
    private static final int evening=20*60;
    private static long t(String local){return LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli();}
    private static long utc(String utc){return Instant.parse(utc).toEpochMilli();}
    private static void check(boolean yes,String what){if(!yes)throw new AssertionError(what);}
    private static ManagementWindow restore(ManagementWindow w) {
        return new ManagementWindow(w.start,w.end,w.pendingStart,w.pendingEnd,w.effectiveAt,w.oldUntil);
    }
    public static void main(String[] args) {
        long before=t("2026-11-23T19:59:00"), begin=t("2026-11-23T20:00:00"), end=t("2026-11-23T20:15:00");
        ManagementWindow empty=ManagementWindow.DEFAULT;
        check(empty.state(before,zone).open && empty.state(before,zone).nextBoundary==0,"no gate for existing installs");
        check(empty.change(true,evening,evening,before,zone)==null && empty.change(true,-1,evening,before,zone)==null
                && empty.change(true,1,1440,before,zone)==null,"reject equal, negative and invalid times");
        ManagementWindow w=empty.change(true,evening,evening+15,before,zone);
        check(!w.state(before,zone).open && w.state(before,zone).nextOpen==begin,"first activation applies immediately");
        check(w.state(begin,zone).open && w.state(begin,zone).nextBoundary==end && w.state(begin,zone).nextOpen==0,"open start inclusive");
        check(w.open(end-1,zone) && !w.open(end,zone),"close at exact end");
        check(w.change(false,0,0,end,zone)==null && w.change(true,0,1439,end,zone)==null,"cannot disable or edit when closed");
        long tomorrow=t("2026-11-24T00:00:00");
        ManagementWindow expanded=w.change(true,evening,23*60,end-1,zone);
        check(expanded.state(end-1,zone).pending && expanded.state(end-1,zone).effectiveAt==tomorrow
                && expanded.state(end-1,zone).nextBoundary==end,"old hours still control today's change");
        check(!restore(expanded).state(end,zone).open && expanded.state(end,zone).nextOpen==t("2026-11-24T20:00:00")
                && expanded.state(end,zone).nextBoundary==tomorrow,"reopen remembers queued change and first actual opening");
        check(expanded.at(tomorrow).end==23*60 && !expanded.state(tomorrow,zone).pending,"new rule active the next day");
        check(expanded.change(true,evening,23*60,end-1,zone)==expanded,"duplicate never defers pending activation");
        ManagementWindow undone=expanded.change(true,evening,evening+15,end-1,zone);
        check(undone.effectiveAt==0 && undone.end==evening+15,"can withdraw pending edit only in original hours");
        check(expanded.change(true,12*60,13*60,end,zone)==null,"outside old hours cannot replace queued change");
        ManagementWindow disabled=w.change(false,0,0,begin,zone);
        check(disabled.state(end,zone).nextOpen==tomorrow && !disabled.open(tomorrow-1,zone)
                && disabled.state(tomorrow,zone).open && !disabled.state(tomorrow,zone).enabled,"disable waits for next day");

        ManagementWindow night=empty.change(true,23*60,60,before,zone);
        long late=t("2026-11-23T23:58:00"), close=t("2026-11-24T01:00:00");
        long nextDay=t("2026-11-24T09:00:00");
        ManagementWindow morning=night.change(true,9*60,10*60,late,zone);
        check(morning.effectiveAt==nextDay && morning.open(tomorrow,zone) && morning.state(tomorrow,zone).nextBoundary==close,
                "midnight cannot extend the overnight session");
        check(!morning.open(close,zone) && morning.state(close,zone).nextOpen==nextDay && morning.open(nextDay,zone),
                "closed gap, new complete morning opening");
        ManagementWindow overlap=night.change(true,30,120,late,zone);
        check(overlap.effectiveAt==t("2026-11-25T00:30:00") && !overlap.open(close,zone),"overlapping replacement never merges with current window");
        check(restore(overlap).state(close,zone).nextOpen==overlap.effectiveAt && !overlap.open(t("2026-11-24T23:00:00"),zone)
                && overlap.change(false,0,0,t("2026-11-24T23:00:00"),zone)==null,
                "no stray admission from old schedule while waiting for the first complete new opening");
        ManagementWindow midnight=empty.change(true,23*60,0,before,zone);
        check(midnight.change(false,0,0,late,zone).effectiveAt==t("2026-11-24T23:00:00"),"even an exact midnight end closes on a pending disable");
        check(night.change(false,0,0,late,zone).effectiveAt==t("2026-11-24T23:00:00"),"overnight disable waits until next old admission");
        // Los Angeles spring gap: 02:00-02:59 does not exist; the actual first allowed minute is 03:00.
        ManagementWindow spring=empty.change(true,2*60+30,3*60+15,before,zone);
        long springJump=utc("2026-03-08T10:00:00Z");
        check(spring.state(springJump-1,zone).nextOpen==springJump && spring.open(springJump,zone)
                && spring.state(springJump,zone).nextBoundary==utc("2026-03-08T10:15:00Z"),"spring-forward edge");
        ManagementWindow lost=empty.change(true,2*60+15,2*60+30,before,zone);
        check(lost.state(springJump-1,zone).nextOpen==t("2026-03-09T02:15:00"),"skip a wholly missing opening");
        ManagementWindow fold=empty.change(true,65,75,before,zone);
        long first=utc("2026-11-01T08:05:00Z"), second=utc("2026-11-01T09:05:00Z");
        check(fold.open(first,zone) && fold.state(first+10*60_000L,zone).nextOpen==second && fold.open(second,zone),"fall repeated civil minutes observed twice");
        System.out.println("ManagementWindowTest passed: exact entry/exit, deferred changes and disable, restart, midnight, DST");
    }
}
