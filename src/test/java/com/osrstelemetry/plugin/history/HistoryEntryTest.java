package com.osrstelemetry.plugin.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionLifecycleEngine;
import java.time.Instant;
import org.junit.Test;

/**
 * Pure
 * coverage of HistoryEntry.from()'s summary-building and its
 * fail-open null-return contract. Uses only PUBLIC
 * session-package API (SessionLifecycleEngine, Session's getters,
 * SessionAggregates' live map) -- this test class deliberately lives
 * in the history package, the same package HistoryEntry/HistoryIndex/
 * HistoryCoordinator live in, and never needs Session's
 * package-private setters.
 */
public class HistoryEntryTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final ActivityIdentity GARGOYLES =
		new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");

	private static Session buildFinalizedSession()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);
		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);
		return engine.advanceTime(expiry).getFinalized();
	}

	@Test
	public void from_validFinalizedSession_buildsSummary()
	{
		Session session = buildFinalizedSession();

		HistoryEntry entry = HistoryEntry.from(session);

		assertNotNull(entry);
		assertEquals(session.getSessionId(), entry.getSessionId());
		assertEquals(GARGOYLES, entry.getActivityIdentity());
		assertEquals(session.getFinalizedAt(), entry.getFinalizedAt());
		assertEquals(session.getStartedAt(), entry.getStartedAt());
		assertEquals(session.getAccumulatedActiveDurationMillis(), entry.getAccumulatedActiveDurationMillis());
		assertFalse("no loadout was ever attached to this session", entry.isHasStartingLoadout());
		assertFalse(entry.isHasEndingLoadout());
	}

	@Test
	public void from_nullSession_returnsNull()
	{
		assertNull(HistoryEntry.from(null));
	}

	@Test
	public void from_sessionMissingFinalizedAt_returnsNull()
	{
		// An ACTIVE session -- finalizedAt is null. HistoryEntry.from()
		// must refuse it rather than fabricate a "finalized" summary.
		Session session = new SessionLifecycleEngine().onQualifyingActivity(GARGOYLES, T0).getCurrent();

		assertNull(HistoryEntry.from(session));
	}

	@Test
	public void from_sumsXpAcrossAllSkills()
	{
		Session session = buildFinalizedSession();
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 500L);
		session.getAggregates().getXpGainedBySkill().put("STRENGTH", 120L);

		HistoryEntry entry = HistoryEntry.from(session);

		assertEquals(620L, entry.getTotalXpGained());
	}

	@Test
	public void from_countsLootDropGroups()
	{
		Session session = buildFinalizedSession();
		session.getAggregates().getLootDrops().add(new com.osrstelemetry.plugin.session.SessionAggregates.LootDropGroup());
		session.getAggregates().getLootDrops().add(new com.osrstelemetry.plugin.session.SessionAggregates.LootDropGroup());

		HistoryEntry entry = HistoryEntry.from(session);

		assertEquals(2, entry.getLootDropCount());
	}

	@Test
	public void from_emptyAggregates_totalXpZero_noException()
	{
		Session session = buildFinalizedSession();

		HistoryEntry entry = HistoryEntry.from(session);

		assertEquals(0L, entry.getTotalXpGained());
		assertEquals(0, entry.getLootDropCount());
	}
}
