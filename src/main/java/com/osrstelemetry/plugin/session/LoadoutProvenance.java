package com.osrstelemetry.plugin.session;

/**
 * Honest
 * labeling of exactly how a session's starting (or ending)
 * {@link LoadoutSnapshot} was resolved, so the History UI can never
 * misrepresent a fallback observation as an exact pre-start read.
 *
 * RESOLUTION LADDER: when a
 * session is established, the loadout resolver first computes a
 * TARGET instant 30 seconds before the session's own startedAt (a
 * starting loadout represents "what the player had ~30s before this
 * session began," not "at the literal startedAt instant" -- see
 * {@code LoadoutResolver#resolveStarting}'s own javadoc for the full
 * rationale and worked example), then looks for the most recent
 * COMPLETE inventory+equipment state already in effect at or before
 * that target -- treating loadout observations as state history, not
 * point samples, so the observation need not have been captured
 * exactly at the target instant, only be the latest one still in
 * effect by then -- within a short "reliable" lookback window of the
 * target. If one exists, that is PRE_START. If none exists within the
 * lookback window (e.g. the plugin only just started observing
 * containers), the resolver falls back to the earliest COMPLETE
 * observation AT OR AFTER that same target instant --
 * FALLBACK_AT_OR_AFTER_START. If neither can be found at all, the
 * loadout is UNAVAILABLE. This enum only records the OUTCOME of that
 * ladder; the ladder itself is implemented by the loadout resolver,
 * against whatever short-lived in-memory archive it builds.
 *
 * The exact same three outcomes apply to an OPTIONAL ending loadout,
 * resolved at/after a session's finalizedAt instead of
 * its startedAt -- PRE_START is not a meaningful label there (there
 * is no "before finalization" preference), so the ending-loadout
 * resolver only ever produces FALLBACK_AT_OR_AFTER_START
 * or UNAVAILABLE for it. Reusing this same enum for both starting and
 * ending loadout (rather than two near-identical enums) keeps
 * LoadoutSnapshot a single reusable shape for either purpose.
 */
public enum LoadoutProvenance
{
	/**
	 * The preferred case: a complete inventory+equipment state already
	 * in effect at or before the resolution target instant (~30s before
	 * the session started, for a starting loadout -- see
	 * {@code LoadoutResolver#resolveStarting}), close enough in time to
	 * trust as "what the player had approaching this session began".
	 * The only provenance the UI may label as "Starting loadout"
	 * without further qualification.
	 */
	PRE_START,

	/**
	 * No qualifying pre-start observation existed -- the earliest
	 * complete observation AT OR AFTER the reference instant
	 * (session startedAt for a starting loadout, or "now" for an
	 * ending loadout) was used instead. Honest, but not the same
	 * claim as PRE_START: the UI must label this distinctly (e.g.
	 * "Earliest captured loadout") rather than implying it reflects
	 * the player's exact state at that boundary.
	 */
	FALLBACK_AT_OR_AFTER_START,

	/**
	 * No complete inventory+equipment observation could be found at
	 * all. {@link LoadoutSnapshot#getInventory()} and
	 * {@link LoadoutSnapshot#getEquipment()} are empty lists in this
	 * case -- never fabricated placeholder data -- and the UI must
	 * render an explicit "unavailable" state rather than an empty
	 * grid that could be mistaken for "nothing carried".
	 */
	UNAVAILABLE
}
