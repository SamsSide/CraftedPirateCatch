package net.samsside.craftedpiratecatch.loot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The ordered pirate loot table. Rolled once per qualifying catch: walk the tiers in order
 * accumulating chance; the first tier whose cumulative chance is {@code >= roll * 100} wins.
 * Otherwise there is no pirate catch.
 */
public final class LootTable {

    private final List<LootTier> tiers;

    private LootTable(List<LootTier> tiers) {
        this.tiers = List.copyOf(tiers);
    }

    /**
     * Validate raw tiers: negative chances become 0, negative points become 0, duplicate ids
     * are dropped, and a total above 100 is scaled proportionally down to 100. Each fix is
     * reported to {@code warn}.
     */
    public static LootTable of(List<LootTier> raw, Consumer<String> warn) {
        List<LootTier> cleaned = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        double total = 0;
        for (LootTier t : raw) {
            if (!ids.add(t.id())) {
                warn.accept("Duplicate loot id '" + t.id() + "' ignored.");
                continue;
            }
            double chance = t.chance();
            if (chance < 0 || Double.isNaN(chance)) {
                warn.accept("Loot '" + t.id() + "' has a negative chance; treated as 0.");
                chance = 0;
            }
            int points = t.points();
            if (points < 0) {
                warn.accept("Loot '" + t.id() + "' has negative points; treated as 0.");
                points = 0;
            }
            total += chance;
            cleaned.add(new LootTier(t.id(), t.name(), chance, points, t.trophyCommand(), t.broadcast()));
        }
        if (total > 100.0) {
            warn.accept("Loot chances total " + total + "% (> 100); scaled proportionally to 100%.");
            double factor = 100.0 / total;
            List<LootTier> scaled = new ArrayList<>(cleaned.size());
            for (LootTier t : cleaned) {
                scaled.add(new LootTier(t.id(), t.name(), t.chance() * factor, t.points(),
                        t.trophyCommand(), t.broadcast()));
            }
            cleaned = scaled;
        }
        return new LootTable(cleaned);
    }

    public List<LootTier> tiers() {
        return tiers;
    }

    /**
     * Map a uniform random value in [0, 1) to a tier, or null for no pirate catch. Tiers with
     * a chance of 0 can never be rolled.
     */
    public LootTier roll(double random) {
        double target = random * 100.0;
        double cumulative = 0;
        for (LootTier t : tiers) {
            if (t.chance() <= 0) {
                continue;
            }
            cumulative += t.chance();
            if (cumulative >= target) {
                return t;
            }
        }
        return null;
    }
}
