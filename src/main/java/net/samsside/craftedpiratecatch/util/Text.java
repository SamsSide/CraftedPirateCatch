package net.samsside.craftedpiratecatch.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Renders configured strings that may mix legacy colour/format codes
 * ({@code &a}, {@code §a}, {@code &#RRGGBB}) with MiniMessage tags. Legacy codes
 * are rewritten into their MiniMessage equivalents first, so a single string can
 * use either format - or both.
 *
 * <p>Two kinds of placeholder, both injection-safe. A String value is inserted with
 * {@link Placeholder#unparsed}, so untrusted text such as a player name can never
 * inject formatting tags. A Component value is already rendered, so it carries its
 * own colours into the message and is never re-parsed.
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Text() {
    }

    /** Parse an authored string into a Component, resolving the given placeholders. */
    public static Component render(String input, TagResolver... resolvers) {
        return MINI.deserialize(legacyToMiniMessage(input), resolvers);
    }

    /** Build an unparsed placeholder resolver ({@code <name>} -> literal value). */
    public static TagResolver placeholder(String name, String value) {
        return Placeholder.unparsed(name, value == null ? "" : value);
    }

    /**
     * Build a placeholder resolver that inserts an already-rendered Component, so the
     * value keeps its own colours (a loot tier name, for instance).
     */
    public static TagResolver placeholder(String name, Component value) {
        return Placeholder.component(name, value == null ? Component.empty() : value);
    }

    /** Serialize to legacy section-sign text, for contexts such as PlaceholderAPI. */
    public static String legacy(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    /** Serialize to uncoloured plain text, for console logs. */
    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * Convert legacy {@code &}/{@code §} colour and format codes (including
     * {@code &#RRGGBB} hex) into MiniMessage tags. Text that is not a code is
     * left untouched, so existing MiniMessage tags survive unchanged.
     */
    static String legacyToMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String s = input.replace('§', '&');
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '#' && i + 7 < s.length()) {
                    String hex = s.substring(i + 2, i + 8);
                    if (isHex(hex)) {
                        out.append("<#").append(hex).append('>');
                        i += 7;
                        continue;
                    }
                }
                String tag = codeToTag(Character.toLowerCase(next));
                if (tag != null) {
                    out.append(tag);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static String codeToTag(char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }
}
