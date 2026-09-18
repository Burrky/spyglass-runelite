package com.osrstelemetry.plugin.session;

import com.osrstelemetry.plugin.collectors.LoadoutArchive;
import java.time.Duration;
import java.time.Instant;

/**
 * The
 * pure resolution ladder described by {@link LoadoutProvenance}'s own
 * javadoc, implemented against whatever short-lived in-memory
 * {@link LoadoutArchive} this plugin process currently holds. Owned by
 * SessionPersistence (see its "resolver" field); never called
 * directly by SessionRuntimeCoordinator/SessionLifecycleEngine -- see
 * Session's own javadoc on why the integration point is
 * SessionPersistence, not the lifecycle/classifier machinery.
 *
 * NULL-SAFE BY CONSTRUCTION: a null archive (SessionPersistence's
 * single-arg constructor, kept for backward compatibility with
 * existing callers/tests that never had a loadout archive to pass)
 * makes every resolution honestly UNAVAILABLE rather than throwing --
 * this resolver is always safe to call unconditionally.
 */
public final class LoadoutResolver
{
	/**
	 * How far before the resolution TARGET instant (see
	 * {@link #STARTING_LOADOUT_TARGET_OFFSET}) this resolver will still
	 * trust a state-history observation as genuinely representative of
	 * what the player had at that target point. Comfortably inside
	 * LoadoutArchive.MAX_AGE, so a qualifying observation is never
	 * pruned out from under a resolution attempt that would otherwise
	 * have found it. This is the "reliable" cutoff this resolver
	 * means by "no reliable state exists for that target point" --
	 * beyond this window, an observation is treated as too stale to
	 * honestly claim as the target instant's state, and resolution
	 * falls through to the fallback step instead of fabricating
	 * accuracy.
	 */
	static final Duration PRE_START_LOOKBACK = Duration.ofMinutes(5);

	/**
	 * A starting
	 * loadout is meant to represent the player's loadout from
	 * approximately this long BEFORE the session actually began, not
	 * the instant the session began -- see {@link #resolveStarting}'s
	 * own javadoc for the full rationale and the worked example this
	 * offset exists to satisfy.
	 */
	static final Duration STARTING_LOADOUT_TARGET_OFFSET = Duration.ofSeconds(30);

	private final LoadoutArchive archive;

	public LoadoutResolver(LoadoutArchive archive)
	{
		this.archive = archive;
	}

	/**
	 * Resolve a STARTING loadout for a session whose startedAt is
	 * `startedAt`.
	 *
	 * TARGET-TIME, NOT STARTED-AT: a starting loadout should represent
	 * the player's loadout from approximately
	 * {@link #STARTING_LOADOUT_TARGET_OFFSET} (30s) BEFORE the session
	 * began, not whatever was observed nearest to startedAt itself.
	 * `target = startedAt - 30s` is computed once and is the single
	 * reference instant the rest of this method reasons about.
	 *
	 * STATE HISTORY, NOT AN EXACT-INSTANT SNAPSHOT REQUIREMENT: this
	 * does NOT require an observation to have been physically captured
	 * exactly at `target`. {@link LoadoutArchive#latestAtOrBefore} finds
	 * the most recent COMPLETE inventory+equipment state already in
	 * effect at or before `target` -- i.e. loadout snapshots are treated
	 * as state history (a state holds until the next observed change),
	 * not point samples. Worked example from the spec: a player equips
	 * gear at T-2min, nothing changes afterward, and the session begins
	 * at T (target = T-30s) -- the T-2min observation is still the
	 * state in effect at T-30s, so it is correctly used, even though no
	 * observation happened to land exactly on T-30s itself.
	 *
	 * Ladder: (1) the most recent complete observation at or before
	 * `target`, IF it falls within {@link #PRE_START_LOOKBACK} of
	 * `target` (the "reliable" cutoff -- see that field's own javadoc)
	 * -- PRE_START; (2) else the earliest complete observation at or
	 * after `target` -- FALLBACK_AT_OR_AFTER_START, the honest
	 * fallback for "no reliable state exists for that target point",
	 * re-anchored to `target` rather than
	 * `startedAt` so it is answering the same question step (1) asked,
	 * not a different one; (3) else UNAVAILABLE.
	 * No new persisted/noisy snapshot machinery is introduced -- this
	 * refines the existing {@link LoadoutArchive} query surface only,
	 * preferring to use/refine the existing LoadoutArchive over adding new machinery.
	 */
	public LoadoutSnapshot resolveStarting(Instant startedAt)
	{
		if (archive == null || startedAt == null)
		{
			return LoadoutSnapshot.unavailable();
		}

		Instant target = startedAt.minus(STARTING_LOADOUT_TARGET_OFFSET);

		LoadoutArchive.Entry atOrBeforeTarget = archive.latestAtOrBefore(target);
		if (atOrBeforeTarget != null && !atOrBeforeTarget.getObservedAt().isBefore(target.minus(PRE_START_LOOKBACK)))
		{
			return toSnapshot(atOrBeforeTarget, LoadoutProvenance.PRE_START);
		}

		LoadoutArchive.Entry fallback = archive.earliestAtOrAfter(target);
		if (fallback != null)
		{
			return toSnapshot(fallback, LoadoutProvenance.FALLBACK_AT_OR_AFTER_START);
		}

		return LoadoutSnapshot.unavailable();
	}

	/**
	 * Resolve an OPTIONAL ENDING loadout as of `referenceInstant`
	 * -- ordinarily a session's finalizedAt, i.e.
	 * essentially "now" at the moment finalization happens. PRE_START
	 * is never produced here -- see LoadoutProvenance's own javadoc:
	 * there is no "before finalization" preference the way there is a
	 * "before the session started" preference for the starting
	 * loadout, so both the at-or-before and the (rare) after case are
	 * labeled FALLBACK_AT_OR_AFTER_START. Ladder: (1) the most recent
	 * complete observation at or before referenceInstant; (2) else the
	 * earliest complete observation after it (covers the narrow race
	 * where the final container observation lags slightly behind the
	 * finalization decision); (3) else UNAVAILABLE -- never blocks
	 * finalization either way.
	 */
	public LoadoutSnapshot resolveEnding(Instant referenceInstant)
	{
		if (archive == null || referenceInstant == null)
		{
			return LoadoutSnapshot.unavailable();
		}

		LoadoutArchive.Entry atOrBefore = archive.latestAtOrBefore(referenceInstant);
		if (atOrBefore != null)
		{
			return toSnapshot(atOrBefore, LoadoutProvenance.FALLBACK_AT_OR_AFTER_START);
		}

		LoadoutArchive.Entry after = archive.earliestAtOrAfter(referenceInstant);
		if (after != null)
		{
			return toSnapshot(after, LoadoutProvenance.FALLBACK_AT_OR_AFTER_START);
		}

		return LoadoutSnapshot.unavailable();
	}

	private static LoadoutSnapshot toSnapshot(LoadoutArchive.Entry entry, LoadoutProvenance provenance)
	{
		return new LoadoutSnapshot(entry.getInventory(), entry.getEquipment(), entry.getObservedAt().toString(), provenance);
	}
}
