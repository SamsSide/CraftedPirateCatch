package net.samsside.craftedpiratecatch.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.samsside.craftedpiratecatch.config.PluginConfig;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Allocation;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Award;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Ranked;
import net.samsside.craftedpiratecatch.storage.Database.FinalizePlan;
import net.samsside.craftedpiratecatch.storage.Database.PendingReward;
import net.samsside.craftedpiratecatch.storage.Database.ScoreRow;
import net.samsside.craftedpiratecatch.util.FlatJson;

/**
 * Turns final scores into an {@link Allocation} and the fully substituted
 * {@code pending_rewards} rows. Pure: runs on the DB thread with an immutable config.
 */
public final class RewardPlanner {

    private RewardPlanner() {
    }

    public static FinalizePlan<Allocation> plan(List<ScoreRow> scores, Set<UUID> cooldown, PluginConfig cfg,
                                                long nowMs, Consumer<String> warn) {
        List<Ranked> input = new ArrayList<>(scores.size());
        for (ScoreRow s : scores) {
            input.add(new Ranked(s.uuid(), s.name(), s.ip(), s.points(), s.lastIncreaseMs()));
        }
        Allocation al = RewardAllocator.allocate(input, cooldown, cfg.minDistinctIps, cfg.placementShards,
                cfg.participationShards);

        List<PendingReward> rows = new ArrayList<>();
        for (Award a : al.awards()) {
            Map<String, String> args = new LinkedHashMap<>();
            String command;
            String messageKey;
            int slots = cfg.requiredFreeSlots;
            switch (a.kind()) {
                case KEY -> {
                    command = substitute(cfg.keyCommand, a.name(), a.amount());
                    messageKey = "reward-key";
                }
                case PLACEMENT_SHARDS -> {
                    command = substitute(cfg.shardCommand, a.name(), a.amount());
                    messageKey = "reward-shards";
                    args.put("amount", String.valueOf(a.amount()));
                    args.put("rank", String.valueOf(a.rank()));
                }
                case PARTICIPATION_SHARDS -> {
                    command = substitute(cfg.shardCommand, a.name(), a.amount());
                    messageKey = "reward-participation";
                    args.put("amount", String.valueOf(a.amount()));
                }
                case COOLDOWN_NOTE -> {
                    command = "";
                    messageKey = "reward-cooldown-note";
                    args.put("hours", String.valueOf(cfg.keyCooldownHours));
                    slots = 0;
                }
                default -> throw new IllegalStateException("Unknown kind " + a.kind());
            }
            if (a.kind() != RewardAllocator.Kind.COOLDOWN_NOTE && command.isBlank()) {
                warn.accept("No command configured for " + a.kind() + " reward to " + a.name()
                        + "; only the message will be sent.");
                slots = 0;
            }
            rows.add(new PendingReward(0, a.uuid(), command, messageKey, FlatJson.encode(args), slots, nowMs));
        }
        UUID keyWinner = al.keyRecipient() == null ? null : al.keyRecipient().uuid();
        return new FinalizePlan<>(keyWinner, rows, al);
    }

    /** Replace {@code %player%} and {@code %amount%}. */
    public static String substitute(String command, String player, int amount) {
        if (command == null) {
            return "";
        }
        return command.replace("%player%", player).replace("%amount%", String.valueOf(amount)).trim();
    }
}
