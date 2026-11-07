package net.samsside.craftedpiratecatch.config;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.bossbar.BossBar;
import net.samsside.craftedpiratecatch.loot.LootTable;
import net.samsside.craftedpiratecatch.loot.LootTier;
import net.samsside.craftedpiratecatch.schedule.Rotation;
import net.samsside.craftedpiratecatch.schedule.SlotEntry;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable, validated snapshot of {@code config.yml}. Validation never throws: each bad value
 * logs a WARNING (or ERROR, for anything that disables scheduling) and falls back.
 */
public final class PluginConfig {

    public static final String[] SOUND_KEYS = {"warning", "start", "pirate-catch", "legendary", "end"};

    // schedule
    public final ZoneId zone;
    public final Rotation rotation; // null => scheduling disabled
    public final int durationMinutes;
    public final List<Integer> warningsMinutes;
    // region
    public final String regionWorld;
    public final String regionId;
    // anti-afk
    public final boolean antiAfkEnabled;
    public final int maxIdleSeconds;
    public final Set<GameMode> allowedGamemodes;
    public final LootTable loot;
    // rewards
    public final String keyCommand;
    public final int[] placementShards;
    public final int participationShards;
    public final String shardCommand;
    public final int requiredFreeSlots;
    // fairness
    public final int keyCooldownHours;
    public final int minDistinctIps;
    // bossbar
    public final boolean bossbarEnabled;
    public final BossBar.Color bossbarColor;
    public final BossBar.Overlay bossbarOverlay;
    public final boolean bossbarAudienceAll;
    // sounds (raw keys, resolved by the plugin against the registry)
    public final Map<String, String> sounds;
    // leaderboard
    public final int refreshSeconds;
    public final int showTop;
    // database
    public final String databaseFile;
    public final int flushIntervalSeconds;
    // discord
    public final String webhookUrl;
    public final boolean discordOnStart;
    public final boolean discordOnEnd;
    public final String discordUsername;
    public final String discordAvatarUrl;

    private PluginConfig(FileConfiguration c, Logger log) {
        // ---- schedule ----
        ZoneId z;
        String tz = c.getString("schedule.timezone", "Europe/London");
        try {
            z = ZoneId.of(tz);
        } catch (Exception e) {
            log.warning("Invalid schedule.timezone '" + tz + "'; using Europe/London.");
            z = ZoneId.of("Europe/London");
        }
        this.zone = z;

        LocalTime rollover;
        String ro = c.getString("schedule.day-rollover", "06:00");
        try {
            rollover = LocalTime.parse(ro.trim());
        } catch (DateTimeParseException e) {
            log.warning("Invalid schedule.day-rollover '" + ro + "'; using 06:00.");
            rollover = LocalTime.of(6, 0);
        }

        int duration = c.getInt("schedule.duration-minutes", 60);
        if (duration < 5 || duration > 240) {
            int clamped = Math.max(5, Math.min(240, duration));
            log.warning("schedule.duration-minutes " + duration + " out of range 5..240; clamped to " + clamped + ".");
            duration = clamped;
        }
        this.durationMinutes = duration;

        List<List<LocalTime>> slots = new ArrayList<>();
        for (String raw : c.getStringList("schedule.slots")) {
            SlotEntry.Parsed p = SlotEntry.parse(raw, rollover, Duration.ofMinutes(duration));
            if (p.times().isEmpty()) {
                log.warning("Unparsable schedule slot '" + raw + "' dropped (expected HH:mm or HH:mm + HH:mm).");
                continue;
            }
            if (p.problem() != null) {
                log.severe("Schedule slot entry '" + raw + "' is invalid (" + p.problem() + ") - using only '"
                        + p.times().get(0) + "'.");
            }
            slots.add(p.times());
        }

        List<Integer> warnings = new ArrayList<>();
        for (Integer w : c.getIntegerList("schedule.warnings-minutes")) {
            if (w == null || w < 1 || w > 120) {
                log.warning("schedule.warnings-minutes value " + w + " outside 1..120 dropped.");
            } else if (!warnings.contains(w)) {
                warnings.add(w);
            }
        }
        this.warningsMinutes = List.copyOf(warnings);

        LocalDate first = parseDate(c.getString("schedule.first-day", ""));
        LocalDate last = parseDate(c.getString("schedule.last-day", ""));
        Rotation rot = null;
        if (slots.isEmpty()) {
            log.severe("No valid schedule.slots - event scheduling is DISABLED.");
        } else if (first == null || last == null) {
            log.severe("schedule.first-day/last-day must be yyyy-MM-dd - event scheduling is DISABLED.");
        } else if (first.isAfter(last)) {
            log.severe("schedule.first-day (" + first + ") is after last-day (" + last
                    + ") - event scheduling is DISABLED.");
        } else {
            rot = new Rotation(z, slots, rollover, first, last, Duration.ofMinutes(duration));
        }
        this.rotation = rot;

        // ---- region ----
        this.regionWorld = c.getString("region.world", "spawn");
        this.regionId = c.getString("region.id", "pirate_dock");

        // ---- anti-afk / gamemodes ----
        this.antiAfkEnabled = c.getBoolean("anti-afk.enabled", true);
        this.maxIdleSeconds = Math.max(1, c.getInt("anti-afk.max-idle-seconds", 120));
        Set<GameMode> modes = EnumSet.noneOf(GameMode.class);
        for (String m : c.getStringList("allowed-gamemodes")) {
            try {
                modes.add(GameMode.valueOf(m.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.warning("Unknown gamemode '" + m + "' in allowed-gamemodes ignored.");
            }
        }
        this.allowedGamemodes = Set.copyOf(modes);

        // ---- loot ----
        List<LootTier> raw = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("loot")) {
            String id = str(m.get("id"), "");
            if (id.isBlank()) {
                log.warning("A loot entry without an id was ignored.");
                continue;
            }
            raw.add(new LootTier(id, str(m.get("name"), id), num(m.get("chance")), (int) num(m.get("points")),
                    str(m.get("trophy-command"), ""), Boolean.parseBoolean(str(m.get("broadcast"), "false"))));
        }
        this.loot = LootTable.of(raw, w -> log.warning(w));

        // ---- rewards ----
        this.keyCommand = c.getString("rewards.key-command", "");
        List<Integer> ps = c.getIntegerList("rewards.placement-shards");
        int[] shards = new int[ps.size()];
        for (int i = 0; i < shards.length; i++) {
            shards[i] = Math.max(0, ps.get(i));
        }
        this.placementShards = shards;
        this.participationShards = Math.max(0, c.getInt("rewards.participation-shards", 1));
        this.shardCommand = c.getString("rewards.shard-command", "");
        this.requiredFreeSlots = Math.max(0, c.getInt("rewards.required-free-slots", 1));

        // ---- fairness ----
        this.keyCooldownHours = Math.max(0, c.getInt("fairness.key-cooldown-hours", 72));
        this.minDistinctIps = Math.max(0, c.getInt("fairness.min-distinct-ips", 3));

        // ---- bossbar ----
        this.bossbarEnabled = c.getBoolean("bossbar.enabled", true);
        BossBar.Color color;
        String colorName = c.getString("bossbar.color", "BLUE");
        try {
            color = BossBar.Color.valueOf(colorName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("Unknown bossbar.color '" + colorName + "'; using BLUE.");
            color = BossBar.Color.BLUE;
        }
        this.bossbarColor = color;
        BossBar.Overlay overlay;
        String overlayName = c.getString("bossbar.overlay", "PROGRESS");
        try {
            overlay = BossBar.Overlay.valueOf(overlayName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("Unknown bossbar.overlay '" + overlayName + "'; using PROGRESS.");
            overlay = BossBar.Overlay.PROGRESS;
        }
        this.bossbarOverlay = overlay;
        String audience = c.getString("bossbar.audience", "all");
        if (!audience.equalsIgnoreCase("all") && !audience.equalsIgnoreCase("world")) {
            log.warning("Unknown bossbar.audience '" + audience + "'; using all.");
        }
        this.bossbarAudienceAll = !audience.equalsIgnoreCase("world");

        // ---- sounds ----
        Map<String, String> snd = new LinkedHashMap<>();
        for (String key : SOUND_KEYS) {
            snd.put(key, c.getString("sounds." + key, ""));
        }
        this.sounds = Map.copyOf(snd);

        // ---- leaderboard ----
        this.refreshSeconds = Math.max(1, c.getInt("leaderboard.refresh-seconds", 2));
        this.showTop = Math.max(1, Math.min(10, c.getInt("leaderboard.show-top", 5)));

        // ---- database ----
        String file = c.getString("database.file", "data.db");
        if (file.isBlank() || file.contains("..") || file.contains("/") || file.contains("\\")) {
            log.warning("database.file '" + file + "' must be a plain file name; using data.db.");
            file = "data.db";
        }
        this.databaseFile = file;
        this.flushIntervalSeconds = Math.max(1, c.getInt("database.flush-interval-seconds", 15));

        // ---- discord ----
        this.webhookUrl = c.getString("discord.webhook-url", "").trim();
        this.discordOnStart = c.getBoolean("discord.on-start", true);
        this.discordOnEnd = c.getBoolean("discord.on-end", true);
        this.discordUsername = c.getString("discord.username", "Pirate's Catch");
        this.discordAvatarUrl = c.getString("discord.avatar-url", "").trim();
    }

    public static PluginConfig load(FileConfiguration c, Logger log) {
        return new PluginConfig(c, log);
    }

    public boolean schedulingEnabled() {
        return rotation != null;
    }

    private static LocalDate parseDate(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
