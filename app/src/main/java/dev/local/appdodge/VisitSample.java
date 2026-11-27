package dev.local.appdodge;

/** Uninterrupted visit fallback when there is no resumable policy clock. Unknown frames pause it. */
final class VisitSample {
    String pkg;long elapsed,since=-1;
    void pause(long now){if(since>=0)elapsed+=Math.max(0,now-since);since=-1;}
    void leave(){pkg=null;elapsed=0;since=-1;}
    void see(String target,long now){if(!target.equals(pkg)){leave();pkg=target;}if(since<0)since=now;}
    long value(long now){return elapsed+(since>=0?Math.max(0,now-since):0);}
}
