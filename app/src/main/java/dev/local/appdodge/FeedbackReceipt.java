package dev.local.appdodge;

/** A return or intercepted tap already happened. Describe that chosen feedback, not another rule. */
final class FeedbackReceipt {
    final String title, detail;
    FeedbackReceipt(String title,String detail){this.title=title;this.detail=detail;}
    static FeedbackReceipt countdown(int seconds){
        if(seconds<1||seconds>5)throw new IllegalArgumentException("倒数应为 1–5 秒");
        return new FeedbackReceipt(seconds+" 秒","提示倒数，结束不会放行");
    }
    static FeedbackReceipt forChoice(int kind,String label,String notice,boolean returned,long used,int seconds,boolean pulseSent){
        switch(kind){
            case FeedbackBasket.NOTICE:return new FeedbackReceipt(notice,returned?"已回到桌面":"已拦住这次打开");
            case FeedbackBasket.HAPTIC:return pulseSent?null:new FeedbackReceipt(returned?"已回到桌面":"已拦住这次打开","系统触感不可用");
            case FeedbackBasket.COUNTDOWN:return countdown(seconds);
            case FeedbackBasket.DURATION:return new FeedbackReceipt(FeedbackBasket.durationCaption(used),label);
            case FeedbackBasket.LOCK:return new FeedbackReceipt("再点一次，手机将锁屏",label);
            default:throw new IllegalArgumentException("未知拦截反馈");
        }
    }
}
