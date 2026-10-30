package com.osrstelemetry.plugin.skills.smithing;

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
import com.osrstelemetry.plugin.skills.SkillCompanionCollector;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;

/**
 * The Smithing companion through the SAME shared collector, on a REAL
 * RuneLite EventBus (the live-startup lesson: every subscriber must register
 * and every event must arrive exactly once): Smithing / Magic StatChanged,
 * inventory, Blast Furnace VarbitChanged (with the one baseline read per
 * login), coal bag menu clicks and chat; one bounded SKILL_PRODUCTION out --
 * exact outputs, exact consumed inputs by name, per-recipe occurrences --
 * bound only to SKILLING/smithing. Production that no Smithing session
 * claims (Giants' Foundry's structured session) is dropped, never leaked.
 */
public class SmithingCompanionCollectorTest
{
	private static final long ACCOUNT = 7_070L;
	private static final Gson GSON = new Gson();

	private final List<Object[]> events = new ArrayList<>();
	private int tick = 100;
	private long now = 1_900_000_000_000L;
	private int smithXp = 5_000_000;
	private int magicXp = 3_000_000;
	private Session current;
	private final Map<Integer, Long> inv = new LinkedHashMap<>();
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private SmithingCompanionCollector sc;
	private EventBus bus;

	@Before
	public void setUp()
	{
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
				case "getRealSkillLevel":
					return 0;
				case "getVarbitValue":
					return varbits.getOrDefault((Integer) a[0], 0);
				case "getVarpValue":
					return 0;
				case "getItemContainer":
					return (Integer) a[0] == InventoryID.INV ? container(inv) : null;
				default:
					return null;
			}
		});
		sc = new SmithingCompanionCollector(client, new EventLedger(GSON)
		{
			@Override
			public void append(long accountHash, EventType type, Object payload)
			{
				events.add(new Object[]{type, payload, accountHash});
			}
		}, () -> current == null ? SessionSnapshot.absent() : SessionSnapshot.capture(current), new LocalStateStore(GSON));
		sc.setClock(() -> now);
		sc.startUp();
		bus = new EventBus(e ->
		{
			throw new AssertionError("subscriber threw", e);
		});
		bus.register(sc);
		bus.post(new StatChanged(Skill.SMITHING, smithXp, 90, 90));
		bus.post(new StatChanged(Skill.MAGIC, magicXp, 80, 80));
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

	private void change(Object... items)
	{
		for (int i = 0; i < items.length; i += 2)
		{
			inv.merge((Integer) items[i], ((Number) items[i + 1]).longValue(), Long::sum);
		}
		inv.values().removeIf(v -> v <= 0);
		bus.post(new ItemContainerChanged(InventoryID.INV, container(inv)));
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick++;
			now += 600;
			bus.post(new GameTick());
		}
	}

	private void smithXp(int xp)
	{
		smithXp += xp;
		bus.post(new StatChanged(Skill.SMITHING, smithXp, 90, 90));
	}

	private void varbit(int varbit, int value)
	{
		varbits.put(varbit, value);
		VarbitChanged e = new VarbitChanged();
		e.setVarbitId(varbit);
		e.setVarpId(-1);
		e.setValue(value);
		bus.post(e);
	}

	private static MenuOptionClicked click(String option, String target)
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
					return MenuAction.CC_OP;
				default:
					return null;
			}
		});
		return new MenuOptionClicked(entry);
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

	private long produced(int id)
	{
		long n = 0;
		for (EventPayloads.SkillProduction u : updates())
		{
			n += qty(u.getProduced(), id);
		}
		return n;
	}

	private long consumed(int id)
	{
		long n = 0;
		for (EventPayloads.SkillProduction u : updates())
		{
			n += qty(u.getConsumed(), id);
		}
		return n;
	}

	private long occurrence(String key)
	{
		long n = 0;
		for (EventPayloads.SkillProduction u : updates())
		{
			if (u.getOccurrences() != null)
			{
				n += u.getOccurrences().getOrDefault(key, 0L);
			}
		}
		return n;
	}

	@Test
	public void realEventBus_smelting_oneBoundedUpdate_exactOutputsAndNamedInputs()
	{
		current = session("s-sm", "SKILLING", "smithing");
		change(ItemID.IRON_ORE, 27, ItemID.COAL, 54);
		ticks(2);
		for (int i = 0; i < 10; i++)
		{
			change(ItemID.IRON_ORE, -1, ItemID.COAL, -2, ItemID.STEEL_BAR, 1);
			smithXp(17);
			ticks(4);
		}
		ticks(60);
		List<EventPayloads.SkillProduction> u = updates();
		assertTrue("bounded, never one per bar: " + u.size(), u.size() >= 1 && u.size() <= 3);
		assertEquals("SMITHING", u.get(0).getSkill());
		assertEquals(10L, produced(ItemID.STEEL_BAR));
		assertEquals(10L, consumed(ItemID.IRON_ORE));
		assertEquals(20L, consumed(ItemID.COAL));
		assertEquals(10L, occurrence("made:smelt_steel"));
		assertEquals("smelt_steel", sc.getView().getMethodKey());
	}

	@Test
	public void realEventBus_superheat_magicXpThroughTheSharedHook()
	{
		current = session("s-sm", "SKILLING", "smithing");
		change(ItemID.MITHRIL_ORE, 10, ItemID.COAL, 40, ItemID.NATURERUNE, 10);
		ticks(2);
		for (int i = 0; i < 3; i++)
		{
			change(ItemID.MITHRIL_ORE, -1, ItemID.COAL, -4, ItemID.NATURERUNE, -1, ItemID.MITHRIL_BAR, 1);
			smithXp(30);
			magicXp += 53;
			bus.post(new StatChanged(Skill.MAGIC, magicXp, 80, 80));
			ticks(4);
		}
		ticks(60);
		assertEquals(3L, produced(ItemID.MITHRIL_BAR));
		assertEquals(3L, occurrence("made:superheat_mithril"));
		assertEquals(0L, occurrence("made:smelt_mithril"));
		assertEquals("runes stay Magic's", 0L, consumed(ItemID.NATURERUNE));
	}

	@Test
	public void realEventBus_blastFurnace_baselineThenVarbits_onlyCollectionProduces()
	{
		current = session("s-sm", "SKILLING", "smithing");
		varbits.put(VarbitID.BLAST_FURNACE_STEEL_BARS, 3);
		ticks(2);
		// Belt.
		change(ItemID.IRON_ORE, 27);
		ticks(1);
		change(ItemID.IRON_ORE, -27);
		varbit(VarbitID.BLAST_FURNACE_IRON_ORE, 27);
		varbit(VarbitID.BLAST_FURNACE_COAL, 27);
		ticks(4);
		// Smelt.
		varbit(VarbitID.BLAST_FURNACE_IRON_ORE, 0);
		varbit(VarbitID.BLAST_FURNACE_COAL, 0);
		varbit(VarbitID.BLAST_FURNACE_STEEL_BARS, 30);
		smithXp(472);
		ticks(4);
		ticks(60);
		assertEquals("belt + smelt: nothing produced yet", 0L, produced(ItemID.STEEL_BAR));
		// Collect all 30 (3 were in the dispenser before Spyglass saw them: no inputs for those).
		change(ItemID.STEEL_BAR, 30);
		varbit(VarbitID.BLAST_FURNACE_STEEL_BARS, 0);
		ticks(64);
		assertEquals(30L, produced(ItemID.STEEL_BAR));
		assertEquals(27L, consumed(ItemID.IRON_ORE));
		assertEquals(27L, consumed(ItemID.COAL));
		assertEquals(30L, occurrence("made:bf_steel"));
	}

	@Test
	public void realEventBus_coalBag_menuAndChat_storageMovementOnly()
	{
		current = session("s-sm", "SKILLING", "smithing");
		change(ItemID.COAL_BAG, 1, ItemID.COAL, 27);
		ticks(2);
		bus.post(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "The coal bag contains 0 pieces of coal.", null, 0));
		ticks(3);
		assertEquals(Long.valueOf(0L), sc.smithing().getCoalBag());
		bus.post(click("Fill", "<col=ff9040>Coal bag"));
		change(ItemID.COAL, -27);
		ticks(64);
		assertEquals(Long.valueOf(27L), sc.smithing().getCoalBag());
		assertEquals(0L, consumed(ItemID.COAL));
		assertTrue("no production from moving coal", updates().isEmpty() || produced(ItemID.COAL) == 0L);
	}

	@Test
	public void noSmithingSession_giantsFoundry_productionIsDropped_neverLeaked()
	{
		current = session("s-gf", "STRUCTURED_ACTIVITY", "giants_foundry");
		change(ItemID.MITHRIL_BAR, 28, ItemID.HAMMER, 1);
		ticks(2);
		change(ItemID.MITHRIL_BAR, -5, ItemID.MITHRIL_PLATEBODY, 1);
		smithXp(250);
		ticks(10);
		assertNotNull("pending, waiting for a Smithing session", sc.getView().getPendingProduced().get(ItemID.MITHRIL_PLATEBODY));
		long ticksPast = SkillCompanionCollector.PENDING_LIMIT_MILLIS / 600 + 120;
		ticks((int) ticksPast);
		assertTrue("no Smithing session ever claimed it: dropped", updates().isEmpty());
		assertNull(sc.getView().getPendingProduced().get(ItemID.MITHRIL_PLATEBODY));
		// Normal Smithing afterwards resumes cleanly.
		current = session("s-sm", "SKILLING", "smithing");
		change(ItemID.MITHRIL_BAR, -5, ItemID.MITHRIL_PLATEBODY, 1);
		smithXp(250);
		ticks(64);
		assertEquals(1L, produced(ItemID.MITHRIL_PLATEBODY));
		assertEquals(5L, consumed(ItemID.MITHRIL_BAR));
	}
}
