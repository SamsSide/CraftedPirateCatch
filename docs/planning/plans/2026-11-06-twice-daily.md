# CraftedPirateCatch — twice-daily sessions — implementation plan
> Execute with `mc-plan-execute` (self-contained; no superpowers skills). Steps use `- [ ]`.

**Goal:** let a rotation day hold two Pirate's Catch sessions (`slots: ["13:00 + 21:00"]`), opt-in, with a
safe v1→v2 SQLite migration, then hand the jar to the "Minecraft Server" session for Testing + Red SMP.
**Spec:** `docs/planning/specs/2026-11-06-twice-daily-design.md` (the spec wins where they disagree).
**Branch:** `feat/twice-daily` (base `feat/initial-build` @ 3788fef; there is **no local `main`**, so use
`3788fef` wherever mc-plan-execute says `git merge-base main HEAD`).
**Target:** MC 26.2, Java 25, Paper `[26.2.build,)` (pom unchanged apart from the version).
**Server lane:** none. The executor never touches a server. Server checks happen in the separate
"Minecraft Server" session, triggered by the GO message in the last step.

> **Sam is asleep during this run and will not answer anything.** mc-plan-execute's one start
> question is pre-answered: **Dispatch all**. If a task blocks after its caps, ledger it, finish
> whatever else can be finished, and **do not send the GO message**. Write the reason in the ledger.

## Global Constraints

- Package `net.samsside.craftedpiratecatch`; author SamsSide; version **1.1.0** (pom `<version>`).
- Storage: SQLite on the existing single DB thread (`Database.submit`); no main-thread JDBC; no new libraries.
- Schema v2 in `PRAGMA user_version`; `events.session INTEGER NOT NULL DEFAULT -1`; `-1` = pre-upgrade
  whole-day record that closes every session of its day; new rows always write session ≥ 0.
- Unique index `ux_events_day_session ON events(rotation_day, session)`; the old `ux_events_day` is dropped.
- Backup before migrating: `VACUUM INTO '<dbfile>.v1-backup-<yyyyMMdd-HHmmss>'`; a failed backup means no migration (throw).
- `schedule.slots` entries: `"HH:mm"` or `"HH:mm + HH:mm"`; at most 2 sessions per day; `config-version` stays 1.
- `fairness.key-cooldown-hours: 0` = no cooldown at all.
- Player-facing messages, broadcasts, Discord text and placeholder ids **unchanged**. Only `admin-status` and
  `admin-schedule-line` gain `<session>` (`"n/N"`, or `"-"`).
- Staff `start|skip|unskip [session]`, 1-based; usage text `/<label> admin <sub> [1-N]`.
- Permissions unchanged; plugin.yml `commands.piratecatch` gains `permission: craftedpiratecatch.use`.
- Commits: owner identity (SamsSide), conventional prefix, **no** `Co-Authored-By`/`Claude-Session`/"Generated with" lines. Do not push. No PR.
- Gates in every task: `./check.sh` and `./scripts/exploit-audit.sh`.

## Review Focus

- **Migration on the real file:** WAL contents must be in the backup (VACUUM INTO reads through the WAL),
  the DDL must be one transaction, and a v2 file must never be backed up or altered again.
- **Whole-day (-1) rows** must close both sessions everywhere: scheduler start/MISSED, warnings, staff
  commands, `next` placeholders and `admin schedule`.
- **Session ordering:** the scheduler loop `break`s on the first future start. That is only correct because sessions within a
  day are sorted by minutes after rollover; check that Rotation sorts.
- **Warnings for session 2** must not be swallowed while session 1 runs (they use `nextStart` + `isRecorded`).
- **Exactly-once finalize is unchanged.** Make sure no task touched `finalizeEvent`/`RewardDispatcher`.

## File map

| File | Task | Responsibility |
|---|---|---|
| `CLAUDE.md`, `check.sh`, `scripts/exploit-audit.sh`, `.gitignore`, `docs/planning/reference/` | 0 | mc-setup gates |
| `pom.xml` (version), `src/main/resources/plugin.yml` (command permission) | 0 | version + audit A1 |
| `schedule/SlotEntry.java` (new), `test/.../schedule/SlotEntryTest.java` (new) | 1 | parse one `slots` entry |
| `storage/Database.java`, `test/.../storage/DatabaseTest.java` | 2 | schema v2, migration, session-aware rows |
| `reward/RewardPlanner.java`, `test/.../reward/RewardPlannerTest.java` (new), `event/EventManager.java` (one line) | 3 | cooldown 0 = none |
| `schedule/Rotation.java`, `schedule/SchedulerService.java`, `event/SessionKey.java` (new), `event/ActiveEvent.java`, `event/EventManager.java`, `config/PluginConfig.java`, `placeholder/PirateCatchExpansion.java`, `command/PirateCommand.java` (compile-level), `storage/Database.java` (remove deprecated overloads), `test/.../schedule/RotationTest.java`, `test/.../storage/DatabaseTest.java` (call sites), `src/main/resources/config.yml` | 4 | sessions end to end |
| `command/SessionPicker.java` (new), `test/.../command/SessionPickerTest.java` (new), `command/PirateCommand.java`, `src/main/resources/messages.yml` | 5 | staff `[session]`, status/schedule |
| `README.md` | 6 | docs |

(Java paths are under `src/main/java/net/samsside/craftedpiratecatch/`, tests under `src/test/java/net/samsside/craftedpiratecatch/`.)

**Batches:** Task 0 alone · then **A = Tasks 1, 2, 3** (parallel; no shared files) · then Task 4 alone · then Task 5 alone · then Task 6 alone · then the final review, verification and GO message (dispatcher).

---

## Task 0: tooling, version, audit A1
**Agent:** main (dispatcher runs it; no Java)
**Files:** creates `CLAUDE.md`, `check.sh`, `scripts/exploit-audit.sh`, `docs/planning/reference/`, appends `.gitignore`; modifies `pom.xml`, `src/main/resources/plugin.yml`.
**Interfaces:** produces the two gates every later task runs.
**Values:** no `TEST_SERVER`; pom `<version>1.1.0</version>`; plugin.yml line `    permission: craftedpiratecatch.use` under `commands: piratecatch:` (4-space indent, same level as `description:`).

- [ ] Run `~/.claude/skills/mc-setup/scaffold.sh gates /home/samuel/dev/CraftedSurvival/CraftedPirateCatch` (no `TEST_SERVER` env). It prints `SKIP` for existing files and never overwrites.
- [ ] Run `./scripts/exploit-audit.sh`. Expect exactly `FAIL A1: command 'piratecatch' in src/main/resources/plugin.yml has no permission:`.
- [ ] Add the `permission:` line to plugin.yml, so the command block reads:
  ```yaml
  commands:
    piratecatch:
      description: Pirate's Catch fishing event.
      permission: craftedpiratecatch.use
      aliases: [pirate]
  ```
- [ ] Set pom `<version>1.1.0</version>` (the project version only, line 9).
- [ ] Gates: `./check.sh` green (33 existing tests) and `./scripts/exploit-audit.sh` green.
- [ ] Commit: `chore: add mc-dev gates, require craftedpiratecatch.use on the command, version 1.1.0`.

**Done when:** both gates are green and `git show --stat HEAD` lists CLAUDE.md, check.sh, scripts/exploit-audit.sh, pom.xml and plugin.yml.

---

## Task 1: parse one `slots` entry
**Agent:** mc-config
**Files:** create `src/main/java/net/samsside/craftedpiratecatch/schedule/SlotEntry.java`, `src/test/java/net/samsside/craftedpiratecatch/schedule/SlotEntryTest.java`. Touch nothing else (PluginConfig is wired in Task 4).
**Interfaces:** produces `SlotEntry.parse(String raw, LocalTime rollover, Duration duration) -> SlotEntry.Parsed`, `record Parsed(List<LocalTime> times, String problem)`, `static int minutesAfter(LocalTime t, LocalTime rollover)`.
**Values (spec §4):** split on `+`, trim; sort by `floorMod(minutes(t) - minutes(rollover), 1440)`; invalid if >2 parts, any unparsable part, equal times, or gap < `duration` minutes (gap == duration is valid); an invalid entry → `times` = [first parseable part as written], `problem` = reason; nothing parseable → `times` empty.

- [ ] Write the failing test `SlotEntryTest` (real code):
  ```java
  package net.samsside.craftedpiratecatch.schedule;

  import static org.junit.jupiter.api.Assertions.assertEquals;
  import static org.junit.jupiter.api.Assertions.assertNotNull;
  import static org.junit.jupiter.api.Assertions.assertNull;
  import static org.junit.jupiter.api.Assertions.assertTrue;

  import java.time.Duration;
  import java.time.LocalTime;
  import java.util.List;
  import org.junit.jupiter.api.Test;

  class SlotEntryTest {
      private static final LocalTime RO = LocalTime.of(6, 0);
      private static final Duration D15 = Duration.ofMinutes(15);

      private static SlotEntry.Parsed p(String raw) {
          return SlotEntry.parse(raw, RO, D15);
      }

      @Test void single() {
          assertEquals(List.of(LocalTime.of(13, 0)), p("13:00").times());
          assertNull(p("13:00").problem());
      }
      @Test void pair() {
          assertEquals(List.of(LocalTime.of(13, 0), LocalTime.of(21, 0)), p("13:00 + 21:00").times());
          assertEquals(List.of(LocalTime.of(13, 0), LocalTime.of(21, 0)), p(" 21:00+13:00 ").times());
          assertNull(p("13:00+21:00").problem());
      }
      @Test void lateNightSortsLast() {
          assertEquals(List.of(LocalTime.of(21, 0), LocalTime.of(1, 0)), p("01:00 + 21:00").times());
      }
      @Test void gapEqualToDurationIsValid() {
          assertNull(p("13:00 + 13:15").problem());
      }
      @Test void overlapFallsBackToFirst() {
          SlotEntry.Parsed r = p("13:10 + 13:00");
          assertEquals(List.of(LocalTime.of(13, 10)), r.times());
          assertNotNull(r.problem());
      }
      @Test void equalTimesInvalid() {
          assertEquals(List.of(LocalTime.of(13, 0)), p("13:00 + 13:00").times());
          assertNotNull(p("13:00 + 13:00").problem());
      }
      @Test void threeTimesInvalid() {
          assertEquals(List.of(LocalTime.of(13, 0)), p("13:00 + 17:00 + 21:00").times());
          assertNotNull(p("13:00 + 17:00 + 21:00").problem());
      }
      @Test void badPartFallsBackToFirstParseable() {
          assertEquals(List.of(LocalTime.of(13, 0)), p("25:00 + 13:00").times());
          assertNotNull(p("25:00 + 13:00").problem());
      }
      @Test void unparsableDropped() {
          assertTrue(p("abc").times().isEmpty());
          assertTrue(p("").times().isEmpty());
          assertTrue(p("+").times().isEmpty());
      }
      @Test void minutesAfterRollover() {
          assertEquals(0, SlotEntry.minutesAfter(LocalTime.of(6, 0), RO));
          assertEquals(19 * 60, SlotEntry.minutesAfter(LocalTime.of(1, 0), RO));
      }
  }
  ```
- [ ] Run `./check.sh -Dtest=SlotEntryTest`. Expect a compilation failure: `cannot find symbol ... SlotEntry`.
- [ ] Implement (real code):
  ```java
  package net.samsside.craftedpiratecatch.schedule;

  import java.time.Duration;
  import java.time.LocalTime;
  import java.time.format.DateTimeParseException;
  import java.util.ArrayList;
  import java.util.Comparator;
  import java.util.List;

  /** Pure parser for one {@code schedule.slots} entry: "HH:mm" or "HH:mm + HH:mm". */
  public final class SlotEntry {

      /** {@code times} in session order (empty = drop the entry); {@code problem} non-null = entry was invalid. */
      public record Parsed(List<LocalTime> times, String problem) {
      }

      private SlotEntry() {
      }

      /** Minutes from {@code rollover} forward to {@code t}, 0..1439 (late-night times sort last). */
      public static int minutesAfter(LocalTime t, LocalTime rollover) {
          return Math.floorMod(t.getHour() * 60 + t.getMinute() - (rollover.getHour() * 60 + rollover.getMinute()), 1440);
      }

      public static Parsed parse(String raw, LocalTime rollover, Duration duration) {
          String[] parts = (raw == null ? "" : raw).split("\\+", -1);
          List<LocalTime> parsed = new ArrayList<>();
          String bad = null;
          for (String part : parts) {
              String s = part.trim();
              try {
                  parsed.add(LocalTime.parse(s));
              } catch (DateTimeParseException e) {
                  if (bad == null) {
                      bad = s;
                  }
              }
          }
          if (parsed.isEmpty()) {
              return new Parsed(List.of(), "no valid HH:mm time");
          }
          List<LocalTime> first = List.of(parsed.get(0));
          if (parts.length > 2) {
              return new Parsed(first, "more than 2 times");
          }
          if (bad != null) {
              return new Parsed(first, "bad time '" + bad + "'");
          }
          if (parsed.size() == 1) {
              return new Parsed(first, null);
          }
          List<LocalTime> sorted = new ArrayList<>(parsed);
          sorted.sort(Comparator.comparingInt(t -> minutesAfter(t, rollover)));
          int gap = minutesAfter(sorted.get(1), rollover) - minutesAfter(sorted.get(0), rollover);
          if (gap == 0) {
              return new Parsed(first, "the same time twice");
          }
          if (gap < duration.toMinutes()) {
              return new Parsed(first, "sessions overlap: " + gap + " min apart, duration " + duration.toMinutes() + " min");
          }
          return new Parsed(List.copyOf(sorted), null);
      }
  }
  ```
- [ ] Run `./check.sh -Dtest=SlotEntryTest`. Expect pass (10 tests).
- [ ] Gates: `./check.sh` and `./scripts/exploit-audit.sh` green.
- [ ] Commit: `feat: parse "HH:mm + HH:mm" schedule entries into one or two sessions`.

**Done when:** `./check.sh -Dtest=SlotEntryTest` reports 10 passing tests.

---

## Task 2: schema v2 and the v1 migration
**Agent:** mc-storage (model: Opus, because it migrates live production data)
**Files:** modify `src/main/java/net/samsside/craftedpiratecatch/storage/Database.java`, `src/test/java/net/samsside/craftedpiratecatch/storage/DatabaseTest.java`. Touch nothing else.
**Interfaces:** produces
- `public static final int SCHEMA_VERSION = 2;` and `public static final int WHOLE_DAY = -1;`
- `EventRow(long id, String rotationDay, String slot, long startMs, long endMs, String status, String startedBy, boolean finalized, int session)` (**session appended last**)
- `CompletableFuture<Long> insertEvent(String day, int session, String slot, long startMs, long endMs, String status, String startedBy)` + `static long insertEvent(Connection, String day, int session, ...)`
- `CompletableFuture<Boolean> deleteSkipped(String day, int session)`
- kept **temporarily**, marked `@Deprecated(forRemoval = true) // removed in Task 4`: the old `insertEvent(String day, String slot, ...)` (both the public and the static one), delegating with `session = 0`, and `deleteSkipped(String day)`, delegating with `0`. EventManager keeps compiling unchanged.
- `static void migrate(Connection c, File dbFile, Logger log) throws SQLException`

**Values (spec §6):** backup name `<dbfile name>.v1-backup-<yyyyMMdd-HHmmss>` (local time, `DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")`, `LocalDateTime.now()`), in the DB file's folder; log lines verbatim:
`Backed up <name> to <backup name> before the schema v2 migration.` and `Database migrated to schema v2 (<n> event record(s) kept as whole-day records).`; newer-file error `<name> schema v<N> is newer than this plugin supports (v2)`.

- [ ] Write the failing tests in `DatabaseTest`. Add these imports: `java.sql.Connection`, `java.sql.DriverManager`, `java.sql.ResultSet`, `java.sql.Statement`, `java.io.File` (already present). Add a helper that builds a **v1 file** with the exact old DDL:
  ```java
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
      return dir.toFile().listFiles((d, n) -> n.startsWith("old.db.v1-backup-"));
  }
  ```
  Add `private static final UUID B = UUID.randomUUID();` next to `A`. Tests (each opens its own `Database` on `old.db` and shuts it down in a `finally`):
  - `migratesV1KeepingAllData`: `v1File("old.db")`, open. Assert `loadEvents()` has 2 rows, ids 1 and 2, statuses FINISHED/RUNNING, `finalized` true/false, **both `session() == Database.WHOLE_DAY`**. `loadScores(1)` has 2 rows (Alice 12 points). `loadTrophies(1)` contains `A + ":rare"`. `loadPending(B)` ids `[5, 9]`. `userVersion(old.db) == 2`. Exactly one backup file exists, its `userVersion` is 0, and a raw `SELECT COUNT(*) FROM events` on it returns 2.
  - `migrationIsIdempotent`: migrate as above, shut down, open a new `Database` on the same file. Still exactly 1 backup, still 2 events.
  - `freshFileIsV2`: the default `@BeforeEach` db. `db.submit(c -> { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) { return r.getInt(1); } }).get() == 2`. No `data.db.v1-backup-*` file exists.
  - `refusesNewerSchema`: create `new.db` raw with `PRAGMA user_version = 3`. Opening it throws `ExecutionException`, and `userVersion(new.db)` is still 3.
  - `twoSessionsPerDay`: on the default db, `insertEvent("2026-11-07", 0, "13:00", 0, 1, RUNNING, "SCHEDULER")` and `insertEvent("2026-11-07", 1, "21:00", ...)` both succeed. A second `(…"2026-11-07", 0, …)` throws `ExecutionException`. `deleteSkipped("2026-11-07", 1)` is false (the row is not SKIPPED). After inserting `("2026-11-08", 1, …, SKIPPED, …)`, `deleteSkipped("2026-11-08", 0)` is false and `deleteSkipped("2026-11-08", 1)` is true.
  - Change the existing tests' `insertEvent("…", "13:00", …)` calls to the new `insertEvent("…", 0, "13:00", …)` form. The existing duplicate-day test keeps asserting that a second `(day, 0)` fails.
- [ ] Run `./check.sh -Dtest=DatabaseTest`. Expect compile errors (`insertEvent(String,int,…)` and `WHOLE_DAY` not found).
- [ ] Implement in `Database`:
  - `open(...)`: after the three PRAGMAs, call `migrate(c, dbFile, log);` then `createSchema(c);`.
  - `createSchema`: `events` DDL gains `,\n session INTEGER NOT NULL DEFAULT -1` as the last column. Replace the `ux_events_day` statement with `CREATE UNIQUE INDEX IF NOT EXISTS ux_events_day_session ON events(rotation_day, session)`. At the end, if `userVersion(c) == 0`, execute `PRAGMA user_version = 2`. Other tables are unchanged.
  - `migrate` (real code):
    ```java
    static void migrate(Connection c, File dbFile, Logger log) throws SQLException {
        int version = userVersion(c);
        if (version > SCHEMA_VERSION) {
            throw new SQLException(dbFile.getName() + " schema v" + version
                    + " is newer than this plugin supports (v" + SCHEMA_VERSION + ")");
        }
        if (!tableExists(c, "events") || hasColumn(c, "events", "session")) {
            return; // fresh file (createSchema builds v2) or already v2
        }
        File backup = new File(dbFile.getAbsoluteFile().getParentFile(),
                dbFile.getName() + ".v1-backup-" + BACKUP_STAMP.format(LocalDateTime.now()));
        if (backup.exists()) {
            throw new SQLException("Backup target " + backup.getName() + " already exists - not migrating");
        }
        try (PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
            ps.setString(1, backup.getAbsolutePath());
            ps.execute();
        }
        log.info("Backed up " + dbFile.getName() + " to " + backup.getName() + " before the schema v2 migration.");
        int rows;
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE events ADD COLUMN session INTEGER NOT NULL DEFAULT -1");
            st.execute("DROP INDEX IF EXISTS ux_events_day");
            st.execute("CREATE UNIQUE INDEX ux_events_day_session ON events(rotation_day, session)");
            st.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM events")) {
                rs.next();
                rows = rs.getInt(1);
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
        log.info("Database migrated to schema v2 (" + rows + " event record(s) kept as whole-day records).");
    }
    ```
    The helpers `userVersion(Connection)` (`PRAGMA user_version`), `tableExists(Connection, String)` (`SELECT 1 FROM sqlite_master WHERE type='table' AND name=?`) and `hasColumn(Connection, String table, String column)` (iterate `PRAGMA table_info(<table>)`, column `name`) are all `static` and package-private. Add `private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");`. If the driver rejects `VACUUM INTO ?`, use `st.execute("VACUUM INTO '" + path.replace("'", "''") + "'")` instead and say so in the report.
  - `loadEvents`: select `..., finalized, session` and pass `rs.getInt(9)` as the last `EventRow` argument.
  - `insertEvent(... int session ...)`: column list `rotation_day, session, slot, start_ms, end_ms, status, started_by, finalized` with 8 placeholders. The `finalized` rule is unchanged.
  - `deleteSkipped(String day, int session)`: `DELETE FROM events WHERE rotation_day = ? AND session = ? AND status = 'SKIPPED'`.
  - The deprecated overloads delegate as described under Interfaces.
- [ ] Run `./check.sh -Dtest=DatabaseTest`. Expect all to pass.
- [ ] Gates: `./check.sh` and `./scripts/exploit-audit.sh` green.
- [ ] Commit: `feat: schema v2 with a session per event, backed-up in-place migration from v1`.

**Done when:** `./check.sh -Dtest=DatabaseTest` passes, including `migratesV1KeepingAllData`.

---

## Task 3: key cooldown 0 means none
**Agent:** mc-security
**Files:** modify `src/main/java/net/samsside/craftedpiratecatch/reward/RewardPlanner.java`, `src/main/java/net/samsside/craftedpiratecatch/event/EventManager.java` (**only** the `cooldownSince` line in `finalizeLive`); create `src/test/java/net/samsside/craftedpiratecatch/reward/RewardPlannerTest.java`.
**Interfaces:** produces `public static long RewardPlanner.cooldownSince(int hours, long nowMs)`.
**Values:** `hours <= 0` → `Long.MAX_VALUE` (so `keyWinsSince` matches nothing, nobody is on cooldown, and `COOLDOWN_NOTE` cannot be produced); otherwise `nowMs - hours * 3_600_000L`.

- [ ] Write the failing test:
  ```java
  package net.samsside.craftedpiratecatch.reward;

  import static org.junit.jupiter.api.Assertions.assertEquals;

  import org.junit.jupiter.api.Test;

  class RewardPlannerTest {
      @Test void zeroCooldownMeansNone() {
          assertEquals(Long.MAX_VALUE, RewardPlanner.cooldownSince(0, 1_000L));
          assertEquals(Long.MAX_VALUE, RewardPlanner.cooldownSince(-5, 1_000L));
      }
      @Test void positiveCooldownLooksBack() {
          long now = 1_000_000_000_000L;
          assertEquals(now - 72 * 3_600_000L, RewardPlanner.cooldownSince(72, now));
      }
  }
  ```
- [ ] Run `./check.sh -Dtest=RewardPlannerTest`. Expect `cannot find symbol: method cooldownSince`.
- [ ] Implement `cooldownSince` in `RewardPlanner` with a one-line Javadoc. In `EventManager.finalizeLive`, replace
  `long cooldownSince = now - cfg.keyCooldownHours * 3_600_000L;` with
  `long cooldownSince = RewardPlanner.cooldownSince(cfg.keyCooldownHours, now);`.
  Change nothing else (`admin resetcooldown` keeps its own arithmetic; with 0 it deletes 0 rows).
- [ ] Run `./check.sh -Dtest=RewardPlannerTest`. Expect pass.
- [ ] Gates: `./check.sh` and `./scripts/exploit-audit.sh` green.
- [ ] Commit: `fix: key-cooldown-hours 0 disables the key cooldown entirely`.

**Done when:** `RewardPlannerTest` passes and `git diff HEAD~1 -- src/main/java/net/samsside/craftedpiratecatch/event/EventManager.java` is that one line.

---

## Task 4: sessions end to end (rotation, scheduler, state, config)
**Agent:** mc-storage (model: Opus, as the core of the change)
**Files:** modify `schedule/Rotation.java`, `schedule/SchedulerService.java`, `event/ActiveEvent.java`, `event/EventManager.java`, `config/PluginConfig.java`, `placeholder/PirateCatchExpansion.java`, `command/PirateCommand.java` (compile-level only, see below), `storage/Database.java` (delete the deprecated overloads), `src/main/resources/config.yml` (comment only); create `event/SessionKey.java`; modify tests `schedule/RotationTest.java`, `storage/DatabaseTest.java` (only if a deprecated call remains).
**Interfaces:** consumes `SlotEntry.parse/minutesAfter` (Task 1); `Database.WHOLE_DAY`, `EventRow.session()`, `insertEvent(day, session, …)`, `deleteSkipped(day, session)` (Task 2); `RewardPlanner.cooldownSince` (Task 3, leave as is). Produces:
- `Rotation(ZoneId zone, List<List<LocalTime>> slots, LocalTime rollover, LocalDate firstDay, LocalDate lastDay, Duration duration)`
- `record Rotation.Slot(LocalDate day, int session, int sessions, LocalTime time, Instant start, Instant end)` with `String label()` → `(session + 1) + "/" + sessions`
- `List<LocalTime> slotTimes(LocalDate)`, `int sessionsOn(LocalDate)`, `Slot slot(LocalDate, int session)`, `List<Slot> slots(LocalDate)`,
  `Slot nextScheduled(Instant, Predicate<Slot>)`, `Slot nextStart(Instant, Predicate<Slot>)`, `List<Slot> upcoming(Instant, int, Predicate<Slot>)`, `LocalDate todayAt(Instant)` (unchanged). **Removed:** `slotTime(LocalDate)`, `slot(LocalDate)`.
- `public record SessionKey(LocalDate day, int session)` in `event`.
- `ActiveEvent(boolean test, LocalDate day, int session, String slot, long startMs, long endMs)`, with field `public final int session` (TEST = -1).
- EventManager: `String status(Rotation.Slot)`, `String status(LocalDate day, int session)`, `boolean isRecorded(Rotation.Slot)`, `boolean isClosed(Rotation.Slot)`, `boolean isWholeDay(LocalDate)`, `void unskip(LocalDate day, int session, Consumer<Boolean> done)`. **Removed:** `dayStatus(LocalDate)`, `isClosed(LocalDate)`, `hasRow(LocalDate)`, `unskip(LocalDate, Consumer)`.

**Values:** spec §4 log lines verbatim: `Unparsable schedule slot '<raw>' dropped (expected HH:mm or HH:mm + HH:mm).` (WARNING) and `Schedule slot entry '<raw>' is invalid (<problem>) - using only '<HH:mm>'.` (SEVERE). Warning dedupe key `day + "#" + session + ":" + minutes`.

- [ ] **RotationTest first.** Migrate the existing 10 tests mechanically: `SLOTS` becomes `List.of(List.of(LocalTime.of(13, 0)), List.of(LocalTime.of(17, 0)), List.of(LocalTime.of(21, 0)), List.of(LocalTime.of(1, 0)))`; `slotTime(d)` → `slotTimes(d).get(0)` (or `.isEmpty()` where null was expected); `slot(d)` → `slot(d, 0)`; `d -> false` → `s -> false`; any closed-day predicate `x::contains` on dates → `s -> set.contains(s.day())`. The assertions keep their meaning. Then add:
  ```java
  private final Rotation pair = new Rotation(LONDON, List.of(List.of(LocalTime.of(13, 0), LocalTime.of(21, 0))),
          LocalTime.of(6, 0), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30), Duration.ofMinutes(15));

  @Test void fixedPairEveryDay() {
      LocalDate d = LocalDate.of(2026, 11, 7);
      List<Rotation.Slot> s = pair.slots(d);
      assertEquals(2, s.size());
      assertEquals(london(2026, 11, 7, 13, 0), s.get(0).start());
      assertEquals(london(2026, 11, 7, 13, 15), s.get(0).end());
      assertEquals(london(2026, 11, 7, 21, 0), s.get(1).start());
      assertEquals("1/2", s.get(0).label());
      assertEquals("2/2", s.get(1).label());
      // under way: nextScheduled returns it, nextStart skips to the 21:00 session
      assertEquals(0, pair.nextScheduled(london(2026, 11, 7, 13, 5), x -> false).session());
      assertEquals(london(2026, 11, 7, 21, 0), pair.nextStart(london(2026, 11, 7, 13, 5), x -> false).start());
      // after the second session: next day's first
      Rotation.Slot n = pair.nextStart(london(2026, 11, 7, 21, 20), x -> false);
      assertEquals(LocalDate.of(2026, 11, 8), n.day());
      assertEquals(0, n.session());
      List<Rotation.Slot> up = pair.upcoming(london(2026, 11, 7, 12, 0), 3, x -> false);
      assertEquals(List.of("2026-11-07#0", "2026-11-07#1", "2026-11-08#0"),
              up.stream().map(x -> x.day() + "#" + x.session()).toList());
  }

  @Test void pairWithLateNightOrdersByRollover() {
      Rotation r = new Rotation(LONDON, List.of(List.of(LocalTime.of(1, 0), LocalTime.of(21, 0))),
              LocalTime.of(6, 0), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30), Duration.ofMinutes(15));
      assertEquals(LocalTime.of(21, 0), r.slot(LocalDate.of(2026, 11, 7), 0).time());
      assertEquals(london(2026, 11, 8, 1, 0), r.slot(LocalDate.of(2026, 11, 7), 1).start());
  }

  @Test void closedSessionSkipsOnlyThatSession() {
      Rotation.Slot s = pair.nextScheduled(london(2026, 11, 7, 12, 0),
              x -> x.day().equals(LocalDate.of(2026, 11, 7)) && x.session() == 0);
      assertEquals(1, s.session());
  }

  @Test void outOfRangeSessionIsNull() {
      assertNull(pair.slot(LocalDate.of(2026, 11, 7), 2));
      assertNull(pair.slot(LocalDate.of(2026, 12, 1), 0));
      assertEquals(0, pair.sessionsOn(LocalDate.of(2026, 12, 1)));
  }

  @Test void rejectsBadEntries() {
      assertThrows(IllegalArgumentException.class, () -> new Rotation(LONDON, List.of(List.of()),
              LocalTime.of(6, 0), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30), Duration.ofMinutes(15)));
      assertThrows(IllegalArgumentException.class, () -> new Rotation(LONDON,
              List.of(List.of(LocalTime.of(1, 0), LocalTime.of(2, 0), LocalTime.of(3, 0))),
              LocalTime.of(6, 0), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30), Duration.ofMinutes(15)));
  }
  ```
- [ ] Run `./check.sh -Dtest=RotationTest`. Expect compile errors (no `slots(LocalDate)`, wrong constructor).
- [ ] **Rotation.** Constructor: throw `IllegalArgumentException("slots must not be empty")` on an empty outer list, `IllegalArgumentException("each slot entry needs 1 or 2 times")` on an inner list of size 0 or more than 2, and keep the `first-day is after last-day` check. Store each inner list sorted by `Comparator.comparingInt(t -> SlotEntry.minutesAfter(t, rollover))`, as `List.copyOf`. Then:
  ```java
  public List<LocalTime> slotTimes(LocalDate d) {
      if (!isRotationDay(d)) return List.of();
      long i = ChronoUnit.DAYS.between(firstDay, d);
      return slots.get((int) Math.floorMod(i, (long) slots.size()));
  }
  public int sessionsOn(LocalDate d) { return slotTimes(d).size(); }
  public Slot slot(LocalDate d, int session) {
      List<LocalTime> times = slotTimes(d);
      if (session < 0 || session >= times.size()) return null;
      LocalTime t = times.get(session);
      LocalDate date = t.isBefore(rollover) ? d.plusDays(1) : d;
      Instant start = resolve(date.atTime(t));
      return new Slot(d, session, times.size(), t, start, start.plus(duration));
  }
  public List<Slot> slots(LocalDate d) {
      int n = sessionsOn(d);
      List<Slot> out = new ArrayList<>(n);
      for (int i = 0; i < n; i++) out.add(slot(d, i));
      return out;
  }
  ```
  `nextScheduled`, `nextStart` and `upcoming` keep their bodies, but the inner loop is `for (Slot s : slots(d))` and the predicate is `Predicate<Slot>`. Update the class Javadoc to say that a rotation day has one or two sessions.
- [ ] **SessionKey** (new): `public record SessionKey(LocalDate day, int session) { }` in `event`.
- [ ] **ActiveEvent:** add `public final int session;` and the new constructor parameter after `day`. Javadoc: `0-based session; -1 for TEST or a pre-upgrade whole-day record`.
- [ ] **EventManager** (main thread unless noted):
  - Replace `days` with `private final Map<SessionKey, String> sessions = new ConcurrentHashMap<>();` and `private final Map<LocalDate, String> wholeDays = new ConcurrentHashMap<>();`. A private `void remember(LocalDate day, int session, String status)` puts into `wholeDays` when `session == Database.WHOLE_DAY` and into `sessions` otherwise. `recover()` and `reloadDays()` clear both maps and call `remember(d, r.session(), r.status())` for each row.
  - Queries: `status(LocalDate day, int session)` returns `wholeDays.get(day)` if present, else `sessions.get(new SessionKey(day, session))`. `status(Rotation.Slot s)` delegates. `isRecorded(s)` is `status(s) != null`. `isClosed(s)` is `status != null && Database.CLOSED.contains(status)`. `isWholeDay(d)` is `wholeDays.containsKey(d)`.
  - `recover()`: `new ActiveEvent(false, d, r.session(), r.slot(), r.startMs(), r.endMs())`. Log text: `"rotation day " + d + sessionText(r.session())`, where `private static String sessionText(int s)` returns `""` for -1 and `" session " + (s + 1)` otherwise.
  - `startLive(slot, …)`: `new ActiveEvent(false, slot.day(), slot.session(), slotText, now, endMs)`; `remember(slot.day(), slot.session(), Database.RUNNING)`; `insertEvent(slot.day().toString(), slot.session(), slotText, now, endMs, Database.RUNNING, startedBy)`. Log: `"Pirate's Catch started for rotation day " + slot.day() + " session " + slot.label() + " (slot " + …` (the rest unchanged).
  - `startTest`: `new ActiveEvent(true, null, -1, "-", …)`.
  - `cancel()`: `remember(ev.day, ev.session, Database.CANCELLED)`. `finalizeLive` success: `remember(ev.day, ev.session, Database.FINISHED)`.
  - `skip(slot, staff)`: `remember(slot.day(), slot.session(), SKIPPED)`; `insertEvent(day, slot.session(), …)`; log `"Rotation day " + slot.day() + " session " + slot.label() + " SKIPPED by " + staffName + "."`.
  - `unskip(LocalDate day, int session, Consumer<Boolean> done)`: `deleteSkipped(day.toString(), session)`; on removal `sessions.remove(new SessionKey(day, session))`.
  - `markMissed(slot)`: `remember(…, MISSED)`; `insertEvent(day, slot.session(), …)`; the warning text gains `" session " + slot.label()`.
  - Do not touch `finalizeEvent`, scoring, trophies, the boss bar or flushing. Keep Task 3's `RewardPlanner.cooldownSince` line.
- [ ] **SchedulerService.tick()** (real code for the loop and warnings):
  ```java
  days:
  for (LocalDate d = rotation.firstDay(); !d.isAfter(rotation.lastDay()); d = d.plusDays(1)) {
      for (Rotation.Slot slot : rotation.slots(d)) {
          if (slot.start().isAfter(nowI)) {
              break days; // sessions are in start order; later ones start later still
          }
          if (events.isRecorded(slot)) {
              continue;
          }
          if (!nowI.isBefore(slot.end())) {
              events.markMissed(slot);
          } else {
              ActiveEvent current = events.active();
              if (current == null || current.test) {
                  events.startScheduled(slot); // late start keeps the original end time
              }
          }
      }
  }

  // Warn for the next session that has not started and has no record (never the running one).
  Rotation.Slot next = rotation.nextStart(nowI, events::isRecorded);
  if (next != null) {
      for (int minutes : cfg.warningsMinutes) {
          String key = next.day() + "#" + next.session() + ":" + minutes;
          // …the existing warned / warnAt / LATE_WARNING_GRACE_MS body, unchanged
      }
  }
  ```
  Update the `warned` field comment to `rotation day + "#" + session + ":" + minutes`.
- [ ] **PluginConfig:** move the `day-rollover` and `duration-minutes` parsing **above** the slots parsing (the same code and messages), then:
  ```java
  List<List<LocalTime>> slots = new ArrayList<>();
  for (String raw : c.getStringList("schedule.slots")) {
      SlotEntry.Parsed p = SlotEntry.parse(raw, rollover, Duration.ofMinutes(duration));
      if (p.times().isEmpty()) {
          log.warning("Unparsable schedule slot '" + raw + "' dropped (expected HH:mm or HH:mm + HH:mm).");
          continue;
      }
      if (p.problem() != null) {
          log.severe("Schedule slot entry '" + raw + "' is invalid (" + p.problem() + ") - using only '"
                  + p.times().get(0) + "'.");
      }
      slots.add(p.times());
  }
  ```
  (`LocalTime.toString()` prints `HH:mm` for whole minutes.) Pass `slots` to the new `Rotation` constructor. Everything else is unchanged.
- [ ] **PirateCatchExpansion:** no source change should be needed (`plugin.events()::isClosed` now binds to `isClosed(Rotation.Slot)`). Confirm it compiles.
- [ ] **PirateCommand (compile-level only; Task 5 rewrites these):** `today(sender)` returns `rotation.slot(today, 0)`. `adminStart` uses `events.isRecorded(slot)` instead of `hasRow(slot.day())`. `adminSkip`/`adminUnskip` use `events.status(slot)` instead of `dayStatus(slot.day())`, and unskip calls `events.unskip(slot.day(), slot.session(), …)`. `info`, `adminStatus` and `adminSchedule` compile unchanged.
- [ ] **Database:** delete the three `@Deprecated(forRemoval = true)` overloads from Task 2. Fix any remaining callers (tests included) to pass a session.
- [ ] **config.yml:** replace the two comment lines above `slots:` with exactly:
  ```yaml
    # Each entry is ONE rotation day; rotation day N uses slots[(N - first-day) mod size].
    # "HH:mm" = one event that day; "HH:mm + HH:mm" = two events that day (24h).
    # Same pair every day:  slots: ["13:00 + 21:00"]
  ```
  The value and every other key stay as they are; `config-version: 1` stays.
- [ ] Run `./check.sh -Dtest=RotationTest`. Expect pass (10 migrated + 5 new). Then the whole `./check.sh`.
- [ ] Gates: `./check.sh` and `./scripts/exploit-audit.sh` green.
- [ ] Commit: `feat: run one or two Pirate's Catch sessions per rotation day`.

**Done when:** `./check.sh` is green, `grep -rn "hasRow\|dayStatus\|slotTime(" src/main` returns nothing, and `grep -n "Deprecated" src/main/java/net/samsside/craftedpiratecatch/storage/Database.java` returns nothing.

---

## Task 5: staff `[session]` argument, session in status/schedule
**Agent:** mc-commands
**Files:** create `command/SessionPicker.java`, `src/test/java/net/samsside/craftedpiratecatch/command/SessionPickerTest.java`; modify `command/PirateCommand.java`, `src/main/resources/messages.yml`.
**Interfaces:** consumes `Rotation.slots(LocalDate)`, `Rotation.sessionsOn(LocalDate)`, `Rotation.Slot.label()`, `Rotation.upcoming(...)`, `EventManager.status(Rotation.Slot)`, `EventManager.isRecorded(Rotation.Slot)`, `EventManager.isWholeDay(LocalDate)`, `EventManager.isClosed(Rotation.Slot)`, `EventManager.unskip(LocalDate, int, Consumer<Boolean>)`, `ActiveEvent.session`, `Database.SKIPPED` (Task 4). Produces `SessionPicker.start/skip/unskip`.
**Values:** permissions unchanged (start → `craftedpiratecatch.admin.start`; skip/unskip → `craftedpiratecatch.admin.skip`; status/schedule → `craftedpiratecatch.admin.status`). Usage: `base + " admin " + sub + " [1-" + n + "]"`, where `n` = today's session count (minimum 1). Message keys: `admin-started`, `admin-already-ran`, `admin-skipped`, `admin-unskipped`, `admin-status`, `admin-schedule-line`, `info-disabled`, `info-ended`, `usage`, `no-permission`. `<session>` = `Slot.label()`; for a running event, `ev.session < 0 ? "-" : (ev.session + 1) + "/" + Math.max(ev.session + 1, rotation == null ? 0 : rotation.sessionsOn(ev.day))`; with nothing running or upcoming, `"-"`.

- [ ] Write the failing test `SessionPickerTest`. Build the slots from `new Rotation(ZoneId.of("Europe/London"), List.of(List.of(LocalTime.of(13, 0), LocalTime.of(21, 0))), LocalTime.of(6, 0), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30), Duration.ofMinutes(15)).slots(LocalDate.of(2026, 11, 7))`, a `Map<Integer, String>` status per session (`s -> map.get(s.session())`) and a `london(h, m)` helper for 7 Nov. Cases (each its own `@Test`):
  - `startNoArg`: at 12:00 with nothing recorded → OK #0. At 14:00 with #0 FINISHED → OK #1. At 14:00 with nothing recorded (#0's window has ended) → OK #1. Both recorded → ALREADY_RAN.
  - `startWithArg`: `2` → OK #1. `1` with #0 FINISHED → ALREADY_RAN. `0` and `3` → BAD_ARG.
  - `skipNoArg`: 12:00 → OK #0. 12:00 with #0 SKIPPED → OK #1. 12:00 with both SKIPPED → ALREADY_SKIPPED. 21:05 with #0 FINISHED and #1 RUNNING → ALREADY_RAN.
  - `skipWithArg`: `2` at 12:00 → OK #1. `1` at 13:05 → ALREADY_RAN. `2` with #1 SKIPPED → ALREADY_SKIPPED. `3` → BAD_ARG.
  - `unskipNoArg`: 14:00 with #0 FINISHED and #1 SKIPPED → OK #1. 12:00 with nothing recorded → ALREADY_UNSKIPPED. 12:00 with whole-day SKIPPED (status `SKIPPED` for both, wholeDay → true) → ALREADY_RAN.
  - `unskipWithArg`: `2` with #1 SKIPPED at 14:00 → OK #1. `2` with nothing recorded at 14:00 → ALREADY_UNSKIPPED. `1` with nothing recorded at 13:05 → ALREADY_RAN. `5` → BAD_ARG.
- [ ] Run `./check.sh -Dtest=SessionPickerTest`. Expect `cannot find symbol: class SessionPicker`.
- [ ] Implement (real code):
  ```java
  package net.samsside.craftedpiratecatch.command;

  import java.time.Instant;
  import java.util.List;
  import java.util.function.Function;
  import java.util.function.Predicate;
  import net.samsside.craftedpiratecatch.schedule.Rotation;
  import net.samsside.craftedpiratecatch.storage.Database;

  /** Pure choice of which of today's sessions a staff start/skip/unskip acts on. */
  public final class SessionPicker {

      public enum Outcome { OK, ALREADY_RAN, ALREADY_SKIPPED, ALREADY_UNSKIPPED, BAD_ARG }

      public record Pick(Outcome outcome, Rotation.Slot slot) {
          static Pick ok(Rotation.Slot s) { return new Pick(Outcome.OK, s); }
          static Pick of(Outcome o) { return new Pick(o, null); }
      }

      private SessionPicker() {
      }

      private static Rotation.Slot chosen(List<Rotation.Slot> today, int requested) {
          return requested >= 1 && requested <= today.size() ? today.get(requested - 1) : null;
      }

      /** {@code requested}: 1-based, or null to pick. {@code status}: null = no record. */
      public static Pick start(List<Rotation.Slot> today, Integer requested, Instant now,
                               Function<Rotation.Slot, String> status) {
          if (requested != null) {
              Rotation.Slot s = chosen(today, requested);
              if (s == null) return Pick.of(Outcome.BAD_ARG);
              return status.apply(s) == null ? Pick.ok(s) : Pick.of(Outcome.ALREADY_RAN);
          }
          for (Rotation.Slot s : today) {
              if (status.apply(s) == null && s.end().isAfter(now)) return Pick.ok(s);
          }
          return Pick.of(Outcome.ALREADY_RAN);
      }

      public static Pick skip(List<Rotation.Slot> today, Integer requested, Instant now,
                              Function<Rotation.Slot, String> status) {
          if (requested != null) {
              Rotation.Slot s = chosen(today, requested);
              if (s == null) return Pick.of(Outcome.BAD_ARG);
              String st = status.apply(s);
              if (Database.SKIPPED.equals(st)) return Pick.of(Outcome.ALREADY_SKIPPED);
              if (st != null || !now.isBefore(s.start())) return Pick.of(Outcome.ALREADY_RAN);
              return Pick.ok(s);
          }
          boolean anySkipped = false;
          for (Rotation.Slot s : today) {
              if (!now.isBefore(s.start())) continue;
              String st = status.apply(s);
              if (st == null) return Pick.ok(s);
              if (Database.SKIPPED.equals(st)) anySkipped = true;
          }
          return Pick.of(anySkipped ? Outcome.ALREADY_SKIPPED : Outcome.ALREADY_RAN);
      }

      public static Pick unskip(List<Rotation.Slot> today, Integer requested, Instant now,
                                Function<Rotation.Slot, String> status, Predicate<Rotation.Slot> wholeDay) {
          if (requested != null) {
              Rotation.Slot s = chosen(today, requested);
              if (s == null) return Pick.of(Outcome.BAD_ARG);
              String st = status.apply(s);
              boolean future = now.isBefore(s.start());
              if (st == null) return Pick.of(future ? Outcome.ALREADY_UNSKIPPED : Outcome.ALREADY_RAN);
              if (!Database.SKIPPED.equals(st) || wholeDay.test(s) || !future) return Pick.of(Outcome.ALREADY_RAN);
              return Pick.ok(s);
          }
          boolean anyFree = false;
          for (Rotation.Slot s : today) {
              if (!now.isBefore(s.start())) continue;
              String st = status.apply(s);
              if (Database.SKIPPED.equals(st) && !wholeDay.test(s)) return Pick.ok(s);
              if (st == null) anyFree = true;
          }
          return Pick.of(anyFree ? Outcome.ALREADY_UNSKIPPED : Outcome.ALREADY_RAN);
      }
  }
  ```
- [ ] Run `./check.sh -Dtest=SessionPickerTest`. Expect pass.
- [ ] **PirateCommand:**
  - `today(sender)` returns `List<Rotation.Slot>` (`rotation.slots(today)`) with the same `info-disabled`/`info-ended` handling, or null.
  - In `admin(...)`, `start`, `skip` and `unskip` leave the `args.length != 2` default branch and get their own cases that accept `args.length` of 2 or 3 (more than 3 → `usage`). Parse `args[2]` with `Integer.parseInt`; on `NumberFormatException` send usage. The usage text is `subUsage + " [1-" + Math.max(1, todayCount) + "]"`, where `todayCount` = `rotation == null ? 1 : rotation.sessionsOn(rotation.todayAt(Instant.now()))`.
  - `adminStart`: get `today`; if a non-TEST event is active → `admin-already-ran`. Otherwise `SessionPicker.start(today, requested, Instant.now(), events::status)`. OK → `events.startStaff(pick.slot(), sender.getName())` + `admin-started`; ALREADY_RAN → `admin-already-ran`; BAD_ARG → usage.
  - `adminSkip`: `SessionPicker.skip(...)`. OK → `events.skip(pick.slot(), name)` + `admin-skipped`; ALREADY_SKIPPED → `admin-skipped`; ALREADY_RAN → `admin-already-ran`; BAD_ARG → usage.
  - `adminUnskip`: `SessionPicker.unskip(..., s -> events.isWholeDay(s.day()))`. OK → `events.unskip(slot.day(), slot.session(), removed -> msg().send(sender, removed ? "admin-unskipped" : "admin-already-ran"))`; ALREADY_UNSKIPPED → `admin-unskipped`; ALREADY_RAN → `admin-already-ran`; BAD_ARG → usage.
  - `adminStatus`: add `Text.placeholder("session", session)` per the Values rule.
  - `adminSchedule`: `upcoming(now, 8, events::isClosed)`, with each line adding `Text.placeholder("session", s.label())`.
  - Tab-complete: when `args.length == 3` and `args[0]` is `admin`, and the sub is `start` (has `PERM_START`) or `skip`/`unskip` (has `PERM_SKIP`), with `n = rotation == null ? 0 : rotation.sessionsOn(rotation.todayAt(Instant.now()))`: if `n >= 2`, offer `"1".."n"`. The existing `resetcooldown`/`test` completions are unchanged.
- [ ] **messages.yml:** set exactly these two values (no other key changes; `config-version: 1` stays):
  ```yaml
  admin-status: "<prefix><gray>State: <white><state></white> <dark_gray>|</dark_gray> Rotation day: <white><day></white> <dark_gray>|</dark_gray> Session: <white><session></white> <dark_gray>|</dark_gray> Slot: <white><slot></white> <dark_gray>|</dark_gray> Next: <white><time></white>"
  admin-schedule-line: " <gold><day></gold> <dark_gray>(<session>)</dark_gray> <dark_gray>-</dark_gray> <white><time></white>"
  ```
- [ ] Gates: `./check.sh` and `./scripts/exploit-audit.sh` green.
- [ ] Commit: `feat: staff start/skip/unskip take an optional session; status and schedule show it`.

**Done when:** `SessionPickerTest` passes, and `grep -n "session" src/main/resources/messages.yml` shows exactly the two lines above.

---

## Task 6: README
**Agent:** main
**Files:** modify `README.md` only.
**Values:** taken verbatim from the spec (§3, §4, §6, §9).

- [ ] "How a day works": after the rotation table, add a subsection **Two events a day** explaining `"HH:mm + HH:mm"` entries, the example `slots: ["13:00 + 21:00"]`, that sessions are ordered by time after `day-rollover` (a late-night time is last), the validation rules (max 2, no equal times, gap ≥ `duration-minutes`, an invalid entry runs only its first time with a SEVERE log), and that each session is an ordinary event with its own warnings, results, rewards and Discord posts.
- [ ] Commands table: `start`/`skip`/`unskip` gain `[session]` and the no-argument rule from spec §3. `status` mentions the session; `schedule` lists the next 8 **sessions** with `(n/N)`. Mention tab-complete `1 2`.
- [ ] config.yml block: the new `slots` comment, and `key-cooldown-hours: 72  # 0 = no cooldown at all`.
- [ ] FAQ entries: **"I upgraded from 1.0.0. What happens to my data?"** covers the automatic `data.db.v1-backup-<timestamp>` file, the in-place schema v2 migration (all events, scores, trophies, key wins and pending rewards kept), and days recorded before the upgrade counting as complete for both sessions. **"How do I roll back to 1.0.0?"** covers: stop, put the old jar back, replace `data.db` with the backup and delete `data.db-wal`/`data.db-shm`; anything recorded after the upgrade is lost; 1.0.0 refuses to start on a v2 file that has two events on one day. **"I switched to two a day mid-season and see MISSED warnings for past days"** explains why this is harmless.
- [ ] Gates: `./check.sh` and `./scripts/exploit-audit.sh` green.
- [ ] Commit: `docs: README for twice-daily sessions, the v2 migration and rollback`.

**Done when:** `grep -c "13:00 + 21:00" README.md` ≥ 1 and `grep -c "v1-backup" README.md` ≥ 1.

---

## After the tasks

### Final review and verification (dispatcher)
Run mc-plan-execute's final review over `git diff 3788fef..HEAD` and its acceptance check against spec §11
items 1–11. Items 12–14 belong to the Minecraft Server session. Then `./check.sh` must be green
(quote the test count; expect 61 = 33 existing + 10 SlotEntry + 5 Database + 2 RewardPlanner + 5 Rotation + 6 SessionPicker) and `./scripts/exploit-audit.sh` green.
Confirm that `target/craftedpiratecatch-1.1.0.jar` exists, and record `sha256sum target/craftedpiratecatch-1.1.0.jar`
and `git rev-parse --short HEAD` in the ledger.

### GO message to the "Minecraft Server" session (dispatcher, last step)
Only if the final review and both gates are green. Load the tool with `ToolSearch` `select:SendMessage`,
then `SendMessage` to `Minecraft Server` with the text below, filling the three `<…>` values:

```
PIRATECATCH 1.1.0 GO.

From the "Pirates Catch" build session. Sam is asleep and will NOT answer anything; do the whole job yourself and do not ask questions.
You were primed earlier with these same instructions; this message is the trigger and repeats everything.

Jar: /home/samuel/dev/CraftedSurvival/CraftedPirateCatch/target/craftedpiratecatch-1.1.0.jar
SHA-256: <sha256>
Commit: <short sha> on branch feat/twice-daily (local only, not pushed)
Spec: /home/samuel/dev/CraftedSurvival/CraftedPirateCatch/docs/planning/specs/2026-11-06-twice-daily-design.md (sections 12 and 12b)

0. Check that you have the mcp__coral__* tools. If you don't, touch no server: write the report saying so and stop.
   Copy the jar into ~/dev/CraftedSurvival/main/rollouts/<today>-piratecatch-twice-daily/ and verify the SHA-256 there.
   Follow your mc-servers and mc-testing rules throughout.

1. TESTING (a8e7c4b4). Back up /plugins/CraftedPirateCatch/. Stop Testing. Upload the 1.1.0 jar and remove the old craftedpiratecatch jar.
   Put a COPY of Red SMP's /plugins/CraftedPirateCatch/data.db (plus data.db-wal and data.db-shm if present; download only, never write to Red SMP)
   into Testing's /plugins/CraftedPirateCatch/. If downloading from Red SMP isn't possible, use Testing's own existing data.db and say so in the report.
   Start Testing. The log MUST show, in order and with no stack trace:
     "Backed up data.db to data.db.v1-backup-<ts> before the schema v2 migration."
     "Database migrated to schema v2 (<n> event record(s) kept as whole-day records)."
     "Loaded <n> event record(s)."
   Console: `pirate admin status` → a status line, no error.
   Then edit Testing's config.yml schedule: first-day and last-day = today (yyyy-MM-dd); slots: ["<HH:mm now+4min> + <HH:mm now+14min>"];
   duration-minutes: 5; warnings-minutes: [2, 1]; fairness key-cooldown-hours: 0. Run `pirate reload` → "Reloaded".
   `pirate admin schedule` → two lines marked (1/2) and (2/2). `pirate admin skip 3` → usage "[1-2]".
   `pirate admin skip 2` → skipped; `pirate admin schedule` → only (1/2); `pirate admin unskip 2` → back on the schedule.
   `papi parse --null %craftedpiratecatch_next_in%` → a duration.
   Wait through BOTH sessions. For each one the log/console shows the two warnings, the start broadcast, then the end broadcast
   ("Nobody caught any pirate loot" is expected) and "finalized". Session 2 must start after session 1 ends.
   Restart Testing once more: there must be NO new "Backed up" line.
   Put Testing's schedule back to what it was before (from your backup) and reload.
   ABORT RULE: if any check fails, do NOT touch Red SMP. Write the report and stop.

2. RED SMP, only if every Testing check passed. Staged rollout in the rollouts folder per mc-servers.
   Window: `pirate admin status` must show no running event and no event starting within 90 minutes under the live config (the live config before
   the change runs one event per day from the rotation 13:00/17:00/21:00/01:00 starting 2026-11-02, plus warnings up to 60 min before). Wait for a safe window if needed.
   Back up the whole /plugins/CraftedPirateCatch/ folder (jar, config.yml, messages.yml, data.db, data.db-wal, data.db-shm) and record the SHA-256s.
   Give the usual restart warning, then stop Red SMP. Swap the jar to 1.1.0 (remove the 1.0.0 jar).
   Edit /plugins/CraftedPirateCatch/config.yml, changing ONLY:
     schedule.slots: ["13:00 + 21:00"]
     fairness.key-cooldown-hours: 0
     fairness.min-distinct-ips: 2
   Start Red SMP. Check, with no stack trace: the "Backed up data.db to data.db.v1-backup-…" and "Database migrated to schema v2 (…)" lines,
   "Loaded <n> event record(s).", `pirate admin status` IDLE, and `pirate admin schedule` listing 13:00 (1/2) and 21:00 (2/2) for upcoming days.
   ROLLBACK RULE: if the plugin fails to enable or any check fails: stop Red SMP, restore the backed-up folder exactly (1.0.0 jar, config.yml,
   messages.yml, data.db, -wal, -shm; delete the 1.1.0 jar; leave the v1-backup file in place), start, confirm 1.0.0 enables, and report.

3. REPORT. Write REPORT.md in the rollout folder: each step, quoted log lines, SHA-256s, the final state of Testing and Red SMP,
   and anything skipped and why. Keep your rollout notes up to date as you normally do. Commit in that repo only if its rules say so,
   with the owner's identity and no AI attribution. Never push.
```

### The hand-test (Sam, when back)
Spec §12, steps 2–5, in game on Testing: fish at the dock in both sessions, and check that the second key win
carries no cooldown note and the tab-complete offers `1 2`.
