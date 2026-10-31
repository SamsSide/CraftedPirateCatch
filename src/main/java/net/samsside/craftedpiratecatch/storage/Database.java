package net.samsside.craftedpiratecatch.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite storage. One dedicated single-thread executor ("CraftedPirateCatch-DB") owns the only
 * connection; every method runs on it and returns a future, so callers on the main thread never
 * block (except the documented enable-recovery load and the disable flush).
 */
public final class Database {

    public static final String RUNNING = "RUNNING";
    public static final String FINISHED = "FINISHED";
    public static final String CANCELLED = "CANCELLED";
    public static final String SKIPPED = "SKIPPED";
    public static final String MISSED = "MISSED";

    /** Statuses that close a rotation day for scheduling. */
    public static final Set<String> CLOSED = Set.of(FINISHED, CANCELLED, SKIPPED, MISSED);

    public record EventRow(long id, String rotationDay, String slot, long startMs, long endMs,
                           String status, String startedBy, boolean finalized) {
    }

    public record ScoreRow(UUID uuid, String name, String ip, int points, int pirateCatches, long lastIncreaseMs) {
    }

    public record PendingReward(long id, UUID uuid, String command, String messageKey, String messageArgs,
                                int needsSlots, long createdMs) {
    }

    /** What finalize must write in its single transaction. */
    public record FinalizePlan<T>(UUID keyWinner, List<PendingReward> rewards, T payload) {
    }

    @FunctionalInterface
    public interface SqlTask<T> {
        T run(Connection c) throws SQLException;
    }

    private final Logger log;
    private final ExecutorService exec;
    private Connection conn; // only touched on the DB thread
    private File file;       // only touched on the DB thread

    public Database(Logger log) {
        this.log = log;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CraftedPirateCatch-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public <T> CompletableFuture<T> submit(SqlTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (conn == null) {
                    throw new SQLException("Database is not open");
                }
                return task.run(conn);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, exec);
    }

    /** Open (or re-open, closing the old connection) the given file and create the schema. */
    public CompletableFuture<Void> open(File dbFile) {
        return CompletableFuture.runAsync(() -> {
            try {
                closeConnection();
                try {
                    Class.forName("org.sqlite.JDBC");
                } catch (ClassNotFoundException e) {
                    throw new SQLException("SQLite driver not found (plugin.yml libraries)", e);
                }
                File parent = dbFile.getAbsoluteFile().getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new SQLException("Could not create " + parent);
                }
                Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA foreign_keys=ON");
                    st.execute("PRAGMA busy_timeout=5000");
                }
                createSchema(c);
                conn = c;
                file = dbFile;
                log.info("Database connected: " + dbFile.getName());
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Database connect FAILED for " + dbFile.getAbsolutePath(), e);
                throw new CompletionException(e);
            }
        }, exec);
    }

    /** The file currently open, read on the DB thread. */
    public CompletableFuture<File> currentFile() {
        return CompletableFuture.supplyAsync(() -> file, exec);
    }

    private void createSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS events (
                      id            INTEGER PRIMARY KEY AUTOINCREMENT,
                      rotation_day  TEXT    NOT NULL,
                      slot          TEXT    NOT NULL,
                      start_ms      INTEGER NOT NULL,
                      end_ms        INTEGER NOT NULL,
                      status        TEXT    NOT NULL,
                      started_by    TEXT    NOT NULL,
                      finalized     INTEGER NOT NULL DEFAULT 0
                    )""");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_events_day ON events(rotation_day)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS scores (
                      event_id         INTEGER NOT NULL REFERENCES events(id),
                      uuid             TEXT    NOT NULL,
                      name             TEXT    NOT NULL,
                      ip               TEXT,
                      points           INTEGER NOT NULL DEFAULT 0,
                      pirate_catches   INTEGER NOT NULL DEFAULT 0,
                      last_increase_ms INTEGER NOT NULL,
                      PRIMARY KEY (event_id, uuid)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS trophies (
                      event_id INTEGER NOT NULL, uuid TEXT NOT NULL, tier TEXT NOT NULL,
                      PRIMARY KEY (event_id, uuid, tier)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS key_wins (
                      uuid TEXT NOT NULL, event_id INTEGER NOT NULL, won_ms INTEGER NOT NULL,
                      PRIMARY KEY (uuid, event_id)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS ix_key_wins_uuid ON key_wins(uuid, won_ms)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS pending_rewards (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      uuid TEXT NOT NULL,
                      command TEXT NOT NULL,
                      message_key TEXT NOT NULL,
                      message_args TEXT NOT NULL,
                      needs_slots INTEGER NOT NULL,
                      created_ms INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS ix_pending_uuid ON pending_rewards(uuid)");
        }
    }

    // ------------------------------------------------------------------ events

    public CompletableFuture<List<EventRow>> loadEvents() {
        return submit(c -> {
            List<EventRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, rotation_day, slot, start_ms, end_ms, status, started_by, finalized FROM events");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EventRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getLong(5), rs.getString(6), rs.getString(7), rs.getInt(8) != 0));
                }
            }
            return out;
        });
    }

    /** Insert an events row and return its id. Fails on the unique rotation_day index. */
    public CompletableFuture<Long> insertEvent(String day, String slot, long startMs, long endMs,
                                               String status, String startedBy) {
        return submit(c -> insertEvent(c, day, slot, startMs, endMs, status, startedBy));
    }

    static long insertEvent(Connection c, String day, String slot, long startMs, long endMs,
                            String status, String startedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO events(rotation_day, slot, start_ms, end_ms, status, started_by, finalized) "
                        + "VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, day);
            ps.setString(2, slot);
            ps.setLong(3, startMs);
            ps.setLong(4, endMs);
            ps.setString(5, status);
            ps.setString(6, startedBy);
            ps.setInt(7, CLOSED.contains(status) && !SKIPPED.equals(status) ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("No id returned for events insert");
    }

    /** Delete a SKIPPED row for {@code day}. True if a row was removed. */
    public CompletableFuture<Boolean> deleteSkipped(String day) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM events WHERE rotation_day = ? AND status = 'SKIPPED'")) {
                ps.setString(1, day);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Mark a running event CANCELLED (finalized=1). False if it was already finalized. */
    public CompletableFuture<Boolean> cancelEvent(LongSupplier eventId, List<ScoreRow> flush) {
        return submit(c -> {
            long id = eventId.getAsLong();
            upsertScores(c, id, flush);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE events SET status = 'CANCELLED', finalized = 1, end_ms = ? WHERE id = ? AND finalized = 0")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    // ------------------------------------------------------------------ scores / trophies

    public CompletableFuture<List<ScoreRow>> loadScores(long eventId) {
        return submit(c -> loadScores(c, eventId));
    }

    static List<ScoreRow> loadScores(Connection c, long eventId) throws SQLException {
        List<ScoreRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, name, ip, points, pirate_catches, last_increase_ms FROM scores WHERE event_id = ?")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ScoreRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getInt(5), rs.getLong(6)));
                }
            }
        }
        return out;
    }

    public CompletableFuture<Set<String>> loadTrophies(long eventId) {
        return submit(c -> {
            Set<String> out = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, tier FROM trophies WHERE event_id = ?")) {
                ps.setLong(1, eventId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1) + ":" + rs.getString(2));
                    }
                }
            }
            return out;
        });
    }

    /** Upsert score rows. The event id is read on the DB thread (after its insert has run). */
    public CompletableFuture<Void> upsertScores(LongSupplier eventId, List<ScoreRow> rows) {
        return submit(c -> {
            upsertScores(c, eventId.getAsLong(), rows);
            return null;
        });
    }

    static void upsertScores(Connection c, long eventId, List<ScoreRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        if (eventId <= 0) {
            throw new SQLException("Cannot flush scores: event has no id");
        }
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO scores(event_id, uuid, name, ip, points, pirate_catches, last_increase_ms)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(event_id, uuid) DO UPDATE SET
                  name = excluded.name, ip = COALESCE(scores.ip, excluded.ip), points = excluded.points,
                  pirate_catches = excluded.pirate_catches, last_increase_ms = excluded.last_increase_ms""")) {
            for (ScoreRow r : rows) {
                ps.setLong(1, eventId);
                ps.setString(2, r.uuid().toString());
                ps.setString(3, r.name());
                ps.setString(4, r.ip());
                ps.setInt(5, r.points());
                ps.setInt(6, r.pirateCatches());
                ps.setLong(7, r.lastIncreaseMs());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    /** Record a trophy and queue its reward row in one transaction. */
    public CompletableFuture<Void> insertTrophy(LongSupplier eventId, UUID uuid, String tier, PendingReward reward) {
        return submit(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO trophies(event_id, uuid, tier) VALUES (?,?,?)")) {
                    ps.setLong(1, eventId.getAsLong());
                    ps.setString(2, uuid.toString());
                    ps.setString(3, tier);
                    if (ps.executeUpdate() == 0) {
                        c.rollback();
                        return null; // already granted: never queue twice
                    }
                }
                insertPending(c, reward);
                c.commit();
                return null;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    // ------------------------------------------------------------------ finalize

    /**
     * Exactly-once finalize: flush the final scores, load them, ask {@code planner} for the
     * allocation (given scores and the key-cooldown set), then in ONE transaction insert the
     * key_wins row, all pending_rewards rows, and flip the event to FINISHED/finalized=1. If the
     * event was already finalized, nothing is written and null is returned.
     */
    public <T> CompletableFuture<FinalizePlan<T>> finalizeEvent(
            LongSupplier eventId, List<ScoreRow> flush, long cooldownSinceMs, long nowMs,
            BiFunction<List<ScoreRow>, Set<UUID>, FinalizePlan<T>> planner) {
        return submit(c -> finalizeEvent(c, eventId.getAsLong(), flush, cooldownSinceMs, nowMs, planner));
    }

    static <T> FinalizePlan<T> finalizeEvent(Connection c, long id, List<ScoreRow> flush, long cooldownSinceMs,
                                             long nowMs,
                                             BiFunction<List<ScoreRow>, Set<UUID>, FinalizePlan<T>> planner)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT finalized FROM events WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Event " + id + " not found");
                }
                if (rs.getInt(1) != 0) {
                    return null;
                }
            }
        }
        upsertScores(c, id, flush);
        List<ScoreRow> scores = loadScores(c, id);
        Set<UUID> cooldown = keyWinsSince(c, cooldownSinceMs);
        FinalizePlan<T> plan = planner.apply(scores, cooldown);

        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE events SET status = 'FINISHED', finalized = 1 WHERE id = ? AND finalized = 0")) {
                ps.setLong(1, id);
                if (ps.executeUpdate() == 0) {
                    c.rollback();
                    return null;
                }
            }
            if (plan.keyWinner() != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO key_wins(uuid, event_id, won_ms) VALUES (?,?,?)")) {
                    ps.setString(1, plan.keyWinner().toString());
                    ps.setLong(2, id);
                    ps.setLong(3, nowMs);
                    ps.executeUpdate();
                }
            }
            for (PendingReward r : plan.rewards()) {
                insertPending(c, r);
            }
            c.commit();
            return plan;
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    // ------------------------------------------------------------------ key wins

    static Set<UUID> keyWinsSince(Connection c, long sinceMs) throws SQLException {
        Set<UUID> out = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT uuid FROM key_wins WHERE won_ms >= ?")) {
            ps.setLong(1, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        return out;
    }

    public CompletableFuture<Integer> deleteKeyWinsSince(UUID uuid, long sinceMs) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM key_wins WHERE uuid = ? AND won_ms >= ?")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, sinceMs);
                return ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ pending rewards

    static long insertPending(Connection c, PendingReward r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pending_rewards(uuid, command, message_key, message_args, needs_slots, created_ms) "
                        + "VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.uuid().toString());
            ps.setString(2, r.command());
            ps.setString(3, r.messageKey());
            ps.setString(4, r.messageArgs());
            ps.setInt(5, r.needsSlots());
            ps.setLong(6, r.createdMs());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    public CompletableFuture<Long> insertPending(PendingReward r) {
        return submit(c -> insertPending(c, r));
    }

    /**
     * Put rows back under their ORIGINAL ids (INSERT OR IGNORE): restores a row whose delete
     * went through but whose command was never run, and is a no-op for rows still present.
     */
    public CompletableFuture<Void> restorePending(List<PendingReward> rows) {
        return submit(c -> {
            restorePending(c, rows);
            return null;
        });
    }

    static void restorePending(Connection c, List<PendingReward> rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO pending_rewards(id, uuid, command, message_key, message_args, needs_slots, "
                        + "created_ms) VALUES (?,?,?,?,?,?,?)")) {
            for (PendingReward r : rows) {
                ps.setLong(1, r.id());
                ps.setString(2, r.uuid().toString());
                ps.setString(3, r.command());
                ps.setString(4, r.messageKey());
                ps.setString(5, r.messageArgs());
                ps.setInt(6, r.needsSlots());
                ps.setLong(7, r.createdMs());
                ps.executeUpdate();
            }
        }
    }

    public CompletableFuture<List<PendingReward>> loadPending(UUID uuid) {
        return submit(c -> {
            List<PendingReward> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, uuid, command, message_key, message_args, needs_slots, created_ms "
                            + "FROM pending_rewards WHERE uuid = ? ORDER BY id")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new PendingReward(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getLong(7)));
                    }
                }
            }
            return out;
        });
    }

    /** Delete one pending row. True only for the caller that actually removed it. */
    public CompletableFuture<Boolean> deletePending(long id) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pending_rewards WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Set<UUID>> pendingUuids() {
        return submit(c -> {
            Set<UUID> out = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT uuid FROM pending_rewards");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(UUID.fromString(rs.getString(1)));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ lifecycle

    private void closeConnection() {
        if (conn != null) {
            try {
                conn.close();
                log.info("Database connection closed.");
            } catch (SQLException e) {
                log.log(Level.WARNING, "Error closing the database connection", e);
            }
            conn = null;
        }
    }

    /** Close the connection and stop the DB thread, waiting for queued work (disable only). */
    public void shutdown() {
        exec.execute(this::closeConnection);
        exec.shutdown();
        try {
            if (!exec.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warning("Database thread did not finish within 10s; forcing shutdown.");
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exec.shutdownNow();
        }
    }
}
