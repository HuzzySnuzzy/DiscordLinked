package com.huzzysnuzzy.discordlinked.update;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plugin versions like "0.25.0" or "0.25.0-SNAPSHOT". */
public final class Version {

    private static final Pattern FORMAT = Pattern.compile("(\\d{1,9}(?:\\.\\d{1,9}){0,3})([-+.][0-9A-Za-z.+-]{1,40})?");

    private Version() {
    }

    /** Whether it looks like a version this compares properly. */
    public static boolean valid(String version) {
        return version != null && FORMAT.matcher(version.strip()).matches();
    }

    /**
     * Negative if {@code a} is older than {@code b}, 0 if the same, positive if newer. Missing parts count as 0
     * ("0.25" = "0.25.0"), and a version with a suffix ("0.25.0-SNAPSHOT") is older than the same one without. A
     * version that isn't one ({@link #valid}) is older than any that is.
     */
    public static int compare(String a, String b) {
        Matcher ma = a == null ? null : FORMAT.matcher(a.strip());
        Matcher mb = b == null ? null : FORMAT.matcher(b.strip());
        boolean va = ma != null && ma.matches();
        boolean vb = mb != null && mb.matches();
        if (!va || !vb) {
            return Boolean.compare(va, vb);
        }
        String[] pa = ma.group(1).split("\\.");
        String[] pb = mb.group(1).split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length ? Long.parseLong(pa[i]) : 0;
            long y = i < pb.length ? Long.parseLong(pb[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        boolean sa = ma.group(2) != null && !ma.group(2).startsWith("+");
        boolean sb = mb.group(2) != null && !mb.group(2).startsWith("+");
        return Boolean.compare(!sa, !sb);
    }

    public static boolean newer(String candidate, String than) {
        return compare(candidate, than) > 0;
    }
}
