package net.samsside.craftedpiratecatch.event;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Ranked;
import net.samsside.craftedpiratecatch.storage.Database.ScoreRow;

/** The running (live or TEST) event. TEST events have no day and are never written to the DB. */
public final class ActiveEvent {

    public final boolean test;
    public final LocalDate day; // null for TEST
    public final String slot;   // "HH:mm", or "-" for TEST
    public final long startMs;
    public final long endMs;
    /** DB id; set on the DB thread by the insert task, which runs before any later DB task. */
    public final AtomicLong id = new AtomicLong(-1);

    public final ConcurrentHashMap<UUID, ScoreEntry> scores = new ConcurrentHashMap<>();
    /** uuid + ":" + tier ids that already received a trophy this event. */
    public final Set<String> trophies = ConcurrentHashMap.newKeySet();
    /** Players already shown the outside-region hint this event. */
    public final Set<UUID> hinted = ConcurrentHashMap.newKeySet();
    public boolean regionErrorLogged;
    public boolean finalizing;

    public ActiveEvent(boolean test, LocalDate day, String slot, long startMs, long endMs) {
        this.test = test;
        this.day = day;
        this.slot = slot;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public long id() {
        return id.get();
    }

    public List<ScoreRow> allRows() {
        List<ScoreRow> out = new ArrayList<>(scores.size());
        for (ScoreEntry e : scores.values()) {
            out.add(e.toRow());
        }
        return out;
    }

    public List<ScoreRow> dirtyRows() {
        List<ScoreRow> out = new ArrayList<>();
        for (ScoreEntry e : scores.values()) {
            ScoreRow r = e.takeIfDirty();
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    public List<Ranked> rankedInput() {
        List<Ranked> out = new ArrayList<>(scores.size());
        for (ScoreEntry e : scores.values()) {
            out.add(e.toRanked());
        }
        return out;
    }
}
