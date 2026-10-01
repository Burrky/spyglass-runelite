package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.time.Instant;
import org.junit.Test;

/**
 * Tempoross session-summary stats end to end through the real session
 * pipeline: permits EARNED (sum of positive gains; spending never reaches
 * the session), and Avg / Best final points over successful games that
 * were observed from their start. Stats only ever land on the Tempoross
 * session itself, survive a plugin restart with the session, and never
 * include a failed or partial game.
 */
public class TemporossSessionStatsTest extends StructuredActivityTestSupport
{
	private static final String TEMPOROSS = "tempoross";
	private static final int ARENA = 12076;
	private static final int COVE = 12588;
	private static final Instant T0 = Instant.parse("2026-03-02T12:00:00Z");

	private static boolean isTempoross(Session s)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.STRUCTURED_ACTIVITY
			&& TEMPOROSS.equals(s.getActivityIdentity().getActivityKey());
	}

	/** Enter the arena and fish: the Tempoross session is current. */
	private Session temporossSession()
	{
		return temporossSession(T0);
	}

	private Session temporossSession(Instant t)
	{
		onTick(t, enter(TEMPOROSS, ARENA, t));
		onTick(t.plusSeconds(20), xpAt("FISHING", 200, t.plusSeconds(20)));
		assertTrue(isTempoross(current()));
		return current();
	}

	/** One successful game: the kill-count completion plus its score, on the same tick. */
	private void wonGame(int killCount, Integer points, boolean fromStart, Instant at)
	{
		onTick(at, SessionSignal.bossKill(at, "Tempoross", killCount),
			SessionSignal.structuredActivityScore(at, TEMPOROSS, killCount, points, fromStart));
	}

	private void permits(long gained, Instant at)
	{
		onTick(at, SessionSignal.structuredActivityCurrency(at, TEMPOROSS, gained));
	}

	private SessionAggregates.ActivitySessionStats stats()
	{
		return current().getAggregates().getActivityStats();
	}

	// Part 21.1 / 21.3 / 21.4: permits earned per game accumulate; nothing before the session.
	@Test
	public void permitsEarned_accumulateAcrossGames()
	{
		temporossSession();
		wonGame(1, 3_000, true, T0.plusSeconds(300));
		permits(4, T0.plusSeconds(301));
		wonGame(2, 3_600, true, T0.plusSeconds(700));
		permits(5, T0.plusSeconds(701));
		assertEquals(9L, stats().getCurrencyGained());
		assertEquals(2, current().getAggregates().getActivityCompletions().getSessionCompletions());
	}

	// Part 21.2: a spend never reaches the session; a non-positive amount changes nothing.
	@Test
	public void spending_neverReducesEarned()
	{
		temporossSession();
		permits(6, T0.plusSeconds(300));
		onTick(T0.plusSeconds(310), enter(TEMPOROSS, COVE, T0.plusSeconds(310)));
		permits(0, T0.plusSeconds(320));
		permits(-6, T0.plusSeconds(330));
		assertEquals(6L, stats().getCurrencyGained());
	}

	// Part 21.5 / 21.7 / 21.8: Avg and Best over trusted final points.
	@Test
	public void avgAndBest_fromTrustedFinalPoints()
	{
		temporossSession();
		wonGame(10, 4_200, true, T0.plusSeconds(300));
		wonGame(11, 5_670, true, T0.plusSeconds(700));
		wonGame(12, 4_890, true, T0.plusSeconds(1_100));
		assertEquals(3, stats().getScoredCompletions());
		assertEquals(Long.valueOf(4_920L), stats().averageScore());
		assertEquals(Integer.valueOf(5_670), stats().getBestScore());
	}

	// Part 21.6 / 19: a failed game has no completion and no score -- nothing moves.
	@Test
	public void failedGame_countsNothing()
	{
		temporossSession();
		wonGame(1, 3_000, true, T0.plusSeconds(300));
		// Failed game: fishing XP only, no kill count, no score, no permits.
		onTick(T0.plusSeconds(500), xpAt("FISHING", 400, T0.plusSeconds(500)));
		assertEquals(1, current().getAggregates().getActivityCompletions().getSessionCompletions());
		assertEquals(1, stats().getScoredCompletions());
		assertEquals(Long.valueOf(3_000L), stats().averageScore());
		assertEquals(0L, stats().getCurrencyGained());
	}

	// Part 21.9: a partial (joined mid-game) or unknown final score still counts as a game, never in Avg/Best.
	@Test
	public void partialOrUnknownScore_countsTheGame_butNotAvgOrBest()
	{
		temporossSession();
		wonGame(5, 9_999, false, T0.plusSeconds(300));
		wonGame(6, null, true, T0.plusSeconds(700));
		wonGame(7, 4_000, true, T0.plusSeconds(1_100));
		assertEquals(3, current().getAggregates().getActivityCompletions().getSessionCompletions());
		assertEquals(1, stats().getScoredCompletions());
		assertEquals(Integer.valueOf(4_000), stats().getBestScore());
	}

	// Part 21.12: duplicate score evidence for one kill count never double counts.
	@Test
	public void duplicateScore_forTheSameKillCount_isIgnored()
	{
		temporossSession();
		wonGame(30, 4_000, true, T0.plusSeconds(300));
		onTick(T0.plusSeconds(301), SessionSignal.structuredActivityScore(T0.plusSeconds(301), TEMPOROSS, 30, 4_000, true));
		onTick(T0.plusSeconds(302), SessionSignal.structuredActivityScore(T0.plusSeconds(302), TEMPOROSS, 29, 8_000, true));
		assertEquals(1, stats().getScoredCompletions());
		assertEquals(Integer.valueOf(4_000), stats().getBestScore());
	}

	// Ownership: stats never land on another session, never start one.
	@Test
	public void statsNeverLandOnAnotherSession_orCreateOne()
	{
		onTick(T0, xpAt("WOODCUTTING", 300, T0));
		Session wc = current();
		permits(8, T0.plusSeconds(40));
		onTick(T0.plusSeconds(50), SessionSignal.structuredActivityScore(T0.plusSeconds(50), TEMPOROSS, 3, 4_000, true));
		assertEquals(wc.getSessionId(), current().getSessionId());
		assertNull(current().getAggregates().getActivityStats());

		coordinator.testEnqueueSignal(SessionSignal.structuredActivityCurrency(T0.plusSeconds(60), TEMPOROSS, 5).withGameTick(tick));
		coordinator.testProcessTick(++tick, T0.plusSeconds(60));
		assertNull("no session: nothing is created", isTempoross(current()) ? current() : null);
	}

	// Part 18 / 21.11: a plugin restart keeps the session's summary stats.
	@Test
	public void restart_keepsTheSessionSummaryStats()
	{
		// Real time: a restored session is judged against the wall clock.
		Instant t = Instant.now().minusSeconds(60);
		Session s = temporossSession(t);
		wonGame(1, 4_100, true, t.plusSeconds(30));
		permits(4, t.plusSeconds(31));
		restartPlugin();
		Session restored = current();
		assertNotNull(restored);
		assertEquals(s.getSessionId(), restored.getSessionId());
		SessionAggregates.ActivitySessionStats kept = restored.getAggregates().getActivityStats();
		assertNotNull(kept);
		assertEquals(4L, kept.getCurrencyGained());
		assertEquals(Integer.valueOf(4_100), kept.getBestScore());
	}

	// Part 21.10: an account switch starts clean -- the other account never sees these stats.
	@Test
	public void accountSwitch_otherAccountStartsClean()
	{
		temporossSession();
		permits(4, T0.plusSeconds(300));
		coordinator.ensureAccountLoaded(ACCOUNT + 1);
		assertTrue(current() == null || current().getAggregates().getActivityStats() == null);
	}

	// Snapshot / presentation model.
	@Test
	public void currentSessionSnapshot_carriesEarnedAvgBest()
	{
		temporossSession();
		wonGame(1, 4_000, true, T0.plusSeconds(300));
		wonGame(2, 5_000, true, T0.plusSeconds(600));
		permits(11, T0.plusSeconds(601));
		CurrentSessionSnapshot ui = ui(T0.plusSeconds(700));
		assertEquals(11L, ui.getActivityCurrencyGained());
		assertEquals(2, ui.getScoredGames());
		assertEquals(Long.valueOf(4_500L), ui.getAverageScore());
		assertEquals(Integer.valueOf(5_000), ui.getBestScore());
	}

	// Part 17: old JSON (no activityStats) still loads; the new field is additive.
	@Test
	public void oldSessionJson_withoutStats_loads()
	{
		Session old = new Gson().fromJson(("{'sessionId':'old','activityIdentity':{'activityType':'STRUCTURED_ACTIVITY','activityKey':'tempoross',"
			+ "'displayName':'Tempoross'},'state':'FINALIZED','aggregates':{'xpGainedBySkill':{'FISHING':1000},'lootDrops':[],"
			+ "'activityCompletions':{'activityKey':'tempoross','sessionCompletions':2}}}").replace('\'', '"'), Session.class);
		assertNull(old.getAggregates().getActivityStats());
		assertEquals(2, old.getAggregates().getActivityCompletions().getSessionCompletions());
	}

	// The event payloads map to exactly these signals.
	@Test
	public void eventMapper_mapsScoreAndCurrency()
	{
		SessionSignal score = SessionEventMapper.map(EventType.STRUCTURED_ACTIVITY_SCORE,
			new EventPayloads.StructuredActivityScore(TEMPOROSS, 12, 3_400, true), T0);
		assertEquals(SignalKind.STRUCTURED_ACTIVITY_SCORE, score.getKind());
		assertEquals(Integer.valueOf(12), score.getReliableCount());
		assertEquals(Long.valueOf(3_400L), score.getActivityAmount());
		assertTrue(score.isActivityAmountTrusted());
		SessionSignal gain = SessionEventMapper.map(EventType.STRUCTURED_ACTIVITY_CURRENCY,
			new EventPayloads.StructuredActivityCurrency(TEMPOROSS, "reward_permits", 4, 44), T0);
		assertEquals(Long.valueOf(4L), gain.getActivityAmount());
		assertNull("never a non-positive gain", SessionEventMapper.map(EventType.STRUCTURED_ACTIVITY_CURRENCY,
			new EventPayloads.StructuredActivityCurrency(TEMPOROSS, "reward_permits", 0, 44), T0));
	}
}
