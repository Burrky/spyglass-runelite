# Spyglass

**The all-in-one activity tracker for RuneLite.** Track sessions, loot, XP,
loadouts, Slayer, bosses, minigames, and detailed activity history — all
automatically, all stored locally on your computer.

Spyglass works in three tabs: **Session**, **Loot**, and **History**. There is
nothing to start or stop — play normally and Spyglass recognizes what you are
doing.

## Features

### Session — what you're doing right now

- **Automatic activity detection** for skilling, combat, Slayer, bosses and
  supported minigames, including picking a session back up when you return
  from the bank or a short break.
- **XP** per skill with level progress and XP/hr. Rates appear after five
  minutes of activity, so a single large XP drop can't project an unrealistic
  hourly rate; totals are always exact.
- **Loot** for the current session, valued at Grand Exchange or High Alch
  prices.

**Smart Slayer**

- Your current task with kills remaining, progress, and an estimate of the
  Slayer XP left.
- Kills, kills/hr and loot per kill for the task; reward points, task streak
  and when your next bonus task is.
- Slayer master, Konar task location and Mortimer task modifiers when the game
  reports them, plus Bracelet of slaughter / Expeditious bracelet effects.
- **Task History**: every task of the session with its own loot, XP and kills.
  XP can be shown for all tasks or only the current task.
- Bosses killed on a matching task (for example Kraken on a Cave kraken task)
  stay part of the same Slayer session.

**Minigames and activities**

- **Guardians of the Rift** — the current game (guardian power, elemental and
  catalytic energy), games, games/hr, reward points earned, average and best
  game points, and an exact **Runes crafted** count. Rewards from the Rewards Guardian
  are tracked as loot, never as runes you crafted.
- **Tempoross** — the game's own energy, essence and storm intensity, games,
  permits earned, and average / best points.
- **Barrows** — the brothers you've killed this run and chests opened.
- **Wintertodt, Pest Control and Giants' Foundry** — tracked as one session
  across many games, with a games count where the game reports one.

### Loot — everything you've picked up

- **Grouped** view: totals per source (NPC, boss, or activity reward), with the
  kill count where RuneLite reports a reliable one.
- **Individual** view: your drops in the order they actually happened, newest
  first, each labelled with its source.
- Search across all sources, favorites, hide items you don't care about, and
  Grand Exchange / High Alch values.
- Right-click a source to reset its displayed totals. Your recorded history is
  never deleted.
- Loot from the past year is rebuilt from your local data each time you log in.

### History — your recent sessions

- Every finished session from the last 10 days, newest first.
- Open a session to see its duration, activity summary, XP, loot, items or
  runes produced, and the loadout (equipment and inventory) you started with.
- Slayer sessions list each task; Guardians of the Rift and Tempoross show
  their own session summary.

## Settings

Settings change how things are shown, never what is recorded:

- **General** — *Loot valuation*: value and sort loot by Grand Exchange or High
  Alch price.
- **Current Session** — *XP / Loot order*: which section comes first (Task
  History counts as Loot during Slayer); *Slayer XP scope*: all tasks or only
  your current task (also switchable from the XP section itself).

To stop all tracking, disable the plugin.

## Privacy / Local Data

- Spyglass does not send your data anywhere. It makes no network requests of
  its own, and it never automates or assists gameplay.
- Everything is stored locally under RuneLite's own directory, per account
  (by RuneLite's account hash, so a name change never orphans your history):

  ```
  .runelite/plugin-data/spyglass/<accountHash>/
  ```

  (Older installs used `.runelite/osrs-telemetry/<accountHash>/`; RuneLite
  moves it automatically the first time an updated Spyglass runs.)

- Stored data includes your activity sessions, XP, loot, and inventory /
  equipment / storage snapshots — the same kind of data RuneLite's own trackers
  keep.
- Bank snapshots are automatically capped in size. Session records and event
  history are kept locally without automatic deletion; the tabs show a recent
  window (History: 10 days, Loot: one year).

## Installation

Install Spyglass from the RuneLite Plugin Hub.

<details>
<summary>Building from source (developers)</summary>

```
git clone <repository URL>
cd spyglass
./gradlew build
```

</details>

## Known Limitations

- Coverage depends on what RuneLite can observe for each activity, so some
  counts and details vary between activities.
- Purpose-built tracking exists for the activities listed above; other
  skilling and minigames are tracked as general sessions (XP, loot, duration).
  Fight Caves / Inferno wave tracking is not included yet.
- Reward chests or caskets opened away from their activity are not attributed
  back to it.

## Bug Reports / Feedback

Please use the GitHub Issues page of the public repository.

## License

Spyglass is licensed under the BSD 2-Clause License. See [LICENSE](LICENSE)
for the full text. Third-party attributions are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
