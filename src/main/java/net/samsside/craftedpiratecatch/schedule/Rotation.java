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
import java.util.List;
import java.util.function.Predicate;

/**
 * Pure rotation/schedule maths. A "rotation day" is not a calendar date: a slot earlier
 * than {@code day-rollover} is the late-night session of its rotation day and runs on the
 * next calendar date (the 01:00 slot belongs to the day before).
 */
public final class Rotation {

    /** One rotation day's event window. */
    public record Slot(LocalDate day, LocalTime time, Instant start, Instant end) {
    }

    private final ZoneId zone;
    private final List<LocalTime> slots;
    private final LocalTime rollover;
    private final LocalDate firstDay;
    private final LocalDate lastDay;
    private final Duration duration;

    public Rotation(ZoneId zone, List<LocalTime> slots, LocalTime rollover,
                    LocalDate firstDay, LocalDate lastDay, Duration duration) {
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("slots must not be empty");
        }
        if (firstDay.isAfter(lastDay)) {
            throw new IllegalArgumentException("first-day is after last-day");
        }
        this.zone = zone;
        this.slots = List.copyOf(slots);
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

    /** The slot time for rotation day {@code d}, or null if it is outside the window. */
    public LocalTime slotTime(LocalDate d) {
        if (!isRotationDay(d)) {
            return null;
        }
        long i = ChronoUnit.DAYS.between(firstDay, d);
        return slots.get((int) Math.floorMod(i, (long) slots.size()));
    }

    /** The full window for rotation day {@code d}, or null if it is outside the window. */
    public Slot slot(LocalDate d) {
        LocalTime t = slotTime(d);
        if (t == null) {
            return null;
        }
        LocalDate date = t.isBefore(rollover) ? d.plusDays(1) : d;
        Instant start = resolve(date.atTime(t));
        return new Slot(d, t, start, start.plus(duration));
    }

    private Instant resolve(LocalDateTime ldt) {
        // ofLocal with a null preferred offset picks the earlier offset in an overlap and
        // shifts forward by the gap length in a gap.
        return ZonedDateTime.ofLocal(ldt, zone, null).toInstant();
    }

    /**
     * The earliest rotation day whose event has not ended by {@code now} and whose day is not
     * closed (FINISHED/CANCELLED/SKIPPED/MISSED). Null when the season is over.
     */
    public Slot nextScheduled(Instant now, Predicate<LocalDate> closed) {
        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            Slot s = slot(d);
            if (s.end().isAfter(now) && !closed.test(d)) {
                return s;
            }
        }
        return null;
    }

    /**
     * For display: the first open rotation day whose start is still in the future (skips a
     * window that is already under way). Null when nothing is left in the season.
     */
    public Slot nextStart(Instant now, Predicate<LocalDate> closed) {
        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            Slot s = slot(d);
            if (s.start().isAfter(now) && !closed.test(d)) {
                return s;
            }
        }
        return null;
    }

    /** The next {@code n} open rotation days (as {@link #nextScheduled}, in order). */
    public List<Slot> upcoming(Instant now, int n, Predicate<LocalDate> closed) {
        List<Slot> out = new ArrayList<>();
        for (LocalDate d = firstDay; !d.isAfter(lastDay) && out.size() < n; d = d.plusDays(1)) {
            Slot s = slot(d);
            if (s.end().isAfter(now) && !closed.test(d)) {
                out.add(s);
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
