# Third-party notices

This project's own code is original. One piece of algorithmic logic —
not literal source — is adapted from RuneLite and is credited here per
the terms of its BSD 2-Clause License.

## Slayer task resolution (SlayerCollector.java)

`SlayerCollector`'s method of resolving the current Slayer task
(reading `VarPlayerID.SLAYER_COUNT`/`SLAYER_TARGET`/`SLAYER_AREA`/
`SLAYER_COUNT_ORIGINAL`, resolving the boss-task sentinel via
`SLAYER_TARGET_BOSSID` and the `SlayerTaskSublist` DB table, and
resolving task/area names via the `SlayerTask`/`SlayerArea` DB tables)
is adapted from the algorithm in RuneLite's core Slayer plugin:

`net.runelite.client.plugins.slayer.SlayerPlugin`
https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/slayer/SlayerPlugin.java

> Copyright (c) 2017, Tyler <https://github.com/tylerthardy>
> Copyright (c) 2018, Shaun Dreclin <shaundreclin@gmail.com>
> All rights reserved.
>
> Redistribution and use in source and binary forms, with or without
> modification, are permitted provided that the following conditions are met:
>
> 1. Redistributions of source code must retain the above copyright notice, this
> list of conditions and the following disclaimer.
> 2. Redistributions in binary form must reproduce the above copyright notice,
> this list of conditions and the following disclaimer in the documentation
> and/or other materials provided with the distribution.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
> ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
> WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
> DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
> ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
> (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
> LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
> ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
> (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
> SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

`SlayerCollector.java` in this project is independently written — it
does not include the original file's overlays, infobox, chat command,
highlighting, or UI code (none of which telemetry needs), and its
Java source is not copied line-for-line. What is carried over is the
*approach* to reading Slayer state (which varps/varbits/DB tables to
read and in what order), which is why attribution is given here
rather than silently reimplementing it as if independently derived.

Everything else in this project uses RuneLite's public plugin API
(`Client`, event classes, etc.) in the ordinary way any third-party
plugin must — that is normal use of a published API under RuneLite's
BSD 2-Clause license and plugin development terms, not "RuneLite's
code" in the sense this notice is concerned with.

## Potion storage extraction (PotionStorageCollector.java)

`PotionStorageCollector`'s method of reading potion storage contents
(building an item-id-to-potion-enum map from `EnumID.POTIONSTORE_POTIONS`
and `EnumID.POTIONSTORE_UNFINISHED_POTIONS`, reading the dynamic
children of the `InterfaceID.Bankmain.POTIONSTORE_ITEMS` widget in
groups of five, and parsing the "Doses: N" / "Quantity: N" text) is
adapted from the data-reading half of
`BankPlugin#getPotionStoragePrice()` in:

`net.runelite.client.plugins.bank.BankPlugin`
https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/bank/BankPlugin.java

> Copyright (c) 2018, TheLonelyDev <https://github.com/TheLonelyDev>
> Copyright (c) 2018, Jeremy Plsek <https://github.com/jplsek>
> Copyright (c) 2019, Hydrox6 <ikada@protonmail.ch>
> Copyright (c) 2024, PhraZier <https://github.com/phrazier>
> All rights reserved.
>
> Redistribution and use in source and binary forms, with or without
> modification, are permitted provided that the following conditions are met:
>
> 1. Redistributions of source code must retain the above copyright notice, this
> list of conditions and the following disclaimer.
> 2. Redistributions in binary form must reproduce the above copyright notice,
> this list of conditions and the following disclaimer in the documentation
> and/or other materials provided with the distribution.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
> ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
> WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
> DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
> ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
> (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
> LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
> ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
> (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
> SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Only the data-reading portion is mirrored — the price-calculation
logic (GE/high-alch value totals, title-bar text formatting) is not
reproduced, since telemetry has no use for it. The dose-variant
fallback quirk (defaulting to dose-4 when the withdrawal loop doesn't
match doses 1-3) is kept faithfully as observed in the original,
rather than "corrected," since the goal is accurately mirroring
real game/plugin behavior, not second-guessing it.

## Slayer task-family target names (SlayerTaskFamilyRegistry.java)

`SlayerTaskFamilyRegistry`'s task-name-to-target-name-alias DATA (e.g.
Pyrefiends' own "Flaming pyrelord" alias, Gargoyles' "Dusk"/"Dawn",
Blue dragons' "Vorkath") is copied and adapted from the task/target
table in RuneLite's own Slayer plugin:

`net.runelite.client.plugins.slayer.Task`
https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/slayer/Task.java

> Copyright (c) 2017, Tyler <https://github.com/tylerthardy>
> Copyright (c) 2018, Shaun Dreclin <shaundreclin@gmail.com>
> All rights reserved.
>
> Redistribution and use in source and binary forms, with or without
> modification, are permitted provided that the following conditions are met:
>
> 1. Redistributions of source code must retain the above copyright notice, this
> list of conditions and the following disclaimer.
> 2. Redistributions in binary form must reproduce the above copyright notice,
> this list of conditions and the following disclaimer in the documentation
> and/or other materials provided with the distribution.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
> ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
> WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
> DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
> ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
> (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
> LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
> ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
> (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
> SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Unlike the two adaptations above, this one copies literal STRING DATA
(task display names and their target-name aliases), not just an
algorithmic approach -- `Task` itself is package-private inside
RuneLite's own `slayer` plugin package and is deliberately not
depended on directly (no reflection, no runtime dependency on another
plugin's internals). `SlayerTaskFamilyRegistry` also independently
reproduces (verified by direct source read of
`SlayerPlugin.rebuildTargetNames()`) the one small piece of matching
*logic* RuneLite itself applies alongside that data: pairing every
task's explicit target-name aliases with the task's own display name
with a single trailing "s" stripped. No RuneLite item-sprite IDs,
weakness data, chat-command, overlay, or UI code is reproduced --
telemetry has no use for any of that.

ADDENDUM (Slayer superior-coverage pass): a further set of entries in
this same file (the missing Superior slayer monster aliases -- Elder
aquanite, Monstrous basilisk, Basilisk Knight/Basilisk sentinel, and
similar) are NOT from RuneLite's `Task` table above -- direct source
inspection during that pass confirmed RuneLite's own `Task` enum does
not (yet) list them. Those entries are instead plain game-mechanic
FACTS (which normal monster a given Superior slayer monster spawns
from, and which existing Slayer task family that monster already
belongs to) sourced from the Old School RuneScape Wiki's own
"Superior slayer monster" article and each paired monster's own
infobox. Facts of this kind (a monster's name and which task counts
it) are not copyrightable expression, so no separate license
attribution applies to them the way it does to RuneLite's own table
above.

## Farming patch tables and capture guards (farming/*)

Spyglass's Farming foundations (`FarmingPatchCatalog`, `FarmingProduce`,
`FarmingDecoderTable`, `FarmingPatchType`, `FarmingObserver`, `CalibrationSample`,
`FarmingCalibration`) derive data
and adapt algorithms from RuneLite's core Time Tracking plugin, read at
RuneLite `master` commit `e98399f931c6` (2026-10-14):

- `net.runelite.client.plugins.timetracking.farming.FarmingWorld`: farming
  area region ids, alias ("extra") regions, transmit-varbit assignments and
  the Falador / Port Sarim, Catherby and Fossil Island bounds rules.
- `net.runelite.client.plugins.timetracking.farming.PatchImplementation`:
  the raw varbit value ranges for every supported patch type (herb, allotment,
  flower, tree, fruit tree, bush, hops and the special types;
  `FarmingDecoderTable` is generated from it) and each type's health-check flag.
- The same `PatchImplementation` (COMPOST and BIG_COMPOST) and `FarmingWorld`
  bin entries: the compost bins, their transmit varbits and value ranges
  (`CompostBin`, canonical §28.15; added 2026-10-22). The bins' object ids,
  tiles, value ranges and Close op counts were then corrected from the game
  cache as published by two data repositories that carry no licence file and
  from which only facts are used (no file is copied): osrs-dumps
  (github.com/Joshua-F/osrs-dumps, commit `6e5033af`: loc multilocs, varbits,
  client scripts) and mejrs data_osrs (github.com/mejrs/data_osrs, commit
  `6a3ca6f1`: object placements) (canonical §28.16; added 2026-10-22).
- `net.runelite.client.plugins.timetracking.farming.CompostTracker`
  (Copyright (c) 2022 LlemonDuck, BSD-2): the compost message patterns (applied,
  Fertile Soil, already treated, Inspect) and the rule that the message is
  credited to the patch chosen with a compost item, Fertile Soil or Inspect
  within 30 seconds (`PatchCompost`, `FarmingCaptureAdapter`; added 2026-10-22).
- `net.runelite.client.plugins.timetracking.farming.Produce`: growth tick
  minutes, growth stages, regrow minutes and harvest stages.
- `net.runelite.client.plugins.timetracking.farming.FarmingTracker` and
  `net.runelite.client.plugins.timetracking.TimeTrackingPlugin`: the capture
  guards (no reads while a modal is open, the post-login Welcome screen,
  same-region settling), the rules for recognising an observed growth tick,
  and the growth-boundary / farm-tick offset model `FarmingCalibration`
  adapts (Spyglass learns its own offset from its own samples).

No RuneLite file is copied verbatim and nothing from the Time Tracking
plugin is used at runtime: Spyglass reads no Time Tracking configuration and
imports nothing from its package.

> Copyright (c) 2018 Abex
> Copyright (c) 2018, NotFoxtrot <https://github.com/NotFoxtrot>
> Copyright (c) 2018, Daniel Teo <https://github.com/takuyakanbr>
> Copyright (c) 2019 Abex
> All rights reserved.
>
> Redistribution and use in source and binary forms, with or without
> modification, are permitted provided that the following conditions are met:
>
> 1. Redistributions of source code must retain the above copyright notice, this
> list of conditions and the following disclaimer.
> 2. Redistributions in binary form must reproduce the above copyright notice,
> this list of conditions and the following disclaimer in the documentation
> and/or other materials provided with the distribution.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
> ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
> WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
> DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
> ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
> (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
> LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
> ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
> (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
> SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Transport arrival tiles and account readers (travel/*, account/*)

Spyglass's Phase F transport catalogue
(`src/main/resources/com/osrstelemetry/plugin/travel/transports/*.json`) and
account readers (`account/AccountReaders`) use facts read from RuneLite
`master` commit `e98399f931c6` (2026-10-14):

- `net.runelite.client.plugins.worldmap.TeleportLocationData` (Copyright (c)
  2018, Morgan Lewis): arrival tiles of spells, tablets and teleport items,
  including the house portal and portal nexus destinations (`poh.json`).
- `net.runelite.client.plugins.worldmap.FairyRingLocation` (Copyright (c)
  2018, Morgan Lewis): fairy-ring arrival tiles.
- `net.runelite.client.plugins.worldmap.TransportationPointLocation`
  (Copyright (c) 2019, Kyle Sergio; Copyright (c) 2019, Bryce Altomare;
  Copyright (c) 2019, Kyle Stead): fixed spirit-tree tiles, and the quetzal
  landing tiles used as quetzal whistle arrivals (`teleport-items.json`, added
  2026-10-22; cross-checked against Shortest Path's `quetzal_whistle` and
  `teleportation_items` tables @142e73e2, BSD-2).
- `net.runelite.client.plugins.worldmap.FarmingPatchLocation` (Copyright (c)
  2018, Torkel Velure): player-grown spirit-tree tiles.
- `net.runelite.client.plugins.fairyring.FairyRing` (Copyright (c) 2018, Yoav
  Ram; Copyright (c) 2018, Infinitay): fairy-ring destination names.
- `net.runelite.client.plugins.dailytaskindicators.DailyTasksPlugin`
  (Copyright (c) 2018, Infinitay; Copyright (c) 2018-2019, Shaun Dreclin): the
  rule that a `*_DIARY_*_COMPLETE` varbit of 1 means the tier is complete.
- `net.runelite.client.plugins.runepouch.RunepouchOverlay` (Copyright (c)
  2017, Tyler; Copyright (c) 2022, Adam): reading the pouch through
  `RUNE_POUCH_TYPE_n` / `RUNE_POUCH_QUANTITY_n` and the `RUNEPOUCH_RUNE` enum.

Only coordinates, names and reading rules are used; no RuneLite file is
copied and none of these plugins is used at runtime. These files are
distributed under the same BSD 2-Clause terms reproduced above. Spell levels,
rune costs, quest requirements, daily limits and charge behaviour were
checked against the Old School RuneScape Wiki (facts only, no text copied).
Shortest Path data was not used.

## Quest Helper facts and RuneLite client scripts (account/*, farming/*, prep/*)

Phase F's gap closure and Phase H use facts (not code) read from Quest Helper
(`Zoinkwiz/quest-helper` master @ `5f79193`, BSD 2-Clause, Copyright (c)
2020, Zoinkwiz): the spellbook varbit values (`requirements.util.Spellbook`),
the diary reward varbits used for Karamja easy / medium / hard
(`questinfo.QuestVarbits`), the fairy-ring staff variants
(`collections.ItemCollections.FAIRY_STAFF`), and the herb run's patch access
requirements and seed-dibber / rake unlock conditions
(`helpers.mischelpers.farmruns.HerbRun`). Quest Helper is not used at runtime
and nothing from it is imported, read or depended on.

From RuneLite `master` @ `e98399f931c6` (BSD 2-Clause, terms above): the
spellbook layout in `MagicSpellbookRedraw.rs2asm` and the bank tab layout in
`BankMainBuild.rs2asm` (game client scripts as shipped in RuneLite), and the
search-assist approach of `plugins.bank.BankSearch` (used through RuneLite's
public client classes).

> BSD 2-Clause License
>
> Copyright (c) 2020, Zoinkwiz
> All rights reserved.
>
> Redistribution and use in source and binary forms, with or without
> modification, are permitted provided that the following conditions are met:
>
> 1. Redistributions of source code must retain the above copyright notice, this
> list of conditions and the following disclaimer.
> 2. Redistributions in binary form must reproduce the above copyright notice,
> this list of conditions and the following disclaimer in the documentation
> and/or other materials provided with the distribution.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
> ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
> WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
> DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
> ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
> (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
> LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
> ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
> (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
> SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Home: Kingdom of Miscellania and daily claims (home/*)

`home/Kingdom` and `home/DailyTask` / `home/Dailies` use facts and adapt rules
from two RuneLite core plugins, read at RuneLite `master` on 2026-10-28; nothing
is copied and neither plugin is used at runtime (Spyglass reads the game's own
variables itself and none of these plugins' saved state):

- `net.runelite.client.plugins.kingdomofmiscellania.KingdomPlugin`
  (Copyright (c) 2018, Infinitay): the kingdom regions (10044, 10300), the
  variables `MISC_COFFERS`, `MISC_APPROVAL` and `MISC_QUEST`, the approval
  scale of 127 and its percentage (points × 100 / 127). The decay rules
  themselves are taken from the OSRS Wiki ("Managing Miscellania").
- `net.runelite.client.plugins.dailytaskindicators.DailyTasksPlugin`
  (Copyright (c) 2018, Infinitay; Copyright (c) 2018-2019, Shaun Dreclin): which
  daily claims exist, each claim's variable, its unlock (diary tier, The Hand in
  the Sand at 160, the ironman types) and Robin's 13 uses per Morytania tier.

The Home page's "Join Spyglass Discord" button draws RuneLite's own Discord
mark (`net/runelite/client/plugins/info/discord_icon.png`), loaded at runtime
from the RuneLite client that runs Spyglass; the image is not copied into this
project. RuneLite's licence terms are given above.

## World navigation data (navigation/*)

Bundled under `src/main/resources/com/osrstelemetry/plugin/navigation/`, from
the Shortest Path RuneLite plugin, https://github.com/Skretzo/shortest-path,
commit `142e73e2a5f840bb16e227e564a036156f4cfc77` (BSD 2-Clause, as published
in that repository; its LICENSE file names no individual copyright holder, so
the project and its contributors are credited): the world collision map
`collision-map.zip`; the transport tables `transports.tsv`,
`agility_shortcuts.tsv`, `boats.tsv`, `ships.tsv`, `quetzals.tsv`,
`magic_mushtrees.tsv`, `hot_air_balloons.tsv`, `fairy_rings.tsv`, `spirit_trees.tsv` and
`item-variations.tsv` (unmodified copies); the format of the collision map and
the requirement grammar of the tables, read by Spyglass's own
`WorldCollision`, `TransitionCatalog` and `NavRequirement`; and the Wilderness
bounds in `Wilderness.java`. `routes.json` is generated by Spyglass from these
files; `spyglass_transitions.tsv` is Spyglass's own data. The world network
entrance tiles in `travel/network-nodes.json` are the origins of
`fairy_rings.tsv` and `spirit_trees.tsv`. Per the project's `CONTRIBUTING.md`,
`collision-map.zip` is generated from the game cache by the project's tooling.
The plugin's listed authors are Skretzo, FIrgolitsch and mpickering. No Shortest
Path code is included. Provenance per file, hashes and the update procedure:
`research/NAVIGATION_DATA.md`. In `spyglass_transitions.tsv`, the Hespori cave
entrance tile comes from RuneLite's `DungeonLocation.HESPORI` (RuneLite, BSD
2-Clause, terms above); object ids, options and requirements from the OSRS Wiki.

> BSD 2-Clause License
>
> Copyright (c) Shortest Path (Skretzo) and contributors
> All rights reserved.
>
> Redistribution and use in source and binary forms, with or without
> modification, are permitted provided that the following conditions are met:
>
> 1. Redistributions of source code must retain the above copyright notice, this
> list of conditions and the following disclaimer.
> 2. Redistributions in binary form must reproduce the above copyright notice,
> this list of conditions and the following disclaimer in the documentation
> and/or other materials provided with the distribution.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
> ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
> WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
> DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
> ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
> (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
> LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
> ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
> (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
> SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
