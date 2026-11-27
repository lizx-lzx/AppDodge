package dev.local.appdodge;

/** Hints can ask for a re-check, never change a user's tap state or restart a running budget. */
final class WakeProbeEdges {
    enum Focus { UNKNOWN, ELSEWHERE, HOME }
    private boolean pending, awaitingHome = true;
    void wakeSignal() { pending = true; }
    void leftHome() { awaitingHome = true; }
    void homeSignal() {
        if (awaitingHome) pending = true;
        awaitingHome = false;
    }
    void observe(Focus focus) {
        if (focus == Focus.HOME) homeSignal();
        if (focus == Focus.ELSEWHERE) leftHome();
    }
    boolean startMissing(boolean missing, boolean running) {
        boolean start = pending && missing && !running;
        pending = false;
        return start;
    }
}
