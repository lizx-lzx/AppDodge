package dev.local.appdodge;

import java.util.Objects;
import java.util.Random;

/** One app's saved selection. Time policies project it; they never own another copy of the choices. */
final class SharedBasket {
    static final int REST_RESPONSES=FeedbackBasket.HAPTIC|FeedbackBasket.NOTICE|FeedbackBasket.COUNTDOWN;
    // Encoded separately from receipt bits; these two may run on the desktop without opening the target.
    static final int HOME_LOAD=256,HOME_REDIRECT=512;
    static final int DIRECT=MethodPool.bit(Rule.EXIT_ICON)|MethodPool.bit(Rule.EXIT_NOW)
            |MethodPool.ALTERNATIVES|MethodPool.bit(Rule.EXIT_LOAD)|MethodPool.bit(Rule.EXIT_LOCK_NOW)|MethodPool.bit(Rule.EXIT_LOCK_DELAY);
    final int methods,feedback;
    final String alternate;
    final boolean curtain;

    SharedBasket(int methods,int feedback,String alternate,boolean curtain) {
        this.alternate=alternate==null?"":alternate;
        if (!validSelection(methods,feedback) || ((methods & MethodPool.ALTERNATIVES)!=0)!=!this.alternate.isEmpty()
                || curtain && (methods&MethodPool.TIMED)==0) throw new IllegalArgumentException("请选择与反馈匹配的设置");
        this.methods=methods;this.feedback=feedback;this.curtain=curtain;
    }
    static SharedBasket fromPolicy(Rule source) {
        return new SharedBasket(source.methodMask,source.feedbackMask,source.alternatePackage,source.curtain);
    }
    static int allowedFeedback(int methods) {
        return FeedbackBasket.ALL & ((methods&MethodPool.TIMED)!=0?-1:~FeedbackBasket.DURATION);
    }
    static boolean validSelection(int methods,int feedback) {
        return MethodPool.valid(methods) && (feedback & ~allowedFeedback(methods))==0;
    }
    int restTickets(boolean icon){
        return (feedback&REST_RESPONSES) | ((methods&MethodPool.bit(Rule.EXIT_LOAD))!=0?HOME_LOAD:0)
                | (icon&&(methods&MethodPool.bit(Rule.EXIT_REDIRECT))!=0?HOME_REDIRECT:0);
    }
    boolean canRestCombo(){return Integer.bitCount(restTickets(true))>=2;}
    boolean canDayCombo(){return MethodPool.validCombo(methods&MethodPool.COMBINABLE);}
    boolean canEffectCombo(){return EffectPlan.participantMask(this)!=0;}
    int effectComboMethodMask(){return EffectPlan.methods(EffectPlan.participantMask(this));}
    int effectComboFeedbackMask(){return EffectPlan.replies(EffectPlan.participantMask(this));}
    int restIconMethods(){
        int direct=methods&DIRECT;
        return direct!=0?direct:MethodPool.bit(Rule.EXIT_NOW);
    }
    int dayMethods(boolean multiple){return multiple?methods&MethodPool.COMBINABLE:methods;}
    int drawRestResponses(Random random,boolean icon) {
        return RandomTickets.combination(restTickets(icon),random);
    }
    @Override public boolean equals(Object obj) {
        if(!(obj instanceof SharedBasket))return false;
        SharedBasket o=(SharedBasket)obj;
        return methods==o.methods&&feedback==o.feedback&&curtain==o.curtain&&alternate.equals(o.alternate);
    }
    @Override public int hashCode(){return Objects.hash(methods,feedback,alternate,curtain);}
}
