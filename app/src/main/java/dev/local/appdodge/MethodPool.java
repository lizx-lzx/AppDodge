package dev.local.appdodge;

import java.util.Random;

/** A user selection contains one ticket per method. The two timer ranges share one ticket. */
final class MethodPool {
    static final int TIMED = (1 << Rule.EXIT_SHORT) | (1 << Rule.EXIT_LONG);
    static final int EXPERIENCES = (1 << Rule.EXIT_LOAD) | (1 << Rule.EXIT_STALL) | (1 << Rule.EXIT_NARROW) | (1 << Rule.EXIT_MUTE);
    static final int COMBINABLE = EXPERIENCES | TIMED;
    static final int FOREGROUND = TIMED | (1 << Rule.EXIT_NOW) | EXPERIENCES;
    static final int ALTERNATIVES = (1 << Rule.EXIT_ALTERNATIVE) | (1 << Rule.EXIT_REDIRECT);
    private static final int REST = (1 << (Rule.EXIT_REDIRECT + 1)) - 1;
    private static final int KNOWN = REST | EXPERIENCES;

    static int bit(int mode) { return Rule.validExit(mode) ? 1 << mode : 0; }
    static int all(int preferredDelay) {
        if (!Rule.delayed(preferredDelay)) throw new IllegalArgumentException("请选择停留时长");
        return (REST & ~TIMED) | bit(preferredDelay);
    }
    static int allDaily(int preferredDelay) { return all(preferredDelay) | EXPERIENCES; }
    static boolean valid(int methods) {
        return methods != 0 && (methods & ~KNOWN) == 0 && (methods & TIMED) != TIMED;
    }
    static boolean validRest(int methods) { return valid(methods) && (methods & ~REST)==0; }
    static boolean validDaily(int methods) { return valid(methods); }
    static boolean validCombo(int methods) {
        return valid(methods) && (methods & ~COMBINABLE)==0 && Integer.bitCount(methods)>=2;
    }
    static int single(int methods) {
        return valid(methods) && Integer.bitCount(methods)==1 ? Integer.numberOfTrailingZeros(methods) : -1;
    }
    static int feedbackFor(int methods,int mode) {
        int allowed=Rule.allowedFeedback(mode);
        // In a pool NOW really opens then returns, even from an icon. A second-tap lock
        // is possible only on an intercepted icon, not on that foreground return.
        return mode==Rule.EXIT_NOW && methods!=bit(Rule.EXIT_NOW) ? allowed & ~FeedbackBasket.LOCK : allowed;
    }
    static int allowedFeedback(int methods) {
        int allowed=0;
        for (int m=Rule.EXIT_ICON; m<=Rule.EXIT_MUTE; m++)
            if ((methods & bit(m))!=0) allowed |= feedbackFor(methods,m);
        return allowed;
    }
    static boolean validFeedback(int methods,int feedback) {
        if (!valid(methods) || (feedback & ~allowedFeedback(methods))!=0) return false;
        for (int m=Rule.EXIT_ICON; m<=Rule.EXIT_MUTE; m++)
            if ((methods & bit(m))!=0 && feedbackFor(methods,m)!=0 && (feedback & feedbackFor(methods,m))==0) return false;
        return true;
    }
    /** Independent unbiased draw; repeated results on separate visits are possible. */
    static int draw(int eligible,Random random) {
        if (eligible==0) return -1;
        if (!valid(eligible)) throw new IllegalArgumentException("无效的方式组合");
        return Integer.numberOfTrailingZeros(RandomTickets.one(eligible,random));
    }
    private MethodPool() {}
}
