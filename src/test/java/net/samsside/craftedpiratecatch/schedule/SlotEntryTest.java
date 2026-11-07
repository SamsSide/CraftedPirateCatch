package net.samsside.craftedpiratecatch.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlotEntryTest {
    private static final LocalTime RO = LocalTime.of(6, 0);
    private static final Duration D15 = Duration.ofMinutes(15);

    private static SlotEntry.Parsed p(String raw) {
        return SlotEntry.parse(raw, RO, D15);
    }

    @Test void single() {
        assertEquals(List.of(LocalTime.of(13, 0)), p("13:00").times());
        assertNull(p("13:00").problem());
    }
    @Test void pair() {
        assertEquals(List.of(LocalTime.of(13, 0), LocalTime.of(21, 0)), p("13:00 + 21:00").times());
        assertEquals(List.of(LocalTime.of(13, 0), LocalTime.of(21, 0)), p(" 21:00+13:00 ").times());
        assertNull(p("13:00+21:00").problem());
    }
    @Test void lateNightSortsLast() {
        assertEquals(List.of(LocalTime.of(21, 0), LocalTime.of(1, 0)), p("01:00 + 21:00").times());
    }
    @Test void gapEqualToDurationIsValid() {
        assertNull(p("13:00 + 13:15").problem());
    }
    @Test void overlapFallsBackToFirst() {
        SlotEntry.Parsed r = p("13:10 + 13:00");
        assertEquals(List.of(LocalTime.of(13, 10)), r.times());
        assertNotNull(r.problem());
    }
    @Test void equalTimesInvalid() {
        assertEquals(List.of(LocalTime.of(13, 0)), p("13:00 + 13:00").times());
        assertNotNull(p("13:00 + 13:00").problem());
    }
    @Test void threeTimesInvalid() {
        assertEquals(List.of(LocalTime.of(13, 0)), p("13:00 + 17:00 + 21:00").times());
        assertNotNull(p("13:00 + 17:00 + 21:00").problem());
    }
    @Test void badPartFallsBackToFirstParseable() {
        assertEquals(List.of(LocalTime.of(13, 0)), p("25:00 + 13:00").times());
        assertNotNull(p("25:00 + 13:00").problem());
    }
    @Test void unparsableDropped() {
        assertTrue(p("abc").times().isEmpty());
        assertTrue(p("").times().isEmpty());
        assertTrue(p("+").times().isEmpty());
    }
    @Test void minutesAfterRollover() {
        assertEquals(0, SlotEntry.minutesAfter(LocalTime.of(6, 0), RO));
        assertEquals(19 * 60, SlotEntry.minutesAfter(LocalTime.of(1, 0), RO));
    }
}
