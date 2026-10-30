package com.osrstelemetry.plugin.farming;

/**
 * The player's run actions as the panel calls them (EDT). Each acts only for
 * the owner the UI is currently showing. Implemented by
 * {@link FarmingCaptureAdapter}; none of them performs anything in the game.
 */
public interface FarmingRunControls
{
	FarmingRunStore.StartResult startRun(FarmingRunRequest request);

	void setSkipped(String patchId, boolean skip);

	/** Skip (or un-skip) an included compost bin's work for the open run (§28.16). */
	default void setBinSkipped(String binId, boolean skip)
	{
	}

	/** Skip (or un-skip) compost still due on a patch (§28.17). */
	default void setCompostSkipped(String patchId, boolean skip)
	{
	}

	/** Skip (or un-skip) a protection payment still due (§28.16). */
	default void setProtectionSkipped(String patchId, boolean skip)
	{
	}

	void resumeRun();

	void endRun();

	void acknowledgeRun();

	/** Go / Go anyway on the preparation stage (Phase H): show the run's patches. */
	void closeRunPreparation();

	/** The preparation option "wear an amulet of bounty" (saved with the run). */
	default void setRunBounty(boolean use)
	{
	}

	/** Pass 31: a patch type's compost changed while the run is open (its pending work follows; done work keeps the game's word). */
	default void setRunCompost(FarmingPatchType type, FarmingPlanDraft.Compost choice)
	{
	}

	/** The suggested visiting order the preparation computed (saved with the run so both pages agree). */
	default void setRunSuggestedOrder(java.util.List<String> patchIds)
	{
	}
}
