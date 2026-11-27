package dev.local.appdodge;

import java.util.function.IntFunction;
public final class PixelBadgeLabelTest {
    static void test(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        String app="计算器";
        IntFunction<String> en = n -> app+" has "+n+(n==1?" notification":" notifications");
        test(PixelBadgeLabel.matches(app,app,app+" has 1 notification",en));
        test(PixelBadgeLabel.matches(app,app,app+" has 12 notifications",en));
        for(String desc : new String[]{app,app+" has 1 notifications",app+" has 01 notification",
                app+" has 1 notification, deep link","work "+app+" has 1 notification",app+" has 2 widgets"})
            test(!PixelBadgeLabel.matches(app,app,desc,en));
        test(!PixelBadgeLabel.matches(app,"",en.apply(3),en));
        test(!PixelBadgeLabel.matches(app,"别的应用",en.apply(1),en));
        test(!PixelBadgeLabel.matches(app,app,app+" has ១ notifications",en));
        String numbered="123";
        test(PixelBadgeLabel.matches(numbered,numbered,"123：٢ 个通知",n->numbered+"："+(n==2?"٢":n)+" 个通知"));
        test(PixelBadgeLabel.matches(app,app,app+" 有一则通知",n->app+(n==1?" 有一则通知":" 有通知")));
        System.out.println("PixelBadgeLabelTest passed: exact real fixture, locale, copied label and suffix negatives");
    }
}
