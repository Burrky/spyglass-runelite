package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.activity.RecognitionState;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.time.Instant;
import org.junit.Test;

/**
 * STRUCTURED ACTIVATION FROM LIVE XP (Tempoross pass). Live incident:
 * entering Tempoross with a SUSPENDED Woodcutting session, Fishing XP was
 * shown under Woodcutting for ~30s -- the structured classifier only ever
 * saw expected-skill XP through the durable XP_CHANGE window, which
 * SkillsCollector flushes every 50 ticks. The fix: a live expected-skill
 * gain inside an armed, non-NON_EXCLUSIVE location is SPECIFIC,
 * classification-only evidence at the next tick close. Location alone
 * never mints a session, durable XP accounting is unchanged (the first
 * gain is credited once, to the session it started), and LOCK still
 * needs the same durable/completion evidence as before.
 */
public class StructuredLiveXpActivationTest extends StructuredActivityTestSupport
{
	static final String TEMPOROSS = "tempoross";
	static final int TEMPOROSS_ARENA = 12076;
	static final int TEMPOROSS_COVE = 12588;
	private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

	private static boolean isTempoross(Session s)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.STRUCTURED_ACTIVITY
			&& TEMPOROSS.equals(s.getActivityIdentity().getActivityKey());
	}

	private static boolean isWoodcutting(Session s)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.SKILLING
			&& "WOODCUTTING".equalsIgnoreCase(s.getActivityIdentity().getActivityKey());
	}

	/** A live StatChanged gain on the CURRENT (still open) tick, as SkillsCollector delivers it. */
	private void liveGain(String skill, long delta, long xpAfter, Instant at)
	{
		coordinator.testRecordLiveXp(skill, delta, xpAfter, tick, at);
		coordinator.noteAbsoluteXp(skill, xpAfter);
	}

	/** Close the current tick with nothing else on it. */
	private void closeTick(Instant at)
	{
		coordinator.testProcessTick(++tick, at);
	}

	private long shownXp(String skill, Instant now)
	{
		for (CurrentSessionSnapshot.XpEntry e : ui(now).getXpEntries())
		{
			if (skill.equalsIgnoreCase(e.getSkill()))
			{
				return e.getXpGained();
			}
		}
		return 0L;
	}

	/** Woodcutting, then 6 idle minutes: a SUSPENDED Woodcutting session is current. */
	private Session suspendedWoodcutting()
	{
		coordinator.noteAbsoluteXp("FISHING", 1_000L);
		coordinator.noteAbsoluteXp("COOKING", 5_000L);
		coordinator.noteAbsoluteXp("CONSTRUCTION", 9_000L);
		xp("WOODCUTTING", 200, T0);
		xp("WOODCUTTING", 200, T0.plusSeconds(30));
		assertTrue(isWoodcutting(current()));
		closeTick(T0.plusSeconds(60 + 6 * 60));
		assertEquals(SessionState.SUSPENDED, current().getState());
		return current();
	}

	private Instant enterArena()
	{
		Instant at = T0.plusSeconds(60 + 7 * 60);
		onTick(at, enter(TEMPOROSS, TEMPOROSS_ARENA, at));
		return at;
	}

	// Part 6 case 1 / Part 30 case 1: the live incident, fixed -- the switch
	// happens at the first tick close after the first Fishing gain.
	@Test
	public void suspendedWoodcutting_liveFishingInArena_activatesTemporossAtTheNextTickClose()
	{
		Session wc = suspendedWoodcutting();
		Instant at = enterArena();
		assertTrue("location alone never displaces anything", isWoodcutting(current()));

		liveGain("FISHING", 50, 1_050L, at.plusSeconds(3));
		closeTick(at.plusSeconds(3).plusMillis(600));

		assertTrue("one tick later, not ~30s later", isTempoross(current()));
		assertEquals(SessionState.ACTIVE, current().getState());
		assertNotEquals(wc.getSessionId(), current().getSessionId());
		assertEquals("the triggering gain shows under Tempoross", 50L, shownXp("FISHING", at.plusSeconds(4)));
		assertEquals(RecognitionState.ACTIVE, recognition());
	}

	// Part 6 case 2 / Part 30 cases 2+3: Cooking and Construction are expected too.
	@Test
	public void cookingAndConstruction_areEquallyActivating()
	{
		suspendedWoodcutting();
		Instant at = enterArena();
		liveGain("COOKING", 30, 5_030L, at.plusSeconds(2));
		closeTick(at.plusSeconds(3));
		assertTrue(isTempoross(current()));
	}

	@Test
	public void construction_activatesFromNoSessionAtAll()
	{
		coordinator.noteAbsoluteXp("CONSTRUCTION", 9_000L);
		Instant at = enterArena();
		assertEquals("location alone mints nothing", null, current());
		liveGain("CONSTRUCTION", 20, 9_020L, at.plusSeconds(2));
		closeTick(at.plusSeconds(3));
		assertTrue(isTempoross(current()));
	}

	// Part 6 case 3 / Part 30 case 4: location only -- no session, however long.
	@Test
	public void locationOnly_neverCreatesOrDisplacesASession()
	{
		Session wc = suspendedWoodcutting();
		Instant at = enterArena();
		for (int i = 1; i <= 20; i++)
		{
			closeTick(at.plusSeconds(i));
		}
		assertEquals(wc.getSessionId(), current().getSessionId());
		assertFalse(isTempoross(current()));
	}

	// Part 6 case 4 / Part 30 case 5: live XP outside any structured location stays display-only.
	@Test
	public void liveXpOutsideAnyLocation_isDisplayOnly_andActivatesNothing()
	{
		coordinator.noteAbsoluteXp("WOODCUTTING", 100L);
		xp("WOODCUTTING", 200, T0);
		Session wc = current();
		coordinator.testRecordLiveXp("WOODCUTTING", 25, 125L, tick, T0.plusSeconds(40));
		assertEquals("display-only path: visible at once", 225L, shownXp("WOODCUTTING", T0.plusSeconds(41)));
		closeTick(T0.plusSeconds(41));
		assertEquals(wc.getSessionId(), current().getSessionId());
	}

	// Part 6 case 5 / Part 30 case 6: the reward cove is NON_EXCLUSIVE -- never activating.
	@Test
	public void rewardCove_isNonExclusive_liveFishingThereActivatesNothing()
	{
		Session wc = suspendedWoodcutting();
		Instant at = T0.plusSeconds(60 + 7 * 60);
		onTick(at, enter(TEMPOROSS, TEMPOROSS_COVE, at));
		liveGain("FISHING", 50, 1_050L, at.plusSeconds(2));
		closeTick(at.plusSeconds(3));
		assertEquals(wc.getSessionId(), current().getSessionId());
		assertFalse(isTempoross(current()));
	}

	// Part 6 case 6: an unexpected skill in the arena is not activation evidence.
	@Test
	public void unexpectedSkillInArena_isNotActivationEvidence()
	{
		Session wc = suspendedWoodcutting();
		Instant at = enterArena();
		coordinator.testRecordLiveXp("HITPOINTS", 4, 1_004L, tick, at.plusSeconds(2));
		closeTick(at.plusSeconds(3));
		assertEquals(wc.getSessionId(), current().getSessionId());
	}

	// Part 6 case 7 / Part 30 case 7: durable XP unchanged and never double counted --
	// the first gain is credited once, to the session it started.
	@Test
	public void durableWindow_spanningTheSwitch_creditsEveryPostSwitchGainOnce_andReconcilesLive()
	{
		Session wc = suspendedWoodcutting();
		long wcFishingBefore = wc.getAggregates().getXpGainedBySkill().getOrDefault("FISHING", 0L);
		Instant at = enterArena();
		Instant windowStart = at.minusSeconds(10); // the durable window opened BEFORE the switch

		liveGain("FISHING", 50, 1_050L, at.plusSeconds(2));
		closeTick(at.plusSeconds(3));
		assertTrue(isTempoross(current()));
		Session tempoross = current();

		liveGain("FISHING", 60, 1_110L, at.plusSeconds(6));
		closeTick(at.plusSeconds(7));
		liveGain("FISHING", 40, 1_150L, at.plusSeconds(12));
		closeTick(at.plusSeconds(13));
		assertEquals("live display: every gain once", 150L, shownXp("FISHING", at.plusSeconds(14)));

		Instant flush = windowStart.plusSeconds(30);
		onTick(flush, SessionSignal.xpChange(flush, "FISHING", 150, windowStart, 1_150L));
		assertEquals(tempoross.getSessionId(), current().getSessionId());
		assertEquals("durable: exactly the three gains, first one included",
			Long.valueOf(150L), current().getAggregates().getXpGainedBySkill().get("FISHING"));
		assertEquals("display after reconcile: not 300", 150L, shownXp("FISHING", flush.plusSeconds(1)));
		assertEquals("nothing leaked into the displaced session", wcFishingBefore,
			(long) wc.getAggregates().getXpGainedBySkill().getOrDefault("FISHING", 0L));
	}

	// Part 6 case 8 / Part 30 case 8: once Tempoross is current, live XP is display-only -- no churn.
	@Test
	public void activeTempoross_liveXpNeverChurnsTheSession()
	{
		suspendedWoodcutting();
		Instant at = enterArena();
		liveGain("FISHING", 50, 1_050L, at.plusSeconds(2));
		closeTick(at.plusSeconds(3));
		String id = current().getSessionId();
		long xpAfter = 1_050L;
		for (int i = 0; i < 12; i++)
		{
			xpAfter += 10;
			liveGain(i % 2 == 0 ? "FISHING" : "COOKING", 10, xpAfter, at.plusSeconds(4 + i));
			closeTick(at.plusSeconds(4 + i).plusMillis(600));
			assertEquals(id, current().getSessionId());
		}
		assertEquals(SessionState.ACTIVE, current().getState());
	}

	// Part 6 case 9 / Part 30 case 9: live XP activates but never LOCKS on its own.
	@Test
	public void liveXpAlone_neverLocksRecognition()
	{
		Instant at = enterArena();
		coordinator.noteAbsoluteXp("FISHING", 1_000L);
		long xpAfter = 1_000L;
		for (int i = 0; i < 6; i++)
		{
			xpAfter += 20;
			liveGain("FISHING", 20, xpAfter, at.plusSeconds(2 + i));
			closeTick(at.plusSeconds(2 + i).plusMillis(600));
		}
		assertTrue(isTempoross(current()));
		assertEquals("LOCK still needs durable/completion confirmations", RecognitionState.ACTIVE, recognition());
	}

	// Part 30 case 10: an off-client-thread gain (no tick) falls back to display-only.
	@Test
	public void untaggedGain_fallsBackToDisplayOnly_neverStrandsASignal()
	{
		Session wc = suspendedWoodcutting();
		Instant at = enterArena();
		coordinator.testRecordLiveXp("FISHING", 50, 1_050L, null, at.plusSeconds(2));
		for (int i = 0; i < 5; i++)
		{
			closeTick(at.plusSeconds(3 + i));
		}
		assertEquals(wc.getSessionId(), current().getSessionId());
	}

	// Part 6 case 10: after leaving the arena, live XP there is no longer evidence.
	@Test
	public void afterLeaving_liveExpectedSkillXpActivatesNothing()
	{
		Session wc = suspendedWoodcutting();
		Instant at = enterArena();
		onTick(at.plusSeconds(5), leave(at.plusSeconds(5)));
		liveGain("FISHING", 50, 1_050L, at.plusSeconds(8));
		closeTick(at.plusSeconds(9));
		assertEquals(wc.getSessionId(), current().getSessionId());
	}

	// Part 14 + Part 16 end to end: live activation, then the game's own
	// completion counts one game, and the reward pool in the NON_EXCLUSIVE
	// cove is still correlated to the session the game belongs to.
	@Test
	public void liveActivated_game_completesAndOwnsItsRewardPool()
	{
		suspendedWoodcutting();
		Instant at = enterArena();
		liveGain("FISHING", 50, 1_050L, at.plusSeconds(2));
		closeTick(at.plusSeconds(3));
		String id = current().getSessionId();
		onTick(at.plusSeconds(300), SessionSignal.bossKill(at.plusSeconds(300), "Tempoross", 41));
		assertEquals(id, current().getSessionId());
		assertEquals(1, current().getAggregates().getActivityCompletions().getSessionCompletions());
		assertEquals(Integer.valueOf(41), current().getAggregates().getActivityCompletions().getLatestAuthoritativeValue());

		onTick(at.plusSeconds(310), enter(TEMPOROSS, TEMPOROSS_COVE, at.plusSeconds(310)));
		onTick(at.plusSeconds(320), SessionSignal.structuredReward(at.plusSeconds(320), TEMPOROSS, "tempoross_reward_pool",
			java.util.Collections.singletonList(new SessionSignal.LootDrop(995, "Coins", 100))));
		assertEquals(id, current().getSessionId());
		assertEquals("the reward pool landed on the Tempoross session, once",
			100L, StructuredActivityAdaptersTest.lootFrom(current(), "Tempoross Reward Pool"));
	}

	/** Part 24: every exclusive structured location activates on its first live expected-skill gain. */
	private void activatesOnFirstLiveGain(String key, int region, String skill)
	{
		coordinator.noteAbsoluteXp("MINING", 100L);
		coordinator.noteAbsoluteXp(skill, 50_000L);
		xp("MINING", 200, T0);
		closeTick(T0.plusSeconds(60 + 6 * 60)); // suspended
		Instant at = T0.plusSeconds(60 + 7 * 60);
		onTick(at, enter(key, region, at));
		assertFalse("location alone: nothing", key.equals(current().getActivityIdentity().getActivityKey()));
		coordinator.testRecordLiveXp(skill, 40, 50_040L, tick, at.plusSeconds(2));
		closeTick(at.plusSeconds(3));
		assertEquals(ActivityType.STRUCTURED_ACTIVITY, current().getActivityIdentity().getActivityType());
		assertEquals(key + " activates at the next tick close", key, current().getActivityIdentity().getActivityKey());
	}

	@Test
	public void wintertodt_activatesFromLiveXp()
	{
		activatesOnFirstLiveGain("wintertodt", 6462, "FIREMAKING");
	}

	@Test
	public void pestControl_islandEntryAlone_activates_liveCombatXpKeepsIt()
	{
		coordinator.noteAbsoluteXp("MINING", 100L);
		coordinator.noteAbsoluteXp("ATTACK", 50_000L);
		xp("MINING", 200, T0);
		closeTick(T0.plusSeconds(60 + 6 * 60)); // suspended
		Instant at = T0.plusSeconds(60 + 7 * 60);
		onTick(at, enter("pest_control", 10536, at));
		assertEquals("the island is the game itself: entry starts it", "pest_control", current().getActivityIdentity().getActivityKey());
		String id = current().getSessionId();
		coordinator.testRecordLiveXp("ATTACK", 40, 50_040L, tick, at.plusSeconds(2));
		closeTick(at.plusSeconds(3));
		assertEquals(id, current().getSessionId());
	}

	@Test
	public void giantsFoundry_activatesFromLiveXp()
	{
		activatesOnFirstLiveGain("giants_foundry", 13491, "SMITHING");
	}

	@Test
	public void barrowsCrypt_activatesFromLiveCombatXp()
	{
		activatesOnFirstLiveGain("barrows", 14231, "MAGIC");
	}

	// Structured regression: the same generic path activates GotR too (not a Tempoross hack).
	@Test
	public void generic_gotrActivatesFromLiveXpToo()
	{
		coordinator.noteAbsoluteXp("RUNECRAFT", 10_000L);
		onTick(T0, enter(GOTR, GOTR_REGION, T0));
		coordinator.testRecordLiveXp("RUNECRAFT", 30, 10_030L, tick, T0.plusSeconds(2));
		closeTick(T0.plusSeconds(3));
		assertNotNull(current());
		assertTrue(isGotr(current()));
	}
}
