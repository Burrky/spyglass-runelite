package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
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
import java.awt.Rectangle;
import java.awt.event.ActionListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Narrow-width Loot toolbar/header layout (full Total Loot, caret always
 * visible) and the ONE smart bulk button (decided by the MANUAL state of
 * the displayed cards; one preference write; no full render).
 */
public class LootNarrowWidthAndBulkTest
{
	private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};

	private Path testRoot;
	private int writes;
	private int refreshRequests;
	private LootTrackerPreferences prefs;
	private LootTrackerIndex index;
	private LootTrackerView view;

	private final class CountingStore extends LocalStateStore
	{
		CountingStore()
		{
			super(new Gson());
		}

		@Override
		public void write(net.runelite.client.util.Filepath target, Object document, Runnable onWritten)
		{
			writes++;
		}
	}

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("loot-narrow-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		prefs = new LootTrackerPreferences(new CountingStore());
		prefs.loadForAccount(1L);
		index = new LootTrackerIndex();
		view = new LootTrackerView(null, null, new OsrsTelemetryConfig()
		{
		}, prefs);
		// Coins at 1 gp each so the totals are real, resolved synchronously.
		java.lang.reflect.Field cache = LootTrackerView.class.getDeclaredField("itemValuationCache");
		cache.setAccessible(true);
		cache.set(view, new ItemValuationCache(Runnable::run, id -> id == 995 ? 1L : 0L, id -> id == 995 ? 1L : 0L));
		view.setRefreshRequester(() -> refreshRequests++);
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private int ev;

	private void drops(String source, int sourceId, int count, long coinsEach)
	{
		for (int i = 0; i < count; i++)
		{
			index.apply(new LootTrackerRecord("e" + (ev++), source, sourceId, T0.plusSeconds(ev),
				Collections.singletonList(new LootTrackerItem(995, "Coins", coinsEach))));
		}
	}

	private void render()
	{
		view.render(LootTrackerSnapshot.from(index, prefs));
		view.render(LootTrackerSnapshot.from(index, prefs)); // prices resolved on the first pass
	}

	private static List<Component> all(Container c)
	{
		List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(all((Container) k));
			}
		}
		return out;
	}

	private static void layout(Container c)
	{
		c.doLayout();
		for (Component k : c.getComponents())
		{
			if (k instanceof Container)
			{
				layout((Container) k);
			}
		}
	}

	private void layoutAt(int width)
	{
		view.setSize(width, 4000);
		for (int pass = 0; pass < 2; pass++)
		{
			view.invalidate();
			layout(view);
		}
	}

	private Rectangle inView(Component c)
	{
		return SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), view);
	}

	private JLabel totalLabel() throws Exception
	{
		java.lang.reflect.Field f = LootTrackerView.class.getDeclaredField("globalTotalHeader");
		f.setAccessible(true);
		return (JLabel) f.get(view);
	}

	private JButton bulk()
	{
		JButton found = null;
		for (Component c : all(view))
		{
			if (c instanceof JButton && (LootTrackerView.BULK_COLLAPSE_TEXT.equals(((JButton) c).getText())
				|| LootTrackerView.BULK_OPEN_TEXT.equals(((JButton) c).getText())))
			{
				assertNull("exactly one bulk button", found);
				found = (JButton) c;
			}
		}
		return found;
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

	private boolean manual(String source, int id)
	{
		return prefs.isCollapsed(LootTrackerIndex.sourceKey(source, id));
	}

	// ------------------------------------------------------------------ Total Loot

	@Test
	public void totalLoot_isNeverClipped_atSidebarWidths() throws Exception
	{
		for (long total : new long[] {999L, 64_012_345L, 1_234_567L, 12_345_678_901L})
		{
			index = new LootTrackerIndex();
			drops("Chest", 1, 1, total);
			render();
			for (int width : WIDTHS)
			{
				layoutAt(width);
				JLabel label = totalLabel();
				Rectangle r = inView(label);
				assertTrue(width + "px " + label.getText() + ": full width", label.getWidth() >= label.getPreferredSize().width);
				assertTrue(width + "px: inside", r.x >= 0 && r.x + r.width <= width);
				assertTrue(label.getText().startsWith("Total Loot: "));
			}
		}
		assertTrue("exact value on hover", totalLabel().getToolTipText().contains("12,345,678,901"));
	}

	// ------------------------------------------------------------------ source header

	@Test
	public void longSourceWithHugeCount_keepsCountAndCaretVisible_grouped_and_individual() throws Exception
	{
		drops("Greater Nechryael", 7278, 2240, 1);
		drops("Imp", 3134, 2, 1);
		prefs.setFavorite(Collections.singletonList(LootTrackerIndex.sourceKey("Greater Nechryael", 7278)), true);
		for (boolean individual : new boolean[] {false, true})
		{
			if (individual)
			{
				for (Component c : all(view))
				{
					if (c instanceof JButton && "Grouped".equals(((JButton) c).getText()))
					{
						press((JButton) c);
					}
				}
			}
			render();
			for (int width : WIDTHS)
			{
				layoutAt(width);
				for (Component c : all(view))
				{
					if (!(c instanceof JLabel))
					{
						continue;
					}
					JLabel l = (JLabel) c;
					boolean caret = "▸".equals(l.getText()) || "▾".equals(l.getText());
					boolean count = LootTrackerView.SOURCE_COUNT_NAME.equals(l.getName());
					if (caret || count)
					{
						Rectangle r = inView(l);
						String what = width + "px " + (individual ? "individual " : "grouped ") + l.getText();
						assertTrue(what + " visible", l.getWidth() > 0 && l.isShowing() || l.getWidth() >= l.getPreferredSize().width);
						assertTrue(what + " not clipped", l.getWidth() >= l.getPreferredSize().width);
						assertTrue(what + " inside the sidebar (" + (r.x + r.width) + ")", r.x >= 0 && r.x + r.width <= width);
					}
				}
			}
		}
	}

	@Test
	public void countIsItsOwnLabel_andTheNameShrinksFirst() throws Exception
	{
		drops("Greater Nechryael", 7278, 2240, 1);
		render();
		layoutAt(150);
		JLabel name = null;
		JLabel count = null;
		for (Component c : all(view))
		{
			if (LootTrackerView.SOURCE_TITLE_NAME.equals(c.getName()))
			{
				name = (JLabel) c;
			}
			if (LootTrackerView.SOURCE_COUNT_NAME.equals(c.getName()))
			{
				count = (JLabel) c;
			}
		}
		assertNotNull(name);
		assertEquals("×2240", count.getText());
		assertEquals("Greater Nechryael", name.getToolTipText());
		assertTrue("the name is what gives way", name.getWidth() < name.getPreferredSize().width);
		assertTrue(count.getWidth() >= count.getPreferredSize().width);
	}

	// ------------------------------------------------------------------ smart bulk button

	private void threeSources()
	{
		drops("Alpha", 1, 3, 10);
		drops("Beta", 2, 3, 10);
		drops("Gamma", 3, 3, 10);
		render();
		writes = 0;
		refreshRequests = 0;
	}

	@Test
	public void allOpen_offersCollapseAll_click_collapses_andFlips() throws Exception
	{
		threeSources();
		assertEquals(LootTrackerView.BULK_COLLAPSE_TEXT, bulk().getText());
		press(bulk());
		assertTrue(manual("Alpha", 1) && manual("Beta", 2) && manual("Gamma", 3));
		assertEquals("flips at once", LootTrackerView.BULK_OPEN_TEXT, bulk().getText());
		assertEquals("one preference write", 1, writes);
		assertEquals("no full render", 0, refreshRequests);
		press(bulk());
		assertFalse(manual("Alpha", 1) || manual("Beta", 2) || manual("Gamma", 3));
		assertEquals(LootTrackerView.BULK_COLLAPSE_TEXT, bulk().getText());
		assertEquals(2, writes);
	}

	@Test
	public void mixed_offersCollapseAll() throws Exception
	{
		prefs.setCollapsed(Collections.singletonList(LootTrackerIndex.sourceKey("Alpha", 1)), true);
		threeSources();
		assertEquals(LootTrackerView.BULK_COLLAPSE_TEXT, bulk().getText());
		press(bulk());
		assertTrue(manual("Alpha", 1) && manual("Beta", 2) && manual("Gamma", 3));
		assertEquals(LootTrackerView.BULK_OPEN_TEXT, bulk().getText());
	}

	@Test
	public void singleCardToggle_updatesTheBulkButton() throws Exception
	{
		threeSources();
		press(bulk()); // all collapsed -> "Open All"
		for (Component c : all(view))
		{
			if (LootTrackerView.SOURCE_TITLE_NAME.equals(c.getName()) && "Beta".equals(((JLabel) c).getText()))
			{
				for (java.awt.event.MouseListener l : c.getMouseListeners())
				{
					l.mouseClicked(null);
				}
			}
		}
		assertFalse(manual("Beta", 2));
		assertEquals("mixed again -> Collapse All", LootTrackerView.BULK_COLLAPSE_TEXT, bulk().getText());
	}

	@Test
	public void favoritesFilter_decidesAndActsOnDisplayedCardsOnly() throws Exception
	{
		prefs.setFavorite(Collections.singletonList(LootTrackerIndex.sourceKey("Alpha", 1)), true);
		prefs.setCollapsed(Collections.singletonList(LootTrackerIndex.sourceKey("Alpha", 1)), true);
		threeSources();
		assertEquals("All tab: mixed", LootTrackerView.BULK_COLLAPSE_TEXT, bulk().getText());
		for (Component c : all(view))
		{
			if (c instanceof JButton && "Favorites".equals(((JButton) c).getText()))
			{
				press((JButton) c);
			}
		}
		render();
		assertEquals("Favorites tab: its one card is collapsed", LootTrackerView.BULK_OPEN_TEXT, bulk().getText());
		press(bulk());
		assertFalse(manual("Alpha", 1));
		assertFalse("filtered-out cards untouched", manual("Beta", 2));
		assertFalse(manual("Gamma", 3));
	}

	@Test
	public void searchExpansion_doesNotFoolTheButton_explicitPressChangesManualState() throws Exception
	{
		drops("Alpha", 1, 2, 10);
		index.apply(new LootTrackerRecord("rune", "Beta", 2, T0.plusSeconds(999), Collections.singletonList(new LootTrackerItem(1127, "Rune platebody", 1))));
		drops("Gamma", 3, 2, 10);
		render();
		press(bulk()); // everything manually collapsed
		search("rune"); // Beta is displayed and temporarily expanded
		render();
		assertEquals("manual state is all collapsed, whatever the search shows", LootTrackerView.BULK_OPEN_TEXT, bulk().getText());
		press(bulk()); // explicit: open the displayed card
		assertFalse(manual("Beta", 2));
		assertTrue("not displayed -> untouched", manual("Alpha", 1) && manual("Gamma", 3));
		assertEquals(LootTrackerView.BULK_COLLAPSE_TEXT, bulk().getText());
		search("");
		render();
		assertFalse("clearing the search keeps the explicit result", manual("Beta", 2));
		assertTrue(manual("Alpha", 1));
		assertEquals("mixed after clearing", LootTrackerView.BULK_COLLAPSE_TEXT, bulk().getText());
	}

	@Test
	public void bulkButton_fitsAndNothingOverflows_atNarrowWidths() throws Exception
	{
		threeSources();
		for (int width : WIDTHS)
		{
			layoutAt(width);
			JButton b = bulk();
			Rectangle r = inView(b);
			assertTrue(width + "px bulk inside", r.x >= 0 && r.x + r.width <= width);
			assertTrue(width + "px bulk not clipped", b.getWidth() >= b.getPreferredSize().width);
			for (Component c : all(view))
			{
				if (c.isVisible() && c.getWidth() > 0)
				{
					Rectangle cr = inView(c);
					assertTrue(width + "px: " + c.getClass().getSimpleName() + " " + c.getName() + " overflows (" + (cr.x + cr.width) + ")",
						cr.x + cr.width <= width + 1);
				}
			}
		}
	}

	@Test
	public void bulkKeepsCardShells_noFullRebuild() throws Exception
	{
		threeSources();
		java.lang.reflect.Field f = LootTrackerView.class.getDeclaredField("displayedCards");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<Object> before = new ArrayList<>((List<Object>) f.get(view));
		press(bulk());
		press(bulk());
		@SuppressWarnings("unchecked")
		List<Object> after = (List<Object>) f.get(view);
		for (int i = 0; i < before.size(); i++)
		{
			assertSame(before.get(i), after.get(i));
		}
	}
}
