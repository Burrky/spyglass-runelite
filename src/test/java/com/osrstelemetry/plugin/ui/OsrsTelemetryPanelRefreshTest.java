package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.collectors.NpcInteractionTargetCollector;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Panel-level refresh behaviour: backend changes render the VISIBLE surface
 * on the next EDT turn without any timer; hidden surfaces stay dirty until
 * shown; shutdown detaches every listener and ignores late refreshes.
 */
public class OsrsTelemetryPanelRefreshTest
{
	private static final long ACCOUNT = 700_900_980L;
	private static final Gson GSON = new Gson();

	private Path testRoot;
	private LocalStateStore store;
	private SessionRuntimeCoordinator session;
	private LootTrackerCoordinator lootTracker;
	private HistoryCoordinator history;
	private OsrsTelemetryPanel panel;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("panel-refresh-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		store = new LocalStateStore(GSON);
		store.start();
		session = new SessionRuntimeCoordinator(store);
		session.ensureAccountLoaded(ACCOUNT);
		lootTracker = new LootTrackerCoordinator(GSON);
		lootTracker.start();
		lootTracker.ensureAccountLoaded(ACCOUNT);
		lootTracker.shutdown();
		lootTracker.start();
		history = new HistoryCoordinator(store);
		history.start();
		history.ensureAccountLoaded(ACCOUNT);
		LootTrackerPreferences prefs = new LootTrackerPreferences(store);
		prefs.loadForAccount(ACCOUNT);
		net.runelite.api.Client client = (net.runelite.api.Client) java.lang.reflect.Proxy.newProxyInstance(
			net.runelite.api.Client.class.getClassLoader(), new Class<?>[] {net.runelite.api.Client.class},
			(proxy, method, args) -> method.getReturnType() == long.class ? (Object) ACCOUNT
				: method.getReturnType() == int.class ? (Object) 0 : method.getReturnType() == boolean.class ? (Object) false : null);
		NpcInteractionTargetCollector npc = new NpcInteractionTargetCollector(client, null);
		SwingUtilities.invokeAndWait(() -> panel = new OsrsTelemetryPanel(session, lootTracker, prefs, history, null, null, null, npc,
			new OsrsTelemetryConfig()
			{
			}));
		flushEdt();
	}

	@After
	public void tearDown() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> panel.shutdown());
		history.shutdown();
		lootTracker.shutdown();
		store.shutdown();
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private static void flushEdt() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> { });
		SwingUtilities.invokeAndWait(() -> { });
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

	private Object lootView() throws Exception
	{
		return field(panel, "lootTrackerView");
	}

	private Object sessionView() throws Exception
	{
		return field(panel, "currentSessionView");
	}

	private void selectTab(String text) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			for (Component c : all(panel))
			{
				if (c instanceof JButton && text.equals(((JButton) c).getText()))
				{
					for (ActionListener l : ((JButton) c).getActionListeners())
					{
						l.actionPerformed(null);
					}
					return;
				}
			}
		});
	}

	private static java.util.List<Component> all(Container c)
	{
		java.util.List<Component> out = new java.util.ArrayList<>();
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

	private void loot(String eventId)
	{
		lootTracker.onEvent(ACCOUNT, EventType.SERVER_NPC_LOOT, new EventPayloads.ServerNpcLoot("Goblin", 3029,
			Arrays.asList(new EventPayloads.ServerNpcLootItem(995, "Coins", 5))), Instant.now(), eventId);
	}

	@Test
	public void startup_registersEachListenerOnce_shutdownDetachesThem() throws Exception
	{
		assertTrue(field(session, "currentSessionListener") != null);
		assertTrue(field(lootTracker, "changeListener") != null);
		assertTrue(field(history, "changeListener") != null);
		SwingUtilities.invokeAndWait(() -> panel.shutdown());
		assertNull(field(session, "currentSessionListener"));
		assertNull(field(lootTracker, "changeListener"));
		assertNull(field(history, "changeListener"));
		// A late backend change after shutdown renders nothing.
		Component before = ((Container) field(sessionView(), "content")).getComponent(0);
		Object lootBefore = field(lootView(), "lastSnapshot");
		loot("late");
		session.ensureAccountLoaded(ACCOUNT + 1);
		flushEdt();
		assertSame(before, ((Container) field(sessionView(), "content")).getComponent(0));
		assertSame(lootBefore, field(lootView(), "lastSnapshot"));
	}

	@Test
	public void visibleSurface_rendersOnTheNextEdtTurn_withoutTheTimer() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> ((javax.swing.Timer) fieldQuiet(panel, "pollTimer")).stop());
		selectTab("Loot");
		Object before = field(lootView(), "lastSnapshot");
		loot("a");
		flushEdt();
		Object after = field(lootView(), "lastSnapshot");
		assertNotSame("rendered from the change signal alone", before, after);
		assertEquals(1, ((com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot) after).getAllSources().size());
	}

	@Test
	public void hiddenSurface_staysDirty_andRendersTheMomentItIsShown() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> ((javax.swing.Timer) fieldQuiet(panel, "pollTimer")).stop());
		selectTab("Session");
		Object before = field(lootView(), "lastSnapshot");
		loot("b");
		loot("c");
		flushEdt();
		assertSame("hidden Loot tab: no Swing work", before, field(lootView(), "lastSnapshot"));
		selectTab("Loot");
		com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot shown =
			(com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot) field(lootView(), "lastSnapshot");
		assertEquals("latest state on first show", 2, shown.getAllSources().get(0).getKillCount());
		Object afterShow = field(lootView(), "lastSnapshot");
		selectTab("Session");
		selectTab("Loot");
		assertSame("a clean tab switches without re-rendering", afterShow, field(lootView(), "lastSnapshot"));
	}

	@Test
	public void accountSwitch_refreshesTheVisibleSession_promptly() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> ((javax.swing.Timer) fieldQuiet(panel, "pollTimer")).stop());
		selectTab("Session");
		// Every Current Session render rebuilds its (small) content tree.
		Component before = ((Container) field(sessionView(), "content")).getComponent(0);
		session.ensureAccountLoaded(ACCOUNT + 7);
		flushEdt();
		assertNotSame("re-rendered from the change signal", before, ((Container) field(sessionView(), "content")).getComponent(0));
	}

	@Test
	public void dirtyHiddenLootOfTheOldAccount_neverRendersUnderTheNewAccount() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> ((javax.swing.Timer) fieldQuiet(panel, "pollTimer")).stop());
		selectTab("Session");
		loot("old-account-drop"); // Loot is hidden and now stale with account A's drop
		flushEdt();
		long other = ACCOUNT + 11;
		lootTracker.ensureAccountLoaded(other);
		// Account A's drop arriving late, after the switch, is not taken either.
		loot("old-account-late");
		lootTracker.shutdown(); // drain the new account's (empty) rebuild
		lootTracker.start();
		flushEdt();
		selectTab("Loot");
		com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot shown =
			(com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot) field(lootView(), "lastSnapshot");
		assertTrue("nothing of account A under account B", shown.getAllSources().isEmpty());
	}

	private static Object fieldQuiet(Object target, String name)
	{
		try
		{
			return field(target, name);
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}
}
