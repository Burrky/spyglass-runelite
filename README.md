# Spyglass

Spyglass is a local RuneLite activity and loot tracker built around three
player-facing views: **Session**, **Loot**, and **History**.

Everything Spyglass records stays on your own computer. It does not make
network requests, and it does not automate or assist gameplay in any way —
see [Data / Security](#data--security) below.

## Features

### Session

The Session tab shows what you're doing right now:

- your current activity (skilling, Slayer, bossing, and more), identified
  automatically as you play
- live XP and XP/hr for the skill you're training
- Slayer task progress, including task identity and kills remaining
- boss and combat activity tracking where RuneLite can observe it
- live session metrics — duration, XP gained, and loot collected so far

If you step away or switch to something else, Spyglass notices and quietly
picks the session back up (or starts a new one) without you having to do
anything.

### Loot

The Loot tab is a persistent record of everything you've picked up, grouped
by source (NPC or activity):

- **Grouped** and **Individual** views, so you can see totals per source or
  every drop individually
- item search across your whole loot history
- **Favorites**, so you can pin the sources you care about to the top
- Grand Exchange and High Alch valuation, configurable in settings
- kill/source counts where RuneLite exposes them

Loot Tracker data survives client restarts — it's rebuilt from your local
history each time you log in, not re-collected from scratch.

### History

The History tab is a browsable list of your recent finalized activities:

- active duration and when each session happened
- XP gained during that session
- the loot collected during that session
- your starting loadout (equipment and inventory) for that session
- a click-through detail view for any recent entry

History shows a recent window of activity rather than every raw event —
see [Known Limitations](#known-limitations).

## Privacy / Local Data

- Spyglass makes no custom network requests. It does not upload your data
  anywhere.
- All data is stored locally under RuneLite's own configuration directory,
  scoped to your account using RuneLite's account hash (not your display
  name, so a name change never orphans your history):

  ```
  .runelite/plugin-data/spyglass/<accountHash>/
  ```

  (Older installs used `.runelite/osrs-telemetry/<accountHash>/` — RuneLite
  itself moves this automatically, once, the first time you run an
  updated Spyglass; nothing is lost and no action is needed.)

- Typical stored information includes your activity/session records, XP,
  loot, and inventory/equipment/storage snapshots — the same kind of data
  RuneLite's own built-in trackers already keep, just organized around
  Spyglass's Session/Loot/History views.
- Retention today: bank snapshots are automatically bounded and pruned to
  stay under a fixed local size. Your event history and finalized session
  records are currently kept locally without automatic deletion.

## Installation

Install Spyglass from the RuneLite Plugin Hub.

*(Once approved, Spyglass will be available through the RuneLite Plugin
Hub. Until then, see the developer build note below.)*

<details>
<summary>Building from source (developers)</summary>

```
git clone <repository URL>
cd spyglass
./gradlew build
```

This is not the recommended way to install Spyglass for normal play —
use the Plugin Hub once Spyglass is available there.
</details>

## Configuration

Spyglass's settings panel lets you:

- choose whether loot is valued and sorted by **Grand Exchange** or
  **High Alch** price
- toggle which categories of data are collected (skills, inventory/
  equipment, bank, seed vault, potion storage, group storage, quests,
  Slayer, collection log, combat achievements, boss/activity kill counts,
  loot, and NPC deaths) — every toggle is honest and does exactly what it
  says, with no hidden behavior behind an unrelated setting

## Known Limitations

- History shows a recent window of finalized activities rather than every
  raw telemetry event ever recorded.
- Some counts and signals depend on what RuneLite itself can observe for a
  given activity, so coverage can vary between activity types.
- Local event and session data currently has no automatic lifetime
  deletion (see Privacy / Local Data above).

## Data / Security

- Observational only — Spyglass reads game state, it never acts on your
  behalf.
- No gameplay automation of any kind.
- No click or input automation.
- No remote backend — everything stays on your machine.

## Bug Reports / Feedback

Please use the GitHub Issues page once the public repository is available.

## License

Spyglass is licensed under the BSD 2-Clause License. See [LICENSE](LICENSE)
for the full text. Third-party attributions are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
