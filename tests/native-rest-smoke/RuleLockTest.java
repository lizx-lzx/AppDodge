package dev.local.appdodge;

public final class RuleLockTest {
    private static final long D = 900_000L, DAY = 86_400_000L, EL = 50_000_000L, WALL = 1_800_000_000_000L;
    private static final int BOOT = 17;
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    private static RuleLock restore(RuleLock lock) {
        return new RuleLock(lock.boot, lock.elapsedUntil, lock.wallUntil);
    }
    public static void main(String[] args) {
        RuleLock original = RuleLock.start(null, BOOT, EL, WALL);
        check(original.elapsedUntil == EL + D && original.wallUntil == WALL + D, "15 minute deadline");
        RuleLock cold = restore(original); // Exact values stored across process death.
        check(cold.at(BOOT, EL + D - 1, WALL + DAY) == cold, "clock forward does not unlock");
        check(cold.at(BOOT, EL + 100, WALL - DAY) == cold, "clock back does not extend");
        check(RuleLock.start(cold, BOOT, EL + 100, WALL + 100) == cold, "repeated start does not renew");
        check(cold.at(BOOT, EL + D, WALL - DAY) == null, "unlocks exactly at elapsed deadline");

        RuleLock next = cold.at(BOOT + 1, 30_000, WALL + 300_000);
        check(next != null && next.boot == BOOT + 1 && next.elapsedUntil == 630_000, "restart carries 10 minutes");
        RuleLock reopened = restore(next);
        check(reopened.at(BOOT + 1, 629_999, WALL + DAY) == reopened, "new boot also ignores wall changes");
        check(reopened.at(BOOT + 1, 630_000, WALL - DAY) == null, "expiry after restart");
        check(reopened.at(BOOT + 2, 1000, WALL + 1000) == null, "repeated restarts do not extend");
        check(cold.at(BOOT + 2, 1000, WALL + 1000) == null, "two unobserved restarts do not extend");

        check(cold.at(BOOT + 1, 1000, WALL + D) == null, "off past deadline");
        check(cold.at(BOOT + 1, 1000, WALL - DAY) == null, "clock before original start is unusable");
        check(cold.at(BOOT + 1, 60_000, WALL + 1).elapsedUntil == D, "current boot time already spent");
        check(cold.at(BOOT + 1, D, WALL + 1) == null, "no bridge after 15 minutes of uptime");
        check(cold.at(-1, 1000, WALL) == null && RuleLock.start(null, -1, 1000, WALL) == null,
                "missing boot identity must not arm a persistent lock");
        check(RuleLock.start(cold, BOOT, EL + D + 1, WALL + D + 1).elapsedUntil == EL + 2*D + 1,
                "an explicit new start after expiry is allowed");
        System.out.println("RuleLockTest passed: 15 minute deadline, restarts, shifts and bounded reboot carry");
    }
}
