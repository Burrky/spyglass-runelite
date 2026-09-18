package com.osrstelemetry.plugin.events;

import lombok.Data;

/**
 * Bundled in one file (rather than one file per payload, unlike the
 * model/ package) because these are pure inert data shapes with no
 * behavior — splitting them wouldn't aid readability, just add file
 * count. Each mirrors a payload example from the telemetry schema.
 */
public final class EventPayloads
{
	private EventPayloads()
	{
	}

	@Data
	public static class XpChange
	{
		private final String skill;
		private final int previousXp;
		private final int newXp;
		private final int delta;
		private final String windowStart;
		private final String windowEnd;
	}

	@Data
	public static class LevelUp
	{
		private final String skill;
		private final int previousLevel;
		private final int newLevel;
		private final int xp;
	}

	@Data
	public static class QuestCompleted
	{
		private final int questId;
		private final String questKey;
		private final String questName;
	}

	@Data
	public static class CombatAchievementCompleted
	{
		private final String tier;
		private final String taskName;
	}

	/**
	 * itemId is null here — see CollectionLogCollector javadoc, the
	 * chat message this is built from doesn't carry an item ID and I'm
	 * not resolving name->ID by guessing. Fill this in once the
	 * full-page widget read (still unimplemented) provides a real
	 * source for it.
	 */
	@Data
	public static class CollectionLogNewItem
	{
		private final Integer itemId;
		private final String itemName;
	}

	/**
	 * Renamed from LootDrop/LOOT_DROP. Built from
	 * net.runelite.client.events.NpcLootReceived, produced by
	 * net.runelite.client.game.LootManager, which attributes an
	 * item stack to an NPC by pure tile + same-game-tick correlation to
	 * the NPC's despawn (LootManager.onItemSpawned() adds every
	 * ItemSpawned to a per-tile map unconditionally;
	 * getItemStacksFromAreas() then just reads whatever TileItem(s) are
	 * on the death tile(s), converting by id+quantity only —
	 * TileItem#getOwnership()/isPrivate() are never read anywhere in
	 * that file). This does NOT prove the item was awarded to or owned
	 * by the local player specifically — it can genuinely include
	 * another visible player's drop sharing the same tile in the same
	 * tick. Hence the field/class name change from "Drop" (which reads
	 * as "my loot") to "LootAttributed" (RuneLite's attribution of an
	 * observed stack to an NPC, nothing stronger). See
	 * NpcDeathCollector's own class javadoc for the full source-audit
	 * evidence, including why the newer, likely
	 * player-owned-loot-capable net.runelite.client.events.ServerNpcLoot
	 * was deliberately NOT switched to as this event's source.
	 *
	 * No field on this payload claims local-player ownership, kill
	 * attribution, or Slayer-task attribution — sourceType/sourceName/
	 * sourceId identify the NPC RuneLite attributed the stack to;
	 * itemId/itemName/quantity identify the item stack itself. Nothing
	 * here was renamed at the field level (no field ever claimed
	 * ownership) — only the class/event name, which previously implied
	 * more than the source proves.
	 */
	@Data
	public static class NpcLootAttributed
	{
		private final String sourceType;
		private final String sourceName;
		private final int sourceId;
		private final int itemId;
		private final String itemName;
		private final int quantity;
	}

	@Data
	public static class BossKill
	{
		private final String activityId;
		private final String displayName;
		private final int killCount;
		private final String source;
	}

	/**
	 * Backs EventType.BOSS_ACTIVITY_CONTEXT -- see that constant's javadoc for
	 * the full source audit and the reason this is deliberately
	 * NON-authoritative. Intentionally a single-field payload:
	 * bossName is the canonical boss display name KnownBossRegistry
	 * resolved (already alias-mapped, e.g. "Dawn" -> "Grotesque
	 * Guardians" -- never the raw, un-resolved NPC name). No
	 * reliableCount/killCount field exists here, unlike BossKill --
	 * this payload structurally cannot carry kill-count authority.
	 */
	@Data
	public static class BossActivityContext
	{
		private final String bossName;
	}

	@Data
	public static class RaidCompletion
	{
		private final String activityId;
		private final String displayName;
		private final int completionCount;
		private final String source;
	}

	/**
	 * Everything that prints a "completion count" line but is NOT one
	 * of the three verified raids (see ActivityKillCountCollector's
	 * RAID_IDS) — Corrupted Gauntlet, minigames, and any other
	 * activity with a completion counter. Kept schema-identical to
	 * RaidCompletion deliberately (same fields) so downstream summary
	 * code can treat them uniformly if it wants to, while the event
	 * TYPE itself still keeps "raid" meaning something specific rather
	 * than "any repeatable content with a counter".
	 */
	@Data
	public static class ActivityCompletion
	{
		private final String activityId;
		private final String displayName;
		private final int completionCount;
		private final String source;
	}

	@Data
	public static class SlayerTaskAssigned
	{
		private final String monster;
		private final int amount;
		private final String location;
		private final int masterId;
	}

	@Data
	public static class SlayerTaskCompleted
	{
		private final String monster;
		private final int streak;
		private final String streakLabel;
		private final int points;
	}

	/**
	 * Task IDENTITY fields (taskName/taskLocation) are included for correlation only
	 * — this event never fires across an identity change (see
	 * SlayerCollector.evaluateSlayerTransition()); it always describes
	 * the SAME assignment's count changing. initialAmount/masterId are
	 * included only because they're already known/available on the
	 * current observation when this fires (the task has amountRemaining
	 * > 0 in both cases progress is computed from) — never fabricated
	 * for an observation where they wouldn't naturally be present.
	 *
	 * Deliberately NOT a kill count: remainingDelta/taskUnitsConsumed
	 * describe the observed change in the task's remaining-count field,
	 * not a verified NPC death count — see EventType.SLAYER_TASK_PROGRESS's
	 * javadoc for why those aren't provably equivalent.
	 */
	@Data
	public static class SlayerTaskProgress
	{
		private final String taskName;
		private final String taskLocation;
		private final int previousRemaining;
		private final int currentRemaining;
		private final int remainingDelta;
		private final int taskUnitsConsumed;
		private final int initialAmount;
		private final int masterId;
	}

	/**
	 * Built from net.runelite.api.events.ActorDeath filtered to actor
	 * instanceof NPC — see NpcDeathCollector's class javadoc for the full
	 * signal selection audit.
	 *
	 * Renamed NpcKill -> NpcDeath (matching EventType.NPC_KILL ->
	 * NPC_DEATH): ActorDeath proves only that RuneLite observed this NPC
	 * die, never that the local player caused the death, received kill
	 * credit, or that the NPC counted toward an active Slayer
	 * assignment — "Kill" read as a player-attribution claim the source
	 * signal cannot back up. Nothing about the field set changed; only
	 * the class/event name, which previously implied more than the
	 * source proves.
	 *
	 * npcName/npcId are the primary NPC identity, read directly off the
	 * NPC object at the moment of death — never fabricated/defaulted.
	 * npcId is nullable: NPC#getId() is documented to be able to return
	 * -1 for an NPC with no valid composition (e.g. a mid-transform
	 * frame); when that happens this field is left null rather than
	 * storing a sentinel that looks like a real ID. combatLevel is
	 * similarly nullable for the same reason (Actor#getCombatLevel()
	 * gives no documented guarantee it always reflects a real NPC
	 * combat level in every case) — see NpcDeathCollector for exactly
	 * when each is left null.
	 *
	 * worldLocation is the actor's server-side WorldPoint (per
	 * Actor#getWorldLocation()'s own javadoc: "not affected by things
	 * such as animations," i.e. authoritative, not a render-position
	 * guess), serialized as "x,y,plane".
	 *
	 * activeSlayerTaskName/activeSlayerTaskLocation are a LIVE read of
	 * whatever Slayer assignment (if any) is active at the moment of
	 * this death — contextual metadata only, not a claim that the dead
	 * NPC belongs to that assignment, and not a claim that the local
	 * player caused this death. There is deliberately NO onSlayerTask
	 * boolean on this payload: no defensible RuneLite/Jagex mechanism
	 * was found for mapping a specific dead NPC to
	 * a specific active Slayer assignment (e.g. a random rat dying while
	 * Gargoyles is active must not read as a Slayer kill just because a
	 * task happens to be active) — see NpcDeathCollector's javadoc for
	 * the full reasoning. Both fields are null when no task is active.
	 */
	@Data
	public static class NpcDeath
	{
		private final String npcName;
		private final Integer npcId;
		private final Integer combatLevel;
		private final String worldLocation;
		private final String activeSlayerTaskName;
		private final String activeSlayerTaskLocation;
	}

	/**
	 * Pointer only, per the spec — no per-item diff event. itemCount
	 * is a cheap sanity signal for downstream consumers; the actual
	 * delta calculation is a backend job against the
	 * corresponding bank.json snapshot, not something computed here.
	 */
	@Data
	public static class BankSnapshot
	{
		private final String storageId;
		private final String snapshotId;
		private final int itemCount;
	}

	/**
	 * See EventType.SEED_VAULT_SNAPSHOT's javadoc for the full audit. Pointer
	 * only — deliberately mirrors BankSnapshot's shape minus snapshotId
	 * (Seed Vault has no immutable per-observation snapshot file the way
	 * Bank does).
	 * The real itemId+quantity+name list lives in seed_vault.json, not
	 * here — same "pointer, not a per-item diff event" precedent
	 * BankSnapshot already established.
	 */
	@Data
	public static class SeedVaultSnapshot
	{
		private final String storageId;
		private final int itemCount;
	}

	/**
	 * Backs EventType.SERVER_NPC_LOOT — see that enum constant's javadoc for
	 * the full source audit and naming rationale. Built from
	 * net.runelite.client.events.ServerNpcLoot (composition + items),
	 * after ServerNpcLootCollector's pickpocket-tick filter has already
	 * rejected the notification (a suppressed notification never
	 * reaches buildPayload() at all — see that collector).
	 *
	 * sourceId is nullable: NPCComposition#getId() carries no documented
	 * guarantee of always returning a real, usable value (mirroring the
	 * same "do not fabricate missing values" treatment
	 * NpcDeathCollector.buildPayload() already applies to NPC#getId()) —
	 * any non-positive raw id is left null rather than stored as a
	 * misleading not-actually-real id.
	 *
	 * items is a small typed list (ServerNpcLootItem), not an
	 * unstructured map, and deliberately preserves every item stack from
	 * one ServerNpcLoot notification together in one payload — this is
	 * what lets a future aggregation layer reconstruct one individual-
	 * drop record, source-grouped totals, and item-grouped totals within
	 * a source, all from this one event. No NPC instance/world-location
	 * field exists on this payload: ServerNpcLoot does not provide one
	 * (only NPCComposition, not a live NPC instance — see the enum
	 * javadoc), and none is fabricated here.
	 *
	 * Deliberately absent from this payload, by design (see
	 * EventType.SERVER_NPC_LOOT's independence rationale and
	 * ServerNpcLootCollector's class javadoc): any kill/death reference,
	 * any Slayer-task field, any "ownedByLocalPlayer"-style boolean.
	 * This payload states only what RuneLite's ServerNpcLoot event
	 * itself carries, nothing this project inferred on top of it.
	 */
	@Data
	public static class ServerNpcLoot
	{
		private final String sourceName;
		private final Integer sourceId;
		private final java.util.List<ServerNpcLootItem> items;
	}

	/**
	 * One item stack within a ServerNpcLoot notification. A small typed
	 * shape (itemId/itemName/quantity) rather than an unstructured
	 * map/array-pair, matching how every other multi-field payload in
	 * this file is represented. itemName is resolved the same way
	 * LootCollector already resolves NPC_LOOT_ATTRIBUTED item names
	 * (ItemManager#getItemComposition(itemId).getName()) — never
	 * fabricated when the lookup can't resolve.
	 */
	@Data
	public static class ServerNpcLootItem
	{
		private final int itemId;
		private final String itemName;
		private final int quantity;
	}

	/**
	 * Backs EventType.NPC_INTERACTION_TARGET — see that constant's javadoc for
	 * the full source audit. npcName is read directly off the NPC's own
	 * (transformed) NPCComposition at interaction time — never
	 * fabricated/guessed, never looked up in any hand-maintained table.
	 * npcId mirrors NpcDeath.npcId's own nullability precedent (NPC#getId()
	 * is documented to be able to return -1 for an NPC with no valid
	 * composition, e.g. a mid-transform frame — left null rather than
	 * stored as a misleading not-actually-real id). No reliableCount/
	 * killCount/combat-proof field exists here, unlike BossKill — this
	 * payload structurally cannot carry kill or damage-attribution
	 * authority (see NpcInteractionTargetCollector — it only ever builds
	 * this payload after confirming the target NPC's composition/actions
	 * genuinely include "Attack", so this is never emitted for a
	 * banker/shopkeeper/other merely-talked-to NPC in the first place).
	 */
	@Data
	public static class NpcInteractionTarget
	{
		private final Integer npcId;
		private final String npcName;
	}

	/**
	 * See EventType.RAW_COMBAT_XP_OBSERVED's own javadoc. skillName is
	 * the RuneLite Skill enum's own name() (matching
	 * XpChange.skillName's own convention exactly) -- deliberately NO
	 * xp/delta/level field of any kind, so this payload is structurally
	 * incapable of ever being mistaken for or merged into an XP metric.
	 */
	@Data
	public static class RawCombatXpObserved
	{
		private final String skillName;
	}
}
