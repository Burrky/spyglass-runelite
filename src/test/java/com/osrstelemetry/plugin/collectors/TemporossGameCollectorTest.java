package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.activity.TemporossGameState;
import com.osrstelemetry.plugin.activity.TemporossGameTracker;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.events.TelemetryEventListener;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tempoross native game state, read by Spyglass itself from Jagex's own
 * HUD (interface 437) and varbits -- no other plugin involved. The bars'
 * text lives on the *_TITLE components (the ENERGY / ESSENCE /
 * STORM_INTENSITY components are text-less layers: reading those was the
 * live "bars never show" bug). The HUD gate is "interface loaded", so a
 * plugin that hides the vanilla HUD cannot blank Spyglass. Plus the
 * session-summary evidence this collector emits: permits EARNED (positive
 * changes only, after a post-login settle) and each successful game's
 * final points with its provenance.
 */
public class TemporossGameCollectorTest
{
	private static final long ACCOUNT = 700_900_977L;
	private static final int ARENA = 12076;
	private static final int COVE = 12588;
	private static final int LUMBRIDGE = 12850;

	private Path testRoot;
	private EventLedger ledger;
	private StructuredActivityCollector collector;
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private final Map<Integer, String> widgetTexts = new HashMap<>();
	private boolean hudUp;
	private boolean hudHidden;
	private final List<EventType> events = new ArrayList<>();
	private final List<Object> payloads = new ArrayList<>();
	private final AtomicInteger changes = new AtomicInteger();
	private GameState gameState = GameState.LOGGED_IN;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("tempoross-game-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		ledger = new EventLedger(new Gson());
		ledger.start();
		ledger.addListener((TelemetryEventListener) (account, type, payload, at) ->
		{
			synchronized (events)
			{
				events.add(type);
				payloads.add(payload);
			}
		});
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getAccountHash":
						return ACCOUNT;
					case "getGameState":
						return gameState;
					case "getVarbitValue":
						return varbits.getOrDefault((Integer) args[0], 0);
					case "getWidget":
						if (args.length == 1 && args[0] instanceof Integer)
						{
							return widget((Integer) args[0]);
						}
						return null;
					default:
						return defaultValue(method.getReturnType());
				}
			});
		collector = new StructuredActivityCollector(client, id -> "item" + id, ledger, StructuredActivityRegistry.defaults());
		collector.setProgressListener(changes::incrementAndGet);
	}

	private Widget widget(int id)
	{
		if (!hudUp || (id != InterfaceID.TemporossHud.CONTENT && !widgetTexts.containsKey(id)))
		{
			return null;
		}
		return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[] {Widget.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getText":
						return widgetTexts.get(id);
					case "isHidden":
						// A plugin hiding the vanilla HUD hides CONTENT and, through it, every child.
						return hudHidden;
					default:
						return defaultValue(method.getReturnType());
				}
			});
	}

	private static Object defaultValue(Class<?> rt)
	{
		if (rt == boolean.class)
		{
			return false;
		}
		if (rt.isPrimitive() && rt != void.class)
		{
			return rt == long.class ? (Object) 0L : (Object) 0;
		}
		return null;
	}

	@After
	public void tearDown() throws Exception
	{
		ledger.shutdown();
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	/** The real HUD: text-less bar layers, "Name: NN%" on each title, points in the varbit. */
	private void hud(String energy, String essence, String storm, int points)
	{
		hudUp = true;
		widgetTexts.put(InterfaceID.TemporossHud.ENERGY, "");
		widgetTexts.put(InterfaceID.TemporossHud.ESSENCE, "");
		widgetTexts.put(InterfaceID.TemporossHud.STORM_INTENSITY, "");
		widgetTexts.put(InterfaceID.TemporossHud.ENERGY_TITLE, energy);
		widgetTexts.put(InterfaceID.TemporossHud.ESSENCE_TITLE, essence);
		widgetTexts.put(InterfaceID.TemporossHud.STORM_INTENSITY_TITLE, storm);
		varbits.put(VarbitID.TEMPOROSS_CURRENT_POINTS, points);
	}

	private void points(int value)
	{
		varbits.put(VarbitID.TEMPOROSS_CURRENT_POINTS, value);
	}

	private void permits(int value)
	{
		varbits.put(VarbitID.TEMPOROSS_REWARDPERMITS, value);
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			collector.trackTemporossTick();
		}
	}

	private List<Object> payloadsOf(EventType type)
	{
		List<Object> out = new ArrayList<>();
		synchronized (events)
		{
			for (int i = 0; i < events.size(); i++)
			{
				if (events.get(i) == type)
				{
					out.add(payloads.get(i));
				}
			}
		}
		return out;
	}

	private int nonLocationEvents()
	{
		synchronized (events)
		{
			int n = 0;
			for (EventType t : events)
			{
				if (t != EventType.STRUCTURED_ACTIVITY_STATE)
				{
					n++;
				}
			}
			return n;
		}
	}

	private void logout()
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(GameState.LOGIN_SCREEN);
		gameState = GameState.LOGIN_SCREEN;
		collector.onGameStateChanged(e);
		gameState = GameState.LOGGED_IN;
	}

	// ------------------------------------------------------------------ native HUD values

	@Test
	public void inArenaWithHud_readsTheGamesOwnValues_fromTheTitleText()
	{
		collector.onRegion(ARENA);
		hud("Energy: 70%", "Essence: 100%", "Storm Intensity: 0%", 1_450);
		collector.readTemporossGame();
		TemporossGameState s = collector.getTemporossGame();
		assertNotNull(s);
		assertEquals(Integer.valueOf(70), s.getEnergy());
		assertEquals(Integer.valueOf(100), s.getEssence());
		assertEquals("0% is a real value, not missing", Integer.valueOf(0), s.getStorm());
		assertEquals(Integer.valueOf(1_450), s.getPoints());
		assertEquals(1, changes.get());
	}

	// Root-cause regression: the bar LAYERS carry no text; reading them gave nothing.
	@Test
	public void barLayersAreTextless_onlyTheTitlesCarryTheValues()
	{
		collector.onRegion(ARENA);
		hud("Energy: 55%", "Essence: 40%", "Storm Intensity: 12%", 0);
		widgetTexts.remove(InterfaceID.TemporossHud.ENERGY_TITLE);
		collector.readTemporossGame();
		assertNull("a missing title is omitted, never read from the layer", collector.getTemporossGame().getEnergy());
		assertEquals(Integer.valueOf(40), collector.getTemporossGame().getEssence());
	}

	@Test
	public void unchangedReads_doNotRepublish_realChangesDo()
	{
		collector.onRegion(ARENA);
		hud("Energy: 87%", "Essence: 100%", "Storm Intensity: 12%", 0);
		collector.readTemporossGame();
		collector.readTemporossGame();
		collector.readTemporossGame();
		assertEquals("no per-tick churn", 1, changes.get());
		hud("Energy: 80%", "Essence: 100%", "Storm Intensity: 12%", 0);
		collector.readTemporossGame();
		assertEquals(2, changes.get());
	}

	@Test
	public void malformedOrOutOfRangeText_isOmitted_neverGuessed()
	{
		collector.onRegion(ARENA);
		hud("Energy", "Essence: ???", "Storm Intensity: 250%", 30);
		collector.readTemporossGame();
		TemporossGameState s = collector.getTemporossGame();
		assertNull(s.getEnergy());
		assertNull(s.getEssence());
		assertNull("out of range is not a percentage", s.getStorm());
		assertEquals(Integer.valueOf(30), s.getPoints());
	}

	// Independence: another plugin hiding the vanilla HUD must not blank Spyglass.
	@Test
	public void hudHiddenByAnotherPlugin_isStillReadNatively_absentHudMeansNoGame()
	{
		collector.onRegion(ARENA);
		hud("Energy: 87%", "Essence: 100%", "Storm Intensity: 12%", 10);
		hudHidden = true;
		collector.readTemporossGame();
		assertEquals(Integer.valueOf(87), collector.getTemporossGame().getEnergy());
		hudHidden = false;
		hudUp = false; // interface unloaded: no game
		collector.readTemporossGame();
		assertNull(collector.getTemporossGame());
	}

	@Test
	public void rewardCoveAndOtherPlaces_neverShowAGame_andLeavingClearsIt()
	{
		collector.onRegion(ARENA);
		hud("Energy: 50%", "Essence: 60%", "Storm Intensity: 70%", 99);
		collector.readTemporossGame();
		assertNotNull(collector.getTemporossGame());
		hudUp = false; // the cove has no game HUD
		collector.onRegion(COVE);
		collector.readTemporossGame();
		assertNull(collector.getTemporossGame());
		hud("Energy: 50%", "Essence: 60%", "Storm Intensity: 70%", 99);
		collector.onRegion(LUMBRIDGE);
		collector.readTemporossGame();
		assertNull("not the Tempoross location: never read", collector.getTemporossGame());
	}

	@Test
	public void logoutAndAccountSwitch_forgetTheGame()
	{
		collector.onRegion(ARENA);
		hud("Energy: 50%", "Essence: 60%", "Storm Intensity: 70%", 99);
		collector.readTemporossGame();
		logout();
		assertNull(collector.getTemporossGame());

		collector.onRegion(ARENA); // mid-game login: the next read shows the real game at once
		collector.readTemporossGame();
		assertNotNull(collector.getTemporossGame());
		collector.resetForAccountSwitch();
		assertNull(collector.getTemporossGame());
		assertEquals("HUD mirroring alone logs nothing", 0, nonLocationEvents());
	}

	// ------------------------------------------------------------------ permits earned

	@Test
	public void permits_existingBalanceIsABaseline_gainsAreEmitted_spendingNever()
	{
		permits(40); // balance synced at login: never "earned"
		collector.onRegion(ARENA);
		ticks(TemporossGameTracker.SETTLE_TICKS + 2);
		assertTrue(payloadsOf(EventType.STRUCTURED_ACTIVITY_CURRENCY).isEmpty());

		permits(44);
		ticks(1);
		permits(40 + 4 + 3);
		ticks(1);
		collector.onRegion(COVE);
		permits(2); // spent at the reward pool
		ticks(3);
		List<Object> gains = payloadsOf(EventType.STRUCTURED_ACTIVITY_CURRENCY);
		assertEquals(2, gains.size());
		EventPayloads.StructuredActivityCurrency first = (EventPayloads.StructuredActivityCurrency) gains.get(0);
		assertEquals("tempoross", first.getActivityKey());
		assertEquals(4, first.getGained());
		assertEquals(44, first.getBalance());
		assertEquals(3, ((EventPayloads.StructuredActivityCurrency) gains.get(1)).getGained());

		permits(9); // next game after spending
		ticks(1);
		assertEquals(3, payloadsOf(EventType.STRUCTURED_ACTIVITY_CURRENCY).size());
		assertEquals(7, ((EventPayloads.StructuredActivityCurrency) payloadsOf(EventType.STRUCTURED_ACTIVITY_CURRENCY).get(2)).getGained());
	}

	@Test
	public void permits_loginSyncInsideTheCove_andOutsideTheLocation_areNeverGains()
	{
		collector.onRegion(COVE);
		permits(0);
		ticks(1);
		permits(120); // varp sync right after login / re-enable
		ticks(TemporossGameTracker.SETTLE_TICKS + 3);
		collector.onRegion(LUMBRIDGE);
		permits(500); // anything outside Tempoross is not observed
		ticks(3);
		collector.onRegion(COVE);
		ticks(2); // re-entry re-baselines
		assertTrue(payloadsOf(EventType.STRUCTURED_ACTIVITY_CURRENCY).isEmpty());

		collector.disarmPendingClaim(); // plugin re-enable
		permits(530);
		ticks(1);
		assertTrue("re-enable re-settles", payloadsOf(EventType.STRUCTURED_ACTIVITY_CURRENCY).isEmpty());
	}

	// ------------------------------------------------------------------ final points

	@Test
	public void successfulGameSeenFromStart_emitsItsFinalPoints_once()
	{
		collector.onRegion(ARENA);
		hud("Energy: 100%", "Essence: 100%", "Storm Intensity: 0%", 0);
		ticks(1);
		points(1_200);
		ticks(1);
		points(3_400);
		ticks(1);
		collector.onGameMessage("Your Tempoross kill count is: 12.");
		collector.onGameMessage("Your Tempoross kill count is: 12."); // same evidence again
		List<Object> scores = payloadsOf(EventType.STRUCTURED_ACTIVITY_SCORE);
		assertEquals(1, scores.size());
		EventPayloads.StructuredActivityScore s = (EventPayloads.StructuredActivityScore) scores.get(0);
		assertEquals("tempoross", s.getActivityKey());
		assertEquals(Integer.valueOf(12), s.getCompletionCount());
		assertEquals(Integer.valueOf(3_400), s.getPoints());
		assertTrue(s.isObservedFromStart());
	}

	@Test
	public void finalPoints_surviveTheHudClosingAndTheVarbitResetting_beforeTheMessage()
	{
		collector.onRegion(ARENA);
		hud("Energy: 100%", "Essence: 100%", "Storm Intensity: 0%", 0);
		ticks(1);
		points(2_800);
		ticks(1);
		hudUp = false;
		points(0);
		ticks(1);
		collector.onGameMessage("Your Tempoross kill count is: 13");
		EventPayloads.StructuredActivityScore s = (EventPayloads.StructuredActivityScore) payloadsOf(EventType.STRUCTURED_ACTIVITY_SCORE).get(0);
		assertEquals(Integer.valueOf(2_800), s.getPoints());
		assertTrue(s.isObservedFromStart());
	}

	@Test
	public void midGameAttach_isPartial_andAFailedGameEmitsNoScore()
	{
		collector.onRegion(ARENA);
		hud("Energy: 60%", "Essence: 50%", "Storm Intensity: 20%", 1_500); // enabled mid-game
		ticks(1);
		points(2_000);
		ticks(1);
		collector.onGameMessage("Your Tempoross kill count is: 20");
		EventPayloads.StructuredActivityScore partial = (EventPayloads.StructuredActivityScore) payloadsOf(EventType.STRUCTURED_ACTIVITY_SCORE).get(0);
		assertFalse("joined mid-game: not trusted for Avg/Best", partial.isObservedFromStart());
		assertEquals(Integer.valueOf(2_000), partial.getPoints());

		// Next game seen from its start, then FAILED: the HUD closes, no kill-count message.
		hudUp = false;
		ticks(1);
		hud("Energy: 100%", "Essence: 100%", "Storm Intensity: 0%", 0);
		ticks(1);
		points(900);
		ticks(1);
		hudUp = false;
		ticks(3);
		assertEquals("a failed game has no score", 1, payloadsOf(EventType.STRUCTURED_ACTIVITY_SCORE).size());
	}

	@Test
	public void unrelatedMessages_andLogout_neverScore()
	{
		collector.onRegion(ARENA);
		hud("Energy: 100%", "Essence: 100%", "Storm Intensity: 0%", 0);
		ticks(1);
		points(3_000);
		ticks(1);
		collector.onGameMessage("Your Zulrah kill count is: 12.");
		logout();
		collector.onRegion(ARENA);
		hud("Energy: 100%", "Essence: 100%", "Storm Intensity: 0%", 3_000);
		ticks(1);
		collector.onGameMessage("Your Tempoross kill count is: 14.");
		EventPayloads.StructuredActivityScore s = (EventPayloads.StructuredActivityScore) payloadsOf(EventType.STRUCTURED_ACTIVITY_SCORE).get(0);
		assertFalse("after a relog the start was not observed", s.isObservedFromStart());
	}
}
