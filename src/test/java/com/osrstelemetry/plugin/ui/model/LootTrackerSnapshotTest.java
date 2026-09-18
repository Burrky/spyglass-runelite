package com.osrstelemetry.plugin.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.loottracker.LootTrackerIndex;
import com.osrstelemetry.plugin.loottracker.LootTrackerItem;
import com.osrstelemetry.plugin.loottracker.LootTrackerPreferences;
import com.osrstelemetry.plugin.loottracker.LootTrackerRecord;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * ADDED (Spyglass Phase 2 -- persistent Loot Tracker). Covers
 * {@link LootTrackerSnapshot}'s own job: merging
 * {@link LootTrackerIndex} (telemetry) with
 * {@link LootTrackerPreferences} (UI preference) into the immutable,
 * Swing-independent shape {@code LootTrackerView} actually renders --
 * favorites sub-navigation, hidden-item flagging (never removing an
 * item, only flagging it), and reset-watermark filtering.
 *
 * LootTrackerPreferences is exercised against a real (but never
 * started) LocalStateStore/TelemetryPaths account directory, matching
 * every other storage-adjacent test in this project -- its in-memory
 * document mutations are read-your-own-write regardless of whether the
 * async durable write executor is even running (see that class's own
 * javadoc), so no sleep/await is needed here at all.
 */
public class LootTrackerSnapshotTest
{
	private static final long TEST_ACCOUNT_HASH = 999_333_555L;
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	private LootTrackerPreferences preferences;

	@Before
	public void setUp() throws Exception
	{
		deleteAccountDir();
		preferences = new LootTrackerPreferences(new LocalStateStore());
		preferences.loadForAccount(TEST_ACCOUNT_HASH);
	}

	@After
	public void tearDown() throws Exception
	{
		deleteAccountDir();
	}

	private void deleteAccountDir() throws Exception
	{
		File dir = TelemetryPaths.accountDir(TEST_ACCOUNT_HASH);
		if (dir.exists())
		{
			File[] files = dir.listFiles();
			if (files != null)
			{
				for (File f : files)
				{
					Files.deleteIfExists(f.toPath());
				}
			}
			Files.deleteIfExists(dir.toPath());
		}
	}

	private static LootTrackerRecord record(String eventId, String sourceName, Integer sourceId, Instant at, LootTrackerItem... items)
	{
		return new LootTrackerRecord(eventId, sourceName, sourceId, at, Arrays.asList(items));
	}

	@Test
	public void from_nullIndex_producesEmptySnapshot()
	{
		LootTrackerSnapshot snapshot = LootTrackerSnapshot.from(null, preferences);
		assertTrue(snapshot.getAllSources().isEmpty());
		assertTrue(snapshot.getFavoriteSources().isEmpty());
	}

	@Test
	public void from_surfacesEverySourceInAllSources()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e2", "Vorkath", 8061, T0, new LootTrackerItem(1, "Dragonbone", 1)));

		LootTrackerSnapshot snapshot = LootTrackerSnapshot.from(index, preferences);

		assertEquals(2, snapshot.getAllSources().size());
	}

	@Test
	public void favoriteSources_onlyIncludesSourcesMarkedFavorite()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e2", "Vorkath", 8061, T0, new LootTrackerItem(1, "Dragonbone", 1)));

		String zulrahKey = LootTrackerIndex.sourceKey("Zulrah", 2042);
		preferences.setFavorite(zulrahKey, true);

		LootTrackerSnapshot snapshot = LootTrackerSnapshot.from(index, preferences);

		assertEquals(2, snapshot.getAllSources().size());
		List<LootTrackerSnapshot.SourceEntry> favorites = snapshot.getFavoriteSources();
		assertEquals(1, favorites.size());
		assertEquals("Zulrah", favorites.get(0).getSourceName());
		assertTrue(favorites.get(0).isFavorite());
	}

	@Test
	public void hiddenItem_isFlaggedNotRemoved_andStillCountsTowardItemTotals()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0,
			new LootTrackerItem(2444, "Zulrah's scales", 100),
			new LootTrackerItem(12934, "Magic seed", 1)));

		String key = LootTrackerIndex.sourceKey("Zulrah", 2042);
		preferences.setItemHidden(key, 12934, true);

		LootTrackerSnapshot snapshot = LootTrackerSnapshot.from(index, preferences);
		LootTrackerSnapshot.SourceEntry entry = snapshot.getAllSources().get(0);

		// Hidden item is STILL present in the item list -- flagged, never removed.
		assertEquals(2, entry.getItemTotals().size());
		boolean foundHidden = false;
		for (LootTrackerSnapshot.ItemEntry item : entry.getItemTotals())
		{
			if (item.getItemId() == 12934)
			{
				assertTrue(item.isHidden());
				foundHidden = true;
			}
			else
			{
				assertFalse(item.isHidden());
			}
		}
		assertTrue(foundHidden);
	}

	@Test
	public void hiddenItemFlag_isSourceSpecific()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e2", "Vorkath", 8061, T0, new LootTrackerItem(2444, "Zulrah's scales", 5)));

		// Hide the SAME itemId, but only at the Zulrah source.
		preferences.setItemHidden(LootTrackerIndex.sourceKey("Zulrah", 2042), 2444, true);

		LootTrackerSnapshot snapshot = LootTrackerSnapshot.from(index, preferences);
		for (LootTrackerSnapshot.SourceEntry entry : snapshot.getAllSources())
		{
			boolean expectedHidden = "Zulrah".equals(entry.getSourceName());
			assertEquals(expectedHidden, entry.getItemTotals().get(0).isHidden());
		}
	}

	@Test
	public void showHidden_defaultsToFalse_andReflectsPreferenceToggle()
	{
		LootTrackerSnapshot before = LootTrackerSnapshot.from(new LootTrackerIndex(), preferences);
		assertFalse(before.isShowHidden());

		preferences.setShowHidden(true);
		LootTrackerSnapshot after = LootTrackerSnapshot.from(new LootTrackerIndex(), preferences);
		assertTrue(after.isShowHidden());
	}

	@Test
	public void resetWatermark_filtersOutRecordsAtOrBeforeIt_andDropsSourceIfNothingRemains()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));

		preferences.resetSource(LootTrackerIndex.sourceKey("Zulrah", 2042), T0.plusSeconds(1));

		LootTrackerSnapshot snapshot = LootTrackerSnapshot.from(index, preferences);
		assertTrue(snapshot.getAllSources().isEmpty());
	}

	@Test
	public void resetWatermark_recordsAfterItSurvive()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e2", "Zulrah", 2042, T0.plusSeconds(90), new LootTrackerItem(2444, "Zulrah's scales", 50)));

		preferences.resetSource(LootTrackerIndex.sourceKey("Zulrah", 2042), T0.plusSeconds(30));

		LootTrackerSnapshot snapshot = LootTrackerSnapshot.from(index, preferences);
		assertEquals(1, snapshot.getAllSources().size());
		assertEquals(1, snapshot.getAllSources().get(0).getKillCount());
		assertEquals(50L, snapshot.getAllSources().get(0).getItemTotals().get(0).getQuantity());
	}

	@Test
	public void individualRecords_preserveAtomicEventBoundaries()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Gargoyle", 412, T0, new LootTrackerItem(561, "Nature rune", 5)));
		index.apply(record("e2", "Gargoyle", 412, T0.plusSeconds(60), new LootTrackerItem(9, "Granite dust", 1)));

		LootTrackerSnapshot snapshot = LootTrackerSnapshot.from(index, preferences);
		LootTrackerSnapshot.SourceEntry entry = snapshot.getAllSources().get(0);

		assertEquals(2, entry.getRecords().size());
		assertEquals(1, entry.getRecords().get(0).getItems().size());
		assertEquals("Nature rune", entry.getRecords().get(0).getItems().get(0).getItemName());
		assertEquals("Granite dust", entry.getRecords().get(1).getItems().get(0).getItemName());
	}

	@Test
	public void collapsedState_reflectsPreference()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));

		String key = LootTrackerIndex.sourceKey("Zulrah", 2042);
		LootTrackerSnapshot before = LootTrackerSnapshot.from(index, preferences);
		assertFalse(before.getAllSources().get(0).isCollapsed());

		preferences.setCollapsed(key, true);
		LootTrackerSnapshot after = LootTrackerSnapshot.from(index, preferences);
		assertTrue(after.getAllSources().get(0).isCollapsed());
	}

	// =====================================================================
	// ADDED (Loot Tracker source-ordering fix pass). "Most recent activity
	// first" -- see LootTrackerSourceOrdering's own javadoc for the exact
	// rule this section verifies.
	// =====================================================================

	@Test
	public void allSources_mostRecentActivityFirst()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e2", "Basilisk Knight", 7999, T0.plusSeconds(120), new LootTrackerItem(560, "Death rune", 3)));
		index.apply(record("e3", "Gargoyles", 412, T0.plusSeconds(60), new LootTrackerItem(561, "Nature rune", 5)));

		List<LootTrackerSnapshot.SourceEntry> sources = LootTrackerSnapshot.from(index, preferences).getAllSources();

		assertEquals(Arrays.asList("Basilisk Knight", "Gargoyles", "Zulrah"), sourceNames(sources));
	}

	@Test
	public void aSourceReceivingANewerEvent_movesToTheTop()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Gargoyles", 412, T0, new LootTrackerItem(561, "Nature rune", 5)));
		index.apply(record("e2", "Zulrah", 2042, T0.plusSeconds(60), new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e3", "Basilisk Knight", 7999, T0.plusSeconds(120), new LootTrackerItem(560, "Death rune", 3)));

		assertEquals(Arrays.asList("Basilisk Knight", "Zulrah", "Gargoyles"),
			sourceNames(LootTrackerSnapshot.from(index, preferences).getAllSources()));

		// A fresh Gargoyle kill, newer than everything else observed so far.
		index.apply(record("e4", "Gargoyles", 412, T0.plusSeconds(200), new LootTrackerItem(9, "Granite dust", 1)));

		assertEquals(Arrays.asList("Gargoyles", "Basilisk Knight", "Zulrah"),
			sourceNames(LootTrackerSnapshot.from(index, preferences).getAllSources()));
	}

	// SUPERSEDED (small UI/loot polish pass -- Favorites must not reorder
	// the All list). This test formerly asserted favorites-as-a-group-
	// before-non-favorites in the All view. That was itself a product
	// decision later reversed: favoriting a source must make it available
	// on the Favorites sub-tab WITHOUT ever moving its position in the
	// All list. See allView_orderIsRecentFirstRegardlessOfFavoriteState()
	// and favoritingThenUnfavoriting_neverChangesAllViewPosition() below
	// for the corrected behavior, using this exact same scenario.
	@Test
	public void allView_orderIsRecentFirstRegardlessOfFavoriteState()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e2", "Vorkath", 8061, T0.plusSeconds(300), new LootTrackerItem(1, "Dragonbone", 1)));
		index.apply(record("e3", "Basilisk Knight", 7999, T0.plusSeconds(120), new LootTrackerItem(560, "Death rune", 3)));
		index.apply(record("e4", "Gargoyles", 412, T0.plusSeconds(60), new LootTrackerItem(561, "Nature rune", 5)));

		// Plain recent-first: Vorkath (300s) > Basilisk Knight (120s) >
		// Gargoyles (60s) > Zulrah (0s).
		List<String> recentFirstOrder = Arrays.asList("Vorkath", "Basilisk Knight", "Gargoyles", "Zulrah");
		assertEquals(recentFirstOrder, sourceNames(LootTrackerSnapshot.from(index, preferences).getAllSources()));

		// Favoriting the OLDEST (Zulrah) and a middle entry (Basilisk
		// Knight) must not move either of them -- the All view's order is
		// entirely independent of favorite state.
		preferences.setFavorite(LootTrackerIndex.sourceKey("Zulrah", 2042), true);
		preferences.setFavorite(LootTrackerIndex.sourceKey("Basilisk Knight", 7999), true);

		assertEquals("favoriting sources must never reorder the All view -- it stays plain "
			+ "recent-first, exactly as before any favorite state changed",
			recentFirstOrder, sourceNames(LootTrackerSnapshot.from(index, preferences).getAllSources()));
	}

	// Regression: favoriting/unfavoriting a card is a pure state toggle
	// as far as the All view is concerned -- clicking the star must never
	// cause that card to visibly jump elsewhere.
	@Test
	public void favoritingThenUnfavoriting_neverChangesAllViewPosition()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Gargoyles", 412, T0, new LootTrackerItem(561, "Nature rune", 5)));
		index.apply(record("e2", "Zulrah", 2042, T0.plusSeconds(60), new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e3", "Basilisk Knight", 7999, T0.plusSeconds(120), new LootTrackerItem(560, "Death rune", 3)));

		List<String> order = Arrays.asList("Basilisk Knight", "Zulrah", "Gargoyles");
		assertEquals(order, sourceNames(LootTrackerSnapshot.from(index, preferences).getAllSources()));

		String zulrahKey = LootTrackerIndex.sourceKey("Zulrah", 2042);
		preferences.setFavorite(zulrahKey, true);
		assertEquals("favoriting the OLDEST-observed source in the list must not top-load it",
			order, sourceNames(LootTrackerSnapshot.from(index, preferences).getAllSources()));

		preferences.setFavorite(zulrahKey, false);
		assertEquals("unfavoriting must likewise never change All-view position",
			order, sourceNames(LootTrackerSnapshot.from(index, preferences).getAllSources()));
	}

	@Test
	public void favoritesView_isRecentFirst()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e2", "Basilisk Knight", 7999, T0.plusSeconds(120), new LootTrackerItem(560, "Death rune", 3)));
		index.apply(record("e3", "Vorkath", 8061, T0.plusSeconds(60), new LootTrackerItem(1, "Dragonbone", 1)));

		preferences.setFavorite(LootTrackerIndex.sourceKey("Zulrah", 2042), true);
		preferences.setFavorite(LootTrackerIndex.sourceKey("Basilisk Knight", 7999), true);
		preferences.setFavorite(LootTrackerIndex.sourceKey("Vorkath", 8061), true);

		List<LootTrackerSnapshot.SourceEntry> favorites = LootTrackerSnapshot.from(index, preferences).getFavoriteSources();

		assertEquals(Arrays.asList("Basilisk Knight", "Vorkath", "Zulrah"), sourceNames(favorites));
	}

	@Test
	public void equalTimestamps_tieBreakDeterministicallyBySourceKey()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e2", "Basilisk Knight", 7999, T0, new LootTrackerItem(560, "Death rune", 3)));
		index.apply(record("e3", "Gargoyles", 412, T0, new LootTrackerItem(561, "Nature rune", 5)));

		List<LootTrackerSnapshot.SourceEntry> first = LootTrackerSnapshot.from(index, preferences).getAllSources();
		List<LootTrackerSnapshot.SourceEntry> second = LootTrackerSnapshot.from(index, preferences).getAllSources();

		// Same (equal-timestamp) input always yields the same order --
		// deterministic, not incidental HashMap/iteration-order luck.
		assertEquals(sourceNames(first), sourceNames(second));

		// All three sources share the same lastObservedAt (T0), so the
		// comparator falls through to sourceKey ascending. Canonical
		// entries are keyed "canon:<name>" (see LootTrackerSnapshot's own
		// CANONICAL PLAYER-FACING IDENTITY javadoc) so ordering among
		// them is exactly alphabetical-by-name: "canon:Basilisk Knight" <
		// "canon:Gargoyles" < "canon:Zulrah" -- assert that directly
		// against the real (unsorted) output, not a sorted-vs-sorted
		// tautology (sourceKeysSorted would pass for ANY ordering of the
		// same three keys, so it is not used here).
		assertEquals(Arrays.asList("canon:Basilisk Knight", "canon:Gargoyles", "canon:Zulrah"), sourceKeys(first));
		assertEquals(Arrays.asList("Basilisk Knight", "Gargoyles", "Zulrah"), sourceNames(first));
	}

	@Test
	public void rebuildFromTelemetry_producesSameOrderingAsLiveIngestion_regardlessOfInsertionOrder()
	{
		LootTrackerIndex liveOrder = new LootTrackerIndex();
		liveOrder.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		liveOrder.apply(record("e2", "Basilisk Knight", 7999, T0.plusSeconds(120), new LootTrackerItem(560, "Death rune", 3)));
		liveOrder.apply(record("e3", "Gargoyles", 412, T0.plusSeconds(60), new LootTrackerItem(561, "Nature rune", 5)));

		// Same three records, applied to a fresh index in a completely
		// different order -- simulating a durable rebuild reading
		// events.jsonl lines in whatever order they happen to appear.
		LootTrackerIndex rebuiltOrder = new LootTrackerIndex();
		rebuiltOrder.apply(record("e3", "Gargoyles", 412, T0.plusSeconds(60), new LootTrackerItem(561, "Nature rune", 5)));
		rebuiltOrder.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		rebuiltOrder.apply(record("e2", "Basilisk Knight", 7999, T0.plusSeconds(120), new LootTrackerItem(560, "Death rune", 3)));

		List<String> liveNames = sourceNames(LootTrackerSnapshot.from(liveOrder, preferences).getAllSources());
		List<String> rebuiltNames = sourceNames(LootTrackerSnapshot.from(rebuiltOrder, preferences).getAllSources());

		assertEquals(liveNames, rebuiltNames);
		assertEquals(Arrays.asList("Basilisk Knight", "Gargoyles", "Zulrah"), rebuiltNames);
	}

	@Test
	public void resetWatermark_ordersBy_postResetLastObservedAt_notRawHistory()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		// Zulrah's RAW last activity is the newest of all -- but everything
		// after its own reset watermark is filtered away except one old-ish
		// record, so its VISIBLE last-observed time is much older.
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e2", "Zulrah", 2042, T0.plusSeconds(10), new LootTrackerItem(2444, "Zulrah's scales", 50)));
		index.apply(record("e3", "Gargoyles", 412, T0.plusSeconds(50), new LootTrackerItem(561, "Nature rune", 5)));

		// Reset Zulrah at T0+400 -- both of its own records predate the
		// watermark and are filtered out entirely, so Zulrah does not
		// appear at all (nothing left to order).
		preferences.resetSource(LootTrackerIndex.sourceKey("Zulrah", 2042), T0.plusSeconds(400));

		List<LootTrackerSnapshot.SourceEntry> sources = LootTrackerSnapshot.from(index, preferences).getAllSources();
		assertEquals(Arrays.asList("Gargoyles"), sourceNames(sources));
	}

	// =====================================================================
	// ADDED (Loot Tracker duplicate-card fix pass). Covers
	// LootTrackerSnapshot's CANONICAL PLAYER-FACING IDENTITY grouping --
	// see that class's own javadoc for the full rationale (OSRS/RuneLite
	// composition ids genuinely vary for what is visually one logical
	// NPC to the player, e.g. the live-confirmed "Desert Wolf"/"Goat"
	// duplicate cards).
	// =====================================================================

	@Test
	public void multipleRuntimeIdsForSameDisplayName_produceOnePlayerFacingCard()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		// Two different composition ids ("735"/"737"), identical display
		// name -- exactly the live-confirmed Bandit/Desert Wolf/Goat
		// pattern.
		index.apply(record("e1", "Bandit", 735, T0, new LootTrackerItem(526, "Bones", 1)));
		index.apply(record("e2", "Bandit", 737, T0.plusSeconds(60), new LootTrackerItem(526, "Bones", 1)));

		List<LootTrackerSnapshot.SourceEntry> sources = LootTrackerSnapshot.from(index, preferences).getAllSources();

		assertEquals(1, sources.size());
		assertEquals("Bandit", sources.get(0).getSourceName());
	}

	@Test
	public void genuinelyDistinctNamedSources_remainSeparate()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e2", "Vorkath", 8061, T0, new LootTrackerItem(1, "Dragonbone", 1)));

		List<LootTrackerSnapshot.SourceEntry> sources = LootTrackerSnapshot.from(index, preferences).getAllSources();

		assertEquals(2, sources.size());
	}

	@Test
	public void mergedCard_killCountAndItemTotals_areCorrectlySummedAcrossRawVariants()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Bandit", 735, T0, new LootTrackerItem(526, "Bones", 1)));
		index.apply(record("e2", "Bandit", 737, T0.plusSeconds(60), new LootTrackerItem(526, "Bones", 1)));
		index.apply(record("e3", "Bandit", 735, T0.plusSeconds(120), new LootTrackerItem(526, "Bones", 1), new LootTrackerItem(1755, "Loop half of a key", 1)));

		LootTrackerSnapshot.SourceEntry merged = LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);

		// 3 raw SERVER_NPC_LOOT occurrences across both composition ids.
		assertEquals(3, merged.getKillCount());
		assertEquals(2, merged.getItemTotals().size());
		for (LootTrackerSnapshot.ItemEntry item : merged.getItemTotals())
		{
			if (item.getItemIdValue() == 526)
			{
				assertEquals(3L, item.getQuantity());
			}
			else
			{
				assertEquals(1755, item.getItemIdValue());
				assertEquals(1L, item.getQuantity());
			}
		}
	}

	@Test
	public void mergedCard_individualRecords_areOneTrueChronologicalTimelineAcrossRawVariants()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		// Applied out of chronological order (737's record observed
		// BEFORE 735's second record) -- the merged timeline must still
		// come out oldest-first by observedAt, not by raw-source or
		// insertion order.
		index.apply(record("e1", "Bandit", 735, T0, new LootTrackerItem(526, "Bones", 1)));
		index.apply(record("e3", "Bandit", 735, T0.plusSeconds(120), new LootTrackerItem(9, "Granite dust", 1)));
		index.apply(record("e2", "Bandit", 737, T0.plusSeconds(60), new LootTrackerItem(561, "Nature rune", 5)));

		LootTrackerSnapshot.SourceEntry merged = LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);

		assertEquals(3, merged.getRecords().size());
		assertEquals("Bones", merged.getRecords().get(0).getItems().get(0).getItemName());
		assertEquals("Nature rune", merged.getRecords().get(1).getItems().get(0).getItemName());
		assertEquals("Granite dust", merged.getRecords().get(2).getItems().get(0).getItemName());
	}

	@Test
	public void mergedCard_rawSourceKeys_preservesEveryRawCompositionIdentity()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Bandit", 735, T0, new LootTrackerItem(526, "Bones", 1)));
		index.apply(record("e2", "Bandit", 737, T0.plusSeconds(60), new LootTrackerItem(526, "Bones", 1)));

		LootTrackerSnapshot.SourceEntry merged = LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);

		assertEquals(2, merged.getRawSourceKeys().size());
		assertTrue(merged.getRawSourceKeys().contains(LootTrackerIndex.sourceKey("Bandit", 735)));
		assertTrue(merged.getRawSourceKeys().contains(LootTrackerIndex.sourceKey("Bandit", 737)));
	}

	@Test
	public void favoritingOneRawVariant_showsTheMergedCardAsFavorited()
	{
		// Simulates a favorite set BEFORE this fix shipped, under the OLD
		// raw sourceKey -- proves no explicit preference migration is
		// needed (see LootTrackerSnapshot's own javadoc).
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Bandit", 735, T0, new LootTrackerItem(526, "Bones", 1)));
		index.apply(record("e2", "Bandit", 737, T0.plusSeconds(60), new LootTrackerItem(526, "Bones", 1)));

		preferences.setFavorite(LootTrackerIndex.sourceKey("Bandit", 735), true);

		LootTrackerSnapshot.SourceEntry merged = LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);
		assertTrue(merged.isFavorite());
		assertEquals(1, LootTrackerSnapshot.from(index, preferences).getFavoriteSources().size());
	}

	@Test
	public void hidingAnItemUnderOneRawVariant_hidesItOnTheMergedCard()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Bandit", 735, T0, new LootTrackerItem(526, "Bones", 1)));
		index.apply(record("e2", "Bandit", 737, T0.plusSeconds(60), new LootTrackerItem(526, "Bones", 1)));

		preferences.setItemHidden(LootTrackerIndex.sourceKey("Bandit", 737), 526, true);

		LootTrackerSnapshot.SourceEntry merged = LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);
		assertTrue(merged.getItemTotals().get(0).isHidden());
	}

	@Test
	public void resettingOneRawVariantsWatermark_onlyFiltersThatVariant_mergedTotalsStillCorrect()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Bandit", 735, T0, new LootTrackerItem(526, "Bones", 1)));
		index.apply(record("e2", "Bandit", 737, T0.plusSeconds(60), new LootTrackerItem(526, "Bones", 1)));

		// Reset ONLY the 735 variant, after its own record -- that raw
		// source contributes nothing further, but 737's record (a
		// DIFFERENT raw source, never told about 735's reset) still
		// surfaces normally on the merged card.
		preferences.resetSource(LootTrackerIndex.sourceKey("Bandit", 735), T0.plusSeconds(30));

		LootTrackerSnapshot.SourceEntry merged = LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);
		assertEquals(1, merged.getKillCount());
		assertEquals(1L, merged.getItemTotals().get(0).getQuantity());
	}

	private static List<String> sourceNames(List<LootTrackerSnapshot.SourceEntry> sources)
	{
		List<String> names = new java.util.ArrayList<>();
		for (LootTrackerSnapshot.SourceEntry entry : sources)
		{
			names.add(entry.getSourceName());
		}
		return names;
	}

	/** Actual (unsorted) sourceKey order, as rendered -- use this to assert real ordering, never a sorted-vs-sorted comparison. */
	private static List<String> sourceKeys(List<LootTrackerSnapshot.SourceEntry> sources)
	{
		List<String> keys = new java.util.ArrayList<>();
		for (LootTrackerSnapshot.SourceEntry entry : sources)
		{
			keys.add(entry.getSourceKey());
		}
		return keys;
	}
}
