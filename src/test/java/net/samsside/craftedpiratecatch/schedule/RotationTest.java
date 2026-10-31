package net.samsside.craftedpiratecatch.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    private static final List<LocalTime> SLOTS = List.of(
            LocalTime.of(13, 0), LocalTime.of(17, 0), LocalTime.of(21, 0), LocalTime.of(1, 0));

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
            assertEquals(expected[i], nov.slotTime(LocalDate.of(2026, 11, 2).plusDays(i)), "day " + i);
        }
    }

    @Test
    void lateNightSlotBelongsToPreviousRotationDay() {
        // Rotation day 4 (5 Nov) uses 01:00, which runs at 01:00 on 6 Nov.
        Rotation.Slot s = nov.slot(LocalDate.of(2026, 11, 5));
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
        Rotation.Slot s = nov.nextScheduled(london(2026, 11, 5, 23, 30), d -> false);
        assertNotNull(s);
        assertEquals(LocalDate.of(2026, 11, 5), s.day());
        assertEquals(london(2026, 11, 6, 1, 0), s.start());
    }

    @Test
    void midWindowReturnsCurrentEvent() {
        Rotation.Slot s = nov.nextScheduled(london(2026, 11, 3, 17, 30), d -> false);
        assertEquals(LocalDate.of(2026, 11, 3), s.day());
    }

    @Test
    void closedDaysAreSkipped() {
        Set<LocalDate> closed = Set.of(LocalDate.of(2026, 11, 3));
        Rotation.Slot s = nov.nextScheduled(london(2026, 11, 3, 10, 0), closed::contains);
        assertEquals(LocalDate.of(2026, 11, 4), s.day());
    }

    @Test
    void nothingAfterLastDay() {
        // Last day 30 Nov: index 28 -> 13:00. After it ends, the season is over.
        assertNotNull(nov.nextScheduled(london(2026, 11, 30, 13, 30), d -> false));
        assertNull(nov.nextScheduled(london(2026, 11, 30, 14, 0), d -> false));
        assertNull(nov.slot(LocalDate.of(2026, 12, 1)));
        assertNull(nov.slot(LocalDate.of(2026, 11, 1)));
    }

    @Test
    void beforeFirstDayReturnsFirstEvent() {
        Rotation.Slot s = nov.nextScheduled(london(2026, 10, 31, 12, 0), d -> false);
        assertEquals(LocalDate.of(2026, 11, 2), s.day());
        assertEquals(london(2026, 11, 2, 13, 0), s.start());
    }

    @Test
    void upcomingListsOpenDaysInOrder() {
        List<Rotation.Slot> up = nov.upcoming(london(2026, 11, 2, 0, 0), 8, d -> false);
        assertEquals(8, up.size());
        assertEquals(LocalDate.of(2026, 11, 2), up.get(0).day());
        assertEquals(LocalDate.of(2026, 11, 9), up.get(7).day());
    }

    @Test
    void dstGapResolvesForward() {
        // UK clocks go forward at 01:00 on 28 Mar 2027: 01:00 local does not exist.
        Rotation r = rotation(LocalDate.of(2027, 3, 24), LocalDate.of(2027, 3, 31));
        // index 3 = 27 Mar -> 01:00 slot -> 28 Mar 01:00 local (gap) -> 02:00 BST = 01:00Z.
        Rotation.Slot s = r.slot(LocalDate.of(2027, 3, 27));
        assertEquals(Instant.parse("2027-03-28T01:00:00Z"), s.start());
    }

    @Test
    void dstOverlapUsesEarlierOffset() {
        // UK clocks go back at 02:00 BST on 25 Oct 2026: 01:00 local happens twice.
        Rotation r = rotation(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 31));
        // index 3 = 24 Oct -> 01:00 slot -> 25 Oct 01:00 local; earlier offset is BST (+01:00).
        Rotation.Slot s = r.slot(LocalDate.of(2026, 10, 24));
        assertEquals(Instant.parse("2026-10-25T00:00:00Z"), s.start());
    }
}
