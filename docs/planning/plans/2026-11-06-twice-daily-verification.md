# CraftedPirateCatch — twice-daily sessions — verification
> Plan: `docs/planning/plans/2026-11-06-twice-daily.md`. Spec: `docs/planning/specs/2026-11-06-twice-daily-design.md`.
> Branch: `feat/twice-daily`. Commits: 3788fef..ac82739 (spec/plan 4afb76b; build 010cfac..ac82739). Date: 2026-11-07.

## Gates
| Gate | Baseline | Final |
|---|---|---|
| `./check.sh` | gates didn't exist; after scaffold, 33 tests, 0 failures | 62 tests, 0 failures, 0 errors — `check.sh: PASS in 13s — target/CraftedPirateCatch-1.1.0.jar (132K)` |
| `./scripts/exploit-audit.sh` | `FAIL A1: command 'piratecatch' in src/main/resources/plugin.yml has no permission:` (1 failure) | `exploit-audit: 0 failure(s), 0 warning(s)` / `EXPLOIT AUDIT PASS` |

Jar: `target/CraftedPirateCatch-1.1.0.jar` (134,825 bytes), SHA-256 `d0e53f991f0e8f7bcc2360a2c44221773812a7ee11b7227b31415046b6680bc5`.

Tests: LootRoll 4, RewardAllocator 10, RewardPlanner 2, Rotation 15, SlotEntry 10, Database 12, SessionPicker 6, Util 3.
(The plan expected 61; the final fix round added `DatabaseTest#backupIncludesUncheckpointedWal`.)

## Acceptance criteria
| # | Criterion | Method | Evidence | Result |
|---|---|---|---|---|
| 1 | One-a-day configs unchanged | unit test `RotationTest` (10 migrated cases) | check.sh PASS | ✅ |
| 2 | `"13:00 + 21:00"` → two 15-min sessions | `RotationTest#fixedPairEveryDay` | check.sh PASS | ✅ |
| 3 | `"01:00 + 21:00"` orders 21:00 first, 01:00 next date | `RotationTest#pairWithLateNightOrdersByRollover` | check.sh PASS | ✅ |
| 4 | next/upcoming walk sessions in order, per-session predicate | `RotationTest#closedSessionSkipsOnlyThatSession`, `#fixedPairEveryDay` | check.sh PASS | ✅ |
| 5 | Invalid entries fall back, unparsable dropped | `SlotEntryTest` (10) | check.sh PASS | ✅ |
| 6 | v1 file migrates with backup, all rows/ids kept, index swapped, user_version 2 | `DatabaseTest#migratesV1KeepingAllData` (incl. key_wins survival and (day,0)/(day,1) inserts after migration), `#backupIncludesUncheckpointedWal` (row only in the WAL reaches the backup; WAL 12392 → 20632 bytes during open) | check.sh PASS | ✅ |
| 7 | Idempotent re-open; fresh file v2; v3 refused | `DatabaseTest#migrationIsIdempotent`, `#freshFileIsV2`, `#refusesNewerSchema` | check.sh PASS | ✅ |
| 8 | (day,0)+(day,1) insert; duplicate fails | `DatabaseTest#twoSessionsPerDay` | check.sh PASS | ✅ |
| 9 | Cooldown 0 → empty cooldown set | `RewardPlannerTest#zeroCooldownMeansNone` (helper; query `won_ms >= Long.MAX_VALUE` matches nothing) | check.sh PASS | ✅ |
| 10 | Staff session selection per §3 | `SessionPickerTest` (6) | check.sh PASS | ✅ |
| 11 | Both gates pass, A1 fixed | gates | quoted above | ✅ |
| 12 | Two sessions on Testing | server | not run (owner: build only, no server) | ⏳ |
| 13 | Copy of Red SMP data.db migrates on Testing | server | not run (owner: build only, no server) | ⏳ |
| 14 | Red SMP rollout | server | not run (owner: build only, no server) | ⏳ |

## Test server
Not used. Midway through the run Sam said: build the plugin only, send no GO message, put nothing on any server.

## Hand-test for Sam
Spec §12 when you choose to deploy. Short form, on Testing (`a8e7c4b4`):
1. Back up `/plugins/CraftedPirateCatch/`, swap in `CraftedPirateCatch-1.1.0.jar` (remove 1.0.0), start. With an old
   `data.db` expect `Backed up data.db to data.db.v1-backup-<ts> before the schema v2 migration.` then
   `Database migrated to schema v2 (<n> event record(s) kept as whole-day records).` Restart: no second backup line.
2. `first-day`/`last-day` = today, `slots: ["<now+4> + <now+16>"]`, `duration-minutes: 5`, `warnings-minutes: [2, 1]`,
   `key-cooldown-hours: 0`, `/pirate reload`. `/pirate admin schedule` → `(1/2)` and `(2/2)`. Fish in both sessions;
   you win the key twice with no cooldown note.
3. Before session 2: `/pirate admin skip 2` → skipped; `/pirate admin unskip` → unskipped; `/pirate admin skip 3` →
   usage `[1-2]`; tab-complete on `admin skip ` offers `1 2`.
4. `slots: ["<now+3>"]`, reload → `admin schedule` shows `(1/1)`, the event runs once.
5. `/papi parse me %craftedpiratecatch_next_in%` between sessions → time to session 2.

## Rulings
- Jar is `target/CraftedPirateCatch-1.1.0.jar` (capitalised finalName), not `craftedpiratecatch-1.1.0.jar` as the plan wrote.
- Unskip never targets a whole-day (-1) row; a legacy whole-day SKIPPED row answers `admin-already-ran` (spec §9).
- Task 4: config.yml had one slots comment line, not two; the timezone comment was kept.
- Task 4 review minor (a): `next`/`admin schedule` filter on "closed" per spec §8, so on upgrade day only a still-RUNNING
  legacy whole-day row lets session 2 show as next for that day. Kept as specified.
- Final fix round added a WAL-through-backup test (Red SMP's live WAL is uncheckpointed) and fixed the `CLOSED` Javadoc.
- `VACUUM INTO ?` with a bound parameter works with sqlite-jdbc 3.53.2.1; the literal fallback wasn't needed.

## Deferred minors (not fixed, judged harmless)
- `LocalTime.parse` also accepts seconds; `"13:00 ++ 21:00"` reports "more than 2 times"; no test for `"13:00 +"`.
- At cooldown 0 the key_wins query still runs (matches nothing); test covers the helper, not the wiring.
- `PRAGMA journal_mode=WAL` runs before a newer-schema refusal (cosmetic).
- Some log lines (cancel, finalize, record failures) name only the day, not the session.
- No unit test for EventManager's whole-day precedence (needs the plugin instance).
- Enable waits 30 s for `Database.open`; a very large v1 file could exceed that during VACUUM INTO + migration.

## Not done
- Spec §11 items 12–14 and §12b (Testing run, Red SMP rollout, GO message): dropped on Sam's instruction mid-run.
