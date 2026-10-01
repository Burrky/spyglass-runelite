package com.osrstelemetry.plugin.model;

import lombok.Data;

/**
 * Task identity (name/location/initial/remaining amount) is resolved
 * natively by Spyglass (SlayerNativeTaskReader: Slayer varps + the game
 * cache's Slayer DB tables) -- never from another RuneLite plugin. Chat
 * text is not used for any current-state field here.
 *
 * masterId is the raw varbit value and is always trustworthy.
 * streak/streakLabel: the streak that belongs to the current master --
 * Krystilia (7) SLAYER_WILDERNESS_TASKS_COMPLETED ("WILDERNESS"), Mortimer
 * (10) varp SLAYER_MORTIMER_TASKS_COMPLETED ("MORTIMER"), every other master
 * SLAYER_TASKS_COMPLETED ("NORMAL") -- the same selection RuneLite 1.13.1's
 * own SlayerPlugin.addCounter() makes. The streak feeds
 * SlayerCollector.looksCancelled(), which only ever compares two
 * observations with the SAME label.
 *
 * modifierId/modifierValue/modifierNegative: the raw task-modifier varbits
 * (Mortimer's "mortifiers"). Additive and nullable -- absent from older
 * slayer.json files. Presentation decides which ids it can name reliably.
 */
@Data
public class SlayerState
{
	private String taskName;
	private String taskLocation;
	private int amountRemaining;
	private int initialAmount;
	/**
	 * Raw varp SLAYER_COUNT_ORIGINAL at this observation (before any quantity
	 * modifier). Diagnostic only -- not part of task identity. Recorded so
	 * live QA can show whether it changes on a direct same-name
	 * reassignment before it is ever trusted as identity evidence.
	 */
	private Integer nativeCountOriginal;

	/** Raw varbit SLAYER_MODIFIER_ID (0 = none). Nullable for older files. */
	private Integer modifierId;
	/** Raw varbit SLAYER_MODIFIER_VALUE. */
	private Integer modifierValue;
	/** Raw varbit SLAYER_MODIFIER_NEGATIVE == 1. */
	private Boolean modifierNegative;

	private int masterId;
	private int points;
	private int streak;
	private String streakLabel;
	private boolean streakSourceVerified;

	private String lastUpdated;
}
