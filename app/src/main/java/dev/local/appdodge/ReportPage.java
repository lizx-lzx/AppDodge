package dev.local.appdodge;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Pure Track-Line + Fluid Motion report layout:
 * Large & Light 54sp numeral, clean rule-bounded metrics, precision 2dp ratio tracks,
 * and tap-to-morph vertical moss rail expansion (zero filled pill cards).
 */
final class ReportPage extends JournalPage {
    final BlockLedger ledger;
    final ReportPeriod period;
    private String expandedApp=null;

    ReportPage(MainActivity a,ReportPeriod period){
        super(a);
        this.period=period;
        ledger=BlockLedger.get(a);
    }

    private TextView eyebrow(String s,int topDp,int bottomDp){
        TextView v=text(s,11,faint,true);
        v.setLetterSpacing(0.10f);
        v.setPadding(0,d(topDp),0,d(bottomDp));
        return v;
    }

    private LinearLayout statDot(String label,long count,int dotColor){
        LinearLayout wrap=row();
        View dot=new View(a);
        dot.setBackground(rounded(dotColor,0,3));
        LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(d(5),d(5));
        dlp.setMarginEnd(d(6));
        wrap.addView(dot,dlp);
        wrap.addView(text(label+" "+count,13,muted,false));
        return wrap;
    }

    private LinearLayout metricCell(String label,String value,int padStartDp,int padEndDp){
        LinearLayout cell=col();
        cell.setPadding(d(padStartDp),d(18),d(padEndDp),d(18));
        TextView k=text(label,12,muted,false);
        k.setLetterSpacing(0.03f);
        cell.addView(k);
        TextView v=text(value,19,ink,true);
        v.setPadding(0,d(6),0,0);
        cell.addView(v);
        return cell;
    }

    /** Crisp 2dp precision track bar (no rounded pill blobs). */
    private View ratioTrack(long count,long maxCount){
        LinearLayout track=row();
        track.setBackgroundColor(line);
        float ratio=maxCount<=0?0f:Math.max(0.06f,Math.min(1f,(float)count/(float)maxCount));
        View fill=new View(a);
        fill.setBackgroundColor(green);
        track.addView(fill,new LinearLayout.LayoutParams(0,d(2),ratio));
        if(ratio<1f){
            track.addView(new View(a),new LinearLayout.LayoutParams(0,d(2),1f-ratio));
        }
        return track;
    }

    void show(LinearLayout p,Runnable log,Runnable redraw){
        // Header: 27sp restrained title + underline text action
        LinearLayout head=row();
        head.setPadding(0,d(10),0,d(12));
        TextView titleView=text("拦截报告",27,ink,true);
        titleView.setLetterSpacing(-0.025f);
        titleView.setAccessibilityHeading(true);
        weight(head,titleView);

        TextView logBtn=text("运行日志",14,green,true);
        logBtn.setMinHeight(d(40));
        logBtn.setGravity(Gravity.CENTER);
        logBtn.setPadding(d(2),0,d(2),0);
        logBtn.setBackground(new JournalLine(a,green,1,false));
        FluidMotion.attachPress(logBtn,0.95f);
        logBtn.setOnClickListener(v->log.run());
        head.addView(logBtn);
        p.addView(head);

        period.controls(p,redraw);

        List<BlockLedger.Totals> rows=ledger.apps(period.key());
        BlockLedger.Totals t=new BlockLedger.Totals();
        long maxCount=0;
        for(BlockLedger.Totals r:rows){
            t.add(r);
            if(r.count>maxCount)maxCount=r.count;
        }

        // Hero metric: sculptural 54sp Light numeral with breathing whitespace
        p.addView(eyebrow(period.caption(),24,4));
        LinearLayout hero=row();
        hero.setGravity(Gravity.BOTTOM);
        TextView bigNum=lightText(String.valueOf(t.count),54,ink);
        bigNum.setLetterSpacing(-0.04f);
        hero.addView(bigNum);
        TextView unit=text("次",15,muted,false);
        unit.setPadding(d(6),0,0,d(10));
        hero.addView(unit);
        p.addView(hero);

        LinearLayout tags=row();
        tags.setPadding(0,d(10),0,d(24));
        tags.addView(statDot("打开前拦住",t.count-t.exits,green));
        LinearLayout secondTag=statDot("使用后退出",t.exits,faint);
        LinearLayout.LayoutParams sLp=new LinearLayout.LayoutParams(-2,-2);
        sLp.setMarginStart(d(18));
        tags.addView(secondTag,sLp);
        p.addView(tags);

        // Two-column rule-bounded metric strip (pure lines + whitespace, zero filled box)
        divider(p);
        LinearLayout grid=row();
        grid.setGravity(Gravity.CENTER_VERTICAL);
        weight(grid,metricCell("退出前合计",t.exits==0?"—":BlockLedger.duration(t.ms),0,12));
        View vRule=new View(a);
        vRule.setBackgroundColor(line);
        grid.addView(vRule,new LinearLayout.LayoutParams(Math.max(1,d(1)),d(38)));
        weight(grid,metricCell("平均每次退出",t.average(),18,0));
        p.addView(grid);
        divider(p);

        if(rows.isEmpty()){
            TextView empty=text("这一天还没有触发拦截记录",14,faint,false);
            empty.setPadding(0,d(32),0,0);
            p.addView(empty);
            return;
        }

        // App distribution: crisp 2dp track bars + tap to morph a left moss vertical rail
        p.addView(eyebrow("按应用分布 · 点按展开明细",28,4));
        LinearLayout appList=col();
        for(int i=0;i<rows.size();i++){
            BlockLedger.Totals r=rows.get(i);
            boolean isOpen=r.name.equals(expandedApp);

            LinearLayout item=col();
            item.setPadding(isOpen?d(14):0,d(16),0,d(16));
            item.setBackground(isOpen?new JournalLine(a,green,2,true):JournalLine.hit(a,line,0));
            item.setClickable(true);
            item.setFocusable(true);
            FluidMotion.attachPress(item,0.985f);

            LinearLayout topRow=row();
            TextView name=text(r.name,15,ink,true);
            weight(topRow,name);
            TextView cnt=text(r.count+" 次",15,green,true);
            topRow.addView(cnt);
            item.addView(topRow);

            View bar=ratioTrack(r.count,maxCount);
            LinearLayout.LayoutParams barLp=new LinearLayout.LayoutParams(-1,d(2));
            barLp.topMargin=d(10);
            item.addView(bar,barLp);

            LinearLayout detailBox=col();
            detailBox.setVisibility(isOpen?View.VISIBLE:View.GONE);
            detailBox.setPadding(0,d(12),0,d(2));

            LinearLayout detailGrid=row();
            detailGrid.addView(statDot("打开前",r.count-r.exits,green));
            LinearLayout exitTag=statDot("使用后退出",r.exits,faint);
            LinearLayout.LayoutParams eLp=new LinearLayout.LayoutParams(-2,-2);
            eLp.setMarginStart(d(16));
            detailGrid.addView(exitTag,eLp);
            detailBox.addView(detailGrid);

            if(r.exits>0){
                TextView dur=text("合计 "+BlockLedger.duration(r.ms)+"  ·  平均每次 "+r.average(),12,muted,false);
                dur.setPadding(0,d(6),0,0);
                detailBox.addView(dur);
            }
            item.addView(detailBox);

            TextView brief=text(
                r.exits>0
                    ?"打开前 "+(r.count-r.exits)+"  ·  退出 "+r.exits+"  ·  合计 "+BlockLedger.duration(r.ms)
                    :"打开前全部拦住",
                12,muted,false);
            brief.setPadding(0,d(8),0,0);
            brief.setVisibility(isOpen?View.GONE:View.VISIBLE);
            item.addView(brief);

            item.setTag(r.name);
            detailBox.setTag("detail");
            brief.setTag("brief");

            item.setOnClickListener(v->{
                boolean nextOpen=!r.name.equals(expandedApp);
                FluidMotion.morph(appList,nextOpen);
                expandedApp=nextOpen?r.name:null;
                for(int j=0;j<appList.getChildCount();j++){
                    View child=appList.getChildAt(j);
                    if(child instanceof LinearLayout && child.getTag() instanceof String){
                        boolean openNow=child.getTag().equals(expandedApp);
                        child.setPadding(openNow?d(14):0,d(16),0,d(16));
                        child.setBackground(openNow?new JournalLine(a,green,2,true):JournalLine.hit(a,line,0));
                        View det=child.findViewWithTag("detail");
                        View brf=child.findViewWithTag("brief");
                        if(det!=null)det.setVisibility(openNow?View.VISIBLE:View.GONE);
                        if(brf!=null)brf.setVisibility(openNow?View.GONE:View.VISIBLE);
                    }
                }
            });

            appList.addView(item);
            divider(appList);
        }
        p.addView(appList);
    }
}
