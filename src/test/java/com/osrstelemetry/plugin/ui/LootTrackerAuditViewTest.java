package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.LootValuationMode;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.loottracker.LootTrackerIndex;
import com.osrstelemetry.plugin.loottracker.LootTrackerItem;
import com.osrstelemetry.plugin.loottracker.LootTrackerPreferences;
import com.osrstelemetry.plugin.loottracker.LootTrackerRecord;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integrity audit (Loot Tracker view side): a retained card is only correct
 * if the render key covers everything it shows -- GE/HA included -- and a
 * card's preference writes cover every raw variant it stands for.
 */
public class LootTrackerAuditViewTest
{
	private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

	private Path testRoot;
	private LocalStateStore store;
	private LootTrackerPreferences prefs;
	private LootValuationMode mode = LootValuationMode.GRAND_EXCHANGE;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("loot-audit-view-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		store = new LocalStateStore(new Gson());
		store.start();
		prefs = new LootTrackerPreferences(store);
		prefs.loadForAccount(5L);
	}

	@After
	public void tearDown() throws Exception
	{
		store.shutdown();
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private static Object field(Object target, String name) throws Exception
	{
		Class<?> c = target.getClass();
		while (c != null)
		{
			try
			{
				java.lang.reflect.Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(target);
			}
			catch (NoSuchFieldException e)
			{
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}

	private static LootTrackerRecord rec(String id, String name, int sourceId, Instant at)
	{
		return new LootTrackerRecord(id, name, sourceId, at, Collections.singletonList(new LootTrackerItem(995, "Coins", 10)));
	}

	@Test
	public void switchingGeToHighAlch_reRendersTheOpenCards()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(rec("e1", "Zulrah", 2042, T0));
		LootTrackerView view = new LootTrackerView(null, null, new OsrsTelemetryConfig()
		{
			@Override
			public LootValuationMode lootValuationMode()
			{
				return mode;
			}
		}, prefs);
		view.setSize(225, 2000);
		view.render(LootTrackerSnapshot.from(index, prefs));
		Object bodyBefore;
		try
		{
			@SuppressWarnings("unchecked")
			List<Object> cards = (List<Object>) field(view, "displayedCards");
			bodyBefore = field(cards.get(0), "body");
			assertNotNull(bodyBefore);

			mode = LootValuationMode.HIGH_ALCH;
			view.render(LootTrackerSnapshot.from(index, prefs)); // same data, same price version
			Object bodyAfter = field(cards.get(0), "body");
			assertNotSame("the open card is re-valued, not skipped by the render key", bodyBefore, bodyAfter);
			assertTrue(String.valueOf(field(view, "lastRenderKey")).contains("valuation:" + LootValuationMode.HIGH_ALCH));
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	@Test
	public void cardPreferenceWrites_coverRawVariantsHiddenBehindAReset()
	{
		String keyA = LootTrackerIndex.sourceKey("Goat", 1);
		String keyB = LootTrackerIndex.sourceKey("Goat", 2);
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(rec("a1", "Goat", 1, T0));
		index.apply(rec("b1", "Goat", 2, T0.plusSeconds(1)));
		prefs.setFavorite(java.util.Arrays.asList(keyA, keyB), true);
		prefs.resetSource(keyA, T0.plusSeconds(5));
		prefs.resetSource(keyB, T0.plusSeconds(5));
		index.apply(rec("b2", "Goat", 2, T0.plusSeconds(10))); // only variant B comes back

		LootTrackerSnapshot.SourceEntry card = LootTrackerSnapshot.from(index, prefs).getAllSources().get(0);
		assertEquals(1, card.getKillCount());
		assertTrue(card.getRawSourceKeys().contains(keyA));
		assertTrue(card.getRawSourceKeys().contains(keyB));
		assertTrue(card.isFavorite());

		// Unfavorite the card, then variant A returns: the card stays unfavorited.
		prefs.setFavorite(card.getRawSourceKeys(), false);
		index.apply(rec("a2", "Goat", 1, T0.plusSeconds(20)));
		LootTrackerSnapshot.SourceEntry after = LootTrackerSnapshot.from(index, prefs).getAllSources().get(0);
		assertEquals(2, after.getKillCount());
		assertFalse(after.isFavorite());
	}
}
