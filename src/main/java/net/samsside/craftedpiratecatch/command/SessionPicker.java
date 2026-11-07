package net.samsside.craftedpiratecatch.command;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.samsside.craftedpiratecatch.schedule.Rotation;
import net.samsside.craftedpiratecatch.storage.Database;

/** Pure choice of which of today's sessions a staff start/skip/unskip acts on. */
public final class SessionPicker {

    public enum Outcome { OK, ALREADY_RAN, ALREADY_SKIPPED, ALREADY_UNSKIPPED, BAD_ARG }

    public record Pick(Outcome outcome, Rotation.Slot slot) {
        static Pick ok(Rotation.Slot s) { return new Pick(Outcome.OK, s); }
        static Pick of(Outcome o) { return new Pick(o, null); }
    }

    private SessionPicker() {
    }

    private static Rotation.Slot chosen(List<Rotation.Slot> today, int requested) {
        return requested >= 1 && requested <= today.size() ? today.get(requested - 1) : null;
    }

    /** {@code requested}: 1-based, or null to pick. {@code status}: null = no record. */
    public static Pick start(List<Rotation.Slot> today, Integer requested, Instant now,
                             Function<Rotation.Slot, String> status) {
        if (requested != null) {
            Rotation.Slot s = chosen(today, requested);
            if (s == null) return Pick.of(Outcome.BAD_ARG);
            return status.apply(s) == null ? Pick.ok(s) : Pick.of(Outcome.ALREADY_RAN);
        }
        for (Rotation.Slot s : today) {
            if (status.apply(s) == null && s.end().isAfter(now)) return Pick.ok(s);
        }
        return Pick.of(Outcome.ALREADY_RAN);
    }

    public static Pick skip(List<Rotation.Slot> today, Integer requested, Instant now,
                            Function<Rotation.Slot, String> status) {
        if (requested != null) {
            Rotation.Slot s = chosen(today, requested);
            if (s == null) return Pick.of(Outcome.BAD_ARG);
            String st = status.apply(s);
            if (Database.SKIPPED.equals(st)) return Pick.of(Outcome.ALREADY_SKIPPED);
            if (st != null || !now.isBefore(s.start())) return Pick.of(Outcome.ALREADY_RAN);
            return Pick.ok(s);
        }
        boolean anySkipped = false;
        for (Rotation.Slot s : today) {
            if (!now.isBefore(s.start())) continue;
            String st = status.apply(s);
            if (st == null) return Pick.ok(s);
            if (Database.SKIPPED.equals(st)) anySkipped = true;
        }
        return Pick.of(anySkipped ? Outcome.ALREADY_SKIPPED : Outcome.ALREADY_RAN);
    }

    public static Pick unskip(List<Rotation.Slot> today, Integer requested, Instant now,
                              Function<Rotation.Slot, String> status, Predicate<Rotation.Slot> wholeDay) {
        if (requested != null) {
            Rotation.Slot s = chosen(today, requested);
            if (s == null) return Pick.of(Outcome.BAD_ARG);
            String st = status.apply(s);
            boolean future = now.isBefore(s.start());
            if (st == null) return Pick.of(future ? Outcome.ALREADY_UNSKIPPED : Outcome.ALREADY_RAN);
            if (!Database.SKIPPED.equals(st) || wholeDay.test(s) || !future) return Pick.of(Outcome.ALREADY_RAN);
            return Pick.ok(s);
        }
        boolean anyFree = false;
        for (Rotation.Slot s : today) {
            if (!now.isBefore(s.start())) continue;
            String st = status.apply(s);
            if (Database.SKIPPED.equals(st) && !wholeDay.test(s)) return Pick.ok(s);
            if (st == null) anyFree = true;
        }
        return Pick.of(anyFree ? Outcome.ALREADY_UNSKIPPED : Outcome.ALREADY_RAN);
    }
}
