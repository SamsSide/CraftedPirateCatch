package net.samsside.craftedpiratecatch.event;

import java.util.UUID;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Ranked;
import net.samsside.craftedpiratecatch.storage.Database.ScoreRow;

/** One player's live score in the running event. Mutated on the main thread only. */
public final class ScoreEntry {

    private final UUID uuid;
    private String name;
    private String ip;
    private int points;
    private int pirateCatches;
    private long lastIncreaseMs;
    private boolean dirty;

    public ScoreEntry(UUID uuid, String name, String ip) {
        this.uuid = uuid;
        this.name = name;
        this.ip = ip;
    }

    public static ScoreEntry fromRow(ScoreRow r) {
        ScoreEntry e = new ScoreEntry(r.uuid(), r.name(), r.ip());
        e.points = r.points();
        e.pirateCatches = r.pirateCatches();
        e.lastIncreaseMs = r.lastIncreaseMs();
        return e;
    }

    /** Record a pirate catch. {@code ip} is kept from the first catch only. */
    public synchronized void addCatch(String currentName, String currentIp, int addPoints, long nowMs) {
        this.name = currentName;
        if (this.ip == null && currentIp != null) {
            this.ip = currentIp;
        }
        this.pirateCatches++;
        if (addPoints > 0) {
            this.points += addPoints;
            this.lastIncreaseMs = nowMs;
        } else if (this.lastIncreaseMs == 0) {
            this.lastIncreaseMs = nowMs;
        }
        this.dirty = true;
    }

    public synchronized int points() {
        return points;
    }

    public synchronized ScoreRow toRow() {
        return new ScoreRow(uuid, name, ip, points, pirateCatches, lastIncreaseMs);
    }

    /** Snapshot this entry for a flush if it changed, clearing the dirty flag. */
    public synchronized ScoreRow takeIfDirty() {
        if (!dirty) {
            return null;
        }
        dirty = false;
        return toRow();
    }

    public synchronized Ranked toRanked() {
        return new Ranked(uuid, name, ip, points, lastIncreaseMs);
    }
}
