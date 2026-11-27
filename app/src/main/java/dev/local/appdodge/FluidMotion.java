package dev.local.appdodge;

import android.content.Context;
import android.provider.Settings;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Apple-inspired fluid spring micro-interactions shared across the app.
 * Respects system animator duration scale (reduced motion).
 */
final class FluidMotion {
    private FluidMotion(){}

    static boolean reduced(Context c){
        try{
            return Settings.Global.getFloat(c.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f;
        }catch(Exception ignored){return false;}
    }

    /** Tactile spring press scale (0.965 down -> gentle spring release) on any clickable control. */
    static void attachPress(View view){
        attachPress(view,0.968f);
    }

    static void attachPress(View view,float pressedScale){
        view.setOnTouchListener((v,ev)->{
            if(reduced(v.getContext()))return false;
            int action=ev.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                v.animate().cancel();
                v.animate().scaleX(pressedScale).scaleY(pressedScale)
                    .setDuration(95)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
            }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                v.animate().cancel();
                v.animate().scaleX(1f).scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(new OvershootInterpolator(1.1f))
                    .start();
            }
            return false;
        });
    }

    /** Subtle icon spring pop when selecting a tab or toggling state. */
    static void pop(View view){
        if(reduced(view.getContext()))return;
        view.animate().cancel();
        view.setScaleX(0.86f);
        view.setScaleY(0.86f);
        view.animate().scaleX(1f).scaleY(1f)
            .setDuration(300)
            .setInterpolator(new OvershootInterpolator(1.6f))
            .start();
    }

    /** Spring-glide a floating pill indicator smoothly to target X and width. */
    static void slidePill(View pill,float targetX,int targetWidth,boolean animate){
        ViewGroup.LayoutParams lp=pill.getLayoutParams();
        if(!animate||reduced(pill.getContext())){
            if(lp!=null&&targetWidth>0&&lp.width!=targetWidth){
                lp.width=targetWidth;
                pill.setLayoutParams(lp);
            }
            pill.setTranslationX(targetX);
            return;
        }
        if(lp!=null&&targetWidth>0&&lp.width!=targetWidth){
            lp.width=targetWidth;
            pill.setLayoutParams(lp);
        }
        pill.animate().cancel();
        pill.animate()
            .translationX(targetX)
            .setDuration(320)
            .setInterpolator(new OvershootInterpolator(0.65f))
            .start();
    }

    /** Spring-glide a thin track cursor line with liquid stretch-and-settle feel. */
    static void glideTrackLine(View line,float fromCenterX,float toCenterX,int baseWidthPx,boolean animate){
        android.view.ViewGroup.LayoutParams lp=line.getLayoutParams();
        if(!animate||reduced(line.getContext())){
            if(lp!=null&&lp.width!=baseWidthPx){lp.width=baseWidthPx;line.setLayoutParams(lp);}
            line.setTranslationX(toCenterX-baseWidthPx/2f);
            return;
        }
        android.animation.ValueAnimator anim=android.animation.ValueAnimator.ofFloat(0f,1f);
        anim.setDuration(320);
        anim.setInterpolator(new OvershootInterpolator(0.5f));
        float travel=Math.abs(toCenterX-fromCenterX);
        float maxStretch=Math.min(baseWidthPx*0.95f,travel*0.36f);
        anim.addUpdateListener(a->{
            float t=(float)a.getAnimatedValue();
            float clamped=Math.max(0f,Math.min(1f,t));
            int w=Math.round(baseWidthPx+maxStretch*(float)Math.sin(clamped*Math.PI));
            float cx=fromCenterX+(toCenterX-fromCenterX)*t;
            android.view.ViewGroup.LayoutParams cur=line.getLayoutParams();
            if(cur!=null&&cur.width!=w){cur.width=w;line.setLayoutParams(cur);}
            line.setTranslationX(cx-w/2f);
        });
        anim.start();
    }

    /** Position a track cursor line continuously during 1:1 finger drag with elastic stretch. */
    static void trackCursorAt(View line,float centerX,float fractionToNearest,int baseWidthPx,int maxExtraPx){
        int w=Math.round(baseWidthPx+maxExtraPx*(float)Math.sin(Math.min(0.5f,fractionToNearest)*2f*Math.PI/2f));
        android.view.ViewGroup.LayoutParams lp=line.getLayoutParams();
        if(lp!=null&&lp.width!=w){lp.width=w;line.setLayoutParams(lp);}
        line.setTranslationX(centerX-w/2f);
    }

    /** In-place fluid morphing for expanding/collapsing details within a page. */
    static void morph(ViewGroup container,boolean expanding){
        if(reduced(container.getContext()))return;
        TransitionSet set=new TransitionSet().setOrdering(TransitionSet.ORDERING_TOGETHER);
        ChangeBounds bounds=new ChangeBounds();
        bounds.setDuration(expanding?300:210);
        bounds.setInterpolator(expanding?new OvershootInterpolator(0.45f):new DecelerateInterpolator(1.6f));
        Fade fade=new Fade(expanding?Fade.IN:Fade.OUT);
        fade.setDuration(expanding?170:100);
        if(expanding)fade.setStartDelay(45);
        set.addTransition(bounds).addTransition(fade);
        TransitionManager.beginDelayedTransition(container,set);
    }

    /** Gentle page entrance with slight upward glide. */
    static void enterPage(View page,int offsetPx){
        if(reduced(page.getContext())){
            page.setAlpha(1f);
            page.setTranslationY(0f);
            return;
        }
        page.animate().cancel();
        page.setAlpha(0f);
        page.setTranslationY(offsetPx);
        page.animate().alpha(1f).translationY(0f)
            .setDuration(200)
            .setInterpolator(new DecelerateInterpolator(1.7f))
            .start();
    }
}
