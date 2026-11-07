package net.samsside.craftedpiratecatch.event;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.samsside.craftedpiratecatch.CraftedPirateCatch;
import net.samsside.craftedpiratecatch.config.Messages;
import net.samsside.craftedpiratecatch.config.PluginConfig;
import net.samsside.craftedpiratecatch.loot.LootTier;
import net.samsside.craftedpiratecatch.reward.RewardAllocator;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Allocation;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Ranked;
import net.samsside.craftedpiratecatch.reward.RewardPlanner;
import net.samsside.craftedpiratecatch.schedule.Rotation;
import net.samsside.craftedpiratecatch.storage.Database;
import net.samsside.craftedpiratecatch.storage.Database.EventRow;
import net.samsside.craftedpiratecatch.storage.Database.PendingReward;
import net.samsside.craftedpiratecatch.storage.Database.ScoreRow;
import net.samsside.craftedpiratecatch.util.Durations;
import net.samsside.craftedpiratecatch.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns the running event: start/stop/cancel/test, scoring, trophies, the leaderboard
 * snapshot, the boss bar, score flushing and exactly-once finalize. Main thread only, except
 * read-only accessors used by PlaceholderAPI.
 */
public final class EventManager {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final CraftedPirateCatch plugin;
    private final Logger log;

    private volatile ActiveEvent active;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    /** (rotation_day, session) -> status, mirroring the events table (kept in memory; DB is written async). */
    private final Map<SessionKey, String> sessions = new ConcurrentHashMap<>();
    /** rotation_day -> status of a pre-upgrade whole-day (session -1) row, which closes every session of its day. */
    private final Map<LocalDate, String> wholeDays = new ConcurrentHashMap<>();

    private BossBar bossBar;
    private final Set<UUID> bossViewers = new HashSet<>();
    private BukkitTask flushTask;
    private BukkitTask snapshotTask;

    public EventManager(CraftedPirateCatch plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    // ================================================================== queries

    public ActiveEvent active() {
        return active;
    }

    public boolean isLive() {
        ActiveEvent ev = active;
        return ev != null && !ev.finalizing;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** The recorded status of {@code day}'s {@code session} (a whole-day row wins), or null if none. */
    public String status(LocalDate day, int session) {
        String whole = wholeDays.get(day);
        return whole != null ? whole : sessions.get(new SessionKey(day, session));
    }

    public String status(Rotation.Slot s) {
        return status(s.day(), s.session());
    }

    /** True if the session has its own row or its day has a whole-day row. */
    public boolean isRecorded(Rotation.Slot s) {
        return status(s) != null;
    }

    public boolean isClosed(Rotation.Slot s) {
        String status = status(s);
        return status != null && Database.CLOSED.contains(status);
    }

    /** True if {@code d} has a pre-upgrade whole-day row. */
    public boolean isWholeDay(LocalDate d) {
        return wholeDays.containsKey(d);
    }

    private void remember(LocalDate day, int session, String status) {
        if (session == Database.WHOLE_DAY) {
            wholeDays.put(day, status);
        } else {
            sessions.put(new SessionKey(day, session), status);
        }
    }

    private static String sessionText(int s) {
        return s == Database.WHOLE_DAY ? "" : " session " + (s + 1);
    }

    /** Points of {@code uuid} in the running event (live map), 0 if none. */
    public int scoreOf(UUID uuid) {
        ActiveEvent ev = active;
        if (ev == null) {
            return 0;
        }
        ScoreEntry e = ev.scores.get(uuid);
        return e == null ? 0 : e.points();
    }

    // ================================================================== lifecycle

    /** Enable: load the events table (synchronous, allowed during enable) and resume/finalize. */
    public void recover() throws Exception {
        List<EventRow> rows = plugin.db().loadEvents().get(30, TimeUnit.SECONDS);
        sessions.clear();
        wholeDays.clear();
        List<EventRow> running = new ArrayList<>();
        for (EventRow r : rows) {
            LocalDate d = parseDay(r.rotationDay());
            if (d == null) {
                log.warning("Ignoring events row " + r.id() + " with bad rotation_day '" + r.rotationDay() + "'");
                continue;
            }
            remember(d, r.session(), r.status());
            if (Database.RUNNING.equals(r.status()) && !r.finalized()) {
                running.add(r);
            }
        }
        log.info("Loaded " + rows.size() + " event record(s).");
        long now = System.currentTimeMillis();
        for (EventRow r : running) {
            LocalDate d = parseDay(r.rotationDay());
            ActiveEvent ev = new ActiveEvent(false, d, r.session(), r.slot(), r.startMs(), r.endMs());
            ev.id.set(r.id());
            if (now < r.endMs() && active == null) {
                for (ScoreRow s : plugin.db().loadScores(r.id()).get(30, TimeUnit.SECONDS)) {
                    ev.scores.put(s.uuid(), ScoreEntry.fromRow(s));
                }
                ev.trophies.addAll(plugin.db().loadTrophies(r.id()).get(30, TimeUnit.SECONDS));
                beginRuntime(ev, false);
                log.info("Resumed the running event for rotation day " + d + sessionText(r.session()) + " (" + ev.scores.size()
                        + " score(s), ends in " + Durations.format(r.endMs() - now) + ").");
            } else {
                log.info("Event for rotation day " + d + sessionText(r.session())
                        + " ended while the server was down - finalizing now.");
                finalizeLive(ev);
            }
        }
    }

    /** Reload: refresh the day cache from the DB (async), keeping the running event. */
    public void reloadDays(Runnable then) {
        plugin.db().loadEvents().whenComplete((rows, ex) -> plugin.sync(() -> {
            if (ex != null) {
                log.log(Level.SEVERE, "Could not reload event records", ex);
            } else {
                sessions.clear();
                wholeDays.clear();
                for (EventRow r : rows) {
                    LocalDate d = parseDay(r.rotationDay());
                    if (d != null) {
                        remember(d, r.session(), r.status());
                    }
                }
            }
            if (then != null) {
                then.run();
            }
        }));
    }

    /** (Re)start the flush and snapshot timers from the current config. */
    public void restartTimers() {
        if (flushTask != null) {
            flushTask.cancel();
        }
        if (snapshotTask != null) {
            snapshotTask.cancel();
        }
        PluginConfig cfg = plugin.cfg();
        long flushTicks = cfg.flushIntervalSeconds * 20L;
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushAsync, flushTicks, flushTicks);
        long snapTicks = cfg.refreshSeconds * 20L;
        snapshotTask = Bukkit.getScheduler().runTaskTimer(plugin, this::rebuildSnapshot, snapTicks, snapTicks);
    }

    /** Disable: flush scores synchronously and hide the boss bar. The event resumes on restart. */
    public void shutdown() {
        if (flushTask != null) {
            flushTask.cancel();
        }
        if (snapshotTask != null) {
            snapshotTask.cancel();
        }
        ActiveEvent ev = active;
        if (ev != null && !ev.test && !ev.finalizing) {
            try {
                plugin.db().upsertScores(ev::id, ev.allRows()).get(10, TimeUnit.SECONDS);
                log.info("Flushed " + ev.scores.size() + " score(s) for the running event; it resumes on restart.");
            } catch (Exception e) {
                log.log(Level.SEVERE, "Final score flush on disable FAILED", e);
            }
        }
        hideBossBar();
    }

    // ================================================================== starting

    /** Scheduler: start {@code slot}'s session now, keeping its original end time. */
    public void startScheduled(Rotation.Slot slot) {
        startLive(slot, slot.end().toEpochMilli(), "SCHEDULER");
    }

    /** Staff: start today's event now for {@code duration-minutes}. */
    public void startStaff(Rotation.Slot slot, String staffName) {
        long end = System.currentTimeMillis() + plugin.cfg().durationMinutes * 60_000L;
        startLive(slot, end, "STAFF:" + staffName);
    }

    private void startLive(Rotation.Slot slot, long endMs, String startedBy) {
        ActiveEvent current = active;
        if (current != null && current.test) {
            log.info("A real event is starting - ending the TEST event.");
            endRuntime(current);
        }
        long now = System.currentTimeMillis();
        String slotText = HHMM.format(slot.time());
        ActiveEvent ev = new ActiveEvent(false, slot.day(), slot.session(), slotText, now, endMs);
        remember(slot.day(), slot.session(), Database.RUNNING);
        beginRuntime(ev, true);
        log.info("Pirate's Catch started for rotation day " + slot.day() + " session " + slot.label() + " (slot " + slotText + ", by "
                + startedBy + ", ends " + Instant.ofEpochMilli(endMs) + ").");
        plugin.db().insertEvent(slot.day().toString(), slot.session(), slotText, now, endMs, Database.RUNNING, startedBy)
                .whenComplete((id, ex) -> {
                    if (ex == null) {
                        ev.id.set(id); // runs on the DB thread, before any later DB task for this event
                        return;
                    }
                    plugin.sync(() -> {
                        log.log(Level.SEVERE, "Could not record the event start for " + slot.day()
                                + " - aborting it (no rewards).", ex);
                        endRuntime(ev);
                        reloadDays(null);
                    });
                });
    }

    /** Staff: a TEST event of {@code minutes}; no rewards, no trophies, no DB, no Discord. */
    public void startTest(int minutes) {
        long now = System.currentTimeMillis();
        ActiveEvent ev = new ActiveEvent(true, null, -1, "-", now, now + minutes * 60_000L);
        beginRuntime(ev, true);
        log.info("TEST event started for " + minutes + " minute(s).");
    }

    private void beginRuntime(ActiveEvent ev, boolean announce) {
        active = ev;
        rebuildSnapshot();
        PluginConfig cfg = plugin.cfg();
        updateBossBar(System.currentTimeMillis());
        if (!announce) {
            return;
        }
        Messages msg = plugin.messages();
        msg.broadcast("start-broadcast", ev.test,
                Text.placeholder("duration", Durations.format(ev.endMs - ev.startMs)));
        plugin.playSoundAll("start");
        if (!ev.test && cfg.discordOnStart) {
            plugin.discord().post(cfg, Text.plain(msg.get("start-broadcast",
                    Text.placeholder("duration", Durations.format(ev.endMs - ev.startMs)))));
        }
    }

    /** Stop scoring and tear down the runtime for {@code ev} (no rewards, no DB). */
    private void endRuntime(ActiveEvent ev) {
        ev.finalizing = true;
        if (active == ev) {
            active = null;
            snapshot = Snapshot.EMPTY;
            hideBossBar();
        }
    }

    // ================================================================== ticking / ending

    /** Called every second by the scheduler. */
    public void tick(long now) {
        ActiveEvent ev = active;
        if (ev != null && !ev.finalizing && now >= ev.endMs) {
            if (ev.test) {
                endTest(ev);
            } else {
                finalizeLive(ev);
            }
            return;
        }
        updateBossBar(now);
    }

    /** Staff stop: end now and finalize normally. False if nothing is running. */
    public boolean stop() {
        ActiveEvent ev = active;
        if (ev == null || ev.finalizing) {
            return false;
        }
        if (ev.test) {
            endTest(ev);
        } else {
            finalizeLive(ev);
        }
        return true;
    }

    /** Staff cancel: end now, no rewards. False if nothing is running. */
    public boolean cancel() {
        ActiveEvent ev = active;
        if (ev == null || ev.finalizing) {
            return false;
        }
        List<ScoreRow> rows = ev.allRows();
        endRuntime(ev);
        if (ev.test) {
            log.info("TEST event cancelled.");
            return true;
        }
        remember(ev.day, ev.session, Database.CANCELLED);
        plugin.db().cancelEvent(ev::id, rows).whenComplete((ok, ex) -> plugin.sync(() -> {
            if (ex != null) {
                log.log(Level.SEVERE, "Could not mark the event for " + ev.day + " CANCELLED", ex);
                reloadDays(null);
            } else {
                log.info("Event for rotation day " + ev.day + " CANCELLED - no rewards given.");
            }
        }));
        return true;
    }

    private void endTest(ActiveEvent ev) {
        List<Ranked> input = ev.rankedInput();
        endRuntime(ev);
        PluginConfig cfg = plugin.cfg();
        Allocation al = RewardAllocator.allocate(input, Set.of(), cfg.minDistinctIps, cfg.placementShards,
                cfg.participationShards);
        announceEnd(al, true);
        log.info("TEST event finished (" + al.ranked().size() + " ranked) - nothing given, nothing recorded.");
    }

    /**
     * Exactly-once finalize. The in-memory guard stops a second call; the DB transaction flips
     * {@code finalized} together with inserting key_wins + pending_rewards, so a crash can never
     * double-pay: recovery either re-runs the whole transaction or finds it already done.
     */
    private void finalizeLive(ActiveEvent ev) {
        if (ev.finalizing) {
            return;
        }
        List<ScoreRow> rows = ev.allRows();
        endRuntime(ev);
        PluginConfig cfg = plugin.cfg();
        long now = System.currentTimeMillis();
        long cooldownSince = RewardPlanner.cooldownSince(cfg.keyCooldownHours, now);
        plugin.db().<Allocation>finalizeEvent(ev::id, rows, cooldownSince, now,
                        (scores, cooldown) -> RewardPlanner.plan(scores, cooldown, cfg, now, log::warning))
                .whenComplete((plan, ex) -> plugin.sync(() -> {
                    if (ex != null) {
                        log.log(Level.SEVERE, "Finalize FAILED for rotation day " + ev.day
                                + " - it will be retried on the next restart.", ex);
                        return;
                    }
                    remember(ev.day, ev.session, Database.FINISHED);
                    if (plan == null) {
                        log.info("Event for rotation day " + ev.day + " was already finalized.");
                        plugin.dispatcher().drainOnline();
                        return;
                    }
                    Allocation al = plan.payload();
                    log.info("Event for rotation day " + ev.day + " finalized: " + al.summary() + ", "
                            + al.ranked().size() + " ranked, " + plan.rewards().size() + " reward row(s) queued"
                            + (al.keyRecipient() != null ? ", key -> " + al.keyRecipient().name() : "") + ".");
                    List<Component> lines = announceEnd(al, false);
                    plugin.dispatcher().drainOnline();
                    if (cfg.discordOnEnd) {
                        StringBuilder sb = new StringBuilder();
                        for (Component c : lines) {
                            if (!sb.isEmpty()) {
                                sb.append('\n');
                            }
                            sb.append(Text.plain(c));
                        }
                        plugin.discord().post(cfg, sb.toString());
                    }
                }));
    }

    /** Broadcast the results; returns the (unprefixed-by-test) lines for Discord. */
    private List<Component> announceEnd(Allocation al, boolean test) {
        Messages msg = plugin.messages();
        PluginConfig cfg = plugin.cfg();
        List<Component> lines = new ArrayList<>();
        lines.add(msg.get("end-broadcast-header"));
        List<Ranked> ranked = al.ranked();
        for (int i = 0; i < Math.min(cfg.showTop, ranked.size()); i++) {
            Ranked r = ranked.get(i);
            lines.add(msg.get("end-broadcast-line", Text.placeholder("rank", String.valueOf(i + 1)),
                    Text.placeholder("name", r.name()), Text.placeholder("score", String.valueOf(r.points()))));
        }
        switch (al.summary()) {
            case KEY_AWARDED -> lines.add(msg.get("end-winner",
                    Text.placeholder("player", al.keyRecipient().name())));
            case NO_KEY_TURNOUT -> lines.add(msg.get("end-no-key-turnout"));
            case NO_ELIGIBLE -> lines.add(msg.get("end-no-key-eligible"));
            case NOBODY -> lines.add(msg.get("end-nobody"));
            default -> { }
        }
        for (Component c : lines) {
            if (!Text.plain(c).isBlank()) {
                Bukkit.getServer().sendMessage(msg.withTest(c, test));
            }
        }
        plugin.playSoundAll("end");
        return lines;
    }

    // ================================================================== staff day controls

    /** Mark {@code slot}'s session SKIPPED (caller has checked it has no record). */
    public void skip(Rotation.Slot slot, String staffName) {
        remember(slot.day(), slot.session(), Database.SKIPPED);
        plugin.db().insertEvent(slot.day().toString(), slot.session(), HHMM.format(slot.time()),
                slot.start().toEpochMilli(), slot.end().toEpochMilli(), Database.SKIPPED, "STAFF:" + staffName)
                .whenComplete((id, ex) -> {
                    if (ex != null) {
                        plugin.sync(() -> {
                            log.log(Level.SEVERE, "Could not record SKIPPED for " + slot.day(), ex);
                            reloadDays(null);
                        });
                    } else {
                        log.info("Rotation day " + slot.day() + " session " + slot.label() + " SKIPPED by "
                                + staffName + ".");
                    }
                });
    }

    /**
     * Remove the SKIPPED row of {@code day}'s {@code session} (never a whole-day row);
     * {@code done} receives true if removed (on the main thread).
     */
    public void unskip(LocalDate day, int session, java.util.function.Consumer<Boolean> done) {
        if (session < 0) {
            done.accept(false); // a whole-day row is never unskipped
            return;
        }
        plugin.db().deleteSkipped(day.toString(), session).whenComplete((removed, ex) -> plugin.sync(() -> {
            if (ex != null) {
                log.log(Level.SEVERE, "Could not unskip " + day + " session " + (session + 1), ex);
                done.accept(false);
                return;
            }
            if (removed) {
                sessions.remove(new SessionKey(day, session));
                log.info("Rotation day " + day + " session " + (session + 1) + " is back on the schedule.");
            }
            done.accept(removed);
        }));
    }

    /** The whole window passed with no row (server offline): record MISSED, never make it up. */
    public void markMissed(Rotation.Slot slot) {
        remember(slot.day(), slot.session(), Database.MISSED);
        log.warning("Rotation day " + slot.day() + " session " + slot.label() + " (" + HHMM.format(slot.time())
                + ") was MISSED - its whole window passed while the server was down.");
        plugin.db().insertEvent(slot.day().toString(), slot.session(), HHMM.format(slot.time()),
                slot.start().toEpochMilli(), slot.end().toEpochMilli(), Database.MISSED, "SCHEDULER")
                .exceptionally(ex -> {
                    log.log(Level.SEVERE, "Could not record MISSED for " + slot.day(), ex);
                    return null;
                });
    }

    // ================================================================== scoring

    /** A CAUGHT_FISH at MONITOR priority. Main thread. */
    public void onCatch(Player player) {
        ActiveEvent ev = active;
        if (ev == null || ev.finalizing) {
            return;
        }
        PluginConfig cfg = plugin.cfg();
        Messages msg = plugin.messages();

        RegionChecker.Result where = RegionChecker.check(player.getLocation(), cfg.regionWorld, cfg.regionId);
        if (where == RegionChecker.Result.MISCONFIGURED) {
            if (!ev.regionErrorLogged) {
                ev.regionErrorLogged = true;
                log.severe("WorldGuard region '" + cfg.regionId + "' in world '" + cfg.regionWorld
                        + "' does not exist - no catches will count this event.");
            }
            return;
        }
        if (where == RegionChecker.Result.OUTSIDE) {
            if (ev.hinted.add(player.getUniqueId())) {
                msg.send(player, "outside-region-hint");
            }
            return;
        }
        if (!cfg.allowedGamemodes.contains(player.getGameMode()) || isVanished(player)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (cfg.antiAfkEnabled && now - plugin.activity().lastActivity(player.getUniqueId())
                > cfg.maxIdleSeconds * 1000L) {
            return;
        }

        LootTier tier = cfg.loot.roll(ThreadLocalRandom.current().nextDouble());
        if (tier == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String ip = player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress();
        ScoreEntry entry = ev.scores.computeIfAbsent(uuid, u -> new ScoreEntry(u, player.getName(), ip));
        entry.addCatch(player.getName(), ip, tier.points(), now);

        Component tierName = msg.renderRaw(tier.name());
        msg.send(player, "catch-message", Text.placeholder("tier", tierName),
                Text.placeholder("points", String.valueOf(tier.points())),
                Text.placeholder("score", String.valueOf(entry.points())));
        plugin.playSound(player, "pirate-catch");
        if (tier.broadcast()) {
            msg.broadcast("legendary-broadcast", ev.test, Text.placeholder("player", player.getName()),
                    Text.placeholder("tier", tierName));
            plugin.playSoundAll("legendary");
        }

        if (!ev.test && tier.hasTrophy() && ev.trophies.add(uuid + ":" + tier.id())) {
            String command = RewardPlanner.substitute(tier.trophyCommand(), player.getName(), 1);
            PendingReward reward = new PendingReward(0, uuid, command, "", "{}", cfg.requiredFreeSlots, now);
            plugin.db().insertTrophy(ev::id, uuid, tier.id(), reward).whenComplete((v, ex) -> plugin.sync(() -> {
                if (ex != null) {
                    log.log(Level.SEVERE, "Could not record the " + tier.id() + " trophy for " + player.getName()
                            + " - give it manually: " + command, ex);
                    return;
                }
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    plugin.dispatcher().drain(online);
                }
            }));
            msg.send(player, "trophy-message", Text.placeholder("tier", tierName));
        }
    }

    private static boolean isVanished(Player player) {
        for (MetadataValue v : player.getMetadata("vanished")) {
            if (v.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== snapshot / flush / boss bar

    public void rebuildSnapshot() {
        ActiveEvent ev = active;
        snapshot = ev == null ? Snapshot.EMPTY : Snapshot.of(ev.rankedInput());
    }

    private void flushAsync() {
        ActiveEvent ev = active;
        if (ev == null || ev.test || ev.finalizing) {
            return;
        }
        List<ScoreRow> dirty = ev.dirtyRows();
        if (dirty.isEmpty()) {
            return;
        }
        plugin.db().upsertScores(ev::id, dirty).exceptionally(ex -> {
            log.log(Level.SEVERE, "Score flush failed (" + dirty.size() + " row(s)); they are re-sent at event end.",
                    ex);
            return null;
        });
    }

    private void updateBossBar(long now) {
        ActiveEvent ev = active;
        PluginConfig cfg = plugin.cfg();
        if (ev == null || !cfg.bossbarEnabled) {
            hideBossBarViewers();
            return;
        }
        if (bossBar == null) {
            bossBar = BossBar.bossBar(Component.empty(), 1f, cfg.bossbarColor, cfg.bossbarOverlay);
        }
        Messages msg = plugin.messages();
        long total = Math.max(1L, ev.endMs - ev.startMs);
        long left = Math.max(0L, ev.endMs - now);
        Ranked leader = snapshot.at(1);
        Component title = msg.getNoPrefix("bossbar-title",
                Text.placeholder("timeleft", Durations.format(left)),
                leader == null ? Text.placeholder("leader", msg.getNoPrefix("bossbar-no-leader"))
                        : Text.placeholder("leader", leader.name()),
                Text.placeholder("score", String.valueOf(leader == null ? 0 : leader.points())));
        bossBar.name(msg.withTest(title, ev.test));
        bossBar.progress(Math.max(0f, Math.min(1f, (float) left / (float) total)));
        bossBar.color(cfg.bossbarColor);
        bossBar.overlay(cfg.bossbarOverlay);

        Set<UUID> want = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (cfg.bossbarAudienceAll || p.getWorld().getName().equals(cfg.regionWorld)) {
                want.add(p.getUniqueId());
                if (bossViewers.add(p.getUniqueId())) {
                    p.showBossBar(bossBar);
                }
            }
        }
        bossViewers.removeIf(u -> {
            if (want.contains(u)) {
                return false;
            }
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                p.hideBossBar(bossBar);
            }
            return true;
        });
    }

    /** A player left: forget them as a viewer (the client drops the bar itself). */
    public void onQuit(UUID uuid) {
        bossViewers.remove(uuid);
    }

    private void hideBossBarViewers() {
        if (bossBar == null) {
            bossViewers.clear();
            return;
        }
        for (UUID u : bossViewers) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                p.hideBossBar(bossBar);
            }
        }
        bossViewers.clear();
    }

    private void hideBossBar() {
        hideBossBarViewers();
        bossBar = null;
    }

    private static LocalDate parseDay(String s) {
        try {
            return LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
