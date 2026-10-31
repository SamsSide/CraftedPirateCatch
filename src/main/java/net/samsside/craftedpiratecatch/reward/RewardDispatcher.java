package net.samsside.craftedpiratecatch.reward;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.samsside.craftedpiratecatch.CraftedPirateCatch;
import net.samsside.craftedpiratecatch.storage.Database.PendingReward;
import net.samsside.craftedpiratecatch.util.FlatJson;
import net.samsside.craftedpiratecatch.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Drains {@code pending_rewards} (Phase 7.3). Every reward is a row; a row is deleted (and the
 * delete confirmed) BEFORE its command runs, and an in-flight set stops the same row running
 * twice. Rows for offline players, or players without enough free slots, wait.
 */
public final class RewardDispatcher {

    private final CraftedPirateCatch plugin;
    private final Logger log;
    /** Rows whose delete is in progress and whose command has not run yet. */
    private final Map<Long, PendingReward> inFlight = new ConcurrentHashMap<>();
    private final Set<UUID> draining = ConcurrentHashMap.newKeySet();
    /** Players who asked for a drain while one was running; drained again when it finishes. */
    private final Set<UUID> redrain = ConcurrentHashMap.newKeySet();
    private final Set<UUID> queuedNotified = ConcurrentHashMap.newKeySet();
    /** Row ids that were held back for lack of space (so delivery also sends reward-delivered). */
    private final Set<Long> deferred = ConcurrentHashMap.newKeySet();

    public RewardDispatcher(CraftedPirateCatch plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /** Drain every online player who has pending rows. */
    public void drainOnline() {
        plugin.db().pendingUuids().whenComplete((uuids, ex) -> plugin.sync(() -> {
            if (ex != null) {
                log.log(Level.SEVERE, "Could not list pending rewards", ex);
                return;
            }
            for (UUID u : uuids) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) {
                    drain(p);
                }
            }
        }));
    }

    /** Main thread. Deliver what fits for this player, one row at a time. */
    public void drain(Player player) {
        UUID u = player.getUniqueId();
        if (!draining.add(u)) {
            redrain.add(u);
            return;
        }
        plugin.db().loadPending(u).whenComplete((rows, ex) -> plugin.sync(() -> {
            if (ex != null) {
                log.log(Level.SEVERE, "Could not load pending rewards for " + player.getName(), ex);
                finish(u);
                return;
            }
            processNext(u, new ArrayDeque<>(rows));
        }));
    }

    private void processNext(UUID u, Deque<PendingReward> queue) {
        Player p = Bukkit.getPlayer(u);
        if (p == null) {
            finish(u);
            return;
        }
        PendingReward row;
        while ((row = queue.poll()) != null) {
            if (inFlight.containsKey(row.id())) {
                continue;
            }
            if (freeSlots(p) < row.needsSlots()) {
                deferred.add(row.id());
                if (queuedNotified.add(u)) {
                    plugin.messages().send(p, "reward-queued");
                }
                continue;
            }
            final PendingReward claimed = row;
            inFlight.put(claimed.id(), claimed);
            plugin.db().deletePending(claimed.id()).whenComplete((deleted, ex) -> plugin.sync(() -> {
                try {
                    if (ex != null) {
                        log.log(Level.SEVERE, "Could not claim pending reward #" + claimed.id(), ex);
                    } else if (Boolean.TRUE.equals(deleted)) {
                        deliver(u, claimed);
                    }
                } finally {
                    inFlight.remove(claimed.id());
                }
                processNext(u, queue);
            }));
            return;
        }
        finish(u);
    }

    private void finish(UUID u) {
        draining.remove(u);
        if (redrain.remove(u)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                drain(p);
            }
        }
    }

    private void deliver(UUID u, PendingReward row) {
        Player p = Bukkit.getPlayer(u);
        if (p == null || freeSlots(p) < row.needsSlots()) {
            // Claimed but cannot be delivered right now: put it back under the same id.
            plugin.db().restorePending(List.of(row)).exceptionally(ex -> {
                log.log(Level.SEVERE, "Could not re-queue reward #" + row.id() + " (" + row.command() + ") for "
                        + u + " - run it manually", ex);
                return null;
            });
            if (p != null) {
                deferred.add(row.id());
                if (queuedNotified.add(u)) {
                    plugin.messages().send(p, "reward-queued");
                }
            }
            return;
        }
        if (!row.command().isBlank()) {
            try {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), row.command());
                if (ok) {
                    log.info("Reward delivered to " + p.getName() + ": " + row.command());
                } else {
                    log.warning("Reward command for " + p.getName() + " reported failure: " + row.command());
                }
            } catch (RuntimeException e) {
                log.log(Level.SEVERE, "Reward command threw for " + p.getName() + " - run it manually: "
                        + row.command(), e);
            }
        }
        if (!row.messageKey().isBlank()) {
            List<TagResolver> resolvers = new ArrayList<>();
            for (Map.Entry<String, String> e : FlatJson.decode(row.messageArgs()).entrySet()) {
                resolvers.add(Text.placeholder(e.getKey(), e.getValue()));
            }
            plugin.messages().send(p, row.messageKey(), resolvers.toArray(new TagResolver[0]));
        }
        if (deferred.remove(row.id())) {
            plugin.messages().send(p, "reward-delivered");
        }
    }

    public static int freeSlots(Player p) {
        int free = 0;
        for (ItemStack item : p.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                free++;
            }
        }
        return free;
    }

    public void onQuit(UUID u) {
        queuedNotified.remove(u);
    }

    /**
     * Disable: rows that were claimed (deleted) but whose command never ran are restored under
     * their original ids, so nothing is lost. Call before the DB shuts down.
     */
    public List<PendingReward> inFlightRows() {
        return new ArrayList<>(inFlight.values());
    }
}
