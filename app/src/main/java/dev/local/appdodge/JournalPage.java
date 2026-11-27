package dev.local.appdodge;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared editorial typography, soft surfaces, and fluid touch targets. */
class JournalPage {
    final MainActivity a;
    final int ink,muted,faint,green,line,warm,canvas,sheet,tint;
    JournalPage(MainActivity activity){
        a=activity;
        ink=color(R.color.daily_ink);
        muted=color(R.color.daily_muted);
        faint=color(R.color.daily_faint);
        green=color(R.color.daily_green);
        line=color(R.color.daily_line);
        warm=color(R.color.daily_warm_ink);
        canvas=color(R.color.daily_canvas);
        sheet=color(R.color.daily_sheet);
        tint=color(R.color.daily_tint);
    }
    final int color(int id){return a.getColor(id);}
    final int d(int dp){return Math.round(a.getResources().getDisplayMetrics().density*dp);}
    final TextView text(String s,int sp,int color,boolean medium){
        TextView t=new TextView(a);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setIncludeFontPadding(false);
        t.setFontFeatureSettings("tnum");
        t.setLineSpacing(0f,1.35f);
        t.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        return t;
    }
    final TextView lightText(String s,int sp,int color){
        TextView t=text(s,sp,color,false);
        t.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
        return t;
    }
    final GradientDrawable rounded(int fillColor,int strokeColor,int radiusDp){
        GradientDrawable g=new GradientDrawable();
        g.setColor(fillColor);
        if(strokeColor!=0)g.setStroke(Math.max(1,d(1)),strokeColor);
        g.setCornerRadius(d(radiusDp));
        return g;
    }
    final TextView chip(String label,int bgColor,int fgColor){
        TextView t=text(label,12,fgColor,true);
        t.setPadding(d(10),d(5),d(10),d(5));
        t.setBackground(rounded(bgColor,0,12));
        return t;
    }
    final LinearLayout col(){LinearLayout p=new LinearLayout(a);p.setOrientation(LinearLayout.VERTICAL);return p;}
    final LinearLayout row(){LinearLayout r=new LinearLayout(a);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    final void weight(LinearLayout p,View child){p.addView(child,new LinearLayout.LayoutParams(0,-2,1));}
    final void pad(View v,int x,int y){v.setPadding(d(x),d(y),d(x),d(y));}
    final void hit(View v,Runnable action){
        v.setFocusable(true);
        v.setBackground(JournalLine.hit(a,line,0));
        FluidMotion.attachPress(v);
        v.setOnClickListener(w->action.run());
    }
    final void divider(LinearLayout p){
        View v=new View(a);
        v.setBackgroundColor(line);
        p.addView(v,new LinearLayout.LayoutParams(-1,Math.max(1,Math.round(a.getResources().getDisplayMetrics().density*0.75f))));
    }
    final void arrow(LinearLayout r){
        TextView v=text("›",20,faint,false);
        v.setPadding(d(8),0,0,0);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(v);
    }
    final void title(LinearLayout p,String title,TextView action){
        LinearLayout r=row();
        r.setPadding(0,d(10),0,d(18));
        TextView name=text(title,27,ink,true);
        name.setLetterSpacing(-0.025f);
        name.setAccessibilityHeading(true);
        weight(r,name);
        if(action!=null)r.addView(action);
        p.addView(r);
    }
    final TextView link(String title,Runnable open){
        TextView t=text(title,14,green,true);
        t.setMinHeight(d(44));
        t.setMinWidth(d(44));
        t.setGravity(Gravity.CENTER);
        pad(t,6,0);
        hit(t,open);
        return t;
    }
    final void item(LinearLayout p,String title,String end,Runnable open){
        LinearLayout r=row();
        r.setMinimumHeight(d(58));
        pad(r,0,10);
        TextView name=text(title,15,ink,true);
        weight(r,name);
        if(end!=null){
            TextView value=text(end,13,muted,false);
            value.setMaxWidth(d(164));
            value.setGravity(Gravity.END);
            r.addView(value);
        }
        if(open!=null){arrow(r);hit(r,open);}
        p.addView(r);
        divider(p);
    }
}
