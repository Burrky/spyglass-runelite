package com.osrstelemetry.plugin.session;

/**
 * How a Slayer assignment instance ended, as far as Spyglass can prove
 * from authoritative task state. See
 * SessionAggregates.SlayerAssignmentRecord#endReason.
 */
public enum SlayerAssignmentEndReason
{
	/** The authoritative completion path set remaining to 0. */
	COMPLETED,
	/**
	 * A genuinely new authoritative assignment replaced it while it still
	 * had remaining task units. Spyglass cannot tell a points skip from
	 * any other replacement, so this is deliberately not called SKIPPED
	 * internally; the UI labels it "Skipped".
	 */
	REPLACED_EARLY
}
