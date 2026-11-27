package dev.local.appdodge;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Random;

/** Fullscreen daily effects. A callback requests a fresh foreground check; it never uses a
 * previously authorized package to newly hide a screen, block a tap or change the volume. */
final class ExperienceEffects {
    private final NativeRestService service;
    private final WindowManager windows;
    private final ExperienceGroup group;
    private final MediaSilence silence;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable verify;
    private final Rect overlayBounds=new Rect(), hitBounds=new Rect();
    private Ink ink;
    private View blocker;
    private long next=Long.MAX_VALUE;
    private int active,reported;
    int newlyApplied(){int fresh=active&~reported;reported|=fresh;return fresh;}
    private boolean unavailable;
    private static final int BARS=WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout();
    private static final int GESTURES=WindowInsets.Type.systemGestures()|WindowInsets.Type.mandatorySystemGestures();
    ExperienceEffects(NativeRestService service,Runnable rescan,Random random) {
        group=new ExperienceGroup(random);
        this.service=service;windows=service.getSystemService(WindowManager.class);
        silence=new MediaSilence(service);
        verify=()->{next=Long.MAX_VALUE;rescan.run();};
    }
    void show(String pkg,int mode) {showGroup(pkg,MethodPool.bit(mode));}
    boolean pastLoading() {return group.pastLoading();}
    boolean contentExperienced() {return group.contentExperienced();}
    boolean contentUnavailable() {return unavailable;}
    void showGroup(String pkg,int experienceMask) {showGroup(pkg,experienceMask,true);}
    void showGroup(String pkg,int experienceMask,boolean visualsAllowed) {
        long now=SystemClock.elapsedRealtime();
        if(!group.same(pkg,experienceMask))leave();
        int previous=active;
        group.advance(pkg,experienceMask,now,visualsAllowed);
        active=group.active();
        if((active&ExperienceGroup.MUTE)!=0) silence.enforce(); else silence.release();
        boolean visual=(active&(ExperienceGroup.LOAD|ExperienceGroup.NARROW))!=0;
        boolean blocks=(active&(ExperienceGroup.LOAD|ExperienceGroup.STALL))!=0;
        if(visual || blocks) {
            WindowMetrics screen=windows.getMaximumWindowMetrics();
            Rect display=screen.getBounds();
            WindowInsets wi=screen.getWindowInsets();
            Insets bars=wi.getInsetsIgnoringVisibility(BARS);
            Rect content=new Rect(display);
            content.inset(bars.left,bars.top,bars.right,bars.bottom);
            if(!content.isEmpty() && visual) {
                setInk(content);
                // The same surface can switch from opaque spinner to transparent curtain.
                if(ink!=null && previous!=active)ink.invalidate();
            } else removeInk();
            if(blocks && !content.isEmpty()) {
                Insets sys=wi.getInsetsIgnoringVisibility(GESTURES|BARS);
                // Reserve edge navigation even on launchers reporting zero gesture insets.
                int edge=Math.round(service.getResources().getDisplayMetrics().density*24);
                Rect hit=new Rect(display);
                hit.inset(Math.max(edge,sys.left),Math.max(edge,sys.top),
                        Math.max(edge,sys.right),Math.max(edge,sys.bottom));
                if(!hit.isEmpty())setBlocker(hit);else removeBlocker();
            } else removeBlocker();
        } else {removeBlocker();removeInk();}
        // A fixed-volume output or rejected overlay must not keep a selected exit timer
        // waiting forever. A covered content area is deliberately suspended, not a failure.
        unavailable=(visual && ink==null) || (blocks && blocker==null)
                || ((active&ExperienceGroup.MUTE)!=0 && !silence.owns());
        if(ink==null)active&=~(ExperienceGroup.LOAD|ExperienceGroup.NARROW);
        if(blocker==null)active&=~ExperienceGroup.STALL;
        if(!silence.owns())active&=~ExperienceGroup.MUTE;
        group.observe(active,now);
        // For a combined session, recheck at the earliest visual, input or media deadline.
        long due=group.nextEdge();
        if((active&ExperienceGroup.MUTE)!=0)due=Math.min(due,now+250);
        if(next!=Long.MAX_VALUE && next<=due && next>=now)return;
        handler.removeCallbacks(verify);next=due;
        if(due!=Long.MAX_VALUE)handler.postDelayed(verify,Math.max(1,due-now));
    }
    /** The foreground is temporarily unverified. Release every side effect immediately, but
     * don't end this visit or create a fast-reentry record. The same app's next verified
     * frame resumes the original monotonic timeline (including an expired loading screen). */
    void suspend() {
        handler.removeCallbacks(verify);next=Long.MAX_VALUE;
        removeBlocker();removeInk();silence.release();
        active=0;unavailable=false;group.suspend();
    }
    void leave() {
        suspend();reported=0;group.leave(SystemClock.elapsedRealtime());
    }
    void reset() {leave();group.reset(SystemClock.elapsedRealtime());}
    boolean ownsWindow(int id) { return belongs(ink,id)||belongs(blocker,id); }
    private boolean belongs(View v,int id) {
        if(v==null || !v.isAttachedToWindow() || id<0)return false;
        AccessibilityNodeInfo n=v.createAccessibilityNodeInfo();
        if(n==null)return false;
        try{return n.getWindowId()==id;}finally{if(Build.VERSION.SDK_INT<33)n.recycle();}
    }
    String diagnostic() {
        long now=SystemClock.elapsedRealtime();
        int phase=(active&ExperienceGroup.LOAD)!=0 ? ExperienceSchedule.LOADING
                : (active&ExperienceGroup.STALL)!=0 ? ExperienceSchedule.FROZEN
                : (active&ExperienceGroup.NARROW)!=0 ? ExperienceSchedule.NARROW
                : (active&ExperienceGroup.MUTE)!=0 ? ExperienceSchedule.MUTED : ExperienceSchedule.OPEN;
        return "effectMode="+group.singleMode()+" effectPhase="+phase+" effectMask="+group.mask()
                +" effectActiveMask="+active+" effectInk="+(ink!=null)
                +" effectTouch="+(blocker!=null)+" effectMediaLease="+silence.owns()
                +" effectNarrowPct="+Math.round(100*group.fraction(now))
                +" effectNextMs="+(next==Long.MAX_VALUE?-1:Math.max(0,next-now));
    }
    private WindowManager.LayoutParams params(Rect b,boolean touchable) {
        int flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if(!touchable)flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(b.width(),b.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,flags,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.LEFT|Gravity.TOP;p.x=b.left;p.y=b.top;
        p.setFitInsetsTypes(0);p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle(touchable?"反人性软件 · 内容区暂停":"反人性软件 · 日常反馈");
        return p;
    }
    private void setInk(Rect b) {
        if(ink!=null && overlayBounds.equals(b))return;
        removeInk(); overlayBounds.set(b);
        Ink v=new Ink();ink=v;
        try {windows.addView(v,params(b,false));}
        catch(WindowManager.BadTokenException | WindowManager.InvalidDisplayException e) {
            ink=null;Log.w(NativeRestService.TAG,"daily_ink_unavailable");
        }
    }
    private void setBlocker(Rect b) {
        if(blocker!=null && hitBounds.equals(b))return;
        removeBlocker();hitBounds.set(b);
        View v=new View(service);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Only the central content area takes input; the visual layer is independently non-touchable.
        v.setOnTouchListener((view,event)->true);
        blocker=v;
        try {windows.addView(v,params(b,true));}
        catch(WindowManager.BadTokenException | WindowManager.InvalidDisplayException e) {
            blocker=null;Log.w(NativeRestService.TAG,"daily_touch_unavailable");
        }
    }
    private void removeInk(){Ink old=ink;ink=null;if(old!=null && old.getParent()!=null)windows.removeViewImmediate(old);}
    private void removeBlocker(){View old=blocker;blocker=null;if(old!=null && old.getParent()!=null)windows.removeViewImmediate(old);}
    private final class Ink extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density=service.getResources().getDisplayMetrics().density;
        Ink(){super(service);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas) {
            long now=SystemClock.elapsedRealtime();
            int w=getWidth(),h=getHeight();
            if((active&ExperienceGroup.LOAD)!=0) {
                canvas.drawColor(0xff141714);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(2.5f*density);float x=w/2f,y=h/2f,r=15*density;
                p.setColor(0xff3b423b);canvas.drawCircle(x,y,r,p);p.setColor(0xffc5cbc5);
                boolean animate=ValueAnimator.areAnimatorsEnabled();
                float start=animate?(now%1000)*.36f-90:-90;
                canvas.drawArc(x-r,y-r,x+r,y+r,start,90,false,p);
                if(animate)postInvalidateDelayed(32);
            } else if((active&ExperienceGroup.NARROW)!=0) {
                float t=group.fraction(now), k=t*t*(3-2*t);
                float slit=Math.min(h,Math.max(16*density,.065f*h));
                float side=.5f*(h-slit)*k;
                p.setStyle(Paint.Style.FILL);p.setColor(0xff101210);
                canvas.drawRect(0,0,w,side,p);canvas.drawRect(0,h-side,w,h,p);
                if(t<1)postInvalidateDelayed(ValueAnimator.areAnimatorsEnabled()?50:500);
            }
        }
    }
}
