package dev.local.appdodge;

import android.app.ApplicationExitInfo;
import java.util.ArrayList;
import java.util.List;

public final class GuardLogTest {
    private static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        // A clean process stop, an app update and an explicit force-stop are different observations.
        int requested=ApplicationExitInfo.REASON_USER_REQUESTED;
        check(GuardLog.exit(10,requested,"user stopped <private>").title.equals("反人性软件进程已停止"));
        GuardLog.Entry stop=GuardLog.exit(11,requested,"[FORCE STOP] stop dev.local.appdodge due to single-cleaner");
        check(stop.title.equals("系统强行停止了反人性软件")&&stop.detail.contains("系统清理工具"));
        check(!stop.detail.contains("dev.local") && !stop.detail.contains("single-cleaner"));
        check(GuardLog.exit(12,requested,"[FORCE STOP] installPackageLI").title.equals("反人性软件已更新"));
        check(GuardLog.exit(13,ApplicationExitInfo.REASON_PACKAGE_UPDATED,null).title.equals("反人性软件已更新"));
        check(GuardLog.exit(14,ApplicationExitInfo.REASON_LOW_MEMORY,"force-stop").title.contains("回收"));
        check(GuardLog.exit(15,ApplicationExitInfo.REASON_OTHER,"unrelated package single-cleaner")==null);
        check(GuardLog.exit(16,requested,"[FORCE STOP] without cause").detail.contains("进程停止"));
        check(GuardLog.responses(SharedBasket.HOME_LOAD|SharedBasket.HOME_REDIRECT|FeedbackBasket.COUNTDOWN).equals("假装加载＋自动改道＋倒数 5 秒"));
        // Reading a merged snapshot repeatedly never mutates/imports it. Retain a bounded newest-first view.
        List<GuardLog.Entry> rows=new ArrayList<>();
        for(int i=0;i<120;i++)rows.add(new GuardLog.Entry(i,"拦截",""));
        rows.add(stop);List<GuardLog.Entry> first=GuardLog.recent(rows),second=GuardLog.recent(rows);
        check(rows.size()==121&&first.size()==100&&second.size()==100);
        check(first.get(0).at==119&&first.get(99).at==20&&first.equals(second));
        System.out.println("GuardLog: bounded merge, exit causes, label-only feedback");
    }
}
