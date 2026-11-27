package dev.local.appdodge;
public final class PairingWindowTest{
    static void expect(PairingWindow.Reply want,long sent,long expires,long live,long until,long now,boolean owner,boolean waiting){
        if(PairingWindow.check(sent,expires,live,until,now,owner,waiting)!=want)throw new AssertionError(want);
    }
    public static void main(String[] args){
        expect(PairingWindow.Reply.FRESH,5,301000,5,301000,300999,true,true);
        expect(PairingWindow.Reply.EXPIRED,5,301000,5,301000,301000,true,true);
        expect(PairingWindow.Reply.EXPIRED,5,301000,0,0,301100,false,false);
        expect(PairingWindow.Reply.CLOSED,5,301000,0,0,300000,false,false);
        expect(PairingWindow.Reply.REPLACED,5,100000,7,400000,200000,true,true);
        expect(PairingWindow.Reply.BUSY,5,301000,5,301000,300999,true,false);
        System.out.println("PairingWindow: current, expired in sleep, host lost, replaced and duplicate reply checked.");
    }
}
