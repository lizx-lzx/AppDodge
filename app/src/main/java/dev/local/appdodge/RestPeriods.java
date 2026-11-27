package dev.local.appdodge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** Repeating local-clock intervals. Retain the editable rows; execute their union. */
final class RestPeriods {
    private static final int DAY=24*60;
    private static final Comparator<int[]> BY_START=Comparator.<int[]>comparingInt(r->r[0]).thenComparingInt(r->r[1]);
    private final int[][] rows, coverage;

    private RestPeriods(int[][] rows,int[][] coverage){this.rows=rows;this.coverage=coverage;}
    static RestPeriods single(int start,int end){return of(new int[][]{{start,end}});}
    static boolean validate(int[][] entries){
        if(entries==null||entries.length==0)return false;
        for(int[] r:entries)if(r==null||r.length!=2||r[0]<0||r[0]>=DAY||r[1]<0||r[1]>=DAY||r[0]==r[1])return false;
        return true;
    }
    static RestPeriods of(int[][] entries){
        if(!validate(entries))throw new IllegalArgumentException("至少保留一个时段，每段开始和结束不能相同。");
        if(entries.length==1 && entries[0][0]<entries[0][1]){
            int[][] one={entries[0].clone()};
            return new RestPeriods(one,one);
        }
        ArrayList<int[]> editable=new ArrayList<>(),pieces=new ArrayList<>();
        int[][] sorted=entries.clone();Arrays.sort(sorted,BY_START);
        for(int[] r:sorted){
            if(!editable.isEmpty() && Arrays.equals(editable.get(editable.size()-1),r))continue;
            editable.add(r.clone());
            if(r[1]>r[0])pieces.add(r.clone());
            else{
                pieces.add(new int[]{r[0],DAY});
                if(r[1]>0)pieces.add(new int[]{0,r[1]});
            }
        }
        pieces.sort(BY_START);
        ArrayList<int[]> union=new ArrayList<>();
        for(int[] r:pieces){
            int[] last=union.isEmpty()?null:union.get(union.size()-1);
            if(last!=null && r[0]<=last[1])last[1]=Math.max(last[1],r[1]);
            else union.add(r);
        }
        return new RestPeriods(editable.toArray(new int[0][]),union.toArray(new int[0][]));
    }
    int[][] ranges(){
        int[][] copy=new int[rows.length][];
        for(int i=0;i<copy.length;i++)copy[i]=rows[i].clone();
        return copy;
    }
    private int[] active(int minute){
        if(minute<0||minute>=DAY)throw new IllegalArgumentException("Invalid minute");
        for(int[] r:coverage){
            if(minute<r[0])break;
            if(minute<r[1])return r;
        }
        return null;
    }
    boolean contains(int minute){return active(minute)!=null;}
    boolean fullDay(){return coverage.length==1&&coverage[0][0]==0&&coverage[0][1]==DAY;}
    /** First minute after continuous protection, modulo midnight; -1 if not resting or always resting. */
    int endOfRestAt(int minute){
        int[] span=active(minute);
        if(span==null||fullDay())return -1;
        if(span[1]!=DAY)return span[1];
        return coverage[0][0]==0?coverage[0][1]:0;
    }
    private static String format(int[] r){return Rule.time(r[0])+"–"+(r[1]<r[0]?"次日 ":"")+Rule.time(r[1]);}
    String text(){
        if(rows.length==1)return format(rows[0]);
        StringBuilder out=new StringBuilder();
        for(int[] r:rows){if(out.length()>0)out.append("、");out.append(format(r));}
        return fullDay()?"全天（"+out+"）":out.toString();
    }
    String summaryAt(int minute){return summaryAt(minute,false);}
    String summaryAt(int minute,boolean byEffects){
        if(rows.length==1)return format(rows[0])+(byEffects?"":" 休息");
        if(fullDay())return rows.length+(byEffects?" 段 · 全天生效":" 段 · 全天休息");
        int end=endOfRestAt(minute);
        if(end>=0)return rows.length+(byEffects?" 段 · 生效到 ":" 段 · 休息到 ")+Rule.time(end);
        int next=coverage[0][0];for(int[] r:coverage)if(r[0]>minute){next=r[0];break;}
        return rows.length+" 段 · "+Rule.time(next)+(byEffects?" 开始生效":" 开始休息");
    }
    @Override public boolean equals(Object other){
        return other instanceof RestPeriods && Arrays.deepEquals(rows,((RestPeriods)other).rows);
    }
    @Override public int hashCode(){return Arrays.deepHashCode(rows);}
}
