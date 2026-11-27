package dev.local.appdodge;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;

/** The time journal's controls rest on a rule rather than in a filled button. */
final class JournalLine extends Drawable {
    private final Paint ink=new Paint();
    private final float width;
    private final boolean vertical;
    JournalLine(Context c,int color,float dp,boolean vertical){ink.setColor(color);width=dp*c.getResources().getDisplayMetrics().density;this.vertical=vertical;}
    public void draw(Canvas c){android.graphics.Rect r=getBounds();if(vertical)c.drawRect(r.left,r.top,r.left+width,r.bottom,ink);else c.drawRect(r.left,r.bottom-width,r.right,r.bottom,ink);}
    public void setAlpha(int a){ink.setAlpha(a);invalidateSelf();}
    public void setColorFilter(ColorFilter f){ink.setColorFilter(f);invalidateSelf();}
    public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    static Drawable hit(Context c,int color,float dp){
        return new RippleDrawable(ColorStateList.valueOf(c.getColor(R.color.daily_tint)),
                dp==0?null:new JournalLine(c,color,dp,false),new ColorDrawable(0xffffffff));
    }
}
