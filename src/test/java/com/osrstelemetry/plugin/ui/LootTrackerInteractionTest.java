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
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Loot Tracker interactions are local and bounded: one card toggle touches
 * one card, bulk controls are one batch (one preference write, no full
 * render), the grouped/individual switch keeps card shells, and the
 * Individual view is one bounded global stream (never per-source cards).
 */
public class LootTrackerInteractionTest
{
	private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

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
		testRoot = Files.createTempDirectory("loot-interaction-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		prefs = new LootTrackerPreferences(new CountingStore());
		prefs.loadForAccount(1L);
		index = build(15, 60);
		view = new LootTrackerView(null, null, new OsrsTelemetryConfig()
		{
		}, prefs);
		view.setRefreshRequester(() -> refreshRequests++);
		view.setSize(225, 4000);
		render();
		writes = 0;
		refreshRequests = 0;
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	/** {@code sources} sources (Source 0 oldest ... newest last), {@code records} drops each of 3 items. */
	private static LootTrackerIndex build(int sources, int records)
	{
		LootTrackerIndex idx = new LootTrackerIndex();
		int ev = 0;
		for (int s = 0; s < sources; s++)
		{
			for (int r = 0; r < records; r++)
			{
				List<LootTrackerItem> items = new ArrayList<>();
				for (int i = 0; i < 3; i++)
				{
					int id = 1000 + s * 10 + (r + i) % 8;
					items.add(new LootTrackerItem(id, (s % 2 == 0 ? "Rune item " : "Iron item ") + id, 1 + r));
				}
				idx.apply(new LootTrackerRecord("e" + (ev++), "Source " + s, s, T0.plusSeconds(ev), items));
			}
		}
		return idx;
	}

	private void render()
	{
		view.render(LootTrackerSnapshot.from(index, prefs));
	}

	// ------------------------------------------------------------------ reflection helpers

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

	@SuppressWarnings("unchecked")
	private List<Object> displayed() throws Exception
	{
		return new ArrayList<>((List<Object>) field(view, "displayedCards"));
	}

	private Object card(String sourceName) throws Exception
	{
		for (Object card : displayed())
		{
			LootTrackerSnapshot.SourceEntry entry = (LootTrackerSnapshot.SourceEntry) field(card, "entry");
			if (sourceName.equals(entry.getSourceName()))
			{
				return card;
			}
		}
		return null;
	}

	private static Object body(Object card) throws Exception
	{
		return field(card, "body");
	}



	private static Set<Component> all(Container c)
	{
		Set<Component> s = Collections.newSetFromMap(new IdentityHashMap<>());
		collect(c, s);
		return s;
	}

	private static void collect(Container c, Set<Component> s)
	{
		for (Component k : c.getComponents())
		{
			s.add(k);
			if (k instanceof Container)
			{
				collect((Container) k, s);
			}
		}
	}

	private static JLabel label(Container c, String prefix)
	{
		for (Component k : c.getComponents())
		{
			if (k instanceof JLabel && HomeView.plain((JLabel) k) != null && HomeView.plain((JLabel) k).startsWith(prefix))
			{
				return (JLabel) k;
			}
			if (k instanceof Container)
			{
				JLabel r = label((Container) k, prefix);
				if (r != null)
				{
					return r;
				}
			}
		}
		return null;
	}

	private static void click(Component c)
	{
		for (MouseListener l : c.getMouseListeners())
		{
			l.mouseClicked(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
		}
	}

	private void clickTitle(String sourceName)
	{
		for (Component c : all(view))
		{
			if (c instanceof JLabel && LootTrackerView.SOURCE_TITLE_NAME.equals(c.getName()) && sourceName.equals(HomeView.plain((JLabel) c)))
			{
				click(c);
				return;
			}
		}
		throw new AssertionError("no title label for " + sourceName);
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

	private void toIndividual()
	{
		press(button("Grouped"));
	}

	private void toGrouped()
	{
		press(button("Individual"));
	}

	private void search(String text) throws Exception
	{
		((JTextField) field(view, "searchField")).setText(text);
	}

	private boolean manualCollapsed(String sourceName, int id)
	{
		return prefs.isCollapsed(LootTrackerIndex.sourceKey(sourceName, id));
	}

	private List<Component> contentOrder() throws Exception
	{
		List<Component> out = new ArrayList<>();
		Collections.addAll(out, ((JPanel) field(view, "content")).getComponents());
		return out;
	}

	// ------------------------------------------------------------------ 1-4 single card

	@Test
	public void collapseOne_touchesOnlyThatCard_immediately_oneWrite_noFullRender() throws Exception
	{
		Object target = card("Source 7");
		Map<Object, Object> otherBodies = new IdentityHashMap<>();
		for (Object c : displayed())
		{
			otherBodies.put(c, body(c));
		}
		Set<Component> before = all(view);
		clickTitle("Source 7");
		assertNull("collapsed at once -- no poll wait", body(target));
		assertTrue(manualCollapsed("Source 7", 7));
		assertEquals(1, writes);
		assertEquals("a local toggle requests no full render", 0, refreshRequests);
		for (Object c : displayed())
		{
			if (c != target)
			{
				assertSame("other cards' bodies untouched", otherBodies.get(c), body(c));
			}
		}
		Set<Component> after = all(view);
		for (Component c : after)
		{
			assertTrue("collapsing creates nothing", before.contains(c));
		}
	}

	@Test
	public void expandOne_buildsOnlyThatCardsBody() throws Exception
	{
		clickTitle("Source 7");
		Set<Component> before = all(view);
		clickTitle("Source 7");
		assertNotNull(body(card("Source 7")));
		Set<Component> created = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Component c : all(view))
		{
			if (!before.contains(c))
			{
				created.add(c);
			}
		}
		Set<Component> body = all((Container) body(card("Source 7")));
		body.add((Component) body(card("Source 7")));
		body.add((Component) field(card("Source 7"), "bodyGap")); // the retained gap is re-added, not new
		assertTrue(created.size() > 0);
		for (Component c : created)
		{
			assertTrue("only the reopened body is new: " + c.getClass(), body.contains(c));
		}
	}

	@Test
	public void individualMode_isOneGlobalStream_noSourceCardsAttached() throws Exception
	{
		toIndividual();
		assertTrue("Individual shows records, not source cards", displayed().isEmpty());
		assertEquals(LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL, view.individualRecordsMaterialized());
		JButton bulk = button(LootTrackerView.BULK_COLLAPSE_TEXT);
		assertTrue("source collapse controls have nothing to act on in Individual", bulk == null || !bulk.isEnabled());
	}


	@Test
	public void sourceCollapse_isAGroupedConcept_neverHidesDropsFromTheStream() throws Exception
	{
		clickTitle("Source 14");
		assertNull(body(card("Source 14")));
		toIndividual();
		assertEquals(LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL, view.individualRecordsMaterialized());
		toGrouped();
		assertNull("the manual collapse is kept for Grouped", body(card("Source 14")));
		assertTrue(manualCollapsed("Source 14", 14));
	}


	// ------------------------------------------------------------------ 5-12 bulk

	private void assertAllCollapsed(boolean collapsed) throws Exception
	{
		for (Object c : displayed())
		{
			assertEquals(collapsed, body(c) == null);
		}
	}

	@Test
	public void collapseAll_andOpenAll_grouped_oneWriteEach_noFullRender_orderKept() throws Exception
	{
		List<Component> order = contentOrder();
		press(button("▸ All"));
		assertAllCollapsed(true);
		assertEquals("one logical preference write", 1, writes);
		assertEquals("no queued full render", 0, refreshRequests);
		assertEquals(order, contentOrder());
		for (int s = 0; s < 15; s++)
		{
			assertTrue(manualCollapsed("Source " + s, s));
		}
		press(button("▾ All"));
		assertAllCollapsed(false);
		assertEquals(2, writes);
		assertEquals(0, refreshRequests);
		assertEquals(order, contentOrder());
	}

	@Test
	public void individualStream_neverExceedsTheHardBudget() throws Exception
	{
		toIndividual();
		for (int i = 0; i < 30; i++)
		{
			JButton more = button("Load more");
			if (more == null)
			{
				break;
			}
			press(more);
			assertTrue(view.individualRecordsMaterialized() <= LootTrackerView.MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED);
		}
		assertEquals("15 x 60 records, capped", LootTrackerView.MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED, view.individualRecordsMaterialized());
		assertNull("no Load more past the cap", button("Load more"));
		assertEquals("Load more never writes preferences", 0, writes);
	}


	@Test
	public void loadMore_extendsTheGlobalStream_byOneStep_keepingTheNewerRecordsInPlace() throws Exception
	{
		toIndividual();
		Set<Component> before = all(view);
		List<Component> firstCards = recordCards();
		assertEquals(LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL, firstCards.size());
		press(button("Load more"));
		List<Component> after = recordCards();
		assertEquals(LootTrackerView.DEFAULT_INDIVIDUAL_REVEAL + LootTrackerView.INDIVIDUAL_REVEAL_INCREMENT, after.size());
		for (int i = 0; i < firstCards.size(); i++)
		{
			assertSame("existing record cards are retained, in place", firstCards.get(i), after.get(i));
		}
		for (Component c : firstCards)
		{
			assertTrue(before.contains(c));
		}
	}

	private List<Component> recordCards()
	{
		List<Component> out = new ArrayList<>();
		collectRecordCards(view, out);
		return out;
	}

	private static void collectRecordCards(Container c, List<Component> out)
	{
		for (Component k : c.getComponents())
		{
			if (LootTrackerView.RECORD_CARD_NAME.equals(k.getName()))
			{
				out.add(k);
			}
			else if (k instanceof Container)
			{
				collectRecordCards((Container) k, out);
			}
		}
	}


	// ------------------------------------------------------------------ 13-15 search

	@Test
	public void bulkDuringSearch_changesManualStateOfDisplayedCardsOnly_andClearingSearchKeepsIt() throws Exception
	{
		search("rune item"); // only even-numbered sources match
		assertEquals(8, displayed().size());
		press(button("▸ All"));
		assertAllCollapsed(true);
		for (int s = 0; s < 15; s++)
		{
			assertEquals("displayed cards only", s % 2 == 0, manualCollapsed("Source " + s, s));
		}
		assertEquals(1, writes);

		search("");
		assertEquals(15, displayed().size());
		for (Object c : displayed())
		{
			LootTrackerSnapshot.SourceEntry e = (LootTrackerSnapshot.SourceEntry) field(c, "entry");
			int id = e.getSourceId();
			assertEquals("clearing search shows the resulting manual state", id % 2 == 0, body(c) == null);
		}

		search("rune item");
		press(button("▾ All"));
		assertAllCollapsed(false);
		search("");
		for (int s = 0; s < 15; s++)
		{
			assertFalse(manualCollapsed("Source " + s, s));
		}
	}

	@Test
	public void searchAloneOnlyTemporarilyExpands_neverWritesPreferences() throws Exception
	{
		press(button("▸ All"));
		writes = 0;
		search("rune item");
		assertAllCollapsed(false);
		search("");
		assertAllCollapsed(true);
		assertEquals("search never writes collapse preferences", 0, writes);
	}

	// ------------------------------------------------------------------ 16-19 other state untouched

	@Test
	public void bulk_leavesFavoritesHiddenResetAndOrderingAlone() throws Exception
	{
		String key3 = LootTrackerIndex.sourceKey("Source 3", 3);
		prefs.setFavorite(key3, true);
		prefs.setItemHidden(key3, 1030, true);
		prefs.resetSource(LootTrackerIndex.sourceKey("Source 4", 4), T0.plusSeconds(260));
		render();
		List<Component> order = contentOrder();
		press(button("▸ All"));
		press(button("▾ All"));
		assertTrue(prefs.isFavorite(key3));
		assertTrue(prefs.isItemHidden(key3, 1030));
		assertEquals(T0.plusSeconds(260), prefs.getResetWatermark(LootTrackerIndex.sourceKey("Source 4", 4)));
		assertEquals(order, contentOrder());
		render();
		assertEquals("a later refresh with unchanged data keeps the order", order, contentOrder());
	}

	// ------------------------------------------------------------------ 20-21 mode switch

	@Test
	public void modeSwitch_keepsCardShellsAndHeaders_andManualCollapseState() throws Exception
	{
		clickTitle("Source 7");
		Map<Object, Object> headers = new IdentityHashMap<>();
		for (Object c : displayed())
		{
			headers.put(c, field(c, "header"));
		}
		List<Object> cardsBefore = displayed();
		toIndividual();
		assertTrue(displayed().isEmpty());
		toGrouped();
		assertEquals("same retained cards", cardsBefore, displayed());
		for (Object c : displayed())
		{
			assertSame("headers retained", headers.get(c), field(c, "header"));
		}
		assertNull("collapsed stays collapsed", body(card("Source 7")));
		assertTrue(manualCollapsed("Source 7", 7));
		assertEquals("switching never writes preferences", 1, writes);
	}


	@Test
	public void newDrop_rebuildsOnlyTheChangedCard_andMovesIt() throws Exception
	{
		Map<Object, Object> headers = new IdentityHashMap<>();
		for (Object c : displayed())
		{
			headers.put(c, field(c, "header"));
		}
		Object source3 = card("Source 3");
		index.apply(new LootTrackerRecord("new", "Source 3", 3, T0.plusSeconds(99999),
			Collections.singletonList(new LootTrackerItem(1030, "Rune item 1030", 5))));
		render();
		assertSame("moved to the top", source3, displayed().get(0));
		for (Object c : displayed())
		{
			if (c != source3)
			{
				assertSame("unchanged cards keep their header", headers.get(c), field(c, "header"));
			}
		}
	}

	// ------------------------------------------------------------------ 24 width

	@Test
	public void toolbarWithBulkControls_fitsNarrowSidebars() throws Exception
	{
		for (int width : new int[] {150, 213, 260})
		{
			view.setSize(width, 2000);
			layout(view);
			layout(view);
			assertNull("one smart bulk button, not two", button(LootTrackerView.BULK_OPEN_TEXT));
			for (String text : new String[] {LootTrackerView.BULK_COLLAPSE_TEXT})
			{
				JButton b = button(text);
				java.awt.Rectangle r = javax.swing.SwingUtilities.convertRectangle(b.getParent(), b.getBounds(), view);
				assertTrue(width + "px: " + text + " starts inside", r.x >= 0);
				assertTrue(width + "px: " + text + " ends inside (" + (r.x + r.width) + ")", r.x + r.width <= width);
				assertTrue(width + "px: " + text + " not clipped", b.getWidth() >= b.getPreferredSize().width);
			}
		}
	}

	@Test
	public void repeatedModeAndBulkCycles_neverGrowTheComponentTree() throws Exception
	{
		toIndividual();
		int individualBaseline = all(view).size();
		toGrouped();
		int groupedBaseline = all(view).size();
		for (int cycle = 0; cycle < 6; cycle++)
		{
			toIndividual();
			assertEquals("Individual, cycle " + cycle, individualBaseline, all(view).size());
			assertTrue(view.individualRecordsMaterialized() <= LootTrackerView.MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED);
			toGrouped();
			press(button("▸ All"));
			press(button("▾ All"));
			assertEquals("Grouped after bulk, cycle " + cycle, groupedBaseline, all(view).size());
		}
		assertEquals("cards are retained, never duplicated", 15, displayed().size());
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
}
