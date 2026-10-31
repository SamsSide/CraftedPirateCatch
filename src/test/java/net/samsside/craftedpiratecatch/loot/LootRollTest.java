package net.samsside.craftedpiratecatch.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LootRollTest {

    private static LootTier t(String id, double chance, int points) {
        return new LootTier(id, id, chance, points, "", false);
    }

    private static final List<LootTier> DEFAULTS = List.of(
            t("common", 25.0, 1), t("uncommon", 12.0, 3), t("rare", 6.0, 8),
            t("epic", 1.7, 20), t("legendary", 0.3, 50));

    @Test
    void defaultTableMapsRollsInOrder() {
        LootTable table = LootTable.of(DEFAULTS, w -> { });
        assertEquals("common", table.roll(0.0).id());
        assertEquals("common", table.roll(0.25).id());
        assertEquals("uncommon", table.roll(0.2501).id());
        assertEquals("uncommon", table.roll(0.37).id());
        assertEquals("rare", table.roll(0.43).id());
        assertEquals("epic", table.roll(0.447).id());
        assertEquals("legendary", table.roll(0.4499).id());
        assertEquals("legendary", table.roll(0.45).id());
        assertNull(table.roll(0.4501));
        assertNull(table.roll(0.99));
    }

    @Test
    void chancesOverHundredAreScaled() {
        List<String> warnings = new ArrayList<>();
        LootTable table = LootTable.of(List.of(t("a", 150, 1), t("b", 50, 2)), warnings::add);
        assertEquals(1, warnings.size());
        assertEquals(75.0, table.tiers().get(0).chance(), 1e-9);
        assertEquals(25.0, table.tiers().get(1).chance(), 1e-9);
        assertEquals("a", table.roll(0.75).id());
        assertEquals("b", table.roll(0.7501).id());
        assertEquals("b", table.roll(0.9999).id());
    }

    @Test
    void negativeValuesAreClamped() {
        List<String> warnings = new ArrayList<>();
        LootTable table = LootTable.of(List.of(t("neg", -5, -3), t("ok", 10, 2)), warnings::add);
        assertEquals(2, warnings.size());
        assertEquals(0.0, table.tiers().get(0).chance());
        assertEquals(0, table.tiers().get(0).points());
        // A zero-chance tier is never rolled, even at roll 0.
        assertEquals("ok", table.roll(0.0).id());
        assertNull(table.roll(0.2));
    }

    @Test
    void duplicateIdsDropped() {
        List<String> warnings = new ArrayList<>();
        LootTable table = LootTable.of(List.of(t("x", 10, 1), t("x", 10, 1)), warnings::add);
        assertEquals(1, table.tiers().size());
        assertTrue(warnings.get(0).contains("Duplicate"));
    }
}
