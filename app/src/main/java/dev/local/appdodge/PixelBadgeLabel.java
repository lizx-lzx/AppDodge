package dev.local.appdodge;

import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recreate the real launcher's localized dotted_app_label rather than accept any suffix. */
final class PixelBadgeLabel {
    private static final Pattern DIGITS = Pattern.compile("\\p{Nd}+");

    static boolean matches(String label, String text, String desc, IntFunction<String> formatted) {
        if (label.isEmpty() || !label.equals(text) || label.equals(desc) || !desc.contains(label)) return false;
        // Only generate plausible count arguments; the entire human-readable result must match exactly.
        Matcher numbers = DIGITS.matcher(desc.replace(label, "\u0000"));
        while (numbers.find()) {
            String digits = numbers.group();
            if (digits.length() > 9) continue;
            int n = Integer.parseInt(digits);
            if (n > 0 && desc.equals(formatted.apply(n))) return true;
        }
        // Some locales spell out the singular or dual rather than including a numeral.
        return desc.equals(formatted.apply(1)) || desc.equals(formatted.apply(2));
    }
    private PixelBadgeLabel() {}
}
