package com.huzzysnuzzy.discordlinked;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeFormat {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());

    private TimeFormat() {
    }

    public static String dateTime(Instant instant) {
        return DATE_TIME.format(instant);
    }

    /** e.g. "1h 4m 12s", "4m 12s", "38s". */
    public static String preciseDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long h = seconds / 3600;
        long m = seconds / 60 % 60;
        long s = seconds % 60;
        if (h > 0) {
            return h + "h " + m + "m " + s + "s";
        }
        return m > 0 ? m + "m " + s + "s" : s + "s";
    }

    /** Playtime in hours, e.g. "76h 12m", "45m", "0m". */
    public static String hours(Duration duration) {
        long minutes = Math.max(0, duration.toMinutes());
        return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m";
    }

    /** e.g. "3d 4h", "2h 15m", "45m". */
    public static String duration(Duration duration) {
        long minutes = Math.max(1, (duration.toSeconds() + 59) / 60);
        long days = minutes / (24 * 60);
        long hours = minutes / 60 % 24;
        long mins = minutes % 60;
        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return mins > 0 ? hours + "h " + mins + "m" : hours + "h";
        }
        return mins + "m";
    }
}
