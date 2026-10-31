package net.samsside.craftedpiratecatch;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.samsside.craftedpiratecatch.command.PirateCommand;
import net.samsside.craftedpiratecatch.config.ConfigMerger;
import net.samsside.craftedpiratecatch.config.Messages;
import net.samsside.craftedpiratecatch.config.PluginConfig;
import net.samsside.craftedpiratecatch.discord.DiscordWebhook;
import net.samsside.craftedpiratecatch.event.ActiveEvent;
import net.samsside.craftedpiratecatch.event.ActivityTracker;
import net.samsside.craftedpiratecatch.event.EventListener;
import net.samsside.craftedpiratecatch.event.EventManager;
import net.samsside.craftedpiratecatch.placeholder.PirateCatchExpansion;
import net.samsside.craftedpiratecatch.reward.RewardDispatcher;
import net.samsside.craftedpiratecatch.schedule.SchedulerService;
import net.samsside.craftedpiratecatch.storage.Database;
import net.samsside.craftedpiratecatch.storage.Database.PendingReward;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Pirate's Catch: a daily rotating fishing race at the spawn dock. */
public final class CraftedPirateCatch extends JavaPlugin {

    private volatile PluginConfig config;
    private volatile Messages messages;
    private volatile Map<String, Sound> sounds = Map.of();
    private Database database;
    private EventManager events;
    private RewardDispatcher dispatcher;
    private SchedulerService scheduler;
    private DiscordWebhook discord;
    private final ActivityTracker activity = new ActivityTracker();
    private PirateCatchExpansion expansion;
    private BukkitTask drainTask;

    @Override
    public void onEnable() {
        try {
            // 1. config + messages
            loadConfigs();
            // 2. database
            database = new Database(getLogger());
            database.open(new File(getDataFolder(), config.databaseFile)).get(30, TimeUnit.SECONDS);
            discord = new DiscordWebhook(getLogger());
            dispatcher = new RewardDispatcher(this);
            events = new EventManager(this);
            // 3. state recovery
            events.recover();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "CraftedPirateCatch failed to start - disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 4. listeners
        getServer().getPluginManager().registerEvents(new EventListener(this), this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            activity.touch(p.getUniqueId());
        }

        // 5. commands
        PluginCommand command = getCommand("piratecatch");
        if (command != null) {
            PirateCommand handler = new PirateCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().severe("Command 'piratecatch' missing from plugin.yml.");
        }

        // 6. PlaceholderAPI
        expansion = new PirateCatchExpansion(this);
        if (expansion.register()) {
            getLogger().info("Registered PlaceholderAPI expansion %craftedpiratecatch_*%.");
        } else {
            getLogger().warning("PlaceholderAPI expansion registration failed.");
        }

        // 7. scheduler tick + timers
        events.restartTimers();
        scheduler = new SchedulerService(this);
        scheduler.start();
        drainTask = Bukkit.getScheduler().runTaskTimer(this, dispatcher::drainOnline, 20L * 5, 20L * 30);

        getLogger().info("CraftedPirateCatch " + getPluginMeta().getVersion() + " enabled"
                + (config.schedulingEnabled() ? " (season " + config.rotation.firstDay() + " to "
                + config.rotation.lastDay() + ", " + config.rotation.zone() + ")." : " - scheduling DISABLED."));
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.stop();
        }
        if (drainTask != null) {
            drainTask.cancel();
        }
        if (events != null) {
            events.shutdown();
        }
        if (expansion != null) {
            try {
                expansion.unregister();
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "Could not unregister the PlaceholderAPI expansion", e);
            }
        }
        if (database != null) {
            if (dispatcher != null) {
                List<PendingReward> inFlight = dispatcher.inFlightRows();
                if (!inFlight.isEmpty()) {
                    try {
                        database.restorePending(inFlight).get(10, TimeUnit.SECONDS);
                        getLogger().info("Restored " + inFlight.size() + " in-flight reward(s) for delivery later.");
                    } catch (Exception e) {
                        getLogger().log(Level.SEVERE, "Could not restore in-flight rewards: " + inFlight, e);
                    }
                }
            }
            database.shutdown();
        }
        getLogger().info("CraftedPirateCatch disabled.");
    }

    // ------------------------------------------------------------------ config

    private void loadConfigs() throws Exception {
        YamlConfiguration cfgYaml = ConfigMerger.loadAndMerge(this, "config.yml");
        YamlConfiguration msgYaml = ConfigMerger.loadAndMerge(this, "messages.yml");
        PluginConfig newConfig = PluginConfig.load(cfgYaml, getLogger());
        Messages newMessages = new Messages(msgYaml, ConfigMerger.bundled(this, "messages.yml"), newConfig.zone,
                getLogger());
        Map<String, Sound> newSounds = resolveSounds(newConfig);
        this.config = newConfig;
        this.messages = newMessages;
        this.sounds = newSounds;
        getLogger().info("Loaded config.yml (" + newConfig.loot.tiers().size() + " loot tier(s)) and messages.yml.");
    }

    private Map<String, Sound> resolveSounds(PluginConfig cfg) {
        Map<String, Sound> out = new HashMap<>();
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT);
        for (Map.Entry<String, String> e : cfg.sounds.entrySet()) {
            String raw = e.getValue() == null ? "" : e.getValue().trim();
            if (raw.isEmpty()) {
                continue;
            }
            try {
                Key key = Key.key(raw);
                if (registry.get(key) == null) {
                    getLogger().warning("Unknown sound '" + raw + "' for sounds." + e.getKey() + " - no sound.");
                    continue;
                }
                out.put(e.getKey(), Sound.sound(key, Sound.Source.MASTER, 1f, 1f));
            } catch (RuntimeException ex) {
                getLogger().warning("Invalid sound key '" + raw + "' for sounds." + e.getKey() + " - no sound.");
            }
        }
        return Map.copyOf(out);
    }

    /** {@code /piratecatch reload}: config + messages + reopen DB + rebuild caches. */
    public void reload(CommandSender sender) {
        PluginConfig previous = this.config;
        try {
            loadConfigs();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Reload failed - keeping the previous config.", e);
            messages.send(sender, "reload-failed");
            return;
        }
        String file = config.databaseFile;
        ActiveEvent ev = events.active();
        if (ev != null && !ev.test && !file.equals(previous.databaseFile)) {
            getLogger().warning("database.file changed while an event is running - keeping "
                    + previous.databaseFile + " until the next reload/restart.");
            file = previous.databaseFile;
        }
        final String dbFile = file;
        events.restartTimers();
        database.open(new File(getDataFolder(), dbFile)).whenComplete((v, ex) -> sync(() -> {
            if (ex != null) {
                getLogger().log(Level.SEVERE, "Reload: database reopen failed", ex);
                messages.send(sender, "reload-failed");
                return;
            }
            events.reloadDays(() -> {
                events.rebuildSnapshot();
                dispatcher.drainOnline();
                getLogger().info("Reloaded by " + sender.getName() + ".");
                messages.send(sender, "reloaded");
            });
        }));
    }

    // ------------------------------------------------------------------ helpers

    /** Run on the main thread (no-op once the plugin is disabled). */
    public void sync(Runnable r) {
        if (!isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(this, r);
        }
    }

    public void playSound(Audience audience, String key) {
        Sound s = sounds.get(key);
        if (s != null) {
            audience.playSound(s);
        }
    }

    public void playSoundAll(String key) {
        Sound s = sounds.get(key);
        if (s != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(s);
            }
        }
    }

    public PluginConfig cfg() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public Database db() {
        return database;
    }

    public EventManager events() {
        return events;
    }

    public RewardDispatcher dispatcher() {
        return dispatcher;
    }

    public DiscordWebhook discord() {
        return discord;
    }

    public ActivityTracker activity() {
        return activity;
    }
}
