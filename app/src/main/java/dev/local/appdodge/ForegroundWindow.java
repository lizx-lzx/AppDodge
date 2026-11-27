package dev.local.appdodge;

import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RegionIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import android.os.SystemClock;
import android.os.Build;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/** Only the owning package of a single, full-screen, visibly focused application.
 * No subtree walk, content text, window titles, or retained names of other applications. */
final class ForegroundWindow {
    final String pkg;
    final int id, width, height;
    enum Cover {
        CLEAR, PARTIAL, MOSTLY_HIDDEN;
        static Cover fromArea(long content,long occluded) {
            if(content<=0)return MOSTLY_HIDDEN;
            if(occluded<=0)return CLEAR;
            // A focused application with at least one quarter still visible continues spending
            // its viewing allowance. Even a small foreign surface takes priority over our ink/touch.
            return content-occluded >= (content+3)/4 ? PARTIAL : MOSTLY_HIDDEN;
        }
        boolean canDrawOverContent() {return this==CLEAR;}
        boolean canCountViewing() {return this!=MOSTLY_HIDDEN;}
    }
    final Cover cover;
    final String lostReason; // coarse, content-free diagnostic; never includes other package names
    private ForegroundWindow(String pkg,int id,int width,int height,String lostReason,Cover cover) {
        this.cover=cover;
        this.pkg=pkg; this.id=id; this.width=width; this.height=height; this.lostReason=lostReason;
    }
    private ForegroundWindow(String reason) { this(null,-1,0,0,reason,Cover.MOSTLY_HIDDEN); }
    private static final ForegroundWindow SPLIT = new ForegroundWindow("split-or-pip");
    private static final ForegroundWindow SYSTEM = new ForegroundWindow("system-window");
    private static final ForegroundWindow PARTIAL = new ForegroundWindow("partial-window");
    private static final ForegroundWindow MIXED = new ForegroundWindow("mixed-owners");
    static final ForegroundWindow TRANSITION = new ForegroundWindow(null);

    static final ForegroundWindow SAME_APP_PANEL = new ForegroundWindow(null);
    static final ForegroundWindow SLIDING = new ForegroundWindow(null);
    /** Diagnostic only: never an authority for HOME. Only enrollments can be retained by name.
     * Keep the last target gap across the later launcher frame instead of logging every scan. */
    static final class Trace {
        private final Rect viewport=new Rect(), focusBounds=new Rect(), gapView=new Rect(), gapFocus=new Rect();
        private String candidate, why="not-scanned", gapTarget, gapWhy;
        private int apps,gapApps;
        private boolean active,gapActive,rotated;
        private long gapAt;
        String target(){return candidate;}
        String reason(){return why;}
        private void start(Rect usable){
            viewport.set(usable); focusBounds.setEmpty(); apps=0; active=false; rotated=false; candidate=null;
        }
        private void app(AccessibilityWindowInfo w,Rect box,String owner,Map<String,NativeTarget> enrolled){
            apps++;
            if(owner!=null && enrolled!=null && enrolled.containsKey(owner))candidate=owner;
            if(w.isFocused()){focusBounds.set(box); active=w.isActive();}
        }
        private void result(String reason,ForegroundWindow win){
            why=reason;
            if(candidate!=null && (win.pkg==null || !win.cover.canCountViewing())){
                gapTarget=candidate; gapWhy=why; gapApps=apps; gapActive=active;
                gapView.set(viewport); gapFocus.set(focusBounds); gapAt=SystemClock.elapsedRealtime();
            }
        }
        @Override public String toString(){
            return why+" rotatedViewport="+rotated+" appsSeen="+apps+" viewport="+viewport.toShortString()+" focusedBounds="+focusBounds.toShortString()+
                    " focusedActive="+active+" candidate="+candidate+
                    (gapTarget==null?"":" lastTargetGap="+gapTarget+":"+gapWhy+" gapAgeMs="+(SystemClock.elapsedRealtime()-gapAt)+
                    " gapAppsSeen="+gapApps+" gapViewport="+gapView.toShortString()+" gapFocusedBounds="+gapFocus.toShortString()+" gapFocusedActive="+gapActive);
        }
    }
    /** Some OEMs keep this service's WindowMetrics in portrait while the default display and
     * target rotate. Accept only an entire origin-anchored display with its exact axes swapped;
     * never infer size from aspect ratio, a translated animation, or the cutout-adjusted area. */
    static boolean exactQuarterTurn(int baseLeft,int baseTop,int baseWidth,int baseHeight,
                                    int left,int top,int width,int height) {
        return baseLeft==0 && baseTop==0 && left==0 && top==0 && baseWidth>0 && baseHeight>0
                && baseWidth!=baseHeight && width==baseHeight && height==baseWidth;
    }
    private static ForegroundWindow result(Trace trace,String reason,ForegroundWindow w) {
        if(trace!=null)trace.result(reason,w);
        return w;
    }
    static ForegroundWindow focused(NativeRestService service, WindowManager wm, MethodEntry entry) {
        return focused(service,wm,entry,null,null);
    }
    static ForegroundWindow focused(NativeRestService service, WindowManager wm, MethodEntry entry,Trace trace,Map<String,NativeTarget> enrolled) {
        return focused(service,wm,entry,trace,enrolled,false);
    }
    static ForegroundWindow forQuota(NativeRestService service,WindowManager wm,MethodEntry entry){
        return focused(service,wm,entry,null,null,true);
    }
    private static ForegroundWindow focused(NativeRestService service,WindowManager wm,MethodEntry entry,Trace trace,Map<String,NativeTarget> enrolled,boolean forQuota){
        WindowMetrics metrics = wm.getMaximumWindowMetrics();
        Rect full = metrics.getBounds(), usable = new Rect(full);
        Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        usable.inset(bars.left,bars.top,bars.right,bars.bottom);
        if(trace!=null)trace.start(usable);
        if (usable.isEmpty()) return result(trace,"empty-viewport",TRANSITION);
        AccessibilityWindowInfo front = null;
        String commonPackage = null, frontPkg = null;
        boolean missingRoot = false, translated = false, partial = false, ownActive=false;
        List<AccessibilityWindowInfo> occluders=null;
        int width=0, height=0;
        int appWindows = 0, nonFocusedPopups=0;
        Rect rotatedViewport = null;
        for (AccessibilityWindowInfo w : service.getWindows()) {
            if (w.getDisplayId() != Display.DEFAULT_DISPLAY) continue;
            int type = w.getType();
            if (type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER || w.isInPictureInPictureMode()) return result(trace,"split-or-pip",SPLIT);
            if (type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                if (type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && service.ownsEffectWindow(w.getId()) && !w.isFocused()) {
                    ownActive |= w.isActive(); continue; // a touch inside our content-only overlay may own touch, not app focus
                }
                if (w.isFocused() || w.isActive()) {
                    // Our own closing touch surface can briefly still be active. Never act
                    // from this frame, but don't mistake it for a call/notification interruption.
                    if (type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && service.ownsWindow(w.getId()))
                        return result(trace,"own-overlay-transition",TRANSITION);
                    return result(trace,"system-focus",SYSTEM);
                }
                // Clip later, in the coordinate space of the confirmed foreground. A right-side
                // landscape occluder does not necessarily intersect the stale portrait viewport.
                if (occluders==null)occluders=new ArrayList<>();
                occluders.add(w);
                continue;
            }
            appWindows++;
            Rect box = new Rect(); w.getBoundsInScreen(box);
            String pkg = box.isEmpty()?null:owner(w);
            if(trace!=null)trace.app(w,box,pkg,enrolled);
            if (pkg==null) { missingRoot=true; continue; }
            if (!box.contains(usable) && w.isFocused() && w.isActive()
                    && exactQuarterTurn(full.left,full.top,full.width(),full.height(),
                            box.left,box.top,box.width(),box.height())) {
                // Not a permission to act yet: below we still require a single application,
                // an identified root, no split/PIP, and no system/foreign focus.
                rotatedViewport=box;
            } else if (!box.contains(usable)) {
                // Activity transitions report a full-size incoming window translated on screen
                // (e.g. 1080 px wide with left=31). Only a visit already verified with that exact
                // app and viewport size may survive. Never act while the window is displaced.
                if (!Rect.intersects(box,usable)) return result(trace,"outside-viewport",PARTIAL);
                if(forQuota && !w.isFocused() && !w.isActive() && usable.contains(box) && box.width()<usable.width() && box.height()<usable.height())nonFocusedPopups++;
                else if (entry.sameExtent(pkg,box.width(),box.height())) translated=true;
                else partial=true;
            }
            if (commonPackage != null && !pkg.equals(commonPackage)) return result(trace,"mixed-owners",MIXED);
            else commonPackage=pkg;
            if (w.isFocused()) {
                front=w; frontPkg=pkg; width=box.width(); height=box.height();
            }
        }
        // During an Activity transition two full-size windows of the same application, or
        // briefly no usable root, may be present. Never act in that gap. A later confirmed
        // full-screen window for the same package continues its previous deadline.
        // A dialog owned by the app that already holds the visit is not an exit. All
        // visible app owners must agree (MIXED above); never act on the smaller window.
        if (partial) return entry.verifiedPackage(commonPackage) ? result(trace,"same-app-panel",SAME_APP_PANEL) : result(trace,"partial-viewport",PARTIAL);
        if (translated) return result(trace,"sliding",SLIDING);
        if (appWindows-nonFocusedPopups!=1 || missingRoot || front==null || frontPkg==null || !(front.isActive()||ownActive))
            return result(trace,appWindows==0?"no-app":missingRoot?"waiting-for-root":appWindows!=1?"multiple-app-windows":
                    front==null?"no-input-focus":"focused-not-active",TRANSITION);
        if(rotatedViewport!=null){
            // The host-context bars belonged to portrait. Use the actual landscape window and
            // the live regions of any windows above it, not those old insets or fake rotations.
            usable.set(rotatedViewport);
            if(trace!=null){trace.viewport.set(usable); trace.rotated=true;}
        }
        Cover cover=cover(usable,front.getLayer(),occluders);
        return result(trace,cover==Cover.CLEAR?"focused":cover==Cover.PARTIAL?"focused-partly-covered":"focused-mostly-hidden",
                new ForegroundWindow(frontPkg,front.getId(),width,height,null,cover));
    }
    private static Cover cover(Rect usable,int appLayer,List<AccessibilityWindowInfo> candidates) {
        if(candidates==null)return Cover.CLEAR;
        Region union=new Region(), part=new Region();
        for(AccessibilityWindowInfo w:candidates) {
            if(w.getLayer()<=appLayer)continue; // Behind the app is not an obstruction.
            w.getRegionInScreen(part);
            if(part.op(usable,Region.Op.INTERSECT))union.op(part,Region.Op.UNION);
        }
        if(union.isEmpty())return Cover.CLEAR;
        RegionIterator it=new RegionIterator(union);Rect rect=new Rect();long area=0;
        while(it.next(rect))area+=(long)rect.width()*rect.height();
        return Cover.fromArea((long)usable.width()*usable.height(),area);
    }
    boolean launchTransition() { return this==PARTIAL || this==MIXED; }
    private static String owner(AccessibilityWindowInfo w) {
        AccessibilityNodeInfo root = Build.VERSION.SDK_INT >= 33 ? w.getRoot(0) : w.getRoot();
        if (root == null) return null;
        try {
            CharSequence pkg=root.getPackageName();
            return pkg==null || root.getWindowId()!=w.getId() ? null : pkg.toString();
        } finally { if (Build.VERSION.SDK_INT < 33) root.recycle(); }
    }
}
