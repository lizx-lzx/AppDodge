package dev.local.appdodge;

import java.util.ArrayList;
import java.util.List;

/** The editor and the overview use the same names for the effects the rule actually stores. */
enum FeedbackChoices {
    REMINDER(FeedbackBasket.NOTICE, "休息提醒","休息时段显示休息到几点；其余时间只提示这次打开已被拦住。"),
    PULSE(FeedbackBasket.HAPTIC, "轻振两下","短振两次，遵循手机的触感设置。"),
    DURATION(FeedbackBasket.DURATION, "显示本轮用时","随机退回后显示本轮实际使用时长，切出去的时间不算。"),
    WAIT(FeedbackBasket.COUNTDOWN, "倒数 5 秒","回到桌面后显示五秒倒数。倒完提示消失，不会解除限制。"),
    SECOND_TAP(FeedbackBasket.LOCK, "再点锁屏","原图标先给锁屏提示，停一下再点同一图标才会锁屏。从其他入口退回时不会锁屏。");

    final int bit;
    final String label,explanation;
    FeedbackChoices(int bit,String label,String explanation){this.bit=bit;this.label=label;this.explanation=explanation;}
    static String summary(int mask){
        int count=Integer.bitCount(mask);
        if(count==1)return "只用"+selectedNames(mask);
        if(count==2)return selectedNames(mask)+" · 抽一项";
        return "已选 "+count+" 项 · 抽一项";
    }
    static String selectedNames(int mask) {
        List<String> result=new ArrayList<>();
        for(FeedbackChoices choice:values())if((mask&choice.bit)!=0)result.add(choice.label);
        return String.join("、",result);
    }
}
