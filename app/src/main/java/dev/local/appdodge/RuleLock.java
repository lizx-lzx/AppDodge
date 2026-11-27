package dev.local.appdodge;

/** Immutable, persistable edit lock. Within a boot, elapsed realtime includes sleep and ignores wall-clock edits. */
final class RuleLock {
    static final long LENGTH_MS = 15 * 60_000L;
    final int boot;
    final long elapsedUntil;
    // A wall deadline is used only to bridge the first reboot. Zero means that bridge was spent.
    final long wallUntil;

    RuleLock(int boot, long elapsedUntil, long wallUntil) {
        this.boot = boot; this.elapsedUntil = elapsedUntil; this.wallUntil = wallUntil;
    }

    static RuleLock start(RuleLock previous, int boot, long elapsedNow, long wallNow) {
        if (boot < 0 || elapsedNow < 0) return null;
        RuleLock active = previous == null ? null : previous.at(boot, elapsedNow, wallNow);
        return active != null ? active : new RuleLock(boot, elapsedNow + LENGTH_MS, wallNow + LENGTH_MS);
    }

    /** Persist a changed non-null snapshot; remove an expired one. Reading a lock never starts one. */
    RuleLock at(int currentBoot, long elapsedNow, long wallNow) {
        if (boot < 0 || currentBoot < 0 || elapsedNow < 0) return null;
        if (boot == currentBoot) {
            long left = elapsedUntil - elapsedNow;
            return left > 0 && left <= LENGTH_MS ? this : null;
        }
        if ((long) currentBoot - boot != 1 || wallUntil == 0) return null;
        long wallLeft = wallUntil - wallNow;
        if (wallLeft <= 0 || wallLeft > LENGTH_MS) return null; // elapsed by wall time, or clock predates start
        // This lock already existed before the current boot. The uptime since boot is a lower
        // bound on time spent locked even if the wall clock was rolled back within its window.
        long left = Math.min(wallLeft, LENGTH_MS - elapsedNow);
        return left <= 0 ? null : new RuleLock(currentBoot, elapsedNow + left, 0);
    }
}
