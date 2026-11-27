package dev.local.appdodge;

import java.util.Map;
import java.util.HashMap;
import java.util.Random;

public final class RandomTicketsTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static final class Rolls extends Random {
        final int[] choices,bounds;
        int index;
        Rolls(int[] choices,int[] bounds){this.choices=choices;this.bounds=bounds;}
        @Override public int nextInt(int n){
            check(index<choices.length && bounds[index]==n && choices[index]<n);
            return choices[index++];
        }
        void done(){check(index==choices.length);}
    }
    private static void exhaustive(boolean strict) {
        SharedBasket basket=new SharedBasket(MethodPool.EXPERIENCES|MethodPool.bit(Rule.EXIT_SHORT)|MethodPool.bit(Rule.EXIT_REDIRECT),
                SharedBasket.REST_RESPONSES,"alternate",false);
        int available=strict?basket.restTickets(true):basket.methods&MethodPool.COMBINABLE;
        check(Integer.bitCount(available)==5);
        for(int k:new int[]{2,3}){
            Map<Integer,Integer> frequencies=new HashMap<>();
            for(int a=0;a<5;a++)for(int b=0;b<4;b++)for(int c=0;c<(k==2?1:3);c++){
                int[] bounds=k==2?new int[]{2,5,4}:new int[]{2,5,4,3};
                int[] rolls=k==2?new int[]{0,a,b}:new int[]{1,a,b,c};
                int previous=0;
                for(int attempt=0;attempt<2;attempt++){
                    Rolls rng=new Rolls(rolls,bounds);
                    int result=strict?basket.drawRestResponses(rng,true):MethodEntry.sample(available,true,rng);
                    rng.done();check(Integer.bitCount(result)==k && (result&~available)==0);
                    if(attempt==0){frequencies.merge(result,1,Integer::sum);previous=result;}
                    else check(result==previous); // the exact same two or three may occur again
                }
            }
            check(frequencies.size()==10);
            for(int count:frequencies.values())check(count==(k==2?2:6)); // all permutations, exact uniformity for each size
        }
    }
    private static void percentages(){
        Rule base=Rule.defaultRest("app","应用",120,180,false);
        for(int pc=0;pc<=100;pc++){
            Rule day=base.withDaily(pc,pc==0?0:MethodPool.bit(Rule.EXIT_NOW),pc==0?0:FeedbackBasket.NOTICE,"",false).during(0);
            if(pc==0){check(day==null);continue;}
            MethodEntry e=new MethodEntry(new ForegroundVisit());
            int hits=0;
            for(int ticket=0;ticket<100;ticket++){
                Rolls rng=new Rolls(pc==100?new int[0]:new int[]{ticket},pc==100?new int[0]:new int[]{100});
                int selection=e.icon(day,ticket*6000L,rng); rng.done();
                check(selection==Rule.EXIT_NOW || selection==MethodEntry.PASS);
                if(selection==Rule.EXIT_NOW)hits++;
            }
            check(hits==pc);
        }
    }
    public static void main(String[] args){
        exhaustive(false);exhaustive(true);percentages();
        int two=MethodPool.bit(Rule.EXIT_LOAD)|MethodPool.bit(Rule.EXIT_MUTE);
        Rolls none=new Rolls(new int[0],new int[0]);
        check(MethodEntry.sample(two,true,none)==two);none.done();
        System.out.println("RandomTickets: uniform independent draws; exact 0..100% gates, fair sizes and subsets, repeats permitted");
    }
}
