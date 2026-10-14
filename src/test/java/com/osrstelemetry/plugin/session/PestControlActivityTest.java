package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.activity.RecognitionState;
import java.time.Instant;
import org.junit.Test;

/**
 * Pest Control live-QA repair (2026-10-01): the island (10536) starts the
 * activity on entry, the outpost (10537) never does, combat stays owned,
 * and a won game is one "Games won" plus its exact commendation award.
 */
public class PestControlActivityTest extends StructuredActivityTestSupport
{
	private static final String PC = "pest_control";
	private static final int ISLAND = 10536;
	private static final int OUTPOST = 10537;
	private static final Instant T0 = Instant.parse("2026-10-02T03:30:00Z");

	private static SessionSignal outpost(Instant at)
	{
		// The outpost is not a registered region: the collector reports no activity.
		return SessionSignal.structuredActivityLocation(at, null, OUTPOST);
	}

	private static SessionSignal win(Instant at, int points)
	{
		return SessionSignal.structuredActivityCompletion(at, PC, null);
	}

	private static SessionSignal award(Instant at, int points)
	{
		return SessionSignal.structuredActivityCurrency(at, PC, "commendation_points", points);
	}

	private boolean isPc(Session s)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.STRUCTURED_ACTIVITY
			&& PC.equals(s.getActivityIdentity().getActivityKey());
	}

	/** A prior non-PC session (the live case was Slayer; any ordinary session shows the same rule). */
	private Session priorSession()
	{
		onTick(T0, xpAt("SLAYER", 200, T0), xpAt("ATTACK", 300, T0));
		Session prior = current();
		assertNotNull(prior);
		return prior;
	}

	@Test
	public void outpost_neverStartsPestControl()
	{
		Session prior = priorSession();
		Instant t = T0.plusSeconds(60);
		onTick(t, outpost(t));
		assertEquals("waiting at the outpost keeps the previous session", prior.getSessionId(), current().getSessionId());
		assertTrue(!isPc(current()));
	}

	@Test
	public void islandEntry_startsPestControlImmediately_elapsedFromEntry()
	{
		priorSession();
		Instant entry = T0.plusSeconds(120);
		onTick(entry, enter(PC, ISLAND, entry));
		assertTrue("entering the island is the game starting", isPc(current()));
		assertEquals("active time starts at entry, not at the first XP drop", entry, Instant.parse(current().getStartedAt()));
		assertEquals(RecognitionState.ACTIVE, recognition());
	}

	@Test
	public void combatXpOnTheIsland_isOwnedByPestControl()
	{
		priorSession();
		Instant entry = T0.plusSeconds(120);
		onTick(entry, enter(PC, ISLAND, entry));
		String id = current().getSessionId();
		for (int i = 1; i <= 4; i++)
		{
			Instant t = entry.plusSeconds(15L * i);
			onTick(t, xpAt(i % 2 == 0 ? "HITPOINTS" : "ATTACK", 120, t));
		}
		assertEquals(id, current().getSessionId());
		assertTrue(current().getAggregates().getXpGainedBySkill().containsKey("ATTACK"));
		assertTrue(current().getAggregates().getXpGainedBySkill().containsKey("HITPOINTS"));
	}

	@Test
	public void outpost_game_win_outpost_game_loss_lifecycle()
	{
		priorSession();
		Instant t = T0.plusSeconds(60);
		onTick(t, outpost(t));
		Instant entry = t.plusSeconds(60);
		onTick(entry, enter(PC, ISLAND, entry));
		String id = current().getSessionId();
		Instant fight = entry.plusSeconds(30);
		onTick(fight, xpAt("ATTACK", 400, fight));

		// Win: the completion and its award (the collector emits both from one deduped line).
		Instant end = entry.plusSeconds(120);
		onTick(end, win(end, 8), award(end, 8));
		Instant back = end.plusSeconds(1);
		onTick(back, outpost(back));
		assertEquals("still the same Pest Control session at the outpost", id, current().getSessionId());

		// Second game: lost (no award line at all).
		Instant entry2 = back.plusSeconds(40);
		onTick(entry2, enter(PC, ISLAND, entry2));
		Instant fight2 = entry2.plusSeconds(30);
		onTick(fight2, xpAt("ATTACK", 300, fight2));
		Instant back2 = entry2.plusSeconds(150);
		onTick(back2, outpost(back2));

		assertEquals("both games are one Pest Control session", id, current().getSessionId());
		SessionAggregates.ActivityCompletionCount c = current().getAggregates().getActivityCompletions();
		assertEquals("only the won game counts", 1, c.getSessionCompletions());
		assertEquals(8L, current().getAggregates().getActivityStats().getCurrencyGained());
		assertEquals(Long.valueOf(8), current().getAggregates().getActivityStats().getCurrencyGainedByKey().get("commendation_points"));
	}

	@Test
	public void lossOnly_isStillAPestControlSession_withNoGamesWonAndNoPoints()
	{
		Instant entry = T0;
		onTick(entry, enter(PC, ISLAND, entry));
		Instant fight = entry.plusSeconds(30);
		onTick(fight, xpAt("ATTACK", 300, fight));
		assertTrue(isPc(current()));
		assertNull(current().getAggregates().getActivityCompletions());
		assertNull(current().getAggregates().getActivityStats());
	}

	@Test
	public void otherActivities_entryStillStartsNothing()
	{
		for (String[] r : new String[][] {{"guardians_of_the_rift", "14484"}, {"tempoross", "12076"}, {"tempoross", "12588"},
			{"wintertodt", "6462"}, {"wintertodt", "6461"}, {"giants_foundry", "13491"}, {"barrows", "14231"}, {"barrows", "14131"}})
		{
			try
			{
				fresh();
			}
			catch (Exception e)
			{
				throw new AssertionError(e);
			}
			onTick(T0, enter(r[0], Integer.parseInt(r[1]), T0));
			assertNull(r[0] + "@" + r[1] + ": location alone never starts a session", current());
		}
	}

	private void fresh() throws Exception
	{
		tearDownCoordinator();
		setUpCoordinator();
	}
}
