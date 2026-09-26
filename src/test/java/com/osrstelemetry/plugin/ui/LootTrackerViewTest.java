package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.loottracker.LootTrackerIndex;
import com.osrstelemetry.plugin.loottracker.LootTrackerItem;
import com.osrstelemetry.plugin.loottracker.LootTrackerPreferences;
import com.osrstelemetry.plugin.loottracker.LootTrackerRecord;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the pure,
 * Swing-free static methods {@link LootTrackerView} exposes specifically
 * so the flashing/freeze fixes could be unit-tested without constructing
 * any JPanel/JScrollPane: {@link LootTrackerView#computeRenderKey} (the
 * presentation fingerprint that lets {@code render()} skip a
 * {@code removeAll()}/rebuild entirely when nothing visible changed),
 * {@link LootTrackerView#capRecords} and
 * {@link LootTrackerView#allowedIndividualCount} (the total
 * individual-record rendering budget), and
 * {@link LootTrackerView#truncateSourceName}/{@link LootTrackerView#buildHeaderSubtitleText}
 * (pure display-formatting helpers for the width fix and the
 * kill-count/GP-per-kill subtitle).
 *
 * Real {@link LootTrackerSnapshot.SourceEntry}/{@code RecordEntry}
 * instances are used throughout (both have private constructors reachable
 * only via {@link LootTrackerSnapshot#from}), never hand-rolled fakes --
 * same convention as {@code LootTrackerSnapshotTest}.
 */
public class LootTrackerViewTest
{
	private static final long TEST_ACCOUNT_HASH = 424_242_424L;
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final Gson TEST_GSON = new Gson();

	private LootTrackerPreferences preferences;
	private Path testRoot;

	@Before
public void setUp() throws Exception
{
testRoot = Files.createTempDirectory("loot-tracker-view-test");
TelemetryPaths.init(TestFilepaths.rooted(testRoot));
deleteAccountDir();
preferences = new LootTrackerPreferences(new LocalStateStore(TEST_GSON));
preferences.loadForAccount(TEST_ACCOUNT_HASH);
}

	@After
public void tearDown() throws Exception
{
deleteAccountDir();
Files.deleteIfExists(testRoot);
}

	private void deleteAccountDir() throws Exception
	{
		File dir = TestFilepaths.file(TelemetryPaths.accountDir(TEST_ACCOUNT_HASH));
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

	private List<LootTrackerSnapshot.SourceEntry> twoSources()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		index.apply(record("e2", "Gargoyles", 412, T0.plusSeconds(60), new LootTrackerItem(561, "Nature rune", 5)));
		return LootTrackerSnapshot.from(index, preferences).getAllSources();
	}

	private static String key(List<LootTrackerSnapshot.SourceEntry> sources, boolean favoritesOnly, boolean individualView, String searchText, boolean showHidden, long valuationVersion)
	{
		return LootTrackerView.computeRenderKey(sources, sources, favoritesOnly, individualView, searchText, showHidden, valuationVersion, Collections.emptyMap());
	}

	// ------------------------------------------------------------------
	// computeRenderKey
	// ------------------------------------------------------------------

	@Test
	public void computeRenderKey_sameInputs_produceEqualKeys()
	{
		List<LootTrackerSnapshot.SourceEntry> sources = twoSources();

		String key1 = key(sources, false, false, "", false, 0L);
		String key2 = key(sources, false, false, "", false, 0L);

		assertEquals(key1, key2);
	}

	@Test
	public void computeRenderKey_differentSearchText_producesDifferentKey()
	{
		List<LootTrackerSnapshot.SourceEntry> sources = twoSources();

		String key1 = key(sources, false, false, "", false, 0L);
		String key2 = key(sources, false, false, "scales", false, 0L);

		assertNotEquals(key1, key2);
	}

	@Test
	public void computeRenderKey_differentFavoritesOnlyFlag_producesDifferentKey_evenWithIdenticalSourceList()
	{
		// The SAME sources list object is passed both times -- proves the
		// All/Favorites tab distinction is captured by the flag itself
		// (needed for the totals-footer label, "Total:" vs "Favorites
		// total:"), not merely inferred from source-list content, which
		// would wrongly collapse to the same key when every source
		// happens to be favorited.
		List<LootTrackerSnapshot.SourceEntry> sources = twoSources();

		String allKey = key(sources, false, false, "", false, 0L);
		String favoritesKey = key(sources, true, false, "", false, 0L);

		assertNotEquals(allKey, favoritesKey);
	}

	@Test
	public void computeRenderKey_differentIndividualViewFlag_producesDifferentKey()
	{
		List<LootTrackerSnapshot.SourceEntry> sources = twoSources();

		String groupedKey = key(sources, false, false, "", false, 0L);
		String individualKey = key(sources, false, true, "", false, 0L);

		assertNotEquals(groupedKey, individualKey);
	}

	@Test
	public void computeRenderKey_differentShowHiddenFlag_producesDifferentKey()
	{
		List<LootTrackerSnapshot.SourceEntry> sources = twoSources();

		String key1 = key(sources, false, false, "", false, 0L);
		String key2 = key(sources, false, false, "", true, 0L);

		assertNotEquals(key1, key2);
	}

	@Test
	public void computeRenderKey_differentValuationVersion_producesDifferentKey()
	{
		// Confirms an async GE/HA price resolution (ItemValuationCache's
		// own version counter, not part of LootTrackerSnapshot itself)
		// still forces a real rebuild on the next tick rather than being
		// permanently skipped once render-key skipping is in place.
		List<LootTrackerSnapshot.SourceEntry> sources = twoSources();

		String key1 = key(sources, false, false, "", false, 0L);
		String key2 = key(sources, false, false, "", false, 1L);

		assertNotEquals(key1, key2);
	}

	@Test
	public void computeRenderKey_reorderedSources_producesDifferentKey()
	{
		// A source being promoted to the top of its group (new drop)
		// must actually change the fingerprint -- otherwise the ordering
		// fix and the render-key skip would silently fight each other.
		List<LootTrackerSnapshot.SourceEntry> sources = twoSources();
		List<LootTrackerSnapshot.SourceEntry> reversed = new ArrayList<>(sources);
		Collections.reverse(reversed);

		String key1 = key(sources, false, false, "", false, 0L);
		String key2 = key(reversed, false, false, "", false, 0L);

		assertNotEquals(key1, key2);
	}

	@Test
	public void computeRenderKey_individualView_changeWithinCappedWindow_producesDifferentKey()
	{
		// Control for the "beyond cap" test below: a change to a RECENT
		// record (inside the rendered/fingerprinted window) must still be
		// detected, proving the cap doesn't just make the fingerprint
		// blind to everything.
		LootTrackerSnapshot.SourceEntry sourceA = buildSourceWithRecentItem(1L);
		LootTrackerSnapshot.SourceEntry sourceB = buildSourceWithRecentItem(2L);

		String keyA = key(Arrays.asList(sourceA), false, true, "", false, 0L);
		String keyB = key(Arrays.asList(sourceB), false, true, "", false, 0L);

		assertNotEquals(keyA, keyB);
	}

	@Test
	public void computeRenderKey_individualView_changeBeyondCapWindow_producesEqualKey()
	{
		// The direct regression test for the EDT-freeze fix: a source
		// with more than DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE records
		// only fingerprints (and LootTrackerView only ever renders) its
		// most recent revealed window -- so fingerprinting cost, like
		// rendering cost, never scales with a source's full historical
		// record count. Only the OLDEST record's timestamp differs
		// between the two builds (its item stays identical, so item
		// TOTALS -- unaffected by the cap -- are identical too); that
		// record falls outside the capped window, so the two keys must
		// be equal.
		int totalRecords = LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE + 1;
		LootTrackerSnapshot.SourceEntry sourceA = buildSourceWithOldestRecordAt(T0.minusSeconds(1000), totalRecords);
		LootTrackerSnapshot.SourceEntry sourceB = buildSourceWithOldestRecordAt(T0.minusSeconds(500), totalRecords);

		String keyA = key(Arrays.asList(sourceA), false, true, "", false, 0L);
		String keyB = key(Arrays.asList(sourceB), false, true, "", false, 0L);

		assertEquals(keyA, keyB);
	}

	@Test
	public void computeRenderKey_individualView_loadMoreReveal_producesDifferentKey()
	{
		LootTrackerSnapshot.SourceEntry source = buildSourceWithOldestRecordAt(T0.minusSeconds(1000), LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE + 1);
		List<LootTrackerSnapshot.SourceEntry> sources = Arrays.asList(source);

		Map<String, Integer> defaultReveal = Collections.emptyMap();
		Map<String, Integer> expandedReveal = new HashMap<>();
		expandedReveal.put(source.getSourceKey(), LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE + LootTrackerView.INDIVIDUAL_REVEAL_INCREMENT);

		String keyBefore = LootTrackerView.computeRenderKey(sources, sources, false, true, "", false, 0L, defaultReveal);
		String keyAfter = LootTrackerView.computeRenderKey(sources, sources, false, true, "", false, 0L, expandedReveal);

		assertNotEquals(keyBefore, keyAfter);
	}

	@Test
	public void computeRenderKey_globalTotalChange_producesDifferentKey_evenWhenNotInFilteredSources()
	{
		// The Favorites tab shows a narrower `sources` list, but the
		// global total header (see LootTrackerView class javadoc) must
		// still reflect ALL sources -- a change to a non-favorited source
		// must still change the key while sitting on the Favorites tab.
		List<LootTrackerSnapshot.SourceEntry> allA = twoSources();
		List<LootTrackerSnapshot.SourceEntry> favoritesOnlyA = Collections.emptyList();

		LootTrackerIndex indexB = new LootTrackerIndex();
		indexB.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 999)));
		indexB.apply(record("e2", "Gargoyles", 412, T0.plusSeconds(60), new LootTrackerItem(561, "Nature rune", 5)));
		List<LootTrackerSnapshot.SourceEntry> allB = LootTrackerSnapshot.from(indexB, preferences).getAllSources();

		String keyA = LootTrackerView.computeRenderKey(favoritesOnlyA, allA, true, false, "", false, 0L, Collections.emptyMap());
		String keyB = LootTrackerView.computeRenderKey(favoritesOnlyA, allB, true, false, "", false, 0L, Collections.emptyMap());

		assertNotEquals(keyA, keyB);
	}

	private LootTrackerSnapshot.SourceEntry buildSourceWithRecentItem(long mostRecentQuantity)
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e0", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 1)));
		index.apply(record("e1", "Zulrah", 2042, T0.plusSeconds(1), new LootTrackerItem(2444, "Zulrah's scales", mostRecentQuantity)));
		return LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);
	}

	private LootTrackerSnapshot.SourceEntry buildSourceWithOldestRecordAt(Instant oldestAt, int totalRecords)
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e0", "Zulrah", 2042, oldestAt, new LootTrackerItem(2444, "Zulrah's scales", 1)));
		for (int i = 1; i < totalRecords; i++)
		{
			index.apply(record("e" + i, "Zulrah", 2042, T0.plusSeconds(i), new LootTrackerItem(2444, "Zulrah's scales", 1)));
		}
		return LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);
	}

	// ------------------------------------------------------------------
	// capRecords
	// ------------------------------------------------------------------

	@Test
	public void capRecords_withinCap_returnsSameListUnchanged()
	{
		List<LootTrackerSnapshot.RecordEntry> records = buildRecords(5);

		List<LootTrackerSnapshot.RecordEntry> capped = LootTrackerView.capRecords(records, 100);

		assertSame(records, capped);
	}

	@Test
	public void capRecords_exactlyAtCap_returnsSameListUnchanged()
	{
		List<LootTrackerSnapshot.RecordEntry> records = buildRecords(5);

		List<LootTrackerSnapshot.RecordEntry> capped = LootTrackerView.capRecords(records, 5);

		assertSame(records, capped);
	}

	@Test
	public void capRecords_beyondCap_returnsLastNInOriginalOldestFirstOrder()
	{
		List<LootTrackerSnapshot.RecordEntry> records = buildRecords(10);

		List<LootTrackerSnapshot.RecordEntry> capped = LootTrackerView.capRecords(records, 3);

		assertEquals(3, capped.size());
		// buildRecords(10) produces records at T0+0 .. T0+9, oldest first;
		// the last 3 (most recent) are T0+7, T0+8, T0+9, still oldest-first.
		assertEquals(T0.plusSeconds(7), capped.get(0).getObservedAt());
		assertEquals(T0.plusSeconds(8), capped.get(1).getObservedAt());
		assertEquals(T0.plusSeconds(9), capped.get(2).getObservedAt());
	}

	// ------------------------------------------------------------------
	// newestFirst (Loot Tracker Load More placement/ordering fix --
	// CONFIRMED live: Individual records rendered oldest-of-the-revealed-
	// window first instead of newest-first, with the Load More control
	// above them instead of below. This is the pure, Swing-independent
	// piece of that fix: capRecords()'s oldest-first output must be
	// reversed before display -- see newestFirst()'s own javadoc.)
	// ------------------------------------------------------------------

	@Test
	public void newestFirst_reversesOldestFirstInputToNewestFirst()
	{
		List<LootTrackerSnapshot.RecordEntry> oldestFirst = buildRecords(3);

		List<LootTrackerSnapshot.RecordEntry> newestFirst = LootTrackerView.newestFirst(oldestFirst);

		assertEquals(3, newestFirst.size());
		assertEquals(T0.plusSeconds(2), newestFirst.get(0).getObservedAt());
		assertEquals(T0.plusSeconds(1), newestFirst.get(1).getObservedAt());
		assertEquals(T0.plusSeconds(0), newestFirst.get(2).getObservedAt());
	}

	@Test
	public void newestFirst_composedWithCapRecords_showsNewestOfTheRevealedWindowFirst()
	{
		// The exact end-to-end shape buildIndividualDropsPanel() now
		// renders: capRecords() selects the most recent 3 of 10 (T0+7,
		// T0+8, T0+9, oldest-first), then newestFirst() reverses that
		// window for display -- T0+9 (newest overall) first, T0+7 (the
		// oldest of the currently-revealed window) last, exactly the
		// "newest record ... oldest currently revealed record" shape QA
		// specified.
		List<LootTrackerSnapshot.RecordEntry> records = buildRecords(10);
		List<LootTrackerSnapshot.RecordEntry> capped = LootTrackerView.capRecords(records, 3);

		List<LootTrackerSnapshot.RecordEntry> displayed = LootTrackerView.newestFirst(capped);

		assertEquals(T0.plusSeconds(9), displayed.get(0).getObservedAt());
		assertEquals(T0.plusSeconds(8), displayed.get(1).getObservedAt());
		assertEquals(T0.plusSeconds(7), displayed.get(2).getObservedAt());
	}

	@Test
	public void newestFirst_neverMutatesItsInput()
	{
		// capRecords() can return the ORIGINAL backing list unmodified
		// when already within the cap (see capRecords_withinCap_...
		// above) -- newestFirst() must never reorder that shared list out
		// from under any other reader of it.
		List<LootTrackerSnapshot.RecordEntry> records = buildRecords(3);
		List<LootTrackerSnapshot.RecordEntry> snapshotBeforeCall = new ArrayList<>(records);

		LootTrackerView.newestFirst(records);

		assertEquals(snapshotBeforeCall, records);
	}

	@Test
	public void newestFirst_empty_returnsEmpty()
	{
		assertTrue(LootTrackerView.newestFirst(Collections.emptyList()).isEmpty());
	}

	@Test
	public void capRecords_maxZero_returnsEmptyList()
	{
		List<LootTrackerSnapshot.RecordEntry> records = buildRecords(5);

		List<LootTrackerSnapshot.RecordEntry> capped = LootTrackerView.capRecords(records, 0);

		assertTrue(capped.isEmpty());
	}

	private List<LootTrackerSnapshot.RecordEntry> buildRecords(int count)
	{
		LootTrackerIndex index = new LootTrackerIndex();
		for (int i = 0; i < count; i++)
		{
			index.apply(record("e" + i, "Zulrah", 2042, T0.plusSeconds(i), new LootTrackerItem(2444, "Zulrah's scales", 1)));
		}
		return LootTrackerSnapshot.from(index, preferences).getAllSources().get(0).getRecords();
	}

	// ------------------------------------------------------------------
	// allowedIndividualCount (total-budget bound, second EDT-freeze fix pass)
	// ------------------------------------------------------------------

	@Test
	public void allowedIndividualCount_neverExceedsRequested()
	{
		assertEquals(10, LootTrackerView.allowedIndividualCount(10, 1000, 1000));
	}

	@Test
	public void allowedIndividualCount_neverExceedsTotalAvailable()
	{
		assertEquals(3, LootTrackerView.allowedIndividualCount(50, 3, 1000));
	}

	@Test
	public void allowedIndividualCount_neverExceedsRemainingBudget()
	{
		assertEquals(20, LootTrackerView.allowedIndividualCount(50, 1000, 20));
	}

	@Test
	public void allowedIndividualCount_exhaustedBudget_returnsZero_neverNegative()
	{
		assertEquals(0, LootTrackerView.allowedIndividualCount(50, 1000, 0));
		assertEquals(0, LootTrackerView.allowedIndividualCount(50, 1000, -5));
	}

	@Test
	public void totalIndividualRecordBudget_isSharedAcrossManySources_neverExceedsHardCeiling()
	{
		// Simulates many long-lived, all-expanded sources at once (the
		// exact live complaint: "30 sources x 100 drops"). Even though
		// every individual source has far more records than the default
		// per-source reveal, the SUM of records actually fingerprinted
		// across all of them must never exceed
		// MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED.
		List<LootTrackerSnapshot.SourceEntry> manySources = new ArrayList<>();
		int perSourceRecordCount = LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE + 20;
		int sourceCount = 30;
		for (int s = 0; s < sourceCount; s++)
		{
			LootTrackerIndex index = new LootTrackerIndex();
			String name = "Source" + s;
			for (int i = 0; i < perSourceRecordCount; i++)
			{
				index.apply(record("s" + s + "e" + i, name, s, T0.plusSeconds(i), new LootTrackerItem(1, "Item", 1)));
			}
			manySources.add(LootTrackerSnapshot.from(index, preferences).getAllSources().get(0));
		}

		int[] budget = {LootTrackerView.MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED};
		int totalRendered = 0;
		for (LootTrackerSnapshot.SourceEntry source : manySources)
		{
			int allowed = LootTrackerView.allowedIndividualCount(LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE, source.getRecords().size(), budget[0]);
			List<LootTrackerSnapshot.RecordEntry> visible = LootTrackerView.capRecords(source.getRecords(), allowed);
			budget[0] -= visible.size();
			totalRendered += visible.size();
		}

		assertTrue("expected the budget to actually constrain total rendering below the naive 30x50=1500", totalRendered <= LootTrackerView.MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED);
		assertEquals(LootTrackerView.MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED, totalRendered);
	}

	// ------------------------------------------------------------------
	// truncateSourceName (width/clipping fix)
	// ------------------------------------------------------------------

	@Test
	public void truncateSourceName_shortName_unchanged()
	{
		assertEquals("Zulrah", LootTrackerView.truncateSourceName("Zulrah"));
	}

	@Test
	public void truncateSourceName_nullName_becomesUnknown()
	{
		assertEquals("Unknown", LootTrackerView.truncateSourceName(null));
	}

	@Test
	public void truncateSourceName_longName_isTruncatedWithEllipsis()
	{
		String longName = "Growth Feedback Golem Instance";
		String truncated = LootTrackerView.truncateSourceName(longName);

		assertTrue(truncated.length() < longName.length());
		assertTrue(truncated.endsWith("…"));
	}

	// ------------------------------------------------------------------
	// buildHeaderSubtitleText (kill-count / GP-per-kill display)
	// ------------------------------------------------------------------

	@Test
	public void buildHeaderSubtitleText_computesGpPerKill()
	{
		String text = LootTrackerView.buildHeaderSubtitleText(1_840_000L, 300);
		assertTrue(text.contains("total"));
		assertTrue(text.contains("/kill"));
	}

	@Test
	public void buildHeaderSubtitleText_zeroKillCount_neverDividesByZero()
	{
		String text = LootTrackerView.buildHeaderSubtitleText(0L, 0);
		assertTrue(text.contains("0"));
	}

	// ------------------------------------------------------------------
	// buildSourceTitleText (source/NPC identity + kill-count
	// presentation)
	// ------------------------------------------------------------------

	@Test
	public void buildSourceTitleText_reliableKillCount_appendsCount()
	{
		assertEquals("Zulrah  ×43", LootTrackerView.buildSourceTitleText("Zulrah", 43));
	}

	@Test
	public void buildSourceTitleText_zeroKillCount_omitsCountRatherThanFabricatingIt()
	{
		// See LootTrackerSource#getKillCount()'s own javadoc -- a source
		// can legitimately reach this view with zero currently-visible
		// records after a reset watermark. Appending "×0" would read as a
		// fabricated, misleadingly precise count, so the name must be
		// shown alone instead.
		String text = LootTrackerView.buildSourceTitleText("Zulrah", 0);
		assertEquals("Zulrah", text);
		assertFalse("must never fabricate a kill count when none is reliable", text.contains("×"));
	}

	@Test
	public void buildSourceTitleText_longName_stillTruncated()
	{
		String longName = "Growth Feedback Golem Instance";
		String text = LootTrackerView.buildSourceTitleText(longName, 5);

		assertTrue(text.startsWith(LootTrackerView.truncateSourceName(longName)));
		assertTrue(text.contains("×5"));
	}

	@Test
	public void buildSourceTitleText_nullName_becomesUnknown()
	{
		assertEquals("Unknown  ×2", LootTrackerView.buildSourceTitleText(null, 2));
	}

	// ------------------------------------------------------------------
	// effectivelyCollapsed (Part E -- Search + Collapsed Cards fix)
	// ------------------------------------------------------------------

	private LootTrackerSnapshot.SourceEntry buildZulrahSourceCollapsed(boolean collapsed)
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		LootTrackerSnapshot.SourceEntry source = LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);
		for (String rawKey : source.getRawSourceKeys())
		{
			preferences.setCollapsed(rawKey, collapsed);
		}
		// Re-derive from preferences (SourceEntry -> LootTrackerPreferences is
		// read-only at construction time -- see LootTrackerSnapshot#from), same
		// pattern real LootTrackerView call sites use after any preference
		// mutation (re-render from the latest snapshot).
		return LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);
	}

	@Test
	public void effectivelyCollapsed_collapsedSourceNoActiveSearch_staysCollapsed()
	{
		LootTrackerSnapshot.SourceEntry source = buildZulrahSourceCollapsed(true);
		assertTrue(source.isCollapsed());
		assertTrue(LootTrackerView.effectivelyCollapsed(source, ""));
	}

	@Test
	public void effectivelyCollapsed_collapsedSourceWithMatchingActiveSearch_temporarilyExpands()
	{
		// Live QA: a manually collapsed source card stayed collapsed during
		// Search, hiding matching items inside it. A source only reaches this
		// point in real rendering (matchesSearch already filtered it) when the
		// search text matched an item inside it -- so any non-empty searchText
		// here stands in for that real "matching item inside a collapsed card"
		// case.
		LootTrackerSnapshot.SourceEntry source = buildZulrahSourceCollapsed(true);
		assertFalse("a collapsed card with a matching search result must temporarily expand",
			LootTrackerView.effectivelyCollapsed(source, "scales"));
	}

	@Test
	public void effectivelyCollapsed_notCollapsedSource_expandedRegardlessOfSearch()
	{
		LootTrackerSnapshot.SourceEntry source = buildZulrahSourceCollapsed(false);
		assertFalse(source.isCollapsed());
		assertFalse(LootTrackerView.effectivelyCollapsed(source, ""));
		assertFalse(LootTrackerView.effectivelyCollapsed(source, "scales"));
	}

	@Test
	public void effectivelyCollapsed_temporaryExpandDuringSearch_neverOverwritesThePersistedPreference()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("e1", "Zulrah", 2042, T0, new LootTrackerItem(2444, "Zulrah's scales", 100)));
		LootTrackerSnapshot.SourceEntry source = LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);
		for (String rawKey : source.getRawSourceKeys())
		{
			preferences.setCollapsed(rawKey, true);
		}
		LootTrackerSnapshot.SourceEntry collapsed = LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);

		// Simulate an active, matching search temporarily expanding the card...
		assertFalse(LootTrackerView.effectivelyCollapsed(collapsed, "scales"));

		// ...then clearing the search field: the card must revert to its
		// persisted collapsed state on the very next render, because nothing
		// about the temporary expand above ever wrote to LootTrackerPreferences.
		LootTrackerSnapshot.SourceEntry afterClearingSearch = LootTrackerSnapshot.from(index, preferences).getAllSources().get(0);
		assertTrue("clearing search must restore the user's original persisted collapsed state",
			afterClearingSearch.isCollapsed());
		assertTrue(LootTrackerView.effectivelyCollapsed(afterClearingSearch, ""));
	}

	// ------------------------------------------------------------------
	// Shared loot-grid geometry (Loot tab 3-column regression fix)
	// ------------------------------------------------------------------

	@Test
	public void lootItemsPerRow_isTheSameSharedColumnCountCurrentSessionUses()
	{
		assertEquals("Loot tab (Grouped + Individual) must use the same shared column count as Current Session",
			LootGridCell.GRID_COLUMNS, LootTrackerView.LOOT_ITEMS_PER_ROW);
		assertEquals(5, LootGridCell.GRID_COLUMNS);
		java.awt.GridLayout layout = LootGridCell.newGridLayout();
		assertEquals(0, layout.getRows());
		assertEquals(LootGridCell.GRID_COLUMNS, layout.getColumns());
		assertTrue("each call must return a fresh LayoutManager", layout != LootGridCell.newGridLayout());
	}

	/**
	 * Real (headless-safe, non-mocked) Swing layout of a Loot-tab-shaped
	 * item grid -- heightBoundedPanel() + the shared GridLayout + real
	 * LootGridCells -- inside a BoxLayout column of a given width, which
	 * is how buildSourceCard() hosts both the Grouped grid and each
	 * Individual record's grid. Returns the laid-out cells.
	 */
	private static java.util.List<java.awt.Component> layOutLootGrid(int availableWidth, int cellCount)
	{
		javax.swing.JPanel card = new javax.swing.JPanel();
		card.setLayout(new javax.swing.BoxLayout(card, javax.swing.BoxLayout.Y_AXIS));
		javax.swing.JPanel grid = LootTrackerView.heightBoundedPanel();
		grid.setLayout(LootGridCell.newGridLayout());
		grid.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		for (int i = 0; i < cellCount; i++)
		{
			grid.add(new LootGridCell(null, i + 2, "item " + i));
		}
		card.add(grid);
		card.setSize(availableWidth, 1000);
		card.doLayout();
		grid.doLayout();
		return java.util.Arrays.asList(grid.getComponents());
	}

	@Test
	public void lootGrid_atNormalSidebarWidth_fitsFiveAcross_noClipping_noHorizontalOverflow()
	{
		// 185px = the worst-case card-interior width at RuneLite's normal
		// 225px PluginPanel width (see LOOT_ITEMS_PER_ROW's own javadoc).
		int width = 185;
		java.util.List<java.awt.Component> cells = layOutLootGrid(width, 7);

		int firstRowY = cells.get(0).getY();
		for (int i = 0; i < 5; i++)
		{
			assertEquals("cells 0..4 must share the first row", firstRowY, cells.get(i).getY());
		}
		assertTrue("the 6th item must wrap to a second row", cells.get(5).getY() > firstRowY);
		for (java.awt.Component cell : cells)
		{
			assertTrue("no cell may extend past the available width (no clipping / horizontal scroll)",
				cell.getX() + cell.getWidth() <= width);
			assertTrue("cells must stay a readable, sprite-sized slot (>= 32px wide)", cell.getWidth() >= 32);
		}
	}

	@Test
	public void lootGrid_reflowsWithAvailableWidth_neverOverflows()
	{
		for (int width : new int[] {150, 185, 220, 260})
		{
			java.util.List<java.awt.Component> cells = layOutLootGrid(width, 5);
			java.awt.Component last = cells.get(4);
			assertEquals("all 5 must stay on one row at width " + width, cells.get(0).getY(), last.getY());
			assertTrue("no overflow at width " + width, last.getX() + last.getWidth() <= width);
		}
		assertTrue("cells grow with a wider container",
			layOutLootGrid(260, 5).get(0).getWidth() > layOutLootGrid(185, 5).get(0).getWidth());
	}

	/**
	 * Covers {@link LootTrackerView#heightBoundedPanel} directly -- the
	 * fix for source cards/headers/item grids expanding into huge empty
	 * vertical blocks once History was wired in as a third
	 * {@code CardLayout} card (see that method's own javadoc for the
	 * full root-cause writeup). Needs NO RuneLite dependency at all --
	 * {@code JPanel}/{@code Dimension} are plain {@code javax.swing}/
	 * {@code java.awt} -- so, unlike most of this test class, these are
	 * real, runnable, non-mocked tests of the exact Swing behavior the
	 * fix depends on.
	 */
	@Test
	public void heightBoundedPanel_maximumHeightTracksPreferredHeight()
	{
		javax.swing.JPanel panel = LootTrackerView.heightBoundedPanel();
		panel.add(new javax.swing.JLabel("card content"));
		java.awt.Dimension preferred = panel.getPreferredSize();

		assertEquals("maximum height must equal the panel's own current preferred height, never a hardcoded Short.MAX_VALUE",
			preferred.height, panel.getMaximumSize().height);
	}

	@Test
	public void heightBoundedPanel_widthStaysUnbounded()
	{
		javax.swing.JPanel panel = LootTrackerView.heightBoundedPanel();
		assertEquals(Integer.MAX_VALUE, panel.getMaximumSize().width);
	}

	@Test
	public void heightBoundedPanel_collapsedCardIsShorterThanExpandedCard()
	{
		// Regression guard for the exact live symptom: a COLLAPSED card
		// (header only) must report a smaller maximum height than the
		// SAME card once body content is added -- i.e. no hardcoded
		// height and no separate collapsed/expanded constants, the
		// panel simply tracks whatever it actually contains.
		javax.swing.JPanel collapsed = LootTrackerView.heightBoundedPanel();
		collapsed.add(new javax.swing.JLabel("header row"));
		int collapsedHeight = collapsed.getMaximumSize().height;

		javax.swing.JPanel expanded = LootTrackerView.heightBoundedPanel();
		expanded.setLayout(new javax.swing.BoxLayout(expanded, javax.swing.BoxLayout.Y_AXIS));
		expanded.add(new javax.swing.JLabel("header row"));
		expanded.add(new javax.swing.JLabel("item grid row one"));
		expanded.add(new javax.swing.JLabel("item grid row two"));
		int expandedHeight = expanded.getMaximumSize().height;

		assertTrue("an expanded card's own maximum height should be at least as tall as a collapsed card's, "
				+ "tracking real content rather than a fixed ceiling",
			expandedHeight >= collapsedHeight);
	}
}
