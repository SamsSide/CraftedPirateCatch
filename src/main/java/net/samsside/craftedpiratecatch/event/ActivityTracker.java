package net.samsside.craftedpiratecatch.event;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Last time each player moved a block or turned their view by at least 1 degree (anti-AFK). */
public final class ActivityTracker {

    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    public void touch(UUID uuid) {
        last.put(uuid, System.currentTimeMillis());
    }

    /** Last activity time, or 0 if never seen. */
    public long lastActivity(UUID uuid) {
        return last.getOrDefault(uuid, 0L);
    }

    public void forget(UUID uuid) {
        last.remove(uuid);
    }
}
