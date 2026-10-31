package net.samsside.craftedpiratecatch.event;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.samsside.craftedpiratecatch.reward.RewardAllocator;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Ranked;

/** Immutable sorted leaderboard (points desc, earlier last increase, name). 0-point players excluded. */
public record Snapshot(List<Ranked> ranked, Map<UUID, Integer> rankOf) {

    public static final Snapshot EMPTY = new Snapshot(List.of(), Map.of());

    public static Snapshot of(List<Ranked> input) {
        List<Ranked> sorted = RewardAllocator.rank(input);
        Map<UUID, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            ranks.put(sorted.get(i).uuid(), i + 1);
        }
        return new Snapshot(List.copyOf(sorted), Map.copyOf(ranks));
    }

    /** 1-based rank entry, or null. */
    public Ranked at(int rank) {
        return rank >= 1 && rank <= ranked.size() ? ranked.get(rank - 1) : null;
    }
}
