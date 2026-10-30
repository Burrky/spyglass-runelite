package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Loot Tracker INDIVIDUAL = one global chronological stream of loot records
 * (newest first) across every source -- event order wins over source
 * grouping -- in the real (headless) view, with search, hidden items, reset
 * watermarks, GE/HA, Load more and the Grouped view checked around it.
 */
public class LootTrackerIndividualStreamTest
{
	private static final Instant T0 = Instant.parse("2026-09-30T12:00:00Z");

	private Path testRoot;
	private LootTrackerPreferences prefs;
	private LootTrackerIndex index;
	private LootTrackerView view;
	private LootValuationMode mode = LootValuationMode.GRAND_EXCHANGE;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("loot-individual-stream-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		prefs = new LootTrackerPreferences(new LocalStateStore(new Gson()));
		prefs.loadForAccount(7L);
		index = new LootTrackerIndex();
		// t1 Kraken, t2 Cave kraken, t3 Kraken, t4 Gargoyle, t5 Kraken
		drop("t1", "Kraken", 494, 1, new LootTrackerItem(3272, "Kraken tentacle", 1));
		drop("t2", "Cave kraken", 492, 2, new LootTrackerItem(995, "Coins", 40));
		drop("t3", "Kraken", 494, 3, new LootTrackerItem(995, "Coins", 900), new LootTrackerItem(560, "Death rune", 30));
		drop("t4", "Gargoyle", 412, 4, new LootTrackerItem(1365, "Steel battleaxe", 1));
		drop("t5", "Kraken", 494, 5, new LootTrackerItem(560, "Death rune", 60));
		view = new LootTrackerView(null, null, new OsrsTelemetryConfig()
		{
			@Override
			public LootValuationMode lootValuationMode()
			{
				return mode;
			}
		}, prefs);
		seedPrices();
		view.setSize(225, 4000);
		render();
		press(button("Grouped")); // -> Individual
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private void drop(String id, String source, int sourceId, long second, LootTrackerItem... items)
	{
		index.apply(new LootTrackerRecord(id, source, sourceId, T0.plusSeconds(second), Arrays.asList(items)));
	}

	private void render()
	{
		view.render(LootTrackerSnapshot.from(index, prefs));
	}

	private void seedPrices() throws Exception
	{
		java.lang.reflect.Field f = LootTrackerView.class.getDeclaredField("itemValuationCache");
		f.setAccessible(true);
		Object cache = f.get(view);
		for (String[] spec : new String[][] {{"gePrices", "200"}, {"haPrices", "100"}})
		{
			java.lang.reflect.Field pf = cache.getClass().getDeclaredField(spec[0]);
			pf.setAccessible(true);
			Object resolving = pf.get(cache);
			java.lang.reflect.Method put = resolving.getClass().getDeclaredMethod("put", Object.class, Object.class);
			put.setAccessible(true);
			for (int id : new int[] {3272, 995, 560, 1365})
			{
				put.invoke(resolving, id, id == 995 ? 1L : Long.parseLong(spec[1]));
			}
		}
	}

	private JButton button(String text)
	{
		for (Component c : all(view))
		{
			if (c instanceof JButton && text.equals(((JButton) c).getText()))
			{
				return (JButton) c;
			}
		}
		return null;
	}

	private static void press(JButton b)
	{
		for (ActionListener l : b.getActionListeners())
		{
			l.actionPerformed(null);
		}
	}

	private void search(String text) throws Exception
	{
		java.lang.reflect.Field f = LootTrackerView.class.getDeclaredField("searchField");
		f.setAccessible(true);
		((JTextField) f.get(view)).setText(text);
	}

	private static List<Component> all(Container root)
	{
		List<Component> out = new ArrayList<>();
		for (Component c : root.getComponents())
		{
			out.add(c);
			if (c instanceof Container)
			{
				out.addAll(all((Container) c));
			}
		}
		return out;
	}

	/** Source headline of every rendered record card, in display order. */
	private List<String> stream()
	{
		List<String> out = new ArrayList<>();
		for (Component c : all(view))
		{
			if (c instanceof JLabel && LootTrackerView.RECORD_SOURCE_NAME.equals(c.getName()))
			{
				out.add(HomeView.plain((JLabel) c));
			}
		}
		return out;
	}

	private List<String> recordValues()
	{
		List<String> out = new ArrayList<>();
		for (Component c : all(view))
		{
			if (LootTrackerView.RECORD_CARD_NAME.equals(c.getName()))
			{
				for (Component k : all((Container) c))
				{
					if (k instanceof JLabel && HomeView.plain((JLabel) k) != null && HomeView.plain((JLabel) k).endsWith(" gp"))
					{
						out.add(HomeView.plain((JLabel) k));
					}
				}
			}
		}
		return out;
	}

	private static final List<String> CHRONOLOGICAL = Arrays.asList("Kraken", "Gargoyle", "Kraken", "Cave kraken", "Kraken");

	@Test
	public void individual_followsActualEventOrder_newestFirst_notGroupedByNpc()
	{
		assertEquals(CHRONOLOGICAL, stream());
		assertNotEquals("never all Kraken first", Arrays.asList("Kraken", "Kraken", "Kraken", "Cave kraken", "Gargoyle"), stream());
	}

	@Test
	public void eachRecord_isHeadedByItsSource_withTheTimeOnlyInTheTooltip()
	{
		for (Component c : all(view))
		{
			if (c instanceof JLabel && LootTrackerView.RECORD_SOURCE_NAME.equals(c.getName()))
			{
				JLabel l = (JLabel) c;
				assertFalse("source, never a timestamp, is the headline", HomeView.plain(l).matches(".*\\d{2}:\\d{2}.*"));
				assertTrue(l.getToolTipText().startsWith(HomeView.plain(l) + " -- "));
			}
		}
	}

	@Test
	public void newDrop_entersAtTheTop_withoutRegroupingAnything()
	{
		drop("t6", "Cave kraken", 492, 6, new LootTrackerItem(995, "Coins", 12));
		render();
		assertEquals(Arrays.asList("Cave kraken", "Kraken", "Gargoyle", "Kraken", "Cave kraken", "Kraken"), stream());
	}

	@Test
	public void search_keepsChronology_andClearingRestoresTheOriginalStream() throws Exception
	{
		search("death RUNE");
		assertEquals("case-insensitive, still chronological, never regrouped", Arrays.asList("Kraken", "Kraken"), stream());
		search("coins");
		assertEquals(Arrays.asList("Kraken", "Cave kraken"), stream());
		search("");
		assertEquals(CHRONOLOGICAL, stream());
	}

	@Test
	public void hiddenItems_respected_showHiddenRestoresThem()
	{
		prefs.setItemHidden(LootTrackerIndex.sourceKey("Gargoyle", 412), 1365, true);
		render();
		assertEquals("a record with only hidden items drops out", Arrays.asList("Kraken", "Kraken", "Cave kraken", "Kraken"), stream());
		prefs.setShowHidden(true);
		render();
		assertEquals(CHRONOLOGICAL, stream());
	}

	@Test
	public void sourceResetWatermark_removesOnlyThatSourcesOlderRecords()
	{
		prefs.resetSource(LootTrackerIndex.sourceKey("Kraken", 494), T0.plusSeconds(4));
		render();
		assertEquals(Arrays.asList("Kraken", "Gargoyle", "Cave kraken"), stream());
	}

	@Test
	public void valuationModeChange_revaluesTheSameStream()
	{
		List<String> ge = recordValues();
		mode = LootValuationMode.HIGH_ALCH;
		render();
		assertEquals(CHRONOLOGICAL, stream());
		assertNotEquals(ge, recordValues());
	}

	@Test
	public void viewToggles_neverCorruptTheOrder_groupedStaysGroupedBySource()
	{
		for (int i = 0; i < 3; i++)
		{
			press(button("Individual")); // -> Grouped
			assertTrue("Grouped renders no record cards", stream().isEmpty());
			List<String> titles = new ArrayList<>();
			for (Component c : all(view))
			{
				if (c instanceof JLabel && LootTrackerView.SOURCE_TITLE_NAME.equals(c.getName()))
				{
					titles.add(HomeView.plain((JLabel) c));
				}
			}
			assertEquals("one card per source, most recent first", Arrays.asList("Kraken", "Gargoyle", "Cave kraken"), titles);
			press(button("Grouped")); // -> Individual
			assertEquals(CHRONOLOGICAL, stream());
		}
	}

	@Test
	public void loadMore_extendsTheStreamInOrder_boundedByTheCap()
	{
		for (int i = 0; i < 120; i++)
		{
			drop("x" + i, i % 3 == 0 ? "Gargoyle" : "Kraken", i % 3 == 0 ? 412 : 494, 100 + i, new LootTrackerItem(995, "Coins", 1));
		}
		render();
		assertEquals(LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL, stream().size());
		List<String> first = stream();
		press(button("Load more"));
		List<String> more = stream();
		assertEquals(LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL + LootTrackerView.INDIVIDUAL_REVEAL_INCREMENT, more.size());
		assertEquals("older records are appended below", first, more.subList(0, first.size()));
		press(button("Load more"));
		assertEquals("all 125 now shown", 125, stream().size());
		assertEquals(CHRONOLOGICAL, stream().subList(120, 125));
		assertTrue("nothing left to load", button("Load more") == null);
	}
}
