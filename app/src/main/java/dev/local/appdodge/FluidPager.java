package dev.local.appdodge;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Zero-dependency 1:1 finger-tracking horizontal pager with Apple-style
 * interruptible spring settle, edge rubber-banding, and live progress callback.
 */
final class FluidPager extends ViewGroup {
    interface Listener {
        void onProgress(float progress);
        void onSettled(int page);
    }

    private final int slop;
    private final float flingThresholdPx;
    private int current=0;
    private float progress=0f;
    private boolean dragging=false;
    private boolean lockedVertical=false;
    private float downX,downY,dragStartProgress;
    private VelocityTracker velocity;
    private ValueAnimator animator;
    private Listener listener;
    private boolean enabled=true;

    FluidPager(Context c){
        super(c);
        slop=ViewConfiguration.get(c).getScaledTouchSlop();
        flingThresholdPx=420f*c.getResources().getDisplayMetrics().density;
    }

    void setListener(Listener l){listener=l;}
    void setPagingEnabled(boolean allow){enabled=allow;}
    int currentPage(){return current;}
    float progress(){return progress;}

    void setPage(int index,boolean animate){
        int max=Math.max(0,getChildCount()-1);
        int target=Math.max(0,Math.min(max,index));
        current=target;
        if(!animate||FluidMotion.reduced(getContext())||getWidth()<=0){
            cancelAnim();
            applyProgress(target);
            return;
        }
        animateTo(target,true);
    }

    private void cancelAnim(){
        if(animator!=null){animator.cancel();animator=null;}
    }

    private void applyProgress(float p){
        progress=p;
        int w=getWidth();
        if(w>0){
            for(int i=0;i<getChildCount();i++){
                getChildAt(i).setTranslationX((i-p)*w);
            }
        }
        if(listener!=null)listener.onProgress(p);
    }

    private void animateTo(int target,boolean gentleSpring){
        cancelAnim();
        float start=progress;
        if(Math.abs(start-target)<0.001f){
            applyProgress(target);
            if(listener!=null)listener.onSettled(target);
            return;
        }
        animator=ValueAnimator.ofFloat(start,target);
        float dist=Math.abs(target-start);
        animator.setDuration(Math.round(250+Math.min(1f,dist)*90));
        animator.setInterpolator(gentleSpring
            ?new OvershootInterpolator(0.32f)
            :new DecelerateInterpolator(1.8f));
        animator.addUpdateListener(a->applyProgress((float)a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter(){
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a){cancelled=true;}
            @Override public void onAnimationEnd(android.animation.Animator a){
                animator=null;
                if(!cancelled&&listener!=null)listener.onSettled(target);
            }
        });
        animator.start();
    }

    @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){
        int w=MeasureSpec.getSize(widthMeasureSpec);
        int h=MeasureSpec.getSize(heightMeasureSpec);
        int cw=MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY);
        int ch=MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY);
        for(int i=0;i<getChildCount();i++){
            getChildAt(i).measure(cw,ch);
        }
        setMeasuredDimension(w,h);
    }

    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int w=r-l,h=b-t;
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);
            child.layout(0,0,w,h);
            child.setTranslationX((i-progress)*w);
        }
        if(listener!=null)listener.onProgress(progress);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent ev){
        if(!enabled||getChildCount()<=1)return false;
        int action=ev.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            downX=ev.getX();downY=ev.getY();
            dragging=false;lockedVertical=false;
            // Interruptible: catching a mid-flight animation smoothly resumes drag.
            if(animator!=null){
                cancelAnim();
                dragStartProgress=progress;
                dragging=true;
                initVelocity(ev);
                return true;
            }
            dragStartProgress=progress;
            return false;
        }
        if(action==MotionEvent.ACTION_MOVE){
            if(lockedVertical)return false;
            float dx=ev.getX()-downX,dy=ev.getY()-downY;
            if(Math.abs(dy)>slop&&Math.abs(dy)>=Math.abs(dx)){
                lockedVertical=true;
                return false;
            }
            if(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.2f){
                dragging=true;
                downX=ev.getX();
                dragStartProgress=progress;
                initVelocity(ev);
                return true;
            }
        }
        return false;
    }

    private void initVelocity(MotionEvent ev){
        if(velocity!=null)velocity.recycle();
        velocity=VelocityTracker.obtain();
        velocity.addMovement(ev);
    }

    @Override public boolean onTouchEvent(MotionEvent ev){
        if(!enabled||getChildCount()<=1)return false;
        if(velocity==null)initVelocity(ev);
        else velocity.addMovement(ev);

        int action=ev.getActionMasked();
        int w=Math.max(1,getWidth());
        int max=getChildCount()-1;

        if(action==MotionEvent.ACTION_MOVE){
            float dx=ev.getX()-downX;
            float raw=dragStartProgress-(dx/w);
            // Edge rubber-banding (Apple HIG resistance at boundaries)
            if(raw<0f)raw=raw*0.24f;
            else if(raw>max)raw=max+(raw-max)*0.24f;
            applyProgress(raw);
            return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            float vx=0f;
            if(velocity!=null){
                velocity.computeCurrentVelocity(1000);
                vx=velocity.getXVelocity();
                velocity.recycle();velocity=null;
            }
            int target=current;
            if(action==MotionEvent.ACTION_UP){
                if(vx<-flingThresholdPx&&current<max)target=current+1;
                else if(vx>flingThresholdPx&&current>0)target=current-1;
                else{
                    float delta=progress-current;
                    if(delta>0.26f&&current<max)target=current+1;
                    else if(delta<-0.26f&&current>0)target=current-1;
                    else target=Math.max(0,Math.min(max,Math.round(progress)));
                }
            }
            current=target;
            dragging=false;
            animateTo(target,false);
            return true;
        }
        return true;
    }
}
