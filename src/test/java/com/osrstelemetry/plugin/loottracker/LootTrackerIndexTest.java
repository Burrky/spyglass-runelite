package com.osrstelemetry.plugin.loottracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Pure,
 * RuneLite/Swing-independent coverage of {@link LootTrackerIndex} --
 * dedup, stable source identity (never merged), honest kill-count
 * semantics, and atomic per-record item-stack preservation. Plain
 * JUnit, no Mockito, matching this project's established test style.
 */
public class LootTrackerIndexTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	private static LootTrackerRecord record(String eventId, String sourceName, Integer sourceId, Instant at, LootTrackerItem... items)
	{
		return new LootTrackerRecord(eventId, sourceName, sourceId, at, Arrays.asList(items));
	}

	@Test
	public void apply_newRecord_createsSourceWithOneRecord()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		boolean applied = index.apply(record("e1", "Zulrah", 2042, T0,
			new LootTrackerItem(2444, "Zulrah's scales", 100)));

		assertTrue(applied);
		List<LootTrackerSource> sources = index.snapshotSources();
		assertEquals(1, sources.size());
		assertEquals("Zulrah", sources.get(0).getSourceName());
		assertEquals(Integer.valueOf(2042), sources.get(0).getSourceId());
		assertEquals(1, sources.get(0).getKillCount());
	}

	@Test
	public void apply_duplicateEventId_dedupedAndSkipped()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		LootTrackerRecord first = record("dup-1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100));
		LootTrackerRecord duplicate = record("dup-1", "Zulrah", 2042, T0.plusSeconds(5), new LootTrackerItem(2444, "Zulrah's scales", 999));

		assertTrue(index.apply(first));
		assertFalse(index.apply(duplicate));

		LootTrackerSource source = index.snapshotSources().get(0);
		assertEquals(1, source.getKillCount());
		assertEquals(100L, source.getItemTotals().get(0).getQuantity());
	}

	@Test
	public void apply_nullEventId_neverDedupSkipped()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		LootTrackerRecord a = record(null, "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 50));
		LootTrackerRecord b = record(null, "Zulrah", 2042, T0.plusSeconds(1), new LootTrackerItem(2444, "Zulrah's scales", 50));

		assertTrue(index.apply(a));
		assertTrue(index.apply(b));

		assertEquals(2, index.snapshotSources().get(0).getKillCount());
	}

	@Test
	public void differentSourceIds_sameName_neverMerged()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Cave krystilia", 100, T0, new LootTrackerItem(1, "Bones", 1)));
		index.apply(record("e2", "Cave krystilia", 200, T0, new LootTrackerItem(1, "Bones", 1)));

		List<LootTrackerSource> sources = index.snapshotSources();
		assertEquals(2, sources.size());
		assertFalse(sources.get(0).getSourceKey().equals(sources.get(1).getSourceKey()));
	}

	@Test
	public void nullSourceId_isItsOwnStableIdentity_distinctFromAnyRealId()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Goblin", null, T0, new LootTrackerItem(1, "Bones", 1)));
		index.apply(record("e2", "Goblin", 42, T0, new LootTrackerItem(1, "Bones", 1)));

		List<LootTrackerSource> sources = index.snapshotSources();
		assertEquals(2, sources.size());
	}

	@Test
	public void sameSourceIdentity_recordsAccumulateAcrossMultipleApplyCalls()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Gargoyle", 412, T0, new LootTrackerItem(561, "Nature rune", 5)));
		index.apply(record("e2", "Gargoyle", 412, T0.plusSeconds(60), new LootTrackerItem(561, "Nature rune", 3)));
		index.apply(record("e3", "Gargoyle", 412, T0.plusSeconds(120), new LootTrackerItem(9, "Granite dust", 1)));

		LootTrackerSource source = index.snapshotSources().get(0);
		// Honest kill count -- one per atomic SERVER_NPC_LOOT record, exactly RuneLite's own Loot Tracker semantics.
		assertEquals(3, source.getKillCount());
		// Individual-drop view preserves the exact atomic event boundaries, in observation order.
		assertEquals(3, source.getRecords().size());
		assertEquals(1, source.getRecords().get(0).getItems().size());

		// Grouped view sums quantities across records for the same item.
		List<LootTrackerItem> totals = source.getItemTotals();
		assertEquals(2, totals.size());
		LootTrackerItem natureRuneTotal = totals.get(0);
		assertEquals("Nature rune", natureRuneTotal.getItemName());
		assertEquals(8L, natureRuneTotal.getQuantity());
	}

	@Test
	public void snapshotSource_byKey_returnsNullWhenUnknown()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		assertNull(index.snapshotSource("nothing here"));
	}

	@Test
	public void snapshotSource_byKey_matchesSnapshotSourcesEntry()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Vorkath", 8061, T0, new LootTrackerItem(1, "Dragonbone", 1)));

		String key = index.snapshotSources().get(0).getSourceKey();
		LootTrackerSource bySingleLookup = index.snapshotSource(key);
		assertEquals("Vorkath", bySingleLookup.getSourceName());
		assertEquals(1, bySingleLookup.getKillCount());
	}

	@Test
	public void snapshotSources_isAFreshImmutableCopy_neverAffectedByLaterApply()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));

		List<LootTrackerSource> before = index.snapshotSources();
		index.apply(record("e2", "Zulrah", 2042, T0.plusSeconds(1), new LootTrackerItem(2444, "Zulrah's scales", 50)));

		assertEquals(1, before.get(0).getKillCount());
		assertEquals(2, index.snapshotSources().get(0).getKillCount());
	}

	@Test
	public void filteredAfter_null_returnsSameUnfilteredContent()
	{
		LootTrackerSource source = new LootTrackerSource("k", "Zulrah", 2042,
			Collections.singletonList(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(1, "x", 1))));
		LootTrackerSource same = source.filteredAfter(null);
		assertEquals(1, same.getKillCount());
	}

	@Test
	public void filteredAfter_excludesRecordsAtOrBeforeWatermark()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(1, "x", 1)));
		index.apply(record("e2", "Zulrah", 2042, T0.plusSeconds(30), new LootTrackerItem(1, "x", 1)));
		index.apply(record("e3", "Zulrah", 2042, T0.plusSeconds(90), new LootTrackerItem(1, "x", 1)));

		LootTrackerSource source = index.snapshotSources().get(0);
		LootTrackerSource filtered = source.filteredAfter(T0.plusSeconds(30));

		// e1 (at T0) and e2 (exactly at the watermark) are both excluded; only e3 remains.
		assertEquals(1, filtered.getKillCount());
	}

	@Test
	public void filteredAfter_recordWithNullObservedAt_isNeverDropped()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, null, new LootTrackerItem(1, "x", 1)));

		LootTrackerSource source = index.snapshotSources().get(0);
		LootTrackerSource filtered = source.filteredAfter(T0.plusSeconds(90));

		assertEquals(1, filtered.getKillCount());
	}
}
