package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

/**
 * NATIVE SLAYER MIGRATION, initial amount: the game's own original task size
 * (SLAYER_COUNT_ORIGINAL +/- modifier) is retained on a BOOTSTRAPPED
 * assignment record as authoritative metadata (assignedInitialAmount), while
 * bootstrapped provenance and every observed-only quantity -- units, XP,
 * active duration, initialAmount -- stay exactly as before. "Originally
 * assigned 157" never means Spyglass observed the first 115 kills.
 */
public class SlayerBootstrappedNativeInitialAmountTest extends SlayerCoordinatorTestSupport
{
	private static SessionAggregates.SlayerAssignmentRecord last(Session s)
	{
		List<SessionAggregates.SlayerAssignmentRecord> l = s.getAggregates().getSlayerAssignments();
		return l.get(l.size() - 1);
	}

	private void joinKalphitesMidTask(Instant at, Integer nativeInitial)
	{
		feed(SessionSignal.slayerTaskProgress(at, KALPHITES, KALPHITE_LOC, 1, 42, nativeInitial), at);
		feed(SessionSignal.slayerTaskProgress(at.plusSeconds(30), KALPHITES, KALPHITE_LOC, 1, 41, nativeInitial), at.plusSeconds(30));
	}

	@Test
	public void bootstrappedMidTask_retainsNativeOriginal_asMetadataOnly()
	{
		joinKalphitesMidTask(T0, 157);
		SessionAggregates.SlayerAssignmentRecord r = last(current());
		assertTrue("provenance unchanged", r.isBootstrapped());
		assertEquals(Integer.valueOf(157), r.getAssignedInitialAmount());
		assertNull("observed-assignment amount stays unknown", r.getInitialAmount());
		assertNull("assignment time stays unknown", r.getAssignedAt());
		assertEquals("only observed units", Integer.valueOf(2), r.getUnitsCompleted());
		assertEquals(Integer.valueOf(2), r.observedUnitsCompleted());

		CurrentSessionSnapshot.SlayerAssignmentView v = ui(T0.plusSeconds(30)).getSlayerAssignments().get(0);
		assertTrue(v.isBootstrapped());
		assertEquals(Integer.valueOf(2), v.getUnitsCompleted());
		assertNull("the player-facing total is not invented", v.getInitialAmount());
	}

	@Test
	public void bootstrappedRecord_withAndWithoutNativeOriginal_hasIdenticalObservedMetrics() throws Exception
	{
		joinKalphitesMidTask(T0, null);
		SessionAggregates.SlayerAssignmentRecord without = last(current());
		Integer unitsWithout = without.getUnitsCompleted();
		Long startWithout = without.getActiveDurationStartMillis();
		Object xpWithout = without.getXpGainedBySkill();

		setUpCoordinator();
		joinKalphitesMidTask(T0, 157);
		SessionAggregates.SlayerAssignmentRecord with = last(current());
		assertNull(without.getAssignedInitialAmount());
		assertEquals(Integer.valueOf(157), with.getAssignedInitialAmount());
		assertEquals(unitsWithout, with.getUnitsCompleted());
		assertEquals(startWithout, with.getActiveDurationStartMillis());
		assertEquals(xpWithout, with.getXpGainedBySkill());
		assertEquals(without.getLatestRemaining(), with.getLatestRemaining());
		assertEquals(without.isBootstrapped(), with.isBootstrapped());
	}

	@Test
	public void fullyObservedAssignment_keepsItsObservedInitialAmount_noNativeMetadataNeeded()
	{
		feed(SessionSignal.slayerTaskAssigned(T0, NECH, NECH_LOC, 120), T0);
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(60), NECH, NECH_LOC, 1, 119, 120), T0.plusSeconds(60));
		SessionAggregates.SlayerAssignmentRecord r = last(current());
		assertFalse(r.isBootstrapped());
		assertEquals(Integer.valueOf(120), r.getInitialAmount());
		assertNull(r.getAssignedInitialAmount());
	}

	@Test
	public void inconsistentNativeOriginal_smallerThanWhatSpyglassAlreadySaw_isRejected()
	{
		joinKalphitesMidTask(T0, 30); // remaining was 42 when Spyglass attached: 30 cannot be this instance
		assertNull(last(current()).getAssignedInitialAmount());
	}

	@Test
	public void nativeOriginalForADifferentTask_neverFillsThisRecord()
	{
		feed(SessionSignal.slayerTaskProgress(T0, KALPHITES, KALPHITE_LOC, 1, 42), T0);
		// A stray progress for another task key must not label this record.
		SessionSignal other = SessionSignal.slayerTaskProgress(T0.plusSeconds(5), NECH, NECH_LOC, 0, 100, 150);
		feed(other, T0.plusSeconds(5));
		for (SessionAggregates.SlayerAssignmentRecord r : current().getAggregates().getSlayerAssignments())
		{
			if (r.getAssignmentKey().equals(ActivitySignalClassifier.slayerIdentity(KALPHITES, KALPHITE_LOC).getActivityKey()))
			{
				assertNull(r.getAssignedInitialAmount());
			}
		}
	}

	@Test
	public void nativeOriginalArrivingLater_fillsOnce_neverOverwritten()
	{
		joinKalphitesMidTask(T0, null);
		assertNull(last(current()).getAssignedInitialAmount());
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(60), KALPHITES, KALPHITE_LOC, 1, 40, 157), T0.plusSeconds(60));
		assertEquals(Integer.valueOf(157), last(current()).getAssignedInitialAmount());
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(90), KALPHITES, KALPHITE_LOC, 1, 39, 200), T0.plusSeconds(90));
		assertEquals(Integer.valueOf(157), last(current()).getAssignedInitialAmount());
		assertEquals(Integer.valueOf(4), last(current()).getUnitsCompleted());
	}

	@Test
	public void persistsAcrossRestart_andOlderJsonLoadsWithNull() throws Exception
	{
		Instant base = Instant.now().minusSeconds(300);
		joinKalphitesMidTask(base, 157);
		String id = current().getSessionId();
		coordinator.finalizeForClientShutdownBlocking();
		restartPlugin();
		assertEquals(id, current().getSessionId());
		SessionAggregates.SlayerAssignmentRecord r = last(current());
		assertTrue(r.isBootstrapped());
		assertEquals(Integer.valueOf(157), r.getAssignedInitialAmount());

		SessionAggregates.SlayerAssignmentRecord legacy = GSON.fromJson(
			"{\"ordinal\":1,\"taskName\":\"Kalphites\",\"bootstrapped\":true,\"latestRemaining\":41,\"items\":[]}",
			SessionAggregates.SlayerAssignmentRecord.class);
		assertNull(legacy.getAssignedInitialAmount());
		assertNull(legacy.getInitialAmount());
	}

	@Test
	public void mapper_carriesProgressInitialAmountAsMetadata_zeroMeansNotYetKnown()
	{
		SessionSignal known = SessionEventMapper.map(EventType.SLAYER_TASK_PROGRESS,
			new EventPayloads.SlayerTaskProgress(KALPHITES, KALPHITE_LOC, 42, 41, -1, 1, 157, 2), T0);
		assertEquals(Integer.valueOf(157), known.getSlayerInitialAmount());
		assertEquals(Integer.valueOf(1), known.getSlayerUnitsConsumed());
		assertEquals(Integer.valueOf(41), known.getSlayerCurrentRemaining());

		SessionSignal syncing = SessionEventMapper.map(EventType.SLAYER_TASK_PROGRESS,
			new EventPayloads.SlayerTaskProgress(KALPHITES, KALPHITE_LOC, 42, 41, -1, 1, 0, 2), T0);
		assertNull(syncing.getSlayerInitialAmount());
	}
}
