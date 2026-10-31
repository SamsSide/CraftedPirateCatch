package net.samsside.craftedpiratecatch.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import net.samsside.craftedpiratecatch.storage.Database.FinalizePlan;
import net.samsside.craftedpiratecatch.storage.Database.PendingReward;
import net.samsside.craftedpiratecatch.storage.Database.ScoreRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir
    Path dir;

    private Database db;

    @BeforeEach
    void open() throws Exception {
        db = new Database(Logger.getLogger("test"));
        db.open(new File(dir.toFile(), "data.db")).get();
    }

    @AfterEach
    void close() {
        db.shutdown();
    }

    private static final UUID A = UUID.randomUUID();

    private FinalizePlan<String> plan() {
        return new FinalizePlan<>(A, List.of(new PendingReward(0, A, "say key " + A, "reward-key", "{}", 1, 1L)),
                "ok");
    }

    @Test
    void finalizeIsExactlyOnce() throws Exception {
        long id = db.insertEvent("2026-11-02", "13:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
        List<ScoreRow> scores = List.of(new ScoreRow(A, "Alice", "1.1.1.1", 10, 2, 5L));

        FinalizePlan<String> first = db.finalizeEvent(() -> id, scores, 0, 10, (s, cd) -> plan()).get();
        assertNotNull(first);
        FinalizePlan<String> second = db.finalizeEvent(() -> id, scores, 0, 10, (s, cd) -> plan()).get();
        assertNull(second, "second finalize must be a no-op");

        assertEquals(1, db.loadPending(A).get().size());
        Database.EventRow row = db.loadEvents().get().get(0);
        assertEquals(Database.FINISHED, row.status());
        assertTrue(row.finalized());
    }

    @Test
    void uniqueRotationDay() throws Exception {
        db.insertEvent("2026-11-02", "13:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
        assertThrows(ExecutionException.class,
                () -> db.insertEvent("2026-11-02", "13:00", 0, 1, Database.RUNNING, "SCHEDULER").get());
    }

    @Test
    void pendingDeleteClaimsOnceAndRestoreKeepsId() throws Exception {
        long rid = db.insertPending(new PendingReward(0, A, "say hi", "", "{}", 0, 1L)).get();
        assertTrue(db.deletePending(rid).get());
        assertFalse(db.deletePending(rid).get(), "a row can only be claimed once");

        PendingReward claimed = new PendingReward(rid, A, "say hi", "", "{}", 0, 1L);
        db.restorePending(List.of(claimed)).get();
        db.restorePending(List.of(claimed)).get(); // idempotent
        List<PendingReward> rows = db.loadPending(A).get();
        assertEquals(1, rows.size());
        assertEquals(rid, rows.get(0).id());
    }

    @Test
    void trophyQueuedOnlyOnce() throws Exception {
        long id = db.insertEvent("2026-11-03", "17:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
        PendingReward r = new PendingReward(0, A, "iagive A x 1 silent", "", "{}", 1, 1L);
        db.insertTrophy(() -> id, A, "rare", r).get();
        db.insertTrophy(() -> id, A, "rare", r).get();
        assertEquals(1, db.loadPending(A).get().size());
        assertEquals(1, db.loadTrophies(id).get().size());
    }

    @Test
    void scoresSurviveAndUpsert() throws Exception {
        long id = db.insertEvent("2026-11-04", "21:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
        db.upsertScores(() -> id, List.of(new ScoreRow(A, "Alice", "1.1.1.1", 3, 1, 5L))).get();
        db.upsertScores(() -> id, List.of(new ScoreRow(A, "Alice2", "9.9.9.9", 11, 2, 9L))).get();
        List<ScoreRow> rows = db.loadScores(id).get();
        assertEquals(1, rows.size());
        assertEquals(11, rows.get(0).points());
        assertEquals("Alice2", rows.get(0).name());
        assertEquals("1.1.1.1", rows.get(0).ip(), "IP is kept from the first catch");
    }

    @Test
    void cancelOnlyOnce() throws Exception {
        long id = db.insertEvent("2026-11-05", "01:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
        assertTrue(db.cancelEvent(() -> id, List.of()).get());
        assertFalse(db.cancelEvent(() -> id, List.of()).get());
        assertNull(db.finalizeEvent(() -> id, List.of(), 0, 10, (s, cd) -> plan()).get(),
                "a cancelled event can never be finalized");
    }
}
