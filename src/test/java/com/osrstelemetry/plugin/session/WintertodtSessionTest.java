package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.Map;
import org.junit.Test;

/**
 * Wintertodt as ONE session of many rounds: intermissions, the arena Gap's
 * Agility XP (downtime, stays Wintertodt), world hops, and the round / action
 * aggregates History reads -- through the real classifier and updater.
 */
public class WintertodtSessionTest extends StructuredActivityTestSupport
{
	private static final String WT = "wintertodt";
	private static final int ARENA = 6462;
	private static final int CAMP = 6461;
	private static final Instant T0 = Instant.parse("2026-10-02T15:00:00Z");

	private boolean isWt(Session s)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.STRUCTURED_ACTIVITY
			&& WT.equals(s.getActivityIdentity().getActivityKey());
	}

	/** One round: firemaking XP, subdue, round time, actions. Returns the time after it. */
	private Instant round(Instant start, int count, int points, int rewards)
	{
		for (int i = 1; i <= 4; i++)
		{
			Instant t = start.plusSeconds(30L * i);
			onTick(t, xpAt("FIREMAKING", 2_000, t));
		}
		Instant end = start.plusSeconds(150);
		onTick(end, SessionSignal.structuredActivityScore(end, WT, count, points, true),
			SessionSignal.structuredActivityCompletion(end, WT, count),
			SessionSignal.structuredActivityCurrency(end, WT, "reward_cart_rewards", rewards),
			SessionSignal.structuredActivityRoundTime(end, WT, 150_000L, true),
			SessionSignal.structuredActivityAction(end, WT, "roots_fed", 40),
			SessionSignal.structuredActivityAction(end, WT, "kindling_fed", 12));
		return end;
	}

	@Test
	public void manyRounds_withIntermissionDowntime_areOneSession()
	{
		onTick(T0, enter(WT, ARENA, T0));
		Instant t = round(T0, 100, 740, 2);
		assertTrue(isWt(current()));
		String id = current().getSessionId();
		// Intermission: the arena Gap (60 Agility, 18 XP) -- downtime, not a new Agility session.
		for (int i = 1; i <= 3; i++)
		{
			Instant g = t.plusSeconds(10L * i);
			onTick(g, xpAt("AGILITY", 18, g));
		}
		assertEquals(id, current().getSessionId());
		t = round(t.plusSeconds(60), 101, 1040, 3);
		t = round(t.plusSeconds(60), 102, 520, 2);
		assertEquals("three rounds, one session", id, current().getSessionId());
		SessionAggregates.ActivitySessionStats st = current().getAggregates().getActivityStats();
		assertEquals(3, current().getAggregates().getActivityCompletions().getSessionCompletions());
		assertEquals(7L, (long) st.getCurrencyGainedByKey().get("reward_cart_rewards"));
		assertEquals(2300L, st.getScoreTotal());
		assertEquals(Long.valueOf(767), st.averageScore());
		assertEquals(3, st.getCompleteRounds());
		assertEquals(450_000L, st.getCompleteRoundMillis());
		assertEquals(Long.valueOf(120), st.getActionCounts().get("roots_fed"));
		assertTrue("the Gap's Agility XP stays in the Wintertodt session",
			current().getAggregates().getXpGainedBySkill().containsKey("AGILITY"));
	}

	@Test
	public void actualAwards_notTheGuaranteedFormula_areTheSessionsRewards()
	{
		// Live 2026-10-02: 950 / 935 / 845 points (formula: 2 each) -> the game awarded +3 each.
		onTick(T0, enter(WT, ARENA, T0));
		Instant t = round(T0, 28, 950, 3);
		t = round(t.plusSeconds(60), 29, 935, 3);
		round(t.plusSeconds(60), 30, 845, 3);
		SessionAggregates.ActivitySessionStats st = current().getAggregates().getActivityStats();
		assertEquals(9L, (long) st.getCurrencyGainedByKey().get("reward_cart_rewards"));
		assertEquals("points and subdues unchanged", 2730L, st.getScoreTotal());
	}

	@Test
	public void agilityAwayFromWintertodt_switchesNormally()
	{
		onTick(T0, enter(WT, ARENA, T0));
		Instant t = round(T0, 100, 740, 2);
		String id = current().getSessionId();
		Instant away = t.plusSeconds(30);
		onTick(away, leave(away));
		for (int i = 1; i <= 6; i++)
		{
			Instant a = away.plusSeconds(20L * i);
			onTick(a, xpAt("AGILITY", 80, a));
		}
		assertNotEquals("real Agility elsewhere is its own activity", id, current().getSessionId());
	}

	@Test
	public void worldHop_betweenGames_keepsOneSession()
	{
		onTick(T0, enter(WT, ARENA, T0));
		Instant t = round(T0, 100, 600, 2);
		String id = current().getSessionId();
		for (int hop = 0; hop < 3; hop++)
		{
			Instant out = t.plusSeconds(5);
			onTick(out, leave(out));
			Instant in = out.plusSeconds(8);
			onTick(in, enter(WT, ARENA, in));
			t = round(in, 101 + hop, 560, 2);
		}
		assertEquals("hopping between games stays one Wintertodt session", id, current().getSessionId());
		assertEquals(4, current().getAggregates().getActivityCompletions().getSessionCompletions());
	}

	@Test
	public void camp_aloneNeverStartsWintertodt()
	{
		onTick(T0, enter(WT, CAMP, T0));
		Instant t = T0.plusSeconds(20);
		onTick(t, xpAt("FIREMAKING", 500, t));
		assertTrue(current() == null || !isWt(current()));
	}

	// ---- camp / reward-management continuity ----------------------------------------------------

	private static SessionSignal cartClaim(Instant at)
	{
		return SessionSignal.structuredReward(at, WT, "wintertodt_reward_cart",
			java.util.Collections.singletonList(new SessionSignal.LootDrop(1515, "Yew logs", 40)));
	}

	private boolean live(Session s)
	{
		return isWt(s) && (s.getState() == SessionState.ACTIVE || s.getState() == SessionState.SUSPENDED);
	}

	@Test
	public void arenaToCamp_bankAndCart_backToArena_isOneSession()
	{
		onTick(T0, enter(WT, ARENA, T0));
		Instant t = round(T0, 100, 740, 3);
		String id = current().getSessionId();
		Instant camp = t.plusSeconds(20);
		onTick(camp, enter(WT, CAMP, camp));
		assertTrue("the camp keeps the session", isWt(current()));
		// Bank, then search the cart twice: the claims land on THIS session, nothing else.
		Instant claim = camp.plusSeconds(60);
		onTick(claim, cartClaim(claim));
		onTick(claim.plusSeconds(3), cartClaim(claim.plusSeconds(3)));
		assertEquals(id, current().getSessionId());
		assertEquals(2, current().getAggregates().getLootDrops().size());
		Instant back = claim.plusSeconds(60);
		onTick(back, enter(WT, ARENA, back));
		round(back.plusSeconds(30), 101, 820, 3);
		assertEquals("arena -> camp -> arena stays one Wintertodt session", id, current().getSessionId());
		assertEquals(2, current().getAggregates().getActivityCompletions().getSessionCompletions());
		assertEquals("claims never change what the session earned", 6L,
			(long) current().getAggregates().getActivityStats().getCurrencyGainedByKey().get("reward_cart_rewards"));
	}

	@Test
	public void campWithoutAPriorSession_claimNeverStartsWintertodt()
	{
		onTick(T0, enter(WT, CAMP, T0));
		Instant t = T0.plusSeconds(10);
		onTick(t, cartClaim(t));
		onTick(t.plusSeconds(3), xpAt("WOODCUTTING", 300, t.plusSeconds(3)));
		assertTrue("the camp alone (even a cart search) is never a Wintertodt session", current() == null || !isWt(current()));
	}

	@Test
	public void standingInTheCamp_doesNotPinWintertodtForever()
	{
		onTick(T0, enter(WT, ARENA, T0));
		Instant t = round(T0, 100, 740, 2);
		Instant camp = t.plusSeconds(10);
		onTick(camp, enter(WT, CAMP, camp));
		Instant idle = camp.plusSeconds(6 * 60);
		onTick(idle);
		assertTrue(isWt(current()));
		assertEquals("idle in the camp pauses it (the engine's 5-minute rule)", SessionState.SUSPENDED, current().getState());
		Instant gone = idle.plusSeconds(40 * 60);
		onTick(gone);
		assertTrue("past the resume window it is released", !live(current()));
	}

	@Test
	public void unrelatedSkillingInTheCamp_takesOwnershipNormally()
	{
		onTick(T0, enter(WT, ARENA, T0));
		Instant t = round(T0, 100, 740, 2);
		String id = current().getSessionId();
		Instant camp = t.plusSeconds(10);
		onTick(camp, enter(WT, CAMP, camp));
		for (int i = 1; i <= 8; i++)
		{
			Instant w = camp.plusSeconds(30L * i);
			onTick(w, xpAt("WOODCUTTING", 175, w));
		}
		assertNotEquals("camp XP is never absorbed: real Woodcutting there is its own session", id, current().getSessionId());
		assertTrue(!isWt(current()));
	}

	@Test
	public void newAggregates_persist_andOldSessionsStillLoad()
	{
		onTick(T0, enter(WT, ARENA, T0));
		round(T0, 100, 740, 2);
		Gson gson = new Gson();
		Session reloaded = gson.fromJson(gson.toJson(current()), Session.class);
		SessionAggregates.ActivitySessionStats st = reloaded.getAggregates().getActivityStats();
		assertEquals(1, st.getCompleteRounds());
		assertEquals(Long.valueOf(12), st.getActionCounts().get("kindling_fed"));
		Map<String, Long> copied = SessionSnapshot.capture(reloaded).getActivityStats().getActionCounts();
		assertEquals(Long.valueOf(40), copied.get("roots_fed"));

		Session old = gson.fromJson(("{'sessionId':'old','activityIdentity':{'activityType':'STRUCTURED_ACTIVITY','activityKey':'wintertodt',"
			+ "'displayName':'Wintertodt'},'state':'FINALIZED','startedAt':'2025-01-01T00:00:00Z','aggregates':{'activityStats':"
			+ "{'activityKey':'wintertodt','currencyGained':4}}}").replace('\'', '"'), Session.class);
		SessionAggregates.ActivitySessionStats o = old.getAggregates().getActivityStats();
		assertEquals(0, o.getCompleteRounds());
		assertNull(o.getActionCounts());
		assertEquals(4L, o.getCurrencyGained());
	}

	@Test
	public void playstyleLabel_rideTheRoundTime_resolvedWins_autoOnlyWhenNeverResolved_oldSessionsHaveNone()
	{
		onTick(T0, enter(WT, ARENA, T0));
		Instant t = round(T0, 100, 740, 2);
		assertNull("no label on a round without one", current().getAggregates().getActivityStats().getPlaystyle());
		t = t.plusSeconds(200);
		onTick(t, SessionSignal.structuredActivityRoundTime(t, WT, 150_000L, true, "Auto"));
		SessionAggregates.ActivitySessionStats st = current().getAggregates().getActivityStats();
		assertEquals("never resolved yet", "Auto", st.getPlaystyle());
		t = t.plusSeconds(200);
		onTick(t, SessionSignal.structuredActivityRoundTime(t, WT, 150_000L, true, "Mass • Points"));
		assertEquals("Mass • Points", current().getAggregates().getActivityStats().getPlaystyle());
		t = t.plusSeconds(200);
		onTick(t, SessionSignal.structuredActivityRoundTime(t, WT, 150_000L, true, "Auto"));
		assertEquals("a later Auto round never hides what was resolved", "Mass • Points",
			current().getAggregates().getActivityStats().getPlaystyle());
		t = t.plusSeconds(200);
		onTick(t, SessionSignal.structuredActivityRoundTime(t, WT, 150_000L, true, null));
		assertEquals("Mass • Points", SessionSnapshot.capture(current()).getActivityStats().getPlaystyle());
		Gson gson = new Gson();
		assertEquals("Mass • Points", gson.fromJson(gson.toJson(current()), Session.class).getAggregates().getActivityStats().getPlaystyle());

		Session old = gson.fromJson(("{'sessionId':'old','activityIdentity':{'activityType':'STRUCTURED_ACTIVITY','activityKey':'wintertodt',"
			+ "'displayName':'Wintertodt'},'state':'FINALIZED','startedAt':'2025-01-01T00:00:00Z','aggregates':{'activityStats':"
			+ "{'activityKey':'wintertodt','currencyGained':4}}}").replace('\'', '"'), Session.class);
		assertNull(old.getAggregates().getActivityStats().getPlaystyle());
	}
}
