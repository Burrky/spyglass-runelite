package com.osrstelemetry.plugin.pestcontrol;

import com.osrstelemetry.plugin.activity.PestControlGameState;
import net.runelite.api.Skill;

/**
 * What the Pest Control dashboard reads (all presentation / planning state,
 * none of it telemetry). Implemented by the plugin from the structured
 * activity collector and the account's goal preferences; every method is
 * safe to call from the Swing thread.
 */
public interface PestControlPanelSource
{
	/** The current game (status overlay), or null when not on the island. */
	PestControlGameState game();

	/** The commendation balance the game last stated, or null when unknown (never assumed 0). */
	Integer balance();

	/** Void pieces owned now. */
	VoidOwnership.Snapshot ownership();

	/** Exact XP of a reward skill, or null when not seen this login. */
	Integer skillXp(Skill skill);

	PestControlGoal goal();

	void setGoal(PestControlGoal goal);
}
