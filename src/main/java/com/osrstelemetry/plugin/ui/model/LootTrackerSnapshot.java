package com.osrstelemetry.plugin.ui.model;

import com.osrstelemetry.plugin.loottracker.LootTrackerIndex;
import com.osrstelemetry.plugin.loottracker.LootTrackerItem;
import com.osrstelemetry.plugin.loottracker.LootTrackerPreferences;
import com.osrstelemetry.plugin.loottracker.LootTrackerRecord;
import com.osrstelemetry.plugin.loottracker.LootTrackerSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADDED (Spyglass Phase 2 -- persistent Loot Tracker). The ONE place
 * pure UI-shaped derivation happens for the Loot Tracker -- immutable,
 * built fresh from {@link LootTrackerIndex}/{@link LootTrackerPreferences}
 * at poll time, unit-testable without Swing. Mirrors
 * {@link CurrentSessionSnapshot}'s own architectural role for Current
 * Session exactly (same project convention: a mutable live
 * index/aggregate is never hand	ed to a Swing view directly -- a fresh
 * immutable snapshot is built from it instead, never handed to a Swing
 * view directly).
 *
 * COMBINES two independent inputs that must never be confused with each
 * other: {@link LootTrackerIndex} (pure telemetry-derived truth -- which
 * sources/records/items actually happened) and
 * {@link LootTrackerPreferences} (pure UI preference -- favorites,
 * hidden items, collapsed cards, reset watermarks, show-hidden toggle).
 * Neither of those two classes knows the other exists; this class is
 * where they meet.
 *
 * RESET WATERMARK APPLICATION: each RAW (sourceName, sourceId) source's
 * records are filtered via {@link LootTrackerSource#filteredAfter} using
 * that raw source's own persisted watermark (if any) BEFORE anything
 * else is derived from it (item totals, kill count, individual-drop
 * list, and -- see CANONICAL PLAYER-FACING IDENTITY below -- BEFORE
 * grouping into a player-facing card too). A raw source left with zero
 * records after filtering is omitted entirely (nothing meaningful to
 * show). Resetting one raw variant never resets another, and "reset this
 * NPC" (see below) works by resetting every raw variant currently
 * grouped under it to the same watermark instant -- no separate
 * canonical-watermark storage/migration is needed at all.
 *
 * CANONICAL PLAYER-FACING IDENTITY (Loot Tracker duplicate-card fix
 * pass): {@link LootTrackerIndex} keys raw telemetry by (sourceName,
 * sourceId) -- see its own javadoc -- because OSRS/RuneLite's
 * {@code NPCComposition#getId()} genuinely varies for what is visually
 * ONE logical monster to the player (confirmed live: e.g. "Desert Wolf"/
 * "Goat"-style duplicate cards, each a different composition id under
 * the identical display name). That RAW identity is exactly right for
 * {@link LootTrackerIndex} (telemetry/diagnostics must never lose real
 * source-id information) but is WRONG for what the player sees: spec
 * requires ONE card per logical NPC/activity. This class is therefore
 * the ONE place that raw (sourceName, sourceId) sources get grouped into
 * a single player-facing {@link SourceEntry} by {@link #canonicalDisplayName}
 * (sourceName alone, case-sensitive, exact match -- see that method's
 * own javadoc for why name-only is the right, deliberately simple
 * canonicalization here and why it is safe never to special-case two
 * genuinely-different sources sharing one display name). Every raw
 * sourceKey a canonical entry represents is preserved on it via
 * {@link SourceEntry#getRawSourceKeys()} -- nothing about the raw
 * telemetry identity is lost or merged away in {@link LootTrackerIndex}
 * itself, only in this presentation layer. Preference lookups
 * (favorite/collapsed/hidden) are OR-ed across every raw member of a
 * canonical group -- e.g. a source already favorited under its OLD raw
 * key before this fix shipped is still shown favorited afterward, with
 * no explicit migration/rewrite of the persisted preferences file
 * needed (see {@link SourceEntry#getRawSourceKeys()}'s own javadoc for
 * how the view layer writes new favorite/hide/collapse/reset actions
 * back across the whole group so this stays consistent going forward).
 *
 * VALUATION IS INTENTIONALLY ABSENT: unlike Current Session's
 * equivalent, this snapshot carries only itemId/itemName/quantity (no
 * price/value fields) -- pricing must be resolved off the EDT via
 * {@code ItemValuationCache}/{@code ClientThread} exactly like Current
 * Session's own loot section (see LootPricing's own class javadoc for
 * why valuation can never happen here), so LootTrackerView applies
 * {@code LootPricing.valueAndSort}/{@code buildTooltipHtml} to this
 * snapshot's item lists at render time, the SAME shared valuation/sort
 * code Current Session already uses -- never a second pricing/sorting
 * implementation.
 */
public final class LootTrackerSnapshot
{
	private static final LootTrackerSnapshot EMPTY = new LootTrackerSnapshot(Collections.emptyList(), false);

	/** Null-safe, oldest-first: a null observedAt (should not occur for a real record) sorts last rather than throwing. */
	private static final Comparator<LootTrackerRecord> BY_OBSERVED_AT_ASCENDING = (a, b) ->
	{
		Instant ai = a.getObservedAt();
		Instant bi = b.getObservedAt();
		if (ai == null && bi == null)
		{
			return 0;
		}
		if (ai == null)
		{
			return 1;
		}
		if (bi == null)
		{
			return -1;
		}
		return ai.compareTo(bi);
	};

	private final List<SourceEntry> sources;
	private final boolean showHidden;

	private LootTrackerSnapshot(List<SourceEntry> sources, boolean showHidden)
	{
		this.sources = Collections.unmodifiableList(sources);
		this.showHidden = showHidden;
	}

	public static LootTrackerSnapshot empty()
	{
		return EMPTY;
	}

	public static LootTrackerSnapshot from(LootTrackerIndex index, LootTrackerPreferences preferences)
	{
		if (index == null)
		{
			return empty();
		}

		// Raw (sourceName, sourceId) sources, watermark-filtered -- see
		// class javadoc's RESET WATERMARK APPLICATION note. Grouped by
		// canonical player-facing display name immediately below, in
		// first-observed-raw-source order (a LinkedHashMap, so grouping
		// itself never introduces HashMap-iteration-order nondeterminism
		// -- final presentation order is still entirely decided by
		// LootTrackerSourceOrdering.COMPARATOR at the very end).
		Map<String, List<LootTrackerSource>> rawByCanonicalName = new LinkedHashMap<>();
		for (LootTrackerSource source : index.snapshotSources())
		{
			Instant watermark = preferences == null ? null : preferences.getResetWatermark(source.getSourceKey());
			LootTrackerSource filtered = source.filteredAfter(watermark);
			if (filtered.getRecords().isEmpty())
			{
				continue;
			}
			String canonicalName = canonicalDisplayName(filtered.getSourceName());
			rawByCanonicalName.computeIfAbsent(canonicalName, k -> new ArrayList<>()).add(filtered);
		}

		List<SourceEntry> entries = new ArrayList<>();
		for (List<LootTrackerSource> group : rawByCanonicalName.values())
		{
			entries.add(buildCanonicalEntry(group, preferences));
		}

		// ADDED (Loot Tracker source-ordering fix); CORRECTED (small
		// UI/loot polish pass). "Most recent activity first" -- the source
		// with the newest POST-WATERMARK lastObservedAt first (so a reset
		// source's ordering reflects only what's actually still visible,
		// exactly like its displayed totals already do), with NO favorite
		// grouping: favorite state must never move a source's position in
		// the All list (product decision -- see
		// LootTrackerSourceOrdering's own javadoc for the corrected
		// ordering and why the old favorites-first behavior was removed).
		// getFavoriteSources() below is a simple filter over this same
		// already-sorted list, so it comes out recent-first among
		// favorites "for free" with no separate sort of its own. Never
		// HashMap iteration/discovery/creation order, never alphabetical
		// -- see LootTrackerSourceOrdering's own javadoc for the
		// deterministic tie-break.
		entries.sort(LootTrackerSourceOrdering.COMPARATOR);

		boolean showHidden = preferences != null && preferences.isShowHidden();
		return new LootTrackerSnapshot(entries, showHidden);
	}

	/**
	 * The player-facing canonical grouping key: the raw sourceName,
	 * exactly as observed (case-sensitive, no trimming/normalization
	 * beyond that -- OSRS composition names are already clean, and
	 * inventing fuzzy normalization here would be exactly the kind of
	 * "clever" canonicalization this fix deliberately avoids). Two raw
	 * sources with the identical display name are, to the player,
	 * visually the SAME logical NPC/activity -- there is no product
	 * concept of two different things that both legitimately look like
	 * "Desert Wolf" and must still be told apart on screen -- so grouping
	 * purely by name is both simple and correct here. A null sourceName
	 * groups together as its own canonical bucket (displayed as
	 * "Unknown" by the view, same as before this fix).
	 */
	private static String canonicalDisplayName(String sourceName)
	{
		return sourceName == null ? "" : sourceName;
	}

	/**
	 * Merges one canonical-name group of raw {@link LootTrackerSource}s
	 * (already watermark-filtered) into the single player-facing
	 * {@link SourceEntry} the view renders as one card. Kill count sums
	 * across every raw member (each is still an honest per-raw-source
	 * SERVER_NPC_LOOT occurrence count -- see LootTrackerSource's own
	 * javadoc -- summing them keeps that same honesty for the merged
	 * total). Individual records are concatenated across every member and
	 * re-sorted oldest-first by observedAt, so the Individual view still
	 * shows one true chronological timeline even when it spans multiple
	 * raw composition ids.
	 */
	private static SourceEntry buildCanonicalEntry(List<LootTrackerSource> group, LootTrackerPreferences preferences)
	{
		List<String> rawSourceKeys = new ArrayList<>(group.size());
		int killCount = 0;
		Instant first = null;
		Instant last = null;
		boolean favorite = false;
		boolean collapsed = false;
		List<LootTrackerRecord> allRecords = new ArrayList<>();

		for (LootTrackerSource member : group)
		{
			rawSourceKeys.add(member.getSourceKey());
			killCount += member.getKillCount();
			Instant memberFirst = member.getFirstObservedAt();
			if (memberFirst != null && (first == null || memberFirst.isBefore(first)))
			{
				first = memberFirst;
			}
			Instant memberLast = member.getLastObservedAt();
			if (memberLast != null && (last == null || memberLast.isAfter(last)))
			{
				last = memberLast;
			}
			if (preferences != null && preferences.isFavorite(member.getSourceKey()))
			{
				favorite = true;
			}
			if (preferences != null && preferences.isCollapsed(member.getSourceKey()))
			{
				collapsed = true;
			}
			allRecords.addAll(member.getRecords());
		}
		allRecords.sort(BY_OBSERVED_AT_ASCENDING);

		Map<Integer, Long> quantityByItemId = new LinkedHashMap<>();
		Map<Integer, String> nameByItemId = new LinkedHashMap<>();
		for (LootTrackerRecord record : allRecords)
		{
			for (LootTrackerItem item : record.getItems())
			{
				quantityByItemId.merge(item.getItemId(), item.getQuantity(), Long::sum);
				nameByItemId.putIfAbsent(item.getItemId(), item.getItemName());
			}
		}

		List<ItemEntry> itemTotals = new ArrayList<>();
		for (Map.Entry<Integer, Long> entry : quantityByItemId.entrySet())
		{
			int itemId = entry.getKey();
			itemTotals.add(new ItemEntry(itemId, nameByItemId.get(itemId), entry.getValue(), isItemHiddenInGroup(rawSourceKeys, itemId, preferences)));
		}

		List<RecordEntry> individualRecords = new ArrayList<>();
		for (LootTrackerRecord record : allRecords)
		{
			List<ItemEntry> recordItems = new ArrayList<>();
			for (LootTrackerItem item : record.getItems())
			{
				recordItems.add(new ItemEntry(item.getItemId(), item.getItemName(), item.getQuantity(), isItemHiddenInGroup(rawSourceKeys, item.getItemId(), preferences)));
			}
			individualRecords.add(new RecordEntry(record.getObservedAt(), Collections.unmodifiableList(recordItems)));
		}

		LootTrackerSource representative = group.get(0);
		return new SourceEntry(
			canonicalSourceKey(representative.getSourceName()),
			representative.getSourceName(),
			representative.getSourceId(),
			killCount,
			first,
			last,
			favorite,
			collapsed,
			Collections.unmodifiableList(itemTotals),
			Collections.unmodifiableList(individualRecords),
			Collections.unmodifiableList(rawSourceKeys));
	}

	/** An item is treated as hidden for the merged card if hidden under ANY raw variant it was hidden from before grouping -- see class javadoc's preference-migration note. */
	private static boolean isItemHiddenInGroup(List<String> rawSourceKeys, int itemId, LootTrackerPreferences preferences)
	{
		if (preferences == null)
		{
			return false;
		}
		for (String rawKey : rawSourceKeys)
		{
			if (preferences.isItemHidden(rawKey, itemId))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The player-facing canonical entry's own identity string -- DELIBERATELY
	 * a different, unambiguous format ({@code "canon:" + name}) from
	 * {@link LootTrackerIndex#sourceKey}'s raw ({@code "name id"}) format, so
	 * the two can never collide even for a name that happens to end in
	 * something that looks like a trailing id token. Used for ordering
	 * tie-breaks and render-key fingerprinting; NEVER used as a
	 * {@link LootTrackerPreferences} key directly -- preference reads/writes
	 * always go through {@link SourceEntry#getRawSourceKeys()} instead (see
	 * that method's own javadoc), so this format never needs to appear in
	 * the persisted preferences document at all.
	 */
	private static String canonicalSourceKey(String sourceName)
	{
		return "canon:" + (sourceName == null ? "" : sourceName);
	}

	/** Every known source (post reset-watermark filtering, post canonical-identity grouping) -- the "All" sub-tab. */
	public List<SourceEntry> getAllSources()
	{
		return sources;
	}

	/** The subset of {@link #getAllSources()} the player has favorited -- the "Favorites" sub-tab. Never a separate/different data source from All. */
	public List<SourceEntry> getFavoriteSources()
	{
		List<SourceEntry> favorites = new ArrayList<>();
		for (SourceEntry entry : sources)
		{
			if (entry.isFavorite())
			{
				favorites.add(entry);
			}
		}
		return favorites;
	}

	/** Spec: "Show hidden" toggle state, default off -- callers decide whether to render a hidden ItemEntry based on this. */
	public boolean isShowHidden()
	{
		return showHidden;
	}

	public static final class SourceEntry
	{
		private final String sourceKey;
		private final String sourceName;
		private final Integer sourceId;
		private final int killCount;
		private final Instant firstObservedAt;
		private final Instant lastObservedAt;
		private final boolean favorite;
		private final boolean collapsed;
		private final List<ItemEntry> itemTotals;
		private final List<RecordEntry> records;
		private final List<String> rawSourceKeys;

		private SourceEntry(String sourceKey, String sourceName, Integer sourceId, int killCount,
			Instant firstObservedAt, Instant lastObservedAt, boolean favorite, boolean collapsed,
			List<ItemEntry> itemTotals, List<RecordEntry> records, List<String> rawSourceKeys)
		{
			this.sourceKey = sourceKey;
			this.sourceName = sourceName;
			this.sourceId = sourceId;
			this.killCount = killCount;
			this.firstObservedAt = firstObservedAt;
			this.lastObservedAt = lastObservedAt;
			this.favorite = favorite;
			this.collapsed = collapsed;
			this.itemTotals = itemTotals;
			this.records = records;
			this.rawSourceKeys = rawSourceKeys;
		}

		/** The CANONICAL (player-facing) identity -- see class javadoc's CANONICAL PLAYER-FACING IDENTITY note. Never a valid {@link LootTrackerPreferences} key on its own; use {@link #getRawSourceKeys()} for preference reads/writes. */
		public String getSourceKey()
		{
			return sourceKey;
		}

		public String getSourceName()
		{
			return sourceName;
		}

		/** The FIRST raw member's sourceId -- representative only (a merged canonical entry can span several raw composition ids); not used for identity or preference keys. */
		public Integer getSourceId()
		{
			return sourceId;
		}

		/** Same authoritative per-raw-source occurrence count as {@link LootTrackerSource#getKillCount()}, summed across every raw variant this canonical entry represents -- see that method's own honest-kill-count javadoc. */
		public int getKillCount()
		{
			return killCount;
		}

		public Instant getFirstObservedAt()
		{
			return firstObservedAt;
		}

		public Instant getLastObservedAt()
		{
			return lastObservedAt;
		}

		public boolean isFavorite()
		{
			return favorite;
		}

		public boolean isCollapsed()
		{
			return collapsed;
		}

		/** Grouped view -- one entry per distinct item, quantity summed across every (post-watermark) record from every raw variant this canonical entry represents. */
		public List<ItemEntry> getItemTotals()
		{
			return itemTotals;
		}

		/** Individual-drop view -- one entry per atomic SERVER_NPC_LOOT record from every raw variant this canonical entry represents, merged into one true oldest-first chronological timeline. */
		public List<RecordEntry> getRecords()
		{
			return records;
		}

		/**
		 * Every raw {@link LootTrackerIndex#sourceKey} (sourceName+sourceId)
		 * this canonical, player-facing entry represents -- almost always
		 * one, sometimes several when OSRS/RuneLite assigns multiple
		 * composition ids to what is visually one logical NPC (see class
		 * javadoc's CANONICAL PLAYER-FACING IDENTITY note). Raw telemetry
		 * identity is NEVER lost -- this list is exactly how a caller finds
		 * it again. {@link LootTrackerPreferences} (favorite/collapsed/
		 * hidden-item/reset-watermark) is keyed ONLY by raw sourceKey, never
		 * by this entry's own canonical {@link #getSourceKey()} -- a UI
		 * action on this card (favorite/hide/collapse/reset) must be applied
		 * to EVERY key in this list so it stays consistent with how this
		 * entry's favorite/collapsed/hidden flags above were themselves
		 * computed (OR across the group), and so a raw variant discovered
		 * later under the same display name inherits the group's already-set
		 * preferences the next time an action is taken on the merged card.
		 */
		public List<String> getRawSourceKeys()
		{
			return rawSourceKeys;
		}
	}

	/**
	 * IMPLEMENTS LootPricing.Priceable (Spyglass Phase 2) -- see that
	 * interface's own javadoc for why this and Current Session's own
	 * {@code CurrentSessionSnapshot.LootEntry} share the one valuation/
	 * sort implementation rather than duplicating it.
	 */
	public static final class ItemEntry implements com.osrstelemetry.plugin.ui.LootPricing.Priceable
	{
		private final int itemId;
		private final String itemName;
		private final long quantity;
		private final boolean hidden;

		private ItemEntry(int itemId, String itemName, long quantity, boolean hidden)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
			this.hidden = hidden;
		}

		/** Never null -- a LootTrackerRecord's itemId is always a real, non-nullable int (see LootTrackerItem). Boxed here only to satisfy LootPricing.Priceable's shared shape. */
		public Integer getItemId()
		{
			return itemId;
		}

		/** The unboxed form, for callers that never need the Priceable-shared shape (e.g. hidden-item lookups by raw id). */
		public int getItemIdValue()
		{
			return itemId;
		}

		public String getItemName()
		{
			return itemName;
		}

		public long getQuantity()
		{
			return quantity;
		}

		/** Spec: hidden is source-specific and presentation-only -- a hidden item still counts toward every total. */
		public boolean isHidden()
		{
			return hidden;
		}
	}

	public static final class RecordEntry
	{
		private final Instant observedAt;
		private final List<ItemEntry> items;

		private RecordEntry(Instant observedAt, List<ItemEntry> items)
		{
			this.observedAt = observedAt;
			this.items = items;
		}

		public Instant getObservedAt()
		{
			return observedAt;
		}

		public List<ItemEntry> getItems()
		{
			return items;
		}
	}
}
