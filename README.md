# CraftedPirateCatch

**Pirate's Catch** is a daily, one-hour competitive fishing race at the pirate fishing dock on the spawn island of
Crafted Survival's **Red SMP**, built for the **November 2026 Pirate month**.

During the hour, every fish caught by a player standing inside the dock's WorldGuard region also rolls a **pirate loot
table**. Pirate catches score points by rarity. The top **eligible** scorer wins a **Crafted Pirate Crate key**, 2nd/3rd
and every participant get **shards**, and the rare tiers also give a keepable **trophy** item. The start time **rotates
daily** so players in every timezone get a fair shot. The plugin runs everything by itself; staff can step in.

- Paper API **26.2** (runs on UniverseSpigot 26.2), Java **25**, Maven.
- Hard dependencies: **WorldGuard** (7.0.19) and **PlaceholderAPI**.
- Storage: **SQLite** (`plugins/CraftedPirateCatch/data.db`). The driver is loaded through `plugin.yml` `libraries:`.
- Rewards are **console commands in config**: ExcellentCrates for the key, ItemsAdder for shards and trophies.

---

## How a day works

Each **rotation day** has one event (or two, see *Two events a day*), one hour long by default. The start time moves one step each day:

| Rotation day | Start (UK time, Europe/London) | Actually runs on |
|---|---|---|
| Day 1 | 13:00 | the same calendar date |
| Day 2 | 17:00 | the same calendar date |
| Day 3 | 21:00 | the same calendar date |
| Day 4 | 01:00 | **the next calendar date** (late-night session) |
| Day 5 | 13:00 again | … |

**The late-night rule:** a slot earlier than `day-rollover` (06:00) is the *late session of its rotation day*, so the
01:00 slot of rotation day 5 Nov runs at 01:00 on 6 Nov. "Today", skip, the missed-slot rule, `/pirate` and the
placeholders all work in rotation days, not calendar dates. For staff commands, "today" runs from 06:00 to 06:00.

### Two events a day

An entry in `slots` can hold two times joined by `+`: `"HH:mm + HH:mm"` means two events (sessions) on that rotation
day, while a plain `"HH:mm"` stays one event, exactly as before. To run the same pair every day:

```yaml
schedule:
  slots: ["13:00 + 21:00"]
```

- **Order:** the sessions of a day are ordered by time after `day-rollover`, so a late-night time is always last.
  With rollover 06:00, `"01:00 + 21:00"` runs 21:00 first and then 01:00 on the next calendar date.
- **Validation:** at most 2 times per entry, the two times must differ, and they must be at least `duration-minutes`
  apart (exactly `duration-minutes` is allowed). An invalid entry logs a SEVERE line
  (`Schedule slot entry '<raw>' is invalid (<reason>) - using only '<first>'.`) and runs only its first time. An entry
  with no valid time is dropped with a WARNING.
- **Each session is an ordinary event:** its own warnings, start broadcast, boss bar, results, rewards and Discord
  posts. Players see the same messages as for any event; nothing names the session.
- Entries with one and two times can be mixed in one rotation; the session count is per day.

Before the start, warnings go out at 15, 5 and 1 minutes. At the start there is a broadcast and a boss bar that shows the
time left and the current leader. At the end the results are broadcast and rewards are paid.

## Setup

1. Drop `CraftedPirateCatch-1.0.0.jar` into `plugins/` next to WorldGuard and PlaceholderAPI, then start the server once
   to generate `config.yml` and `messages.yml`.
2. Build the dock, then create the WorldGuard region in the spawn world:
   `/rg define pirate_dock` (in world `spawn`). If you use other names, set `region.world` and `region.id`.
3. Set `schedule.first-day` and `schedule.last-day` (inclusive, `yyyy-MM-dd`). Defaults: 2026-11-02 to 2026-11-30.
4. Check the reward commands (`rewards.*`, `loot[].trophy-command`) match your crate and item IDs.
5. Optional: set `discord.webhook-url`.
6. `/piratecatch reload`, then `/piratecatch admin schedule` to confirm the next 8 sessions.
7. Make a FancyHolograms board at the dock using the placeholders below. This layout matches the spawn "weekly event"
   board (legacy codes, `update_text_interval: 5`, transparent background, no text shadow, centred):
   ```
   &dᴅᴀɪʟʏ ꜰɪѕʜɪɴɢ ʀᴀᴄᴇ
   &6Pirate's Catch
   %craftedpiratecatch_status%
   &7&l--------------------
   &71#&r %craftedpiratecatch_top_1_name% &7- &d%craftedpiratecatch_top_1_score% ⚓
   ...one line per rank, up to #10...
   &7&l--------------------
   &7#%craftedpiratecatch_rank%&r %player_name% &7- &d%craftedpiratecatch_score% ⚓
   ```
8. Rehearse with `/piratecatch admin test 5`. It gives no rewards and records nothing.

## Commands

The base command is `/piratecatch`, with the alias `/pirate`.

| Command | Permission | What it does |
|---|---|---|
| `/pirate` | `craftedpiratecatch.use` | **Live:** says it is live and how long is left, shows your score and rank (players only), and the top 5. **Scheduled:** the next start time (UK) and a countdown. **Season over:** says it has finished. **Scheduling disabled:** says it isn't scheduled. Works from the console. |
| `/pirate top` | `craftedpiratecatch.use` | The top `leaderboard.show-top` players of the running event, or "no catches yet". |
| `/piratecatch reload` | `craftedpiratecatch.reload` | Reloads `config.yml`, `messages.yml`, reopens the database and rebuilds caches. A running event keeps running, and new loot/reward settings apply to later catches and to its finalize. If the YAML can't be parsed, it says so and keeps the previous config. |
| `/piratecatch admin start [session]` | `craftedpiratecatch.admin.start` | Starts a session of **today** (rotation day, 06:00-to-06:00) now, for `duration-minutes`, under normal rules, and uses up that session. No argument: today's first session (in start order) that has no record and whose window hasn't ended. With an argument: that session, which must have no record (ran, running, skipped, missed or cancelled). Refuses outside the season or while a non-TEST event is running. If a TEST event is running, it is ended first. |
| `/piratecatch admin stop` | `craftedpiratecatch.admin.stop` | Ends the running event now and awards normally. |
| `/piratecatch admin cancel` | `craftedpiratecatch.admin.cancel` | Ends the running event with **no rewards** (recorded as CANCELLED). For a TEST event it just ends it. |
| `/piratecatch admin skip [session]` | `craftedpiratecatch.admin.skip` | Marks a session of today SKIPPED if it hasn't started. No argument: the first of today's sessions that hasn't started and has no record. Refuses if it is running or already ran (use cancel). |
| `/piratecatch admin unskip [session]` | `craftedpiratecatch.admin.skip` | Puts a skipped session of today back on the schedule if its start hasn't passed. No argument: the first such skipped session. A day skipped before the 1.1.0 upgrade can't be unskipped. |
| `/piratecatch admin test [minutes]` | `craftedpiratecatch.admin.test` | A TEST event, default 5 minutes, range 1 to 60. No rewards, no trophies, no database records and no Discord post, and it doesn't use the day. Broadcasts and the boss bar carry the `[TEST]` prefix. Refuses while any event runs. If a real event's start time arrives during a test, the test ends and the real event starts. |
| `/piratecatch admin status` | `craftedpiratecatch.admin.status` | State (IDLE / RUNNING / TEST / SEASON_OVER / DISABLED), the running or next rotation day, session (`n/N`) and slot, and the next start time. |
| `/piratecatch admin schedule` | `craftedpiratecatch.admin.status` | The next 8 open **sessions** (not days), each with its rotation day, `(n/N)` and start time. |
| `/piratecatch admin resetcooldown <player>` | `craftedpiratecatch.admin.resetcooldown` | Deletes that player's key wins from the last `key-cooldown-hours`, so they can win the key again. |

Wrong arguments show the usage message, and missing permission shows `no-permission`. Tab-complete only offers what you
can run. `admin` only appears if you hold at least one admin node, and under it only your permitted sub-commands are
listed. `resetcooldown` completes online player names, and `test` suggests `5 10 15`.

`[session]` on `start`, `skip` and `unskip` is 1-based and only matters on a two-event day; tab-complete offers
`1 2` when today has two sessions. Anything that isn't a whole number from 1 to today's session count (including `2` on a
one-event day) shows the usage, e.g. `/pirate admin skip [1-2]`.

## Permissions

| Node | Default | Grants |
|---|---|---|
| `craftedpiratecatch.use` | **everyone** | `/pirate`, `/pirate top` |
| `craftedpiratecatch.reload` | op | `/piratecatch reload` |
| `craftedpiratecatch.admin.start` | op | `admin start` |
| `craftedpiratecatch.admin.stop` | op | `admin stop` |
| `craftedpiratecatch.admin.cancel` | op | `admin cancel` |
| `craftedpiratecatch.admin.skip` | op | `admin skip`, `admin unskip` |
| `craftedpiratecatch.admin.test` | op | `admin test` |
| `craftedpiratecatch.admin.status` | op | `admin status`, `admin schedule` |
| `craftedpiratecatch.admin.resetcooldown` | op | `admin resetcooldown` |
| `craftedpiratecatch.admin.*` | op | every `admin` node above |

## Placeholders (PlaceholderAPI)

Identifier `craftedpiratecatch`. All values come from in-memory caches and never touch the database. A TEST event counts
as live. Text values are returned as legacy `§` colour text.

| Placeholder | Value |
|---|---|
| `%craftedpiratecatch_live%` | `true` / `false` |
| `%craftedpiratecatch_timeleft%` | time left while live (`1h 05m`, `12m`, `45s`), else empty |
| `%craftedpiratecatch_next%` | next start, formatted with `time-format` in the config timezone, or empty |
| `%craftedpiratecatch_next_in%` | time until the next start (`2h 05m`), or empty |
| `%craftedpiratecatch_status%` | one line built from `info-live` / `info-next` / `info-ended` / `info-disabled`, with `<prefix>` empty |
| `%craftedpiratecatch_top_<n>_name%` | name at rank n (1 to 10), or `-` |
| `%craftedpiratecatch_top_<n>_score%` | points at rank n (1 to 10), or `-` |
| `%craftedpiratecatch_score%` | the viewer's points this event (`0` if none) |
| `%craftedpiratecatch_rank%` | the viewer's rank, or `-` |
| `%craftedpiratecatch_participants%` | how many players are ranked (points > 0) |

The leaderboard snapshot behind these is rebuilt every `leaderboard.refresh-seconds`.

## Scoring

A catch counts only if **all** of these are true:

- an event (live or TEST) is running;
- the player is standing inside WorldGuard region `region.id` in world `region.world`. A player fishing elsewhere during
  an event gets `outside-region-hint` once per event. If the region or world doesn't exist, nothing scores and one ERROR
  is logged per event;
- the player's gamemode is in `allowed-gamemodes` and they are not vanished (metadata `vanished`);
- anti-AFK: the player moved to another block, or turned their view by at least 1°, within `max-idle-seconds`.

Each counted catch rolls the `loot` table once, in order. Normal vanilla fishing loot drops as usual. A pirate catch adds
the tier's points, shows `catch-message` and plays a sound. Tiers with `broadcast: true` (Legendary) are announced to
everyone. A tier with a `trophy-command` gives that trophy the **first** time a player lands that tier in an event: at
most one trophy per tier per player per event. TEST events give no trophies.

Ranking: points (high first), then whoever **reached** their score first, then name. Players with 0 points are never
ranked.

## Rewards and fairness

At the end:

1. **Placement shards** (`placement-shards`, default 5/3/2) go by **raw** placing.
2. The **key** goes to the highest-placed **eligible** player. They get the key **instead of** their placement shards
   (the shards are not passed on). It can land below 3rd if everyone above them is on cooldown.
3. **Participation shards** (`participation-shards`, default 1) go to every ranked player who got nothing else.

Fairness rules:

- **Key cooldown:** anyone who won the key in the last `key-cooldown-hours` (72) isn't eligible for it. They still place
  and get shards. If they topped the board they're told why (`reward-cooldown-note`). Set it to `0` for no cooldown at
  all: a player can win the key at every event, including both sessions of a day, and the note is never sent.
- **Minimum turnout:** the key is only given if the ranked players come from at least `min-distinct-ips` (3) **distinct
  IP addresses** (the real client IP as forwarded by Velocity, Bedrock included). Players sharing an IP still play, score,
  place and get shards; they just count once for turnout. Below the minimum, no key is given and shards are still paid.
- Nobody wins with 0 points.

**Delivery:** every reward is a row in `pending_rewards` with its command already filled in. It is delivered when the
player is online and has at least `required-free-slots` empty inventory slots. Otherwise they get `reward-queued` (once
per login) and it arrives later: on join, every 30 seconds, or at the next event end. Offline winners get their rewards
when they next join. A row is deleted **before** its command runs, so it can never run twice.

## config.yml

```yaml
config-version: 1                # don't edit; used for auto-merge

schedule:
  timezone: "Europe/London"      # all times/dates use this zone (invalid -> Europe/London)
  # Each entry is ONE rotation day; rotation day N uses slots[(N - first-day) mod size].
  # "HH:mm" = one event that day; "HH:mm + HH:mm" = two events that day (24h).
  # Same pair every day:  slots: ["13:00 + 21:00"]
  slots: ["13:00", "17:00", "21:00", "01:00"]
  day-rollover: "06:00"          # a slot earlier than this is the late-night session (runs on the NEXT calendar date)
  first-day: "2026-11-02"        # first rotation day (inclusive)
  last-day: "2026-11-30"         # last rotation day (inclusive); its late slot may fall on 1 Dec
  duration-minutes: 60           # event length, clamped 5..240
  warnings-minutes: [15, 5, 1]   # warning broadcasts before the start; values outside 1..120 dropped

region:
  world: "spawn"                 # world holding the dock region
  id: "pirate_dock"              # WorldGuard region id; only catches inside it count

anti-afk:
  enabled: true
  max-idle-seconds: 120          # a catch only scores if the player moved/looked around within this time

allowed-gamemodes: [SURVIVAL, ADVENTURE]   # who may score; unknown names ignored

loot:                            # rolled once per counted catch, in order; first match wins
  - id: common                   # unique id (also the trophy key)
    name: "<gray>ᴅᴏᴜʙʟᴏᴏɴ"      # shown as <tier> in messages
    chance: 25.0                 # percent; < 0 -> 0; if the total > 100, all are scaled down to 100
    points: 1                    # points added (< 0 -> 0)
    trophy-command: ""           # console command run once per player per event for this tier ("" = none); %player%
    broadcast: false             # announce to the whole server (legendary-broadcast)
  # uncommon 12% / 3 pts, rare 6% / 8 pts (Pirate Parrot), epic 1.7% / 20 pts (Purple Kraken),
  # legendary 0.3% / 50 pts (Treasure Chest, broadcast). Remaining 55% = no pirate catch.

rewards:
  key-command: "crates key give %player% crafted_pirate 1 -s"   # the daily key
  placement-shards: [5, 3, 2]    # shards for raw rank 1, 2, 3... (the key winner gets the key instead)
  participation-shards: 1        # for every ranked player who got nothing else (0 = off)
  shard-command: "iagive %player% craftedsurvival:shard %amount% silent"
  required-free-slots: 1         # free slots needed before an item reward runs; otherwise it waits

fairness:
  key-cooldown-hours: 72  # 0 = no cooldown at all (recent key winners can't win the key again within this window)
  min-distinct-ips: 3            # distinct IPs among ranked players needed for the key to be awarded

bossbar:
  enabled: true
  color: BLUE                    # PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE (unknown -> BLUE)
  overlay: PROGRESS              # PROGRESS, NOTCHED_6/10/12/20 (unknown -> PROGRESS)
  audience: all                  # all = every online player; world = only players in region.world

sounds:                          # Minecraft sound keys; unknown -> no sound + a warning
  warning: "block.note_block.bell"
  start: "item.goat_horn.sound.0"
  pirate-catch: "entity.experience_orb.pickup"
  legendary: "ui.toast.challenge_complete"
  end: "entity.player.levelup"

leaderboard:
  refresh-seconds: 2             # how often the placeholder/boss bar leaderboard is rebuilt
  show-top: 5                    # ranks shown by /pirate, /pirate top and the end broadcast (1..10)

database:
  file: "data.db"                # SQLite file in the plugin folder (plain file name only)
  flush-interval-seconds: 15     # how often changed scores are saved during an event

discord:
  webhook-url: ""                # empty = disabled
  on-start: true                 # post the start broadcast
  on-end: true                   # post the results
  username: "Pirate's Catch"
  avatar-url: ""                 # optional
```

## messages.yml

Every player-visible string lives in `messages.yml`, including command feedback, errors, broadcasts, the boss bar title,
leaderboard lines and reward notices. Set a message to `""` to stop it being sent.

**Formats.** Each message can use **MiniMessage** (`<gold>`, `<b>`, `<#ff8800>`, `<gradient:…>`), **legacy codes**
(`&6`, `§6`, `&l`, and hex as `&#RRGGBB`), or a mix of both in one string. Legacy codes are converted to MiniMessage before
parsing. Placeholder values such as player names are inserted as plain text, so they can't inject formatting.

**`<prefix>`** works in every message and resolves to the `prefix` key. `time-format` is a Java `DateTimeFormatter`
pattern (default `EEE d MMM HH:mm`) used for `<time>`, shown in the config timezone. `<duration>`, `<timeleft>` and `<in>`
look like `1h 05m`, `12m` or `45s`. During a TEST event, every broadcast and the boss bar start with `test-prefix`.

| Key | Tags available |
|---|---|
| `no-permission`, `player-only`, `reloaded`, `reload-failed`, `info-ended`, `info-disabled`, `top-header`, `top-empty`, `outside-region-hint`, `end-broadcast-header`, `end-no-key-turnout`, `end-no-key-eligible`, `end-nobody`, `reward-key`, `reward-queued`, `reward-delivered`, `admin-started`, `admin-already-ran`, `admin-not-running`, `admin-stopped`, `admin-cancelled`, `admin-skipped`, `admin-unskipped`, `bossbar-no-leader`, `test-prefix` | `<prefix>` |
| `unknown-player`, `admin-cooldown-reset` | `<player>` |
| `usage` | `<usage>` |
| `info-live` | `<timeleft>` |
| `info-next` | `<time>`, `<in>` |
| `info-your-score` | `<score>`, `<rank>` |
| `top-line`, `end-broadcast-line` | `<rank>`, `<name>`, `<score>` |
| `warning-broadcast` | `<minutes>` |
| `start-broadcast` | `<duration>` |
| `bossbar-title` | `<timeleft>`, `<leader>`, `<score>` |
| `catch-message` | `<tier>`, `<points>`, `<score>` |
| `trophy-message` | `<tier>` |
| `legendary-broadcast` | `<player>`, `<tier>` |
| `end-winner` | `<player>` |
| `reward-shards` | `<amount>`, `<rank>` |
| `reward-participation` | `<amount>` |
| `reward-cooldown-note` | `<hours>` |
| `admin-test-started` | `<minutes>` |
| `admin-status` | `<state>`, `<day>`, `<session>`, `<slot>`, `<time>` |
| `admin-schedule-line` | `<day>`, `<session>`, `<time>` |

`<session>` is `n/N` (1-based, e.g. `1/2`; `1/1` on one-a-day days), or `-` for a record from before 1.1.0 or when
nothing is running or upcoming. Existing `messages.yml` files keep their own text; add `<session>` by hand if you want it.

## Config updates (auto-merge)

On every start and reload, both `config.yml` and `messages.yml` are merged with the defaults bundled in the jar. Missing
keys are added, along with their comments where Bukkit's YAML keeps them. **Your existing values are never overwritten.**
Lists such as `loot` and `slots` count as single values and are never merged element by element. `config-version` is
raised to the bundled version. The console logs `Merged N new key(s) into <file>`.

Invalid values never crash the plugin. They log a WARNING and fall back to a safe value. Anything that makes the schedule
impossible (no valid slots, bad dates, `first-day` after `last-day`) logs an ERROR and disables scheduling until fixed.

## FAQ

**The server restarted mid-event. Are points lost?** No. Scores are saved every `flush-interval-seconds` and on shutdown.
If the server comes back inside the hour, the event resumes with the boss bar and scores intact. If the hour ended while it
was down, the event is finalized and paid on startup.

**The server was down for the whole slot.** That day is recorded as MISSED. There is no make-up event, and the next day
runs as normal.

**The server came up partway through a slot.** The event starts immediately and keeps its original end time, so it is
shorter.

**The winner was offline at the end / had a full inventory.** Rewards are queued and delivered exactly once when they
join or free a slot.

**Can a crash during the payout pay someone twice?** No. Marking the event finished, recording the key win and queuing
every reward happen in one database transaction. Delivery only drains that queue, and each row is deleted before its
command runs.

**Does the 01:00 slot ever run twice or get skipped around midnight or a clock change?** No. It belongs to the previous
rotation day, and times are resolved in Europe/London (in a DST gap the time moves forward; in an overlap the earlier
instant is used).

**I upgraded from 1.0.0. What happens to my data?** On the first start, 1.1.0 copies `data.db` to
`data.db.v1-backup-<yyyyMMdd-HHmmss>` in the plugin folder (a consistent copy that includes unsaved WAL contents) and
logs `Backed up data.db to data.db.v1-backup-<ts> before the schema v2 migration.` It then migrates the file in place to
schema v2 in one transaction and logs `Database migrated to schema v2 (<n> event record(s) kept as whole-day records).`
Every event, score, trophy, key win and pending reward is kept with the same ids. Days recorded before the upgrade count
as complete for both sessions of that day. If the backup can't be written, nothing is migrated and the plugin disables
itself. Later starts and reloads make no new backup.

**How do I roll back to 1.0.0?** Stop the server, put the 1.0.0 jar back, replace `data.db` with the
`data.db.v1-backup-*` file and delete `data.db-wal` and `data.db-shm`. Anything recorded after the upgrade is lost.
Don't skip the restore: 1.0.0 refuses to start on a v2 file that has two events on one day.

**I switched to two a day mid-season and see MISSED warnings for past days.** Past days that already had a session-1
record get their session-2 window checked, and those windows are over, so each is recorded as MISSED with one WARNING
line per day. This is harmless: nothing is paid or broadcast. Switching before the first start of 1.1.0 avoids it,
because pre-upgrade days are whole-day records.

**Does it clash with CraftedEvents fishing events?** No. Both plugins can count the same fish independently.

## Building

```
mvn clean package
```

This produces `target/CraftedPirateCatch-1.0.0.jar` and runs the unit tests (rotation, reward allocation, loot rolls,
SQLite storage). Nothing is shaded.
