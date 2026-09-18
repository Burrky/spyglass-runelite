package com.osrstelemetry.plugin.loottracker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One immutable snapshot of everything observed for one STABLE source identity
 * (sourceName + sourceId -- see LootTrackerIndex.sourceKey()'s own
 * javadoc for why the two are never merged even when only one of them
 * differs). Produced fresh by {@link LootTrackerIndex#snapshotSources()}
 * every time it is called; never itself mutated afterward -- the same
 * "immutable snapshot built fresh from mutable live state" split this
 * project's Current Session architecture already uses
 * (SessionAggregates -> CurrentSessionSnapshot).
 *
 * {@link #getRecords()} preserves the exact atomic
 * {@code SERVER_NPC_LOOT} event boundaries in observation order --
 * required by the spec's individual-drop view, which must reconstruct
 * real event boundaries rather than a fabricated one. {@link #getItemTotals()}
 * is a SEPARATE, derived aggregation over those same records for the
 * grouped view -- both views read from the same underlying records, so
 * they can never disagree with each other.
 */
public final class LootTrackerSource
{
	private final String sourceKey;
	private final String sourceName;
	private final Integer sourceId;
	private final List<LootTrackerRecord> records;

	LootTrackerSource(String sourceKey, String sourceName, Integer sourceId, List<LootTrackerRecord> records)
	{
		this.sourceKey = sourceKey;
		this.sourceName = sourceName;
		this.sourceId = sourceId;
		this.records = Collections.unmodifiableList(new ArrayList<>(records));
	}

	public String getSourceKey()
	{
		return sourceKey;
	}

	public String getSourceName()
	{
		return sourceName;
	}

	public Integer getSourceId()
	{
		return sourceId;
	}

	/** Every atomic drop record observed for this source, in observation order. Never empty (a source with zero records is never surfaced -- see LootTrackerIndex). */
	public List<LootTrackerRecord> getRecords()
	{
		return records;
	}

	/**
	 * The one reliable "kill count" for this source: the number of
	 * distinct atomic {@code SERVER_NPC_LOOT} notifications observed --
	 * exactly RuneLite's own core Loot Tracker's kill-count semantics
	 * (see LootTrackerIndex's own javadoc for the full source audit).
	 * Never derived from NPC_DEATH or any other signal.
	 */
	public int getKillCount()
	{
		return records.size();
	}

	public Instant getFirstObservedAt()
	{
		return records.isEmpty() ? null : records.get(0).getObservedAt();
	}

	public Instant getLastObservedAt()
	{
		return records.isEmpty() ? null : records.get(records.size() - 1).getObservedAt();
	}

	/**
	 * A new snapshot containing only records strictly AFTER
	 * {@code afterExclusive} (or this exact object, unfiltered, if
	 * {@code afterExclusive} is null) -- used by the UI-facing snapshot
	 * layer to apply a per-source RESET WATERMARK (see
	 * LootTrackerPreferences's own javadoc) without this class or
	 * {@link LootTrackerIndex} needing any concept of "reset" baked into
	 * their own pure telemetry-derivation logic. A reset is "forget
	 * everything AT OR BEFORE this point," so a record with
	 * {@code observedAt} exactly equal to {@code afterExclusive} is
	 * excluded too. A record with a null {@code observedAt} is always
	 * KEPT -- never silently dropped by a filter it has no timestamp to
	 * compare against.
	 */
	public LootTrackerSource filteredAfter(Instant afterExclusive)
	{
		if (afterExclusive == null)
		{
			return this;
		}
		List<LootTrackerRecord> filtered = new ArrayList<>();
		for (LootTrackerRecord record : records)
		{
			if (record.getObservedAt() == null || record.getObservedAt().isAfter(afterExclusive))
			{
				filtered.add(record);
			}
		}
		return new LootTrackerSource(sourceKey, sourceName, sourceId, filtered);
	}

	/**
	 * Grouped view: one entry per distinct itemId, quantity summed
	 * across every record, in FIRST-SEEN item order (a stable,
	 * deterministic base order -- callers doing value-based sorting,
	 * e.g. LootPricing, re-sort this themselves; this method makes no
	 * value/price assumption of its own).
	 */
	public List<LootTrackerItem> getItemTotals()
	{
		Map<Integer, Long> quantityByItemId = new LinkedHashMap<>();
		Map<Integer, String> nameByItemId = new LinkedHashMap<>();
		for (LootTrackerRecord record : records)
		{
			for (LootTrackerItem item : record.getItems())
			{
				quantityByItemId.merge(item.getItemId(), item.getQuantity(), Long::sum);
				nameByItemId.putIfAbsent(item.getItemId(), item.getItemName());
			}
		}

		List<LootTrackerItem> totals = new ArrayList<>();
		for (Map.Entry<Integer, Long> entry : quantityByItemId.entrySet())
		{
			totals.add(new LootTrackerItem(entry.getKey(), nameByItemId.get(entry.getKey()), entry.getValue()));
		}
		return totals;
	}
}
