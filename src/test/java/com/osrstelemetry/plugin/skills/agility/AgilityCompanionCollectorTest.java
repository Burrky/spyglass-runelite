package com.osrstelemetry.plugin.skills.agility;

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
import com.osrstelemetry.plugin.skills.SkillCycleStats;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

/**
 * The Agility companion through the SAME shared collector as Mining and
 * Fishing: object clicks from real MenuOptionClicked events, laps in the
 * bounded SKILL_PRODUCTION update (a separate cycles field -- never items),
 * binding to SKILLING/agility only, and the live view the panel reads.
 */
public class AgilityCompanionCollectorTest
{
	private static final long ACCOUNT = 3_131L;
	private static final Gson GSON = new Gson();
	private static final AgilityCourse ARDY = AgilityCourses.byKey("ardougne");

	private final List<Object[]> events = new ArrayList<>();
	private int tick = 100;
	private long now = 1_800_000_000_000L;
	private int xp = 3_000_000;
	private Session current;
	private final Map<Integer, Long> inv = new LinkedHashMap<>();
	private AgilityCompanionCollector collector;

	@Before
	public void setUp()
	{
		EventLedger ledger = new EventLedger(GSON)
		{
			@Override
			public void append(long accountHash, EventType type, Object payload)
			{
				events.add(new Object[]{type, payload});
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
				default:
					return null;
			}
		});
		collector = new AgilityCompanionCollector(client, ledger,
			() -> current == null ? SessionSnapshot.absent() : SessionSnapshot.capture(current), new LocalStateStore(GSON));
		collector.setClock(() -> now);
		collector.startUp();
		collector.onStatChanged(new StatChanged(Skill.AGILITY, xp, 85, 85));
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

	private static MenuOptionClicked objectClick(int objectId, MenuAction action)
	{
		MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(), new Class<?>[]{MenuEntry.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getOption":
					return "Climb";
				case "getTarget":
					return "<col=ffff>Obstacle";
				case "getType":
					return action;
				case "getIdentifier":
					return objectId;
				default:
					return null;
			}
		});
		return new MenuOptionClicked(entry);
	}

	private void obstacle(int objectId)
	{
		collector.onMenuOptionClicked(objectClick(objectId, MenuAction.GAME_OBJECT_FIRST_OPTION));
		ticks(3);
		xp += 30;
		collector.onStatChanged(new StatChanged(Skill.AGILITY, xp, 85, 85));
		ticks(2);
	}

	private void lap()
	{
		for (int i = 0; i < ARDY.getNodes().size() - 1; i++)
		{
			obstacle(ARDY.getNodes().get(i).getObjectIds().iterator().next());
		}
		collector.onMenuOptionClicked(objectClick(ARDY.getNodes().get(6).getObjectIds().iterator().next(), MenuAction.GAME_OBJECT_FIRST_OPTION));
		ticks(3);
		AgilityCourse.Tile end = ARDY.getEndTiles().get(0);
		collector.agility().onPosition(tick, now, end.getX(), end.getY(), end.getPlane(), end.regionId());
		xp += 500;
		collector.onStatChanged(new StatChanged(Skill.AGILITY, xp, 85, 85));
		collector.agility().onPosition(tick, now, end.getX() + 2, end.getY(), 0, end.regionId());
		ticks(2);
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

	@Test
	public void lapsGoInTheBoundedUpdate_asCycles_neverAsItems()
	{
		current = session("s-agi", "SKILLING", "agility");
		int startTick = tick;
		lap();
		lap();
		lap();
		ticks(60);
		int elapsedTicks = tick - startTick;
		long laps = 0;
		for (EventPayloads.SkillProduction u : updates())
		{
			assertTrue("laps are never produced items", u.getProduced().isEmpty());
			if (u.getCycles() != null)
			{
				for (EventPayloads.SkillCycle c : u.getCycles())
				{
					assertEquals("ardougne", c.getMethod());
					assertTrue(c.getBestMillis() > 0);
					laps += c.getCompleted();
				}
			}
		}
		assertEquals(3L, laps);
		assertTrue("bounded: one update per ~30 s of activity, never one per obstacle (21 here)",
			updates().size() <= elapsedTicks / 50 + 1 && updates().size() < 21);
		assertEquals("ardougne", updates().get(0).getMethod());
		SkillCompanionView v = collector.getView();
		assertEquals("s-agi", v.getBoundSessionId());
		SkillCycleStats flushed = v.getFlushedCycles().get("ardougne");
		assertEquals(3L, flushed.getCount());
	}

	@Test
	public void theLiveViewCarriesTheCurrentLap()
	{
		current = session("s-agi", "SKILLING", "agility");
		lap();
		obstacle(ARDY.getNodes().get(0).getObjectIds().iterator().next());
		obstacle(ARDY.getNodes().get(1).getObjectIds().iterator().next());
		SkillCompanionView.CycleView c = collector.getView().getCycle();
		assertNotNull(c);
		assertEquals("ardougne", c.getMethodKey());
		assertEquals(2, c.getProgress());
		assertEquals(Integer.valueOf(7), c.getDenominator());
		assertNotNull(c.getElapsedMillis());
		assertEquals("ardougne", collector.getView().getMethodKey());
	}

	@Test
	public void markOfGrace_collectedOnTheCourse_isAReward_inTheUpdate()
	{
		current = session("s-agi", "SKILLING", "agility");
		obstacle(ARDY.getNodes().get(0).getObjectIds().iterator().next());
		inv.merge(ItemID.GRACE, 1L, Long::sum);
		pushInventory();
		ticks(60);
		long marks = 0;
		for (EventPayloads.SkillProduction u : updates())
		{
			for (EventPayloads.ProducedItem i : u.getProduced())
			{
				if (i.getItemId() == ItemID.GRACE)
				{
					marks += i.getQuantity();
				}
			}
		}
		assertEquals(1L, marks);
	}

	@Test
	public void lapsNoAgilitySessionClaims_areDropped()
	{
		current = session("s-sep", "STRUCTURED_ACTIVITY", "hallowed_sepulchre");
		lap();
		ticks(50 * 8);
		current = session("s-agi-later", "SKILLING", "agility");
		ticks(60);
		for (EventPayloads.SkillProduction u : updates())
		{
			assertNull("never sent to another activity, never carried into a later session", u.getCycles());
		}
	}

	@Test
	public void otherMenuActions_areNotObstacleClicks()
	{
		collector.onMenuOptionClicked(objectClick(ARDY.getNodes().get(0).getObjectIds().iterator().next(), MenuAction.WALK));
		ticks(2);
		xp += 30;
		collector.onStatChanged(new StatChanged(Skill.AGILITY, xp, 85, 85));
		ticks(2);
		assertNull(collector.getView().getCycle());
	}
}
