package com.osrstelemetry.plugin.ui.model;

import java.time.Instant;
import java.util.Comparator;

/**
 * ADDED (Loot Tracker source-ordering fix pass); CORRECTED (small
 * UI/loot polish pass -- Favorites must not reorder the All list). The
 * single, pure, directly-unit-testable definition of "most recent
 * activity first" for the Loot Tracker's source cards -- factored out of
 * {@link LootTrackerSnapshot#from} so the ordering rule itself can be
 * tested with plain {@link LootTrackerSnapshot.SourceEntry} instances,
 * no index/preferences/Swing required.
 *
 * ORDER (product decision, corrected by this pass): plain recent-first,
 * with NO favorite grouping at all -- the source whose newest (post
 * reset-watermark) record is most recent comes first, regardless of
 * favorite state. Favoriting a source makes it available on the
 * Favorites sub-tab; it must never move that source's position within
 * the All list. Applying this ONE comparator to the full source list is
 * what makes both {@link LootTrackerSnapshot#getAllSources()} (plain
 * recent-first, favorite-state-independent) and
 * {@link LootTrackerSnapshot#getFavoriteSources()} (a plain filter over
 * that same already-sorted list, so it comes out recent-first among
 * favorites "for free," with no separate sort of its own) correct from
 * one place.
 *
 * SUPERSEDED: this comparator previously sorted favorites as a group
 * before non-favorites (see this class's prior javadoc / the live
 * bug-fix report the "small UI/loot polish" pass corrected). That
 * behavior made the All view visibly jump/reorder the instant a card was
 * favorited -- confirmed by product decision to be wrong: the point of
 * Favorites is a separate filtered view, not a mutation of the normal
 * list's ordering. Removing the favorite-first comparison is the entire
 * fix; recency-then-sourceKey below is unchanged from before.
 *
 * TIE-BREAK: sources with an equal (or equally absent) last-activity
 * instant are ordered by sourceKey ascending -- stable source identity,
 * never sourceName alone (two different sourceIds sharing a display name
 * must still sort deterministically relative to each other -- see
 * LootTrackerIndex's own "never merged" javadoc). A null lastObservedAt
 * (should not occur for a real, non-empty source -- defensive only)
 * sorts as LEAST recent, never crashes the comparator and never
 * silently ties with a real timestamp.
 */
final class LootTrackerSourceOrdering
{
	private LootTrackerSourceOrdering()
	{
	}

	static final Comparator<LootTrackerSnapshot.SourceEntry> COMPARATOR = (a, b) ->
	{
		int byRecency = compareRecencyDescending(a.getLastObservedAt(), b.getLastObservedAt());
		if (byRecency != 0)
		{
			return byRecency;
		}
		return a.getSourceKey().compareTo(b.getSourceKey());
	};

	/** Descending by instant -- more recent first; null sorts after every real instant. */
	private static int compareRecencyDescending(Instant a, Instant b)
	{
		if (a == null && b == null)
		{
			return 0;
		}
		if (a == null)
		{
			return 1;
		}
		if (b == null)
		{
			return -1;
		}
		return b.compareTo(a);
	}
}
