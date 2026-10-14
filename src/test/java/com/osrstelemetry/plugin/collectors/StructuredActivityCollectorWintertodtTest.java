package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.activity.StructuredActivityCatalog;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.activity.WintertodtRoundTracker;
import com.osrstelemetry.plugin.activity.WintertodtState;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.wintertodt.WintertodtTools;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;

/**
 * Wintertodt through the real collector: the round from native state only
 * (status overlay texts + respawn varbit), BOUNDED persistence (round time
 * and action totals once per round, the subdue's points and rewards once) --
 * never a per-tick event -- and the boundaries (leave, logout, hop, switch).
 */
public class StructuredActivityCollectorWintertodtTest
{
	private static final long ACCOUNT = 6262L;
	private static final int ARENA = StructuredActivityCollector.WINTERTODT_ARENA_REGION;
	private static final int CAMP = StructuredActivityCollector.WINTERTODT_CAMP_REGION;
	private static final Instant T = Instant.parse("2026-10-02T15:00:00Z");

	private final List<Object[]> events = new ArrayList<>();
	private final Map<Integer, String> widgets = new HashMap<>();
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private final List<Integer> widgetReads = new ArrayList<>();
	private int tick = 500;
	private StructuredActivityCollector collector;

	@Before
	public void setUp()
	{
		EventLedger ledger = new EventLedger(new Gson())
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
				case "getVarbitValue":
					return a.length == 1 ? varbits.getOrDefault((Integer) a[0], 0) : 0;
				case "getWidget":
					if (a.length == 1 && a[0] instanceof Integer)
					{
						widgetReads.add((Integer) a[0]);
						String text = widgets.get(a[0]);
						return text == null ? null : widget(text);
					}
					return null;
				default:
					return defaultValue(m.getReturnType());
			}
		});
		Map<Integer, String> names = new HashMap<>();
		names.put(1351, "Bronze axe");
		names.put(946, "Knife");
		names.put(590, "Tinderbox");
		collector = new StructuredActivityCollector(client, id -> names.getOrDefault(id, "item" + id), ledger,
			StructuredActivityRegistry.defaults());
		collector.setClock(Clock.fixed(T, ZoneOffset.UTC));
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

	private static Widget widget(String text)
	{
		return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
			(p, m, a) -> "getText".equals(m.getName()) ? text : defaultValue(m.getReturnType()));
	}

	private static ItemContainer container(int... idQty)
	{
		Item[] arr = new Item[idQty.length / 2];
		for (int i = 0; i < idQty.length; i += 2)
		{
			arr[i / 2] = new Item(idQty[i], idQty[i + 1]);
		}
		return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(), new Class<?>[]{ItemContainer.class},
			(p, m, a) -> "getItems".equals(m.getName()) ? arr : defaultValue(m.getReturnType()));
	}

	@SuppressWarnings("unchecked")
	private <T> List<T> payloads(EventType type)
	{
		List<T> out = new ArrayList<>();
		for (Object[] e : events)
		{
			if (e[0] == type)
			{
				out.add((T) e[1]);
			}
		}
		return out;
	}

	private void hud(Integer energy, int points, int respawn)
	{
		widgets.put(InterfaceID.WintStatus.WARMTH_TITLE, "Warmth: 74%");
		widgets.put(InterfaceID.WintStatus.ENERGY_TITLE, energy == null ? "The Wintertodt returns in: 0:30" : "Wintertodt's Energy: " + energy + "%");
		widgets.put(InterfaceID.WintStatus.POINTS, "Points<br>" + points);
		varbits.put(VarbitID.WINT_TRANSMIT_RESPAWNDELAY, respawn);
		at(ARENA);
	}

	private void at(int region)
	{
		tick++;
		collector.onRegion(region);
		collector.trackWintertodtTick();
	}

	private void inventory(int... idQty)
	{
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, container(idQty)));
	}

	private void game(String text)
	{
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", text, "", 0));
	}

	/** Intermission, then a full round worth {@code points}, ending into the next intermission after 300 ticks. */
	private void fullRound(int points)
	{
		hud(null, 0, 40);
		hud(100, 0, 0);
		for (int i = 0; i < 298; i++)
		{
			hud(50, points, 0);
		}
		hud(0, points, 0);
		hud(null, 0, 50);
	}

	@Test
	public void liveState_fromTheNativeOverlay()
	{
		hud(null, 0, 40);
		WintertodtState s = collector.getWintertodtState();
		assertEquals(WintertodtState.Phase.INTERMISSION, s.getPhase());
		assertEquals(Integer.valueOf(24), s.getCountdownSeconds());
		hud(88, 125, 0);
		s = collector.getWintertodtState();
		assertEquals(WintertodtState.Phase.ROUND, s.getPhase());
		assertEquals(Integer.valueOf(74), s.getWarmthPercent());
		assertEquals(Integer.valueOf(88), s.getEnergyPercent());
		assertEquals(Integer.valueOf(125), s.getPoints());
	}

	@Test
	public void roundEnd_stampsTheEffectivePlaystyleLabel_forHistory_only()
	{
		fullRound(620);
		List<EventPayloads.StructuredActivityRoundTime> first = payloads(EventType.STRUCTURED_ACTIVITY_ROUND_TIME);
		assertNull("unwired: no label", first.get(0).getPlaystyle());
		collector.setWintertodtPlaystyleLabel(() -> "Mass • Points");
		fullRound(910);
		List<EventPayloads.StructuredActivityRoundTime> rounds = payloads(EventType.STRUCTURED_ACTIVITY_ROUND_TIME);
		assertEquals(2, rounds.size());
		assertEquals("Mass • Points", rounds.get(1).getPlaystyle());
		assertEquals("no extra events for the label", 2, rounds.size());
	}

	@Test
	public void liveHudChanges_writeNothing()
	{
		hud(null, 0, 40);
		hud(100, 0, 0);
		for (int i = 0; i < 50; i++)
		{
			hud(100 - i, i * 10, 0);
		}
		for (Object[] e : events)
		{
			assertEquals("warmth / energy / points are ephemeral: only the one location event", EventType.STRUCTURED_ACTIVITY_STATE, e[0]);
		}
		assertEquals(1, events.size());
	}

	@Test
	public void roundEnd_writesItsTime_once_completeWhenSeenFromStart()
	{
		fullRound(620);
		List<EventPayloads.StructuredActivityRoundTime> rounds = payloads(EventType.STRUCTURED_ACTIVITY_ROUND_TIME);
		assertEquals(1, rounds.size());
		assertEquals(StructuredActivityCatalog.WINTERTODT, rounds.get(0).getActivityKey());
		assertEquals(Boolean.TRUE, rounds.get(0).getCompleteRound());
		assertEquals("start to the tick energy hit 0", 299 * 600L, rounds.get(0).getRoundMillis());
	}

	@Test
	public void subdue_writesItsPoints_once_butNeverDerivedRewards()
	{
		fullRound(950);
		game("Your subdued Wintertodt count is: 412.");
		game("Your subdued Wintertodt count is: 412.");
		List<EventPayloads.StructuredActivityScore> scores = payloads(EventType.STRUCTURED_ACTIVITY_SCORE);
		assertEquals(1, scores.size());
		assertEquals(Integer.valueOf(950), scores.get(0).getPoints());
		assertEquals(Integer.valueOf(412), scores.get(0).getCompletionCount());
		assertTrue("rewards come only from the game's award line", payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY).isEmpty());
	}

	private static final String LIVE_AWARD_1 = "You're owed an additional 3 rewards from the reward cart. You're now owed 3 rewards.";

	private long earned()
	{
		long total = 0;
		for (Object o : payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY))
		{
			EventPayloads.StructuredActivityCurrency c = (EventPayloads.StructuredActivityCurrency) o;
			assertEquals(StructuredActivityCollector.WINTERTODT_REWARDS, c.getCurrency());
			total += c.getGained();
		}
		return total;
	}

	@Test
	public void liveAward_isTheActualCount_evenAboveTheGuaranteedTwo()
	{
		// Live 2026-10-02: 950 points (formula guarantees 2) -> the game awarded 3.
		fullRound(950);
		game("Your subdued Wintertodt count is: 28.");
		game(LIVE_AWARD_1);
		assertEquals(3, earned());
		assertEquals(Integer.valueOf(3), collector.getWintertodtCartOwed());
	}

	@Test
	public void threeLiveRounds_plusThreeEach_isNine()
	{
		at(ARENA);
		game(LIVE_AWARD_1);
		tick += 500;
		game("You're owed an additional 3 rewards from the reward cart. You're now owed 6 rewards.");
		tick += 500;
		game("You're owed an additional 3 rewards from the reward cart. You're now owed 9 rewards.");
		assertEquals(9, earned());
		assertEquals(Integer.valueOf(9), collector.getWintertodtCartOwed());
	}

	@Test
	public void cartAlreadyHoldingRewards_onlyTheAdditionalAmountIsEarned()
	{
		at(ARENA);
		game("You're owed an additional 3 rewards from the reward cart. You're now owed 43 rewards.");
		assertEquals("never the balance", 3, earned());
		assertEquals(Integer.valueOf(43), collector.getWintertodtCartOwed());
		List<EventPayloads.StructuredActivityCurrency> c = payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY);
		assertEquals(43, c.get(0).getBalance());
	}

	@Test
	public void duplicateAwardDelivery_isOneAward_singularAndCommasParse()
	{
		at(ARENA);
		game(LIVE_AWARD_1);
		tick += 1;
		game(LIVE_AWARD_1);
		assertEquals(3, earned());
		tick += 400;
		game("You're owed an additional 1 reward from the reward cart. You're now owed 1,204 rewards.");
		assertEquals(4, earned());
		assertEquals(Integer.valueOf(1204), collector.getWintertodtCartOwed());
	}

	@Test
	public void check_search_bigSearch_andEmptying_neverChangeEarnedRewards()
	{
		at(ARENA);
		game(LIVE_AWARD_1);
		at(CAMP);
		game("You are owed 9 more rewards from the cart.");
		assertEquals(Integer.valueOf(9), collector.getWintertodtCartOwed());
		game("You are owed 8 more rewards from the cart.");
		game("You are owed 3 more rewards from the cart.");
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.MESBOX, "",
			"You think you've taken as much as you're owed from the reward cart.", "", 0));
		assertEquals("claiming never reduces (or adds to) what the session earned", 3, earned());
		assertEquals(1, payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY).size());
	}

	@Test
	public void cartBalance_followsThePoolVarbit_intoTheCamp_neverTheEarnedRewards()
	{
		varbits.put(VarbitID.WINT_REWARD_POOL, 9);
		hud(null, 0, 40);
		assertEquals(Integer.valueOf(9), collector.getWintertodtCartRewards());
		assertEquals(com.osrstelemetry.plugin.wintertodt.WintertodtPanelSource.Location.ARENA, collector.getWintertodtLocation());
		int before = events.size();
		at(CAMP);
		assertEquals(com.osrstelemetry.plugin.wintertodt.WintertodtPanelSource.Location.CAMP, collector.getWintertodtLocation());
		assertEquals(Integer.valueOf(9), collector.getWintertodtCartRewards());
		varbits.put(VarbitID.WINT_REWARD_POOL, 8);  // Search
		at(CAMP);
		assertEquals(Integer.valueOf(8), collector.getWintertodtCartRewards());
		varbits.put(VarbitID.WINT_REWARD_POOL, 3);  // Big-search
		at(CAMP);
		varbits.put(VarbitID.WINT_REWARD_POOL, 0);  // emptied
		at(CAMP);
		assertEquals(Integer.valueOf(0), collector.getWintertodtCartRewards());
		assertEquals("the balance writes nothing (round end of the partial intermission aside)", 0,
			payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY).size());
		assertTrue("no per-tick telemetry in the camp", events.size() - before <= 1);
		assertEquals(0, earned());
		at(12850);
		assertEquals(com.osrstelemetry.plugin.wintertodt.WintertodtPanelSource.Location.ELSEWHERE, collector.getWintertodtLocation());
	}

	@Test
	public void campOnly_neverTrustsTheVarbit_untilTheArenaOrTheGameStatesIt()
	{
		varbits.put(VarbitID.WINT_REWARD_POOL, 5);
		at(CAMP);
		at(CAMP);
		assertNull("not seen in the arena this login: unknown, never invented", collector.getWintertodtCartRewards());
		game("You are owed 40 more rewards from the cart.");
		assertEquals("the game's own Check", Integer.valueOf(40), collector.getWintertodtCartRewards());
		GameStateChanged logout = new GameStateChanged();
		logout.setGameState(GameState.LOGIN_SCREEN);
		collector.onGameStateChanged(logout);
		assertNull("ephemeral: gone at logout", collector.getWintertodtCartRewards());
	}

	@Test
	public void awardLine_setsTheBalance_toTheNowOwedTotal()
	{
		at(ARENA);
		game("You're owed an additional 3 rewards from the reward cart. You're now owed 43 rewards.");
		assertEquals(Integer.valueOf(43), collector.getWintertodtCartRewards());
		assertEquals(3, earned());
	}

	@Test
	public void awardLineAwayFromWintertodt_isIgnored()
	{
		at(12850);
		game(LIVE_AWARD_1);
		assertEquals(0, earned());
		assertNull(collector.getWintertodtCartOwed());
	}

	@Test
	public void actionTotals_areWrittenOncePerRound()
	{
		hud(null, 0, 40);
		hud(100, 0, 0);
		inventory(ItemID.WINT_BRUMA_ROOT, 0);
		inventory(ItemID.WINT_BRUMA_ROOT, 5);
		inventory(ItemID.WINT_BRUMA_ROOT, 3);
		hud(90, 20, 0);
		game("You light the brazier.");
		hud(90, 45, 0);
		hud(null, 0, 50);
		Map<String, Integer> totals = new HashMap<>();
		for (Object o : payloads(EventType.STRUCTURED_ACTIVITY_ACTION))
		{
			EventPayloads.StructuredActivityAction a = (EventPayloads.StructuredActivityAction) o;
			totals.put(a.getAction(), a.getCount());
		}
		assertEquals(Integer.valueOf(5), totals.get(WintertodtRoundTracker.CHOPPED));
		assertEquals(Integer.valueOf(2), totals.get(WintertodtRoundTracker.ROOTS_FED));
		assertEquals(Integer.valueOf(1), totals.get(WintertodtRoundTracker.LIT));
		assertEquals("one event per action kind", 3, totals.size());
	}

	@Test
	public void leavingTheArena_midRound_writesAPartialRound_andClearsTheState()
	{
		hud(null, 0, 40);
		hud(100, 0, 0);
		hud(80, 50, 0);
		at(CAMP);
		List<EventPayloads.StructuredActivityRoundTime> rounds = payloads(EventType.STRUCTURED_ACTIVITY_ROUND_TIME);
		assertEquals(1, rounds.size());
		assertEquals(Boolean.FALSE, rounds.get(0).getCompleteRound());
		assertNull(collector.getWintertodtState());
	}

	@Test
	public void logoutOrHop_midRound_closesIt_andTheNextWorldsRoundIsPartial()
	{
		hud(null, 0, 40);
		hud(100, 0, 0);
		hud(80, 50, 0);
		GameStateChanged hop = new GameStateChanged();
		hop.setGameState(GameState.HOPPING);
		collector.onGameStateChanged(hop);
		assertEquals(1, payloads(EventType.STRUCTURED_ACTIVITY_ROUND_TIME).size());
		assertNull(collector.getWintertodtState());
		hud(60, 0, 0); // a game already in progress on the new world
		assertNull("joined part-way", collector.getWintertodtState().getRoundStartedAt());
	}

	@Test
	public void awayFromWintertodt_nothingIsRead()
	{
		at(12850);
		at(12850);
		assertTrue(widgetReads.isEmpty());
		assertNull(collector.getWintertodtState());
	}

	@Test
	public void tools_andSkillXp_forReadinessAndGoals()
	{
		at(CAMP);
		inventory(1351, 1, 590, 1);
		assertTrue(collector.getWintertodtTools().contains(WintertodtTools.AXE));
		assertTrue(collector.getWintertodtTools().contains(WintertodtTools.TINDERBOX));
		assertFalse(collector.getWintertodtTools().contains(WintertodtTools.KNIFE));
		collector.onStatChanged(new StatChanged(Skill.FIREMAKING, 13_034_431, 99, 99));
		collector.onStatChanged(new StatChanged(Skill.AGILITY, 273_742, 60, 60));
		assertEquals(Integer.valueOf(13_034_431), collector.wintertodtSkillXp(Skill.FIREMAKING));
		assertEquals(Integer.valueOf(273_742), collector.wintertodtSkillXp(Skill.AGILITY));
		collector.resetForAccountSwitch();
		assertNull(collector.wintertodtSkillXp(Skill.FIREMAKING));
	}
}
