package dev.local.appdodge;

import java.util.Random;
public final class FeedbackBasketTest {
    static void test(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        Random random = new Random(19082);
        for (int mask=1;mask<=FeedbackBasket.ALL;mask++) for (int via=0;via<2;via++) {
            FeedbackBasket.Round round=new FeedbackBasket.Round();
            int eligible=FeedbackBasket.eligible(mask, via==1);
            test((eligible & (via==1 ? FeedbackBasket.LOCK : FeedbackBasket.DURATION))==0);
            int pool=eligible,n=Integer.bitCount(pool),seen=0;
            for(int i=0;i<n;i++) {
                final int slot=i;
                Random ticket=new Random(){public int nextInt(int bound){test(bound==n);return slot;}};
                int expect=pool;
                for(int skip=0;skip<i;skip++)expect&=expect-1;
                expect=Integer.lowestOneBit(expect);
                // The same winning slot on two fresh attempts may repeat, even with alternatives.
                for(int run=0;run<2;run++){
                    long t=6000L*(2*i+run);
                    int item=round.draw(mask,via==1,t,ticket);test(item==expect);
                    test(round.draw(mask,via==1,t+60,ticket)==0);
                    seen|=item;
                }
            }
            test(seen==eligible);
        }
        FeedbackBasket.Round countdown = new FeedbackBasket.Round(); long t=421;
        test(countdown.draw(FeedbackBasket.COUNTDOWN,false,t,random)==FeedbackBasket.COUNTDOWN);
        for(int i=0;i<5;i++) {
            test(countdown.seconds(t+1000*i)==5-i);
            test(countdown.draw(FeedbackBasket.COUNTDOWN,true,t+1000*i+999,random)==0);
        }
        test(countdown.seconds(t+5000)==0 && countdown.draw(FeedbackBasket.COUNTDOWN,false,t+5000,random)==FeedbackBasket.COUNTDOWN);
        FeedbackBasket.Round returned=new FeedbackBasket.Round();
        test(returned.draw(FeedbackBasket.COUNTDOWN,true,t,random)==FeedbackBasket.COUNTDOWN);
        // A tap on the actual icon during a confirmed return's countdown must not open a new draw.
        for(long dt=0;dt<5000;dt+=60)test(returned.responding(t+dt));
        test(returned.end()==t+5000 && !returned.responding(t+5000));
        test(returned.draw(FeedbackBasket.HAPTIC,false,t+5000,random)==FeedbackBasket.HAPTIC);
        test(returned.responding(t+5849)&&!returned.responding(t+5850)); // no overlay needed
        for (int k : new int[]{0,FeedbackBasket.NOTICE,FeedbackBasket.HAPTIC,FeedbackBasket.COUNTDOWN,FeedbackBasket.DURATION})
            test(!FeedbackBasket.canConfirmLock(k,true,1,-1,10000));
        test(!FeedbackBasket.canConfirmLock(FeedbackBasket.LOCK,true,-1,-1,10000));
        test(!FeedbackBasket.canConfirmLock(FeedbackBasket.LOCK,false,1,-1,10000));
        test(!FeedbackBasket.canConfirmLock(FeedbackBasket.LOCK,true,10,-1,859));
        test(FeedbackBasket.canConfirmLock(FeedbackBasket.LOCK,true,10,-1,860));
        long up=-1;for(long down=100;down<2000;down+=150){
            test(!FeedbackBasket.canConfirmLock(FeedbackBasket.LOCK,true,10,up,down));up=down+30;
        }
        test(!FeedbackBasket.canConfirmLock(FeedbackBasket.LOCK,true,10,up,up+849));
        test(FeedbackBasket.canConfirmLock(FeedbackBasket.LOCK,true,10,up,up+850));
        test(FeedbackBasket.durationCaption(0).equals("本轮累计使用不到 1 秒"));
        test(FeedbackBasket.durationCaption(999).equals("本轮累计使用不到 1 秒"));
        test(FeedbackBasket.durationCaption(1999).equals("本轮共用了 1 秒"));
        test(FeedbackBasket.durationCaption(60000).equals("本轮共用了 1 分钟"));
        test(FeedbackBasket.durationCaption(181999).equals("本轮共用了 3 分钟 1 秒"));
        String time="计算器休息到 23:00";
        test(FeedbackReceipt.forChoice(FeedbackBasket.NOTICE,"计算器",time,false,-1,0,false).title.equals(time));
        test(FeedbackReceipt.forChoice(FeedbackBasket.HAPTIC,"计算器",time,false,-1,0,true)==null);
        test(FeedbackReceipt.forChoice(FeedbackBasket.HAPTIC,"计算器",time,false,-1,0,false).title.equals("已拦住这次打开"));
        test(FeedbackReceipt.forChoice(FeedbackBasket.HAPTIC,"计算器",time,true,-1,0,false).title.equals("已回到桌面"));
        test(FeedbackReceipt.forChoice(FeedbackBasket.COUNTDOWN,"计算器",time,true,-1,3,false).title.equals("3 秒"));
        test(FeedbackReceipt.forChoice(FeedbackBasket.DURATION,"计算器",time,true,120000,0,false).title.equals("本轮共用了 2 分钟"));
        test(FeedbackReceipt.forChoice(FeedbackBasket.LOCK,"计算器",time,false,-1,0,false).title.contains("将锁屏"));
        System.out.println("FeedbackBasketTest passed: all selections, both entries, independent uniform tickets, timing and lock consent");
    }
}
