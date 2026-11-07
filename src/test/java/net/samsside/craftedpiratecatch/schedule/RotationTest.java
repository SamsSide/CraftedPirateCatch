package net.samsside.craftedpiratecatch.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RotationTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final List<List<LocalTime>> SLOTS = List.of(
            List.of(LocalTime.of(13, 0)), List.of(LocalTime.of(17, 0)),
            List.of(LocalTime.of(21, 0)), List.of(LocalTime.of(1, 0)));

    private static Rotation rotation(LocalDate first, LocalDate last) {
        return new Rotation(LONDON, SLOTS, LocalTime.of(6, 0), first, last, Duration.ofMinutes(60));
    }

    private static Instant london(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, LONDON).toInstant();
    }

    private final Rotation nov = rotation(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30));

    @Test
    void slotsCycleOverEightDays() {
        LocalTime[] expected = {
                LocalTime.of(13, 0), LocalTime.of(17, 0), LocalTime.of(21, 0), LocalTime.of(1, 0),
                LocalTime.of(13, 0), LocalTime.of(17, 0), LocalTime.of(21, 0), LocalTime.of(1, 0)};
        for (int i = 0; i < 8; i++) {
            assertEquals(expected[i], nov.slotTimes(LocalDate.of(2026, 11, 2).plusDays(i)).get(0), "day " + i);
        }
    }

    @Test
    void lateNightSlotBelongsToPreviousRotationDay() {
        // Rotation day 4 (5 Nov) uses 01:00, which runs at 01:00 on 6 Nov.
        Rotation.Slot s = nov.slot(LocalDate.of(2026, 11, 5), 0);
        assertEquals(LocalTime.of(1, 0), s.time());
        assertEquals(london(2026, 11, 6, 1, 0), s.start());
        assertEquals(london(2026, 11, 6, 2, 0), s.end());
        // Staff "today" at 00:30 on 6 Nov is still rotation day 5 Nov.
        assertEquals(LocalDate.of(2026, 11, 5), nov.todayAt(london(2026, 11, 6, 0, 30)));
        assertEquals(LocalDate.of(2026, 11, 6), nov.todayAt(london(2026, 11, 6, 6, 0)));
    }

    @Test
    void nextScheduledAcrossMidnight() {
        // 23:30 on 5 Nov: the 21:00 day (4 Nov) is done, next is the 01:00 session of 5 Nov.
        Rotation.Slot s = nov.nextScheduled(london(2026, 11, 5, 23, 30), x -> false);
        assertNotNull(s);
        assertEquals(LocalDate.of(2026, 11, 5), s.day());
        assertEquals(london(2026, 11, 6, 1, 0), s.start());
    }

    @Test
    void midWindowReturnsCurrentEvent() {
        Rotation.Slot s = nov.nextScheduled(london(2026, 11, 3, 17, 30), x -> false);
        assertEquals(LocalDate.of(2026, 11, 3), s.day());
    }

    @Test
    void closedDaysAreSkipped() {
        Set<LocalDate> closed = Set.of(LocalDate.of(2026, 11, 3));
        Rotation.Slot s = nov.nextScheduled(london(2026, 11, 3, 10, 0), x -> closed.contains(x.day()));
        assertEquals(LocalDate.of(2026, 11, 4), s.day());
    }

    @Test
    void nothingAfterLastDay() {
        // Last day 30 Nov: index 28 -> 13:00. After it ends, the season is over.
        assertNotNull(nov.nextScheduled(london(2026, 11, 30, 13, 30), x -> false));
        assertNull(nov.nextScheduled(london(2026, 11, 30, 14, 0), x -> false));
        assertNull(nov.slot(LocalDate.of(2026, 12, 1), 0));
        assertNull(nov.slot(LocalDate.of(2026, 11, 1), 0));
    }

    @Test
    void beforeFirstDayReturnsFirstEvent() {
        Rotation.Slot s = nov.nextScheduled(london(2026, 10, 31, 12, 0), x -> false);
        assertEquals(LocalDate.of(2026, 11, 2), s.day());
        assertEquals(london(2026, 11, 2, 13, 0), s.start());
    }

    @Test
    void upcomingListsOpenDaysInOrder() {
        List<Rotation.Slot> up = nov.upcoming(london(2026, 11, 2, 0, 0), 8, x -> false);
        assertEquals(8, up.size());
        assertEquals(LocalDate.of(2026, 11, 2), up.get(0).day());
        assertEquals(LocalDate.of(2026, 11, 9), up.get(7).day());
    }

    @Test
    void dstGapResolvesForward() {
        // UK clocks go forward at 01:00 on 28 Mar 2027: 01:00 local does not exist.
        Rotation r = rotation(LocalDate.of(2027, 3, 24), LocalDate.of(2027, 3, 31));
        // index 3 = 27 Mar -> 01:00 slot -> 28 Mar 01:00 local (gap) -> 02:00 BST = 01:00Z.
        Rotation.Slot s = r.slot(LocalDate.of(2027, 3, 27), 0);
        assertEquals(Instant.parse("2027-03-28T01:00:00Z"), s.start());
    }

    @Test
    void dstOverlapUsesEarlierOffset() {
        // UK clocks go back at 02:00 BST on 25 Oct 2026: 01:00 local happens twice.
        Rotation r = rotation(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 31));
        // index 3 = 24 Oct -> 01:00 slot -> 25 Oct 01:00 local; earlier offset is BST (+01:00).
        Rotation.Slot s = r.slot(LocalDate.of(2026, 10, 24), 0);
        assertEquals(Instant.parse("2026-10-25T00:00:00Z"), s.start());
    }

    private final Rotation pair = new Rotation(LONDON, List.of(List.of(LocalTime.of(13, 0), LocalTime.of(21, 0))),
            LocalTime.of(6, 0), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30), Duration.ofMinutes(15));

    @Test
    void fixedPairEveryDay() {
        LocalDate d = LocalDate.of(2026, 11, 7);
        List<Rotation.Slot> s = pair.slots(d);
        assertEquals(2, s.size());
        assertEquals(london(2026, 11, 7, 13, 0), s.get(0).start());
        assertEquals(london(2026, 11, 7, 13, 15), s.get(0).end());
        assertEquals(london(2026, 11, 7, 21, 0), s.get(1).start());
        assertEquals("1/2", s.get(0).label());
        assertEquals("2/2", s.get(1).label());
        // under way: nextScheduled returns it, nextStart skips to the 21:00 session
        assertEquals(0, pair.nextScheduled(london(2026, 11, 7, 13, 5), x -> false).session());
        assertEquals(london(2026, 11, 7, 21, 0), pair.nextStart(london(2026, 11, 7, 13, 5), x -> false).start());
        // after the second session: next day's first
        Rotation.Slot n = pair.nextStart(london(2026, 11, 7, 21, 20), x -> false);
        assertEquals(LocalDate.of(2026, 11, 8), n.day());
        assertEquals(0, n.session());
        List<Rotation.Slot> up = pair.upcoming(london(2026, 11, 7, 12, 0), 3, x -> false);
        assertEquals(List.of("2026-11-07#0", "2026-11-07#1", "2026-11-08#0"),
                up.stream().map(x -> x.day() + "#" + x.session()).toList());
    }

    @Test
    void pairWithLateNightOrdersByRollover() {
        Rotation r = new Rotation(LONDON, List.of(List.of(LocalTime.of(1, 0), LocalTime.of(21, 0))),
                LocalTime.of(6, 0), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30), Duration.ofMinutes(15));
        assertEquals(LocalTime.of(21, 0), r.slot(LocalDate.of(2026, 11, 7), 0).time());
        assertEquals(london(2026, 11, 8, 1, 0), r.slot(LocalDate.of(2026, 11, 7), 1).start());
    }

    @Test
    void closedSessionSkipsOnlyThatSession() {
        Rotation.Slot s = pair.nextScheduled(london(2026, 11, 7, 12, 0),
                x -> x.day().equals(LocalDate.of(2026, 11, 7)) && x.session() == 0);
        assertEquals(1, s.session());
    }

    @Test
    void outOfRangeSessionIsNull() {
        assertNull(pair.slot(LocalDate.of(2026, 11, 7), 2));
        assertNull(pair.slot(LocalDate.of(2026, 12, 1), 0));
        assertEquals(0, pair.sessionsOn(LocalDate.of(2026, 12, 1)));
    }

    @Test
    void rejectsBadEntries() {
        assertThrows(IllegalArgumentException.class, () -> new Rotation(LONDON, List.of(List.of()),
                LocalTime.of(6, 0), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30), Duration.ofMinutes(15)));
        assertThrows(IllegalArgumentException.class, () -> new Rotation(LONDON,
                List.of(List.of(LocalTime.of(1, 0), LocalTime.of(2, 0), LocalTime.of(3, 0))),
                LocalTime.of(6, 0), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30), Duration.ofMinutes(15)));
    }
}
