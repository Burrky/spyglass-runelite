package com.osrstelemetry.plugin.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.LifecycleResult;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.session.SessionLifecycleEngine;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.session.SessionState;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Experience;
import org.junit.Test;

public class CurrentSessionSnapshotTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	private static final ActivityIdentity GARGOYLES =
		new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");

	@Test
	public void noSession_producesEmptySnapshot()
	{
		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(null, T0);

		assertFalse(snapshot.isPresent());
		assertNull(snapshot.getSessionId());
		assertNull(snapshot.getDisplayName());
		assertNull(snapshot.getActivityType());
		assertNull(snapshot.getState());
		assertEquals(0L, snapshot.getActiveDurationMillis());
		assertTrue(snapshot.getXpEntries().isEmpty());
		assertNull(snapshot.getReliableCount());
		assertNull(snapshot.getSlayerProgressDelta());
		assertTrue(snapshot.getLootEntries().isEmpty());
		assertSame(CurrentSessionSnapshot.empty(), snapshot);
	}

	@Test
	public void absentSessionSnapshot_producesEmptySnapshot()
	{
		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.absent(), T0);

		assertFalse(snapshot.isPresent());
		assertSame(CurrentSessionSnapshot.empty(), snapshot);
	}

	@Test
	public void activeSession_reflectsIdentityAndState()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Session session = engine.getCurrentSession();

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertTrue(snapshot.isPresent());
		assertEquals(session.getSessionId(), snapshot.getSessionId());
		assertEquals("Gargoyles", snapshot.getDisplayName());
		assertEquals(ActivityType.SLAYER, snapshot.getActivityType());
		assertEquals(SessionState.ACTIVE, snapshot.getState());
	}

	@Test
	public void suspendedSession_durationExcludesIdleTime()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant t1 = T0.plus(Duration.ofMinutes(2));
		engine.onQualifyingActivity(GARGOYLES, t1);
		Instant farLater = t1.plus(Duration.ofMinutes(10));
		engine.advanceTime(farLater);

		Session session = engine.getCurrentSession();
		assertEquals(SessionState.SUSPENDED, session.getState());
		assertEquals(Duration.ofMinutes(2).toMillis(), session.getAccumulatedActiveDurationMillis());

		Instant readAt = farLater.plus(Duration.ofMinutes(20));
		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), readAt);

		assertEquals(SessionState.SUSPENDED, snapshot.getState());
		assertEquals(Duration.ofMinutes(2).toMillis(), snapshot.getActiveDurationMillis());
	}

	@Test
	public void finalizedSession_durationRemainsFrozen()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant t1 = T0.plus(Duration.ofMinutes(3));
		engine.onQualifyingActivity(GARGOYLES, t1);

		Instant pastResumeWindow = t1.plus(Duration.ofMinutes(5)).plus(Duration.ofMinutes(30)).plusSeconds(1);
		LifecycleResult result = engine.advanceTime(pastResumeWindow);
		Session finalized = result.getFinalized();
		assertEquals(SessionState.FINALIZED, finalized.getState());
		assertEquals(Duration.ofMinutes(3).toMillis(), finalized.getAccumulatedActiveDurationMillis());

		Instant readMuchLater = pastResumeWindow.plus(Duration.ofDays(1));
		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(finalized), readMuchLater);

		assertEquals(SessionState.FINALIZED, snapshot.getState());
		assertEquals(Duration.ofMinutes(3).toMillis(), snapshot.getActiveDurationMillis());
	}

	@Test
	public void xpEntries_onlyNonzeroSkillsIncluded()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 5000L);
		session.getAggregates().getXpGainedBySkill().put("HITPOINTS", 0L);
		session.getAggregates().getXpGainedBySkill().put("ATTACK", 1200L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(
			SessionSnapshot.capture(session), T0.plus(Duration.ofMinutes(10)));

		List<String> skills = new java.util.ArrayList<>();
		for (CurrentSessionSnapshot.XpEntry e : snapshot.getXpEntries())
		{
			skills.add(e.getSkill());
		}
		assertEquals(Arrays.asList("SLAYER", "ATTACK"), skills);
	}

	@Test
	public void reliableCount_absentWhenNotObserved()
	{
		Session session = activeSessionAt(T0);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertNull(snapshot.getReliableCount());
	}

	@Test
	public void reliableCount_presentSurfacesBothNumbers()
	{
		Session session = activeSessionAt(T0);
		SessionAggregates.ReliableCount rc = new SessionAggregates.ReliableCount();
		rc.setKind(SessionAggregates.ReliableCountKind.KILLS);
		rc.setSourceKey("gargoyles:catacombs");
		rc.setSessionOccurrences(4);
		rc.setAuthoritativeCurrentValue(507);
		session.getAggregates().setReliableCount(rc);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertEquals(SessionAggregates.ReliableCountKind.KILLS, snapshot.getReliableCount().getKind());
		assertEquals(4, snapshot.getReliableCount().getSessionOccurrences());
		assertEquals(Integer.valueOf(507), snapshot.getReliableCount().getAuthoritativeCurrentValue());
	}

	@Test
	public void slayerProgressDelta_absentIsNull()
	{
		Session session = activeSessionAt(T0);
		assertNull(CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0).getSlayerProgressDelta());
	}

	@Test
	public void slayerProgressDelta_presentIsSurfaced()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setSlayerProgressDelta(14);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertEquals(Integer.valueOf(14), snapshot.getSlayerProgressDelta());
	}

	@Test
	public void slayerCurrentRemaining_absentIsNull()
	{
		Session session = activeSessionAt(T0);
		assertNull(CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0).getSlayerCurrentRemaining());
	}

	@Test
	public void slayerCurrentRemaining_presentIsSurfaced_independentOfSlayerProgressDelta()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setSlayerProgressDelta(7);
		session.getAggregates().setLatestSlayerCurrentRemaining(93);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertEquals(Integer.valueOf(93), snapshot.getSlayerCurrentRemaining());
		assertEquals(Integer.valueOf(7), snapshot.getSlayerProgressDelta());
	}

	@Test
	public void slayerCurrentRemaining_latestObservationWinsOverEarlierOne()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setLatestSlayerCurrentRemaining(99);
		session.getAggregates().setLatestSlayerCurrentRemaining(92);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertEquals(Integer.valueOf(92), snapshot.getSlayerCurrentRemaining());
	}

	@Test
	public void lootEntries_sumSameItemAcrossGroups_withoutMutatingSession()
	{
		Session session = activeSessionAt(T0);

		SessionAggregates.LootDropGroup group1 = new SessionAggregates.LootDropGroup();
		group1.setSourceName("Gargoyle");
		group1.setObservedAt(T0.toString());
		SessionAggregates.LootItemAggregate item1 = new SessionAggregates.LootItemAggregate();
		item1.setItemId(561);
		item1.setItemName("Nature rune");
		item1.setQuantity(10L);
		group1.setItems(Collections.singletonList(item1));

		SessionAggregates.LootDropGroup group2 = new SessionAggregates.LootDropGroup();
		group2.setSourceName("Gargoyle");
		group2.setObservedAt(T0.plusSeconds(30).toString());
		SessionAggregates.LootItemAggregate item2 = new SessionAggregates.LootItemAggregate();
		item2.setItemId(561);
		item2.setItemName("Nature rune");
		item2.setQuantity(5L);
		group2.setItems(Collections.singletonList(item2));

		session.getAggregates().getLootDrops().add(group1);
		session.getAggregates().getLootDrops().add(group2);

		int groupCountBefore = session.getAggregates().getLootDrops().size();
		long qty1Before = item1.getQuantity();
		long qty2Before = item2.getQuantity();

		SessionSnapshot sessionSnapshot = SessionSnapshot.capture(session);
		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(sessionSnapshot, T0);

		assertEquals(1, snapshot.getLootEntries().size());
		CurrentSessionSnapshot.LootEntry entry = snapshot.getLootEntries().get(0);
		assertEquals(Integer.valueOf(561), entry.getItemId());
		assertEquals("Nature rune", entry.getItemName());
		assertEquals(15L, entry.getQuantity());

		assertEquals(groupCountBefore, session.getAggregates().getLootDrops().size());
		assertEquals(qty1Before, item1.getQuantity());
		assertEquals(qty2Before, item2.getQuantity());
		assertSame(group1, session.getAggregates().getLootDrops().get(0));
		assertSame(group2, session.getAggregates().getLootDrops().get(1));

		assertEquals(2, sessionSnapshot.getLootDrops().size());
		assertEquals(10L, sessionSnapshot.getLootDrops().get(0).getItems().get(0).getQuantity());
		assertEquals(5L, sessionSnapshot.getLootDrops().get(1).getItems().get(0).getQuantity());
	}

	@Test
	public void xpPerHour_omittedBelowThreshold_includedAtOrAboveThreshold()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Session session = engine.getCurrentSession();
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 3600L);
		SessionSnapshot sessionSnapshot = SessionSnapshot.capture(session);

		Instant belowThreshold = T0.plusSeconds(30);
		CurrentSessionSnapshot below = CurrentSessionSnapshot.from(sessionSnapshot, belowThreshold);
		assertNull(below.getXpEntries().get(0).getXpPerHour());

		Instant atThreshold = T0.plusSeconds(60);
		CurrentSessionSnapshot at = CurrentSessionSnapshot.from(sessionSnapshot, atThreshold);
		assertEquals(Long.valueOf(216_000L), at.getXpEntries().get(0).getXpPerHour());
	}

	@Test
	public void xpPerHour_reflectsTheImmediateLiveTotal_notJustDurable()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Session session = engine.getCurrentSession();
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 600L);

		java.util.Map<String, Long> livePending = new java.util.HashMap<>();
		livePending.put("SLAYER", 3000L);
		SessionSnapshot sessionSnapshot = SessionSnapshot.capture(session, livePending);

		Instant atThreshold = T0.plusSeconds(60);
		CurrentSessionSnapshot at = CurrentSessionSnapshot.from(sessionSnapshot, atThreshold);
		assertEquals(3600L, at.getXpEntries().get(0).getXpGained());
		assertEquals(Long.valueOf(216_000L), at.getXpEntries().get(0).getXpPerHour());
	}

	@Test
	public void from_doesNotMutateSessionOrSnapshot()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 5000L);
		session.getAggregates().setSlayerProgressDelta(2);

		String sessionIdBefore = session.getSessionId();
		SessionState stateBefore = session.getState();
		long accumulatedBefore = session.getAccumulatedActiveDurationMillis();
		String lastActiveAtBefore = session.getLastActiveAt();
		int xpMapSizeBefore = session.getAggregates().getXpGainedBySkill().size();

		SessionSnapshot sessionSnapshot = SessionSnapshot.capture(session);
		int snapshotXpSizeBefore = sessionSnapshot.getXpGainedBySkill().size();

		CurrentSessionSnapshot.from(sessionSnapshot, T0.plus(Duration.ofMinutes(5)));

		assertEquals(sessionIdBefore, session.getSessionId());
		assertEquals(stateBefore, session.getState());
		assertEquals(accumulatedBefore, session.getAccumulatedActiveDurationMillis());
		assertEquals(lastActiveAtBefore, session.getLastActiveAt());
		assertEquals(xpMapSizeBefore, session.getAggregates().getXpGainedBySkill().size());
		assertEquals(snapshotXpSizeBefore, sessionSnapshot.getXpGainedBySkill().size());
	}

	@Test
	public void slayerTaskComplete_falseWhenNeverObserved()
	{
		Session session = activeSessionAt(T0);
		assertFalse(CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0).isSlayerTaskComplete());
	}

	@Test
	public void slayerTaskComplete_falseWhileRemainingIsPositive()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setLatestSlayerCurrentRemaining(5);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertFalse(snapshot.isSlayerTaskComplete());
		assertEquals(Integer.valueOf(5), snapshot.getSlayerCurrentRemaining());
	}

	@Test
	public void slayerTaskComplete_trueWhenRemainingReachesZero()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setLatestSlayerCurrentRemaining(0);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertTrue(snapshot.isSlayerTaskComplete());
	}

	@Test
	public void levelProgress_midLevel_matchesRealRuneliteXpTable()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().getXpGainedBySkill().put("STRENGTH", 500L);

		int level = 50;
		int xpForLevel = Experience.getXpForLevel(level);
		int xpForNextLevel = Experience.getXpForLevel(level + 1);
		long absoluteXp = xpForLevel + (xpForNextLevel - xpForLevel) / 2;

		Map<String, Long> absolute = new HashMap<>();
		absolute.put("STRENGTH", absoluteXp);

		SessionSnapshot sessionSnapshot = SessionSnapshot.capture(session, Collections.<String, Long>emptyMap(), absolute);
		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(sessionSnapshot, T0);

		assertEquals(1, snapshot.getXpEntries().size());
		CurrentSessionSnapshot.LevelProgress progress = snapshot.getXpEntries().get(0).getLevelProgress();
		assertNotNull(progress);
		assertEquals(level, progress.getCurrentLevel());
		assertFalse(progress.isMaxLevel());
		assertEquals(Long.valueOf(xpForNextLevel - absoluteXp), progress.getXpRemainingToNextLevel());
		assertTrue(progress.getProgressPercent() >= 0 && progress.getProgressPercent() <= 100);
	}

	@Test
	public void levelProgress_atLevel99_reportsMaxWithNoFurtherProgressFields()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().getXpGainedBySkill().put("ATTACK", 100L);

		Map<String, Long> absolute = new HashMap<>();
		absolute.put("ATTACK", (long) Experience.getXpForLevel(Experience.MAX_REAL_LEVEL));

		SessionSnapshot sessionSnapshot = SessionSnapshot.capture(session, Collections.<String, Long>emptyMap(), absolute);
		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(sessionSnapshot, T0);

		CurrentSessionSnapshot.LevelProgress progress = snapshot.getXpEntries().get(0).getLevelProgress();
		assertNotNull(progress);
		assertTrue(progress.isMaxLevel());
		assertEquals(Experience.MAX_REAL_LEVEL, progress.getCurrentLevel());
		assertNull(progress.getProgressPercent());
		assertNull(progress.getXpRemainingToNextLevel());
	}

	@Test
	public void levelProgress_nullWhenAbsoluteXpNeverObserved()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().getXpGainedBySkill().put("MINING", 250L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertEquals(1, snapshot.getXpEntries().size());
		assertEquals(250L, snapshot.getXpEntries().get(0).getXpGained());
		assertNull(snapshot.getXpEntries().get(0).getLevelProgress());
	}

	@Test
	public void xpEntries_suppressesOverallSkill()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().getXpGainedBySkill().put("OVERALL", 9999L);
		session.getAggregates().getXpGainedBySkill().put("MINING", 400L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertEquals(1, snapshot.getXpEntries().size());
		assertEquals("MINING", snapshot.getXpEntries().get(0).getSkill());
	}

	@Test
	public void primarySkill_slayerSession_isAlwaysSlayer()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().getXpGainedBySkill().put("ATTACK", 500L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertEquals("SLAYER", snapshot.getPrimarySkill());
	}

	@Test
	public void primarySkill_combatSession_prefersCoreCombatSkillOverHitpoints()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		ActivityIdentity combat = new ActivityIdentity(ActivityType.COMBAT, "combat:earthen_nagua", "Earthen Nagua");
		engine.onQualifyingActivity(combat, T0);
		Session session = engine.getCurrentSession();
		session.getAggregates().getXpGainedBySkill().put("HITPOINTS", 900L);
		session.getAggregates().getXpGainedBySkill().put("STRENGTH", 700L);
		session.getAggregates().getXpGainedBySkill().put("ATTACK", 200L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertEquals("STRENGTH", snapshot.getPrimarySkill());
	}

	@Test
	public void primarySkill_skillingSession_isHighestXpSkill()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		ActivityIdentity mining = new ActivityIdentity(ActivityType.SKILLING, "mining", "Mining");
		engine.onQualifyingActivity(mining, T0);
		Session session = engine.getCurrentSession();
		session.getAggregates().getXpGainedBySkill().put("MINING", 300L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertEquals("MINING", snapshot.getPrimarySkill());
	}

	@Test
	public void primarySkill_nullWhenNoXpAndNotSlayer()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		ActivityIdentity bossing = new ActivityIdentity(ActivityType.BOSSING, "zulrah", "Zulrah");
		engine.onQualifyingActivity(bossing, T0);
		Session session = engine.getCurrentSession();

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertNull(snapshot.getPrimarySkill());
	}

	@Test
	public void currentNpc_absentByDefault()
	{
		Session session = activeSessionAt(T0);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertNull(snapshot.getCurrentNpcName());
		assertNull(snapshot.getCurrentNpcId());
	}

	@Test
	public void currentNpc_surfacedWhenSupplied()
	{
		Session session = activeSessionAt(T0);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(
			SessionSnapshot.capture(session), T0, "Basilisk Knight", 7999);

		assertEquals("Basilisk Knight", snapshot.getCurrentNpcName());
		assertEquals(Integer.valueOf(7999), snapshot.getCurrentNpcId());
	}

	// ADDED (Spyglass Phase 1A/1B/1C/1D).

	@Test
	public void slayerKillsPerHour_nullBelowDurationThreshold()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setSlayerProgressDelta(3);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(
			SessionSnapshot.capture(session), T0.plusSeconds(30));

		assertNull(snapshot.getSlayerKillsPerHour());
	}

	@Test
	public void slayerKillsPerHour_computedAtOrAboveDurationThreshold()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setSlayerProgressDelta(3);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(
			SessionSnapshot.capture(session), T0.plusSeconds(60));

		// 3 task units in 60 seconds -> 180/hr.
		assertEquals(Long.valueOf(180L), snapshot.getSlayerKillsPerHour());
	}

	@Test
	public void slayerKillsPerHour_nullWhenNoKillsObservedYet()
	{
		Session session = activeSessionAt(T0);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(
			SessionSnapshot.capture(session), T0.plus(Duration.ofMinutes(5)));

		assertNull(snapshot.getSlayerKillsPerHour());
	}

	@Test
	public void slayerKillsPerHour_neverDerivedFromReliableCount_onlyFromSlayerProgressDelta()
	{
		Session session = activeSessionAt(T0);
		SessionAggregates.ReliableCount rc = new SessionAggregates.ReliableCount();
		rc.setKind(SessionAggregates.ReliableCountKind.KILLS);
		rc.setSourceKey("gargoyles:catacombs");
		rc.setSessionOccurrences(40);
		session.getAggregates().setReliableCount(rc);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(
			SessionSnapshot.capture(session), T0.plus(Duration.ofMinutes(5)));

		assertNull(snapshot.getSlayerKillsPerHour());
	}

	@Test
	public void estimatedSlayerXpRemaining_nullBelowMinKillsThreshold()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setSlayerProgressDelta(1);
		session.getAggregates().setLatestSlayerCurrentRemaining(10);
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 500L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertNull(snapshot.getEstimatedSlayerXpRemaining());
	}

	@Test
	public void estimatedSlayerXpRemaining_computedFromSessionObservedAverageOnly()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setSlayerProgressDelta(4);
		session.getAggregates().setLatestSlayerCurrentRemaining(10);
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 800L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		// (800 xp / 4 kills) * 10 remaining = 2000 -- session-observed data only, never a Wiki table.
		assertEquals(Long.valueOf(2000L), snapshot.getEstimatedSlayerXpRemaining());
	}

	@Test
	public void estimatedSlayerXpRemaining_nullWhenTaskAlreadyComplete()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setSlayerProgressDelta(5);
		session.getAggregates().setLatestSlayerCurrentRemaining(0);
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 1000L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertNull(snapshot.getEstimatedSlayerXpRemaining());
	}

	@Test
	public void estimatedSlayerXpRemaining_nullWhenNoSlayerXpObservedYet()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setSlayerProgressDelta(5);
		session.getAggregates().setLatestSlayerCurrentRemaining(10);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertNull(snapshot.getEstimatedSlayerXpRemaining());
	}

	@Test
	public void estimatedSlayerXpRemaining_nullWhenNoRemainingCountObserved()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setSlayerProgressDelta(5);
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 1000L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertNull(snapshot.getEstimatedSlayerXpRemaining());
	}

	@Test
	public void lootHeaderKillCount_nullWhenNothingReliableObserved()
	{
		Session session = activeSessionAt(T0);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertNull(snapshot.getLootHeaderKillCount());
	}

	@Test
	public void lootHeaderKillCount_prefersSlayerProgressDeltaOverReliableCount()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setSlayerProgressDelta(6);
		SessionAggregates.ReliableCount rc = new SessionAggregates.ReliableCount();
		rc.setKind(SessionAggregates.ReliableCountKind.KILLS);
		rc.setSourceKey("gargoyles:catacombs");
		rc.setSessionOccurrences(99);
		session.getAggregates().setReliableCount(rc);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertEquals(Integer.valueOf(6), snapshot.getLootHeaderKillCount());
	}

	@Test
	public void lootHeaderKillCount_fallsBackToReliableCountSessionOccurrences_forNonSlayerSession()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		ActivityIdentity bossing = new ActivityIdentity(ActivityType.BOSSING, "zulrah", "Zulrah");
		engine.onQualifyingActivity(bossing, T0);
		Session session = engine.getCurrentSession();
		SessionAggregates.ReliableCount rc = new SessionAggregates.ReliableCount();
		rc.setKind(SessionAggregates.ReliableCountKind.KILLS);
		rc.setSourceKey("zulrah");
		rc.setSessionOccurrences(12);
		session.getAggregates().setReliableCount(rc);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);

		assertEquals(Integer.valueOf(12), snapshot.getLootHeaderKillCount());
	}

	private static Session activeSessionAt(Instant at)
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, at);
		return engine.getCurrentSession();
	}
}
