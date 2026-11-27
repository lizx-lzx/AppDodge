package dev.local.appdodge;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.icu.text.MessageFormat;
import java.util.Locale;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves the *current* icon, never stores page coordinates, never reads another app's subtree. */
final class NativeIconFinder {
    static final class Hit {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        Hit(AccessibilityNodeInfo n, Rect r) { node = n; bounds = r; }
    }
    WakeProbeEdges.Focus focus = WakeProbeEdges.Focus.UNKNOWN;
    private String lastHome;
    private long lastEmptyRefresh = -1000;
    int scans, emptyRefreshes, refreshRecovered;
    long lastScanAt;
    boolean fromHome(CharSequence pkg) { return lastHome != null && lastHome.contentEquals(nonNull(pkg)); }
    String lastScan="idle"; // non-identifying, read only through the system service dump
    private Map<String,Hit> none(String why) { lastScan=why; return Collections.emptyMap(); }
    private static final String PIXEL_HOME = "com.google.android.apps.nexuslauncher";
    private final Map<String,String> pausedLabels = new HashMap<>();
    private Resources pixelResources;
    private int pausedLabelId;
    private boolean lookedUpPixelFormats;
    private MessageFormat dottedFormat;
    private final Map<String,String> matchedBadge = new HashMap<>();
    private final AccessibilityService service;
    private final Map<String, Set<String>> labelOwners = new HashMap<>();
    NativeIconFinder(AccessibilityService service) { this.service = service; reload(); }

    // Overview is often owned by the very same package as Home. Never use a package
    // match alone as proof that the user is on their desktop for a privileged action.
    private static final String[] NON_WORKSPACE={"deep_shortcuts_container","popup_container","folder","apps_view","drop_target_bar"};
    private static boolean showing(AccessibilityNodeInfo root,String home,String id){
        for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByViewId(home+":id/"+id))
            if(n.isVisibleToUser())return true;
        return false;
    }
    boolean isActualWorkspace(ForegroundWindow focused) {
        if(focused.pkg==null || !fromHome(focused.pkg) || !focused.cover.canCountViewing())return false;
        ResolveInfo ri=service.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
        if(ri==null || ri.activityInfo==null || !focused.pkg.equals(ri.activityInfo.packageName))return false;
        for(AccessibilityWindowInfo w:service.getWindows()){
            if(w.getId()!=focused.id || w.getDisplayId()!=android.view.Display.DEFAULT_DISPLAY || !w.isFocused()
                    || w.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION || w.isInPictureInPictureMode())continue;
            AccessibilityNodeInfo root=Build.VERSION.SDK_INT>=33?w.getRoot(0):w.getRoot();
            if(root==null)return false;
            try{
                if(root.getWindowId()!=w.getId() || !focused.pkg.contentEquals(nonNull(root.getPackageName())))return false;
                List<AccessibilityNodeInfo> pages=root.findAccessibilityNodeInfosByViewId(focused.pkg+":id/workspace");
                if(pages.size()!=1 || !pages.get(0).isVisibleToUser())return false;
                for(String id:NON_WORKSPACE)if(showing(root,focused.pkg,id))return false;
                for(String id:new String[]{"overview_panel","recents_view","task_view"})
                    if(showing(root,focused.pkg,id))return false;
                return true;
            }finally{if(Build.VERSION.SDK_INT<33)root.recycle();}
        }
        return false;
    }

    void reload() {
        PackageManager pm = service.getPackageManager();
        labelOwners.clear();
        pausedLabels.clear(); matchedBadge.clear(); pixelResources=null; pausedLabelId=0; dottedFormat=null; lookedUpPixelFormats=false;
        for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (info.activityInfo == null) continue;
            CharSequence label = info.loadLabel(pm);
            if (label != null) labelOwners.computeIfAbsent(label.toString(), k -> new HashSet<>())
                    .add(info.activityInfo.packageName);
        }
    }

    Map<String,Hit> visible(List<NativeTarget> denied) {return visible(denied,false);}
    Map<String,Hit> visible(List<NativeTarget> denied,boolean observeHome) {
        scans++; lastScanAt = SystemClock.uptimeMillis(); focus = WakeProbeEdges.Focus.UNKNOWN;
        if (denied.isEmpty() && !observeHome) return none("no active rule");
        ResolveInfo ri = service.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return none("no default home");
        String home = ri.activityInfo.packageName;
        lastHome = home;
        if (home.equals(service.getPackageName())) return none("own application is home");
        List<AccessibilityWindowInfo> windows = service.getWindows();
        // Android 14's getWindows() uses the service's window cache. An empty result after
        // unlocking may be a cached keyguard transition, not an empty default display.
        if (windows.isEmpty() && Build.VERSION.SDK_INT >= 33 && lastScanAt - lastEmptyRefresh >= 64) {
            lastEmptyRefresh = lastScanAt; emptyRefreshes++;
            if (service.clearCache()) {
                windows = service.getWindows();
                if (!windows.isEmpty()) refreshRecovered++;
            }
        }
        AccessibilityWindowInfo front = null;
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && w.isFocused()) front = w;
            // Touching our own non-focusable overlay may temporarily make it the active touch window.
            if (w.isActive() && w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                    && w.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return none("system window is active");
        }
        if (front == null) return none("no focused application window; windows=" + windows.size());
        AccessibilityNodeInfo root = front.getRoot();
        if (root == null) return none("missing foreground root");
        if (!home.contentEquals(nonNull(root.getPackageName()))) {
            focus = WakeProbeEdges.Focus.ELSEWHERE; return none("foreground is not default home");
        }
        focus = WakeProbeEdges.Focus.HOME;
        List<AccessibilityNodeInfo> workspaces = root.findAccessibilityNodeInfosByViewId(home + ":id/workspace");
        if (workspaces.size() != 1 || !workspaces.get(0).isVisibleToUser()) return none("visible workspace not found; matches=" + workspaces.size());
        // Shortcuts menu, open folder, and app drawer are not the workspace, even if a launcher
        // keeps the underlying workspace visible to accessibility during the transition.
        for(String id:NON_WORKSPACE)if(showing(root,home,id))return none("non-workspace panel: "+id);
        Rect viewport = new Rect(); front.getBoundsInScreen(viewport);
        ArrayList<Rect> occluded = new ArrayList<>();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getLayer() <= front.getLayer() || w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            Rect b = new Rect(); w.getBoundsInScreen(b); occluded.add(b);
        }
        Map<String,Hit> found = new HashMap<>();
        AccessibilityNodeInfo workspace = workspaces.get(0);
        int unique=0, candidates=0, iconLike=0, exact=0, onPage=0, open=0, contained=0, clear=0;
        for (NativeTarget rule : denied) {
            if (!rule.interceptIcon) continue; // delayed rules must let the original icon open normally
            Set<String> owners = labelOwners.get(rule.label);
            if (owners == null || owners.size() != 1 || !owners.contains(rule.packageName)) continue;
            unique++;
            Hit best = null;
            for (AccessibilityNodeInfo n : workspace.findAccessibilityNodeInfosByText(rule.label)) {
                candidates++;
                if (!n.isVisibleToUser() || !n.isEnabled() || !n.isClickable() || n.getChildCount() != 0
                        || !"android.widget.TextView".contentEquals(nonNull(n.getClassName()))
                        || !home.contentEquals(nonNull(n.getPackageName()))) continue;
                iconLike++;
                String text = nonNull(n.getText()), desc = nonNull(n.getContentDescription());
                if (!ordinaryLabel(rule.label,text,desc) && !matchesSuspendedPixelIcon(home,rule,text,desc)
                        && !matchesPixelBadge(home,rule.label,text,desc)) continue;
                exact++;
                if (!onPage(n, workspace)) continue;
                onPage++;
                if (home.equals("com.bbk.launcher2") && !vivoOpenAction(n)) continue;
                open++;
                Rect b = new Rect(); n.getBoundsInScreen(b);
                // Reject offscreen previews, tiny folder contents and partially scrolled pages.
                int min = Math.round(32 * service.getResources().getDisplayMetrics().density);
                if (b.width() < min || b.height() < min || !viewport.contains(b)) continue;
                contained++;
                boolean covered = false;
                for (Rect other : occluded) if (Rect.intersects(b, other)) { covered = true; break; }
                if (covered) continue;
                clear++;
                if (best != null) { best = null; break; } // two copies on this page: do not guess
                best = new Hit(n, b);
            }
            if (best != null) found.put(rule.packageName, best);
        }
        lastScan = "rules=" + denied.size() + " unique=" + unique + " candidates=" + candidates + " icon=" + iconLike
                + " exact=" + exact + " page=" + onPage + " open=" + open + " bounds=" + contained + " clear=" + clear;
        return found;
    }
    static boolean ordinaryLabel(String label, String text, String desc) {
        return label.equals(desc) && (text.isEmpty() || label.equals(text));
    }
    static boolean exactPausedLabel(String label, String text, String desc, String launcherDescription) {
        return label.equals(text) && !label.equals(desc) && desc.equals(launcherDescription);
    }
    private void pixelFormats() throws PackageManager.NameNotFoundException {
        if (lookedUpPixelFormats) return;
        lookedUpPixelFormats = true;
        pixelResources = service.getPackageManager().getResourcesForApplication(PIXEL_HOME);
        pausedLabelId = pixelResources.getIdentifier("disabled_app_label","string",PIXEL_HOME);
        int id = pixelResources.getIdentifier("dotted_app_label","string",PIXEL_HOME);
        if (id != 0) {
            try { dottedFormat = new MessageFormat(pixelResources.getString(id),Locale.getDefault()); }
            catch (IllegalArgumentException | Resources.NotFoundException ignored) { /* unsupported launcher format */ }
        }
    }
    private boolean matchesPixelBadge(String home, String label, String text, String desc) {
        if (!PIXEL_HOME.equals(home) || !label.equals(text) || label.isEmpty()) return false;
        if (desc.equals(matchedBadge.get(label))) return true;
        try {
            pixelFormats();
            if (dottedFormat == null) return false;
            Map<String,Object> args = new HashMap<>();
            args.put("app_name",label);
            boolean exact = PixelBadgeLabel.matches(label,text,desc, count -> {
                args.put("count",count);
                return dottedFormat.format(args);
            });
            if (exact) matchedBadge.put(label,desc);
            return exact;
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException unavailable) { return false; }
    }
    private boolean matchesSuspendedPixelIcon(String home, NativeTarget rule, String text, String desc) {
        if (!PIXEL_HOME.equals(home) || !rule.label.equals(text)) return false;
        PackageManager pm=service.getPackageManager();
        try {
            // Same localized template that this launcher's BubbleTextView uses. Label/cache
            // invalidated together on locale or installed package changes. No prefix guessing.
            pixelFormats();
            if (pausedLabelId == 0 || pixelResources == null) return false;
            String paused=pausedLabels.get(rule.label);
            if (paused == null) {
                paused=pixelResources.getString(pausedLabelId,rule.label);
                pausedLabels.put(rule.label,paused);
            }
            return exactPausedLabel(rule.label,text,desc,paused)
                    && (pm.getApplicationInfo(rule.packageName,0).flags & ApplicationInfo.FLAG_SUSPENDED) != 0;
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException
                | java.util.IllegalFormatException unavailable) { return false; }
    }
    private boolean onPage(AccessibilityNodeInfo n, AccessibilityNodeInfo workspace) {
        for (int i=0; n != null && i<14; i++, n=n.getParent()) {
            if (workspace.equals(n)) return true;
            String id = nonNull(n.getViewIdResourceName()).toLowerCase(java.util.Locale.ROOT);
            String cls = nonNull(n.getClassName()).toLowerCase(java.util.Locale.ROOT);
            if (id.contains("folder") || id.contains("widget") || cls.contains("appwidget") || cls.contains("folder")) return false;
        }
        return false;
    }
    private boolean vivoOpenAction(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
            if (a.getId() == AccessibilityNodeInfo.ACTION_CLICK) {
                String label = nonNull(a.getLabel());
                return "打开".equals(label) || "開啟".equals(label) || "open".equalsIgnoreCase(label);
            }
        }
        return false;
    }
    private static String nonNull(CharSequence c) { return c == null ? "" : c.toString(); }
}
