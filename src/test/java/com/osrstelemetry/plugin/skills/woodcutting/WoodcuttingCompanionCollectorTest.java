package com.osrstelemetry.plugin.skills.woodcutting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import org.junit.Before;
import org.junit.Test;

/**
 * The Woodcutting companion through the SAME shared collector as Mining /
 * Fishing / Agility: real RuneLite events in (Woodcutting and Firemaking
 * StatChanged, chat, inventory, worn equipment, menu clicks, Forestry NPCs),
 * one bounded SKILL_PRODUCTION out -- now carrying the auto-burned part of
 * production and Forestry occurrences -- and context evidence to the session
 * engine for infernal Firemaking and Forestry other-skill XP.
 */
public class WoodcuttingCompanionCollectorTest
{
	private static final long ACCOUNT = 7_777L;
	private static final Gson GSON = new Gson();

	private final List<Object[]> events = new ArrayList<>();
	private final List<String> contextNotes = new ArrayList<>();
	private int tick = 100;
	private long now = 1_800_000_000_000L;
	private int wcXp = 13_000_000;
	private int fmXp = 2_000_000;
	private int hunterXp = 500_000;
	private Session current;
	private final Map<Integer, Long> inv = new LinkedHashMap<>();
	private final Map<Integer, Long> worn = new LinkedHashMap<>();
	private WoodcuttingCompanionCollector wc;

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
					return GameState.LOGGED_IN;
				case "getSkillExperience":
					return 0;
				case "getItemContainer":
					int id = (Integer) a[0];
					return id == InventoryID.INV ? container(inv) : id == InventoryID.WORN ? container(worn) : null;
				default:
					return null;
			}
		});
		wc = new WoodcuttingCompanionCollector(client, ledger,
			() -> current == null ? SessionSnapshot.absent() : SessionSnapshot.capture(current), new LocalStateStore(GSON));
		wc.setClock(() -> now);
		wc.setContextEvidenceSink((owner, skill, at) -> contextNotes.add(owner + "|" + skill));
		wc.startUp();
		wc.onStatChanged(new StatChanged(Skill.WOODCUTTING, wcXp, 90, 90));
		wc.onStatChanged(new StatChanged(Skill.FIREMAKING, fmXp, 80, 80));
		wc.onStatChanged(new StatChanged(Skill.HUNTER, hunterXp, 70, 70));
		pushInventory();
	}

	private static Session session(String id, String type, String key)
	{
		return GSON.fromJson(("{'sessionId':'" + id + "','activityIdentity':{'activityType':'" + type + "','activityKey':'" + key
			+ "','displayName':'X'},'state':'ACTIVE','startedAt':'2026-10-13T16:00:00Z','lastActiveAt':'2026-10-13T16:00:00Z',"
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
		wc.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, container(inv)));
	}

	private void tick()
	{
		tick++;
		now += 600;
		wc.onGameTick(new GameTick());
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick();
		}
	}

	private static MenuOptionClicked click(String option, String target, int id, MenuAction action, NPC npc)
	{
		MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(), new Class<?>[]{MenuEntry.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getOption":
					return option;
				case "getTarget":
					return target;
				case "getType":
					return action;
				case "getIdentifier":
					return id;
				case "getParam0":
					return 51;
				case "getParam1":
					return 52;
				case "getNpc":
					return npc;
				default:
					return null;
			}
		});
		return new MenuOptionClicked(entry);
	}

	private static NPC npc(int id)
	{
		return (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
			(p, m, a) -> "getId".equals(m.getName()) ? id : null);
	}

	private void cutYew()
	{
		wcXp += 175;
		wc.onStatChanged(new StatChanged(Skill.WOODCUTTING, wcXp, 90, 90));
		wc.onChatMessage(new ChatMessage(null, ChatMessageType.SPAM, "", "You get some yew logs.", null, 0));
		inv.merge(ItemID.YEW_LOGS, 1L, Long::sum);
		pushInventory();
		ticks(4);
	}

	private void burnYew()
	{
		wcXp += 175;
		fmXp += 175;
		wc.onStatChanged(new StatChanged(Skill.WOODCUTTING, wcXp, 90, 90));
		wc.onStatChanged(new StatChanged(Skill.FIREMAKING, fmXp, 80, 80));
		ticks(4);
	}

	private List<EventPayloads.SkillProduction> updates()
	{
		List<EventPayloads.SkillProduction> out = new ArrayList<>();
		for (Object[] e : events)
		{
			if (e[0] == EventType.SKILL_PRODUCTION && "WOODCUTTING".equals(((EventPayloads.SkillProduction) e[1]).getSkill()))
			{
				out.add((EventPayloads.SkillProduction) e[1]);
			}
		}
		return out;
	}

	private static long qty(List<EventPayloads.ProducedItem> items, int id)
	{
		long q = 0;
		if (items != null)
		{
			for (EventPayloads.ProducedItem i : items)
			{
				if (i.getItemId() == id)
				{
					q += i.getQuantity();
				}
			}
		}
		return q;
	}

	/**
	 * LIVE STARTUP GUARD: the real collector on a real RuneLite EventBus. Registration
	 * must succeed (RuneLite stops the plugin otherwise), and the Woodcutting-specific
	 * tick / scene-loading state must reach the shared handlers through the inherited
	 * subscriptions alone: same totals as the direct-call test, nothing thrown.
	 */
	@Test
	public void realRuneLiteEventBus_registersAndDeliversTheInfernalChain()
	{
		net.runelite.client.eventbus.EventBus bus = new net.runelite.client.eventbus.EventBus(e ->
		{
			throw new AssertionError("subscriber threw", e);
		});
		bus.register(wc);
		worn.put(ItemID.INFERNAL_AXE, 1L);
		current = session("s-wc", "SKILLING", "woodcutting");
		GameStateChanged loading = new GameStateChanged();
		loading.setGameState(GameState.LOADING);
		bus.post(loading);
		bus.post(click("Chop down", "<col=ffff>Yew tree", 10822, MenuAction.GAME_OBJECT_FIRST_OPTION, null));
		for (int i = 0; i < 9; i++)
		{
			wcXp += 175;
			bus.post(new StatChanged(Skill.WOODCUTTING, wcXp, 90, 90));
			if (i % 3 == 2)
			{
				fmXp += 175;
				bus.post(new StatChanged(Skill.FIREMAKING, fmXp, 80, 80));
			}
			else
			{
				bus.post(new ChatMessage(null, ChatMessageType.SPAM, "", "You get some yew logs.", null, 0));
				inv.merge(ItemID.YEW_LOGS, 1L, Long::sum);
				bus.post(new ItemContainerChanged(InventoryID.INV, container(inv)));
			}
			busTicks(bus, 4);
		}
		busTicks(bus, 60);
		long produced = 0;
		long burned = 0;
		for (EventPayloads.SkillProduction p : updates())
		{
			produced += qty(p.getProduced(), ItemID.YEW_LOGS);
			burned += qty(p.getAutoTransformed(), ItemID.YEW_LOGS);
		}
		assertEquals(9L, produced);
		assertEquals("the burning axe was seen through the inherited GameTick subscription", 3L, burned);
		bus.unregister(wc);
	}

	private void busTicks(net.runelite.client.eventbus.EventBus bus, int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick++;
			now += 600;
			bus.post(new GameTick());
		}
	}

	@Test
	public void infernalChopping_boundedTelemetry_withTheAutoBurnedPart()
	{
		worn.put(ItemID.INFERNAL_AXE, 1L);
		current = session("s-wc", "SKILLING", "woodcutting");
		wc.onMenuOptionClicked(click("Chop down", "<col=ffff>Yew tree", 10822, MenuAction.GAME_OBJECT_FIRST_OPTION, null));
		for (int i = 0; i < 9; i++)
		{
			if (i % 3 == 2)
			{
				burnYew();
			}
			else
			{
				cutYew();
			}
		}
		ticks(60);
		List<EventPayloads.SkillProduction> u = updates();
		assertTrue("bounded, never one per log: " + u.size(), u.size() >= 1 && u.size() <= 3);
		long produced = 0;
		long burned = 0;
		for (EventPayloads.SkillProduction p : u)
		{
			produced += qty(p.getProduced(), ItemID.YEW_LOGS);
			burned += qty(p.getAutoTransformed(), ItemID.YEW_LOGS);
			assertNull("nothing about Forestry happened", p.getOccurrences());
		}
		assertEquals(9L, produced);
		assertEquals(3L, burned);
		assertEquals("yew", u.get(0).getMethod());
		assertTrue("Firemaking XP was reported as Woodcutting's", contextNotes.contains("woodcutting|FIREMAKING"));
		SkillCompanionView v = wc.getView();
		assertEquals("s-wc", v.getBoundSessionId());
		assertEquals(Long.valueOf(3L), v.getFlushedAutoTransformed().get(ItemID.YEW_LOGS));
		assertNotNull("the clicked yew is the current tree", v.getFocus());
		assertEquals(9L, v.getFocus().getProduced());
	}

	@Test
	public void noInfernalAxe_firemakingXpIsNeverWoodcuttings()
	{
		current = session("s-wc", "SKILLING", "woodcutting");
		cutYew();
		burnYew();
		ticks(60);
		assertTrue(contextNotes.isEmpty());
		for (EventPayloads.SkillProduction p : updates())
		{
			assertNull(p.getAutoTransformed());
		}
	}

	@Test
	public void forestryEvent_isContext_countedOnceInTelemetry()
	{
		current = session("s-wc", "SKILLING", "woodcutting");
		cutYew();
		wc.onNpcSpawned(new NpcSpawned(npc(NpcID.GATHERING_EVENT_POACHERS_FOX_OUTDOORS)));
		ticks(2);
		assertEquals("Poachers", wc.getView().getEvent().getName());
		assertTrue("Hunter XP during Poachers is Woodcutting's", contextNotes.contains("woodcutting|HUNTER"));
		hunterXp += 300;
		wc.onStatChanged(new StatChanged(Skill.HUNTER, hunterXp, 70, 70));
		wc.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "",
			"You've been awarded <col=ff0000>9 Anima-infused bark</col>.", null, 0));
		inv.put(ItemID.FORESTRY_CURRENCY, 9L);
		pushInventory();
		ticks(4);
		assertEquals("1", wc.getView().getEvent().getFields().get(1).getValue());
		wc.onNpcDespawned(new NpcDespawned(npc(NpcID.GATHERING_EVENT_POACHERS_FOX_OUTDOORS)));
		ticks(70);
		assertNull("ended: no FORESTRY EVENT section", wc.getView().getEvent());
		long occurrences = 0;
		long bark = 0;
		for (EventPayloads.SkillProduction p : updates())
		{
			if (p.getOccurrences() != null)
			{
				occurrences += p.getOccurrences().getOrDefault("forestry:poachers", 0L);
			}
			bark += qty(p.getProduced(), ItemID.FORESTRY_CURRENCY);
		}
		assertEquals(1L, occurrences);
		assertEquals(9L, bark);
	}

	@Test
	public void productionNoWoodcuttingSessionClaims_isDropped()
	{
		current = session("s-gotr", "STRUCTURED_ACTIVITY", "guardians_of_the_rift");
		cutYew();
		ticks(50 * 8);
		current = session("s-wc-later", "SKILLING", "woodcutting");
		ticks(60);
		long sent = 0;
		for (EventPayloads.SkillProduction u : updates())
		{
			sent += qty(u.getProduced(), ItemID.YEW_LOGS);
		}
		assertEquals("never parked on a later, unrelated session", 0L, sent);
	}

	@Test
	public void leprechaunClick_andBasketClicks_reachTheTracker()
	{
		current = session("s-wc", "SKILLING", "woodcutting");
		cutYew();
		cutYew();
		ticks(60);
		wc.onNpcSpawned(new NpcSpawned(npc(NpcID.GATHERING_EVENT_WOODCUTTING_LEPRECHAUN)));
		wc.onMenuOptionClicked(click("Talk-to", "Leprechaun", 0, MenuAction.NPC_FIRST_OPTION,
			npc(NpcID.GATHERING_EVENT_WOODCUTTING_LEPRECHAUN)));
		ticks(2);
		inv.put(ItemID.YEW_LOGS, 0L);
		pushInventory();
		ticks(60);
		long banked = 0;
		for (EventPayloads.SkillProduction u : updates())
		{
			banked += qty(u.getBanked(), ItemID.YEW_LOGS);
		}
		assertEquals(2L, banked);
	}

	@Test
	public void choppingTreeClick_isAHintOnly()
	{
		wc.onMenuOptionClicked(click("Chop down", "Magic tree", 10834, MenuAction.GAME_OBJECT_FIRST_OPTION, null));
		ticks(5);
		assertNull("a click never names the tree by itself", wc.getView().getMethodKey());
		assertTrue(WoodcuttingCompanionCollector.isChopOption("chop down"));
		assertTrue(WoodcuttingCompanionCollector.isChopOption("cut"));
	}
}
