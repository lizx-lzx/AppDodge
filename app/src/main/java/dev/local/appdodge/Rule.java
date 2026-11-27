package dev.local.appdodge;

import java.util.Locale;

/** Pure local-time policy: start inclusive, end exclusive. */
public final class Rule {
    public final String packageName, label;
    public final int start, end;
    public final boolean allowInside;
    final RestPeriods periods;
    UseLimits limits=UseLimits.NONE; // Assigned once when hydrating the persisted enrollment, not a draw-mode property.
    final SharedBasket sharedBasket;
    final boolean restCombo,dayCombo;
    final boolean modernPolicy,restByEffects,dayByEffects;
    boolean byEffects(){return modernPolicy && (daily?dayByEffects:restByEffects);}
    boolean sharedRest() { return sharedBasket!=null && (modernPolicy?!byEffects():!daily); }
    boolean hardCombo(){return sharedRest() && (daily?dayCombo:restCombo);}
    int[][] restPeriods(){return periods.ranges();}
    String restRangeText(){return periods.text();}
    /** Explicit native foreground protection; older records default to false. */
    public final boolean returnHome;
    /** Stop the target only after an intercepted foreground visit has returned to Home. */
    final boolean stopAfterReturn;
    public static final int EXIT_ICON=0, EXIT_NOW=1, EXIT_SHORT=2, EXIT_LONG=3;
    public static final int EXIT_WAIT_OPEN=4, EXIT_ALTERNATIVE=5, EXIT_LOCK_NOW=6, EXIT_LOCK_DELAY=7, EXIT_REDIRECT=8;
    public static final int EXIT_LOAD=9, EXIT_STALL=10, EXIT_NARROW=11, EXIT_MUTE=12;
    public final int feedbackMask, methodMask;
    /** Opt-in plan saved alongside (never instead of) the previously configured rest choices. */
    final int dailyChance, dailyMask;
    final String dailyAlternatePackage;
    /** Runtime view: true only for an intervention in a normally permitted period. */
    final boolean daily, combo;
    final int chance;
    final Rule daytime;
    // Previous selection in the other draw mode; never returned by during().
    final Rule standby;
    private final Rule rest, daySettings;
    /** Editable preset from versions where enabling daily hid the chosen rest settings.
     * Neither this nor the inactive daily selection participates in during(). */
    final Rule previousRest;
    /** Legacy single-mode accessor: -1 for a pool. Call forMode after drawing a ticket. */
    public final int exitMode;
    public final String alternatePackage;
    public final boolean curtain;
    static boolean iconAction(int mode) { return mode >= EXIT_WAIT_OPEN && mode <= EXIT_REDIRECT; }
    static boolean delayed(int mode) { return mode == EXIT_SHORT || mode == EXIT_LONG; }
    static boolean validExit(int mode) { return mode >= EXIT_ICON && mode <= EXIT_MUTE; }
    static int allowedFeedback(int mode) {
        if (!validExit(mode) || iconAction(mode) || experience(mode)) return 0;
        return FeedbackBasket.ALL & ~(delayed(mode) ? FeedbackBasket.LOCK : FeedbackBasket.DURATION);
    }
    static boolean experience(int mode) { return mode>=EXIT_LOAD && mode<=EXIT_MUTE; }
    static boolean validChoices(int mode, int mask) {
        return MethodPool.validFeedback(MethodPool.bit(mode),mask);
    }
    public Rule(String packageName, String label, int start, int end, boolean allowInside) {
        this(packageName, label, start, end, allowInside, false);
    }
    Rule(String packageName, String label, int start, int end, boolean allowInside, boolean returnHome) {
        this(packageName, label, start, end, allowInside, returnHome, FeedbackBasket.LOCK);
    }
    Rule(String packageName, String label, int start, int end, boolean allowInside, boolean returnHome, int feedbackMask) {
        this(packageName,label,start,end,allowInside,returnHome ? EXIT_NOW : EXIT_ICON,feedbackMask);
    }
    Rule(String packageName, String label, int start, int end, boolean allowInside, int exitMode, int feedbackMask) {
        this(packageName,label,start,end,allowInside,exitMode,feedbackMask,"",false);
    }
    Rule(String packageName,String label,int start,int end,boolean allowInside,int exitMode,int feedbackMask,
            String alternatePackage,boolean curtain) {
        this(packageName,label,start,end,allowInside,alternatePackage,curtain,MethodPool.bit(exitMode),feedbackMask);
    }
    /** Entry-level UI preset; never use to reconstruct the effective policy of an older installation. */
    static Rule newAppRest(String pkg,String label,int start,int end,boolean allow) {
        return new Rule(pkg,label,start,end,allow,EXIT_NOW,FeedbackBasket.NEW_DEFAULT);
    }
    /** Historical immediate-rest policy, including 0.10 daily-rule migration in Store. */
    static Rule defaultRest(String pkg,String label,int start,int end,boolean allow) {
        return new Rule(pkg,label,start,end,allow,EXIT_NOW,FeedbackBasket.NOTICE);
    }
    static Rule pool(String pkg,String label,int start,int end,boolean allow,int methods,int feedback,String alternate,boolean curtain) {
        return new Rule(pkg,label,start,end,allow,alternate,curtain,methods,feedback);
    }
    private Rule(String pkg,String label,int start,int end,boolean allow,String alternate,boolean curtain,int methods,int feedback) {
        this(pkg,label,start,end,allow,alternate,curtain,methods,feedback,false,100,false);
    }
    private Rule(String pkg,String label,int start,int end,boolean allow,String alternate,boolean curtain,int methods,int feedback,
                 boolean daily,int chance,boolean combo) {
        this(pkg,label,start,end,allow,alternate,curtain,methods,feedback,daily,chance,combo,
                RestPeriods.single(allow?end:start,allow?start:end),null,false,false);
    }
    private Rule(String pkg,String label,int start,int end,boolean allow,String alternate,boolean curtain,int methods,int feedback,
                 boolean daily,int chance,boolean combo,RestPeriods periods,SharedBasket shared,boolean restCombo,boolean dayCombo) {
        this(pkg,label,start,end,allow,alternate,curtain,methods,feedback,daily,chance,combo,periods,shared,restCombo,dayCombo,false,false,true);
    }
    private Rule(String pkg,String label,int start,int end,boolean allow,String alternate,boolean curtain,int methods,int feedback,
                 boolean daily,int chance,boolean combo,RestPeriods periods,SharedBasket shared,boolean restCombo,boolean dayCombo,
                 boolean modern,boolean restFx,boolean dayFx) {
        this(pkg,label,start,end,allow,alternate,curtain,methods,feedback,daily,chance,combo,periods,shared,restCombo,dayCombo,modern,restFx,dayFx,false);
    }
    private Rule(String pkg,String label,int start,int end,boolean allow,String alternate,boolean curtain,int methods,int feedback,
                 boolean daily,int chance,boolean combo,RestPeriods periods,SharedBasket shared,boolean restCombo,boolean dayCombo,
                 boolean modern,boolean restFx,boolean dayFx,boolean stopAfterReturn) {
        this.stopAfterReturn=stopAfterReturn;
        modernPolicy=modern;restByEffects=restFx;dayByEffects=dayFx;
        this.periods=periods;this.sharedBasket=shared;this.restCombo=restCombo;this.dayCombo=dayCombo;
        if (modern?!MethodPool.valid(methods):!(daily ? (combo ? MethodPool.validCombo(methods) : MethodPool.validDaily(methods)) : !combo && (MethodPool.validRest(methods) || shared!=null && MethodPool.valid(methods) && (methods&~SharedBasket.DIRECT)==0)))
            throw new IllegalArgumentException("方式与时段不匹配");
        if (pkg==null || pkg.isEmpty() || start<0 || start>=1440 || end<0 || end>=1440 || start==end)
            throw new IllegalArgumentException("开始和结束时间必须不同");
        if (shared==null && !MethodPool.validFeedback(methods,feedback) || shared!=null && (feedback & ~FeedbackBasket.ALL)!=0) throw new IllegalArgumentException("请选择与方式匹配的反馈");
        this.methodMask=methods; this.exitMode=MethodPool.single(methods); this.feedbackMask=feedback;
        this.alternatePackage=alternate==null ? "" : alternate;
        if (((methods & MethodPool.ALTERNATIVES)!=0) != !this.alternatePackage.isEmpty() || this.alternatePackage.equals(pkg)
                || curtain && (methods & MethodPool.TIMED)==0)
            throw new IllegalArgumentException("请选择与此方式匹配的设置");
        this.packageName=pkg; this.label=label; this.start=start; this.end=end; this.allowInside=allow;
        this.returnHome=modern || shared!=null && !daily || (methods & MethodPool.FOREGROUND)!=0; this.curtain=curtain;
        this.daily=daily; this.chance=chance; this.combo=combo;
        this.dailyChance=this.dailyMask=0; this.dailyAlternatePackage="";
        this.daytime=this.standby=this.rest=this.daySettings=this.previousRest=null;
    }
    private Rule(Rule base,Rule day,boolean enabled,Rule standby,Rule previousRest) {
        stopAfterReturn=base.stopAfterReturn;modernPolicy=base.modernPolicy;restByEffects=base.restByEffects;dayByEffects=base.dayByEffects;
        rest=base.restPolicy();periods=rest.periods;sharedBasket=rest.sharedBasket;restCombo=rest.restCombo;dayCombo=rest.dayCombo;
        packageName=rest.packageName; label=rest.label; start=rest.start; end=rest.end; allowInside=rest.allowInside;
        alternatePackage=rest.alternatePackage; curtain=rest.curtain; returnHome=rest.returnHome;
        methodMask=rest.methodMask; feedbackMask=rest.feedbackMask; exitMode=rest.exitMode;
        daily=false; chance=100; this.daySettings=withStop(day,stopAfterReturn); this.daytime=enabled?daySettings:null; this.standby=withStop(standby,stopAfterReturn);
        this.previousRest=withStop(previousRest,stopAfterReturn);
        combo=daytime!=null && daytime.combo;
        dailyChance=daytime==null?0:daytime.chance; dailyMask=daytime==null?0:daytime.methodMask;
        dailyAlternatePackage=daytime==null?"":daytime.alternatePackage;
    }
    private static Rule withStop(Rule r,boolean enabled){return r==null?null:r.withStopAfterReturn(enabled);}
    Rule withStopAfterReturn(boolean enabled){
        if(stopAfterReturn==enabled)return this;
        if(rest!=null)return new Rule(rest.withStopAfterReturn(enabled),daySettings,daytime!=null,standby,previousRest);
        return new Rule(packageName,label,start,end,allowInside,alternatePackage,curtain,methodMask,feedbackMask,
                daily,chance,combo,periods,sharedBasket,restCombo,dayCombo,modernPolicy,restByEffects,dayByEffects,enabled);
    }
    /** Persist only basket + periods + draw settings; these views are runtime projections. */
    static Rule shared(String pkg,String label,RestPeriods periods,SharedBasket basket,int chance,boolean restCombo,boolean dayCombo) {
        if(basket==null || chance<0 || chance>100 || restCombo && !basket.canRestCombo() || dayCombo && !basket.canDayCombo()
                || basket.alternate.equals(pkg)) throw new IllegalArgumentException("组合或概率不匹配");
        int[] times=periods.ranges()[0]; int direct=restCombo?MethodPool.bit(EXIT_NOW):basket.restIconMethods();
        Rule rest=new Rule(pkg,label,times[0],times[1],false,(direct&MethodPool.ALTERNATIVES)!=0?basket.alternate:"",
                false,direct,basket.feedback & ~FeedbackBasket.DURATION,false,100,false,periods,basket,restCombo,dayCombo);
        int dayMethods=basket.dayMethods(dayCombo);
        Rule day=new Rule(pkg,label,times[0],times[1],false,(dayMethods&MethodPool.ALTERNATIVES)!=0?basket.alternate:"",
                basket.curtain&&(dayMethods&MethodPool.TIMED)!=0,dayMethods,basket.feedback,true,chance,dayCombo,
                periods,basket,restCombo,dayCombo);
        return new Rule(rest,day,chance!=0,null,null);
    }
    static Rule shared(String pkg,String label,RestPeriods periods,SharedBasket b,int probability,boolean rc,boolean dc,boolean rf,boolean df) {
        if(b==null || probability<0 || probability>100 || rc && rf && !b.canEffectCombo()
                || dc && !(df?b.canEffectCombo():b.canRestCombo()) || b.alternate.equals(pkg))throw new IllegalArgumentException("方式与选取数量不匹配");
        int[] first=periods.ranges()[0];
        Rule[] view=new Rule[2];
        for(int i=0;i<2;i++){
            boolean day=i==1,fx=day?df:rf,multi=day?dc:rc;
            int m=fx?b.methods:multi?MethodPool.bit(EXIT_NOW):b.restIconMethods();
            view[i]=new Rule(pkg,label,first[0],first[1],false,(m&MethodPool.ALTERNATIVES)!=0?b.alternate:"",
                    (m&MethodPool.TIMED)!=0 && b.curtain,m,b.feedback,day,day?probability:100,fx&&multi,
                    periods,b,rc,dc,true,rf,df);
        }
        return new Rule(view[0],view[1],probability>0,null,null);
    }
    private Rule withPeriodView(RestPeriods times) {
        int[] first=times.ranges()[0];
        return new Rule(packageName,label,first[allowInside?1:0],first[allowInside?0:1],allowInside,
                alternatePackage,curtain,methodMask,feedbackMask,daily,chance,combo,times,null,false,false);
    }
    Rule reschedule(RestPeriods times) {
        if(periods.equals(times))return this;
        if(modernPolicy)return shared(packageName,label,times,sharedBasket,dailyChance,restCombo,dayCombo,restByEffects,dayByEffects).withStopAfterReturn(stopAfterReturn);
        if(sharedBasket!=null)return shared(packageName,label,times,sharedBasket,dailyChance,restCombo,dayCombo).withStopAfterReturn(stopAfterReturn);
        Rule base=restPolicy().withPeriodView(times).withStopAfterReturn(stopAfterReturn);
        if(daySettings==null && previousRest==null)return base;
        return new Rule(base,daySettings==null?null:daySettings.withPeriodView(times),daytime!=null,
                standby==null?null:standby.withPeriodView(times),previousRest==null?null:previousRest.withPeriodView(times));
    }
    Rule restPolicy() { return rest==null ? this : rest; }
    Rule preferredDaily() { return daySettings; }
    Rule modeSettings(boolean wantCombo) {
        return daySettings!=null && daySettings.combo==wantCombo ? daySettings : standby;
    }
    Rule withDaily(int probability,int methods,int feedback,String alternate,boolean curtainEnabled) {
        return withDaily(probability,methods,feedback,alternate,curtainEnabled,false);
    }
    Rule withDaily(int probability,int methods,int feedback,String alternate,boolean curtainEnabled,boolean comboMode) {
        if (daily || probability<0 || probability>100) throw new IllegalArgumentException("日常概率必须在 0%～100% 之间");
        if (probability==0) {
            if (methods!=0 || feedback!=0 || (alternate!=null && !alternate.isEmpty()) || curtainEnabled || comboMode)
                throw new IllegalArgumentException("未启用日常反馈");
            return daytime==null ? this : new Rule(this,daySettings,false,standby,previousRest);
        }
        Rule day=new Rule(packageName,label,start,end,allowInside,alternate,curtainEnabled,methods,feedback,true,probability,comboMode);
        return new Rule(this,day,true,modeSettings(!comboMode),previousRest);
    }
    Rule withStandby(int methods,int feedback,String alternate,boolean curtain) {
        if (daySettings==null) throw new IllegalArgumentException("没有日常设置");
        Rule inactive=new Rule(packageName,label,start,end,allowInside,alternate,curtain,methods,feedback,true,daySettings.chance,!daySettings.combo);
        return new Rule(this,daySettings,daytime!=null,inactive,previousRest);
    }
    /** Change the schedule or effective rest selection without modifying independent daily settings. */
    Rule withRest(Rule base) {
        if (!packageName.equals(base.packageName) || base.daily) throw new IllegalArgumentException("休息方式不匹配");
        base=base.withStopAfterReturn(stopAfterReturn);
        Rule prior=base.copySettings(previousRest);
        if (base.sameSettings(prior)) prior=null; // user has expressly restored the old preset
        if (daySettings==null && prior==null) return base.restPolicy();
        return new Rule(base,base.copySettings(daySettings),daytime!=null,base.copySettings(standby),prior);
    }
    Rule withPreviousRest(Rule previous) {
        if (previous.daily || !packageName.equals(previous.packageName)) throw new IllegalArgumentException("升级前休息方式不匹配");
        return new Rule(this,daySettings,daytime!=null,standby,restPolicy().sameSettings(previous)?null:copySettings(previous));
    }
    private boolean sameSettings(Rule other) {
        return other!=null && methodMask==other.methodMask && feedbackMask==other.feedbackMask && curtain==other.curtain
                && alternatePackage.equals(other.alternatePackage);
    }
    private Rule copySettings(Rule template) {
        if (template==null) return null;
        return new Rule(packageName,label,start,end,allowInside,template.alternatePackage,template.curtain,template.methodMask,
                template.feedbackMask,template.daily,template.chance,template.combo);
    }
    Rule during(int minute) { return allows(minute) ? daytime : restPolicy(); }
    /** Stored presets are kept verbatim for editing/migration. All enforcement goes through this
     * projection: resting never opens the target; hits outside rest always have a stopping point. */
    private Rule enforcement;
    Rule enforcedAt(int minute) {
        Rule v=during(minute);
        if(v==null || v.modernPolicy && (v.daily || v.sharedRest()))return v;
        if(v.enforcement==null){
            SharedBasket basket=v.sharedBasket==null?SharedBasket.fromPolicy(v):v.sharedBasket;
            boolean effects=v.daily, multi=effects?v.combo:v.restCombo;
            int methods=effects?basket.methods:multi?MethodPool.bit(EXIT_NOW):basket.restIconMethods();
            v.enforcement=new Rule(v.packageName,v.label,v.start,v.end,v.allowInside,
                    (methods&MethodPool.ALTERNATIVES)==0?"":basket.alternate,
                    (methods&MethodPool.TIMED)!=0 && basket.curtain,methods,basket.feedback,
                    v.daily,v.chance,effects&&multi,v.periods,basket,v.restCombo,v.dayCombo,true,false,true,v.stopAfterReturn);
        }
        return v.enforcement;
    }
    boolean hasMode(int mode) { return (methodMask & MethodPool.bit(mode))!=0; }
    int singleMode() { return exitMode; }
    Rule forMode(int mode) {
        boolean fallback=sharedRest() && mode==EXIT_NOW;
        if (!fallback && !hasMode(mode)) throw new IllegalArgumentException("方式未勾选");
        if (exitMode==mode) return rest==null ? this : rest;
        return new Rule(packageName,label,start,end,allowInside,
                (MethodPool.bit(mode) & MethodPool.ALTERNATIVES)!=0 ? alternatePackage : "", delayed(mode) && curtain,
                MethodPool.bit(mode),feedbackMask & (sharedRest()&&mode==EXIT_NOW?allowedFeedback(mode):MethodPool.feedbackFor(methodMask,mode)),
                daily,chance,false,periods,sharedBasket,restCombo,dayCombo,modernPolicy,restByEffects,dayByEffects,stopAfterReturn);
    }
    public boolean allows(int minute) {
        if (minute < 0 || minute >= 1440) throw new IllegalArgumentException("Invalid minute");
        return limits.noSchedule || !periods.contains(minute);
    }
    public static String time(int minute) { return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60); }
    public String range() { return time(start) + " — " + (end < start ? "次日 " : "") + time(end); }
}
