package net.samsside.craftedpiratecatch.event;

import net.samsside.craftedpiratecatch.CraftedPirateCatch;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Fishing, anti-AFK activity, and join/quit housekeeping. */
public final class EventListener implements Listener {

    private final CraftedPirateCatch plugin;

    public EventListener(CraftedPirateCatch plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        plugin.events().onCatch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        boolean moved = from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();
        boolean looked = Math.abs(angleDelta(from.getYaw(), to.getYaw())) >= 1.0f
                || Math.abs(from.getPitch() - to.getPitch()) >= 1.0f;
        if (moved || looked) {
            plugin.activity().touch(event.getPlayer().getUniqueId());
        }
    }

    private static float angleDelta(float a, float b) {
        float d = (b - a) % 360f;
        if (d > 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.activity().touch(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                plugin.dispatcher().drain(player);
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.activity().forget(player.getUniqueId());
        plugin.dispatcher().onQuit(player.getUniqueId());
        plugin.events().onQuit(player.getUniqueId());
    }
}
