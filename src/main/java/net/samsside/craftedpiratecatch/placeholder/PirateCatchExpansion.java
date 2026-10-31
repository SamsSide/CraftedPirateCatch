package net.samsside.craftedpiratecatch.placeholder;

import java.time.Instant;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.samsside.craftedpiratecatch.CraftedPirateCatch;
import net.samsside.craftedpiratecatch.config.Messages;
import net.samsside.craftedpiratecatch.config.PluginConfig;
import net.samsside.craftedpiratecatch.event.ActiveEvent;
import net.samsside.craftedpiratecatch.event.EventManager;
import net.samsside.craftedpiratecatch.event.Snapshot;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Ranked;
import net.samsside.craftedpiratecatch.schedule.Rotation;
import net.samsside.craftedpiratecatch.util.Durations;
import net.samsside.craftedpiratecatch.util.Text;
import org.bukkit.OfflinePlayer;

/** {@code %craftedpiratecatch_*%}. Served only from in-memory caches; never touches the DB. */
public final class PirateCatchExpansion extends PlaceholderExpansion {

    private static final int MAX_TOP = 10;

    private final CraftedPirateCatch plugin;

    public PirateCatchExpansion(CraftedPirateCatch plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "craftedpiratecatch";
    }

    @Override
    public String getAuthor() {
        return "SamsSide";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        EventManager events = plugin.events();
        ActiveEvent ev = events.active();
        boolean live = ev != null && events.isLive();
        Snapshot snap = live ? events.snapshot() : Snapshot.EMPTY;
        long now = System.currentTimeMillis();

        switch (params) {
            case "live":
                return String.valueOf(live);
            case "timeleft":
                return live ? Durations.format(ev.endMs - now) : "";
            case "next": {
                Rotation.Slot next = next();
                return next == null ? "" : plugin.messages().formatTime(next.start());
            }
            case "next_in": {
                Rotation.Slot next = next();
                return next == null ? "" : Durations.format(next.start().toEpochMilli() - now);
            }
            case "status":
                return status(live, ev, now);
            case "score":
                return player == null ? "0" : String.valueOf(live ? events.scoreOf(player.getUniqueId()) : 0);
            case "rank": {
                if (player == null) {
                    return "-";
                }
                Integer rank = snap.rankOf().get(player.getUniqueId());
                return rank == null ? "-" : String.valueOf(rank);
            }
            case "participants":
                return String.valueOf(snap.ranked().size());
            default:
                break;
        }

        // top_<n>_name / top_<n>_score
        if (params.startsWith("top_")) {
            String rest = params.substring(4);
            int us = rest.indexOf('_');
            if (us > 0) {
                int n;
                try {
                    n = Integer.parseInt(rest.substring(0, us));
                } catch (NumberFormatException e) {
                    return null;
                }
                if (n < 1 || n > MAX_TOP) {
                    return null;
                }
                Ranked r = snap.at(n);
                return switch (rest.substring(us + 1)) {
                    case "name" -> r == null ? "-" : r.name();
                    case "score" -> r == null ? "-" : String.valueOf(r.points());
                    default -> null;
                };
            }
        }
        return null;
    }

    private Rotation.Slot next() {
        PluginConfig cfg = plugin.cfg();
        if (cfg.rotation == null) {
            return null;
        }
        return cfg.rotation.nextStart(Instant.now(), plugin.events()::isClosed);
    }

    private String status(boolean live, ActiveEvent ev, long now) {
        Messages msg = plugin.messages();
        if (live) {
            return Text.legacy(msg.getNoPrefix("info-live", Text.placeholder("timeleft", Durations.format(ev.endMs - now))));
        }
        if (plugin.cfg().rotation == null) {
            return Text.legacy(msg.getNoPrefix("info-disabled"));
        }
        Rotation.Slot next = next();
        if (next == null) {
            return Text.legacy(msg.getNoPrefix("info-ended"));
        }
        return Text.legacy(msg.getNoPrefix("info-next", Text.placeholder("time", msg.formatTime(next.start())),
                Text.placeholder("in", Durations.format(next.start().toEpochMilli() - now))));
    }
}
