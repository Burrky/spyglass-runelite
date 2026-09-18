package com.osrstelemetry.plugin.model;

import lombok.Data;

/**
 * Task identity (name/location/initial/remaining amount) is resolved
 * via RuneLite's published net.runelite.client.plugins.slayer.SlayerPluginService.
 * Chat text is not used for any current-state field here.
 *
 * masterId is the raw varbit value and is always trustworthy.
 * streakLabel/streakSourceVerified exist because of one genuine gap:
 * RuneLite core special-cases exactly one master (Krystilia, id 7) as
 * having a separate streak varbit from everyone else. Mortimer
 * (Wyrmscraig content, launched July 29 2026) is documented by
 * Jagex/the wiki as also having a separate streak, but current
 * RuneLite core has no Mortimer branch to mirror — see GitHub issue
 * runelite/runelite#20349 ("mortimer modifiers are not tracked by the
 * slayer plugin"), still open with no linked fix as of this writing.
 *
 * IMPORTANT LIMITATION: Mortimer's numeric master id is not known
 * from any source reachable here, so the code cannot even flag "this
 * specific master's streak is unverified" — every master other than
 * Krystilia gets streakLabel="NORMAL" and streakSourceVerified=true,
 * Mortimer included, even though "NORMAL" is specifically unverified
 * for him. A manual test on Mortimer content — record masterId's
 * actual value, then check whether SLAYER_TASKS_COMPLETED tracks his
 * streak correctly — would let this be tightened to a real per-master
 * check.
 */
@Data
public class SlayerState
{
	private String taskName;
	private String taskLocation;
	private int amountRemaining;
	private int initialAmount;

	private int masterId;
	private int points;
	private int streak;
	private String streakLabel;
	private boolean streakSourceVerified;

	private String lastUpdated;
}
