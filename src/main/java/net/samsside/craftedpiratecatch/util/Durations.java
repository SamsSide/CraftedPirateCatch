package net.samsside.craftedpiratecatch.util;

/**
 * Formats a span of time as {@code 1h 05m}, {@code 12m} or {@code 45s} (seconds only
 * under one minute). Negative values are treated as zero.
 */
public final class Durations {

    private Durations() {
    }

    public static String format(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        long totalMinutes = totalSeconds / 60L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0) {
            return hours + "h " + (minutes < 10 ? "0" : "") + minutes + "m";
        }
        return minutes + "m";
    }
}
