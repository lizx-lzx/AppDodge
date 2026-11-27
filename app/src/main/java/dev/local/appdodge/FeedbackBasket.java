package dev.local.appdodge;

import java.util.Random;

/** A rule's choices are stable data. The current response window is process-local. */
final class FeedbackBasket {
    static final int NOTICE = 1, HAPTIC = 2, COUNTDOWN = 4, LOCK = 8, DURATION = 16;
    static final int ALL = NOTICE | HAPTIC | COUNTDOWN | LOCK | DURATION, NEW_DEFAULT = HAPTIC | COUNTDOWN;
    static final long COUNT_MS = 5000, REPEAT_MS = 850;
    static boolean valid(int mask) { return mask > 0 && (mask & ~ALL) == 0; }
    static boolean canConfirmLock(int shown, boolean visible, long drawnAt, long lastEarlyUp, long downAt) {
        // The confirming touch must START after the warning and a pause, not merely end later
        // because the finger was held or because many fast taps straddled the threshold.
        return shown == LOCK && visible && drawnAt >= 0 && downAt-Math.max(drawnAt,lastEarlyUp)>=REPEAT_MS;
    }
    static String name(int item) {
        switch (item) {
            case NOTICE: return "notice"; case HAPTIC: return "haptic"; case COUNTDOWN: return "countdown";
            case LOCK: return "lock"; case DURATION: return "duration"; default: return "none";
        }
    }
    static String durationCaption(long millis) {
        if (millis < 0) throw new IllegalArgumentException("No measured visit");
        long seconds = millis / 1000;
        if (seconds == 0) return "本轮累计使用不到 1 秒";
        if (seconds < 60) return "本轮共用了 " + seconds + " 秒";
        return "本轮共用了 " + seconds / 60 + " 分钟" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + " 秒");
    }
    static int eligible(int mask, boolean external) {
        int possible = external ? mask & ~LOCK : mask & ~DURATION;
        return possible != 0 ? possible : NOTICE;
    }
    static final class Round {
        private int last;
        private long next;
        int last() { return last; }
        long end() { return next; }
        boolean responding(long now) { return now<next; }
        int seconds(long now) { return last != COUNTDOWN || now >= next ? 0 : (int)((next-now+999)/1000); }
        void release() { next = 0; }
        /** Reserve a single, non-refreshable response window for a composite presentation. */
        void startComposite(boolean countdown,long now) {
            last=countdown?COUNTDOWN:NOTICE; next=now+(countdown?COUNT_MS:2400);
        }
        /** Zero means the existing response already covers this attempt. Do not refresh its deadline. */
        int draw(int mask, boolean external, long now, Random random) {
            if (responding(now)) return 0;
            last = RandomTickets.one(eligible(mask,external),random);
            next = now + (last == COUNTDOWN ? COUNT_MS : REPEAT_MS);
            return last;
        }
    }
    private FeedbackBasket() {}
}
