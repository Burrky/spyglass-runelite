package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.collectors.NpcInteractionTargetCollector;
import com.osrstelemetry.plugin.history.HistoryCoordinator;
import com.osrstelemetry.plugin.loottracker.LootTrackerCoordinator;
import com.osrstelemetry.plugin.loottracker.LootTrackerPreferences;
import com.osrstelemetry.plugin.session.SessionRuntimeCoordinator;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Panel-level navigation: the fixed header and owned scroll pane, Home and the
 * Farming child route, route persistence through the attached store, and the
 * account-safe restore of a saved History detail.
 */
public class OsrsTelemetryPanelNavigationTest
{
	private static final long ACCOUNT = 700_900_981L;
	private static final String FOREIGN_SESSION = "4f7c2a9e-1b3d-4c5e-8f60-0a1b2c3d4e5f";
	private static final Gson GSON = new Gson();

	private Path testRoot;
	private LocalStateStore store;
	private SessionRuntimeCoordinator session;
	private LootTrackerCoordinator lootTracker;
	private HistoryCoordinator history;
	private LootTrackerPreferences prefs;
	private NpcInteractionTargetCollector npc;
	private OsrsTelemetryPanel panel;
	private final List<String> written = new ArrayList<>();

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("panel-nav-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		store = new LocalStateStore(GSON);
		store.start();
		session = new SessionRuntimeCoordinator(store);
		session.ensureAccountLoaded(ACCOUNT);
		lootTracker = new LootTrackerCoordinator(GSON);
		lootTracker.start();
		history = new HistoryCoordinator(store);
		history.start();
		prefs = new LootTrackerPreferences(store);
		prefs.loadForAccount(ACCOUNT);
		net.runelite.api.Client client = (net.runelite.api.Client) java.lang.reflect.Proxy.newProxyInstance(
			net.runelite.api.Client.class.getClassLoader(), new Class<?>[] {net.runelite.api.Client.class},
			(proxy, method, args) -> method.getReturnType() == long.class ? (Object) ACCOUNT
				: method.getReturnType() == int.class ? (Object) 0 : method.getReturnType() == boolean.class ? (Object) false : null);
		npc = new NpcInteractionTargetCollector(client, null);
	}

	@After
	public void tearDown() throws Exception
	{
		if (panel != null)
		{
			SwingUtilities.invokeAndWait(() -> panel.shutdown());
		}
		history.shutdown();
		lootTracker.shutdown();
		store.shutdown();
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private void build(String savedRoute) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			panel = new OsrsTelemetryPanel(session, lootTracker, prefs, history, null, null, null, npc,
				new OsrsTelemetryConfig()
				{
				});
			panel.attachNavigationStore(() -> savedRoute, written::add);
			panel.setSize(242, 600);
			panel.validate();
		});
		flushEdt();
	}

	private static void flushEdt() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> { });
		SwingUtilities.invokeAndWait(() -> { });
	}

	private NavRoute route() throws Exception
	{
		AtomicReference<NavRoute> out = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> out.set(panel.currentRoute()));
		return out.get();
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

	private <T> T find(Class<T> type) throws Exception
	{
		for (Component c : all(panel))
		{
			if (type.isInstance(c))
			{
				return type.cast(c);
			}
		}
		throw new AssertionError("no " + type.getSimpleName());
	}

	private void click(String tabText) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			for (Component c : all(panel))
			{
				if (c instanceof JButton && matches((JButton) c, tabText))
				{
					for (ActionListener l : ((JButton) c).getActionListeners())
					{
						l.actionPerformed(null);
					}
					return;
				}
			}
			throw new AssertionError("no button " + tabText);
		});
		flushEdt();
	}

	private static boolean matches(JButton b, String text)
	{
		return text.equals(b.getText()) || text.equals(b.getToolTipText())
			|| text.equals(b.getAccessibleContext().getAccessibleName());
	}

	private void clickFarmingCard() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			RaisedTile farming = null;
			for (Component c : all(panel))
			{
				if (c instanceof HomeView)
				{
					farming = ((HomeView) c).cards().get(0);
				}
			}
			farming.setSize(200, 60);
			long now = System.currentTimeMillis();
			farming.dispatchEvent(new MouseEvent(farming, MouseEvent.MOUSE_PRESSED, now, MouseEvent.BUTTON1_DOWN_MASK, 10, 10, 1, false, MouseEvent.BUTTON1));
			farming.dispatchEvent(new MouseEvent(farming, MouseEvent.MOUSE_RELEASED, now, 0, 10, 10, 1, false, MouseEvent.BUTTON1));
		});
		flushEdt();
	}

	private boolean visible(Class<?> type) throws Exception
	{
		Component c = (Component) find(type);
		return c.isVisible();
	}

	@Test
	public void noSavedRoute_opensHome_withItsCardsInTheLockedOrder() throws Exception
	{
		build(null);
		assertEquals(NavRoute.HOME, route());
		assertTrue(visible(HomeView.class));
		List<RaisedTile> cards = find(HomeView.class).cards();
		assertEquals(6, cards.size());
		assertTrue("Farming navigates", cards.get(0).isInteractive());
		assertTrue("Bird houses opens its page (pass 30: its own card)", cards.get(1).isInteractive());
		assertTrue("Kingdom opens its page", cards.get(2).isInteractive());
		assertTrue("Dailies & Weeklies opens its page", cards.get(3).isInteractive());
		assertFalse("Notes has no destination yet", cards.get(4).isInteractive());
		assertFalse("PvP is a placeholder tile, never a page", cards.get(5).isInteractive());
		assertEquals(java.util.Arrays.asList("Not seen yet", "Read on Fossil Island"), find(HomeView.class).lines(3));
		for (RaisedTile card : cards)
		{
			assertNull("nothing read: no urgency rail", card.getRail());
		}
	}

	private void clickHomeCard(int index) throws Exception
	{
		HomeView home = find(HomeView.class);
		SwingUtilities.invokeAndWait(() ->
		{
			RaisedTile card = home.cards().get(index);
			card.setSize(200, 60);
			long now = System.currentTimeMillis();
			card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_PRESSED, now, MouseEvent.BUTTON1_DOWN_MASK, 10, 10, 1, false, MouseEvent.BUTTON1));
			card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_RELEASED, now, 0, 10, 10, 1, false, MouseEvent.BUTTON1));
		});
		flushEdt();
	}

	@Test
	public void birdHousesCard_opensTheBirdHousePageDirectly_andUpReturnsHome() throws Exception
	{
		build(null);
		clickHomeCard(1);
		assertEquals(NavRoute.of(NavRoute.Section.HOME, NavRoute.BIRDHOUSES), route());
		assertTrue(visible(BirdhouseView.class));
		assertEquals("Bird houses", NavHeader.subHeaderTitle(route()));
		assertEquals("Home", NavHeader.parentLabel(route()));
		assertEquals(NavRoute.HOME, route().parent());
		assertEquals("Dailies & Weeklies", NavHeader.subHeaderTitle(NavRoute.of(NavRoute.Section.HOME, NavRoute.DAILIES)));
	}

	@Test
	public void textSizeButton_opensTheAppearancePage() throws Exception
	{
		build(null);
		HomeView home = find(HomeView.class);
		SwingUtilities.invokeAndWait(() -> home.appearanceButton().press());
		flushEdt();
		assertEquals(NavRoute.of(NavRoute.Section.HOME, NavRoute.APPEARANCE), route());
		assertTrue(visible(AppearanceView.class));
		assertEquals("Text size & contrast", NavHeader.subHeaderTitle(route()));
	}

	@Test
	public void unparseableSavedRoute_opensHome() throws Exception
	{
		build("not-a-route");
		assertEquals(NavRoute.HOME, route());
	}

	@Test
	public void farmingCard_opensFarming_underTheHouse_andUpReturnsHome() throws Exception
	{
		build(null);
		clickFarmingCard();
		assertEquals(NavRoute.of(NavRoute.Section.HOME, NavRoute.FARMING), route());
		assertTrue(visible(FarmingView.class));
		assertTrue("fixed sub-header", find(NavHeader.class).isSubHeaderVisible());
		assertEquals("HOME/FARMING", written.get(written.size() - 1));

		click("Back to Home");
		assertEquals(NavRoute.HOME, route());
		assertFalse(find(NavHeader.class).isSubHeaderVisible());
		assertEquals("HOME", written.get(written.size() - 1));
	}

	@Test
	public void savedFarmingRoute_isRestored_andTheHouseAlwaysGoesToHomeRoot() throws Exception
	{
		build("HOME/FARMING");
		assertEquals(NavRoute.of(NavRoute.Section.HOME, NavRoute.FARMING), route());
		assertTrue(visible(FarmingView.class));
		click(NavHeader.HOME_TOOLTIP);
		assertEquals(NavRoute.HOME, route());
		assertTrue(visible(HomeView.class));
	}

	@Test
	public void sections_switch_andRestorePersists_withoutRebuildingViews() throws Exception
	{
		build("LOOT");
		assertEquals(NavRoute.root(NavRoute.Section.LOOT), route());
		assertTrue(visible(LootTrackerView.class));
		Component loot = find(LootTrackerView.class);
		click("Session");
		assertEquals(NavRoute.root(NavRoute.Section.SESSION), route());
		assertEquals("SESSION", written.get(written.size() - 1));
		click("Loot");
		assertSame("views are kept, never recreated", loot, find(LootTrackerView.class));
		click("Loot");
		assertEquals("re-clicking the active section keeps its root", NavRoute.root(NavRoute.Section.LOOT), route());
	}

	@Test
	public void headerIsOutsideTheOnlyScrollPane() throws Exception
	{
		build("HISTORY");
		List<JScrollPane> panes = new ArrayList<>();
		for (Component c : all(panel))
		{
			if (c instanceof JScrollPane)
			{
				panes.add((JScrollPane) c);
			}
		}
		assertEquals("exactly one scroll pane: no nested wheel-swallowing panes", 1, panes.size());
		NavHeader header = find(NavHeader.class);
		assertNull("the header never scrolls", SwingUtilities.getAncestorOfClass(JScrollPane.class, header));
		assertSame("Loot finds this pane for its own scroll restore",
			panes.get(0), SwingUtilities.getAncestorOfClass(JScrollPane.class, find(LootTrackerView.class)));
	}

	@Test
	public void savedHistoryDetail_waitsWhileNoAccountIsLoaded_insteadOfBeingDiscarded() throws Exception
	{
		build("HISTORY/" + FOREIGN_SESSION);
		assertEquals(NavRoute.of(NavRoute.Section.HISTORY, FOREIGN_SESSION), route());
		assertNull("nothing shown before History is known", find(HistoryView.class).getOpenDetailSessionId());
		assertTrue("the saved value is not rewritten", written.isEmpty());
		assertTrue("History's parent navigation is the fixed sub-header", find(NavHeader.class).isSubHeaderVisible());

		click("Back to History");
		assertEquals(NavRoute.root(NavRoute.Section.HISTORY), route());
		assertFalse(find(NavHeader.class).isSubHeaderVisible());
		assertEquals("HISTORY", written.get(written.size() - 1));
	}

	@Test
	public void savedHistoryDetail_fromAnotherAccount_fallsBackToTheHistoryList() throws Exception
	{
		build("HISTORY/" + FOREIGN_SESSION);
		history.ensureAccountLoaded(ACCOUNT); // this account has no such session
		long deadline = System.currentTimeMillis() + 5000;
		while (!history.isInitialScanComplete() && System.currentTimeMillis() < deadline)
		{
			Thread.sleep(10);
		}
		assertTrue(history.isInitialScanComplete());
		flushEdt();
		flushEdt();
		assertEquals(NavRoute.root(NavRoute.Section.HISTORY), route());
		assertNull(find(HistoryView.class).getOpenDetailSessionId());
		assertEquals("HISTORY", written.get(written.size() - 1));
	}
}
