package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.model.StorageState;
import com.osrstelemetry.plugin.pestcontrol.PestControlGoalPreferences;
import com.osrstelemetry.plugin.pestcontrol.VoidOwnership;
import com.osrstelemetry.plugin.pestcontrol.VoidPiece;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The plugin's Pest Control planning wiring, end to end with the real
 * collector, preferences and LocalStateStore (no plugin / Guice instance):
 * the collector's balance sink is PestControlGoalPreferences#onBalanceStated,
 * login seeds the balance from the preferences and the bank from Spyglass's
 * own bank.json (VoidOwnership#savedBankItems) -- exactly the calls
 * OsrsTelemetryPlugin makes. Planning state only: no event is ever written.
 */
public class StructuredActivityCollectorPestControlPlanningWiringTest
{
	private static final long ACCOUNT = 9_090_001L;
	private static final String LIVE_BALANCE = "<col=000080>You now have <col=800000>11<col=000080> Void Knight Commendation points! "
		+ "You can speak to a Void Knight to exchange your points for rewards.";

	private Path root;
	private final List<EventType> events = new ArrayList<>();

	@Before
	public void setUp() throws Exception
	{
		root = Files.createTempDirectory("pc-wiring-test");
		TelemetryPaths.init(TestFilepaths.rooted(root));
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(root).deleteRecursively();
	}

	private StructuredActivityCollector collector()
	{
		EventLedger ledger = new EventLedger(new Gson())
		{
			@Override
			public void append(long accountHash, EventType type, Object payload)
			{
				events.add(type);
			}
		};
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getAccountHash":
					return ACCOUNT;
				case "getGameState":
					return GameState.LOGGED_IN;
				case "getTickCount":
				case "getVarbitValue":
					return 0;
				default:
					return defaultValue(m.getReturnType());
			}
		});
		return new StructuredActivityCollector(client, id -> "item" + id, ledger, StructuredActivityRegistry.defaults());
	}

	private static Object defaultValue(Class<?> t)
	{
		if (t == boolean.class)
		{
			return false;
		}
		if (t == long.class)
		{
			return 0L;
		}
		if (t.isPrimitive() && t != void.class)
		{
			return 0;
		}
		return null;
	}

	private static LocalStateStore store()
	{
		LocalStateStore store = new LocalStateStore(new Gson());
		store.start();
		return store;
	}

	@Test
	public void statedBalance_isRemembered_andSeedsTheNextLogin()
	{
		LocalStateStore store = store();
		PestControlGoalPreferences prefs = new PestControlGoalPreferences(store);
		prefs.loadForAccount(ACCOUNT);
		StructuredActivityCollector collector = collector();
		collector.setPestBalanceListener(prefs::onBalanceStated);
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.MESBOX, "", LIVE_BALANCE, "", 0));
		assertEquals(Integer.valueOf(11), prefs.getBalance());
		store.shutdown();

		// Next client start: handleLogin() loads the preferences, then seeds the collector.
		LocalStateStore again = store();
		PestControlGoalPreferences reloaded = new PestControlGoalPreferences(again);
		reloaded.loadForAccount(ACCOUNT);
		StructuredActivityCollector next = collector();
		next.seedPestBalance(reloaded.getBalance());
		assertEquals(Integer.valueOf(11), next.getPestBalance());
		again.shutdown();
		assertTrue("planning state is never telemetry", events.isEmpty());
	}

	@Test
	public void rewardShop_forgetsTheRememberedBalance()
	{
		LocalStateStore store = store();
		PestControlGoalPreferences prefs = new PestControlGoalPreferences(store);
		prefs.loadForAccount(ACCOUNT);
		prefs.recordBalance(500);
		StructuredActivityCollector collector = collector();
		collector.setPestBalanceListener(prefs::onBalanceStated);
		collector.seedPestBalance(prefs.getBalance());
		WidgetLoaded shop = new WidgetLoaded();
		shop.setGroupId(InterfaceID.PEST_REWARDSHOP);
		collector.onWidgetLoaded(shop);
		assertNull(collector.getPestBalance());
		assertNull(prefs.getBalance());
		store.shutdown();
		LocalStateStore again = store();
		PestControlGoalPreferences reloaded = new PestControlGoalPreferences(again);
		reloaded.loadForAccount(ACCOUNT);
		assertNull("unknown survives a restart (never a stale number)", reloaded.getBalance());
		again.shutdown();
	}

	@Test
	public void savedBankJson_seedsVoidOwnership_untilTheBankIsSeen()
	{
		LocalStateStore store = store();
		StorageState bank = new StorageState("bank");
		bank.setItems(Arrays.asList(
			new StorageState.StorageItem(0, ItemID.PEST_VOID_KNIGHT_MACE, "Void knight mace", 1, null),
			new StorageState.StorageItem(1, ItemID.COINS, "Coins", 5000, null)));
		bank.setLastObservedAt("2026-10-01T23:00:00Z");
		assertTrue(store.writeAndWait(TelemetryPaths.stateFile(ACCOUNT, "bank"), bank, 5000L));
		StructuredActivityCollector collector = collector();
		collector.seedBankOwnership(VoidOwnership.savedBankItems(store, ACCOUNT));
		assertTrue(collector.getVoidOwnership().getSnapshot().isBankKnown());
		assertTrue(collector.getVoidOwnership().getSnapshot().owns(VoidPiece.MACE));
		store.shutdown();
	}

	@Test
	public void noSavedBank_leavesTheBankUnknown()
	{
		LocalStateStore store = store();
		StructuredActivityCollector collector = collector();
		assertNull(VoidOwnership.savedBankItems(store, ACCOUNT));
		collector.seedBankOwnership(VoidOwnership.savedBankItems(store, ACCOUNT));
		assertFalse(collector.getVoidOwnership().getSnapshot().isBankKnown());
		store.shutdown();
	}
}
