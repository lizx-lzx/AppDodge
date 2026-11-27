package dev.local.appdodge;

import java.util.Random;

/** Draw eligible bits uniformly. No past draw is an input; only a single combination has no replacement. */
final class RandomTickets {
    private RandomTickets() {}
    static int one(int available,Random random) {
        int n=Integer.bitCount(available);
        if(n<2)return available;
        for(int skip=random.nextInt(n);skip>0;skip--)available &= available-1;
        return Integer.lowestOneBit(available);
    }
    static int combination(int available,Random random) {
        int n=Integer.bitCount(available);
        // With zero or one compatible option at an external entry, nothing else is invented.
        if(n<=2)return available;
        int k=2+random.nextInt(2),picked=0;
        for(int i=0;i<k;i++)picked|=one(available&~picked,random);
        return picked;
    }
}
