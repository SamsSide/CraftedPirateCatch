package net.samsside.craftedpiratecatch.event;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** WorldGuard region membership for the dock. */
public final class RegionChecker {

    public enum Result { INSIDE, OUTSIDE, MISCONFIGURED }

    private RegionChecker() {
    }

    public static Result check(Location loc, String worldName, String regionId) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return Result.MISCONFIGURED;
        }
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager manager = container.get(BukkitAdapter.adapt(world));
        if (manager == null || !manager.hasRegion(regionId)) {
            return Result.MISCONFIGURED;
        }
        if (loc.getWorld() == null || !loc.getWorld().getUID().equals(world.getUID())) {
            return Result.OUTSIDE;
        }
        ApplicableRegionSet set = container.createQuery().getApplicableRegions(BukkitAdapter.adapt(loc));
        for (ProtectedRegion region : set) {
            if (region.getId().equalsIgnoreCase(regionId)) {
                return Result.INSIDE;
            }
        }
        return Result.OUTSIDE;
    }
}
