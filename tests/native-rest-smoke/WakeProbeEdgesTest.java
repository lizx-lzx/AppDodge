package dev.local.appdodge;

public class WakeProbeEdgesTest {
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        WakeProbeEdges e = new WakeProbeEdges();
        e.observe(WakeProbeEdges.Focus.HOME);
        require(!e.startMissing(false,false)); // healthy, nothing to retry
        e.wakeSignal(); // even if no screen-off ever arrived
        require(e.startMissing(true,false));
        e.wakeSignal(); require(!e.startMissing(true,true)); // don't restart running burst
        e.observe(WakeProbeEdges.Focus.UNKNOWN); require(!e.startMissing(true,false));
        e.observe(WakeProbeEdges.Focus.ELSEWHERE);
        e.homeSignal(); require(e.startMissing(true,false)); // fresh HOME after previous budget
        e.observe(WakeProbeEdges.Focus.HOME); require(!e.startMissing(true,false));
        e.wakeSignal(); require(!e.startMissing(false,false)); // late USER_PRESENT after first warning
    }
}
