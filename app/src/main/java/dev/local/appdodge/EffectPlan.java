package dev.local.appdodge;

import java.util.ArrayList;
import java.util.Random;

/** A feasible episode, including its entry, content and post-return stages. No global mutable pool. */
final class EffectPlan {
    static final int SHIFT=16,METHODS=(1<<SHIFT)-1;
    static int methods(int plan){return plan&METHODS;}
    static int replies(int plan){return plan>>>SHIFT;}
    static int post(int f){return f<<SHIFT;}
    static final int CONTENT=MethodPool.EXPERIENCES, TIMED=MethodPool.TIMED;
    static final int BLOCK=MethodPool.bit(Rule.EXIT_ICON), HOME=MethodPool.bit(Rule.EXIT_NOW);
    static final int LOCK=MethodPool.bit(Rule.EXIT_LOCK_NOW), REDIRECT=MethodPool.bit(Rule.EXIT_REDIRECT);
    static final int TERMINALS=LOCK|REDIRECT;
    static final int GATE=MethodPool.bit(Rule.EXIT_WAIT_OPEN);
    /** A completed gate spends its own short visit; this never changes the user's random-exit range. */
    static int clock(Rule r,int plan){return methods(plan)&(TIMED|(r.byEffects()?GATE:0));}
    static boolean opensFromIcon(int plan){return plan==MethodEntry.PASS || plan>0 && (methods(plan)&(CONTENT|TIMED|HOME))!=0;}
    /** Zero keeps waiting for the first real presentation. An unfocused/mostly hidden target
     * is never authority for a system action; a small non-focusing obstruction is not
     * authority to lock/redirect or see indefinitely, either. */
    static int afterPresentation(int ending,boolean stillVisible,boolean unobstructed,boolean available,boolean experienced){
        if(!stillVisible)return 0;
        if(!unobstructed || !available)return HOME;
        return experienced?(ending==0?HOME:ending):0;
    }
    static final int POST_VISUAL=FeedbackBasket.ALL & ~FeedbackBasket.HAPTIC;
    static boolean legal(int plan,boolean icon) {
        int m=methods(plan),f=replies(plan);
        if(!MethodPool.valid(m)||(f&~FeedbackBasket.ALL)!=0)return false;
        int c=m&CONTENT, finish=m&~CONTENT;
        // Exactly one outcome: lock, redirect, immediate/timed return, gate, or desktop-only action.
        if(Integer.bitCount(finish)>1 || Integer.bitCount(f&POST_VISUAL)>1)return false;
        if(!icon && (m&~MethodPool.FOREGROUND)!=0)return false;
        if(c!=0 && (finish&~(HOME|TIMED|TERMINALS))!=0)return false;
        if((f&(FeedbackBasket.NOTICE|FeedbackBasket.COUNTDOWN))!=0 && (m&(BLOCK|HOME|TIMED))==0)return false;
        if((f&FeedbackBasket.DURATION)!=0 && (m&TIMED)==0)return false;
        if((f&FeedbackBasket.LOCK)!=0 && (!icon || c!=0 || (m&(BLOCK|HOME))==0))return false;
        return true;
    }
    private static int available(SharedBasket b,boolean icon) {
        return (icon?b.methods:b.methods&MethodPool.FOREGROUND)|post(b.feedback);
    }
    static ArrayList<Integer> choices(SharedBasket b,boolean icon,int size){
        ArrayList<Integer> out=new ArrayList<>();
        int pool=available(b,icon);
        for(int a=pool;a!=0;a&=a-1){
            int i=Integer.lowestOneBit(a);
            for(int t=a^i;t!=0;t&=t-1){
                int j=Integer.lowestOneBit(t);
                if(size==2){if(legal(i|j,icon))out.add(i|j);continue;}
                for(int u=t^j;u!=0;u&=u-1){
                    int trio=i|j|Integer.lowestOneBit(u);
                    if(legal(trio,icon))out.add(trio);
                }
            }
        }
        return out;
    }
    static int participantMask(SharedBasket b) {
        // Every legal trio has at least one legal pair; a duration may depend on the timer.
        int seen=0;for(int size=2;size<=3;size++)for(int x:choices(b,true,size))seen|=x;
        return seen;
    }
    static int draw(SharedBasket basket,boolean icon,boolean multi,Random r){
        int m=methods(available(basket,icon));if(m==0)return MethodEntry.NONE;
        if(multi){
            ArrayList<Integer> two=choices(basket,icon,2),three=choices(basket,icon,3);
            ArrayList<Integer> group=two.isEmpty()?three:three.isEmpty()?two:r.nextBoolean()?two:three;
            if(!group.isEmpty())return group.get(r.nextInt(group.size()));
            // An external launch cannot perform original-icon actions: use a feasible single.
        }
        int picked=RandomTickets.one(m,r);
        // Single means one intervention; an optional receipt follows an actual block/return only.
        int receipts=0;
        if((picked&(BLOCK|HOME|TIMED))!=0){
            for(int f=basket.feedback;f!=0;f&=f-1){
                int item=Integer.lowestOneBit(f);
                if(legal(picked|post(item),icon))receipts|=item;
            }
        }
        return picked|post(RandomTickets.one(receipts,r));
    }
    private EffectPlan(){}
}
