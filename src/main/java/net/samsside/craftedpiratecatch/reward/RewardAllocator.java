package net.samsside.craftedpiratecatch.reward;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure end-of-event reward allocation (Phase 7.1). No Bukkit, no database.
 */
public final class RewardAllocator {

    /** One ranked player. Only players with points > 0 are ever ranked. */
    public record Ranked(UUID uuid, String name, String ip, int points, long lastIncreaseMs) {
    }

    public enum Summary {
        /** The key was awarded. */
        KEY_AWARDED,
        /** Nobody scored. */
        NOBODY,
        /** Turnout was fine but every ranked player is on key cooldown. */
        NO_ELIGIBLE,
        /** Too few distinct IPs; no key, shards still paid. */
        NO_KEY_TURNOUT
    }

    public enum Kind { KEY, PLACEMENT_SHARDS, PARTICIPATION_SHARDS, COOLDOWN_NOTE }

    /** One reward (or note) for one player. {@code rank} is the raw 1-based rank. */
    public record Award(UUID uuid, String name, int rank, Kind kind, int amount) {
    }

    public record Allocation(Summary summary, Ranked keyRecipient, List<Ranked> ranked, List<Award> awards) {
    }

    /** Points desc, then earlier {@code lastIncreaseMs}, then name. */
    public static final Comparator<Ranked> ORDER = Comparator
            .comparingInt(Ranked::points).reversed()
            .thenComparingLong(Ranked::lastIncreaseMs)
            .thenComparing(Ranked::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(r -> r.uuid().toString());

    private RewardAllocator() {
    }

    /** Filter to points > 0 and sort by {@link #ORDER}. */
    public static List<Ranked> rank(List<Ranked> entries) {
        List<Ranked> out = new ArrayList<>();
        for (Ranked r : entries) {
            if (r.points() > 0) {
                out.add(r);
            }
        }
        out.sort(ORDER);
        return out;
    }

    public static Allocation allocate(List<Ranked> entries, Set<UUID> onCooldown, int minDistinctIps,
                                      int[] placementShards, int participationShards) {
        List<Ranked> ranked = rank(entries);
        if (ranked.isEmpty()) {
            return new Allocation(Summary.NOBODY, null, ranked, List.of());
        }

        Set<String> ips = new HashSet<>();
        for (Ranked r : ranked) {
            if (r.ip() != null && !r.ip().isBlank()) {
                ips.add(r.ip());
            }
        }
        boolean turnoutOk = ips.size() >= minDistinctIps;

        Ranked keyRecipient = null;
        if (turnoutOk) {
            for (Ranked r : ranked) {
                if (!onCooldown.contains(r.uuid())) {
                    keyRecipient = r;
                    break;
                }
            }
        }

        Summary summary;
        if (!turnoutOk) {
            summary = Summary.NO_KEY_TURNOUT;
        } else if (keyRecipient == null) {
            summary = Summary.NO_ELIGIBLE;
        } else {
            summary = Summary.KEY_AWARDED;
        }

        List<Award> awards = new ArrayList<>();
        Set<UUID> rewarded = new HashSet<>();
        for (int i = 0; i < ranked.size(); i++) {
            Ranked r = ranked.get(i);
            int rank = i + 1;
            if (keyRecipient != null && Objects.equals(r.uuid(), keyRecipient.uuid())) {
                awards.add(new Award(r.uuid(), r.name(), rank, Kind.KEY, 1));
                rewarded.add(r.uuid());
            } else if (i < placementShards.length && placementShards[i] > 0) {
                awards.add(new Award(r.uuid(), r.name(), rank, Kind.PLACEMENT_SHARDS, placementShards[i]));
                rewarded.add(r.uuid());
            }
        }
        if (participationShards > 0) {
            for (int i = 0; i < ranked.size(); i++) {
                Ranked r = ranked.get(i);
                if (!rewarded.contains(r.uuid())) {
                    awards.add(new Award(r.uuid(), r.name(), i + 1, Kind.PARTICIPATION_SHARDS, participationShards));
                }
            }
        }

        Ranked top = ranked.get(0);
        if (turnoutOk && onCooldown.contains(top.uuid())) {
            awards.add(new Award(top.uuid(), top.name(), 1, Kind.COOLDOWN_NOTE, 0));
        }

        return new Allocation(summary, keyRecipient, ranked, List.copyOf(awards));
    }
}
