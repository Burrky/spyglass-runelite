package com.osrstelemetry.plugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * Every one of these is read live by its collector on every relevant
 * event (see each collector's config.xxx() check) — not just declared
 * here for show. See README's data table for exactly which line of
 * code reads which toggle.
 */
@ConfigGroup("osrstelemetry")
public interface OsrsTelemetryConfig extends Config
{
	@ConfigItem(
		keyName = "collectSkills",
		name = "Collect skills",
		description = "Record skill levels and XP.",
		position = 1
	)
	default boolean collectSkills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectInventoryEquipment",
		name = "Collect inventory/equipment",
		description = "Record current inventory and equipment.",
		position = 2
	)
	default boolean collectInventoryEquipment()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectBank",
		name = "Collect bank",
		description = "Record bank contents when the bank is open.",
		position = 3
	)
	default boolean collectBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectSeedVault",
		name = "Collect seed vault",
		description = "Record seed vault contents when it is open.",
		position = 4
	)
	default boolean collectSeedVault()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectPotionStorage",
		name = "Collect potion storage",
		description = "Record potion storage contents (item, dose count) when the bank's potion storage tab is built or changes.",
		position = 5
	)
	default boolean collectPotionStorage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectGroupStorage",
		name = "Collect group storage (GIM)",
		description = "Record Group Ironman shared storage contents when it is open. Never treated as empty when unopened.",
		position = 5
	)
	default boolean collectGroupStorage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectQuests",
		name = "Collect quests",
		description = "Record quest completion state.",
		position = 6
	)
	default boolean collectQuests()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectSlayer",
		name = "Collect Slayer",
		description = "Record current Slayer task, streak, and points.",
		position = 7
	)
	default boolean collectSlayer()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectCollectionLog",
		name = "Collect collection log",
		description = "Record new-collection-log-item notifications in real time. Depends on your in-game 'collection log - new item' chat notification setting being on.",
		position = 8
	)
	default boolean collectCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectCombatAchievements",
		name = "Collect combat achievements",
		description = "Record combat achievement task completions.",
		position = 9
	)
	default boolean collectCombatAchievements()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectBossKc",
		name = "Collect boss/activity kill counts",
		description = "Record kill/completion counts from official game messages where available.",
		position = 11
	)
	default boolean collectBossKc()
	{
		return true;
	}

	// FIX (server-side loot data-foundation pass): this single toggle now
	// gates TWO distinct NPC-loot events with different truth semantics
	// — NPC_LOOT_ATTRIBUTED (tile/tick correlation, no ownership check)
	// and SERVER_NPC_LOOT (Jagex's own server-side loot tracker record,
	// materially stronger local-player scope — see EventType.
	// SERVER_NPC_LOOT's javadoc). One shared toggle was kept rather than
	// adding a second raw-telemetry checkbox for what is still, from the
	// player's point of view, "loot collection" — but the description
	// below no longer implies the two events carry identical guarantees.
	@ConfigItem(
		keyName = "collectLoot",
		name = "Collect loot",
		description = "Record NPC loot RuneLite observes via two separate signals: item stacks it merely attributes to an NPC by tile/timing (does not confirm the item was awarded to you personally), and the stronger, separate record RuneLite's own native Loot Tracker relies on (Jagex's server-side loot tracker feed). See README for the difference.",
		position = 12
	)
	default boolean collectLoot()
	{
		return true;
	}

	// FIX (Step 6 — event truth-semantics correction): renamed from
	// collectNpcKills. "Kills" implied local-player attribution that
	// the underlying signal (ActorDeath) cannot prove — see
	// NpcDeathCollector's class javadoc.
	@ConfigItem(
		keyName = "collectNpcDeaths",
		name = "Collect NPC deaths",
		description = "Record observed NPC death events (net.runelite.api.events.ActorDeath). Does not confirm the local player caused the death — see README.",
		position = 13
	)
	default boolean collectNpcDeaths()
	{
		return true;
	}

	// REMOVED (Task 5 follow-up -- circular first-boss recognition +
	// architecture-violation fix): collectBossActivityContext() config
	// toggle deleted. This project's architecture intentionally does
	// NOT expose per-signal collection toggles for continuous,
	// non-authoritative context signals -- BOSS_ACTIVITY_CONTEXT is now
	// collected unconditionally whenever the plugin/session runtime is
	// enabled, same as SessionRuntimeCoordinator itself. See
	// BossActivityContextCollector's class javadoc.

	// ADDED (Current Session UI facelift, PART 10). A player-facing
	// display setting, not a collection toggle -- read live by
	// CurrentSessionView on every render, never by a collector. Governs
	// both the displayed per-item/per-session gp figures AND the loot
	// sort order (see LootPricing).
	@ConfigItem(
		keyName = "lootValuationMode",
		name = "Loot valuation",
		description = "Which price Current Session's loot section displays and sorts by.",
		position = 14
	)
	default LootValuationMode lootValuationMode()
	{
		return LootValuationMode.GRAND_EXCHANGE;
	}
}
