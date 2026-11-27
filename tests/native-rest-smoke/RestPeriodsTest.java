package dev.local.appdodge;

import java.util.Arrays;
import java.util.Random;

public final class RestPeriodsTest {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        int[][] input={{1320,60},{450,540},{540,600},{510,570},{1320,60}};
        RestPeriods r=RestPeriods.of(input);input[0][0]=0;r.ranges()[0][0]=0;
        check(r.ranges().length==4 && r.ranges()[0][0]==450);
        check(r.equals(RestPeriods.of(r.ranges())));
        check(r.endOfRestAt(0)==60 && r.endOfRestAt(1439)==60 && !r.contains(60));
        check(r.endOfRestAt(450)==600 && !r.contains(600));
        check(r.summaryAt(539,true).equals("4 段 · 生效到 10:00"));
        check(r.summaryAt(539).equals("4 段 · 休息到 10:00"));
        check(r.summaryAt(60).equals("4 段 · 07:30 开始休息"));
        check(RestPeriods.single(60,0).endOfRestAt(1439)==0);
        check(RestPeriods.single(30,61).summaryAt(0).equals("00:30–01:01 休息"));
        RestPeriods all=RestPeriods.of(new int[][]{{600,720},{720,600}});
        check(all.fullDay() && all.endOfRestAt(600)==-1 && all.contains(1200) && all.text().contains("全天"));
        for(int[][] invalid:new int[][][]{null,{},{{0,0}},{{1440,0}},{{10,-1}},{null},{{1}}})check(!RestPeriods.validate(invalid));
        // Independent minute-by-minute oracle; covers wrap, abutment and overlapping rows.
        Random random=new Random(47);
        for(int k=0;k<60;k++){
            boolean[] expect=new boolean[1440];int[][] rows=new int[1+random.nextInt(6)][2];
            for(int[] p:rows){p[0]=random.nextInt(1440);p[1]=(p[0]+1+random.nextInt(700))%1440;
                for(int m=p[0];m!=p[1];m=(m+1)%1440)expect[m]=true;}
            RestPeriods policy=RestPeriods.of(rows);boolean full=true;for(boolean covered:expect)full&=covered;
            for(int minute=0;minute<1440;minute++){
                check(policy.contains(minute)==expect[minute]);int end=minute;
                if(!full&&expect[minute])while(expect[end])end=(end+1)%1440;else end=-1;
                if(policy.endOfRestAt(minute)!=end)throw new AssertionError(Arrays.deepToString(rows)+" @ "+minute);
            }
        }
        System.out.println("RestPeriods: editable rows, overlapping edges, midnight and entire day passed");
    }
}
