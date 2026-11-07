package net.samsside.craftedpiratecatch.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import net.samsside.craftedpiratecatch.storage.Database.EventRow;
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
    private static final UUID B = UUID.randomUUID();

    private FinalizePlan<String> plan() {
        return new FinalizePlan<>(A, List.of(new PendingReward(0, A, "say key " + A, "reward-key", "{}", 1, 1L)),
                "ok");
    }

    @Test
    void finalizeIsExactlyOnce() throws Exception {
        long id = db.insertEvent("2026-11-02", 0, "13:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
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
        db.insertEvent("2026-11-02", 0, "13:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
        assertThrows(ExecutionException.class,
                () -> db.insertEvent("2026-11-02", 0, "13:00", 0, 1, Database.RUNNING, "SCHEDULER").get());
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
        long id = db.insertEvent("2026-11-03", 0, "17:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
        PendingReward r = new PendingReward(0, A, "iagive A x 1 silent", "", "{}", 1, 1L);
        db.insertTrophy(() -> id, A, "rare", r).get();
        db.insertTrophy(() -> id, A, "rare", r).get();
        assertEquals(1, db.loadPending(A).get().size());
        assertEquals(1, db.loadTrophies(id).get().size());
    }

    @Test
    void scoresSurviveAndUpsert() throws Exception {
        long id = db.insertEvent("2026-11-04", 0, "21:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
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
        long id = db.insertEvent("2026-11-05", 0, "01:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
        assertTrue(db.cancelEvent(() -> id, List.of()).get());
        assertFalse(db.cancelEvent(() -> id, List.of()).get());
        assertNull(db.finalizeEvent(() -> id, List.of(), 0, 10, (s, cd) -> plan()).get(),
                "a cancelled event can never be finalized");
    }

    // ------------------------------------------------------------------ schema v2 / v1 migration

    private static final String[] V1_DDL = {
        "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, rotation_day TEXT NOT NULL, slot TEXT NOT NULL, "
            + "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, status TEXT NOT NULL, started_by TEXT NOT NULL, "
            + "finalized INTEGER NOT NULL DEFAULT 0)",
        "CREATE UNIQUE INDEX ux_events_day ON events(rotation_day)",
        "CREATE TABLE scores (event_id INTEGER NOT NULL REFERENCES events(id), uuid TEXT NOT NULL, name TEXT NOT NULL, "
            + "ip TEXT, points INTEGER NOT NULL DEFAULT 0, pirate_catches INTEGER NOT NULL DEFAULT 0, "
            + "last_increase_ms INTEGER NOT NULL, PRIMARY KEY (event_id, uuid))",
        "CREATE TABLE trophies (event_id INTEGER NOT NULL, uuid TEXT NOT NULL, tier TEXT NOT NULL, PRIMARY KEY (event_id, uuid, tier))",
        "CREATE TABLE key_wins (uuid TEXT NOT NULL, event_id INTEGER NOT NULL, won_ms INTEGER NOT NULL, PRIMARY KEY (uuid, event_id))",
        "CREATE INDEX ix_key_wins_uuid ON key_wins(uuid, won_ms)",
        "CREATE TABLE pending_rewards (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, command TEXT NOT NULL, "
            + "message_key TEXT NOT NULL, message_args TEXT NOT NULL, needs_slots INTEGER NOT NULL, created_ms INTEGER NOT NULL)",
        "CREATE INDEX ix_pending_uuid ON pending_rewards(uuid)"};

    /** A v1.0.0-shaped file in WAL mode with two events, scores, a trophy, a key win and two pending rewards. */
    private File v1File(String name) throws Exception {
        File f = new File(dir.toFile(), name);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            for (String ddl : V1_DDL) {
                st.execute(ddl);
            }
            st.execute("INSERT INTO events VALUES (1,'2026-11-02','13:00',100,200,'FINISHED','SCHEDULER',1)");
            st.execute("INSERT INTO events VALUES (2,'2026-11-03','17:00',300,400,'RUNNING','SCHEDULER',0)");
            st.execute("INSERT INTO scores VALUES (1,'" + A + "','Alice','1.1.1.1',12,3,150)");
            st.execute("INSERT INTO scores VALUES (1,'" + B + "','Bob','2.2.2.2',5,1,160)");
            st.execute("INSERT INTO trophies VALUES (1,'" + A + "','rare')");
            st.execute("INSERT INTO key_wins VALUES ('" + A + "',1,200)");
            st.execute("INSERT INTO pending_rewards VALUES (5,'" + B + "','say a','reward-shards','{}',1,201)");
            st.execute("INSERT INTO pending_rewards VALUES (9,'" + B + "','say b','reward-participation','{}',1,202)");
        }
        return f;
    }

    private static int userVersion(File f) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.getInt(1);
        }
    }

    private File[] backups() {
        return backups("old.db");
    }

    private File[] backups(String dbName) {
        return dir.toFile().listFiles((d, n) -> n.startsWith(dbName + ".v1-backup-"));
    }

    @Test
    void migratesV1KeepingAllData() throws Exception {
        File old = v1File("old.db");
        Database v1 = new Database(Logger.getLogger("test"));
        try {
            v1.open(old).get();

            List<EventRow> events = v1.loadEvents().get().stream()
                    .sorted((x, y) -> Long.compare(x.id(), y.id())).toList();
            assertEquals(2, events.size());
            assertEquals(1, events.get(0).id());
            assertEquals(2, events.get(1).id());
            assertEquals(Database.FINISHED, events.get(0).status());
            assertEquals(Database.RUNNING, events.get(1).status());
            assertTrue(events.get(0).finalized());
            assertFalse(events.get(1).finalized());
            assertEquals(Database.WHOLE_DAY, events.get(0).session());
            assertEquals(Database.WHOLE_DAY, events.get(1).session());

            List<ScoreRow> scores = v1.loadScores(1).get();
            assertEquals(2, scores.size());
            ScoreRow alice = scores.stream().filter(r -> r.uuid().equals(A)).findFirst().orElseThrow();
            assertEquals("Alice", alice.name());
            assertEquals(12, alice.points());
            assertTrue(v1.loadTrophies(1).get().contains(A + ":rare"));
            assertEquals(List.of(5L, 9L), v1.loadPending(B).get().stream().map(PendingReward::id).sorted().toList());

            int keyWins = v1.submit(c -> {
                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM key_wins WHERE uuid=? AND event_id=1 AND won_ms=200")) {
                    ps.setString(1, A.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.getInt(1);
                    }
                }
            }).get();
            assertEquals(1, keyWins, "the key win survived the migration");

            // the unique index was swapped: a real session coexists with the -1 whole-day row
            v1.insertEvent("2026-11-02", 0, "13:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
            v1.insertEvent("2026-11-04", 0, "13:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
            v1.insertEvent("2026-11-04", 1, "21:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
        } finally {
            v1.shutdown();
        }

        assertEquals(2, userVersion(old));
        File[] backups = backups();
        assertNotNull(backups);
        assertEquals(1, backups.length, () -> Arrays.toString(backups));
        assertEquals(0, userVersion(backups[0]));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backups[0].getAbsolutePath());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM events")) {
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        File old = v1File("old.db");
        Database first = new Database(Logger.getLogger("test"));
        try {
            first.open(old).get();
        } finally {
            first.shutdown();
        }
        Database second = new Database(Logger.getLogger("test"));
        try {
            second.open(old).get();
            assertEquals(2, second.loadEvents().get().size());
        } finally {
            second.shutdown();
        }
        File[] backups = backups();
        assertNotNull(backups);
        assertEquals(1, backups.length, () -> Arrays.toString(backups));
        assertEquals(2, userVersion(old));
    }

    @Test
    void freshFileIsV2() throws Exception {
        int version = db.submit(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) {
                return r.getInt(1);
            }
        }).get();
        assertEquals(2, version);
        File[] backups = dir.toFile().listFiles((d, n) -> n.startsWith("data.db.v1-backup-"));
        assertNotNull(backups);
        assertEquals(0, backups.length, () -> Arrays.toString(backups));
    }

    @Test
    void refusesNewerSchema() throws Exception {
        File newer = new File(dir.toFile(), "new.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + newer.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("PRAGMA user_version = 3");
        }
        Database future = new Database(Logger.getLogger("test"));
        try {
            assertThrows(ExecutionException.class, () -> future.open(newer).get());
        } finally {
            future.shutdown();
        }
        assertEquals(3, userVersion(newer));
    }

    @Test
    void twoSessionsPerDay() throws Exception {
        db.insertEvent("2026-11-07", 0, "13:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
        db.insertEvent("2026-11-07", 1, "21:00", 0, 1, Database.RUNNING, "SCHEDULER").get();
        assertThrows(ExecutionException.class,
                () -> db.insertEvent("2026-11-07", 0, "13:00", 0, 1, Database.RUNNING, "SCHEDULER").get());
        assertFalse(db.deleteSkipped("2026-11-07", 1).get(), "a RUNNING row is not SKIPPED");

        db.insertEvent("2026-11-08", 1, "21:00", 0, 1, Database.SKIPPED, "SCHEDULER").get();
        assertFalse(db.deleteSkipped("2026-11-08", 0).get(), "session 0 was never skipped");
        assertTrue(db.deleteSkipped("2026-11-08", 1).get());
    }

    @Test
    void backupIncludesUncheckpointedWal() throws Exception {
        File f = v1File("wal.db");
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = raw.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA wal_autocheckpoint=0");
            st.execute("INSERT INTO events VALUES (3,'2026-11-04','21:00',500,600,'FINISHED','SCHEDULER',1)");

            Database v1 = new Database(Logger.getLogger("test"));
            try {
                v1.open(f).get();
                assertEquals(3, v1.loadEvents().get().size());
            } finally {
                v1.shutdown();
            }
        }
        File[] backups = backups("wal.db");
        assertNotNull(backups);
        assertEquals(1, backups.length, () -> Arrays.toString(backups));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backups[0].getAbsolutePath());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM events")) {
            assertEquals(3, rs.getInt(1), "the WAL-only row reached the backup");
        }
    }
}
