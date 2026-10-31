package net.samsside.craftedpiratecatch.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal encoder/decoder for a flat JSON object of string keys to string values,
 * used for the {@code pending_rewards.message_args} column. No dependency needed.
 */
public final class FlatJson {

    private FlatJson() {
    }

    public static String encode(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(sb, e.getKey());
            sb.append(':');
            quote(sb, e.getValue() == null ? "" : e.getValue());
        }
        return sb.append('}').toString();
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        quote(sb, s);
        return sb.toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Decode an object produced by {@link #encode}. Malformed input yields an empty map. */
    public static Map<String, String> decode(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        int[] pos = {0};
        try {
            skipWs(json, pos);
            expect(json, pos, '{');
            skipWs(json, pos);
            if (peek(json, pos) == '}') {
                return out;
            }
            while (true) {
                skipWs(json, pos);
                String key = readString(json, pos);
                skipWs(json, pos);
                expect(json, pos, ':');
                skipWs(json, pos);
                String value = readString(json, pos);
                out.put(key, value);
                skipWs(json, pos);
                char c = json.charAt(pos[0]++);
                if (c == '}') {
                    return out;
                }
                if (c != ',') {
                    return new LinkedHashMap<>();
                }
            }
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    private static char peek(String s, int[] pos) {
        return s.charAt(pos[0]);
    }

    private static void skipWs(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    private static void expect(String s, int[] pos, char c) {
        if (s.charAt(pos[0]) != c) {
            throw new IllegalArgumentException("expected " + c);
        }
        pos[0]++;
    }

    private static String readString(String s, int[] pos) {
        expect(s, pos, '"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(pos[0]++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char n = s.charAt(pos[0]++);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(pos[0], pos[0] + 4), 16));
                        pos[0] += 4;
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
    }
}
