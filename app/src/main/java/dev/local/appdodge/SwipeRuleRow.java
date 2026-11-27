package dev.local.appdodge;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;

/** One physical leftward swipe reveals an explicit action; releasing never removes anything. */
final class SwipeRuleRow extends FrameLayout {
    static final class Group {
        private SwipeRuleRow active;
        void touchScreen(MotionEvent e) {
            if(active==null||e.getActionMasked()!=MotionEvent.ACTION_DOWN)return;
            Rect r=new Rect();int[] pos=new int[2];active.getLocationOnScreen(pos);
            if(!active.getLocalVisibleRect(r)||!r.contains((int)e.getRawX()-pos[0],(int)e.getRawY()-pos[1]))active.setOpen(false,true);
        }
        private void claim(SwipeRuleRow r){
            if(active!=null && active!=r)active.setOpen(false,true);
            active=r;
        }
        String beforeRender(){
            String pkg=active!=null&&active.open?active.pkg:null;
            active=null;
            return pkg;
        }
    }
    private final String pkg;
    private final Group group;
    private final View face;
    private final Button action;
    private final int extent,slop;
    private boolean open,drag,vertical;
    private float downX,downY,startTranslation;

    SwipeRuleRow(Context c, Group group, String pkg, String label, View face, View header,
                 boolean undo, Runnable remove, boolean restore){
        super(c);this.group=group;this.pkg=pkg;this.face=face;
        slop=ViewConfiguration.get(c).getScaledTouchSlop();
        float density=getResources().getDisplayMetrics().density;
        String verb=undo?"撤回移除":"移除";
        String accessible=undo?"撤回移除"+label:"移除"+label+"的规则";
        action=new Button(c);action.setText(verb);action.setAllCaps(false);
        action.setTextSize(16);action.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        action.setTextColor(c.getColor(undo?R.color.daily_green:R.color.daily_warm_ink));action.setPadding(0,0,0,0);
        action.setStateListAnimator(null);action.setBackground(new RippleDrawable(
                ColorStateList.valueOf(c.getColor(R.color.daily_line)),
                new ColorDrawable(c.getColor(undo?R.color.daily_tint:R.color.daily_warm)),null));
        action.setContentDescription(accessible);action.setGravity(Gravity.CENTER);
        extent=Math.max(Math.round(76*density),Math.round(action.getPaint().measureText(verb)+24*density));
        addView(action,new LayoutParams(extent,LayoutParams.MATCH_PARENT,Gravity.RIGHT));
        face.setBackgroundColor(c.getColor(R.color.daily_paper));
        addView(face,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        Runnable confirm=()->{setOpen(false,false);remove.run();};
        action.setOnClickListener(v->confirm.run());
        header.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(host,info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS,accessible));
            }
            @Override public boolean performAccessibilityAction(View host,int id,Bundle args){
                if(id==AccessibilityNodeInfo.ACTION_DISMISS){confirm.run();return true;}
                return super.performAccessibilityAction(host,id,args);
            }
        });
        setOpen(restore,false);
    }
    private void setOpen(boolean show,boolean animate){
        if(show)group.claim(this);else if(group.active==this)group.active=null;
        open=show;
        // Keep the action out of TalkBack/keyboard traversal until it is actually exposed.
        action.setImportantForAccessibility(show?IMPORTANT_FOR_ACCESSIBILITY_YES:IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        action.setFocusable(show);action.setEnabled(show);
        face.animate().cancel();float end=show?-extent:0;
        if(animate)face.animate().translationX(end).setDuration(145).start();else face.setTranslationX(end);
    }
    private void start(MotionEvent e){
        downX=e.getX();downY=e.getY();startTranslation=face.getTranslationX();drag=vertical=false;
        // Let the row establish the axis before the outer ScrollView takes a diagonal move.
        getParent().requestDisallowInterceptTouchEvent(true);
    }
    private void move(MotionEvent e){
        if(vertical)return;
        float dx=e.getX()-downX,dy=e.getY()-downY;
        if(!drag){
            if(Math.abs(dy)>slop && Math.abs(dy)>=Math.abs(dx)){vertical=true;getParent().requestDisallowInterceptTouchEvent(false);return;}
            if(Math.abs(dx)<=slop || Math.abs(dx)<=Math.abs(dy))return;
            drag=true;face.animate().cancel();group.claim(this);
        }
        face.setTranslationX(Math.max(-extent,Math.min(0,startTranslation+dx)));
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                start(e);
                // Tapping the exposed row face closes it, without opening an editor underneath.
                return open&&e.getX()<getWidth()+face.getTranslationX();
            case MotionEvent.ACTION_MOVE: move(e);return drag;
            default:return false;
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:return true;
            case MotionEvent.ACTION_MOVE:move(e);return true;
            case MotionEvent.ACTION_UP:
                if(drag)setOpen(-face.getTranslationX()>=extent*(open?0.65f:0.35f),true);
                else if(open&&!vertical)setOpen(false,true);
                getParent().requestDisallowInterceptTouchEvent(false);return true;
            case MotionEvent.ACTION_CANCEL:
                if(drag)setOpen(open,true);
                getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return true;
        }
    }
}
