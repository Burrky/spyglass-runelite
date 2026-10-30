package com.osrstelemetry.plugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * Player-facing presentation settings only, each read live by the UI on
 * every render. Telemetry collection is deliberately not configurable:
 * every collector runs while Spyglass is enabled. The former collect*
 * keys were deleted, not hidden; values old profiles still store for them
 * are simply never read.
 */
@ConfigGroup("osrstelemetry")
public interface OsrsTelemetryConfig extends Config
{
	// Sections follow each setting's real consumers. "General" (key "shared")
	// = read by more than one tab. No setting is exclusive to the Loot
	// Tracker or History, so neither has a section. Section/item NAMES are
	// player-facing; the key names are storage and never change.

	@ConfigSection(
		name = "Text size & contrast",
		description = "How large and how bright text is in the Spyglass sidebar (not the game overlays). Also on Home → Text size & contrast,"
			+ " with a live preview.",
		position = 0
	)
	String APPEARANCE_SECTION = "appearance";

	@ConfigSection(
		name = "General",
		description = "Settings that apply across Spyglass.",
		position = 1
	)
	String SHARED_SECTION = "shared";

	@ConfigSection(
		name = "Current Session",
		description = "How the Session tab is laid out.",
		position = 2
	)
	String CURRENT_SESSION_SECTION = "currentSession";

	@ConfigSection(
		name = "Home",
		description = "Home's reminders: Dailies & Weeklies and bird houses.",
		position = 3
	)
	String HOME_SECTION = "home";

	@ConfigItem(
		keyName = "textSize",
		name = "Text size",
		description = "Text size on every Spyglass page in the sidebar: Standard, Large or Extra large. Pages wrap and scroll to fit.",
		position = 1,
		section = APPEARANCE_SECTION
	)
	default SidebarTextSize textSize()
	{
		return SidebarTextSize.STANDARD;
	}

	@ConfigItem(
		keyName = "textContrast",
		name = "Text contrast",
		description = "Standard, or High contrast: brighter supporting text and colours on every Spyglass page in the sidebar.",
		position = 2,
		section = APPEARANCE_SECTION
	)
	default SidebarTextContrast textContrast()
	{
		return SidebarTextContrast.STANDARD;
	}

	// Read by CurrentSessionView, LootTrackerView and HistoryDetailView;
	// drives both displayed gp values and loot sort order (see LootPricing).
	@ConfigItem(
		keyName = "lootValuationMode",
		name = "Loot valuation",
		description = "The price used to value and sort loot on the Session, Loot and History tabs.",
		position = 1,
		section = SHARED_SECTION
	)
	default LootValuationMode lootValuationMode()
	{
		return LootValuationMode.GRAND_EXCHANGE;
	}

	@ConfigItem(
		keyName = "currentSessionSectionOrder",
		name = "XP / Loot order",
		description = "Which section the Session tab shows first: XP, or Loot (Task History during Slayer).",
		position = 1,
		section = CURRENT_SESSION_SECTION
	)
	default CurrentSessionSectionOrder currentSessionSectionOrder()
	{
		return CurrentSessionSectionOrder.XP_FIRST;
	}

	@ConfigItem(
		keyName = "slayerXpScope",
		name = "Slayer XP scope",
		description = "What the Slayer XP section counts: every task this session, or only your current task. You can also switch this from the XP section itself. Display only.",
		position = 2,
		section = CURRENT_SESSION_SECTION
	)
	default SlayerXpScope slayerXpScope()
	{
		return SlayerXpScope.ALL_TASKS;
	}

	@ConfigItem(
		keyName = "recurringNotifications",
		name = "Reminders",
		description = "Send a RuneLite notification once when your bird houses are full and once when Tears of Guthix becomes available (both read"
			+ " from the game). Uses RuneLite's own notification settings.",
		position = 1,
		section = HOME_SECTION
	)
	default boolean recurringNotifications()
	{
		return false;
	}

	@ConfigItem(
		keyName = "kingdomOverlay",
		name = "Kingdom overlay",
		description = "On Miscellania and Etceteria, a small box with the kingdom's approval and coffer as the game shows them there, and what"
			+ " needs topping up. Drag it to move it (Alt-drag, as other RuneLite boxes).",
		position = 2,
		section = HOME_SECTION
	)
	default boolean kingdomOverlay()
	{
		return true;
	}
}
