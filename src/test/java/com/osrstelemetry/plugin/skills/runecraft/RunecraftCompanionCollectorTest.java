package com.osrstelemetry.plugin.skills.runecraft;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;

/**
 * The Runecraft companion through the SAME shared collector: real RuneLite
 * events in (Runecraft StatChanged, inventory, pouch varbits / varps, worn
 * equipment), one bounded SKILL_PRODUCTION out with produced runes and
 * CONSUMED inputs by exact name, bound only to SKILLING/runecraft (never
 * GotR), and the live CURRENT TRIP built from native pouch state.
 */
public class RunecraftCompanionCollectorTest
{
	private static final long ACCOUNT = 5_151L;
	private static final Gson GSON = new Gson();

	private final List<Object[]> events = new ArrayList<>();
	private int tick = 100;
	private long now = 1_800_000_000_000L;
	private int rcXp = 8_000_000;
	private Session current;
	private final Map<Integer, Long> inv = new LinkedHashMap<>();
	private final Map<Integer, Long> worn = new LinkedHashMap<>();
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private final Map<Integer, Integer> varps = new HashMap<>();
	private RunecraftCompanionCollector rc;

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
		varbits.put(VarbitID.GIANT_ESSENCE_POUCH, 12);
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
					return m.getName().equals("getRealSkillLevel") ? 91 : 0;
				case "getVarbitValue":
					return varbits.getOrDefault((Integer) a[0], 0);
				case "getVarpValue":
					return varps.getOrDefault((Integer) a[0], 0);
				case "getItemContainer":
					int id = (Integer) a[0];
					return id == InventoryID.INV ? container(inv) : id == InventoryID.WORN ? container(worn) : null;
				default:
					return null;
			}
		});
		rc = new RunecraftCompanionCollector(client, ledger,
			() -> current == null ? SessionSnapshot.absent() : SessionSnapshot.capture(current), new LocalStateStore(GSON));
		rc.setClock(() -> now);
		rc.startUp();
		rc.onStatChanged(new StatChanged(Skill.RUNECRAFT, rcXp, 91, 91));
		inv.put(ItemID.RCU_POUCH_GIANT, 1L);
		inv.put(ItemID.BLANKRUNE_HIGH, 26L);
		push();
		ticks(2);
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

	private void push()
	{
		inv.values().removeIf(v -> v <= 0);
		rc.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, container(inv)));
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick++;
			now += 600;
			rc.onRunecraftTick(new GameTick());
			rc.onGameTick(new GameTick());
		}
	}

	private void craftNatures(int essence)
	{
		rcXp += essence * 9;
		rc.onStatChanged(new StatChanged(Skill.RUNECRAFT, rcXp, 91, 91));
		inv.merge(ItemID.BLANKRUNE_HIGH, -(long) essence, Long::sum);
		inv.merge(ItemID.NATURERUNE, essence * 2L, Long::sum);
		push();
		ticks(4);
	}

	private List<EventPayloads.SkillProduction> updates()
	{
		List<EventPayloads.SkillProduction> out = new ArrayList<>();
		for (Object[] e : events)
		{
			if (e[0] == EventType.SKILL_PRODUCTION && "RUNECRAFT".equals(((EventPayloads.SkillProduction) e[1]).getSkill()))
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

	private static VarbitChanged varbit(int id, int value)
	{
		VarbitChanged e = new VarbitChanged();
		e.setVarbitId(id);
		e.setVarpId(-1);
		e.setValue(value);
		return e;
	}

	private static VarbitChanged varp(int id, int value)
	{
		VarbitChanged e = new VarbitChanged();
		e.setVarbitId(-1);
		e.setVarpId(id);
		e.setValue(value);
		return e;
	}

	@Test
	public void crafts_boundedTelemetry_runesAndConsumedEssence()
	{
		current = session("s-rc", "SKILLING", "runecraft");
		craftNatures(26);
		inv.put(ItemID.BLANKRUNE_HIGH, 12L);
		rc.onVarbitChanged(varbit(VarbitID.GIANT_ESSENCE_POUCH, 0));
		push();
		ticks(2);
		craftNatures(12);
		ticks(60);
		List<EventPayloads.SkillProduction> u = updates();
		assertTrue("bounded: " + u.size(), u.size() >= 1 && u.size() <= 2);
		long runes = 0;
		long essence = 0;
		for (EventPayloads.SkillProduction p : u)
		{
			runes += qty(p.getProduced(), ItemID.NATURERUNE);
			essence += qty(p.getConsumed(), ItemID.BLANKRUNE_HIGH);
			assertEquals("never as production", 0L, qty(p.getProduced(), ItemID.BLANKRUNE_HIGH));
		}
		assertEquals(76L, runes);
		assertEquals(38L, essence);
		assertEquals("Pure essence", u.get(0).getConsumed().get(0).getItemName());
		assertEquals("nature", u.get(0).getMethod());
		SkillCompanionView v = rc.getView();
		assertEquals(Long.valueOf(38L), v.getFlushedConsumed().get(ItemID.BLANKRUNE_HIGH));
	}

	@Test
	public void pouchState_isTheGamesOwn_fromVarbitsAndVarps()
	{
		current = session("s-rc", "SKILLING", "runecraft");
		craftNatures(2);
		SkillCompanionView.EventView e = rc.getView().getEvent();
		assertEquals("24 loose + 12 read from the giant pouch's varbit when enabled", "36", e.getFields().get(0).getValue());
		assertEquals("1 loaded", e.getFields().get(1).getValue());
		inv.remove(ItemID.RCU_POUCH_GIANT);
		inv.put(ItemID.RCU_POUCH_GIANT_DEGRADE, 1L);
		push();
		rc.onVarbitChanged(varp(VarPlayerID.RCU_POUCH_DEGRADATION_GIANT, 900));
		ticks(2);
		boolean warned = false;
		for (SkillCompanionView.ContextLine l : rc.getView().getEvent().getLines())
		{
			warned |= l.getText().equals("Giant pouch degraded (holds 5 of 12)");
		}
		assertTrue(warned);
	}

	@Test
	public void bindingNecklace_isReadFromWornEquipment()
	{
		current = session("s-rc", "SKILLING", "runecraft");
		inv.put(ItemID.EARTHRUNE, 20L);
		push();
		ticks(1);
		rcXp += 100;
		rc.onStatChanged(new StatChanged(Skill.RUNECRAFT, rcXp, 91, 91));
		inv.put(ItemID.BLANKRUNE_HIGH, 6L);
		inv.put(ItemID.EARTHRUNE, 0L);
		inv.put(ItemID.LAVARUNE, 20L);
		push();
		ticks(4);
		assertEquals("lava", rc.getView().getMethodKey());
		assertTrue(lines().contains("No binding necklace: binds can fail"));
		worn.put(ItemID.MAGIC_EMERALD_NECKLACE, 1L);
		ticks(1);
		assertTrue(!lines().contains("No binding necklace: binds can fail"));
	}

	private List<String> lines()
	{
		List<String> out = new ArrayList<>();
		for (SkillCompanionView.ContextLine l : rc.getView().getEvent().getLines())
		{
			out.add(l.getText());
		}
		return out;
	}

	@Test
	public void gotr_productionIsNeverSentToRunecraft()
	{
		current = session("s-gotr", "STRUCTURED_ACTIVITY", "guardians_of_the_rift");
		craftNatures(20);
		ticks(50 * 8);
		current = session("s-rc-later", "SKILLING", "runecraft");
		ticks(60);
		long runes = 0;
		for (EventPayloads.SkillProduction p : updates())
		{
			runes += qty(p.getProduced(), ItemID.NATURERUNE);
		}
		assertEquals("never parked on a later, unrelated session", 0L, runes);
	}
}
