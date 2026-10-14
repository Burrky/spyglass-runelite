package com.osrstelemetry.plugin.skills.mining;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

/**
 * The Mining companion through its real RuneLite wiring: bounded telemetry
 * (one coalesced update per ~30 s of activity, plus one per deposit), the
 * binding to the SKILLING/mining session the engine created, production that
 * never finds a Mining session being dropped (never misattributed), and the
 * live view / bank view the panel reads.
 */
public class MiningCompanionCollectorTest
{
	private static final long ACCOUNT = 7_777L;
	private static final Gson GSON = new Gson();

	private final List<Object[]> events = new ArrayList<>();
	private int tick = 100;
	private long now = 1_800_000_000_000L;
	private int xp = 500_000;
	private Session current;
	private GameState state = GameState.LOGGED_IN;
	private final Map<Integer, Long> inv = new LinkedHashMap<>();
	private final Map<Integer, Long> bank = new LinkedHashMap<>();
	private MiningCompanionCollector collector;

	@Before
	public void setUp()
	{
		EventLedger ledger = new EventLedger(GSON)
		{
			@Override
			public void append(long accountHash, EventType type, Object payload)
			{
				events.add(new Object[]{type, payload, accountHash});
			}
		};
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getAccountHash":
					return ACCOUNT;
				case "getTickCount":
					return tick;
				case "getGameState":
					return state;
				case "getSkillExperience":
					return 0;
				default:
					return null;
			}
		});
		collector = new MiningCompanionCollector(client, ledger,
			() -> current == null ? SessionSnapshot.absent() : SessionSnapshot.capture(current), new LocalStateStore(GSON));
		collector.setClock(() -> now);
		collector.startUp();
		collector.onStatChanged(new StatChanged(Skill.MINING, xp, 80, 80));
		pushInventory();
	}

	private static Session session(String id, String type, String key)
	{
		return GSON.fromJson(("{'sessionId':'" + id + "','activityIdentity':{'activityType':'" + type + "','activityKey':'" + key
			+ "','displayName':'X'},'state':'ACTIVE','startedAt':'2026-10-12T16:00:00Z','lastActiveAt':'2026-10-12T16:00:00Z',"
			+ "'accumulatedActiveDurationMillis':600000,'aggregates':{'xpGainedBySkill':{},'lootDrops':[]}}").replace('\'', '"'), Session.class);
	}

	private static ItemContainer container(Map<Integer, Long> items)
	{
		List<Item> list = new ArrayList<>();
		items.forEach((id, q) -> list.add(new Item(id, (int) (long) q)));
		Item[] arr = list.toArray(new Item[0]);
		return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(), new Class<?>[]{ItemContainer.class},
			(p, m, a) -> "getItems".equals(m.getName()) ? arr : null);
	}

	private void pushInventory()
	{
		inv.values().removeIf(v -> v <= 0);
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, container(inv)));
	}

	private void pushBank()
	{
		bank.values().removeIf(v -> v <= 0);
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK, container(bank)));
	}

	private void tick()
	{
		tick++;
		now += 600;
		collector.onGameTick(new GameTick());
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick();
		}
	}

	private void mineIron()
	{
		xp += 35;
		collector.onStatChanged(new StatChanged(Skill.MINING, xp, 80, 80));
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.SPAM, "", "You manage to mine some iron.", null, 0));
		inv.merge(ItemID.IRON_ORE, 1L, Long::sum);
		pushInventory();
		ticks(3);
	}

	private List<EventPayloads.SkillProduction> updates()
	{
		List<EventPayloads.SkillProduction> out = new ArrayList<>();
		for (Object[] e : events)
		{
			if (e[0] == EventType.SKILL_PRODUCTION)
			{
				out.add((EventPayloads.SkillProduction) e[1]);
			}
		}
		return out;
	}

	private static long qty(List<EventPayloads.ProducedItem> items, int id)
	{
		long q = 0;
		for (EventPayloads.ProducedItem i : items)
		{
			if (i.getItemId() == id)
			{
				q += i.getQuantity();
			}
		}
		return q;
	}

	@Test
	public void panelRefresh_onlyWhileItsOwnSkillSessionIsCurrent()
	{
		int[] refreshes = {0};
		collector.setProgressListener(() -> refreshes[0]++);
		current = session("s-slayer", "SLAYER", "nechryael");
		inv.put(ItemID.SHARK, 5L);
		pushInventory();
		tick();
		inv.put(ItemID.LOBSTER, 3L);
		pushInventory();
		tick();
		assertEquals("an idle Mining companion never repaints a Slayer session", 0, refreshes[0]);

		current = session("s-mining", "SKILLING", "mining");
		mineIron();
		assertTrue("its own session: live changes reach the panel", refreshes[0] > 0);

		int before = refreshes[0];
		GameStateChanged logout = new GameStateChanged();
		logout.setGameState(GameState.LOGIN_SCREEN);
		collector.onGameStateChanged(logout);
		assertEquals("a forced publish (logout / switch) always signals", before + 1, refreshes[0]);
	}

	@Test
	public void productionWaitsForTheMiningSession_thenOneBoundedUpdate()
	{
		for (int i = 0; i < 10; i++)
		{
			mineIron();
		}
		ticks(30);
		assertTrue("no Mining session yet: nothing sent", updates().isEmpty());
		SkillCompanionView v = collector.getView();
		assertEquals("live: pending", Long.valueOf(10L), v.getPendingProduced().get(ItemID.IRON_ORE));
		assertNull(v.getBoundSessionId());

		current = session("s-mining", "SKILLING", "mining");
		ticks(50);
		assertEquals(1, updates().size());
		EventPayloads.SkillProduction u = updates().get(0);
		assertEquals("MINING", u.getSkill());
		assertEquals("iron", u.getMethod());
		assertEquals(10L, qty(u.getProduced(), ItemID.IRON_ORE));
		assertTrue(u.getMethodMillis().get("iron") > 0);
		assertEquals(ACCOUNT, (long) (Long) events.get(0)[2]);
		v = collector.getView();
		assertEquals("s-mining", v.getBoundSessionId());
		assertEquals(Long.valueOf(10L), v.getFlushedProduced().get(ItemID.IRON_ORE));
		assertTrue(v.getPendingProduced().isEmpty());
	}

	@Test
	public void telemetryIsBounded_neverPerItem()
	{
		current = session("s-mining", "SKILLING", "mining");
		for (int i = 0; i < 16; i++)
		{
			mineIron();
		}
		ticks(5);
		assertTrue("16 ores over ~50 ticks: at most two updates, never sixteen", updates().size() <= 2);
		ticks(60);
		long sent = 0;
		for (EventPayloads.SkillProduction u : updates())
		{
			sent += qty(u.getProduced(), ItemID.IRON_ORE);
		}
		assertEquals(16L, sent);
	}

	@Test
	public void aDeposit_isSentPromptly_withBanked()
	{
		current = session("s-mining", "SKILLING", "mining");
		for (int i = 0; i < 5; i++)
		{
			mineIron();
		}
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.BANKMAIN);
		collector.onWidgetLoaded(open);
		pushBank();
		tick();
		inv.put(ItemID.IRON_ORE, 0L);
		bank.put(ItemID.IRON_ORE, 5L);
		pushInventory();
		pushBank();
		ticks(4);
		assertTrue(updates().size() >= 1);
		long banked = 0;
		for (EventPayloads.SkillProduction u : updates())
		{
			banked += qty(u.getBanked(), ItemID.IRON_ORE);
		}
		assertEquals(5L, banked);
		assertEquals(Long.valueOf(5L), collector.bankQuantity(ItemID.IRON_ORE));
		assertEquals(Long.valueOf(0L), collector.bankQuantity(ItemID.AMETHYST));
		WidgetClosed close = new WidgetClosed(InterfaceID.BANKMAIN, 0, false);
		collector.onWidgetClosed(close);
		ticks(50);
		assertEquals("banking sample for the stockpile ETA", 5L, collector.getView().getBanking().get(ItemID.IRON_ORE).getQuantity());
	}

	@Test
	public void productionNoMiningSessionClaims_isDropped_neverMisattributed()
	{
		current = session("s-gotr", "STRUCTURED_ACTIVITY", "guardians_of_the_rift");
		for (int i = 0; i < 3; i++)
		{
			mineIron();
		}
		ticks(50 * 8);
		current = session("s-mining-later", "SKILLING", "mining");
		ticks(60);
		long sent = 0;
		for (EventPayloads.SkillProduction u : updates())
		{
			sent += qty(u.getProduced(), ItemID.IRON_ORE);
		}
		assertEquals("never sent to another activity, never carried into a later session", 0L, sent);
	}

	@Test
	public void logout_sendsWhatIsPending_whileTheAccountIsStillKnown()
	{
		current = session("s-mining", "SKILLING", "mining");
		mineIron();
		mineIron();
		GameStateChanged out = new GameStateChanged();
		out.setGameState(GameState.LOGIN_SCREEN);
		state = GameState.LOGIN_SCREEN;
		collector.onGameStateChanged(out);
		assertEquals(1, updates().size());
		assertEquals(2L, qty(updates().get(0).getProduced(), ItemID.IRON_ORE));
	}

	@Test
	public void aNewMiningSession_rebinds_andNeverBanksTheOldSessionsOre()
	{
		current = session("s-1", "SKILLING", "mining");
		for (int i = 0; i < 4; i++)
		{
			mineIron();
		}
		ticks(50);
		current = session("s-2", "SKILLING", "mining");
		mineIron();
		ticks(50);
		assertEquals("s-2", collector.getView().getBoundSessionId());
		assertEquals(Long.valueOf(1L), collector.getView().getFlushedProduced().get(ItemID.IRON_ORE));
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.BANKMAIN);
		collector.onWidgetLoaded(open);
		pushBank();
		tick();
		inv.put(ItemID.IRON_ORE, 0L);
		bank.put(ItemID.IRON_ORE, 5L);
		pushInventory();
		pushBank();
		ticks(5);
		long banked = 0;
		for (EventPayloads.SkillProduction u : updates())
		{
			banked += qty(u.getBanked(), ItemID.IRON_ORE);
		}
		assertEquals("only s-2's own ore", 1L, banked);
	}

	@Test
	public void accountSwitch_startsOver()
	{
		current = session("s-mining", "SKILLING", "mining");
		mineIron();
		ticks(50);
		collector.resetForAccountSwitch();
		SkillCompanionView v = collector.getView();
		assertNull(v.getBoundSessionId());
		assertTrue(v.getFlushedProduced().isEmpty());
		assertNull(v.getMethodKey());
		assertNull(collector.bankQuantity(ItemID.IRON_ORE));
	}

	@Test
	public void currentXp_isTheAbsoluteMiningXp()
	{
		assertEquals(Integer.valueOf(500_000), collector.currentXp());
		mineIron();
		assertEquals(Integer.valueOf(500_035), collector.currentXp());
	}
}
