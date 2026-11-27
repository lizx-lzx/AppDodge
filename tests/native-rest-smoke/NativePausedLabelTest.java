package dev.local.appdodge;

public class NativePausedLabelTest {
    private static void require(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        String app = "Dodge Dot", paused = "Disabled Dodge Dot";
        require(NativeIconFinder.ordinaryLabel(app,app,app));
        require(!NativeIconFinder.ordinaryLabel(app,app,paused));
        require(NativeIconFinder.exactPausedLabel(app,app,paused,paused));
        require(!NativeIconFinder.exactPausedLabel(app,"",paused,paused));
        require(!NativeIconFinder.exactPausedLabel(app,app,"Work "+paused,paused));
        require(!NativeIconFinder.exactPausedLabel(app,app,app,app));
    }
}
