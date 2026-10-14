package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.activity.PestControlGameState;
import com.osrstelemetry.plugin.activity.StructuredActivityCatalog;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.pestcontrol.VoidPiece;
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
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;

/**
 * Pest Control dashboard through the real collector: round lifecycle (island
 * 10536 only), ROUND TIME and AVG GAME evidence, the native status overlay,
 * the commendation balance, Void ownership and reward-skill XP -- with no
 * dependency on RuneLite's optional Pest Control plugin.
 */
public class StructuredActivityCollectorPestControlDashboardTest
{
	private static final long ACCOUNT = 5151L;
	private static final int ISLAND = StructuredActivityCollector.PEST_CONTROL_ISLAND_REGION;
	private static final int OUTPOST = StructuredActivityCollector.PEST_CONTROL_OUTPOST_REGION;
	private static final int LUMBRIDGE = 12850;
	private static final Instant T = Instant.parse("2026-10-02T03:43:38Z");
	private static final String LIVE_WIN = "Squire|Congratulations! You managed to destroy all the portals! We've awarded you 8 Void "
		+ "Knight Commendation points. Please also accept these coins as a reward.";
	private static final String LIVE_BALANCE = "<col=000080>You now have <col=800000>11<col=000080> Void Knight Commendation points! "
		+ "You can speak to a Void Knight to exchange your points for rewards.";

	/** A widget: text, width, x. */
	private static final class W
	{
		final String text;
		final int width;

		W(String text, int width)
		{
			this.text = text;
			this.width = width;
		}
	}

	private final List<Object[]> events = new ArrayList<>();
	private final Map<Integer, W> widgets = new HashMap<>();
	private final List<Integer> widgetReads = new ArrayList<>();
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private int tick = 1000;
	private int progressCalls;
	private final List<Integer> balanceSink = new ArrayList<>();
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
				case "getVarbitValue":
					return a.length == 1 ? varbits.getOrDefault((Integer) a[0], 0) : 0;
				case "getGameState":
					return GameState.LOGGED_IN;
				case "getWidget":
					if (a.length == 1 && a[0] instanceof Integer)
					{
						widgetReads.add((Integer) a[0]);
						W w = widgets.get(a[0]);
						return w == null ? null : widget(w);
					}
					return null;
				default:
					return defaultValue(m.getReturnType());
			}
		});
		collector = new StructuredActivityCollector(client, id -> "item" + id, ledger, StructuredActivityRegistry.defaults());
		collector.setClock(Clock.fixed(T, ZoneOffset.UTC));
		collector.setProgressListener(() -> progressCalls++);
		collector.setPestBalanceListener(balanceSink::add);
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

	private static Widget widget(W w)
	{
		return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getText":
					return w.text;
				case "getWidth":
					return w.width;
				default:
					return defaultValue(m.getReturnType());
			}
		});
	}

	private static ItemContainer container(int... ids)
	{
		Item[] arr = new Item[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			arr[i] = new Item(ids[i], 1);
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

	/** The status overlay as the live game had it: static activity layers 141 / 145 wide, the meter in the varbit. */
	private void hud(int activityCounter, String health, String... portalHp)
	{
		varbits.put(VarbitID.PEST_ACTIVITY_COUNTER, activityCounter);
		widgets.put(InterfaceID.PestStatusOverlay.ACTIVITY_BAR, new W(null, 141));
		widgets.put(InterfaceID.PestStatusOverlay.ACTIVITY_CONTAINER, new W(null, 145));
		widgets.put(InterfaceID.PestStatusOverlay.PEST_STATUS_OVER_HEALTH, new W(health, 0));
		int[] ids = {InterfaceID.PestStatusOverlay.PEST_STATUS_PORTTXT1, InterfaceID.PestStatusOverlay.PEST_STATUS_PORTTXT2,
			InterfaceID.PestStatusOverlay.PEST_STATUS_PORTTXT3, InterfaceID.PestStatusOverlay.PEST_STATUS_PORTTXT4};
		for (int i = 0; i < ids.length; i++)
		{
			widgets.put(ids[i], new W(portalHp[i], 0));
		}
	}

	private void at(int region)
	{
		tick++;
		collector.onRegion(region);
		collector.trackPestControlTick();
	}

	private void dialog(String text)
	{
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.DIALOG, "", text, "", 0));
	}

	private void mesbox(String text)
	{
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.MESBOX, "", text, "", 0));
	}

	/** Observed game: outpost first, then the island. */
	private void observedEntry()
	{
		at(OUTPOST);
		at(ISLAND);
	}

	// ---- CURRENT GAME lifecycle ------------------------------------------------------

	@Test
	public void currentGame_existsOnlyOnTheIsland()
	{
		at(OUTPOST);
		assertNull("the outpost is not a game", collector.getPestControlGame());
		at(ISLAND);
		assertNotNull(collector.getPestControlGame());
		at(OUTPOST);
		assertNull(collector.getPestControlGame());
		at(LUMBRIDGE);
		assertNull(collector.getPestControlGame());
	}

	@Test
	public void hud_isReadFromTheNativeStatusOverlay()
	{
		observedEntry();
		hud(37, "184", "250", "0", "37", "0");
		at(ISLAND);
		PestControlGameState g = collector.getPestControlGame();
		assertEquals("live QA: the HUD showed ~74%, never the static layers' 97%", Integer.valueOf(74), g.getActivityPercent());
		assertEquals(Integer.valueOf(92), g.getKnightHealthPercent());
		assertEquals(Integer.valueOf(2), g.getPortalsDestroyed());
		assertEquals(T, g.getStartedAt());
	}

	@Test
	public void activity_followsTheVarbit_acrossTheWholeRange()
	{
		observedEntry();
		int[][] cases = {{50, 100}, {38, 76}, {25, 50}, {13, 26}, {0, 0}};
		for (int[] c : cases)
		{
			hud(c[0], "200", "250", "250", "250", "250");
			at(ISLAND);
			assertEquals("counter " + c[0], Integer.valueOf(c[1]), collector.getPestControlGame().getActivityPercent());
		}
	}

	@Test
	public void activity_withoutTheStatusOverlay_isUnknown_evenIfTheVarbitHasAValue()
	{
		observedEntry();
		varbits.put(VarbitID.PEST_ACTIVITY_COUNTER, 40);
		at(ISLAND);
		assertNull(collector.getPestControlGame().getActivityPercent());
	}

	@Test
	public void activity_outOfRangeVarbit_isADash_notAClamp()
	{
		observedEntry();
		hud(77, "200", "250", "250", "250", "250");
		at(ISLAND);
		assertNull(collector.getPestControlGame().getActivityPercent());
	}

	@Test
	public void hudAbsent_isUnknown_neverZero()
	{
		observedEntry();
		at(ISLAND);
		PestControlGameState g = collector.getPestControlGame();
		assertNull(g.getActivityPercent());
		assertNull(g.getKnightHealthPercent());
		assertNull(g.getPortalsDestroyed());
	}

	@Test
	public void hudChange_notifiesThePanel_unchangedDoesNot()
	{
		observedEntry();
		hud(50, "200", "250", "250", "250", "250");
		at(ISLAND);
		int before = progressCalls;
		at(ISLAND);
		assertEquals("same values: no repaint request", before, progressCalls);
		hud(49, "200", "250", "250", "250", "250");
		at(ISLAND);
		assertTrue(progressCalls > before);
	}

	@Test
	public void eachRound_clearsAndReinitialises()
	{
		observedEntry();
		hud(30, "100", "0", "0", "0", "250");
		at(ISLAND);
		dialog(LIVE_WIN);
		at(OUTPOST);
		widgets.clear();
		at(ISLAND);
		PestControlGameState g = collector.getPestControlGame();
		assertNull("no carry-over from the last round", g.getPortalsDestroyed());
		assertNull(g.getKnightHealthPercent());
	}

	@Test
	public void outsidePestControl_noWidgetIsRead()
	{
		hud(50, "200", "250", "250", "250", "250");
		at(LUMBRIDGE);
		at(LUMBRIDGE);
		assertTrue("bounded: nothing read away from Pest Control", widgetReads.isEmpty());
	}

	@Test
	public void midGameLogin_isNotObservedFromStart()
	{
		at(ISLAND); // the first region seen after login
		PestControlGameState g = collector.getPestControlGame();
		assertNotNull(g);
		assertNull("joined part-way: no game time", g.getStartedAt());
		tick += 100;
		dialog(LIVE_WIN);
		assertTrue("a win joined part-way never enters Avg Game", payloads(EventType.STRUCTURED_ACTIVITY_SCORE).isEmpty());
		assertEquals("but it is still a game won", 1, payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION).size());
	}

	// ---- AVG GAME / ROUND TIME -------------------------------------------------------

	@Test
	public void observedWin_emitsItsDuration_once()
	{
		observedEntry();
		int entry = tick;
		tick = entry + 200; // live: 2:00
		dialog(LIVE_WIN);
		tick += 4;
		dialog(LIVE_WIN);
		List<EventPayloads.StructuredActivityScore> scores = payloads(EventType.STRUCTURED_ACTIVITY_SCORE);
		assertEquals(1, scores.size());
		assertEquals(StructuredActivityCatalog.PEST_CONTROL, scores.get(0).getActivityKey());
		assertEquals(Integer.valueOf(120), scores.get(0).getPoints());
		assertTrue(scores.get(0).isObservedFromStart());
		assertEquals(1, payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION).size());
	}

	@Test
	public void exit_emitsRoundTime_winOrLoss()
	{
		observedEntry();
		tick += 199;
		at(OUTPOST); // a lost game: no win line
		List<EventPayloads.StructuredActivityRoundTime> rounds = payloads(EventType.STRUCTURED_ACTIVITY_ROUND_TIME);
		assertEquals(1, rounds.size());
		assertEquals(200 * 600L, rounds.get(0).getRoundMillis());
		assertTrue("a loss adds no win", payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION).isEmpty());
		assertTrue("and no points", payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY).isEmpty());
	}

	@Test
	public void outpostWaiting_neverBecomesRoundTime()
	{
		at(OUTPOST);
		tick += 500;
		at(OUTPOST);
		at(LUMBRIDGE);
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_ROUND_TIME).isEmpty());
	}

	@Test
	public void logoutOnTheIsland_closesTheRound_thenForgetsIt()
	{
		observedEntry();
		tick += 50;
		collector.onGameStateChanged(gameState(GameState.LOGIN_SCREEN));
		assertEquals(1, payloads(EventType.STRUCTURED_ACTIVITY_ROUND_TIME).size());
		assertNull(collector.getPestControlGame());
		assertNull("balance unknown again until re-seeded", collector.getPestBalance());
	}

	@Test
	public void reEnable_onTheIsland_resumesAsNotObserved_withoutTheDisabledGap()
	{
		observedEntry();
		tick += 1000; // plugin disabled meanwhile
		collector.disarmPendingClaim();
		assertTrue("the disabled gap is never round time", payloads(EventType.STRUCTURED_ACTIVITY_ROUND_TIME).isEmpty());
		collector.trackPestControlTick();
		PestControlGameState g = collector.getPestControlGame();
		assertNotNull("still on the island: a round joined part-way", g);
		assertNull(g.getStartedAt());
	}

	@Test
	public void accountSwitch_resetsEverythingPestControl()
	{
		observedEntry();
		mesbox(LIVE_BALANCE);
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, container(ItemID.PEST_VOID_KNIGHT_TOP)));
		collector.onStatChanged(new StatChanged(Skill.DEFENCE, 500_000, 70, 70));
		collector.resetForAccountSwitch();
		assertNull(collector.getPestControlGame());
		assertNull(collector.getPestBalance());
		assertFalse(collector.getVoidOwnership().getSnapshot().owns(VoidPiece.TOP));
		assertNull(collector.pestSkillXp(Skill.DEFENCE));
	}

	// ---- balance -------------------------------------------------------------------

	@Test
	public void balance_fromTheLiveMessageBox()
	{
		mesbox(LIVE_BALANCE);
		assertEquals(Integer.valueOf(11), collector.getPestBalance());
		assertEquals("remembered as planning state", Integer.valueOf(11), balanceSink.get(balanceSink.size() - 1));
		assertTrue("never telemetry", events.isEmpty());
	}

	@Test
	public void award_advancesAKnownBalance_andTheMessageBoxRestatesIt()
	{
		collector.seedPestBalance(3);
		observedEntry();
		dialog(LIVE_WIN);
		assertEquals(Integer.valueOf(11), collector.getPestBalance());
		mesbox(LIVE_BALANCE);
		assertEquals("no double count", Integer.valueOf(11), collector.getPestBalance());
	}

	@Test
	public void unknownBalance_staysUnknownAfterAWin()
	{
		observedEntry();
		dialog(LIVE_WIN);
		assertNull("never assumed 0 + 8", collector.getPestBalance());
	}

	@Test
	public void landerPointsLine_inTheOutpost()
	{
		widgets.put(InterfaceID.PestLanderOverlay.PEST_LANDER_OVER_POINTS, new W("Commendation points: 42", 0));
		at(OUTPOST);
		assertEquals(Integer.valueOf(42), collector.getPestBalance());
	}

	@Test
	public void rewardShop_makesTheBalanceUnknown()
	{
		collector.seedPestBalance(500);
		WidgetLoaded shop = new WidgetLoaded();
		shop.setGroupId(InterfaceID.PEST_REWARDSHOP);
		collector.onWidgetLoaded(shop);
		assertNull(collector.getPestBalance());
		assertNull("the remembered balance is cleared too", balanceSink.get(balanceSink.size() - 1));
	}

	// ---- ownership / XP ------------------------------------------------------------

	@Test
	public void ownership_fromInventoryWornAndBank_currentStateOnly()
	{
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, container(ItemID.PEST_VOID_KNIGHT_GLOVES)));
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, container(ItemID.GAME_PEST_MAGE_HELM)));
		assertFalse(collector.getVoidOwnership().getSnapshot().isBankKnown());
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK, container(ItemID.ELITE_VOID_KNIGHT_TOP)));
		assertTrue(collector.getVoidOwnership().getSnapshot().owns(VoidPiece.GLOVES));
		assertTrue(collector.getVoidOwnership().getSnapshot().owns(VoidPiece.MAGE_HELM));
		assertTrue(collector.getVoidOwnership().getSnapshot().owns(VoidPiece.ELITE_TOP));
		assertTrue("ownership is never telemetry", events.isEmpty());
	}

	@Test
	public void seededBank_neverOverridesABankSeenThisLogin()
	{
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK, container()));
		Map<Integer, Long> stale = new HashMap<>();
		stale.put(ItemID.PEST_VOID_KNIGHT_MACE, 1L);
		collector.seedBankOwnership(stale);
		assertFalse(collector.getVoidOwnership().getSnapshot().owns(VoidPiece.MACE));
	}

	@Test
	public void rewardSkillXp_isCachedExactly_othersIgnored()
	{
		collector.onStatChanged(new StatChanged(Skill.PRAYER, 1_234_567, 72, 72));
		collector.onStatChanged(new StatChanged(Skill.MINING, 99, 1, 1));
		assertEquals(Integer.valueOf(1_234_567), collector.pestSkillXp(Skill.PRAYER));
		assertNull(collector.pestSkillXp(Skill.MINING));
		assertNull(collector.pestSkillXp(Skill.ATTACK));
	}

	private static GameStateChanged gameState(GameState state)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(state);
		return e;
	}
}
