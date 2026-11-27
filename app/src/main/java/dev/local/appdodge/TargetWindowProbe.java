package dev.local.appdodge;

/** A target event or unresolved target root may request fresh reads, but never an action. */
final class TargetWindowProbe {
    private static final int[] AT_MS={64,160,360,720,1350,2400,4000};
    static final String DEPARTURE="<focus-change>";
    private String target;
    private long began;
    private int next;
    boolean arm(String candidate,long now,boolean newWindowEvent){
        if(candidate==null)return false;
        if(DEPARTURE.equals(candidate) && target!=null && !DEPARTURE.equals(target) && nextAt()>=0)return false;
        if(candidate.equals(target) && (next<AT_MS.length || !newWindowEvent))return false;
        target=candidate;began=now;next=0;return true;
    }
    String target(){return target;}
    long nextAt(){return target==null || next==AT_MS.length?-1:began+AT_MS[next];}
    void advance(){if(nextAt()>=0)next++;}
    void clear(){target=null;next=0;}
}
