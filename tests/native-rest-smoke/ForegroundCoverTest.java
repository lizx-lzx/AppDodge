package dev.local.appdodge;

public final class ForegroundCoverTest {
    static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why); }
    public static void main(String[] args) {
        // iQOO: WindowMetrics for the service stays 1080x2400, game really occupies 2400x1080.
        check(ForegroundWindow.exactQuarterTurn(0,0,1080,2400,0,0,2400,1080),"verified full display in rotated space");
        check(ForegroundWindow.exactQuarterTurn(0,0,2400,1080,0,0,1080,2400),"reverse quarter-turn");
        check(!ForegroundWindow.exactQuarterTurn(0,0,1080,2400,0,0,1080,2400),"portrait keeps its actual system insets");
        check(!ForegroundWindow.exactQuarterTurn(0,0,1080,2400,0,1,1079,1079),"observed animation frame is not fullscreen");
        check(!ForegroundWindow.exactQuarterTurn(0,0,1080,2400,1,0,2400,1080),"offset full extent is a transition");
        check(!ForegroundWindow.exactQuarterTurn(0,0,1080,2400,0,0,1200,540),"same landscape aspect in freeform must not qualify");
        check(!ForegroundWindow.exactQuarterTurn(0,0,1080,2400,0,0,2400,540),"half-screen window must not qualify");
        check(!ForegroundWindow.exactQuarterTurn(0,101,1080,2299,0,0,2299,1080),"never transpose inset-adjusted viewport");
        check(ForegroundWindow.Cover.fromArea(2400L*1080,42L*1080)==ForegroundWindow.Cover.PARTIAL,
                "real iQOO right system window is in the rotated viewport, does not hide the game");
        long content=1080L*2200;
        ForegroundWindow.Cover clean=ForegroundWindow.Cover.fromArea(content,0);
        check(clean.canDrawOverContent() && clean.canCountViewing(),"ordinary fullscreen accrues time and allows effects");
        for(long pixels:new long[]{48L*48,56L*800,content/2,3*content/4}) {
            ForegroundWindow.Cover c=ForegroundWindow.Cover.fromArea(content,pixels);
            check(c.canCountViewing() && !c.canDrawOverContent(),"visible app + small bubble/volume panel must not grant free time or cover foreign controls");
        }
        for(long pixels:new long[]{3*content/4+1,content}) {
            ForegroundWindow.Cover c=ForegroundWindow.Cover.fromArea(content,pixels);
            check(!c.canCountViewing() && !c.canDrawOverContent(),"mostly hidden focused app doesn't consume time");
        }
        check(!ForegroundWindow.Cover.fromArea(0,0).canCountViewing(),"empty app is not a view");
        System.out.println("ForegroundCoverTest passed: small foreground occluders yield controls, not the viewing budget; near-full covers pause");
    }
}
