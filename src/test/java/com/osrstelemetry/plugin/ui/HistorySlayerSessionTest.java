package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.LootValuationMode;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionPersistence;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * HISTORY FOR MULTI-TASK SLAYER SESSIONS: title, task summary, chronological
 * per-assignment loot cards, collapse, valuation, legacy/old-schema
 * compatibility and narrow-sidebar layout. Every session here is a literal
 * saved-JSON fixture written to disk and loaded through the real
 * SessionPersistence.loadFinalized() path -- exactly what History reads.
 */
public class HistorySlayerSessionTest
{
	private static final long ACCOUNT = 700_900_700L;
	private static final Gson GSON = new Gson();

	private Path testRoot;
	private LocalStateStore store;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("history-slayer-session-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		store = new LocalStateStore(GSON);
		store.start();
	}

	@After
	public void tearDown() throws Exception
	{
		store.shutdown();
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	// ------------------------------------------------------------------ fixtures

	private static String q(String s)
	{
		return s.replace('\'', '"');
	}

	private static String session(String id, String type, String key, String name, String aggregates)
	{
		return q("{'sessionId':'" + id + "','activityIdentity':{'activityType':'" + type + "','activityKey':'" + key
			+ "','displayName':'" + name + "'},'state':'FINALIZED','startedAt':'2026-09-20T10:00:00Z',"
			+ "'lastActiveAt':'2026-09-20T11:00:00Z','finalizedAt':'2026-09-20T11:30:00Z',"
			+ "'accumulatedActiveDurationMillis':3600000,'aggregates':" + aggregates + "}");
	}

	private static String item(int id, String name, long qty)
	{
		return "{'itemId':" + id + ",'itemName':'" + name + "','quantity':" + qty + "}";
	}

	private static String group(String source, String... items)
	{
		return "{'sourceName':'" + source + "','observedAt':'2026-09-20T10:30:00Z','items':[" + String.join(",", items) + "]}";
	}

	/** A: finalized session from BEFORE the assignment ledger existed. */
	private static final String LEGACY_NO_LEDGER = session("legacy", "SLAYER", "kalphites@kalphite lair", "Kalphites",
		"{'xpGainedBySkill':{'SLAYER':5000},'lootDrops':[" + group("Kalphite Worker", item(995, "Coins", 500)) + "]}");

	/** B: ledger records from before endReason / unitsCompleted / task XP / duration existed. */
	private static final String OLD_SCHEMA_LEDGER = session("oldschema", "SLAYER", "kalphites@kalphite lair", "Kalphites",
		"{'xpGainedBySkill':{'SLAYER':800},'lootDrops':[" + group("Nechryael", item(995, "Coins", 100)) + "," + group("Kalphite Worker", item(995, "Coins", 20)) + "],"
			+ "'slayerAssignments':["
			+ "{'ordinal':1,'taskName':'Nechryael','assignmentKey':'nechryael@catacombs of kourend','assignedAt':'2026-09-20T10:00:00Z','initialAmount':5,'latestRemaining':0,'endedAt':'2026-09-20T10:40:00Z','bootstrapped':false,'items':[" + item(995, "Coins", 100) + "]},"
			+ "{'ordinal':2,'taskName':'Kalphites','assignmentKey':'kalphites@kalphite lair','latestRemaining':12,'bootstrapped':true,'items':[" + item(995, "Coins", 20) + "]}]}");

	/**
	 * C: current schema, oldest first:
	 * #1 Bloodveld COMPLETED x10 (coins 300, bones 10), #2 Black demons SKIPPED x2 with ZERO loot,
	 * #3 Kalphites SKIPPED x5 (coins 50, adamant arrow 25), #4 Kalphites (same name) unfinished x8 (coins 70).
	 */
	private static final String NEW_SCHEMA_MULTI = session("multi", "SLAYER", "kalphites@kalphite lair", "Kalphites",
		"{'xpGainedBySkill':{'SLAYER':2400,'RANGED':3000},'lootDrops':["
			+ group("Bloodveld", item(995, "Coins", 300), item(526, "Bones", 10)) + ","
			+ group("Kalphite Worker", item(995, "Coins", 50), item(1123, "Adamant arrow", 25)) + ","
			+ group("Kalphite Soldier", item(995, "Coins", 70)) + "],"
			+ "'slayerAssignments':["
			+ record(1, "Bloodveld", "COMPLETED", 10, 0, 10, false, "{'SLAYER':1200,'RANGED':3000}", 60000L, 660000L, item(995, "Coins", 300), item(526, "Bones", 10)) + ","
			+ record(2, "Black demons", "REPLACED_EARLY", 30, 28, 2, false, "{'SLAYER':300}", 660000L, 780000L) + ","
			+ record(3, "Kalphites", "REPLACED_EARLY", 40, 35, 5, false, "{'SLAYER':0}", 780000L, 900000L, item(995, "Coins", 50), item(1123, "Adamant arrow", 25)) + ","
			+ record(4, "Kalphites", null, 20, 12, 8, false, "{'SLAYER':900}", 900000L, null, item(995, "Coins", 70))
			+ "]}");

	private static String record(int ordinal, String task, String endReason, int initial, int remaining, int units, boolean bootstrapped,
		String xp, Long start, Long end, String... items)
	{
		return "{'ordinal':" + ordinal + ",'taskName':'" + task + "','assignmentKey':'" + task.toLowerCase() + "@x',"
			+ "'assignedAt':'2026-09-20T10:0" + ordinal + ":00Z','initialAmount':" + initial + ",'latestRemaining':" + remaining + ","
			+ (endReason == null ? "" : "'endReason':'" + endReason + "','endedAt':'2026-09-20T10:1" + ordinal + ":00Z',")
			+ ("COMPLETED".equals(endReason) ? "'completedAt':'2026-09-20T10:1" + ordinal + ":00Z'," : "")
			+ "'bootstrapped':" + bootstrapped + ",'unitsCompleted':" + units + ",'xpGainedBySkill':" + xp + ","
			+ "'activeDurationStartMillis':" + start + (end == null ? "" : ",'activeDurationEndMillis':" + end)
			+ ",'items':[" + String.join(",", items) + "]}";
	}

	/** One-task session in the current schema. */
	private static final String NEW_SCHEMA_SINGLE = session("single", "SLAYER", "bloodveld@x", "Bloodveld",
		"{'lootDrops':[" + group("Bloodveld", item(995, "Coins", 300)) + "],'slayerAssignments':["
			+ record(1, "Bloodveld", "COMPLETED", 10, 0, 10, false, "{'SLAYER':1200}", 0L, 600000L, item(995, "Coins", 300)) + "]}");

	private Session load(String json) throws Exception
	{
		Session parsed = GSON.fromJson(q(json), Session.class);
		Path file = TestFilepaths.path(TelemetryPaths.sessionFile(ACCOUNT, parsed.getSessionId()));
		Files.createDirectories(file.getParent());
		Files.write(file, q(json).getBytes(StandardCharsets.UTF_8));
		Session loaded = new SessionPersistence(store).loadFinalized(ACCOUNT, parsed.getSessionId());
		assertNotNull("fixture loads through the real read path", loaded);
		return loaded;
	}

	private HistoryDetailModel model(String json) throws Exception
	{
		return HistoryDetailModel.from(load(json));
	}

	// ------------------------------------------------------------------ view helpers

	private static final class Cfg implements OsrsTelemetryConfig
	{
		LootValuationMode mode = LootValuationMode.GRAND_EXCHANGE;

		@Override
		public LootValuationMode lootValuationMode()
		{
			return mode;
		}
	}

	/** GE: coins 1, bones 100, arrows 80. HA: coins 1, bones 50, arrows 40. */
	private static HistoryDetailView view(Cfg cfg) throws Exception
	{
		HistoryDetailView v = new HistoryDetailView(null, null, null, cfg, null);
		Object cache = field(v, "itemValuationCache");
		seed(field(cache, "gePrices"), 995, 1L, 526, 100L, 1123, 80L);
		seed(field(cache, "haPrices"), 995, 1L, 526, 50L, 1123, 40L);
		return v;
	}

	private static Object field(Object target, String name) throws Exception
	{
		java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(target);
	}

	private static void seed(Object resolvingCache, Object... idPrice) throws Exception
	{
		java.lang.reflect.Method put = resolvingCache.getClass().getDeclaredMethod("put", Object.class, Object.class);
		put.setAccessible(true);
		for (int i = 0; i < idPrice.length; i += 2)
		{
			put.invoke(resolvingCache, idPrice[i], idPrice[i + 1]);
		}
	}

	private static List<JPanel> taskCards(Container root)
	{
		List<JPanel> out = new ArrayList<>();
		collect(root, out);
		return out;
	}

	private static void collect(Container root, List<JPanel> out)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JPanel && c.getName() != null && c.getName().startsWith(HistoryDetailView.HISTORY_TASK_CARD_NAME_PREFIX))
			{
				out.add((JPanel) c);
			}
			else if (c instanceof Container)
			{
				collect((Container) c, out);
			}
		}
	}

	private static JPanel header(JPanel card)
	{
		return (JPanel) card.getComponent(0);
	}

	private static JLabel titleLabel(JPanel card)
	{
		return (JLabel) header(card).getComponent(0);
	}

	private static String title(JPanel card)
	{
		return titleLabel(card).getText().substring(2); // drop chevron
	}

	private static String value(JPanel card)
	{
		return ((JLabel) header(card).getComponent(1)).getText();
	}

	private static boolean expanded(JPanel card)
	{
		return titleLabel(card).getText().startsWith("▾");
	}

	private static JPanel grid(Container root)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof GridLayout)
			{
				return (JPanel) c;
			}
			if (c instanceof Container)
			{
				JPanel g = grid((Container) c);
				if (g != null)
				{
					return g;
				}
			}
		}
		return null;
	}

	private static List<String> labels(Container root, List<String> out)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null)
			{
				out.add(((JLabel) c).getText());
			}
			if (c instanceof Container)
			{
				labels((Container) c, out);
			}
		}
		return out;
	}

	private static String labelStartingWith(Container root, String prefix)
	{
		for (String s : labels(root, new ArrayList<>()))
		{
			if (UiText.visible(s).startsWith(prefix))
			{
				return s;
			}
		}
		return null;
	}

	private static long sum(List<HistoryDetailModel.LootRow> rows, int itemId)
	{
		long t = 0;
		for (HistoryDetailModel.LootRow r : rows)
		{
			if (r.getItemId() == itemId)
			{
				t += r.getQuantity();
			}
		}
		return t;
	}

	// ------------------------------------------------------------------ 1-3, 6, 12: identity, count, order, xN

	@Test
	public void singleTask_keepsTaskTitle_noTaskCards() throws Exception
	{
		HistoryDetailModel m = model(NEW_SCHEMA_SINGLE);
		assertEquals("Bloodveld", m.getTitle());
		assertFalse(m.isMultiTaskSlayerSession());
		assertTrue(m.getSlayerTasks().isEmpty());
		HistoryEntry e = HistoryEntry.from(load(NEW_SCHEMA_SINGLE));
		assertEquals("Bloodveld", HistoryEntry.displayTitle(e.getActivityIdentity(), e.getSlayerAssignmentCount()));
	}

	@Test
	public void multiTask_slayerSessionTitle_taskCount_chronological_sameNameSeparate_xN() throws Exception
	{
		HistoryDetailModel m = model(NEW_SCHEMA_MULTI);
		assertEquals("Slayer Session", m.getTitle());
		assertEquals("persisted identity is still the final task", "Kalphites", m.getActivityIdentity().getDisplayName());
		assertEquals(4, m.getSlayerTasks().size());
		List<Integer> ordinals = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (HistoryDetailModel.SlayerTask t : m.getSlayerTasks())
		{
			ordinals.add(t.getOrdinal());
			names.add(t.getTaskName());
		}
		assertEquals("oldest first", Arrays.asList(1, 2, 3, 4), ordinals);
		assertEquals("same-name tasks are two cards", Arrays.asList("Bloodveld", "Black demons", "Kalphites", "Kalphites"), names);
		assertEquals(Integer.valueOf(10), m.getSlayerTasks().get(0).getUnitsCompleted());
		assertEquals(Integer.valueOf(8), m.getSlayerTasks().get(3).getUnitsCompleted());
		assertEquals("4 tasks  •  1 completed  •  2 skipped", HistoryDetailView.slayerSummaryText(m));

		HistoryEntry e = HistoryEntry.from(load(NEW_SCHEMA_MULTI));
		assertEquals(4, e.getSlayerAssignmentCount());
		assertEquals("Slayer Session", HistoryEntry.displayTitle(e.getActivityIdentity(), e.getSlayerAssignmentCount()));
	}

	// ------------------------------------------------------------------ 4, 5, 7, 8, 9, 11: cards, partition, valuation

	@Test
	public void cards_skippedAndZeroLootIncluded_partitionPersistedLoot_valuedLikeTheSession() throws Exception
	{
		HistoryDetailModel m = model(NEW_SCHEMA_MULTI);
		for (int itemId : new int[] {995, 526, 1123})
		{
			long cards = 0;
			for (HistoryDetailModel.SlayerTask t : m.getSlayerTasks())
			{
				cards += sum(t.getLootRows(), itemId);
			}
			assertEquals("cards partition session loot for item " + itemId, sum(m.getLootRows(), itemId), cards);
		}

		Cfg cfg = new Cfg();
		HistoryDetailView v = view(cfg);
		v.render(m);
		List<JPanel> cards = taskCards(v);
		assertEquals(4, cards.size());
		assertEquals("Bloodveld x10", title(cards.get(0)));
		assertEquals("Black demons x2 · Skipped", title(cards.get(1)));
		assertEquals("Kalphites x5 · Skipped", title(cards.get(2)));
		assertEquals("unfinished at finalize: no guessed badge", "Kalphites x8", title(cards.get(3)));
		assertEquals("zero-loot task stays visible", "0 gp", value(cards.get(1)));

		// GE: 300 + 10*100 = 1,300 ; 50 + 25*80 = 2,050 ; 70. Session: 3,420.
		assertEquals("1,300 gp", value(cards.get(0)));
		assertEquals("2,050 gp", value(cards.get(2)));
		assertEquals("70 gp", value(cards.get(3)));
		assertEquals("3,420 gp", labelEndingGp(v));
		assertTrue("session LOOT header unchanged", labelStartingWith(v, "LOOT") != null);

		cfg.mode = LootValuationMode.HIGH_ALCH;
		v.render(m);
		cards = taskCards(v);
		assertEquals("800 gp", value(cards.get(0)));      // 300 + 10*50
		assertEquals("1,050 gp", value(cards.get(2)));    // 50 + 25*40
		assertEquals("1,920 gp", labelEndingGp(v));
	}

	/** The session-wide LOOT total (the bold header value). */
	private static String labelEndingGp(Container root)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JPanel)
			{
				JPanel p = (JPanel) c;
				if (p.getComponentCount() == 2 && p.getComponent(0) instanceof JLabel
					&& ((JLabel) p.getComponent(0)).getText() != null && ((JLabel) p.getComponent(0)).getText().startsWith("LOOT"))
				{
					return ((JLabel) p.getComponent(1)).getText();
				}
				String nested = labelEndingGp(p);
				if (nested != null)
				{
					return nested;
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ 10, 11, 22: collapse + grid

	@Test
	public void collapse_allCollapsedOnOpen_toggleExpandsOnlyThatCard_totalUnaffected_resetOnReopen() throws Exception
	{
		HistoryDetailModel m = model(NEW_SCHEMA_MULTI);
		HistoryDetailView v = view(new Cfg());
		v.showLoading();
		v.render(m);
		for (JPanel c : taskCards(v))
		{
			assertFalse("every card starts collapsed", expanded(c));
			assertNull(grid(c));
		}
		String total = labelEndingGp(v);

		v.toggleSlayerTask(3);
		List<JPanel> cards = taskCards(v);
		assertTrue(expanded(cards.get(2)));
		assertFalse(expanded(cards.get(0)));
		JPanel g = grid(cards.get(2));
		assertNotNull("expanded card shows its own loot grid", g);
		GridLayout layout = (GridLayout) g.getLayout();
		assertEquals("shared 5-column grid", LootGridCell.GRID_COLUMNS, layout.getColumns());
		assertEquals(LootGridCell.GRID_GAP, layout.getHgap());
		assertEquals("only this task's 2 items", 2, g.getComponentCount());
		assertEquals("collapse never changes the session total", total, labelEndingGp(v));

		v.render(m); // an ordinary re-render keeps the state
		assertTrue(expanded(taskCards(v).get(2)));

		v.toggleSlayerTask(3);
		assertFalse(expanded(taskCards(v).get(2)));

		v.toggleSlayerTask(1);
		v.showLoading();
		v.render(m);
		assertFalse("reopening starts collapsed again", expanded(taskCards(v).get(0)));
	}

	// ------------------------------------------------------------------ Part 6 / 7: per-task XP line + duration

	@Test
	public void expandedCard_compactXpLine_andDurationOnlyWhenBothBoundariesObserved() throws Exception
	{
		HistoryDetailModel m = model(NEW_SCHEMA_MULTI);
		HistoryDetailModel.SlayerTask bloodveld = m.getSlayerTasks().get(0);
		assertEquals("XP  +3,000 Ranged  •  +1,200 Slayer", HistoryDetailView.taskXpText(bloodveld));
		assertTrue("0-XP skills never listed", m.getSlayerTasks().get(2).getXpRows().isEmpty());
		assertEquals(Long.valueOf(600000L), bloodveld.getActiveDurationMillis());
		assertNull("unfinished at finalize: no guessed duration", m.getSlayerTasks().get(3).getActiveDurationMillis());

		HistoryDetailView v = view(new Cfg());
		v.render(m);
		v.toggleSlayerTask(1);
		JPanel card = taskCards(v).get(0);
		assertTrue(labels(card, new ArrayList<>()).stream().anyMatch(s -> s.contains("+1,200 Slayer")));
		assertTrue(titleLabel(card).getToolTipText().contains("10:00 active"));
		assertTrue(titleLabel(card).getToolTipText().contains("10 completed"));
		assertFalse(titleLabel(card).getToolTipText().contains("/ 10"));
		assertFalse(titleLabel(taskCards(v).get(3)).getToolTipText().contains("active"));
	}

	// ------------------------------------------------------------------ 13-15: bootstrapped, legacy, old schema

	@Test
	public void legacyNoLedger_originalHistoryBehaviourPreserved() throws Exception
	{
		HistoryDetailModel m = model(LEGACY_NO_LEDGER);
		assertEquals("never relabelled without proof", "Kalphites", m.getTitle());
		assertFalse(m.isMultiTaskSlayerSession());
		assertEquals(0, HistoryEntry.from(load(LEGACY_NO_LEDGER)).getSlayerAssignmentCount());
		HistoryDetailView v = view(new Cfg());
		v.render(m);
		assertTrue("no fabricated task cards", taskCards(v).isEmpty());
		assertNotNull("original single loot grid", grid(v));
		assertEquals("500 gp", labelEndingGp(v));
	}

	@Test
	public void oldSchemaLedger_loadsSafely_noFakeStatusOrProgress() throws Exception
	{
		HistoryDetailModel m = model(OLD_SCHEMA_LEDGER);
		assertEquals("Slayer Session", m.getTitle());
		assertEquals("2 tasks", HistoryDetailView.slayerSummaryText(m));
		HistoryDetailModel.SlayerTask nech = m.getSlayerTasks().get(0);
		HistoryDetailModel.SlayerTask kal = m.getSlayerTasks().get(1);
		assertNull(nech.getEndReason());
		assertEquals("fully observed legacy record: initial - remaining", Integer.valueOf(5), nech.getUnitsCompleted());
		assertNull("bootstrapped legacy record: no invented count", kal.getUnitsCompleted());
		assertTrue(nech.getXpRows().isEmpty());
		assertNull(nech.getActiveDurationMillis());

		HistoryDetailView v = view(new Cfg());
		v.render(m);
		List<JPanel> cards = taskCards(v);
		assertEquals("Nechryael x5", title(cards.get(0)));
		assertEquals("Kalphites", title(cards.get(1)));
		String tip = titleLabel(cards.get(1)).getToolTipText();
		assertFalse(tip.contains("Skipped"));
		assertFalse(tip.contains("Completed"));
		assertFalse(tip.contains(" / "));
	}

	@Test
	public void bootstrappedTask_neverInventsTotalOrDuration()
	{
		String tip = SlayerTaskCards.tooltip("Kalphites", 4, true, 60, null, null);
		assertTrue(tip.contains("4 completed (tracked since Spyglass joined mid-task)"));
		assertFalse(tip.contains("/ 143"));
	}

	@Test
	public void fullyObservedTask_tooltipNeverShowsTheAssignmentSize()
	{
		String tip = SlayerTaskCards.tooltip("Kalphites", 40, false, 103, null, null);
		assertTrue(tip.contains("40 completed"));
		assertTrue(tip.contains("103 remaining"));
		assertFalse("the assignment's original size is never shown", tip.contains("/ "));
		assertFalse(tip.contains("143"));
	}

	// ------------------------------------------------------------------ 21: narrow widths

	@Test
	public void narrowSidebar_detailAndListFitAt150To260_noRightEdgeClipping() throws Exception
	{
		HistoryDetailModel m = model(NEW_SCHEMA_MULTI);
		HistoryEntry entry = HistoryEntry.from(load(NEW_SCHEMA_MULTI));
		for (int width : new int[] {150, 180, 213, 260})
		{
			HistoryDetailView v = view(new Cfg());
			v.render(m);
			for (int ordinal = 1; ordinal <= 4; ordinal++)
			{
				v.toggleSlayerTask(ordinal);
			}
			layoutAt(v, width);
			assertInside(v, v, width);
			for (JPanel card : taskCards(v))
			{
				JLabel value = (JLabel) header(card).getComponent(1);
				assertTrue(width + "px: gp value never truncated", value.getWidth() >= value.getPreferredSize().width);
			}
			JLabel headline = findLabel(v, "Slayer Session");
			assertTrue(width + "px: title fully visible", headline.getWidth() >= headline.getPreferredSize().width);

			HistoryView list = new HistoryView(null, null, null, new Cfg(), null);
			list.render(Collections.singletonList(entry));
			layoutAt(list, width);
			assertInside(list, list, width);
			JLabel rowTitle = findLabel(list, "Slayer Session");
			assertTrue(width + "px: list title fully visible", rowTitle.getWidth() >= rowTitle.getPreferredSize().width);
			assertTrue(labelStartingWith(list, "4 tasks") != null);
		}
	}

	private static void layoutAt(Container c, int width)
	{
		c.setSize(width, 4000);
		for (int pass = 0; pass < 2; pass++) // second pass: WrappingHtmlLabel re-measures at the laid-out width
		{
			c.invalidate();
			layout(c);
		}
	}

	private static void layout(Container c)
	{
		c.doLayout();
		for (Component child : c.getComponents())
		{
			if (child instanceof Container)
			{
				layout((Container) child);
			}
		}
	}

	private static void assertInside(Container root, Container c, int width)
	{
		for (Component child : c.getComponents())
		{
			if (!child.isVisible() || child.getWidth() == 0)
			{
				continue;
			}
			Rectangle r = SwingUtilities.convertRectangle(c, child.getBounds(), root);
			assertTrue(width + "px: " + child.getClass().getSimpleName() + " right edge " + (r.x + r.width) + " beyond " + width,
				r.x >= 0 && r.x + r.width <= width);
			if (child instanceof Container)
			{
				assertInside(root, (Container) child, width);
			}
		}
	}

	private static JLabel findLabel(Container root, String text)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JLabel && text.equals(UiText.visible(((JLabel) c).getText())) && c.isVisible() && c.getWidth() > 0)
			{
				return (JLabel) c;
			}
			if (c instanceof Container)
			{
				JLabel l = findLabel((Container) c, text);
				if (l != null)
				{
					return l;
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ 23, 24: independence / no reconstruction

	@Test
	public void historyReadsOnlyTheLoadedSession_neverLootTrackerOrTelemetry() throws Exception
	{
		for (String file : Arrays.asList("HistoryDetailModel", "HistoryDetailView", "HistoryView", "SlayerTaskCards"))
		{
			String src = new String(Files.readAllBytes(Paths.get("src/main/java/com/osrstelemetry/plugin/ui/" + file + ".java")), StandardCharsets.UTF_8)
				.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", ""); // code only, not comments
			for (String forbidden : Arrays.asList("loottracker", "LootTracker", "EventLedger", "eventsFile", "events.jsonl", "SessionPersistence", "TelemetryPaths"))
			{
				assertFalse(file + " must not touch " + forbidden, src.contains(forbidden));
			}
		}
	}

	// ------------------------------------------------------------------ Smart Slayer presentation pass

	/** Current schema + bracelet tracking + master: Nechryael (Konar) completed, Kalphites (Duradel) unfinished. */
	private static final String TRACKED = session("tracked", "SLAYER", "kalphites@x", "Kalphites",
		"{'lootDrops':[" + group("Nechryael", item(995, "Coins", 300)) + "],'slayerAssignments':["
			+ record(1, "Nechryael", "COMPLETED", 10, 0, 10, false, "{'SLAYER':1200}", 0L, 600000L, item(995, "Coins", 300))
				.replace("'bootstrapped'", "'masterId':8,'slaughterProcs':2,'expeditiousProcs':1,'bootstrapped'") + ","
			+ record(2, "Kalphites", null, 20, 12, 8, false, "{'SLAYER':900}", 600000L, null)
				.replace("'bootstrapped'", "'masterId':5,'slaughterProcs':0,'expeditiousProcs':0,'bootstrapped'")
			+ "]}");

	@Test
	public void trackedSession_slayerBlock_killsRateAndBracelets_taskTooltipsCarryKillsAndMaster() throws Exception
	{
		HistoryDetailModel m = model(TRACKED);
		assertEquals("(10 - 1 + 2) + 8", Integer.valueOf(19), m.getSlayerKills());
		assertEquals(2, m.getSlayerSlaughterProcs());
		assertEquals(1, m.getSlayerExpeditiousProcs());
		assertEquals(Integer.valueOf(11), m.getSlayerTasks().get(0).getKills());
		assertEquals(Integer.valueOf(8), m.getSlayerTasks().get(0).getMasterId());

		HistoryDetailView v = view(new Cfg());
		v.render(m);
		List<String> all = labels(v, new ArrayList<>());
		assertTrue(all.contains("SLAYER"));
		assertTrue(all.contains("Kills 19"));
		assertTrue("1 hour active", all.contains("19/hr"));
		assertTrue(all.contains("Slaughter 2"));
		assertTrue(all.contains("Expeditious 1"));
		String tip = titleLabel(taskCards(v).get(0)).getToolTipText();
		assertTrue(tip, tip.contains("11 kills"));
		assertTrue(tip, tip.contains("Slaughter 2"));
		assertTrue(tip, tip.contains("Konar quo Maten"));
		assertFalse("no assignment size in History either", tip.contains("/ 10"));
		String current = titleLabel(taskCards(v).get(1)).getToolTipText();
		assertTrue(current, current.contains("Duradel / Kuradal"));
		assertFalse("zero bracelet effects are not listed", current.contains("Slaughter"));
	}

	@Test
	public void olderSessions_haveNoSlayerBlock_andNoFabricatedKills() throws Exception
	{
		for (String json : new String[] {LEGACY_NO_LEDGER, OLD_SCHEMA_LEDGER, NEW_SCHEMA_MULTI, NEW_SCHEMA_SINGLE})
		{
			HistoryDetailModel m = model(json);
			assertNull(m.getSlayerKills());
			assertFalse(m.hasSlayerBlock());
			HistoryDetailView v = view(new Cfg());
			v.render(m);
			assertFalse(labels(v, new ArrayList<>()).contains("SLAYER"));
		}
	}

	@Test
	public void trackedSession_narrowWidths_slayerBlockFits() throws Exception
	{
		HistoryDetailModel m = model(TRACKED);
		for (int width : new int[] {150, 180, 213, 260})
		{
			HistoryDetailView v = view(new Cfg());
			v.render(m);
			v.setSize(width, 4000);
			layoutAll(v);
			layoutAll(v);
			for (java.awt.Component c : allComponents(v))
			{
				if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty()
					&& c.getName() != null && c.getName().startsWith("historySlayer"))
				{
					java.awt.Rectangle r = javax.swing.SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), v);
					assertTrue(width + "px: '" + ((JLabel) c).getText() + "' clipped", c.getWidth() >= c.getPreferredSize().width);
					assertTrue(width + "px: overflow", r.x + r.width <= width + 1);
				}
			}
		}
	}

	private static void layoutAll(Container c)
	{
		c.doLayout();
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof Container)
			{
				layoutAll((Container) k);
			}
		}
	}

	private static List<java.awt.Component> allComponents(Container root)
	{
		List<java.awt.Component> out = new ArrayList<>();
		for (java.awt.Component c : root.getComponents())
		{
			out.add(c);
			if (c instanceof Container)
			{
				out.addAll(allComponents((Container) c));
			}
		}
		return out;
	}
}
