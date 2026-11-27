package dev.local.appdodge;

/** Independent of feedback selection and probability. Zero disables a limit. */
final class UseLimits {
    static final UseLimits NONE=new UseLimits(0,0,0,false);
    final int session,day,rest; final boolean noSchedule;
    UseLimits(int session,int day,int rest,boolean noSchedule){
        if(session<0||session>1440||day<0||day>1440||rest<0||rest>1440||session==0&&rest>0)
            throw new IllegalArgumentException("请输入 1～1440 分钟；不限制可留空");
        this.session=session;this.day=day;this.rest=rest;this.noSchedule=noSchedule;
    }
    boolean timed(){return session>0||day>0;}
    static String minutes(int n){return n==0?"不限":n+" 分钟";}
    String summary(){return !timed()?"未设置":(session>0?"单次 "+session+" 分钟":"")+
        (session>0&&day>0?" · ":"")+(day>0?"每天 "+day+" 分钟":"");}
    public String toString(){return session+":"+day+":"+rest+":"+noSchedule;}
}
