package net.samsside.craftedpiratecatch.config;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.samsside.craftedpiratecatch.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Every player-visible string, from {@code messages.yml}. Each message supports
 * {@code <prefix>}; missing keys fall back to the bundled default.
 */
public final class Messages {

    private static final String DEFAULT_TIME_FORMAT = "EEE d MMM HH:mm";

    private final YamlConfiguration user;
    private final YamlConfiguration bundled;
    private final DateTimeFormatter timeFormat;

    public Messages(YamlConfiguration user, YamlConfiguration bundled, ZoneId zone, Logger log) {
        this.user = user;
        this.bundled = bundled;
        String pattern = raw("time-format");
        DateTimeFormatter f;
        try {
            f = DateTimeFormatter.ofPattern(pattern, Locale.UK);
        } catch (IllegalArgumentException e) {
            log.warning("Invalid time-format '" + pattern + "' in messages.yml; using " + DEFAULT_TIME_FORMAT + ".");
            f = DateTimeFormatter.ofPattern(DEFAULT_TIME_FORMAT, Locale.UK);
        }
        this.timeFormat = f.withZone(zone);
    }

    /** The raw string for {@code key} (user value, else bundled default, else empty). */
    public String raw(String key) {
        String v = user.getString(key);
        if (v == null && bundled != null) {
            v = bundled.getString(key);
        }
        return v == null ? "" : v;
    }

    public String formatTime(Instant instant) {
        return timeFormat.format(instant);
    }

    /** Render {@code key} with {@code <prefix>} resolved to the configured prefix. */
    public Component get(String key, TagResolver... resolvers) {
        return render(raw(key), true, resolvers);
    }

    /** Render {@code key} with {@code <prefix>} resolved to nothing. */
    public Component getNoPrefix(String key, TagResolver... resolvers) {
        return render(raw(key), false, resolvers);
    }

    /** Render an arbitrary configured string (e.g. a loot tier name). */
    public Component renderRaw(String input, TagResolver... resolvers) {
        return render(input, true, resolvers);
    }

    private Component render(String input, boolean withPrefix, TagResolver... resolvers) {
        Component prefix = withPrefix ? Text.render(raw("prefix")) : Component.empty();
        TagResolver all = TagResolver.builder()
                .resolvers(resolvers)
                .resolver(Placeholder.component("prefix", prefix))
                .build();
        return Text.render(input, all);
    }

    /** Whether {@code key} is configured to an empty string (so it should not be sent). */
    public boolean isBlank(String key) {
        return raw(key).isBlank();
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        if (isBlank(key)) {
            return;
        }
        to.sendMessage(get(key, resolvers));
    }

    /** Broadcast to every player and the console, prefixed with {@code test-prefix} if {@code test}. */
    public void broadcast(String key, boolean test, TagResolver... resolvers) {
        if (isBlank(key)) {
            return;
        }
        Bukkit.getServer().sendMessage(withTest(get(key, resolvers), test));
    }

    public Component withTest(Component c, boolean test) {
        return test ? get("test-prefix").append(c) : c;
    }
}
