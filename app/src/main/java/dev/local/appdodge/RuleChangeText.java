package dev.local.appdodge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Compare the effective policies, not record JSON or the number of selected choices. */
final class RuleChangeText {
    final String before,after;
    private RuleChangeText(String before,String after){this.before=before;this.after=after;}
    static RuleChangeText compare(Rule current,Rule next,Function<String,String> appName){
        Map<String,String> old=fields(current,appName), future=fields(next,appName);
        LinkedHashSet<String> keys=new LinkedHashSet<>(old.keySet());keys.addAll(future.keySet());
        ArrayList<String> left=new ArrayList<>(),right=new ArrayList<>();
        for(String key:keys){
            String a=old.get(key),b=future.get(key);
            if(Objects.equals(a,b))continue;
            if(a!=null)left.add(key+" "+a);
            if(b!=null)right.add(key+" "+b);
        }
        if(current!=null&&next!=null&&left.isEmpty()&&right.isEmpty())
            return new RuleChangeText("执行方式不变","更新保存的规则，执行方式不变");
        // Some edits only add/remove a dependent choice (e.g. the first post-interception feedback).
        return new RuleChangeText(current==null?"没有这条规则":left.isEmpty()?"保持原设置":String.join("；",left),
                                  next==null?"移除这条规则":right.isEmpty()?"取消上述设置":String.join("；",right));
    }
    private static Map<String,String> fields(Rule r,Function<String,String> appName){
        Map<String,String> m=new LinkedHashMap<>();
        if(r==null)return m;
        m.put("休息时段",r.limits.noSchedule?"关闭":r.restRangeText());
        m.put("单次上限",UseLimits.minutes(r.limits.session));m.put("单日上限",UseLimits.minutes(r.limits.day));
        m.put("单次用满后休息",r.limits.rest+" 分钟");
        m.put("拦截后停止后台运行",r.stopAfterReturn?"开启":"关闭");
        if(r.sharedBasket!=null) {
            SharedBasket b=r.sharedBasket;
            if(r.modernPolicy){
                m.put("其余时间执行",r.dayByEffects?"触发时按所选反馈拦截":"触发时直接拦住");
            }
            policy(m,"共用",b.methods,b.feedback,b.alternate,b.curtain,true,appName);
            m.put("休息时抽取",r.restCombo?"随机 2～3 种":"一种");
            m.put("日常干预",r.dailyChance==0?"关闭":r.dailyChance+"%");
            m.put("日常抽取",r.dayCombo?"随机 2～3 种":"一种");
            return m;
        }
        policy(m,"休息",r.restPolicy(),appName);
        m.put("日常干预",r.daytime==null?"关闭":r.dailyChance+"%");
        if(r.daytime!=null){
            m.put("每次触发",r.daytime.combo?(Integer.bitCount(r.dailyMask)==2?"两种一起":"随机 2～3 种"):"一种");
            policy(m,"日常",r.daytime,appName);
        }
        return m;
    }
    private static void policy(Map<String,String> m,String prefix,Rule p,Function<String,String> appName){
        policy(m,prefix,p.methodMask,p.feedbackMask,p.alternatePackage,p.curtain,p.daily,appName);
    }
    private static void policy(Map<String,String> m,String prefix,int mask,int feedback,String alternate,boolean curtain,boolean daily,Function<String,String> appName){
        ArrayList<String> methods=new ArrayList<>();
        for(DailyExitMethod method:DailyExitMethod.displayOrder(daily)){
            if(method==DailyExitMethod.RANDOM){
                if((mask&MethodPool.TIMED)!=0)methods.add(method.label+((mask&MethodPool.bit(Rule.EXIT_LONG))!=0?"（3～10 分钟）":"（0～1 分钟）"));
            }else if((mask&MethodPool.bit(method.mode))!=0)methods.add(method.label);
        }
        m.put(prefix+"方式",String.join("、",methods));
        if(feedback!=0)m.put(prefix+"拦截后",FeedbackChoices.selectedNames(feedback));
        if(!alternate.isEmpty())m.put(prefix+"备选应用",appName.apply(alternate));
        if((mask&MethodPool.TIMED)!=0)m.put(prefix+"退出前收拢画面",curtain?"开启":"关闭");
    }
}
