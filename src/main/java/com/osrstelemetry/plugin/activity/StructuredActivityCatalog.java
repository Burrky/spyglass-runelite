package com.osrstelemetry.plugin.activity;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The supported structured activities. Every entry was audited against
 * RuneLite 1.12.39's own data (see the project doc
 * "structured-activity-minigame-support.md"):
 *
 *  - region ids: DiscordGameEventType / BarrowsPlugin / WintertodtPlugin /
 *    LootTrackerPlugin template-region constants;
 *  - reward producers: the same game signals RuneLite's own Loot Tracker
 *    plugin reads (reward interface + container, or a region-bound claim
 *    message + the next inventory change), observed by Spyglass itself so
 *    nothing depends on that optional plugin being enabled;
 *  - completion strings: ChatCommandsPlugin's KILLCOUNT_PATTERN /
 *    GUARDIANS_OF_THE_RIFT_PATTERN, via this project's existing
 *    ActivityKillCountCollector where it already parses them.
 *
 * Deliberately absent: activities whose location or completion could not
 * be confirmed from local client data (e.g. Perilous Moons' arena
 * regions). Adding an activity is a data change here -- nothing else.
 */
public final class StructuredActivityCatalog
{
	private StructuredActivityCatalog()
	{
	}

	public static final String GUARDIANS_OF_THE_RIFT = "guardians_of_the_rift";
	public static final String TEMPOROSS = "tempoross";
	public static final String PEST_CONTROL = "pest_control";
	public static final String GIANTS_FOUNDRY = "giants_foundry";
	public static final String BARROWS = "barrows";
	public static final String WINTERTODT = "wintertodt";

	/** The reward-claim chat message RuneLite 1.12.39 itself keys GotR / Tempoross / Wintertodt rewards on. */
	static final String CLAIM_MESSAGE = "You found some loot: ";

	public static List<StructuredActivityDefinition> definitions()
	{
		return Collections.unmodifiableList(Arrays.asList(
			StructuredActivityDefinition.builder(GUARDIANS_OF_THE_RIFT, "Guardians of the Rift")
				.region(LocationPolicy.EXCLUSIVE, 14484)
				.expectedSkills("runecraft", "mining", "crafting")
				// ChatCommandsPlugin.GUARDIANS_OF_THE_RIFT_PATTERN, tags removed.
				// LIVE-VERIFIED TEXT (2026-10-01): "Amount of rifts you have closed:
				// <col=ff0000>87</col>." -- lowercase "rifts". The former capital-R
				// pattern never matched a real game, so wins were never counted.
				// Case-insensitive so a capitalisation change cannot break it again.
				.completionChat("(?i)^Amount of rifts you have closed: ([0-9,]+)\\.?$")
				// Rewards Guardian search: "You found some loot: ..." in the temple.
				.rewardClaimMessage("guardians_of_the_rift", "Guardians of the Rift", CLAIM_MESSAGE, true, new int[] {14484},
					"Rewards Guardian")
				.completionLabel("Games")
				.build(),
			StructuredActivityDefinition.builder(TEMPOROSS, "Tempoross")
				.region(LocationPolicy.EXCLUSIVE, 12076)
				// Tempoross Cove: where the reward pool is claimed after a game.
				.region(LocationPolicy.NON_EXCLUSIVE, 12588)
				.expectedSkills("fishing", "cooking", "construction")
				// "Your Tempoross kill count is: N" (BOSS_KILL) is one game.
				.completionBoss("Tempoross")
				// Reward pool in the cove. Caskets are opened anywhere: never a Tempoross reward.
				.rewardClaimMessage("tempoross_reward_pool", "Tempoross Reward Pool", CLAIM_MESSAGE, true, new int[] {12588},
					"Reward pool (Tempoross)")
				.completionLabel("Games")
				// TEMPOROSS_REWARDPERMITS: earned this session = positive changes only.
				.currencyLabel("Permits")
				.build(),
			StructuredActivityDefinition.builder(PEST_CONTROL, "Pest Control")
				.region(LocationPolicy.EXCLUSIVE, 10536)
				.combatExpected()
				// Adapter-isolated fallback: only a won, rewarded game prints this.
				.completionChat("awarded you [0-9,]+ Void Knight Commendation points?")
				.completionLabel("Games")
				.build(),
			StructuredActivityDefinition.builder(GIANTS_FOUNDRY, "Giants' Foundry")
				.region(LocationPolicy.EXCLUSIVE, 13491)
				.expectedSkills("smithing")
				// No confirmed per-sword completion signal: no completion metric.
				.build(),
			StructuredActivityDefinition.builder(BARROWS, "Barrows")
				.region(LocationPolicy.STRONG_CONTEXT, 14131)
				.region(LocationPolicy.EXCLUSIVE, 14231)
				.combatExpected()
				.subordinates("Ahrim the Blighted", "Dharok the Wretched", "Guthan the Infested",
					"Karil the Tainted", "Torag the Corrupted", "Verac the Defiled")
				// "Your Barrows chest count is: N" (ACTIVITY_COMPLETION).
				.completionCount("Barrows chest")
				// Chest reward interface (gameval InterfaceID.BARROWS_REWARD) and its
				// item container (InventoryID.TRAIL_REWARDINV -- only read while THIS
				// interface opens, since clue caskets share that container).
				.rewardInterface("barrows", "Barrows", 155, 141)
				.completionLabel("Chests")
				.build(),
			StructuredActivityDefinition.builder(WINTERTODT, "Wintertodt")
				.region(LocationPolicy.EXCLUSIVE, 6462)
				// Wintertodt camp: reward cart / crates, shared with banking and skilling.
				.region(LocationPolicy.NON_EXCLUSIVE, 6461)
				.expectedSkills("firemaking", "woodcutting", "fletching", "construction")
				// "Your subdued Wintertodt count is: N" (ACTIVITY_COMPLETION).
				.completionCount("subdued Wintertodt")
				// Reward cart in the camp. Supply crates are items opened anywhere:
				// never attributed to a Wintertodt session.
				.rewardClaimMessage("wintertodt_reward_cart", "Wintertodt Reward Cart", CLAIM_MESSAGE, false, new int[] {6461},
					"Reward cart (Wintertodt)")
				.completionLabel("Games")
				.build()));
	}
}
