package net.samsside.craftedpiratecatch.command;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
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
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /piratecatch} (alias {@code /pirate}): player info plus permission-gated admin tools. */
public final class PirateCommand implements TabExecutor {

    public static final String PERM_USE = "craftedpiratecatch.use";
    public static final String PERM_RELOAD = "craftedpiratecatch.reload";
    public static final String PERM_START = "craftedpiratecatch.admin.start";
    public static final String PERM_STOP = "craftedpiratecatch.admin.stop";
    public static final String PERM_CANCEL = "craftedpiratecatch.admin.cancel";
    public static final String PERM_SKIP = "craftedpiratecatch.admin.skip";
    public static final String PERM_TEST = "craftedpiratecatch.admin.test";
    public static final String PERM_STATUS = "craftedpiratecatch.admin.status";
    public static final String PERM_RESET = "craftedpiratecatch.admin.resetcooldown";

    /** Admin sub-command -> permission, in tab-complete order. */
    private static final String[][] ADMIN = {
            {"start", PERM_START}, {"stop", PERM_STOP}, {"cancel", PERM_CANCEL}, {"skip", PERM_SKIP},
            {"unskip", PERM_SKIP}, {"test", PERM_TEST}, {"status", PERM_STATUS}, {"schedule", PERM_STATUS},
            {"resetcooldown", PERM_RESET}};

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_NAME_LENGTH = 16;

    private final CraftedPirateCatch plugin;

    public PirateCommand(CraftedPirateCatch plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    private void usage(CommandSender sender, String usage) {
        msg().send(sender, "usage", Text.placeholder("usage", usage));
    }

    private boolean deny(CommandSender sender, String perm) {
        if (sender.hasPermission(perm)) {
            return false;
        }
        msg().send(sender, "no-permission");
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (deny(sender, PERM_USE)) {
            return true;
        }
        String base = "/" + label;
        if (args.length == 0) {
            info(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "top" -> {
                if (args.length != 1) {
                    usage(sender, base + " top");
                } else {
                    sendTop(sender);
                }
            }
            case "reload" -> {
                if (deny(sender, PERM_RELOAD)) {
                    return true;
                }
                if (args.length != 1) {
                    usage(sender, base + " reload");
                } else {
                    plugin.reload(sender);
                }
            }
            case "admin" -> admin(sender, base, args);
            default -> usage(sender, base + " [top|reload|admin]");
        }
        return true;
    }

    // ------------------------------------------------------------------ player info

    private void info(CommandSender sender) {
        EventManager events = plugin.events();
        ActiveEvent ev = events.active();
        PluginConfig cfg = plugin.cfg();
        if (ev != null && events.isLive()) {
            msg().send(sender, "info-live",
                    Text.placeholder("timeleft", Durations.format(ev.endMs - System.currentTimeMillis())));
            if (sender instanceof Player p) {
                Integer rank = events.snapshot().rankOf().get(p.getUniqueId());
                msg().send(sender, "info-your-score",
                        Text.placeholder("score", String.valueOf(events.scoreOf(p.getUniqueId()))),
                        Text.placeholder("rank", rank == null ? "-" : String.valueOf(rank)));
            }
            sendTop(sender);
            return;
        }
        if (cfg.rotation == null) {
            msg().send(sender, "info-disabled");
            return;
        }
        Instant now = Instant.now();
        Rotation.Slot next = cfg.rotation.nextStart(now, events::isClosed);
        if (next == null) {
            msg().send(sender, "info-ended");
            return;
        }
        msg().send(sender, "info-next", Text.placeholder("time", msg().formatTime(next.start())),
                Text.placeholder("in", Durations.format(next.start().toEpochMilli() - now.toEpochMilli())));
    }

    private void sendTop(CommandSender sender) {
        EventManager events = plugin.events();
        Snapshot snap = events.isLive() ? events.snapshot() : Snapshot.EMPTY;
        if (snap.ranked().isEmpty()) {
            msg().send(sender, "top-empty");
            return;
        }
        msg().send(sender, "top-header");
        int show = Math.min(plugin.cfg().showTop, snap.ranked().size());
        for (int i = 0; i < show; i++) {
            Ranked r = snap.ranked().get(i);
            msg().send(sender, "top-line", Text.placeholder("rank", String.valueOf(i + 1)),
                    Text.placeholder("name", r.name()), Text.placeholder("score", String.valueOf(r.points())));
        }
    }

    // ------------------------------------------------------------------ admin

    private void admin(CommandSender sender, String base, String[] args) {
        if (!hasAnyAdmin(sender)) {
            msg().send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            usage(sender, base + " admin <" + String.join("|", allowedAdmin(sender)) + ">");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        String perm = permFor(sub);
        if (perm == null) {
            usage(sender, base + " admin <" + String.join("|", allowedAdmin(sender)) + ">");
            return;
        }
        if (deny(sender, perm)) {
            return;
        }
        String subUsage = base + " admin " + sub;
        switch (sub) {
            case "test" -> {
                if (args.length > 3) {
                    usage(sender, subUsage + " [1-60]");
                    return;
                }
                adminTest(sender, args.length == 3 ? args[2] : null, subUsage + " [1-60]");
            }
            case "resetcooldown" -> {
                if (args.length != 3) {
                    usage(sender, subUsage + " <player>");
                    return;
                }
                adminResetCooldown(sender, args[2]);
            }
            case "start", "skip", "unskip" -> {
                String sessionUsage = subUsage + " [1-" + Math.max(1, todayCount()) + "]";
                if (args.length > 3) {
                    usage(sender, sessionUsage);
                    return;
                }
                Integer requested = null;
                if (args.length == 3) {
                    try {
                        requested = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        usage(sender, sessionUsage);
                        return;
                    }
                }
                switch (sub) {
                    case "start" -> adminStart(sender, requested, sessionUsage);
                    case "skip" -> adminSkip(sender, requested, sessionUsage);
                    default -> adminUnskip(sender, requested, sessionUsage);
                }
            }
            default -> {
                if (args.length != 2) {
                    usage(sender, subUsage);
                    return;
                }
                switch (sub) {
                    case "stop" -> {
                        if (plugin.events().stop()) {
                            msg().send(sender, "admin-stopped");
                        } else {
                            msg().send(sender, "admin-not-running");
                        }
                    }
                    case "cancel" -> {
                        if (plugin.events().cancel()) {
                            msg().send(sender, "admin-cancelled");
                        } else {
                            msg().send(sender, "admin-not-running");
                        }
                    }
                    case "status" -> adminStatus(sender);
                    case "schedule" -> adminSchedule(sender);
                    default -> usage(sender, base + " admin <" + String.join("|", allowedAdmin(sender)) + ">");
                }
            }
        }
    }

    /** Today's sessions, or null after sending info-disabled/info-ended. */
    private List<Rotation.Slot> today(CommandSender sender) {
        Rotation rotation = plugin.cfg().rotation;
        if (rotation == null) {
            msg().send(sender, "info-disabled");
            return null;
        }
        LocalDate today = rotation.todayAt(Instant.now());
        if (today.isAfter(rotation.lastDay())) {
            msg().send(sender, "info-ended");
            return null;
        }
        if (today.isBefore(rotation.firstDay())) {
            msg().send(sender, "info-disabled");
            return null;
        }
        return rotation.slots(today);
    }

    private void adminStart(CommandSender sender, Integer requested, String usage) {
        List<Rotation.Slot> today = today(sender);
        if (today == null) {
            return;
        }
        EventManager events = plugin.events();
        ActiveEvent current = events.active();
        if (current != null && !current.test) {
            msg().send(sender, "admin-already-ran");
            return;
        }
        SessionPicker.Pick pick = SessionPicker.start(today, requested, Instant.now(), events::status);
        switch (pick.outcome()) {
            case OK -> {
                events.startStaff(pick.slot(), sender.getName());
                msg().send(sender, "admin-started");
            }
            case BAD_ARG -> usage(sender, usage);
            default -> msg().send(sender, "admin-already-ran");
        }
    }

    private void adminSkip(CommandSender sender, Integer requested, String usage) {
        List<Rotation.Slot> today = today(sender);
        if (today == null) {
            return;
        }
        EventManager events = plugin.events();
        SessionPicker.Pick pick = SessionPicker.skip(today, requested, Instant.now(), events::status);
        switch (pick.outcome()) {
            case OK -> {
                events.skip(pick.slot(), sender.getName());
                msg().send(sender, "admin-skipped");
            }
            case ALREADY_SKIPPED -> msg().send(sender, "admin-skipped");
            case BAD_ARG -> usage(sender, usage);
            default -> msg().send(sender, "admin-already-ran");
        }
    }

    private void adminUnskip(CommandSender sender, Integer requested, String usage) {
        List<Rotation.Slot> today = today(sender);
        if (today == null) {
            return;
        }
        EventManager events = plugin.events();
        SessionPicker.Pick pick = SessionPicker.unskip(today, requested, Instant.now(), events::status,
                s -> events.isWholeDay(s.day()));
        switch (pick.outcome()) {
            case OK -> events.unskip(pick.slot().day(), pick.slot().session(),
                    removed -> msg().send(sender, removed ? "admin-unskipped" : "admin-already-ran"));
            case ALREADY_UNSKIPPED -> msg().send(sender, "admin-unskipped");
            case BAD_ARG -> usage(sender, usage);
            default -> msg().send(sender, "admin-already-ran");
        }
    }

    /** Today's session count, minimum 1 for usage text; 0 when disabled (tab-complete). */
    private int todayCount() {
        Rotation rotation = plugin.cfg().rotation;
        return rotation == null ? 0 : rotation.sessionsOn(rotation.todayAt(Instant.now()));
    }

    private void adminTest(CommandSender sender, String arg, String usage) {
        int minutes = 5;
        if (arg != null) {
            try {
                minutes = Integer.parseInt(arg);
            } catch (NumberFormatException e) {
                usage(sender, usage);
                return;
            }
        }
        if (minutes < 1 || minutes > 60) {
            usage(sender, usage);
            return;
        }
        if (plugin.events().active() != null) {
            msg().send(sender, "admin-already-ran");
            return;
        }
        plugin.events().startTest(minutes);
        msg().send(sender, "admin-test-started", Text.placeholder("minutes", String.valueOf(minutes)));
    }

    private void adminStatus(CommandSender sender) {
        EventManager events = plugin.events();
        Rotation rotation = plugin.cfg().rotation;
        ActiveEvent ev = events.active();
        Instant now = Instant.now();
        Rotation.Slot next = rotation == null ? null : rotation.nextStart(now, events::isClosed);
        String state;
        if (ev != null && ev.test) {
            state = "TEST";
        } else if (ev != null) {
            state = "RUNNING";
        } else if (rotation == null) {
            state = "DISABLED";
        } else if (next == null) {
            state = "SEASON_OVER";
        } else {
            state = "IDLE";
        }
        String day = "-";
        String slot = "-";
        if (ev != null && !ev.test) {
            day = String.valueOf(ev.day);
            slot = ev.slot;
        } else if (next != null) {
            day = next.day().toString();
            slot = HHMM.format(next.time());
        }
        String session = "-";
        if (ev != null && !ev.test) {
            session = ev.session < 0 ? "-" : (ev.session + 1) + "/"
                    + Math.max(ev.session + 1, rotation == null ? 0 : rotation.sessionsOn(ev.day));
        } else if (next != null) {
            session = next.label();
        }
        String time = next == null ? "-" : msg().formatTime(next.start());
        msg().send(sender, "admin-status", Text.placeholder("state", state), Text.placeholder("day", day),
                Text.placeholder("session", session), Text.placeholder("slot", slot),
                Text.placeholder("time", time));
    }

    private void adminSchedule(CommandSender sender) {
        Rotation rotation = plugin.cfg().rotation;
        if (rotation == null) {
            msg().send(sender, "info-disabled");
            return;
        }
        List<Rotation.Slot> up = rotation.upcoming(Instant.now(), 8, plugin.events()::isClosed);
        if (up.isEmpty()) {
            msg().send(sender, "info-ended");
            return;
        }
        for (Rotation.Slot s : up) {
            msg().send(sender, "admin-schedule-line", Text.placeholder("day", s.day().toString()),
                    Text.placeholder("session", s.label()), Text.placeholder("time", msg().formatTime(s.start())));
        }
    }

    private void adminResetCooldown(CommandSender sender, String name) {
        if (name.length() > MAX_NAME_LENGTH + 1 || name.isBlank()) {
            msg().send(sender, "unknown-player", Text.placeholder("player", name.substring(0,
                    Math.min(name.length(), MAX_NAME_LENGTH + 1))));
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(name);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(name);
        }
        if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
            msg().send(sender, "unknown-player", Text.placeholder("player", name));
            return;
        }
        UUID uuid = target.getUniqueId();
        String display = target.getName() == null ? name : target.getName();
        long since = System.currentTimeMillis() - plugin.cfg().keyCooldownHours * 3_600_000L;
        plugin.db().deleteKeyWinsSince(uuid, since).whenComplete((count, ex) -> plugin.sync(() -> {
            if (ex != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not reset key cooldown for " + display, ex);
                return;
            }
            plugin.getLogger().info(sender.getName() + " reset the key cooldown for " + display + " (" + count
                    + " row(s)).");
            msg().send(sender, "admin-cooldown-reset", Text.placeholder("player", display));
        }));
    }

    private static String permFor(String sub) {
        for (String[] a : ADMIN) {
            if (a[0].equals(sub)) {
                return a[1];
            }
        }
        return null;
    }

    private static boolean hasAnyAdmin(CommandSender sender) {
        for (String[] a : ADMIN) {
            if (sender.hasPermission(a[1])) {
                return true;
            }
        }
        return false;
    }

    private static List<String> allowedAdmin(CommandSender sender) {
        List<String> out = new ArrayList<>();
        for (String[] a : ADMIN) {
            if (sender.hasPermission(a[1])) {
                out.add(a[0]);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ tab-complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM_USE)) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("top");
            if (sender.hasPermission(PERM_RELOAD)) {
                options.add("reload");
            }
            if (hasAnyAdmin(sender)) {
                options.add("admin");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            options.addAll(allowedAdmin(sender));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("resetcooldown") && sender.hasPermission(PERM_RESET)) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!(sender instanceof Player viewer) || viewer.canSee(p)) {
                        options.add(p.getName());
                    }
                }
            } else if (sub.equals("test") && sender.hasPermission(PERM_TEST)) {
                options.addAll(List.of("5", "10", "15"));
            } else if ((sub.equals("start") && sender.hasPermission(PERM_START))
                    || ((sub.equals("skip") || sub.equals("unskip")) && sender.hasPermission(PERM_SKIP))) {
                int n = todayCount();
                if (n >= 2) {
                    for (int i = 1; i <= n; i++) {
                        options.add(String.valueOf(i));
                    }
                }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(o);
            }
        }
        return out;
    }
}
