# CraftedPirateCatch: design

**Status:** design, awaiting approval · **Date:** 2026-10-31 · **Owner:** Sam (SamsSide)
**Server:** Crafted Survival, Red SMP (UniverseSpigot / Paper API **26.2**, Java **25**), behind a Velocity proxy, with Bedrock via Geyser/Floodgate.

## 1. Intent: the why

November 2026 is the **Crafted Pirate Crate** month. Sam wants a small daily micro-event that gets players logging in and
playing together, and that gives away a Pirate Crate key a day (about 30 a month, which Sam is fine with).

**Pirate's Catch** is a one-hour competitive fishing race that runs once a day on the spawn island. Players rush to the
pirate fishing spot, fish against each other on a live leaderboard, and the top eligible fisher wins a Crafted Pirate Crate key.

The event must be **fair to players in every timezone**. Some players only play at certain times, so the start time
changes every day and nobody is always left out. Last week's Red SMP logs (24 to 30 Oct, 350 players) show two big
audiences: the UK/EU afternoon and evening, and a strong US crowd after midnight UK.

## 2. Goals
- A daily one-hour fishing race at a fixed spot on the spawn island, starting at a **different time each day** on a rotation.
- Exciting and competitive: a live leaderboard and visible rivals at the dock. Regulars have the edge, but a late joiner can
  still snipe a win with one lucky rare catch.
- One **Crafted Pirate Crate key** a day to the winner. Shards for runners-up and participants, so turning up always pays.
- Fair distribution of keys (see the cooldown and minimum-turnout rules).
- Runs by itself every day with no staff action. Staff can still intervene.
- **Live ASAP**: aim for the first days of November. A lean first version is fine.

## 3. Non-goals (not in v1)
- Re-theming for other months. It's **pirate-only for November**, by decision. Don't build a generic theme engine, but
  keeping text and loot in config is still expected and cheap.
- Fishing anywhere in the world. Only the spawn dock counts.
- Tradeable or sellable loot. Pirate catches are **points**, not economy items, apart from the trophies.
- Any change to CraftedEvents, the weekly event plugin. This is a separate plugin and must not interfere with it.

## 4. Anti-goals (a failure even if it "works")
- The same one or two players winning most of the month's keys.
- A key won unopposed at a quiet hour.
- An AFK fishing farm or an auto-fisher winning.
- Damage to the economy: pirate catches turned into something sellable, or duped trophies.
- Lag or main-thread database work during a busy hour at spawn.
- Cheapening the Pirate Crate: trophies must not be items that are crate cosmetics (see 6.6).

## 5. Constraints and existing environment
- **Standalone plugin**, working name **CraftedPirateCatch**, in Sam's Crafted plugin style. The closest model is
  **CraftedEvents** (`SamsSide/CraftedEvents`): SQLite with a bundled shaded driver, an in-memory leaderboard cache for
  PlaceholderAPI, ties broken by who reached the score first, "must score ≥1 to win", state that survives restarts, and an
  optional Discord webhook. Follow those patterns.
- Build: Maven, `paper-api [26.2.build,)`, Java 25 (match CraftedCosmetics' pom).
- Plugins on Red SMP that this hooks into or relies on:
  - **WorldGuard 7.0.19**: the dock is a WorldGuard region; only catches inside it count.
  - **PlaceholderAPI**: hard dependency, for the hologram leaderboard.
  - **FancyHolograms**: Sam builds the hologram at the dock from our placeholders.
  - **ItemsAdder 4.0.18**: trophies are ItemsAdder items, given with `iagive <player> <id> 1 silent`.
  - **ExcellentCrates 6.6.1**: key command `crates key give <player> crafted_pirate 1 -s`.
  - **CraftedCratesAddon**: shards are the ItemsAdder item `craftedsurvival:shard`. Its configured give command is
    `iagive %player% craftedsurvival:shard %amount% silent`.
- All rewards are **console commands in config**, so the key, shard and trophy commands can change without code changes.
- Server time is UTC. Slot times are **UK time (Europe/London)**, so the rotation follows UK clock changes.
- The server restarts daily around **06:05 UK** for backups. No slot is near that.
- Bedrock players (Geyser) must be able to take part: plain vanilla fishing, chat, boss bar and holograms only. No
  Java-only UI is required to play.

## 6. Decided behaviour (Sam's decisions)

### 6.1 Schedule: rotating slots
- One event a day, **one hour long**.
- Start times rotate through **13:00 → 17:00 → 21:00 → 01:00 UK**, moving one step each day, so each slot comes round every
  4 days. These come from real player data:
  - 13:00: UK/EU daytime and weekends (about 22 online)
  - 17:00: the peak (about 31 online)
  - 21:00: UK evening and US afternoon (about 24 online)
  - 01:00: the US evening crowd (about 25 online)
- **The 01:00 slot is the late session of its own rotation day** (Sam's decision). On rotation day 4, the event runs at
  01:00 in the early hours *after* that day, i.e. at 01:00 on the next calendar date. This gives gaps of 28h, 28h, 28h and 12h.
  So a "rotation day" is not the same as a calendar date. "Today's event", "skip today", the missed-slot rule, `/pirate` and
  the next-start placeholder all work on rotation days, never calendar dates.
- **Active date window** (my call, configurable): events only run between a configured first and last rotation day.
  Defaults: first day = the day it's installed on Red SMP; last day = **30 Nov 2026**, so its 01:00 or later slot can fall in
  the early hours of 1 Dec. After the window, nothing is scheduled and `/pirate` says the event has ended. It's pirate-only for
  November, so no Pirate keys go out in December.
- Warnings before the start at 15 and 5 minutes (my call: also 1 minute). Then a start broadcast, and a **boss bar** with the
  time remaining during the hour.

### 6.2 Where
- A themed **fishing spot on the spawn island** (Sam builds it). It's defined as a **WorldGuard region** named in config.
  Only fish caught while the player is inside the region count, and only while the event is running.

### 6.3 Scoring
- During the hour, every successful catch inside the region rolls on a **pirate loot table**, ordered by rarity. A pirate
  catch adds **points**.
- Normal vanilla fishing loot still drops as usual.
- Points by rarity are what win, not the number of fish. Regulars have the edge, and a late joiner can still snipe a win
  with one rare catch.
- Ties go to whoever reached the score first.

### 6.4 Rewards (all console commands in config)
- **1st:** one **Crafted Pirate Crate key**, given to the best *eligible* player (see 6.5).
- **2nd and 3rd:** shards (my call: 3 and 2).
- **Participation:** everyone who landed at least one pirate catch gets shards (my call: 1).
- **Trophies:** rare tiers also give a keepable trophy item (see 6.6).
- **How the key and placings combine** (my call). Placings are by raw score. Placement shards (my call: 1st 5, 2nd 3,
  3rd 2) go by raw placing. The one exception is the player who receives the key: they get the key *instead of* their
  placement shards, and the shards are not re-assigned. The key goes to the highest raw-placed **eligible** player (6.5).
  So a top scorer on cooldown keeps the 1st-place 5 shards, and, for example, the 2nd-placed player gets the key instead of
  their 3 shards. If no eligible player scored, no key is given that day. Participation shards go to everyone with ≥1 pirate
  catch who got no other reward.

### 6.5 Fairness rules
- **Key cooldown:** a player who won the key in the **last 3 days** can still take part and win shards, but the key goes to
  the next-best eligible player. A rolling 72 hours from their last key win.
- **Minimum turnout:** the key is only awarded if **3 or more players** landed at least one pirate catch. Otherwise the event
  still runs and finishes, and nobody gets the key; placement shards are paid as normal.
- **Alt protection for turnout** (Sam's decision): players are counted towards the 3-player minimum by **distinct IP
  address**. Players sharing an IP all still play, score, place and get rewards normally; they just count as one for the
  turnout check. Use the real client IP as forwarded by Velocity (including Floodgate/Bedrock players).
- **Must score:** nobody can win with 0 points (inherited from CraftedEvents).

### 6.6 Trophies
These are display items from packs Sam already owns that are **not** Pirate Crate cosmetics:
- `bee_pirate:pirate_parrot`
- `bee_pirate:kraken_purple` (the seafoam Kraken is the crate balloon, so don't use it)
- `pirate_treasure_new:pirate_treasure_new_chest` (placeable furniture)

**Trophy cap** (Sam's decision): at most **one trophy of each tier per player per event**. The first Rare gives the Parrot,
the first Epic the Kraken, and the first Legendary the Chest. Later catches of the same tier in that event still score points
but give no further trophy. This is tracked per event, and needs no long-term per-player record.

**Never** use `bee_pirate:shark_head`, `pirate_hat`, `pirate_bandana`, `sailor_cap`, `kraken_seafoam`, `cutlass`,
`flintlock_pistol` or `anchor_pickaxe`. They're crate rewards.

### 6.7 Default loot table (my call; tune in config)
Per qualifying catch, roll once:

| Tier | Example name | Chance | Points | Extra |
|---|---|---|---|---|
| none | (just a normal fish) | 55% | 0 | |
| Common | Doubloon | 25% | 1 | |
| Uncommon | Message in a Bottle | 12% | 3 | |
| Rare | Captain's Compass | 6% | 8 | trophy: Pirate Parrot |
| Epic | Kraken Tentacle | 1.7% | 20 | trophy: Purple Kraken |
| Legendary | Sunken Treasure Chest | 0.3% | 50 | trophy: Treasure Chest; server-wide broadcast |

A pirate catch shows a chat line and a sound to the catcher (Legendary is broadcast to everyone).

## 7. Commands, placeholders and integrations (my call)
- **Players:** `/pirate` (alias `/piratecatch`) shows the next start time in UK time plus a relative countdown, live or
  "starts in X". During the event it also shows your score and rank, and the top 5.
- **Staff** (my call): permissions are under `craftedpiratecatch.*`.
  - **start now:** runs **today's** event early, under the normal rules, and uses up today's slot. It refuses if today's event
    already ran, so manual starts can never push the month past one event a day.
  - **stop:** ends the running event early and awards normally.
  - **cancel:** ends it with no rewards.
  - **skip today.**
  - **test start:** a separate **test mode** with no rewards. It doesn't use a slot and isn't recorded for cooldowns; it's for
    Testing and checking the dock.
  - **reload.**
  - **reset a player's key cooldown.**
  - There's no "force next slot"; the rotation only moves by rotation day.
- **Placeholders** for the dock hologram: whether it's live, time left, next start, top N names and scores, and the
  viewer's score and rank. Served from cache, like CraftedEvents.
- **Discord webhook:** optional, off until a URL is set. Posts on start and on the result.

## 8. Edge cases that must be handled
- A **restart or crash mid-event**: resume if still inside the hour; finalise and award on next startup if it ended while
  the server was down (CraftedEvents does this).
- **Missed slot:** if the server is down for a whole slot, that day's event is skipped. No double event the next day.
- The **01:00 slot** belongs to the rotation day before it (see 6.1). It must not run twice or be skipped around midnight
  or a UK clock change. No clock change happens in November 2026, but it must still compute in Europe/London time.
- A player **offline at the end**: their placement still counts, and rewards are delivered next time they join. Persist them.
- **Full inventory** for trophies or shards: drop at their feet, or queue for later (builder's choice; never lose a reward).
- **Creative/spectator** players and vanished staff don't score.
- Weekly **CraftedEvents** fishing events still count normally. Both plugins may count the same fish (my call: no
  interference wanted).

## 9. Left to the builder
- Internal architecture, class layout, schema, how rotation is anchored, and the boss bar and message styling (follow the
  house style: small caps, `&`/MiniMessage like the other Crafted plugins).
- Anti-AFK / anti-auto-fisher detail beyond "region only". A light check (for example, a player who hasn't moved or turned
  their view for N minutes stops scoring) is welcome if it's cheap.
- How hologram layout placeholders are formatted.
- Tests: follow the repo conventions of the other Crafted plugins.

## 10. Rollout
Build and test on **Testing** (`a8e7c4b4`, 26.2, has the spawn plugins but not the spawn world, so give it a test region).
Then add to Red SMP. Sam builds the dock and region, and the hologram.
