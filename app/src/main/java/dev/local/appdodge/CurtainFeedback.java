package dev.local.appdodge;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

/** Opt-in ending to a random visit. Draws over the content; never owns a tap or a deadline to HOME. */
final class CurtainFeedback {
    static final long WINDOW_MS = 4_000L;
    private static final int SYSTEM_EDGES = WindowInsets.Type.systemBars()
            | WindowInsets.Type.mandatorySystemGestures() | WindowInsets.Type.displayCutout();
    private final AccessibilityService service;
    private final WindowManager wm;
    private Panels view;
    private final Runnable expire = this::hide;

    CurtainFeedback(AccessibilityService service) {
        this.service = service;
        wm = service.getSystemService(WindowManager.class);
    }

    void show(long deadlineElapsedRealtime) {
        long now = SystemClock.elapsedRealtime();
        if (view != null && view.end == deadlineElapsedRealtime) return;
        hide();
        long remaining = deadlineElapsedRealtime - now;
        if (remaining < 100 || remaining > WINDOW_MS || !ValueAnimator.areAnimatorsEnabled()) return;
        int match = WindowManager.LayoutParams.MATCH_PARENT;
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(match, match,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.FILL;
        p.setFitInsetsTypes(SYSTEM_EDGES);
        p.setFitInsetsIgnoringVisibility(true);
        p.setTitle("反人性软件 · 画面收拢");
        Panels v = new Panels(now, deadlineElapsedRealtime);
        try {
            wm.addView(v, p);
        } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException e) {
            Log.w(NativeRestService.TAG, "curtain_unavailable");
            return;
        }
        view = v;
        v.postDelayed(expire, remaining);
    }

    View surface() { return view; }
    void hide() {
        if (view == null) return;
        Panels old = view;
        view = null;
        old.removeCallbacks(expire);
        if (old.getParent() != null) wm.removeViewImmediate(old);
    }

    private final class Panels extends View {
        final long start, end;
        final Paint ink = new Paint();
        final Rect usable = new Rect();
        final int[] origin = new int[2];

        Panels(long start, long end) {
            super(service);
            this.start = start;
            this.end = end;
            ink.setColor(0xff171c18);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            updateEdges();
        }

        private void updateEdges() {
            // A gesture-navigation accessibility window can extend UNDER the handle even with
            // fitInsetsTypes set. Restrict actual ink in display coordinates, not just its window.
            WindowMetrics screen = wm.getMaximumWindowMetrics();
            Insets safe = screen.getWindowInsets().getInsetsIgnoringVisibility(SYSTEM_EDGES);
            usable.set(screen.getBounds());
            usable.inset(safe.left, safe.top, safe.right, safe.bottom);
        }

        @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            updateEdges();
            return super.onApplyWindowInsets(insets);
        }

        @Override protected void onDraw(Canvas c) {
            getLocationOnScreen(origin);
            int l=Math.max(0,usable.left-origin[0]), r=Math.min(getWidth(),usable.right-origin[0]);
            int top=Math.max(0,usable.top-origin[1]), bottom=Math.min(getHeight(),usable.bottom-origin[1]);
            if (l>=r || top>=bottom) return;
            float t = Math.min(1f, Math.max(0f, (float) (SystemClock.elapsedRealtime() - start) / (end - start)));
            float eased = t * t * (3f - 2f * t);
            float inset = (bottom-top) * 0.5f * eased;
            c.drawRect(l, top, r, top+inset, ink);
            c.drawRect(l, bottom-inset, r, bottom, ink);
            if (t < 1f) postInvalidateOnAnimation();
        }
    }
}
