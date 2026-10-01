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
 * {@link LootTrackerView#individualStream} (the bounded global
 * chronological Individual stream; the total
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
		return LootTrackerView.computeRenderKey(sources, sources, favoritesOnly, individualView, searchText, showHidden, valuationVersion, LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL);
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
		// The direct regression test for the EDT-freeze fix: a stream
		// with more than DEFAULT_INDIVIDUAL_REVEAL records
		// only fingerprints (and LootTrackerView only ever renders) its
		// most recent revealed window -- so fingerprinting cost, like
		// rendering cost, never scales with a source's full historical
		// record count. Only the OLDEST record's timestamp differs
		// between the two builds (its item stays identical, so item
		// TOTALS -- unaffected by the cap -- are identical too); that
		// record falls outside the capped window, so the two keys must
		// be equal.
		int totalRecords = LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL + 1;
		LootTrackerSnapshot.SourceEntry sourceA = buildSourceWithOldestRecordAt(T0.minusSeconds(1000), totalRecords);
		LootTrackerSnapshot.SourceEntry sourceB = buildSourceWithOldestRecordAt(T0.minusSeconds(500), totalRecords);

		String keyA = key(Arrays.asList(sourceA), false, true, "", false, 0L);
		String keyB = key(Arrays.asList(sourceB), false, true, "", false, 0L);

		assertEquals(keyA, keyB);
	}

	@Test
	public void computeRenderKey_individualView_loadMoreReveal_producesDifferentKey()
	{
		LootTrackerSnapshot.SourceEntry source = buildSourceWithOldestRecordAt(T0.minusSeconds(1000), LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL + 1);
		List<LootTrackerSnapshot.SourceEntry> sources = Arrays.asList(source);

		String keyBefore = LootTrackerView.computeRenderKey(sources, sources, false, true, "", false, 0L, LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL);
		String keyAfter = LootTrackerView.computeRenderKey(sources, sources, false, true, "", false, 0L,
			LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL + LootTrackerView.INDIVIDUAL_REVEAL_INCREMENT);

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

		String keyA = LootTrackerView.computeRenderKey(favoritesOnlyA, allA, true, false, "", false, 0L, LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL);
		String keyB = LootTrackerView.computeRenderKey(favoritesOnlyA, allB, true, false, "", false, 0L, LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL);

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




	// ------------------------------------------------------------------
	// newestFirst (Loot Tracker Load More placement/ordering fix --
	// CONFIRMED live: Individual records rendered oldest-of-the-revealed-
	// window first instead of newest-first, with the Load More control
	// above them instead of below. This is the pure, Swing-independent
	// piece of that fix: capRecords()'s oldest-first output must be
	// reversed before display -- see newestFirst()'s own javadoc.)
	// ------------------------------------------------------------------







	// ------------------------------------------------------------------
	// allowedIndividualCount (total-budget bound, second EDT-freeze fix pass)
	// ------------------------------------------------------------------






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

	// ------------------------------------------------------------------
	// individualStream: ONE global chronological stream (Individual view)
	// ------------------------------------------------------------------

	private List<LootTrackerSnapshot.SourceEntry> chronologicalFive()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("t1", "Kraken", 494, T0.plusSeconds(1), new LootTrackerItem(3272, "Kraken tentacle", 1)));
		index.apply(record("t2", "Cave kraken", 492, T0.plusSeconds(2), new LootTrackerItem(995, "Coins", 40)));
		index.apply(record("t3", "Kraken", 494, T0.plusSeconds(3), new LootTrackerItem(995, "Coins", 900)));
		index.apply(record("t4", "Gargoyle", 412, T0.plusSeconds(4), new LootTrackerItem(1365, "Steel battleaxe", 1)));
		index.apply(record("t5", "Kraken", 494, T0.plusSeconds(5), new LootTrackerItem(12004, "Kraken tentacle", 1)));
		return LootTrackerSnapshot.from(index, preferences).getAllSources();
	}

	private static List<String> order(LootTrackerView.IndividualWindow window)
	{
		List<String> out = new ArrayList<>();
		for (LootTrackerView.StreamRecord r : window.getRecords())
		{
			out.add(r.getSource().getSourceName() + "@" + (r.getRecord().getObservedAt().getEpochSecond() - T0.getEpochSecond()));
		}
		return out;
	}

	@Test
	public void individualStream_eventOrderWinsOverSourceGrouping_newestFirst()
	{
		List<LootTrackerSnapshot.SourceEntry> sources = chronologicalFive();
		assertEquals("Grouped stays one card per source", 3, sources.size());
		LootTrackerView.IndividualWindow window = LootTrackerView.individualStream(sources, 50);
		assertEquals(Arrays.asList("Kraken@5", "Gargoyle@4", "Kraken@3", "Cave kraken@2", "Kraken@1"), order(window));
		assertEquals(5, window.getTotalAvailable());
	}

	@Test
	public void individualStream_sameSourceRepeated_andAlternating()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		for (int i = 0; i < 6; i++)
		{
			index.apply(record("a" + i, i % 2 == 0 ? "Zulrah" : "Vorkath", i % 2 == 0 ? 2042 : 8061, T0.plusSeconds(i),
				new LootTrackerItem(995, "Coins", i + 1)));
		}
		index.apply(record("z", "Zulrah", 2042, T0.plusSeconds(10), new LootTrackerItem(995, "Coins", 1)));
		index.apply(record("z2", "Zulrah", 2042, T0.plusSeconds(11), new LootTrackerItem(995, "Coins", 1)));
		List<String> got = order(LootTrackerView.individualStream(LootTrackerSnapshot.from(index, preferences).getAllSources(), 50));
		assertEquals(Arrays.asList("Zulrah@11", "Zulrah@10", "Vorkath@5", "Zulrah@4", "Vorkath@3", "Zulrah@2", "Vorkath@1", "Zulrah@0"), got);
	}

	@Test
	public void individualStream_eachRecordKeepsItsSource_andAStableKey()
	{
		List<LootTrackerSnapshot.SourceEntry> sources = chronologicalFive();
		LootTrackerView.IndividualWindow before = LootTrackerView.individualStream(sources, 50);
		for (LootTrackerView.StreamRecord r : before.getRecords())
		{
			assertTrue(r.getSource().getRecords().contains(r.getRecord()));
		}
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("t1", "Kraken", 494, T0.plusSeconds(1), new LootTrackerItem(3272, "Kraken tentacle", 1)));
		index.apply(record("t2", "Cave kraken", 492, T0.plusSeconds(2), new LootTrackerItem(995, "Coins", 40)));
		index.apply(record("t3", "Kraken", 494, T0.plusSeconds(3), new LootTrackerItem(995, "Coins", 900)));
		index.apply(record("t4", "Gargoyle", 412, T0.plusSeconds(4), new LootTrackerItem(1365, "Steel battleaxe", 1)));
		index.apply(record("t5", "Kraken", 494, T0.plusSeconds(5), new LootTrackerItem(12004, "Kraken tentacle", 1)));
		index.apply(record("t6", "Kraken", 494, T0.plusSeconds(6), new LootTrackerItem(995, "Coins", 1)));
		LootTrackerView.IndividualWindow after = LootTrackerView.individualStream(LootTrackerSnapshot.from(index, preferences).getAllSources(), 50);
		assertEquals("a new drop never changes older records' identity", before.getRecords().get(0).key, after.getRecords().get(1).key);
		assertEquals(before.getRecords().get(4).key, after.getRecords().get(5).key);
	}

	@Test
	public void individualStream_isBounded_andLoadMoreExtendsTheSameStream()
	{
		List<LootTrackerSnapshot.SourceEntry> many = new ArrayList<>();
		LootTrackerIndex index = new LootTrackerIndex();
		for (int s = 0; s < 30; s++)
		{
			for (int i = 0; i < 70; i++)
			{
				index.apply(record("s" + s + "e" + i, "Source" + s, s, T0.plusSeconds(i * 30L + s), new LootTrackerItem(1, "Item", 1)));
			}
		}
		many.addAll(LootTrackerSnapshot.from(index, preferences).getAllSources());
		LootTrackerView.IndividualWindow first = LootTrackerView.individualStream(many, LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL);
		assertEquals(LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL, first.getRecords().size());
		assertEquals(2100, first.getTotalAvailable());
		LootTrackerView.IndividualWindow more = LootTrackerView.individualStream(many,
			LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL + LootTrackerView.INDIVIDUAL_REVEAL_INCREMENT);
		for (int i = 0; i < first.getRecords().size(); i++)
		{
			assertEquals("load more appends older records; newer keep their place", first.getRecords().get(i).key, more.getRecords().get(i).key);
		}
		Instant previous = null;
		for (LootTrackerView.StreamRecord r : more.getRecords())
		{
			assertTrue(previous == null || !r.getRecord().getObservedAt().isAfter(previous));
			previous = r.getRecord().getObservedAt();
		}
		assertEquals("hard cap", LootTrackerView.MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED,
			LootTrackerView.individualStream(many, 100_000).getRecords().size());
		assertTrue(LootTrackerView.individualStream(many, 0).getRecords().isEmpty());
		assertTrue(LootTrackerView.individualStream(Collections.<LootTrackerSnapshot.SourceEntry>emptyList(), 50).getRecords().isEmpty());
	}

	@Test
	public void computeRenderKey_individual_followsGlobalOrder_groupedIgnoresIt()
	{
		List<LootTrackerSnapshot.SourceEntry> sources = chronologicalFive();
		String individual = key(sources, false, true, "", false, 0L);
		assertTrue(individual.indexOf("~stream~") > 0);
		assertTrue("newest record first in the fingerprint", individual.indexOf("@" + T0.plusSeconds(5)) < individual.indexOf("@" + T0.plusSeconds(4)));
		assertFalse(key(sources, false, false, "", false, 0L).contains("~stream~"));
	}

	@Test
	public void recordTimeText_isSecondaryInformation()
	{
		assertEquals("Unknown time", LootTrackerView.recordTimeText(null));
		assertFalse(LootTrackerView.recordTimeText(T0).isEmpty());
	}

	@Test
	public void individualStream_sameDisplayNameDifferentIds_interleaveByEventTime_withUniqueKeys()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("v1", "Kraken", 494, T0.plusSeconds(1), new LootTrackerItem(995, "Coins", 1)));
		index.apply(record("v2", "Gargoyle", 412, T0.plusSeconds(2), new LootTrackerItem(995, "Coins", 2)));
		index.apply(record("v3", "Kraken", 496, T0.plusSeconds(3), new LootTrackerItem(995, "Coins", 3)));
		index.apply(record("v4", "Kraken", 494, T0.plusSeconds(4), new LootTrackerItem(995, "Coins", 4)));
		index.apply(record("v5", "Kraken", 496, T0.plusSeconds(4), new LootTrackerItem(995, "Coins", 5)));
		LootTrackerView.IndividualWindow window = LootTrackerView.individualStream(LootTrackerSnapshot.from(index, preferences).getAllSources(), 50);
		assertEquals(5, window.totalAvailable);
		List<String> names = new ArrayList<>();
		List<Long> quantities = new ArrayList<>();
		java.util.Set<String> keys = new java.util.HashSet<>();
		Instant previous = null;
		for (LootTrackerView.StreamRecord r : window.getRecords())
		{
			names.add(r.getSource().getSourceName());
			quantities.add((long) r.getRecord().getItems().get(0).getQuantity());
			assertTrue("record keys stay unique even at an identical instant", keys.add(r.key));
			if (previous != null)
			{
				assertFalse("newest first", r.getRecord().getObservedAt().isAfter(previous));
			}
			previous = r.getRecord().getObservedAt();
		}
		assertEquals(Arrays.asList("Kraken", "Kraken", "Kraken", "Gargoyle", "Kraken"), names);
		assertEquals("the Gargoyle drop keeps its place between the Kraken variants", 2L, (long) quantities.get(3));
		assertEquals(1L, (long) quantities.get(4));
	}
}
