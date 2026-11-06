package net.samsside.craftedpiratecatch.schedule;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure parser for one {@code schedule.slots} entry: "HH:mm" or "HH:mm + HH:mm". */
public final class SlotEntry {

    /** {@code times} in session order (empty = drop the entry); {@code problem} non-null = entry was invalid. */
    public record Parsed(List<LocalTime> times, String problem) {
    }

    private SlotEntry() {
    }

    /** Minutes from {@code rollover} forward to {@code t}, 0..1439 (late-night times sort last). */
    public static int minutesAfter(LocalTime t, LocalTime rollover) {
        return Math.floorMod(t.getHour() * 60 + t.getMinute() - (rollover.getHour() * 60 + rollover.getMinute()), 1440);
    }

    public static Parsed parse(String raw, LocalTime rollover, Duration duration) {
        String[] parts = (raw == null ? "" : raw).split("\\+", -1);
        List<LocalTime> parsed = new ArrayList<>();
        String bad = null;
        for (String part : parts) {
            String s = part.trim();
            try {
                parsed.add(LocalTime.parse(s));
            } catch (DateTimeParseException e) {
                if (bad == null) {
                    bad = s;
                }
            }
        }
        if (parsed.isEmpty()) {
            return new Parsed(List.of(), "no valid HH:mm time");
        }
        List<LocalTime> first = List.of(parsed.get(0));
        if (parts.length > 2) {
            return new Parsed(first, "more than 2 times");
        }
        if (bad != null) {
            return new Parsed(first, "bad time '" + bad + "'");
        }
        if (parsed.size() == 1) {
            return new Parsed(first, null);
        }
        List<LocalTime> sorted = new ArrayList<>(parsed);
        sorted.sort(Comparator.comparingInt(t -> minutesAfter(t, rollover)));
        int gap = minutesAfter(sorted.get(1), rollover) - minutesAfter(sorted.get(0), rollover);
        if (gap == 0) {
            return new Parsed(first, "the same time twice");
        }
        if (gap < duration.toMinutes()) {
            return new Parsed(first, "sessions overlap: " + gap + " min apart, duration " + duration.toMinutes() + " min");
        }
        return new Parsed(List.copyOf(sorted), null);
    }
}
