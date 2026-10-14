package com.osrstelemetry.plugin.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Before;
import org.junit.Test;

/**
 * Wintertodt round lifecycle and native HUD interpretation (pure): phase from
 * the overlay + respawn timer, round start / end, complete vs partial rounds,
 * corroborated action counting and the subdue's points.
 */
public class WintertodtRoundTrackerTest
{
	private static final Instant T = Instant.parse("2026-10-02T15:00:00Z");
	private WintertodtRoundTracker t;
	private int tick;

	@Before
	public void setUp()
	{
		t = new WintertodtRoundTracker();
		tick = 1000;
	}

	/** One tick in the arena with the HUD up. */
	private WintertodtRoundTracker.RoundEnd hud(Integer energy, int points, int respawn)
	{
		tick++;
		return t.onTick(tick, T.plusMillis(tick * 600L), true, true, "Warmth: 80%",
			energy == null ? "The Wintertodt returns in: 0:30" : "Wintertodt's Energy: " + energy + "%", "Points<br>" + points, respawn, 12);
	}

	private void intermission()
	{
		hud(null, 0, 40);
	}

	@Test
	public void phase_roundWhileEnergyLive_intermissionWhileTheTimerRuns()
	{
		intermission();
		assertEquals(WintertodtState.Phase.INTERMISSION, t.state().getPhase());
		assertEquals("RuneLite's own reading: value x 30 / 50", Integer.valueOf(24), t.state().getCountdownSeconds());
		hud(100, 0, 0);
		assertEquals(WintertodtState.Phase.ROUND, t.state().getPhase());
		assertNull(t.state().getCountdownSeconds());
		assertEquals(Integer.valueOf(80), t.state().getWarmthPercent());
		assertEquals(Integer.valueOf(100), t.state().getEnergyPercent());
		assertEquals(Integer.valueOf(0), t.state().getPoints());
	}

	@Test
	public void outsideTheArena_orWithoutTheHud_thereIsNoState()
	{
		assertNull(t.state());
		t.onTick(1, T, false, false, null, null, null, 0, 0);
		assertNull(t.state());
	}

	@Test
	public void roundSeenFromItsStart_toTheNextIntermission_isComplete()
	{
		intermission();
		hud(100, 0, 0);
		Instant started = t.state().getRoundStartedAt();
		assertNotNull("seen from its start", started);
		for (int i = 0; i < 299; i++)
		{
			hud(50, 300, 0);
		}
		WintertodtRoundTracker.RoundEnd end = hud(0, 620, 0);
		assertNotNull(end);
		assertTrue(end.isComplete());
		assertEquals(300 * 600L, end.getMillis());
		assertEquals(Integer.valueOf(300), end.getPoints());
		assertEquals(WintertodtState.Phase.INTERMISSION, t.state().getPhase());
	}

	@Test
	public void roundJoinedPartWay_isPartial_noStartTime()
	{
		hud(60, 120, 0); // first sight is mid-round (login / world hop)
		assertNull(t.state().getRoundStartedAt());
		assertNotNull("but its time still counts", t.state().getRoundOpenedAt());
		hud(40, 200, 0);
		WintertodtRoundTracker.RoundEnd end = hud(null, 0, 50);
		assertFalse(end.isComplete());
		assertTrue(end.getMillis() > 0);
	}

	@Test
	public void leavingMidRound_endsItPartial()
	{
		intermission();
		hud(100, 0, 0);
		hud(80, 50, 0);
		tick++;
		WintertodtRoundTracker.RoundEnd end = t.onTick(tick, T, false, false, null, null, null, 0, 0);
		assertNotNull(end);
		assertFalse("left before the end: never in Avg Round", end.isComplete());
		assertNull(t.state());
	}

	@Test
	public void subduePoints_belongToTheRoundJustEnded_once()
	{
		intermission();
		hud(100, 0, 0);
		hud(10, 740, 0);
		hud(null, 0, 50);
		assertEquals(Integer.valueOf(740), t.pointsForSubdue(tick + 1));
		assertNull("a repeated line never scores twice", t.pointsForSubdue(tick + 2));
	}

	@Test
	public void subdueLongAfterTheRound_isNotScored()
	{
		intermission();
		hud(100, 0, 0);
		hud(10, 740, 0);
		hud(null, 0, 50);
		assertNull(t.pointsForSubdue(tick + WintertodtRoundTracker.LATE_SUBDUE_TICKS + 1));
	}

	// ---- actions ---------------------------------------------------------------------

	private void inv(int roots, int kindling, int doses)
	{
		t.onInventory(tick, roots, kindling, doses, true);
	}

	@Test
	public void chopping_andFletching_areInventoryTransitions()
	{
		intermission();
		hud(100, 0, 0);
		inv(0, 0, 0);
		inv(3, 0, 0);
		assertEquals(WintertodtState.Action.CHOPPING, t.state().getAction());
		inv(2, 1, 0);
		assertEquals(WintertodtState.Action.FLETCHING, t.state().getAction());
		WintertodtRoundTracker.RoundEnd end = hud(0, 0, 50);
		assertEquals(Integer.valueOf(3), end.getActions().get(WintertodtRoundTracker.CHOPPED));
		assertEquals(Integer.valueOf(1), end.getActions().get(WintertodtRoundTracker.FLETCHED));
	}

	@Test
	public void feeding_countsOnlyWithTheMatchingPointGain()
	{
		intermission();
		hud(100, 0, 0);
		inv(5, 4, 0);
		inv(3, 4, 0); // two roots gone ...
		hud(90, 20, 0); // ... and +20 points: fed
		assertEquals(WintertodtState.Action.FEEDING_ROOTS, t.state().getAction());
		inv(3, 2, 0);
		hud(90, 70, 0); // +50 = two kindling
		assertEquals(WintertodtState.Action.FEEDING_KINDLING, t.state().getAction());
		inv(2, 2, 0); // a root dropped: no points follow
		for (int i = 0; i < 4; i++)
		{
			hud(90, 70, 0);
		}
		WintertodtRoundTracker.RoundEnd end = hud(0, 70, 50);
		assertEquals(Integer.valueOf(2), end.getActions().get(WintertodtRoundTracker.ROOTS_FED));
		assertEquals(Integer.valueOf(2), end.getActions().get(WintertodtRoundTracker.KINDLING_FED));
	}

	@Test
	public void pointsBeforeInventory_inTheSameWindow_stillMatch()
	{
		intermission();
		hud(100, 0, 0);
		inv(4, 0, 0);
		hud(90, 10, 0);
		inv(3, 0, 0);
		WintertodtRoundTracker.RoundEnd end = hud(0, 10, 50);
		assertEquals(Integer.valueOf(1), end.getActions().get(WintertodtRoundTracker.ROOTS_FED));
	}

	@Test
	public void lighting_repairing_andHealing()
	{
		intermission();
		hud(100, 0, 0);
		inv(0, 0, 4);
		t.onChat(tick, "You light the brazier.");
		hud(90, 25, 0);
		assertEquals(WintertodtState.Action.LIGHTING, t.state().getAction());
		t.onChat(tick, "You fix the brazier.");
		hud(90, 50, 0);
		assertEquals(WintertodtState.Action.REPAIRING, t.state().getAction());
		inv(0, 0, 3);
		hud(90, 125, 0);
		assertEquals(WintertodtState.Action.HEALING, t.state().getAction());
		inv(0, 0, 2); // drank a dose: no +75 follows
		for (int i = 0; i < 4; i++)
		{
			hud(90, 125, 0);
		}
		WintertodtRoundTracker.RoundEnd end = hud(0, 125, 50);
		assertEquals(Integer.valueOf(1), end.getActions().get(WintertodtRoundTracker.LIT));
		assertEquals(Integer.valueOf(1), end.getActions().get(WintertodtRoundTracker.REPAIRED));
		assertEquals(Integer.valueOf(1), end.getActions().get(WintertodtRoundTracker.HEALED));
	}

	@Test
	public void action_clearsAfterItsTimeout_neverStale()
	{
		intermission();
		hud(100, 0, 0);
		inv(0, 0, 0);
		inv(2, 0, 0);
		for (int i = 0; i <= WintertodtRoundTracker.ACTION_TICKS; i++)
		{
			hud(90, 0, 0);
		}
		assertNull(t.state().getAction());
	}

	@Test
	public void firstInventory_isOnlyABaseline()
	{
		intermission();
		hud(100, 0, 0);
		inv(28, 0, 0);
		assertNull("a full inventory at login was not chopped now", t.state().getAction());
	}

	// ---- parsing -------------------------------------------------------------------------

	@Test
	public void nativeTexts_parse_andRejectNonsense()
	{
		assertEquals(Integer.valueOf(86), WintertodtRoundTracker.percentOf("<col=ffffff>Warmth: 86%</col>"));
		assertEquals(Integer.valueOf(0), WintertodtRoundTracker.percentOf("Wintertodt's Energy: 0%"));
		assertNull(WintertodtRoundTracker.percentOf("Wintertodt's Energy: 140%"));
		assertNull(WintertodtRoundTracker.percentOf("Energy"));
		assertNull(WintertodtRoundTracker.percentOf(null));
		assertEquals(Integer.valueOf(1234), WintertodtRoundTracker.numberOf("Points<br>1,234"));
		assertNull(WintertodtRoundTracker.numberOf("Points"));
		assertEquals(Integer.valueOf(30), WintertodtRoundTracker.countdownSeconds(50, null));
		assertEquals(Integer.valueOf(43), WintertodtRoundTracker.countdownSeconds(0, "The Wintertodt returns in: 0:43"));
		assertNull(WintertodtRoundTracker.countdownSeconds(0, "Wintertodt's Energy: 50%"));
	}

	@Test
	public void outOfRangeHud_isUnknown_andNoRoundStarts()
	{
		tick++;
		t.onTick(tick, T, true, true, "Warmth: 900%", "Wintertodt's Energy: 999%", "Points<br>x", 0, -5);
		WintertodtState s = t.state();
		assertEquals(WintertodtState.Phase.INTERMISSION, s.getPhase());
		assertNull(s.getWarmthPercent());
		assertNull(s.getEnergyPercent());
		assertNull(s.getCartRewards());
		assertFalse(t.isInRound());
	}
}
