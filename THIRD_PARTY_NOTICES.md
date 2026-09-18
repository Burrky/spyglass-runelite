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
