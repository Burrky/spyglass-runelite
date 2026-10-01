package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.activity.GotrGameState;
import com.osrstelemetry.plugin.activity.StructuredActivityCatalog;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
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
 * Guardians of the Rift through the real collector: native HUD / varbit /
 * script / chat / inventory inputs in, Spyglass events out. No other plugin.
 */
public class StructuredActivityCollectorGotrTest
{
	private static final long ACCOUNT = 4242L;
	private static final int GOTR_REGION = 14484;
	private static final int ESS = ItemID.GOTR_GUARDIAN_ESSENCE;

	private final List<Object[]> events = new ArrayList<>();
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private final Map<Integer, String> widgetTexts = new HashMap<>();
	private boolean hudLoaded;
	private int tick = 100;
	private Map<Integer, Long> inventory = new HashMap<>();
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
						int id = (Integer) a[0];
						if (id == InterfaceID.GotrHud.CONTENT)
						{
							return hudLoaded ? widget(null) : null;
						}
						return widgetTexts.containsKey(id) ? widget(widgetTexts.get(id)) : null;
					}
					return null;
				case "getItemContainer":
					return container(inventory);
				default:
					return defaultValue(m.getReturnType());
			}
		});
		collector = new StructuredActivityCollector(client, id -> "item" + id, ledger, StructuredActivityRegistry.defaults());
		varbits.put(VarbitID.GOTR_ELEMENTAL_EARNED_THIS_GAME, 0);
		varbits.put(VarbitID.GOTR_CATALYTIC_EARNED_THIS_GAME, 0);
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

	private static ItemContainer container(Map<Integer, Long> items)
	{
		Item[] arr = new Item[items.size()];
		int i = 0;
		for (Map.Entry<Integer, Long> e : items.entrySet())
		{
			arr[i++] = new Item(e.getKey(), (int) (long) e.getValue());
		}
		return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(), new Class<?>[]{ItemContainer.class},
			(p, m, a) -> "getItems".equals(m.getName()) ? arr : defaultValue(m.getReturnType()));
	}

	private void enterGotr()
	{
		collector.onRegion(GOTR_REGION);
		events.clear();
	}

	private void energy(int elemental, int catalytic)
	{
		varbits.put(VarbitID.GOTR_ELEMENTAL_EARNED_THIS_GAME, elemental);
		varbits.put(VarbitID.GOTR_CATALYTIC_EARNED_THIS_GAME, catalytic);
		tick++;
		collector.trackGotrTick();
	}

	private void inventory(long... idQty)
	{
		inventory = new HashMap<>();
		for (int i = 0; i < idQty.length; i += 2)
		{
			if (idQty[i + 1] > 0)
			{
				inventory.put((int) idQty[i], idQty[i + 1]);
			}
		}
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, container(inventory)));
	}

	private void runecraftXp(int xp)
	{
		collector.onStatChanged(new StatChanged(Skill.RUNECRAFT, xp, 77, 77));
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

	// ---- CURRENT GAME ----------------------------------------------------

	@Test
	public void currentGame_powerFromTheHudScript_energyFromTheVarbits_keptThroughAShortGap_clearedAfterLeaving()
	{
		enterGotr();
		hudLoaded = true;
		collector.onGotrHudScript(new Object[]{5980, 280, 310, 620, 1000, 0, 0, 0, 0, 0, 0, 0});
		energy(280, 310);
		GotrGameState g = collector.getGotrGame();
		assertNotNull(g);
		assertEquals(Integer.valueOf(62), g.getGuardianPowerPercent());
		assertEquals(Integer.valueOf(280), g.getElementalEnergy());
		assertEquals(Integer.valueOf(310), g.getCatalyticEnergy());
		assertEquals(Integer.valueOf(590), g.getTotalEnergy());

		// Region load (stepping into an altar): the HUD is briefly absent.
		hudLoaded = false;
		energy(280, 310);
		assertEquals("a short HUD gap never collapses the current game", g, collector.getGotrGame());
		for (int i = 0; i < StructuredActivityCollector.GOTR_IN_GAME_GRACE_TICKS + 1; i++)
		{
			energy(280, 310);
		}
		assertNull("really left the game: never kept as stale state", collector.getGotrGame());
		hudLoaded = true;
		energy(0, 0);
		assertNull("power is unknown again until the HUD script reports it",
			collector.getGotrGame().getGuardianPowerPercent());
	}


	@Test
	public void hudScript_malformedArguments_areIgnored_otherScriptsNeverRead()
	{
		enterGotr();
		hudLoaded = true;
		collector.onGotrHudScript(new Object[]{5980, 1, 2});
		collector.onGotrHudScript(null);
		collector.onGotrHudScript(new Object[]{5980, 1, 2, "x", "y"});
		energy(5, 5);
		assertNull(collector.getGotrGame().getGuardianPowerPercent());
		assertEquals(Integer.valueOf(7), StructuredActivityCollector.intArg(new Object[]{"7"}, 0));
		assertNull(StructuredActivityCollector.intArg(new Object[]{"7"}, 3));
	}

	// ---- COMPLETION / FAILURE / SCORE ------------------------------------

	@Test
	public void wonGame_scoreThenCompletion_once_duplicateIgnored()
	{
		enterGotr();
		hudLoaded = true;
		energy(0, 0);
		energy(420, 380);
		collector.onGameMessage("Amount of rifts you have closed: 12.");
		collector.onGameMessage("Amount of rifts you have closed: 12.");
		List<EventPayloads.StructuredActivityScore> scores = payloads(EventType.STRUCTURED_ACTIVITY_SCORE);
		List<EventPayloads.StructuredActivityCompletion> done = payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION);
		assertEquals(1, scores.size());
		assertEquals(1, done.size());
		assertEquals(StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT, scores.get(0).getActivityKey());
		assertEquals(Integer.valueOf(800), scores.get(0).getPoints());
		assertTrue(scores.get(0).isObservedFromStart());
		assertEquals(Integer.valueOf(12), done.get(0).getCompletionCount());
	}

	@Test
	public void failedGame_neverCompletes_andItsEnergyNeverBecomesATrustedScore()
	{
		enterGotr();
		hudLoaded = true;
		energy(0, 0);
		energy(300, 300);
		collector.onGameMessage("The Great Guardian was defeated!");
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION).isEmpty());
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_SCORE).isEmpty());
		energy(300, 300);
		collector.onGameMessage("Amount of rifts you have closed: 13.");
		EventPayloads.StructuredActivityScore s = (EventPayloads.StructuredActivityScore) payloads(EventType.STRUCTURED_ACTIVITY_SCORE).get(0);
		assertFalse(s.isObservedFromStart());
	}

	// ---- REWARD POINTS ---------------------------------------------------

	@Test
	public void rewardPoints_baselineThenPerCurrencyGains_searchesNeverReduceEarned()
	{
		enterGotr();
		collector.onGameMessage("Total elemental energy: 41. Total catalytic energy: 39.");
		assertTrue("first balance is a baseline", payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY).isEmpty());
		collector.onGameMessage("You found some loot: 3 x Nature rune");
		collector.onGameMessage("Total elemental energy: 47. Total catalytic energy: 46.");
		List<EventPayloads.StructuredActivityCurrency> c = payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY);
		assertEquals(2, c.size());
		assertEquals(StructuredActivityCollector.GOTR_ELEMENTAL_POINTS, c.get(0).getCurrency());
		assertEquals(7, c.get(0).getGained());
		assertEquals(47, c.get(0).getBalance());
		assertEquals(StructuredActivityCollector.GOTR_CATALYTIC_POINTS, c.get(1).getCurrency());
		assertEquals(8, c.get(1).getGained());
	}

	@Test
	public void checkMessageBox_inTheTemple_isABaseline()
	{
		enterGotr();
		widgetTexts.put(InterfaceID.Messagebox.TEXT, "You have 39 catalytic energy and 41 elemental energy.");
		energy(0, 0);
		hudLoaded = true;
		energy(0, 0);
		widgetTexts.clear();
		collector.onGameMessage("Total elemental energy: 45. Total catalytic energy: 44.");
		List<EventPayloads.StructuredActivityCurrency> c = payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY);
		assertEquals(2, c.size());
		assertEquals(4, c.get(0).getGained());
		assertEquals(5, c.get(1).getGained());
	}

	// ---- RUNES CRAFTED ---------------------------------------------------

	@Test
	public void altarCraft_essenceConsumedPlusRunesPlusRunecraftXp_isExactProduction()
	{
		enterGotr();
		hudLoaded = true;
		energy(0, 0);
		runecraftXp(1_000_000);
		inventory(ESS, 24, ItemID.NATURERUNE, 500);
		tick++;
		runecraftXp(1_000_480);
		inventory(ItemID.NATURERUNE, 548);
		List<EventPayloads.StructuredActivityProduction> p = payloads(EventType.STRUCTURED_ACTIVITY_PRODUCTION);
		assertEquals(1, p.size());
		assertEquals(StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT, p.get(0).getActivityKey());
		assertEquals(1, p.get(0).getItems().size());
		assertEquals(ItemID.NATURERUNE, p.get(0).getItems().get(0).getItemId());
		assertEquals(48, p.get(0).getItems().get(0).getQuantity());
	}

	@Test
	public void rewardRunes_areLoot_neverCraftedRunes()
	{
		enterGotr();
		hudLoaded = true;
		energy(0, 0);
		runecraftXp(1_000_000);
		inventory(ESS, 10);
		collector.onGameMessage("You found some loot: 50 x Nature rune");
		inventory(ESS, 10, ItemID.NATURERUNE, 50);
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_PRODUCTION).isEmpty());
		assertEquals(1, payloads(EventType.STRUCTURED_REWARD).size());
	}

	@Test
	public void arbitraryRunePickup_inTheGame_isNotProduction()
	{
		enterGotr();
		hudLoaded = true;
		energy(0, 0);
		runecraftXp(1_000_000);
		inventory(ESS, 10);
		runecraftXp(1_000_100);
		inventory(ESS, 10, ItemID.AIRRUNE, 25);
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_PRODUCTION).isEmpty());
	}

	@Test
	public void logoutOrAccountSwitch_resetsEverything_noFabricatedGainsAfterwards()
	{
		enterGotr();
		hudLoaded = true;
		collector.onGotrHudScript(new Object[]{5980, 0, 0, 500, 1000});
		energy(0, 0);
		collector.onGameMessage("Total elemental energy: 10. Total catalytic energy: 10.");
		runecraftXp(1_000_000);
		inventory(ESS, 20);
		collector.onGameStateChanged(gameState(GameState.LOGIN_SCREEN));
		assertNull(collector.getGotrGame());
		enterGotr();
		collector.onGameMessage("Total elemental energy: 90. Total catalytic energy: 90.");
		runecraftXp(2_000_000);
		inventory(ItemID.AIRRUNE, 300);
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY).isEmpty());
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_PRODUCTION).isEmpty());

		collector.resetForAccountSwitch();
		assertNull(collector.getGotrGame());
	}

	@Test
	public void reEnable_disarm_reBaselinesGotrToo()
	{
		enterGotr();
		collector.onGameMessage("Total elemental energy: 10. Total catalytic energy: 10.");
		collector.disarmPendingClaim();
		collector.onGameMessage("Total elemental energy: 15. Total catalytic energy: 15.");
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY).isEmpty());
	}

	private static GameStateChanged gameState(GameState s)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(s);
		return e;
	}

	// ---- LIVE REGRESSION (2026-10-01): a won game showed "Games: 0" ----------

	private void chat(net.runelite.api.ChatMessageType type, String raw)
	{
		collector.onChatMessage(new net.runelite.api.events.ChatMessage(null, type, "", raw, "", 0));
	}

	/** The HUD script with the live argument layout: [script, elemE, catE, power, max, portal, activeElemental, activeCatalytic, guardians, 10, rotation, portalTicks]. */
	private void hud(int power, int max, int activeElemental, int activeCatalytic)
	{
		collector.onGotrHudScript(new Object[]{5980, 0, 0, power, max, 0, activeElemental, activeCatalytic, 9, 10, 20, -40});
	}

	/** The end-of-game chat exactly as the game sent it on 2026-10-01 (raw, tags included). */
	private void liveWinMessages()
	{
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "@mes_hl_gre@The Great Guardian successfully closed the rift!</col>");
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "Amount of rifts you have closed: <col=ff0000>87</col>.");
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "Elemental energy attuned: <col=ef1020>2</col>. Catalytic energy attuned: <col=ef1020>3</col>.");
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "Total elemental energy: <col=ef1020>2</col>. Total catalytic energy: <col=ef1020>9</col>.");
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "You've been awarded 3,105 bonus Runecraft XP for closing the rift.");
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "The Portal Guardians will keep their rifts open for another 30 seconds.");
	}

	@Test
	public void liveGame_wonGame_countsExactlyOnce_withItsScoreAndPoints_noRewardClaimNeeded()
	{
		enterGotr();
		hudLoaded = true;
		hud(0, 27000, 0, 0);
		energy(0, 0);
		hud(2740, 27400, 3, 2);
		energy(89, 9);
		// Altar trip: the HUD widget is briefly absent while the region loads.
		hudLoaded = false;
		energy(89, 9);
		assertNotNull("the current game survives the altar transition", collector.getGotrGame());
		hudLoaded = true;
		hud(30660, 31000, 2, 1);
		energy(207, 283);

		liveWinMessages();

		List<EventPayloads.StructuredActivityCompletion> done = payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION);
		assertEquals("Games +1 from the win itself -- no reward claim needed", 1, done.size());
		assertEquals(StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT, done.get(0).getActivityKey());
		assertEquals(Integer.valueOf(87), done.get(0).getCompletionCount());
		List<EventPayloads.StructuredActivityScore> scores = payloads(EventType.STRUCTURED_ACTIVITY_SCORE);
		assertEquals(1, scores.size());
		assertEquals("fully observed: final energy 207 + 283", Integer.valueOf(490), scores.get(0).getPoints());
		assertTrue(scores.get(0).isObservedFromStart());
		List<EventPayloads.StructuredActivityCurrency> points = payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY);
		assertEquals("the game's own award, exact even with no earlier balance", 2, points.size());
		assertEquals(StructuredActivityCollector.GOTR_ELEMENTAL_POINTS, points.get(0).getCurrency());
		assertEquals(2, points.get(0).getGained());
		assertEquals(StructuredActivityCollector.GOTR_CATALYTIC_POINTS, points.get(1).getCurrency());
		assertEquals(3, points.get(1).getGained());
		assertEquals(com.osrstelemetry.plugin.activity.GotrRewardWallet.of(2, 9), collector.getGotrWallet());
		assertNull("the game ended: no current game", collector.getGotrGame());

		// Closing phase ticks and the corroboration window pass: still one game.
		hud(0, 31000, 1, 6);
		for (int i = 0; i < 6; i++)
		{
			energy(207, 283);
		}
		assertNull("the ended game's values never come back", collector.getGotrGame());
		// The live reward pulls that followed: loot, never another game.
		chat(net.runelite.api.ChatMessageType.SPAM, "You found some loot: 474 x Water rune");
		chat(net.runelite.api.ChatMessageType.SPAM, "You found some loot: 16 x Abyssal pearls");
		assertEquals(1, payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION).size());
		assertEquals("two searches spend one of each", com.osrstelemetry.plugin.activity.GotrRewardWallet.of(0, 7), collector.getGotrWallet());

		// The next game starts: power rises from 0 again -> a fresh current game.
		hud(0, 27000, 0, 0);
		energy(0, 0);
		hud(2740, 27400, 1, 3);
		energy(0, 0);
		GotrGameState next = collector.getGotrGame();
		assertNotNull(next);
		assertEquals(Integer.valueOf(0), next.getTotalEnergy());
	}

	@Test
	public void winLineAlone_standsInForAMissingCount_once_andALateCountIsNotASecondGame()
	{
		enterGotr();
		hudLoaded = true;
		hud(1000, 30000, 4, 7);
		energy(0, 0);
		energy(300, 400);
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "@mes_hl_gre@The Great Guardian successfully closed the rift!</col>");
		assertTrue("waits for its count line first", payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION).isEmpty());
		for (int i = 0; i < StructuredActivityCollector.GOTR_WIN_CORROBORATION_TICKS; i++)
		{
			energy(300, 400);
		}
		List<EventPayloads.StructuredActivityCompletion> done = payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION);
		assertEquals(1, done.size());
		assertNull("count unknown, never invented", done.get(0).getCompletionCount());
		assertEquals(Integer.valueOf(700), ((EventPayloads.StructuredActivityScore) payloads(EventType.STRUCTURED_ACTIVITY_SCORE).get(0)).getPoints());
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "Amount of rifts you have closed: <col=ff0000>88</col>.");
		assertEquals("a late count for the same game is not a second game", 1, payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION).size());
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "Amount of rifts you have closed: <col=ff0000>88</col>.");
		assertEquals(1, payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION).size());
	}

	@Test
	public void lostGame_neverCounts_clearsTheCurrentGame_andTheNextGameStartsClean()
	{
		enterGotr();
		hudLoaded = true;
		hud(9000, 30000, 2, 5);
		energy(0, 0);
		energy(350, 250);
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "The Great Guardian was defeated!");
		for (int i = 0; i < 4; i++)
		{
			energy(350, 250);
		}
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION).isEmpty());
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_SCORE).isEmpty());
		assertNull("a lost game's values are cleared", collector.getGotrGame());
		hud(0, 27000, 0, 0);
		energy(0, 0);
		hud(2000, 27000, 3, 3);
		energy(40, 0);
		assertEquals(Integer.valueOf(40), collector.getGotrGame().getElementalEnergy());
	}

	@Test
	public void openRifts_fromTheHudScript_withThePlayersOwnEligibility()
	{
		enterGotr();
		hudLoaded = true;
		collector.setGotrAltarUsable(altar -> altar != com.osrstelemetry.plugin.activity.GotrAltar.DEATH);
		hud(5000, 30000, 3, 7);
		energy(100, 50);
		GotrGameState g = collector.getGotrGame();
		assertEquals(com.osrstelemetry.plugin.activity.GotrAltar.EARTH, g.getActiveElemental());
		assertEquals(com.osrstelemetry.plugin.activity.GotrAltar.DEATH, g.getActiveCatalytic());
		assertEquals(Boolean.TRUE, g.getElementalUsable());
		assertEquals(Boolean.FALSE, g.getCatalyticUsable());
		hud(5100, 30000, 0, 9);
		energy(100, 50);
		assertNull("out-of-range indices are unknown, never guessed", collector.getGotrGame().getActiveElemental());
		assertNull(collector.getGotrGame().getActiveCatalytic());
	}

	@Test
	public void rewardWallet_unknownUntilTheGameStatesIt_checkBox_searches_logoutForgets()
	{
		enterGotr();
		assertNull(collector.getGotrWallet());
		widgetTexts.put(InterfaceID.Messagebox.TEXT, "You have 27 catalytic energy and 31 elemental energy.");
		hudLoaded = true;
		energy(0, 0);
		widgetTexts.clear();
		assertEquals(com.osrstelemetry.plugin.activity.GotrRewardWallet.of(31, 27), collector.getGotrWallet());
		assertEquals(27, collector.getGotrWallet().searches());
		chat(net.runelite.api.ChatMessageType.SPAM, "You found some loot: 3 x Nature rune");
		assertEquals(com.osrstelemetry.plugin.activity.GotrRewardWallet.of(30, 26), collector.getGotrWallet());
		collector.onGameStateChanged(gameState(GameState.LOGIN_SCREEN));
		assertNull("unknown again after logout: points could change elsewhere", collector.getGotrWallet());
	}

	// ---- RUNES CRAFTED: rapid crafting + rune pouch -------------------------

	private static final int DEATH = ItemID.DEATHRUNE;
	private static final int BLOOD = ItemID.BLOODRUNE;
	/** Pouch slot type -> rune item (the game's RUNEPOUCH_RUNE enum is not available headless). */
	private static final Map<Integer, Integer> POUCH_TYPES = new HashMap<>();

	static
	{
		POUCH_TYPES.put(11, DEATH);
		POUCH_TYPES.put(12, BLOOD);
		POUCH_TYPES.put(13, ItemID.NATURERUNE);
	}

	private static final int[] POUCH_TYPE_VARBITS = {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4};
	private static final int[] POUCH_QTY_VARBITS = {VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2,
		VarbitID.RUNE_POUCH_QUANTITY_3, VarbitID.RUNE_POUCH_QUANTITY_4};

	/** Sets the pouch slots (type, quantity pairs; slot order) and fires the varbit change, as the client would. */
	private void pouch(int... typeQty)
	{
		for (int i = 0; i < POUCH_TYPE_VARBITS.length; i++)
		{
			varbits.put(POUCH_TYPE_VARBITS[i], i * 2 < typeQty.length ? typeQty[i * 2] : 0);
			varbits.put(POUCH_QTY_VARBITS[i], i * 2 < typeQty.length ? typeQty[i * 2 + 1] : 0);
		}
		net.runelite.api.events.VarbitChanged change = new net.runelite.api.events.VarbitChanged();
		change.setVarbitId(VarbitID.RUNE_POUCH_QUANTITY_1);
		collector.onVarbitChanged(change);
	}

	private void inGameWithPouch()
	{
		collector.setRunePouchRuneItem(type -> POUCH_TYPES.getOrDefault(type, -1));
		enterGotr();
		hudLoaded = true;
		energy(0, 0);
	}

	private Map<Integer, Long> produced()
	{
		Map<Integer, Long> out = new HashMap<>();
		for (Object o : payloads(EventType.STRUCTURED_ACTIVITY_PRODUCTION))
		{
			for (EventPayloads.ProducedItem item : ((EventPayloads.StructuredActivityProduction) o).getItems())
			{
				out.merge(item.getItemId(), (long) item.getQuantity(), Long::sum);
			}
		}
		return out;
	}

	@Test
	public void craftIntoAnExistingRunePouchStack_countsExactly()
	{
		inGameWithPouch();
		pouch(11, 800);
		runecraftXp(1_000_000);
		inventory(ESS, 28);
		tick++;
		runecraftXp(1_000_560);
		inventory();
		pouch(11, 856);
		assertEquals(Long.valueOf(56), produced().get(DEATH));
		assertEquals(1, produced().size());
	}

	@Test
	public void pouchStateNeverSeenChange_baselineComesFromTheInventoryEvent()
	{
		inGameWithPouch();
		varbits.put(VarbitID.RUNE_POUCH_TYPE_1, 11);
		varbits.put(VarbitID.RUNE_POUCH_QUANTITY_1, 800); // loaded at login, no change event since
		runecraftXp(1_000_000);
		inventory(ESS, 28);
		tick++;
		pouch(11, 856);
		runecraftXp(1_000_560);
		inventory();
		assertEquals(Long.valueOf(56), produced().get(DEATH));
	}

	@Test
	public void craftSplitBetweenInventoryAndPouch_countsTheWholeCraftOnce_inAnyEventOrder()
	{
		inGameWithPouch();
		pouch(11, 800);
		runecraftXp(1_000_000);
		inventory(ESS, 28);
		tick++;
		pouch(11, 860);                       // pouch first
		inventory(DEATH, 40);                 // then inventory
		runecraftXp(1_000_560);               // XP last
		assertEquals(Long.valueOf(100), produced().get(DEATH));

		events.clear();
		inventory(DEATH, 40, ESS, 28);        // essence pouch emptied
		tick++;
		runecraftXp(1_001_120);               // XP first
		inventory(DEATH, 80);
		tick++;
		pouch(11, 920);                       // pouch on the adjacent tick
		assertEquals(Long.valueOf(100), produced().get(DEATH));
	}

	@Test
	public void newRuneTypeIntoAnEmptyPouchSlot_andMultipleRuneTypes()
	{
		inGameWithPouch();
		pouch(11, 800);
		runecraftXp(1_000_000);
		inventory(ESS, 28, ItemID.NATURERUNE, 10);
		tick++;
		runecraftXp(1_000_300);
		inventory(ItemID.NATURERUNE, 40);
		pouch(11, 800, 12, 12);
		Map<Integer, Long> got = produced();
		assertEquals(Long.valueOf(12), got.get(BLOOD));
		assertEquals(Long.valueOf(30), got.get(ItemID.NATURERUNE));
		assertEquals(2, got.size());
	}

	@Test
	public void rapidCraft_refill_craft_withPouchOutput_bothExactlyOnce()
	{
		inGameWithPouch();
		pouch(11, 800);
		runecraftXp(1_000_000);
		inventory(ESS, 28);
		// craft A
		tick++;
		inventory();
		pouch(11, 830);
		runecraftXp(1_000_300);
		// refill from the essence pouch, craft B, XP coalesced/late
		tick++;
		inventory(ESS, 22);
		tick++;
		inventory();
		pouch(11, 856);
		runecraftXp(1_000_560);
		// craft C on the next tick, half in the inventory
		inventory(ESS, 10);
		tick++;
		inventory(DEATH, 4);
		pouch(11, 862);
		runecraftXp(1_000_660);
		assertEquals("30 + 26 + 10", Long.valueOf(66), produced().get(DEATH));
	}

	@Test
	public void rapidCraft_liveLike_bothXpUpdatesBeforeEitherInventory_bothBatchesCount()
	{
		inGameWithPouch();
		runecraftXp(1_000_000);
		inventory(ESS, 28);
		tick++;
		runecraftXp(1_000_300);
		tick++;
		runecraftXp(1_000_560);
		inventory(DEATH, 30);
		inventory(DEATH, 30, ESS, 26);
		tick++;
		inventory(DEATH, 56);
		assertEquals(Long.valueOf(56), produced().get(DEATH));
	}

	@Test
	public void runePouchTransfers_rewardsAndPickupsMovedIntoIt_neverCount()
	{
		inGameWithPouch();
		pouch(11, 800);
		runecraftXp(1_000_000);
		inventory(ESS, 10, DEATH, 100);
		tick++;
		inventory(DEATH, 100);                // essence into an essence pouch (consumed, no runes made)
		runecraftXp(1_000_050);
		inventory();                          // inventory -> pouch
		pouch(11, 900);
		tick += 3;
		pouch(11, 850);                       // pouch -> inventory
		inventory(DEATH, 50);
		collector.onGameMessage("You found some loot: 25 x Blood rune");
		inventory(DEATH, 50, BLOOD, 25);      // reward runes ...
		tick++;
		inventory(DEATH, 50);
		pouch(11, 850, 12, 25);               // ... moved into the pouch
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_PRODUCTION).isEmpty());
	}

	@Test
	public void runePouchBaseline_afterLoginReEnableOrAccountSwitch_neverCounts()
	{
		inGameWithPouch();
		pouch(11, 800);
		runecraftXp(1_000_000);
		inventory(ESS, 28);
		collector.onGameStateChanged(gameState(GameState.LOGIN_SCREEN));
		enterGotr();
		hudLoaded = true;
		energy(0, 0);
		runecraftXp(1_000_500);
		inventory();
		pouch(11, 900);
		assertTrue("login: first pouch/inventory/XP are baselines", payloads(EventType.STRUCTURED_ACTIVITY_PRODUCTION).isEmpty());

		collector.disarmPendingClaim();
		energy(0, 0);
		pouch(11, 1000);
		runecraftXp(1_000_900);
		inventory(ESS, 5);
		assertTrue("re-enable", payloads(EventType.STRUCTURED_ACTIVITY_PRODUCTION).isEmpty());

		collector.resetForAccountSwitch();
		enterGotr();
		hudLoaded = true;
		energy(0, 0);
		pouch(11, 2000);
		inventory();
		runecraftXp(5_000_000);
		assertTrue("account switch", payloads(EventType.STRUCTURED_ACTIVITY_PRODUCTION).isEmpty());
	}

	@Test
	public void runePouchVarbits_areTheNativeSixSlots_unknownTypesSkipped()
	{
		Map<Integer, Integer> v = new HashMap<>();
		v.put(VarbitID.RUNE_POUCH_TYPE_1, 11);
		v.put(VarbitID.RUNE_POUCH_QUANTITY_1, 800);
		v.put(VarbitID.RUNE_POUCH_TYPE_6, 13);
		v.put(VarbitID.RUNE_POUCH_QUANTITY_6, 5);
		v.put(VarbitID.RUNE_POUCH_TYPE_3, 99);
		v.put(VarbitID.RUNE_POUCH_QUANTITY_3, 7);
		Map<Integer, Long> read = com.osrstelemetry.plugin.activity.GotrRunes.readRunePouch(id -> v.getOrDefault(id, 0),
			type -> POUCH_TYPES.getOrDefault(type, -1));
		assertEquals(Long.valueOf(800), read.get(DEATH));
		assertEquals(Long.valueOf(5), read.get(ItemID.NATURERUNE));
		assertEquals(2, read.size());
		assertTrue(com.osrstelemetry.plugin.activity.GotrRunes.isRunePouchVarbit(VarbitID.RUNE_POUCH_QUANTITY_5));
		assertFalse(com.osrstelemetry.plugin.activity.GotrRunes.isRunePouchVarbit(VarbitID.GOTR_IS_PLAYING));
	}

	// ---- closed rift without contribution (live 2026-10-01 15:46:02) ------

	@Test
	public void riftClosedWithoutContribution_isNotAGame_cancelsTheBackupWin()
	{
		enterGotr();
		hudLoaded = true;
		hud(9000, 30000, 2, 5);
		energy(0, 0);
		energy(0, 0);
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "@mes_hl_gre@The Great Guardian successfully closed the rift!</col>");
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "You did not contribute enough to attune any elemental or catalytic energy.");
		for (int i = 0; i < 4; i++)
		{
			energy(0, 0);
		}
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION).isEmpty());
		assertTrue(payloads(EventType.STRUCTURED_ACTIVITY_SCORE).isEmpty());
	}

	// ---- portal talismans -> extra STRATEGY candidates --------------------

	@Test
	public void heldPortalTalisman_addsItsAltar_asATalismanCandidate_andGoesWhenUsed()
	{
		collector.setGotrAltarUsable(a -> true);
		enterGotr();
		hudLoaded = true;
		hud(9000, 30000, 2, 5);
		inventory(ESS, 10, ItemID.GOTR_PORTAL_TALISMAN_BLOOD, 1);
		energy(10, 10);
		List<com.osrstelemetry.plugin.activity.GotrCandidate> c = collector.getGotrGame().getCandidates();
		assertEquals(3, c.size());
		assertEquals(com.osrstelemetry.plugin.activity.GotrAltar.BLOOD, c.get(2).getAltar());
		assertEquals(com.osrstelemetry.plugin.activity.GotrCandidate.Access.TALISMAN, c.get(2).getAccess());
		assertEquals(Boolean.TRUE, c.get(2).getUsable());

		inventory(ESS, 10); // "The portal talisman shatters ..."
		energy(10, 10);
		assertEquals(2, collector.getGotrGame().getCandidates().size());
	}
}
