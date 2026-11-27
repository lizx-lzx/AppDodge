package dev.local.appdodge;

/** The two random time ranges share one choice in the editor. */
enum DailyExitMethod {
    ICON(Rule.EXIT_ICON,"桌面提醒"),
    NOW(Rule.EXIT_NOW,"打开后退回桌面"),
    RANDOM(Rule.EXIT_SHORT,"随机退回桌面"),
    WAIT(Rule.EXIT_WAIT_OPEN,"延迟打开后退出"),
    ALTERNATE(Rule.EXIT_ALTERNATIVE,"换个去处"),
    REDIRECT(Rule.EXIT_REDIRECT,"自动改道"),
    LOCK(Rule.EXIT_LOCK_NOW,"直接锁屏"),
    LOCK_LATER(Rule.EXIT_LOCK_DELAY,"延时锁屏"),
    LOAD(Rule.EXIT_LOAD,"假装加载"),
    STALL(Rule.EXIT_STALL,"间歇卡顿"),
    NARROW(Rule.EXIT_NARROW,"视野缩窄"),
    MUTE(Rule.EXIT_MUTE,"强行消音");

    final int mode;
    final String label;
    DailyExitMethod(int mode,String label){this.mode=mode;this.label=label;}
    static DailyExitMethod[] displayOrder(boolean daily){
        return daily?new DailyExitMethod[]{LOAD,STALL,NARROW,MUTE,REDIRECT,ICON,NOW,RANDOM,WAIT,ALTERNATE,LOCK,LOCK_LATER}:values();
    }
    static DailyExitMethod forMode(int mode){
        if(Rule.delayed(mode))return RANDOM;
        for(DailyExitMethod method:values())if(method.mode==mode)return method;
        throw new IllegalArgumentException("未知拦截方式");
    }
    static String selectionName(int mask){
        int count=Integer.bitCount(mask);
        return count==1?forMode(Integer.numberOfTrailingZeros(mask)).label:"随机 "+count+" 选 1";
    }
    static String comboName(int mask){
        int n=Integer.bitCount(mask);
        if(n==2){
            StringBuilder parts=new StringBuilder();
            for(DailyExitMethod m:displayOrder(true)){
                int ticket=m==RANDOM?MethodPool.TIMED:MethodPool.bit(m.mode);
                if((mask&ticket)!=0){if(parts.length()>0)parts.append("＋");parts.append(m==RANDOM?"随机退回":m.label);}
            }
            return parts.toString();
        }
        return n+" 种中随机用 2～3 种";
    }
    static String scope(int mask){
        if(Integer.bitCount(mask)==1){
            int mode=Integer.numberOfTrailingZeros(mask);
            return forMode(mode).editorNote(mode);
        }
        return (mask & MethodPool.FOREGROUND)==0?"只拦原桌面上能识别的独立图标。"
                :"从其他入口进入，只抽能在应用全屏时执行的方式。";
    }
    String stage(){
        switch(this){
            case LOAD:case STALL:case NARROW:case MUTE:return "使用中";
            case ICON:case WAIT:case ALTERNATE:return "打开时";
            case NOW:case RANDOM:return "退出";
            case REDIRECT:case LOCK:case LOCK_LATER:return "去向";
            default:throw new AssertionError(this);
        }
    }
    String editorNote(int mode){
        switch(this){
            case ICON:return "只拦原桌面上能识别的独立图标。";
            case NOW:return "确认应用在前台后，立即退回桌面。";
            case RANDOM:return "只计在应用里的时间；离开不到 5 分钟，再进接着计。";
            case WAIT:return "点原图标后等 10 秒，随后 1 分钟内再点一次。进应用 1～2 秒后退回桌面。";
            case ALTERNATE:return "点原图标后，由你决定是否打开备选应用。";
            case REDIRECT:return ""; // Explained beside the destination.
            case LOAD:return "用加载画面盖住内容；可以直接离开。";
            case STALL:return "内容区会时而停住 4～6 秒，仍可退出。";
            case NARROW:return "画面逐渐收成一条窄缝，切走即撤掉。";
            case MUTE:return "目标在前台时静音；后台音乐也会暂时没声音。";
            case LOCK:return "单独抽到会立即锁屏。若和使用中方式同抽，先执行该方式，仅在目标仍在前台时锁屏。";
            case LOCK_LATER:return "点原图标后留在桌面，5～10 秒内整部手机会锁屏；离开桌面即取消。";
            default:throw new AssertionError(this);
        }
    }
    /** On-demand explanation beside the actual checkbox, not a separate catalogue. */
    String explanation(){
        switch(this){
            case ICON:return "点原桌面上能识别的图标时不打开，在桌面给一次所选反馈。";
            case NOW:return "日常抽到时会先打开应用，再退回桌面；在休息时段，点原图标就拦住。";
            case REDIRECT:return "单独抽到会转到备选应用；和使用中方式同抽，先执行该方式，再改道。备选不可用就留在桌面。";
            case RANDOM:return "选 0～1 分钟或 3～10 分钟，用完本轮抽到的前台时长就退回桌面。离开不到 5 分钟再进，不会重抽时长。";
            case LOAD:return "休息时在桌面转圈。其余时间在应用内遮住内容：首次 15 秒，短时间再进 30 秒。可随时离开。";
            case NARROW:return "上下遮幕在 20～40 秒内逐渐收拢。离开时撤下，短时间回来接着收。";
            default:return editorNote(mode);
        }
    }
    static String summary(Rule r,MainActivity a){
        if(r.combo)return comboName(r.methodMask);
        if(r.singleMode()<0)return selectionName(r.methodMask);
        DailyExitMethod method=forMode(r.singleMode());
        switch(method){
            case RANDOM:return "随机退回 · "+(r.hasMode(Rule.EXIT_SHORT)?"0～1分钟":"3～10分钟")+(r.curtain?" · 画面收拢":"");
            case ALTERNATE:case REDIRECT:return method.label+" · "+a.appLabel(r.alternatePackage);
            case WAIT:return "等 10 秒再打开 · 进入 1～2 秒后退回";
            case LOCK_LATER:return method.label+" · 5～10 秒";
            default:return method.label;
        }
    }
}
