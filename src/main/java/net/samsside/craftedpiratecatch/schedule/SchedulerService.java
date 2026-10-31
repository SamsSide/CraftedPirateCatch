package net.samsside.craftedpiratecatch.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import net.samsside.craftedpiratecatch.CraftedPirateCatch;
import net.samsside.craftedpiratecatch.config.PluginConfig;
import net.samsside.craftedpiratecatch.event.ActiveEvent;
import net.samsside.craftedpiratecatch.event.EventManager;
import net.samsside.craftedpiratecatch.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Sync 20-tick loop: sends warnings, starts events at their window, records MISSED days,
 * and ends running events (via {@link EventManager#tick}).
 */
public final class SchedulerService {

    private static final long LATE_WARNING_GRACE_MS = 60_000L;

    private final CraftedPirateCatch plugin;
    /** rotation day + ":" + minutes, for warnings already sent (or deliberately skipped). */
    private final Set<String> warned = new HashSet<>();
    private BukkitTask task;

    public SchedulerService(CraftedPirateCatch plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        EventManager events = plugin.events();
        try {
            events.tick(now);
            PluginConfig cfg = plugin.cfg();
            Rotation rotation = cfg.rotation;
            if (rotation == null) {
                return;
            }
            Instant nowI = Instant.ofEpochMilli(now);

            for (LocalDate d = rotation.firstDay(); !d.isAfter(rotation.lastDay()); d = d.plusDays(1)) {
                Rotation.Slot slot = rotation.slot(d);
                if (slot.start().isAfter(nowI)) {
                    break; // later days start later still
                }
                if (events.hasRow(d)) {
                    continue;
                }
                if (!nowI.isBefore(slot.end())) {
                    events.markMissed(slot);
                } else {
                    ActiveEvent current = events.active();
                    if (current == null || current.test) {
                        events.startScheduled(slot); // late start keeps the original end time
                    }
                }
            }

            Rotation.Slot next = rotation.nextScheduled(nowI, events::isClosed);
            if (next != null && !events.hasRow(next.day()) && nowI.isBefore(next.start())) {
                for (int minutes : cfg.warningsMinutes) {
                    String key = next.day() + ":" + minutes;
                    if (warned.contains(key)) {
                        continue;
                    }
                    long warnAt = next.start().toEpochMilli() - minutes * 60_000L;
                    if (now < warnAt) {
                        continue;
                    }
                    warned.add(key);
                    if (now - warnAt <= LATE_WARNING_GRACE_MS) {
                        plugin.messages().broadcast("warning-broadcast", false,
                                Text.placeholder("minutes", String.valueOf(minutes)));
                        plugin.playSoundAll("warning");
                    }
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Scheduler tick failed", e);
        }
    }
}
