package dev.local.appdodge;

public final class StopResultTest {
    static void check(int observation,StopBridge.Result.Status outcome){
        if(StopBridge.Result.afterCommand(observation).status!=outcome)throw new AssertionError(observation);
    }
    public static void main(String[] args){
        check(StopPackageState.STOPPED,StopBridge.Result.Status.CONFIRMED);
        check(StopPackageState.RUNNABLE,StopBridge.Result.Status.RUNNABLE);
        check(StopPackageState.UNKNOWN,StopBridge.Result.Status.UNKNOWN);
        check(AppStopWorker.FAILURE,StopBridge.Result.Status.UNKNOWN);
        if(StopBridge.Result.afterCommand(StopPackageState.UNKNOWN).detail.equals(StopBridge.Result.afterCommand(AppStopWorker.FAILURE).detail))
            throw new AssertionError("a missing command receipt is not a missing post-command check");
        System.out.println("StopResult: command receipt, stopped flag and immediate reactivation remain distinct.");
    }
}
