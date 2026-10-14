package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.ui.CompletionMetricText;
import java.time.Instant;
import org.junit.Test;

/**
 * Pest Control session analytics: GAMES WON, COMMENDATIONS, AVG GAME
 * (observed-from-start won games, entry to win) and the ROUND TIME
 * denominator of COMMENDATIONS / HR -- through the real classifier /
 * aggregate updater, persisted for History.
 */
public class PestControlDashboardSessionTest extends StructuredActivityTestSupport
{
	private static final String PC = "pest_control";
	private static final int ISLAND = 10536;
	private static final int OUTPOST = 10537;
	private static final Instant T0 = Instant.parse("2026-10-02T03:30:00Z");

	private static SessionSignal outpost(Instant at)
	{
		return SessionSignal.structuredActivityLocation(at, null, OUTPOST);
	}

	private static SessionSignal round(Instant at, long millis)
	{
		return SessionSignal.structuredActivityRoundTime(at, PC, millis);
	}

	private static SessionSignal gameTime(Instant at, int seconds)
	{
		return SessionSignal.structuredActivityScore(at, PC, null, seconds, true);
	}

	private SessionAggregates.ActivitySessionStats stats()
	{
		return current().getAggregates().getActivityStats();
	}

	/** One observed won game: entry, fight, win (score + completion + award), exit with its round time. */
	private Instant wonGame(Instant entry, int seconds, int points)
	{
		onTick(entry, enter(PC, ISLAND, entry));
		Instant fight = entry.plusSeconds(20);
		onTick(fight, xpAt("ATTACK", 300, fight));
		Instant win = entry.plusSeconds(seconds);
		onTick(win, gameTime(win, seconds), SessionSignal.structuredActivityCompletion(win, PC, null),
			SessionSignal.structuredActivityCurrency(win, PC, "commendation_points", points));
		Instant exit = win.plusSeconds(1);
		onTick(exit, outpost(exit), round(exit, (seconds + 1) * 1000L));
		return exit;
	}

	@Test
	public void avgGame_isTheMeanOfObservedWins()
	{
		Instant t = wonGame(T0, 120, 8);
		t = wonGame(t.plusSeconds(60), 180, 8);
		assertEquals(2, current().getAggregates().getActivityCompletions().getSessionCompletions());
		assertEquals(2, stats().getScoredCompletions());
		assertEquals(Long.valueOf(150), stats().averageScore());
		assertEquals(16L, stats().getCurrencyGained());
	}

	@Test
	public void winJoinedPartWay_countsAsAWin_butNotInAvgGame()
	{
		onTick(T0, enter(PC, ISLAND, T0));
		Instant fight = T0.plusSeconds(10);
		onTick(fight, xpAt("ATTACK", 300, fight));
		Instant win = T0.plusSeconds(60);
		// The collector emits no score for it at all.
		onTick(win, SessionSignal.structuredActivityCompletion(win, PC, null),
			SessionSignal.structuredActivityCurrency(win, PC, "commendation_points", 8));
		assertEquals(1, current().getAggregates().getActivityCompletions().getSessionCompletions());
		assertEquals(0, stats().getScoredCompletions());
		assertNull(stats().averageScore());
	}

	@Test
	public void roundTime_accumulates_winsAndLosses_neverOutpostTime()
	{
		Instant t = wonGame(T0, 120, 8);
		// A long outpost wait (inside the 5-minute suspend window): active time grows, round time does not.
		Instant wait = t.plusSeconds(200);
		onTick(wait, outpost(wait));
		Instant entry = wait.plusSeconds(60);
		onTick(entry, enter(PC, ISLAND, entry));
		Instant fight = entry.plusSeconds(20);
		onTick(fight, xpAt("ATTACK", 200, fight));
		Instant lost = entry.plusSeconds(240);
		onTick(lost, outpost(lost), round(lost, 240_000L));
		assertEquals(2, stats().getRoundsTimed());
		assertEquals(121_000L + 240_000L, stats().getRoundMillis());
		assertEquals("a loss adds no win", 1, current().getAggregates().getActivityCompletions().getSessionCompletions());
		assertTrue("active time includes the outpost wait", current().getAccumulatedActiveDurationMillis() > stats().getRoundMillis());
	}

	@Test
	public void commendationsPerHour_usesRoundTime_withTheFiveMinuteFloor()
	{
		assertEquals(Double.valueOf(48.0), CompletionMetricText.commendationsPerHour(48, 3_600_000L, 10, 0L, 9_999_999L));
		assertEquals("plus the round in progress", Double.valueOf(48.0),
			CompletionMetricText.commendationsPerHour(48, 3_000_000L, 9, 600_000L, 9_999_999L));
		assertNull("below 5 minutes", CompletionMetricText.commendationsPerHour(16, 240_000L, 2, 0L, 0L));
		assertNull("nothing earned", CompletionMetricText.commendationsPerHour(0, 3_600_000L, 3, 0L, 0L));
		assertEquals("older sessions fall back to active time", Double.valueOf(24.0),
			CompletionMetricText.commendationsPerHour(24, 0L, 0, 0L, 3_600_000L));
	}

	@Test
	public void roundTime_andAvgGame_persistAndCopy_forHistory()
	{
		wonGame(T0, 120, 8);
		Session s = current();
		Session reloaded = new Gson().fromJson(new Gson().toJson(s), Session.class);
		SessionAggregates.ActivitySessionStats st = reloaded.getAggregates().getActivityStats();
		assertEquals(121_000L, st.getRoundMillis());
		assertEquals(1, st.getRoundsTimed());
		assertEquals(Long.valueOf(120), st.averageScore());
		SessionAggregates.ActivitySessionStats copied = SessionSnapshot.capture(reloaded).getActivityStats();
		assertEquals(121_000L, copied.getRoundMillis());
		assertEquals(1, copied.getRoundsTimed());
	}

	@Test
	public void roundTimeEvent_mapsToItsSignal_andZeroIsDropped()
	{
		SessionSignal sig = SessionEventMapper.map(EventType.STRUCTURED_ACTIVITY_ROUND_TIME,
			new EventPayloads.StructuredActivityRoundTime(PC, 90_000L), T0);
		assertEquals(SignalKind.STRUCTURED_ACTIVITY_ROUND_TIME, sig.getKind());
		assertEquals(Long.valueOf(90_000L), sig.getActivityAmount());
		assertNull(SessionEventMapper.map(EventType.STRUCTURED_ACTIVITY_ROUND_TIME, new EventPayloads.StructuredActivityRoundTime(PC, 0L), T0));
	}
}
