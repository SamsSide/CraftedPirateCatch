package net.samsside.craftedpiratecatch.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.samsside.craftedpiratecatch.command.SessionPicker.Outcome;
import net.samsside.craftedpiratecatch.command.SessionPicker.Pick;
import net.samsside.craftedpiratecatch.schedule.Rotation;
import net.samsside.craftedpiratecatch.storage.Database;
import org.junit.jupiter.api.Test;

class SessionPickerTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 7);
    private static final List<Rotation.Slot> TODAY = new Rotation(LONDON,
            List.of(List.of(LocalTime.of(13, 0), LocalTime.of(21, 0))), LocalTime.of(6, 0),
            LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30), Duration.ofMinutes(15)).slots(DAY);

    private static Instant london(int h, int m) {
        return DAY.atTime(h, m).atZone(LONDON).toInstant();
    }

    private static Map<Integer, String> st(Object... kv) {
        Map<Integer, String> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((Integer) kv[i], (String) kv[i + 1]);
        }
        return map;
    }

    private static void check(Pick p, Outcome o, Integer session) {
        assertEquals(o, p.outcome());
        if (session != null) {
            assertEquals(session.intValue(), p.slot().session());
        }
    }

    private static Pick start(Integer req, Instant now, Map<Integer, String> m) {
        return SessionPicker.start(TODAY, req, now, s -> m.get(s.session()));
    }

    private static Pick skip(Integer req, Instant now, Map<Integer, String> m) {
        return SessionPicker.skip(TODAY, req, now, s -> m.get(s.session()));
    }

    private static Pick unskip(Integer req, Instant now, Map<Integer, String> m) {
        return SessionPicker.unskip(TODAY, req, now, s -> m.get(s.session()), s -> false);
    }

    @Test
    void startNoArg() {
        check(start(null, london(12, 0), st()), Outcome.OK, 0);
        check(start(null, london(14, 0), st(0, Database.FINISHED)), Outcome.OK, 1);
        check(start(null, london(14, 0), st()), Outcome.OK, 1);
        check(start(null, london(12, 0), st(0, Database.FINISHED, 1, Database.FINISHED)), Outcome.ALREADY_RAN,
                null);
    }

    @Test
    void startWithArg() {
        check(start(2, london(12, 0), st()), Outcome.OK, 1);
        check(start(1, london(12, 0), st(0, Database.FINISHED)), Outcome.ALREADY_RAN, null);
        check(start(0, london(12, 0), st()), Outcome.BAD_ARG, null);
        check(start(3, london(12, 0), st()), Outcome.BAD_ARG, null);
    }

    @Test
    void skipNoArg() {
        check(skip(null, london(12, 0), st()), Outcome.OK, 0);
        check(skip(null, london(12, 0), st(0, Database.SKIPPED)), Outcome.OK, 1);
        check(skip(null, london(12, 0), st(0, Database.SKIPPED, 1, Database.SKIPPED)), Outcome.ALREADY_SKIPPED,
                null);
        check(skip(null, london(21, 5), st(0, Database.FINISHED, 1, Database.RUNNING)), Outcome.ALREADY_RAN, null);
    }

    @Test
    void skipWithArg() {
        check(skip(2, london(12, 0), st()), Outcome.OK, 1);
        check(skip(1, london(13, 5), st()), Outcome.ALREADY_RAN, null);
        check(skip(2, london(12, 0), st(1, Database.SKIPPED)), Outcome.ALREADY_SKIPPED, null);
        check(skip(3, london(12, 0), st()), Outcome.BAD_ARG, null);
    }

    @Test
    void unskipNoArg() {
        check(unskip(null, london(14, 0), st(0, Database.FINISHED, 1, Database.SKIPPED)), Outcome.OK, 1);
        check(unskip(null, london(12, 0), st()), Outcome.ALREADY_UNSKIPPED, null);
        Map<Integer, String> whole = st(0, Database.SKIPPED, 1, Database.SKIPPED);
        check(SessionPicker.unskip(TODAY, null, london(12, 0), s -> whole.get(s.session()), s -> true),
                Outcome.ALREADY_RAN, null);
    }

    @Test
    void unskipWithArg() {
        check(unskip(2, london(14, 0), st(1, Database.SKIPPED)), Outcome.OK, 1);
        check(unskip(2, london(14, 0), st()), Outcome.ALREADY_UNSKIPPED, null);
        check(unskip(1, london(13, 5), st()), Outcome.ALREADY_RAN, null);
        check(unskip(5, london(14, 0), st()), Outcome.BAD_ARG, null);
    }
}
