package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.events.TelemetryEventListener;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InventoryID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The structured-activity collector only produces normalized, deduplicated
 * evidence: a location event per (activity, policy) change, completions
 * from each activity's own pattern, and rewards for exact declared names.
 */
public class StructuredActivityCollectorTest
{
	private static final long ACCOUNT = 700_900_901L;

	private Path testRoot;
	private EventLedger ledger;
	private final List<Object[]> events = Collections.synchronizedList(new ArrayList<>());
	private StructuredActivityCollector collector;
	private final Map<Integer, List<Item>> containers = new HashMap<>();

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("structured-activity-collector-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		ledger = new EventLedger(new Gson());
		ledger.start();
		ledger.addListener((TelemetryEventListener) (account, type, payload, at) -> events.add(new Object[] {account, type, payload}));
		Client client = (Client) java.lang.reflect.Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				if ("getAccountHash".equals(method.getName()))
				{
					return ACCOUNT;
				}
				if ("getItemContainer".equals(method.getName()) && args != null && args[0] instanceof Integer)
				{
					return container((Integer) args[0]);
				}
				Class<?> rt = method.getReturnType();
				if (rt == boolean.class)
				{
					return false;
				}
				if (rt.isPrimitive() && rt != void.class)
				{
					return rt == long.class ? (Object) 0L : (Object) 0;
				}
				return null;
			});
		collector = new StructuredActivityCollector(client, NAMES, ledger, StructuredActivityRegistry.defaults());
	}

	@After
	public void tearDown() throws Exception
	{
		ledger.shutdown();
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private List<Object> payloads(EventType type)
	{
		List<Object> out = new ArrayList<>();
		synchronized (events)
		{
			for (Object[] e : events)
			{
				if (e[1] == type)
				{
					out.add(e[2]);
				}
			}
		}
		return out;
	}

	@Test
	public void locationEvents_onlyOnActivityOrPolicyChange()
	{
		collector.onRegion(12850); // outside, first observation: silent
		collector.onRegion(12851);
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_STATE).isEmpty());

		collector.onRegion(12076); // Tempoross game: arm
		collector.onRegion(12076); // same tick again: nothing
		collector.onRegion(12588); // same activity, different policy: one event
		collector.onRegion(12588);
		collector.onRegion(12850); // leave: one disarm event
		collector.onRegion(12851); // still outside: nothing

		List<Object> states = payloads(EventType.STRUCTURED_ACTIVITY_STATE);
		assertEquals(3, states.size());
		EventPayloads.StructuredActivityState enter = (EventPayloads.StructuredActivityState) states.get(0);
		assertEquals("tempoross", enter.getActivityKey());
		assertEquals("EXCLUSIVE", enter.getLocationPolicy());
		assertEquals(12076, enter.getRegionId());
		assertEquals("NON_EXCLUSIVE", ((EventPayloads.StructuredActivityState) states.get(1)).getLocationPolicy());
		assertNull(((EventPayloads.StructuredActivityState) states.get(2)).getActivityKey());
	}

	@Test
	public void logout_disarms_andNextLoginReArms()
	{
		collector.onRegion(14484);
		GameStateChanged logout = new GameStateChanged();
		logout.setGameState(GameState.LOGIN_SCREEN);
		collector.onGameStateChanged(logout);
		collector.onRegion(14484); // first tick after login
		List<Object> states = payloads(EventType.STRUCTURED_ACTIVITY_STATE);
		assertEquals(3, states.size());
		assertNull("logout disarms", ((EventPayloads.StructuredActivityState) states.get(1)).getActivityKey());
		assertEquals("login re-arms", "guardians_of_the_rift", ((EventPayloads.StructuredActivityState) states.get(2)).getActivityKey());
	}

	@Test
	public void startingOutsideAfterLogin_saysNothing()
	{
		GameStateChanged hop = new GameStateChanged();
		hop.setGameState(GameState.HOPPING);
		collector.onGameStateChanged(hop);
		collector.onRegion(12850);
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_STATE).isEmpty());
	}

	@Test
	public void chatCompletions_dedupedByRunningCount_countlessOnesEachCount()
	{
		collector.onGameMessage("Amount of rifts you have closed: 41.");
		collector.onGameMessage("Amount of rifts you have closed: 41."); // duplicate delivery
		collector.onGameMessage("Amount of rifts you have closed: 42.");
		collector.onGameMessage("We've awarded you 4 Void Knight Commendation points.");
		collector.onGameMessage("Your Barrows chest count is: 5."); // owned by ActivityKillCountCollector
		collector.onGameMessage("You need to wait for the next game.");
		List<Object> done = payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION);
		assertEquals(3, done.size());
		assertEquals(Integer.valueOf(41), ((EventPayloads.StructuredActivityCompletion) done.get(0)).getCompletionCount());
		assertEquals(Integer.valueOf(42), ((EventPayloads.StructuredActivityCompletion) done.get(1)).getCompletionCount());
		assertEquals("pest_control", ((EventPayloads.StructuredActivityCompletion) done.get(2)).getActivityKey());
	}

	// ------------------------------------------------------------------ reward producers

	private static final java.util.function.IntFunction<String> NAMES = id -> id == 565 ? "Blood rune" : id == 995 ? "Coins" : "Item " + id;

	/** Slots in order; the same item id may occupy several slots (unstacked items). */
	private void setContainer(int containerId, int... idQty)
	{
		List<Item> slots = new ArrayList<>();
		for (int i = 0; i < idQty.length; i += 2)
		{
			slots.add(new Item(idQty[i], idQty[i + 1]));
		}
		containers.put(containerId, slots);
	}

	private ItemContainer container(int containerId)
	{
		List<Item> slots = containers.get(containerId);
		if (slots == null)
		{
			return null;
		}
		List<Item> list = new ArrayList<>(slots);
		list.add(new Item(-1, 0)); // an empty slot is skipped
		Item[] array = list.toArray(new Item[0]);
		return (ItemContainer) java.lang.reflect.Proxy.newProxyInstance(ItemContainer.class.getClassLoader(), new Class<?>[] {ItemContainer.class},
			(proxy, method, args) -> "getItems".equals(method.getName()) ? array : null);
	}

	private List<EventPayloads.StructuredReward> rewards()
	{
		List<EventPayloads.StructuredReward> out = new ArrayList<>();
		for (Object o : payloads(EventType.STRUCTURED_REWARD))
		{
			out.add((EventPayloads.StructuredReward) o);
		}
		return out;
	}

	private void inventoryChanged()
	{
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, container(InventoryID.INV)));
	}

	@Test
	public void barrows_rewardInterface_readsTheExactChestContainer()
	{
		setContainer(141, 4708, 1, 565, 120, 565, 3);
		WidgetLoaded loaded = new WidgetLoaded();
		loaded.setGroupId(155); // gameval InterfaceID.BARROWS_REWARD
		collector.onWidgetLoaded(loaded);
		List<EventPayloads.StructuredReward> r = rewards();
		assertEquals(1, r.size());
		assertEquals("barrows", r.get(0).getActivityKey());
		assertEquals("barrows", r.get(0).getSourceKey());
		assertEquals("Barrows", r.get(0).getSourceName());
		assertEquals(2, r.get(0).getItems().size());
		assertEquals(4708, r.get(0).getItems().get(0).getItemId());
		assertEquals(1, r.get(0).getItems().get(0).getQuantity());
		assertEquals("Blood rune", r.get(0).getItems().get(1).getItemName());
		assertEquals(123, r.get(0).getItems().get(1).getQuantity());
	}

	@Test
	public void otherInterfaces_sharingTheRewardContainer_emitNothing()
	{
		setContainer(141, 995, 5000);
		WidgetLoaded clue = new WidgetLoaded();
		clue.setGroupId(73); // clue scroll reward interface also uses container 141
		collector.onWidgetLoaded(clue);
		WidgetLoaded moons = new WidgetLoaded();
		moons.setGroupId(868); // Perilous Moons: not implemented
		collector.onWidgetLoaded(moons);
		assertTrue(rewards().isEmpty());
	}

	@Test
	public void claimMessage_armsOneBoundedInventoryCorrelation_perSource()
	{
		Object[][] cases = {
			{14484, "You found some loot: 40 x Blood rune", "guardians_of_the_rift", "guardians_of_the_rift", "Guardians of the Rift"},
			{12588, "You found some loot: 40 x Blood rune", "tempoross", "tempoross_reward_pool", "Tempoross Reward Pool"},
			{6461, "<col=ef1020>You found some loot: 40 x Blood rune", "wintertodt", "wintertodt_reward_cart", "Wintertodt Reward Cart"},
		};
		for (Object[] c : cases)
		{
			events.clear();
			collector.resetForAccountSwitch();
			collector.onRegion((Integer) c[0]);
			setContainer(InventoryID.INV, 995, 100, 565, 10);
			collector.onGameMessage((String) c[1]);
			assertTrue(collector.isClaimArmed());
			setContainer(InventoryID.INV, 995, 100, 565, 50); // +40 blood runes
			inventoryChanged();
			assertTrue("one change consumes the claim", !collector.isClaimArmed());
			setContainer(InventoryID.INV, 995, 100, 565, 50, 4151, 1);
			inventoryChanged(); // a later, unrelated inventory change is never a reward
			List<EventPayloads.StructuredReward> r = rewards();
			assertEquals(c[3] + ": exactly one reward", 1, r.size());
			assertEquals(c[2], r.get(0).getActivityKey());
			assertEquals(c[3], r.get(0).getSourceKey());
			assertEquals(c[4], r.get(0).getSourceName());
			assertEquals(1, r.get(0).getItems().size());
			assertEquals(565, r.get(0).getItems().get(0).getItemId());
			assertEquals(40, r.get(0).getItems().get(0).getQuantity());
		}
	}

	@Test
	public void claimMessage_outsideItsRegion_orExpired_emitsNothing()
	{
		collector.onRegion(12076); // Tempoross GAME region, not the reward cove
		setContainer(InventoryID.INV, 995, 1);
		collector.onGameMessage("You found some loot: 3 x Coins");
		assertTrue(!collector.isClaimArmed());

		collector.onRegion(12588);
		collector.onGameMessage("You found some loot: 3 x Coins");
		assertTrue(collector.isClaimArmed());
		for (int i = 0; i < StructuredActivityCollector.CLAIM_WINDOW_TICKS; i++)
		{
			collector.tickClaimWindow();
		}
		assertTrue("an unanswered claim expires", !collector.isClaimArmed());
		setContainer(InventoryID.INV, 995, 4);
		inventoryChanged();
		assertTrue(rewards().isEmpty());
	}

	@Test
	public void unrelatedLoot_noClaim_noReward()
	{
		collector.onRegion(14484);
		setContainer(InventoryID.INV, 995, 1);
		collector.onGameMessage("You find some treasure in the chest!"); // e.g. an unrelated chest
		setContainer(InventoryID.INV, 995, 1000);
		inventoryChanged();
		collector.onRegion(12850);
		collector.onGameMessage("You open the casket. Inside you find some loot."); // caskets / supply crates: no source
		inventoryChanged();
		assertTrue(rewards().isEmpty());
		assertTrue("never SERVER_NPC_LOOT", payloads(EventType.SERVER_NPC_LOOT).isEmpty());
	}

	@Test
	public void logoutDisarmsAPendingClaim()
	{
		collector.onRegion(14484);
		setContainer(InventoryID.INV, 995, 1);
		collector.onGameMessage("You found some loot: 3 x Coins");
		GameStateChanged logout = new GameStateChanged();
		logout.setGameState(GameState.LOGIN_SCREEN);
		collector.onGameStateChanged(logout);
		assertTrue(!collector.isClaimArmed());
	}
}
