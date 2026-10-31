package net.samsside.craftedpiratecatch.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UtilTest {

    @Test
    void durationsFormat() {
        assertEquals("45s", Durations.format(45_000));
        assertEquals("0s", Durations.format(-5));
        assertEquals("12m", Durations.format(12 * 60_000 + 30_000));
        assertEquals("1h 05m", Durations.format(65 * 60_000));
        assertEquals("2h 00m", Durations.format(120 * 60_000));
    }

    @Test
    void flatJsonRoundTrip() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("amount", "5");
        m.put("odd", "a\"b\\c\nd");
        assertEquals(m, FlatJson.decode(FlatJson.encode(m)));
        assertEquals(Map.of(), FlatJson.decode("{}"));
        assertEquals(Map.of(), FlatJson.decode("garbage"));
    }

    @Test
    void legacyAndMiniMessageMix() {
        assertEquals("<red>hi <bold>x", Text.legacyToMiniMessage("&chi §lx"));
        assertEquals("<#ff00aa>c", Text.legacyToMiniMessage("&#ff00aac"));
        assertEquals("plain hi", Text.plain(Text.render("&cplain <green>hi")));
    }
}
