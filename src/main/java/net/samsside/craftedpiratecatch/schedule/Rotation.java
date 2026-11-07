package net.samsside.craftedpiratecatch.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Pure rotation/schedule maths. A "rotation day" is not a calendar date: a slot earlier
 * than {@code day-rollover} is the late-night session of its rotation day and runs on the
 * next calendar date (the 01:00 slot belongs to the day before). A rotation day has one or
 * two sessions; within a day they are ordered by minutes after {@code day-rollover}, which is
 * also their start order.
 */
public final class Rotation {

    /** One session's event window. {@code session} is 0-based; {@code sessions} is that day's count. */
    public record Slot(LocalDate day, int session, int sessions, LocalTime time, Instant start, Instant end) {

        /** 1-based "n/N", e.g. "1/2". */
        public String label() {
            return (session + 1) + "/" + sessions;
        }
    }

    private final ZoneId zone;
    private final List<List<LocalTime>> slots;
    private final LocalTime rollover;
    private final LocalDate firstDay;
    private final LocalDate lastDay;
    private final Duration duration;

    public Rotation(ZoneId zone, List<List<LocalTime>> slots, LocalTime rollover,
                    LocalDate firstDay, LocalDate lastDay, Duration duration) {
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("slots must not be empty");
        }
        if (firstDay.isAfter(lastDay)) {
            throw new IllegalArgumentException("first-day is after last-day");
        }
        List<List<LocalTime>> sorted = new ArrayList<>(slots.size());
        for (List<LocalTime> entry : slots) {
            if (entry.isEmpty() || entry.size() > 2) {
                throw new IllegalArgumentException("each slot entry needs 1 or 2 times");
            }
            List<LocalTime> times = new ArrayList<>(entry);
            times.sort(Comparator.comparingInt(t -> SlotEntry.minutesAfter(t, rollover)));
            sorted.add(List.copyOf(times));
        }
        this.zone = zone;
        this.slots = List.copyOf(sorted);
        this.rollover = rollover;
        this.firstDay = firstDay;
        this.lastDay = lastDay;
        this.duration = duration;
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDate firstDay() {
        return firstDay;
    }

    public LocalDate lastDay() {
        return lastDay;
    }

    public Duration duration() {
        return duration;
    }

    public boolean isRotationDay(LocalDate d) {
        return !d.isBefore(firstDay) && !d.isAfter(lastDay);
    }

    /** Rotation day {@code d}'s session times in start order, or empty if it is outside the window. */
    public List<LocalTime> slotTimes(LocalDate d) {
        if (!isRotationDay(d)) {
            return List.of();
        }
        long i = ChronoUnit.DAYS.between(firstDay, d);
        return slots.get((int) Math.floorMod(i, (long) slots.size()));
    }

    /** How many sessions rotation day {@code d} has (0 outside the window). */
    public int sessionsOn(LocalDate d) {
        return slotTimes(d).size();
    }

    /** The window of {@code d}'s 0-based {@code session}, or null if there is no such session. */
    public Slot slot(LocalDate d, int session) {
        List<LocalTime> times = slotTimes(d);
        if (session < 0 || session >= times.size()) {
            return null;
        }
        LocalTime t = times.get(session);
        LocalDate date = t.isBefore(rollover) ? d.plusDays(1) : d;
        Instant start = resolve(date.atTime(t));
        return new Slot(d, session, times.size(), t, start, start.plus(duration));
    }

    /** All of {@code d}'s sessions in start order (empty outside the window). */
    public List<Slot> slots(LocalDate d) {
        int n = sessionsOn(d);
        List<Slot> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(slot(d, i));
        }
        return out;
    }

    private Instant resolve(LocalDateTime ldt) {
        // ofLocal with a null preferred offset picks the earlier offset in an overlap and
        // shifts forward by the gap length in a gap.
        return ZonedDateTime.ofLocal(ldt, zone, null).toInstant();
    }

    /**
     * The earliest session whose event has not ended by {@code now} and which is not closed
     * (FINISHED/CANCELLED/SKIPPED/MISSED). Null when the season is over.
     */
    public Slot nextScheduled(Instant now, Predicate<Slot> closed) {
        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            for (Slot s : slots(d)) {
                if (s.end().isAfter(now) && !closed.test(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * For display: the first open session whose start is still in the future (skips a window
     * that is already under way). Null when nothing is left in the season.
     */
    public Slot nextStart(Instant now, Predicate<Slot> closed) {
        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            for (Slot s : slots(d)) {
                if (s.start().isAfter(now) && !closed.test(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    /** The next {@code n} open sessions (as {@link #nextScheduled}, in order). */
    public List<Slot> upcoming(Instant now, int n, Predicate<Slot> closed) {
        List<Slot> out = new ArrayList<>();
        for (LocalDate d = firstDay; !d.isAfter(lastDay) && out.size() < n; d = d.plusDays(1)) {
            for (Slot s : slots(d)) {
                if (out.size() < n && s.end().isAfter(now) && !closed.test(s)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /**
     * "Today" for staff commands: the day {@code d} such that {@code now} lies between
     * {@code d} at day-rollover and {@code d+1} at day-rollover. May be outside the window.
     */
    public LocalDate todayAt(Instant now) {
        ZonedDateTime z = now.atZone(zone);
        return z.toLocalTime().isBefore(rollover) ? z.toLocalDate().minusDays(1) : z.toLocalDate();
    }
}
