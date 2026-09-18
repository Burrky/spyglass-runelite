package com.osrstelemetry.plugin.events;

/**
 * Unlike storageId/activityId (deliberately open strings), eventType is
 * a fixed, small vocabulary defined by the telemetry schema — an enum
 * is the right tool here. Adding a new event type is a schema change
 * (bump schemaVersion), which is exactly the case where you want the
 * compiler to catch every switch/handler that needs updating.
 */
public enum EventType
{
	XP_CHANGE,
	LEVEL_UP,
	QUEST_COMPLETED,
	COMBAT_ACHIEVEMENT_COMPLETED,
	COLLECTION_LOG_NEW_ITEM,

	/**
	 * Renamed from LOOT_DROP. A read-only audit of LootManager.java's
	 * source (net.runelite.client.game.LootManager — the class behind
	 * NpcLootReceived) confirmed this event's name previously overstated
	 * what the signal proves: LootManager.onItemSpawned() adds EVERY
	 * ItemSpawned to a per-scene-tile map unconditionally, and
	 * getItemStacksFromAreas()/getDropLocations() then pulls whatever
	 * TileItem(s) are sitting on the NPC's death tile(s) within the same
	 * game tick, converting each purely by id+quantity —
	 * TileItem#getOwnership() (OWNERSHIP_SELF/OTHER/GROUP/NONE) and
	 * TileItem#isPrivate() are never read anywhere in that file. This is
	 * pure spatial (tile-matching) + temporal (same-tick, itemSpawns
	 * cleared every onGameTick()) correlation to an NPC despawn — it is
	 * NOT ownership-verified, and can genuinely include another visible
	 * player's drop occupying the same tile in the same tick.
	 *
	 * NPC_LOOT_ATTRIBUTED = RuneLite's LootManager attributed an
	 * observed item stack to an NPC (by tile + tick correlation to its
	 * despawn). It does NOT claim: the local player killed the NPC, the
	 * local player owns the item, the item is the player's personal
	 * loot, or the NPC was on the player's Slayer assignment. See
	 * NpcDeathCollector's (and this project's loot audit report's)
	 * class javadoc for the full source evidence, including why the
	 * newer net.runelite.client.events.ServerNpcLoot — which likely
	 * DOES carry real personal-loot ownership semantics, inferred from
	 * RuneLite's own LootTrackerPlugin needing to explicitly filter
	 * pickpocket loot back OUT of it (pickpocketing only ever rewards
	 * the local player, so that filtering need is itself evidence
	 * ServerNpcLoot is scoped to the local player's own loot-granting
	 * events) — was deliberately NOT switched to as this event's
	 * source: it carries no per-instance NPC location, and
	 * conflates non-death loot mechanics (pickpocketing) unless
	 * filtered, so swapping sources would trade one set of caveats for
	 * a different one rather than eliminate them. Left as future work
	 * for a possible separate, genuinely player-owned-loot event.
	 */
	NPC_LOOT_ATTRIBUTED,

	BOSS_KILL,

	/**
	 * A deliberately NON-authoritative companion to BOSS_KILL, built from
	 * net.runelite.api.events.InteractingChanged filtered to
	 * event.getSource() == client.getLocalPlayer() and
	 * event.getTarget() instanceof NPC (see BossActivityContextCollector's
	 * class javadoc for the full source-selection audit; this mirrors
	 * NpcDeathCollector's own npc.getInteracting()-style local-player
	 * interaction technique, but for a narrower and different purpose:
	 * activity CONTEXT, not kill or damage attribution).
	 *
	 * BOSS_ACTIVITY_CONTEXT = RuneLite observed the local player actively
	 * interacting with an NPC whose name resolves (via KnownBossRegistry --
	 * either a direct match against a boss name PREVIOUSLY CONFIRMED for
	 * this account by ActivityKillCountCollector's own "Your X kill count
	 * is" chat parsing -- the exact same source BOSS_KILL already trusts --
	 * or a small explicit NPC-name alias table for the disclosed
	 * multi-NPC-name case, Dawn/Dusk -> Grotesque Guardians) to a
	 * previously-confirmed boss encounter. This is activity/context
	 * evidence only.
	 *
	 * This event does NOT prove: the local player has killed, will kill,
	 * or has ever killed this specific NPC instance; a kill occurred at
	 * all; any kill count, current or otherwise (this event NEVER carries
	 * a reliableCount -- see EventPayloads.BossActivityContext and
	 * ActivitySignalClassifier's dedicated classifyBossActivityContext(),
	 * which always passes a null MetricUpdate into decideCombatBranch(),
	 * structurally guaranteeing this signal can never set
	 * SessionAggregates' reliableCount, unlike BOSS_KILL); or that the NPC
	 * being interacted with is genuinely hostile/engaged in combat at this
	 * instant (InteractingChanged fires on the interaction target
	 * changing, not on a hit landing).
	 *
	 * DIFFERS FROM BOSS_KILL: BOSS_KILL is authoritative kill-count
	 * telemetry parsed from Jagex's own "Your X kill count is: N" chat
	 * message and is the ONLY source that ever sets reliableCount.
	 * BOSS_ACTIVITY_CONTEXT is weaker, earlier, non-authoritative activity
	 * evidence whose entire purpose is to establish the correct
	 * BOSSING/&lt;boss&gt; session identity BEFORE that authoritative
	 * kill-count message arrives (e.g. a player with an assigned Slayer
	 * task who begins fighting Zulrah or the Grotesque Guardians), so
	 * that combat XP and the eventual BOSS_KILL land on the SAME session
	 * instead of a fabricated SLAYER/&lt;task&gt; session being created
	 * first and only later corrected. Routed through the existing,
	 * unmodified ActivitySignalClassifier.decideCombatBranch() -- the same
	 * refine-vs-switch-vs-ignore machinery XP_CHANGE, SLAYER_TASK_PROGRESS,
	 * and BOSS_KILL already share -- so a tracked Slayer task's SLAYER
	 * identity is correctly outranked/refined into BOSSING by this signal
	 * exactly as it already is by a real BOSS_KILL, with zero new
	 * session-lifecycle resolver code.
	 *
	 * NOT location-based: no WorldPoint or region check is used as
	 * primary proof anywhere in this signal's derivation -- see
	 * KnownBossRegistry and BossActivityContextCollector.
	 */
	BOSS_ACTIVITY_CONTEXT,

	RAID_COMPLETION,
	ACTIVITY_COMPLETION,
	SLAYER_TASK_ASSIGNED,
	SLAYER_TASK_COMPLETED,
	/**
	 * A same-identity task's amountRemaining changing. Deliberately NOT called
	 * SLAYER_KILL — Slayer mechanics can consume zero, one, or multiple
	 * task units for a single monster kill (superior spawns, some
	 * multi-count tasks, etc.), so a count-decrement is not provably
	 * equivalent to "one NPC died." This event represents observed
	 * task-count progress only, never a fabricated kill signal. See
	 * SlayerCollector's SlayerTransitionResult javadoc for the full
	 * semantic split between task IDENTITY (taskName+taskLocation) and
	 * task PROGRESS/metadata (amountRemaining and everything else).
	 */
	SLAYER_TASK_PROGRESS,

	/**
	 * Renamed from NPC_KILL. net.runelite.api.events.ActorDeath does not
	 * prove as much as the old name claimed:
	 * ActorDeath, filtered to actor instanceof NPC, proves only "RuneLite
	 * observed this NPC die" — it has no damage-attribution accessor, so
	 * it does NOT prove the local player caused the death, received kill
	 * credit, or that the dead NPC counted toward the active Slayer
	 * assignment. ActorDeath is already known (from the same prior
	 * audit) to fire for nearby NPCs killed by other players. "NPC_KILL"
	 * read as a stronger, player-attributed claim than that; "NPC_DEATH"
	 * does not.
	 *
	 * NPC_DEATH = RuneLite observed an NPC death. See
	 * NpcDeathCollector's class javadoc for the full source audit (why
	 * ActorDeath was selected over NpcDespawned — fires for many
	 * non-death despawns including boss NPC-ID phase swaps — and over
	 * both loot-based signals, which are loot-triggered, not
	 * death-triggered, so a genuine zero-drop death would never fire
	 * either one, which this event must still observe).
	 *
	 * Deliberately independent of SLAYER_TASK_PROGRESS/
	 * SLAYER_TASK_ASSIGNED/NPC_LOOT_ATTRIBUTED: this event fires purely
	 * from the death observation itself, never gated on or delayed by a
	 * Slayer counter changing or loot appearing, and never suppressed
	 * when either of those doesn't happen. See EventPayloads.NpcDeath's
	 * javadoc for why the payload deliberately omits any
	 * onSlayerTask=true/false claim, and any claim of local-player kill
	 * attribution.
	 */
	NPC_DEATH,

	BANK_SNAPSHOT,

	/**
	 * RuneLite exposes complete, reliable Seed Vault contents the same
	 * way it does the Bank: a direct fetch of
	 * net.runelite.client.plugins.bank.BankPlugin.java source confirms the Seed
	 * Vault price-total feature reads
	 * `client.getItemContainer(InventoryID.SEED_VAULT).getItems()`
	 * directly off the container object — the exact same
	 * direct-container-read pattern already relied on for Bank/
	 * inventory/equipment/group storage in this project (NOT a
	 * widget-children read, unlike PotionStorageCollector — so search/
	 * filter UI state in the Seed Vault interface cannot produce an
	 * incomplete snapshot, since the search box only filters what's
	 * drawn, never the underlying ItemContainer). ItemContainerChanged
	 * for InventoryID.SEED_VAULT is fired by RuneLite's core BankPlugin
	 * for exactly this reason. This project's ContainerCollector
	 * already reads this container this same way for the seed_vault.json
	 * state file (see STORAGE_IDS) — SEED_VAULT_SNAPSHOT only adds the
	 * missing event-ledger notification that Bank already had and
	 * Seed Vault/Group Storage/Potion Storage did not.
	 *
	 * SEED_VAULT_SNAPSHOT = RuneLite observed the contents of the
	 * player's Seed Vault at this point in time. Deliberately a
	 * pointer-only event, matching BankSnapshot's own documented design
	 * ("Pointer only, per the spec — no per-item diff event"): the full
	 * itemId+quantity+name list already lives in seed_vault.json
	 * (StorageState/StorageItem, written by the same
	 * ContainerCollector.writeContainer() call this event fires
	 * alongside), so it is not duplicated into the event payload.
	 *
	 * Like Bank/Group Storage/Potion Storage, Seed Vault is a GATED
	 * domain — this event only ever fires from inside an actual
	 * ItemContainerChanged delivery, so it structurally cannot fire for
	 * "container unavailable" and is never suppressed for "genuinely
	 * observed empty" (a real empty vault is still a real observation).
	 * What is NOT independently confirmed: whether the container is
	 * observable via any path OTHER than opening the Seed Vault
	 * interface itself, and exact behavior across Group Ironman/other
	 * account types beyond "Seed Vault is per-player, not part of
	 * INV_GROUP_TEMP's shared GIM stash" (no evidence found either way
	 * of it behaving differently by account type).
	 */
	SEED_VAULT_SNAPSHOT,

	/**
	 * RuneLite's own core
	 * `net.runelite.client.plugins.loottracker.LootTrackerPlugin` uses
	 * `net.runelite.client.events.ServerNpcLoot` — NOT
	 * `net.runelite.client.events.NpcLootReceived` (this project's
	 * existing NPC_LOOT_ATTRIBUTED source) — for its own player-facing
	 * NPC-kill loot records, via a single handler,
	 * `onServerNpcLoot(ServerNpcLoot event)`. `ServerNpcLoot` is
	 * documented by RuneLite itself as "NPC loot received from the
	 * in-game loot tracker" (Jagex's own server-side feature), which
	 * `LootTrackerPlugin` filters for pickpocket loot via a same-tick
	 * guard (`ignorePickpocketLoot == client.getTickCount()`, set from a
	 * chat-message match against `PICKPOCKET_REGEX`) — since
	 * pickpocketing only ever rewards the local player, the fact that
	 * RuneLite's own core plugin has to explicitly filter pickpocket
	 * loot BACK OUT of this signal is itself real evidence that,
	 * post-filtering, `ServerNpcLoot` is scoped to loot actually granted
	 * to the local player, not a tile/tick spatial correlation that can
	 * pick up another visible player's drop the way `NpcLootReceived`
	 * (`NPC_LOOT_ATTRIBUTED`'s source) can.
	 *
	 * SERVER_NPC_LOOT = RuneLite received an NPC loot record from
	 * Jagex's in-game loot tracker for the local client. This is
	 * DELIBERATELY not named NPC_LOOT_GRANTED/PLAYER_LOOT/OWNED_LOOT/
	 * MY_LOOT: those names would assert a stronger, formal
	 * personal-ownership contract than RuneLite's public source
	 * actually documents anywhere. No RuneLite comment or javadoc
	 * anywhere in this audit claims `ServerNpcLoot` is a 100%-guaranteed
	 * personal-loot record — only that RuneLite's own native, widely
	 * used Loot Tracker plugin relies on it as its player-facing source,
	 * and that its own pickpocket-filtering necessity is real
	 * corroborating evidence of local-player scope. This event name
	 * states exactly the producer fact proved (RuneLite received this
	 * record from Jagex's loot tracker for this client), consistent
	 * with every other event name in this file — it neither overclaims
	 * nor underclaims relative to `NPC_LOOT_ATTRIBUTED`.
	 *
	 * NOT a replacement for NPC_LOOT_ATTRIBUTED, and NOT gated on or
	 * correlated with it, `NPC_DEATH`, `SLAYER_TASK_PROGRESS`,
	 * `BOSS_KILL`, or any raid/activity completion event — see
	 * ServerNpcLootCollector's class javadoc for the full independence
	 * rationale. `NPC_LOOT_ATTRIBUTED` (`NpcLootReceived`/
	 * `LootManager`, tile+tick correlation, no ownership check) is
	 * unchanged by this addition and remains this project's separate,
	 * weaker, already-shipped signal.
	 *
	 * One `SERVER_NPC_LOOT` event corresponds to one accepted
	 * (non-pickpocket) `ServerNpcLoot` notification, preserving all of
	 * its item stacks together in one payload — deliberately NOT
	 * flattened into one ledger event per item stack the way
	 * `NPC_LOOT_ATTRIBUTED` is, because the player-facing Loot Tracker
	 * this event is intended to eventually feed needs to reconstruct
	 * individual-drop history (one record per kill) as well as grouped
	 * totals, and flattening at the producer would destroy the
	 * per-notification boundary needed for the former. See
	 * EventPayloads.ServerNpcLoot's javadoc for the exact payload shape.
	 */
	SERVER_NPC_LOOT,

	/**
	 * Built from net.runelite.api.events.InteractingChanged, filtered to
	 * event.getSource() == client.getLocalPlayer(), event.getTarget()
	 * instanceof NPC, AND that NPC's own (transformed) NPCComposition
	 * genuinely offering an "Attack" action — see
	 * NpcInteractionTargetCollector's class javadoc for the full
	 * source-selection audit, which mirrors BossActivityContextCollector's
	 * own use of this same InteractingChanged/local-player-source
	 * technique (BOSS_ACTIVITY_CONTEXT above), extended here with an
	 * explicit attackability check so bankers, shopkeepers, and other
	 * merely-talked-to NPCs are never mistaken for a combat target.
	 *
	 * NPC_INTERACTION_TARGET = RuneLite observed the local player's own
	 * interaction target change to a specific, genuinely attackable NPC.
	 * This does NOT prove: a kill occurred, the local player has ever
	 * damaged this NPC, this NPC is hostile or in combat at this instant
	 * (InteractingChanged fires on the target reference changing, not on
	 * a hit landing), or any kill/reliableCount authority whatsoever —
	 * this event structurally cannot carry one (see
	 * EventPayloads.NpcInteractionTarget). It exists purely to give a
	 * LATER, independent combat-skill XP_CHANGE something honest to name
	 * itself after, instead of every ordinary NPC fight collapsing into
	 * one indistinguishable generic "Combat" identity — see
	 * ActivitySignalClassifier.classifyXpChange()'s own combat branch and
	 * ClassifierContext's short-lived "recent target" watch for exactly
	 * how this is consumed, bounded, and kept from ever independently
	 * starting, resuming, or switching a session by itself.
	 *
	 * Deliberately independent of BOSS_ACTIVITY_CONTEXT: a boss NPC is
	 * also, incidentally, an attackable NPC, so both events can fire for
	 * the same interaction — this is fine and expected. BOSS_ACTIVITY_CONTEXT's
	 * own SPECIFIC-strength BOSSING identity always outranks whatever
	 * generic ORDINARY-strength COMBAT/&lt;npc&gt; identity this event's
	 * context might otherwise produce (see ActivityPrecedence /
	 * decideCombatBranch()) — no special-casing of any particular boss or
	 * ordinary NPC name exists anywhere in this file.
	 */
	NPC_INTERACTION_TARGET,

	/**
	 * Emitted immediately by SkillsCollector.onStatChanged()
	 * whenever a genuine combat skill's (Attack/Strength/Defence/Ranged/
	 * Hitpoints -- NEVER Magic) XP is observed to increase, independent
	 * of and well before XP_CHANGE's own slow, aggregated flush cadence
	 * (see SessionSignal/SignalKind.RAW_COMBAT_XP_OBSERVED's own javadoc
	 * for the full rationale). Carries a skill name only -- NEVER an XP
	 * delta/value -- so it can never be mistaken for, or double-count
	 * against, XP_CHANGE's own accumulated XP metric.
	 */
	RAW_COMBAT_XP_OBSERVED
}
