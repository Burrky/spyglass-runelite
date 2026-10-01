package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.util.Arrays;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JTextField;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Structured reward sources in the existing Loot Tracker model and view:
 * grouped / individual, naming, canonical identity, recency ordering,
 * favorites, collapse, hidden items, reset watermark, search, global total
 * and GE / High Alch valuation -- all through the generic source model.
 */
public class StructuredRewardLootTrackerViewTest
{
	private static final long ACCOUNT = 999_333_111L;
	private static final Gson GSON = new Gson();
	private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
	private static final String BARROWS = LootTrackerIndex.structuredSourceKey("barrows");
	private static final String POOL = LootTrackerIndex.structuredSourceKey("tempoross_reward_pool");

	private Path testRoot;
	private LootTrackerPreferences preferences;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("structured-reward-loot-view-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		preferences = new LootTrackerPreferences(new LocalStateStore(GSON));
		preferences.loadForAccount(ACCOUNT);
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private static LootTrackerRecord structured(String id, String key, String name, Instant at, LootTrackerItem... items)
	{
		return new LootTrackerRecord(id, name, null, at, Arrays.asList(items), key);
	}

	/** Barrows x2 claims, Tempoross pool x1 (newest), Zulrah NPC x1, and a hypothetical NPC literally named "Barrows". */
	private LootTrackerIndex index()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(structured("b1", BARROWS, "Barrows", T0, new LootTrackerItem(4708, "Ahrim's hood", 1), new LootTrackerItem(565, "Blood rune", 100)));
		index.apply(new LootTrackerRecord("z1", "Zulrah", 2042, T0.plusSeconds(10), Arrays.asList(new LootTrackerItem(2444, "Zulrah's scales", 100))));
		index.apply(structured("b2", BARROWS, "Barrows", T0.plusSeconds(20), new LootTrackerItem(565, "Blood rune", 50)));
		index.apply(new LootTrackerRecord("n1", "Barrows", 1, T0.plusSeconds(25), Arrays.asList(new LootTrackerItem(526, "Bones", 1))));
		index.apply(structured("t1", POOL, "Tempoross Reward Pool", T0.plusSeconds(30), new LootTrackerItem(995, "Coins", 4000)));
		return index;
	}

	private static LootTrackerSnapshot.SourceEntry entry(List<LootTrackerSnapshot.SourceEntry> all, String key)
	{
		for (LootTrackerSnapshot.SourceEntry e : all)
		{
			if (e.getSourceKey().equals(key))
			{
				return e;
			}
		}
		return null;
	}

	private static long qty(LootTrackerSnapshot.SourceEntry e, int itemId)
	{
		for (LootTrackerSnapshot.ItemEntry i : e.getItemTotals())
		{
			if (i.getItemIdValue() == itemId)
			{
				return i.getQuantity();
			}
		}
		return 0;
	}

	@Test
	public void grouped_individual_naming_identity_andOrdering()
	{
		List<LootTrackerSnapshot.SourceEntry> all = LootTrackerSnapshot.from(index(), preferences).getAllSources();
		assertEquals("Barrows reward, pool, Zulrah and the NPC named Barrows stay four sources", 4, all.size());
		LootTrackerSnapshot.SourceEntry barrows = entry(all, BARROWS);
		assertNotNull(barrows);
		assertTrue(barrows.isStructuredReward());
		assertEquals("Barrows", barrows.getSourceName());
		assertEquals(2, barrows.getKillCount());
		assertEquals(150, qty(barrows, 565));
		assertEquals(1, qty(barrows, 4708));
		assertEquals("individual view: one record per claim", 2, barrows.getRecords().size());
		assertEquals(T0, barrows.getRecords().get(0).getObservedAt());
		assertEquals(Arrays.asList(BARROWS), barrows.getRawSourceKeys());
		assertEquals("most recent first", POOL, all.get(0).getSourceKey());
		assertEquals("Tempoross Reward Pool", all.get(0).getSourceName());
		assertFalse("the NPC named Barrows never merges into the reward source", entry(all, "canon:Barrows").isStructuredReward());
	}

	@Test
	public void favorites_collapse_hidden_andReset_useTheStructuredKey()
	{
		preferences.setFavorite(BARROWS, true);
		preferences.setCollapsed(BARROWS, true);
		preferences.setItemHidden(BARROWS, 565, true);
		LootTrackerSnapshot snap = LootTrackerSnapshot.from(index(), preferences);
		LootTrackerSnapshot.SourceEntry barrows = entry(snap.getAllSources(), BARROWS);
		assertTrue(barrows.isFavorite());
		assertTrue(barrows.isCollapsed());
		assertEquals(1, snap.getFavoriteSources().size());
		assertEquals(BARROWS, snap.getFavoriteSources().get(0).getSourceKey());
		for (LootTrackerSnapshot.ItemEntry i : barrows.getItemTotals())
		{
			assertEquals(i.getItemIdValue() == 565, i.isHidden());
		}
		assertFalse("preferences never leak into the NPC named Barrows", entry(snap.getAllSources(), "canon:Barrows").isFavorite());

		preferences.resetSource(BARROWS, T0.plusSeconds(5));
		LootTrackerSnapshot.SourceEntry afterReset = entry(LootTrackerSnapshot.from(index(), preferences).getAllSources(), BARROWS);
		assertEquals("only claims after the watermark remain", 1, afterReset.getKillCount());
		assertEquals(50, qty(afterReset, 565));
		assertEquals(0, qty(afterReset, 4708));
	}

	// ------------------------------------------------------------------ view: total / search / valuation

	private static final class Cfg implements OsrsTelemetryConfig
	{
		final LootValuationMode mode;

		Cfg(LootValuationMode mode)
		{
			this.mode = mode;
		}

		@Override
		public LootValuationMode lootValuationMode()
		{
			return mode;
		}
	}

	private static Object field(Object target, String name) throws Exception
	{
		java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(target);
	}

	private static void seed(Object cache, Object... idPrice) throws Exception
	{
		java.lang.reflect.Method put = cache.getClass().getDeclaredMethod("put", Object.class, Object.class);
		put.setAccessible(true);
		for (int i = 0; i < idPrice.length; i += 2)
		{
			put.invoke(cache, idPrice[i], idPrice[i + 1]);
		}
	}

	/** GE: hood 1000, blood 400, scales 150, bones 100, coins 1. HA: halves (coins 1). */
	private LootTrackerView view(LootValuationMode mode) throws Exception
	{
		LootTrackerView v = new LootTrackerView(null, null, new Cfg(mode), preferences);
		Object cache = field(v, "itemValuationCache");
		seed(field(cache, "gePrices"), 4708, 1000L, 565, 400L, 2444, 150L, 526, 100L, 995, 1L);
		seed(field(cache, "haPrices"), 4708, 500L, 565, 200L, 2444, 75L, 526, 50L, 995, 1L);
		return v;
	}

	private static String total(long gp)
	{
		return "Total Loot: " + net.runelite.client.util.QuantityFormatter.quantityToStackSize(gp) + " gp";
	}

	private static String globalTotal(LootTrackerView v) throws Exception
	{
		return ((JLabel) field(v, "globalTotalHeader")).getText();
	}

	@Test
	public void globalTotal_includesStructuredRewards_andIgnoresSearchFavoritesHidden() throws Exception
	{
		// GE: Barrows 1000 + 150*400 = 61,000; Zulrah 15,000; NPC Barrows 100; pool 4,000 => 80,100
		preferences.setItemHidden(BARROWS, 565, true);
		preferences.setFavorite(POOL, true);
		LootTrackerView ge = view(LootValuationMode.GRAND_EXCHANGE);
		ge.render(LootTrackerSnapshot.from(index(), preferences));
		assertEquals(total(80_100), globalTotal(ge));
		((JTextField) field(ge, "searchField")).setText("ahrim");
		ge.render(LootTrackerSnapshot.from(index(), preferences));
		assertEquals("search never alters the global total", total(80_100), globalTotal(ge));

		// HA uses the same generic valuation path: 500 + 150*200 + 7,500 + 50 + 4,000 = 42,050
		LootTrackerView ha = view(LootValuationMode.HIGH_ALCH);
		ha.render(LootTrackerSnapshot.from(index(), preferences));
		assertEquals(total(42_050), globalTotal(ha));
	}

	@Test
	public void subtitle_countsRewardClaims_notKills()
	{
		assertEquals("61K total · 30.5K/reward", LootTrackerView.buildHeaderSubtitleText(61_000, 2, true));
		assertEquals("61K total · 30.5K/kill", LootTrackerView.buildHeaderSubtitleText(61_000, 2, false));
		assertEquals(LootTrackerView.buildHeaderSubtitleText(61_000, 2), LootTrackerView.buildHeaderSubtitleText(61_000, 2, false));
	}
}
