# CraftedPirateCatch — twice-daily sessions — specification
> Status: agreed 2026-11-06. Build: further development (from v1.0.0, branch `feat/initial-build` @ 3788fef).
> Target: MC 26.2, Java 25, Paper `[26.2.build,)` (unchanged pom), api-version 26.2. Branch: `feat/twice-daily`.
> Package: `net.samsside.craftedpiratecatch`. Author: SamsSide. Version: **1.1.0**.

## 1. Premise

Pirate's Catch runs once per rotation day today. This change lets a rotation day hold **two
sessions**. Each session is a full, ordinary Pirate's Catch: the same warnings, start broadcast, boss
bar, scoring, end broadcast, rewards and Discord posts. Players see no difference except that the
event comes round twice a day. Nothing in player-facing text names the session.

Two a day is **opt-in through the existing `schedule.slots` key**. Each entry is one rotation day:
`"HH:mm"` means one event that day, as now, and `"HH:mm + HH:mm"` means two. A config with plain
`"HH:mm"` entries behaves exactly as v1.0.0.

Red SMP will run `slots: ["13:00 + 21:00"]` (the same pair every day, Europe/London, 15 minutes
each), `fairness.key-cooldown-hours: 0` (a player may win the key at every event) and
`fairness.min-distinct-ips: 2`. Those are **live config edits made at rollout**. The bundled
defaults do not change except for comments.

Player experience on Red SMP: warnings at 60/15/5/1 minutes before 13:00, then the event from
13:00 to 13:15 with its results and rewards. The same happens again before 21:00 and from 21:00 to
21:15. `/pirate` and `%craftedpiratecatch_next%` always show the next session to start.

## 2. What is already true

- `schedule/Rotation.java`: pure maths. `slots` is a `List<LocalTime>` rotation, and day N uses
  `slots[(N - first-day) mod size]`. API: `slotTime(d)`, `slot(d)`, `nextScheduled(now, Predicate<LocalDate>)`,
  `nextStart(...)`, `upcoming(now, n, ...)`, `todayAt(now)`. `record Slot(LocalDate day, LocalTime time, Instant start, Instant end)`.
  Late-night rule: a time before `day-rollover` runs on the next calendar date.
- `schedule/SchedulerService.java`: a 20-tick loop that starts, late-starts or marks MISSED each day's
  slot, and sends warnings for `nextScheduled` (keyed `day:minutes`).
- `storage/Database.java`: SQLite (WAL) on a single DB thread. `events(id, rotation_day, slot, start_ms,
  end_ms, status, started_by, finalized)` with `UNIQUE INDEX ux_events_day ON events(rotation_day)`.
  `scores`, `trophies` and `key_wins` reference `event_id`, and `pending_rewards` is keyed by uuid. There
  is no schema version (`PRAGMA user_version` = 0).
- `event/EventManager.java`: `Map<LocalDate,String> days` mirrors `events` and drives `isClosed(LocalDate)`
  and `hasRow(LocalDate)`. It provides `startScheduled`, `startStaff`, `skip`, `unskip` and `markMissed`
  per day, recovery on enable, and exactly-once finalize.
- `event/ActiveEvent.java`: `day` and `slot`, with no session.
- `command/PirateCommand.java`: `admin start|skip|unskip` act on `rotation.slot(todayAt(now))`, and
  `status`/`schedule` show day and slot.
- `placeholder/PirateCatchExpansion.java`: `next`, `next_in` and `status` use `rotation.nextStart`.
- `config/PluginConfig.java`: parses `schedule.slots` with `getStringList` + `LocalTime.parse`, and
  `fairness.key-cooldown-hours` / `min-distinct-ips` as `max(0, n)`.
- `plugin.yml`: the command `piratecatch` has no `permission:` line (exploit-audit A1 fails).
- Tests: `RotationTest` (10), `RewardAllocatorTest` (11), `LootRollTest` (4), `DatabaseTest`, `UtilTest`.
- No `CLAUDE.md`, `check.sh`, `scripts/exploit-audit.sh` or `docs/planning/` yet.

**This change alters:** Rotation (sessions), SchedulerService (per-session loop and warnings),
Database (schema v2 + migration), EventManager/ActiveEvent (session-keyed state), PirateCommand
(optional session argument, session in status/schedule), PirateCatchExpansion (session-aware
predicate only), PluginConfig (slot-entry parsing, cooldown 0), the bundled config.yml comments,
messages.yml (`admin-status`, `admin-schedule-line`), plugin.yml (command permission), the pom
version, README, and the mc-setup tooling.

## 3. Commands and permissions

Unchanged nodes and defaults. The only changes are the `[session]` argument and the status/schedule output.

| Command | Args | Permission (default) | Console | Does | Tab-complete |
|---|---|---|---|---|---|
| `/piratecatch admin start` | `[session]` | `craftedpiratecatch.admin.start` (op) | yes | Starts a session of **today** (rotation day, 06:00→06:00) now for `duration-minutes`. No arg: today's first session (in start order) that has no record and whose window end is after now. With an arg: that session, which must have no record. Ends a TEST event first, as now. | `1`,`2` when today has 2 sessions; nothing otherwise |
| `/piratecatch admin skip` | `[session]` | `craftedpiratecatch.admin.skip` (op) | yes | Marks a session of today SKIPPED if it has not started. No arg: the first of today's sessions with start > now and no record. | as above |
| `/piratecatch admin unskip` | `[session]` | `craftedpiratecatch.admin.skip` (op) | yes | Removes a SKIPPED record for a session of today whose start > now. No arg: the first such SKIPPED session. | as above |
| `/piratecatch admin status` | — | `craftedpiratecatch.admin.status` (op) | yes | As now, plus `<session>`: the running session, or the next one when idle. | — |
| `/piratecatch admin schedule` | — | `craftedpiratecatch.admin.status` (op) | yes | The next **8 sessions** (not days) that are not closed, each with `<session>`. | — |
| everything else | | | | unchanged | unchanged |

`[session]` is 1-based. Anything that is not an integer from 1 to today's session count
(including `2` on a one-a-day day) sends `usage` with `<usage>` = `/<label> admin <sub> [1-N]`, where N is
today's session count (`[1-1]` for one-a-day).

Messages each command can send (all existing keys):
- **start:** `admin-started`, `admin-already-ran` (no eligible session, the chosen session has a record, or a non-TEST event is active), `info-disabled`, `info-ended`, `usage`, `no-permission`.
- **skip:** `admin-skipped` (also sent when no arg is given, no unrecorded future session exists and at least one future session is already SKIPPED; and when the chosen session is already SKIPPED), `admin-already-ran`, `info-disabled`, `info-ended`, `usage`, `no-permission`.
- **unskip:** `admin-unskipped` (also sent when the chosen session has no record and start > now, or when no arg is given, no SKIPPED future session exists and at least one future session has no record), `admin-already-ran`, `info-disabled`, `info-ended`, `usage`, `no-permission`.
- **status/schedule:** `admin-status`, `admin-schedule-line`, `info-disabled`, `info-ended`.

**plugin.yml:** add `permission: craftedpiratecatch.use` under `commands.piratecatch`, which fixes audit A1.
The node is already default `true` and checked in code.

## 4. Config

`config-version` stays **1** because no key is added or renamed. Changed behaviour:

| Key | Type | Default | Meaning | Invalid → |
|---|---|---|---|---|
| `schedule.slots` | list of strings | `["13:00", "17:00", "21:00", "01:00"]` (unchanged) | Each entry is one rotation day; day N uses `slots[(N - first-day) mod size]`. An entry is `"HH:mm"` (one session) or `"HH:mm + HH:mm"` (two sessions; whitespace around `+` optional). | See the entry rules below. |
| `fairness.key-cooldown-hours` | int | 72 (unchanged) | **0 = no cooldown at all**: no lookup, nobody is ever on cooldown, and `reward-cooldown-note` is never queued. | negative → 0, as now |
| `fairness.min-distinct-ips` | int | 3 (unchanged) | unchanged; Red SMP sets 2 by hand | negative → 0, as now |

Entry rules (pure function, unit-tested):
1. Split on `+`, trim each part, parse each as `LocalTime` (`HH:mm`).
2. Session order: sort by minutes after `day-rollover`, i.e. `floorMod(t - rollover, 1440)`. A late-night
   time (before rollover) is therefore always last. Example with rollover 06:00: `"01:00 + 21:00"` → sessions 21:00, 01:00.
3. The entry is **invalid** if it has more than 2 parts, if any part fails to parse, if the two times are equal,
   or if the gap between the two (in minutes after rollover) is less than `duration-minutes`, which would overlap.
4. An invalid entry logs SEVERE `Schedule slot entry '<raw>' is invalid (<reason>) - using only '<first>'.`
   and falls back to its **first parseable part as written** (one session). If no part parses, it logs
   WARNING `Unparsable schedule slot '<raw>' dropped (expected HH:mm or HH:mm + HH:mm).` and drops the entry.
   An empty resulting list disables scheduling, as now.

The bundled `config.yml` keeps every default value and only rewrites the `slots` comment:
```yaml
  # Each entry is ONE rotation day; rotation day N uses slots[(N - first-day) mod size].
  # "HH:mm" = one event that day; "HH:mm + HH:mm" = two events that day (24h).
  # Same pair every day:  slots: ["13:00 + 21:00"]
  slots: ["13:00", "17:00", "21:00", "01:00"]
```

## 5. Messages

Only two staff keys change their bundled default (the live file keeps its own text; ConfigMerger never overwrites).
No new keys. Player-facing keys are **untouched**.

| Key | New default | Placeholders |
|---|---|---|
| `admin-status` | `"<prefix><gray>State: <white><state></white> <dark_gray>\|</dark_gray> Rotation day: <white><day></white> <dark_gray>\|</dark_gray> Session: <white><session></white> <dark_gray>\|</dark_gray> Slot: <white><slot></white> <dark_gray>\|</dark_gray> Next: <white><time></white>"` | `<state> <day> <session> <slot> <time>` |
| `admin-schedule-line` | `" <gold><day></gold> <dark_gray>(<session>)</dark_gray> <dark_gray>-</dark_gray> <white><time></white>"` | `<day> <session> <time>` |

`<session>` = `"<n>/<count>"` (1-based, e.g. `1/2`; `1/1` on one-a-day days). For a pre-upgrade
whole-day record it is `"-"`, and when nothing is running or upcoming it is `"-"`.

## 6. Data

SQLite, unchanged choice. **Schema v2**, tracked in `PRAGMA user_version`:

```sql
CREATE TABLE IF NOT EXISTS events (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  rotation_day  TEXT    NOT NULL,
  slot          TEXT    NOT NULL,
  start_ms      INTEGER NOT NULL,
  end_ms        INTEGER NOT NULL,
  status        TEXT    NOT NULL,
  started_by    TEXT    NOT NULL,
  finalized     INTEGER NOT NULL DEFAULT 0,
  session       INTEGER NOT NULL DEFAULT -1   -- 0-based session index; -1 = pre-upgrade whole-day record
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_events_day_session ON events(rotation_day, session);
-- scores, trophies, key_wins, pending_rewards: unchanged
PRAGMA user_version = 2;
```

New rows always write `session` ≥ 0. A row with `session = -1` **closes every session of its day**.
That covers the upgrade-day decision: days recorded before the upgrade count as complete.

**Migration (on `Database.open`, on the DB thread, before the schema is created):**
1. Read `PRAGMA user_version`. If it is > 2, fail with `SQLException("data.db schema v<N> is newer than this plugin supports (v2)")`
   and the plugin disables itself, as for any open failure. The DB is untouched.
2. If table `events` exists and `PRAGMA table_info(events)` has no `session` column (a v1 file):
   a. **Backup:** `VACUUM INTO '<dbfile>.v1-backup-<yyyyMMdd-HHmmss>'` in the same folder
      (e.g. `data.db.v1-backup-20261107-120000`). This gives a consistent copy that includes WAL contents. If
      the target exists or the VACUUM fails, throw, the plugin disables, and no migration statement runs.
      Log INFO `Backed up <name> to <backup name> before the schema v2 migration.`
   b. In one transaction: `ALTER TABLE events ADD COLUMN session INTEGER NOT NULL DEFAULT -1`;
      `DROP INDEX IF EXISTS ux_events_day`; `CREATE UNIQUE INDEX ux_events_day_session ON events(rotation_day, session)`;
      `PRAGMA user_version = 2`; commit. On any error, roll back and throw (SQLite DDL is transactional).
      Log INFO `Database migrated to schema v2 (<n> event record(s) kept as whole-day records).`
3. Then run `createSchema` (all `IF NOT EXISTS`, v2 DDL above) and set `user_version = 2` if it is 0 (fresh file).
4. A v2 file opens with no backup and no DDL changes (idempotent; repeated reloads create no backups).

Scores, trophies, key_wins and pending_rewards are never touched by the migration, so their rows and
ids are preserved.

**In-memory state:** EventManager keeps `Map<SessionKey, String>` (`record SessionKey(LocalDate day, int session)`)
plus `Map<LocalDate, String>` for whole-day (-1) rows. A session is *recorded* if it has its own row or its
day has a whole-day row, and *closed* if that status is in `CLOSED`. Both are loaded at enable (sync, as now)
and on reload (async, as now).

**Database down / insert fails:** same behaviour as now, per session.

**Downgrade:** the v1.0.0 jar cannot run on a v2 file that has two rows for one day, because its
`CREATE UNIQUE INDEX ux_events_day` fails and the plugin disables. Rollback = stop the server, put the 1.0.0
jar back, and replace `data.db` (removing `data.db-wal`/`-shm`) with the `data.db.v1-backup-*` copy. Anything
recorded after the upgrade is lost. This goes in the README.

## 7. GUIs
N/A.

## 8. Integrations

PlaceholderAPI (hard depend, unchanged ids):
- `%craftedpiratecatch_next%` / `_next_in%`: start of the first session with start > now that is not
  closed. A session already under way is skipped, as now.
- `%craftedpiratecatch_status%`: `info-live` while live, otherwise `info-next` for that next session; unchanged text.
- All other placeholders are unchanged.

Discord webhook: unchanged. Each session posts its own start/end like any event, with the same text.
WorldGuard: unchanged. No new libraries.

## 9. Abuse and edge cases

| Case | Rule |
|---|---|
| Two sessions overlap (`"13:00 + 13:10"` with 15-min duration) | Entry invalid → SEVERE, first time only (§4). |
| Session 1 is staff-started late and still running at session 2's start | Session 2 waits; when session 1 ends inside session 2's window, session 2 late-starts with its **original** end (existing late-start rule). If session 2's window has already passed when session 1 ends → MISSED. |
| Warning for session 2 while session 1 is running, or right after it | Warnings target the **first session with start > now and no record** (not the running one), keyed `day#session:minutes`. Late-warning grace (60 s) is unchanged. |
| Server down for one session's whole window | That session → MISSED; the other session of the day is unaffected. |
| Restart mid-session | Resume or finalize by event id, as now; the session comes from the row. |
| Upgrade day | Pre-upgrade rows have session -1 and close both sessions of their day. Days after the upgrade use sessions. |
| Config switched from one-a-day to pairs mid-season after the upgrade | Past days with a session-0 row get their session-1 window evaluated, and those windows have passed → MISSED rows + WARNING log lines (one per past day). Harmless and documented. Rollout edits config.yml **before** the first start of the new jar, so on Red SMP every pre-upgrade day is a whole-day row. |
| Rotation day where entries have 1 and 2 times (rotating pairs) | Supported; session count is per day. |
| Key cooldown 0 | No `key_wins` lookup, so a player can win the key at both sessions. key_wins is still written (history). `admin resetcooldown` still works and deletes 0 rows. |
| Double finalize / double pay | Unchanged exactly-once finalize by event id; sessions are separate event ids. |
| Staff `skip 2` while session 2 is running | Has a record → `admin-already-ran` (use cancel). |
| Staff `start` with no arg when every session today is recorded | `admin-already-ran`. |
| Session arg `0`, `3`, `abc`, `-1`, `999999999999` | `usage`. |
| Legacy (-1) SKIPPED row for today | skip → `admin-skipped`; unskip → `admin-already-ran` (only possible on the upgrade day). |
| Bad DB file newer than the plugin | Refuses to open; plugin disables; file untouched. |
| Backup can't be written (disk full, name exists) | Migration aborted, plugin disables, v1 file untouched. |
| DST gap/overlap | Each session resolves with the existing `ZonedDateTime.ofLocal(..., null)` rule. |

## 10. Out of scope

- Different rewards, durations or text per session (Sam: every session is the same event). This would need a future version and a new config shape.
- More than 2 sessions per day (validated out).
- Player-facing session wording or a "today's times" list.
- Changing Red SMP or Testing **from the build session**. The executor never touches a server. Testing
  and the Red SMP rollout are done by Sam's separate **"Minecraft Server"** Claude session (which has
  Coral), triggered by a GO message at the end of the plan (§12b).

## 11. Acceptance criteria

1. One-a-day configs produce exactly the v1.0.0 schedule (the existing RotationTest cases pass unchanged in meaning). Verified by: unit test `RotationTest`.
2. `"13:00 + 21:00"` yields two sessions per rotation day at 13:00 and 21:00 Europe/London, each `duration` long. Verified by: unit test `RotationTest#fixedPairEveryDay`.
3. `"01:00 + 21:00"` orders 21:00 first and runs 01:00 on the next calendar date. Verified by: unit test `RotationTest#pairWithLateNightOrdersByRollover`.
4. `nextScheduled` / `nextStart` / `upcoming` walk sessions in start order and honour the per-session closed predicate. Verified by: unit test `RotationTest`.
5. Invalid entries (3 parts, equal times, overlap, bad time) fall back to the first parseable part, and unparsable entries are dropped. Verified by: unit test `SlotEntryTest`.
6. Opening a v1 `data.db` creates a `data.db.v1-backup-*` copy containing the original rows, adds `session` (= -1 on old rows), swaps the unique index to `(rotation_day, session)`, and sets `user_version` to 2. Every events, scores, trophies, key_wins and pending_rewards row survives with the same ids. Verified by: unit test `DatabaseTest#migratesV1KeepingAllData`.
7. Re-opening a migrated file makes no new backup; a fresh file is v2; a v3 file is refused. Verified by: unit tests `DatabaseTest#migrationIsIdempotent`, `#freshFileIsV2`, `#refusesNewerSchema`.
8. Two rows for the same day with sessions 0 and 1 insert; a duplicate (day, session) fails. Verified by: unit test `DatabaseTest#twoSessionsPerDay`.
9. `key-cooldown-hours: 0` yields an empty cooldown set even for a key won in the same millisecond. Verified by: unit test `RewardPlannerTest#zeroCooldownMeansNone`.
10. Session selection for staff start/skip/unskip follows §3. Verified by: unit test `SessionPickerTest`.
11. `./check.sh` and `./scripts/exploit-audit.sh` pass (A1 fixed). Verified by: gates.
12. On Testing with two sessions a few minutes apart, both sessions warn, start and end (console log), and `admin schedule` lists both with `(1/2)`/`(2/2)`. Verified by: the Minecraft Server session (§12b, console-driven), then 👤 in-game fishing when Sam is back.
13. A copy of Red SMP's `data.db` migrates on Testing with the backup file present and `admin status` working. Verified by: the Minecraft Server session (§12b).
14. Red SMP runs 1.1.0 with `slots: ["13:00 + 21:00"]`, `key-cooldown-hours: 0`, `min-distinct-ips: 2`, migrated, with a pre-rollout backup, or it is untouched/rolled back with a written reason. Verified by: the Minecraft Server session's rollout report.

## 12. Hand-test (Sam, on Testing `a8e7c4b4`, from a session in `~/dev/CraftedSurvival/main`)

Prep: back up Testing's `/plugins/CraftedPirateCatch/` (the mc-servers procedure). Upload `target/craftedpiratecatch-1.1.0.jar`, removing the 1.0.0 jar.

1. **Migration on real data:** copy Red SMP's `data.db` to Testing **read-only** (download, then upload to Testing; never write to Red SMP), with the server stopped. Start it. Expect in the log:
   `Backed up data.db to data.db.v1-backup-<ts> before the schema v2 migration.` then `Database migrated to schema v2 (<n> event record(s) kept as whole-day records).` then `Loaded <n> event record(s).` with no stack trace.
   `/pirate admin status` shows the next session. Restart once more: no new backup line.
2. **Two sessions:** set `first-day`/`last-day` to today, `slots: ["<HH:mm now+4> + <HH:mm now+16>"]`, `duration-minutes: 5`, `warnings-minutes: [2, 1]`, `key-cooldown-hours: 0`, `min-distinct-ips: 1`, then `/pirate reload`.
   `/pirate admin schedule` → two lines `(1/2)` and `(2/2)`. Warnings at −2 and −1 min, then start → fish at the dock → end broadcast + key. The same for session 2: you win the key **again** (no cooldown note).
3. **Staff:** before session 2, `/pirate admin skip 2` → `admin-skipped`; `/pirate admin schedule` no longer lists it; `/pirate admin unskip` → `admin-unskipped`. `/pirate admin skip 3` → usage `[1-2]`. Tab-complete on `admin skip ` offers `1 2`.
4. **One-a-day still works:** set `slots: ["<now+3>"]`, reload. `admin schedule` shows `(1/1)` and the event runs once.
5. **Placeholders:** `/papi parse me %craftedpiratecatch_next_in%` between the sessions → time to session 2.

Failure looks like: any stack trace on enable, no backup file, session 2 not starting after session 1, a cooldown note on the second win, or missing warnings for session 2.

**Red SMP rollout (staged in `~/dev/CraftedSurvival/main/rollouts/`, only with Sam's go-ahead, outside 12:00–13:30 and 20:00–21:30):**
back up `/plugins/CraftedPirateCatch/` (including `data.db`, `-wal`, `-shm`) → stop → swap the jar to 1.1.0 → edit `config.yml`:
`slots: ["13:00 + 21:00"]`, `key-cooldown-hours: 0`, `min-distinct-ips: 2` → start → check the backup + migration log lines and `/pirate admin schedule`.
Optional: add `<session>` to the live `admin-status` / `admin-schedule-line` texts.

## 12b. Unattended Testing + Red SMP rollout (agreed 2026-11-06)

Sam is asleep during execution and **will not answer any question**. He has explicitly authorised
deploying to Red SMP without asking, subject to the gates below. Division of work:

1. **Build session** (mc-plan-execute in this repo): builds, gates and commits. Its last task sends the GO
   message (the exact text is in the plan) to the local session named **"Minecraft Server"**. It touches no server.
2. **Minecraft Server session** (already primed with the same instructions when the plan was approved):
   on GO, it does the following without asking anyone:
   - **Testing (`a8e7c4b4`), per mc-testing/mc-servers:** back up Testing's `/plugins/CraftedPirateCatch/`, put
     the 1.1.0 jar in (removing 1.0.0), then put a **copy** of Red SMP's `data.db` (+`-wal`/`-shm` if present,
     read-only download from Red SMP) in place with the server stopped, and start it. Check the backup and
     migration log lines, `Loaded <n> event record(s)`, no stack trace. Then a console-driven
     two-session run (`first-day`/`last-day` today, `slots: ["<now+4m> + <now+14m>"]`, `duration-minutes: 5`,
     `warnings-minutes: [2, 1]`, reload): warnings, start, `end-nobody` end and the next session in the
     log for **both** sessions; `pirate admin schedule` shows `(1/2)`/`(2/2)`; `pirate admin skip 3` → usage;
     `pirate admin skip 2` then `pirate admin unskip 2`; `papi parse --null %craftedpiratecatch_next_in%`.
     Restart once more: no second backup line.
   - **Abort rule:** if any Testing check fails, **do not touch Red SMP**. Write the report and stop.
   - **Red SMP (staged in `~/dev/CraftedSurvival/main/rollouts/<date>-piratecatch-twice-daily/`, per mc-servers):**
     only in a window where `pirate admin status` shows no running event and none starting within 90
     minutes under the live config. Back up `/plugins/CraftedPirateCatch/` fully (jar, `config.yml`,
     `messages.yml`, `data.db`, `-wal`, `-shm`). Follow the usual restart warning, stop, swap the jar to 1.1.0
     (removing 1.0.0), edit `config.yml` (`slots: ["13:00 + 21:00"]`, `key-cooldown-hours: 0`,
     `min-distinct-ips: 2`; nothing else), and start. Verify the backup + migration log lines, no stack trace,
     `pirate admin schedule` lists 13:00 (1/2) and 21:00 (2/2) from the next rotation day, and
     `pirate admin status` is IDLE.
   - **Rollback rule:** if the plugin fails to enable or any check fails on Red SMP: stop, restore the
     backed-up folder exactly (1.0.0 jar, config, data.db, -wal, -shm), start, confirm 1.0.0 enables, and report.
   - **Report:** write `REPORT.md` in the rollout folder (each step, log lines quoted, final state) and
     update Sam's rollout notes as that session normally does. Commit locally there only if that repo's
     rules say so, with no AI attribution and no push.

## 13. Open questions
None.
