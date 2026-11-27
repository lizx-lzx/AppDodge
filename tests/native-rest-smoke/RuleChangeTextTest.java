package dev.local.appdodge;

public final class RuleChangeTextTest {
    private static void yes(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static RuleChangeText diff(Rule a,Rule b){return RuleChangeText.compare(a,b,p->p.equals("com.notes")?"备忘录":"阅读");}
    public static void main(String[] args){
        Rule old=Rule.defaultRest("video","短视频",1380,420,false);
        Rule newer=Rule.defaultRest("video","短视频",1260,420,false);
        RuleChangeText stopped=diff(old,old.withStopAfterReturn(true));
        yes(stopped.before.equals("拦截后停止后台运行 关闭")&&stopped.after.equals("拦截后停止后台运行 开启"),"show exactly the pending opt-in");
        RuleChangeText times=diff(old,newer);
        yes(times.before.equals("休息时段 23:00–次日 07:00")&&times.after.equals("休息时段 21:00–次日 07:00"),"show changed wall time only");
        Rule reversed=Rule.defaultRest("video","短视频",420,1380,true);
        yes(diff(null,reversed).after.contains("23:00–次日 07:00"),"display rest even for allowed-interval records");
        Rule one=new Rule("video","短视频",1380,420,false,Rule.EXIT_ICON,FeedbackBasket.COUNTDOWN);
        Rule two=new Rule("video","短视频",1380,420,false,Rule.EXIT_ICON,FeedbackBasket.HAPTIC);
        RuleChangeText sameTime=diff(one,two);
        yes(sameTime.before.equals("休息拦截后 倒数 5 秒")&&sameTime.after.equals("休息拦截后 轻振两下"),"equal counts must name the feedback");
        Rule bundle=old.withDaily(50,MethodPool.bit(Rule.EXIT_NARROW)|MethodPool.bit(Rule.EXIT_MUTE),0,"",false,true);
        Rule bundle2=old.withDaily(50,MethodPool.bit(Rule.EXIT_LOAD)|MethodPool.bit(Rule.EXIT_MUTE),0,"",false,true);
        yes(diff(bundle,bundle2).before.contains("视野缩窄")&&diff(bundle,bundle2).after.contains("假装加载"),"same-size baskets show actual methods");
        Rule timer=old.withDaily(75,MethodPool.bit(Rule.EXIT_SHORT),FeedbackBasket.DURATION,"",false,false);
        Rule timer2=old.withDaily(75,MethodPool.bit(Rule.EXIT_LONG),FeedbackBasket.DURATION,"",true,false);
        yes(diff(timer,timer2).after.contains("3～10 分钟")&&diff(timer,timer2).after.contains("日常退出前收拢画面 开启"),"show changed timing and curtain");
        Rule alternate=old.withDaily(25,MethodPool.bit(Rule.EXIT_REDIRECT),0,"com.notes",false,false);
        yes(diff(null,alternate).before.equals("没有这条规则")&&diff(null,alternate).after.contains("备忘录"),"add describes target and future rule");
        yes(diff(alternate,null).after.equals("移除这条规则")&&diff(alternate,null).before.contains("日常干预 25%"),"removal preserves current behavior until due");
        System.out.println("RuleChangeTextTest PASS");
    }
}
