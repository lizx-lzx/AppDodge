package dev.local.appdodge;

/** The rule behind a genuine desktop icon; a mixed rule draws only on an actual tap. */
final class NativeTarget {
    final Rule policy;
    final String packageName, label, notice;
    final int feedbackMask;
    private final int endMinute;
    final String alternate;
    final boolean interceptIcon;
    private NativeTarget(Rule rule,int end) {
        endMinute=end;policy=rule; packageName=rule.packageName; label=rule.label;
        notice=rule.byEffects() ? label+" · 本次触发负反馈" : rule.daily ? label+" · 日常负反馈" : end<0 ? label+" · 全天休息" : label+"休息到 "+Rule.time(end);
        feedbackMask=rule.feedbackMask; alternate=rule.alternatePackage;
        // A lone delay still lets the launcher start its own app without a pad.
        // A mixed rule needs its icon tap to choose between opening and intercepting.
        interceptIcon=rule.modernPolicy || rule.sharedRest() || !rule.combo && !Rule.delayed(rule.exitMode) && (rule.methodMask & ~MethodPool.EXPERIENCES)!=0;
    }
    static NativeTarget from(Rule rule) { return new NativeTarget(rule,rule.allowInside?rule.start:rule.end); }
    static NativeTarget from(Rule rule,int minute){return new NativeTarget(rule,rule.periods.endOfRestAt(minute));}
    boolean samePolicy(NativeTarget other) {
        return other!=null && policy.modernPolicy==other.policy.modernPolicy && policy.byEffects()==other.policy.byEffects() && java.util.Objects.equals(policy.sharedBasket,other.policy.sharedBasket) && policy.restCombo==other.policy.restCombo && policy.daily==other.policy.daily && notice.equals(other.notice)
                && policy.methodMask==other.policy.methodMask && feedbackMask==other.feedbackMask
                && policy.combo==other.policy.combo && policy.chance==other.policy.chance && policy.curtain==other.policy.curtain && alternate.equals(other.alternate);
    }
    NativeTarget selected(int mode) { return policy.exitMode==mode ? this : new NativeTarget(policy.forMode(mode),endMinute); }
}
