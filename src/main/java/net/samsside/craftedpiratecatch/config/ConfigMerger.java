package net.samsside.craftedpiratecatch.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Auto-merges a bundled default YAML file into the user's copy: every key present in the
 * default but missing from the user file is added (recursively), existing user values are
 * never overwritten, comments are kept where Bukkit's YAML API preserves them, and the
 * bundled {@code config-version} is written if it is higher. Lists are single values.
 */
public final class ConfigMerger {

    private ConfigMerger() {
    }

    /**
     * Ensure {@code name} exists in the data folder and contains every bundled key, then
     * return the loaded user configuration.
     *
     * @throws IOException                   if the file cannot be read or written
     * @throws InvalidConfigurationException if the user's YAML is malformed
     */
    public static YamlConfiguration loadAndMerge(JavaPlugin plugin, String name)
            throws IOException, InvalidConfigurationException {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
            log.info("Created default " + name + ".");
        }

        YamlConfiguration user = new YamlConfiguration();
        user.options().parseComments(true);
        user.load(file);

        YamlConfiguration defaults = bundled(plugin, name);
        if (defaults == null) {
            log.warning("Bundled " + name + " is missing from the jar; skipping merge.");
            return user;
        }

        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (user.contains(key, true)) {
                continue;
            }
            if (defaults.isConfigurationSection(key)) {
                user.createSection(key);
            } else {
                user.set(key, defaults.get(key));
                added++;
            }
            user.setComments(key, defaults.getComments(key));
            user.setInlineComments(key, defaults.getInlineComments(key));
        }

        boolean versionBumped = false;
        int bundledVersion = defaults.getInt("config-version", 1);
        if (user.getInt("config-version", 0) < bundledVersion) {
            user.set("config-version", bundledVersion);
            versionBumped = true;
        }

        if (added > 0 || versionBumped) {
            user.save(file);
        }
        log.info("Merged " + added + " new key(s) into " + name
                + (versionBumped ? " (config-version -> " + bundledVersion + ")" : "") + ".");
        return user;
    }

    /** The bundled default as a configuration, or null if absent. */
    public static YamlConfiguration bundled(JavaPlugin plugin, String name)
            throws IOException, InvalidConfigurationException {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return null;
            }
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.options().parseComments(true);
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return defaults;
        }
    }
}
