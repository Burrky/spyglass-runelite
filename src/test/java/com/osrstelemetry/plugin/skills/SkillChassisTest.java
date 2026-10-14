package com.osrstelemetry.plugin.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * The generic skill-companion chassis: the production ledger (TOTAL vs
 * BANKED vs where things went), method inertia + method time, the observed
 * rate window, and the live/durable merge. Skill-agnostic by construction
 * (item ids and method keys are just numbers and strings here).
 */
public class SkillChassisTest
{
	// ---- ProductionLedger ----------------------------------------------------------------------

	@Test
	public void ledger_totalOnlyEverGrows_banksOnlyHeldProduction()
	{
		ProductionLedger l = new ProductionLedger();
		l.produced(1, 10, ProductionLedger.Destination.INVENTORY);
		l.inventoryRemoved(1, 4);
		assertEquals("dropped 4: only 6 still bankable", 6L, l.heldInInventory(1));
		assertEquals(6L, l.bankedFromInventory(1, 9));
		ProductionLedger.Drain d = l.drain();
		assertEquals(Long.valueOf(10), d.getProduced().get(1));
		assertEquals(Long.valueOf(6), d.getBanked().get(1));
		assertFalse(l.hasPending());
		assertEquals("nothing left to bank", 0L, l.bankedFromInventory(1, 5));
	}

	@Test
	public void ledger_storageMovesAreNeverProductionOrBanking()
	{
		ProductionLedger l = new ProductionLedger();
		l.produced(7, 3, ProductionLedger.Destination.STORAGE);
		l.storageToInventory(7, 3);
		l.inventoryToStorage(7, 1);
		ProductionLedger.Drain d = l.drain();
		assertEquals(Long.valueOf(3), d.getProduced().get(7));
		assertTrue(d.getBanked().isEmpty());
		assertEquals(2L, l.heldInInventory(7));
		assertEquals(1L, l.heldInStorage(7));
		assertEquals(1L, l.bankedFromStorage(7, 5));
	}

	@Test
	public void ledger_forgetHeld_newSessionNeverBanksTheOldOnes()
	{
		ProductionLedger l = new ProductionLedger();
		l.produced(1, 28, ProductionLedger.Destination.INVENTORY);
		l.drain();
		l.forgetHeld();
		assertEquals(0L, l.bankedFromInventory(1, 28));
	}

	@Test
	public void ledger_discardPending_keepsHeld()
	{
		ProductionLedger l = new ProductionLedger();
		l.produced(1, 2, ProductionLedger.Destination.INVENTORY);
		l.discardPending();
		assertFalse(l.hasPending());
		assertEquals(2L, l.heldInInventory(1));
	}

	// ---- MethodTracker -------------------------------------------------------------------------

	@Test
	public void method_adoptedOnFirstSuccess_switchesOnlyWithConfirmation()
	{
		MethodTracker m = new MethodTracker();
		assertTrue(m.success("iron", 0));
		assertEquals("iron", m.getCurrent());
		assertFalse(m.success("coal", 1_000));
		assertEquals("iron", m.getCurrent());
		m.success("iron", 2_000);
		assertFalse("a current success resets the half-built switch", m.success("coal", 3_000));
		assertTrue(m.success("coal", 4_000));
		assertEquals("coal", m.getCurrent());
		assertEquals(1L, m.getTransitions());
	}

	@Test
	public void method_freshHintSwitchesOnOneSuccess_staleHintDoesNot()
	{
		MethodTracker m = new MethodTracker();
		m.success("iron", 0);
		m.hint("coal", 1_000);
		assertTrue(m.success("coal", 2_000));
		m.hint("amethyst", 10_000);
		assertFalse(m.success("amethyst", 10_000 + MethodTracker.HINT_FRESH_MILLIS + 1));
	}

	@Test
	public void method_nullSuccessNeverAdopts_butKeepsTheTrainingClock()
	{
		MethodTracker m = new MethodTracker();
		assertFalse(m.success(null, 0));
		assertNull(m.getCurrent());
		assertEquals(0L, m.getLastSuccessAt());
	}

	@Test
	public void methodTime_countsWhileTraining_stopsAfterIdle_capsEachStep()
	{
		MethodTracker m = new MethodTracker();
		m.step(0);
		m.success("iron", 0);
		for (long t = 600; t <= 60_000; t += 600)
		{
			m.step(t);
		}
		assertEquals(60_000L, (long) m.getSessionMillis().get("iron"));
		// A frozen client: one huge gap books at most one capped step.
		m.step(64_000);
		assertEquals(60_000L + 4_000L, (long) m.getSessionMillis().get("iron"));
		// Idle beyond the cutoff: nothing more.
		m.step(60_000 + MethodTracker.IDLE_CUTOFF_MILLIS + 5_000);
		m.step(60_000 + MethodTracker.IDLE_CUTOFF_MILLIS + 5_600);
		assertEquals(64_000L, (long) m.getSessionMillis().get("iron"));
		assertEquals("drained once", 64_000L, (long) m.drainMillis().get("iron"));
		assertTrue(m.drainMillis().isEmpty());
	}

	@Test
	public void method_newSession_keepsTheMethod_restartsItsTime()
	{
		MethodTracker m = new MethodTracker();
		m.step(0);
		m.success("iron", 0);
		m.step(600);
		m.newSession();
		assertEquals("iron", m.getCurrent());
		assertTrue(m.getSessionMillis().isEmpty());
	}

	// ---- RateWindow ----------------------------------------------------------------------------

	@Test
	public void rate_needsSamplesAndSpan_restartsAfterAGap()
	{
		RateWindow w = new RateWindow(12, 4, 20_000, 60_000);
		w.record(0, 1);
		w.record(1_000, 1);
		assertNull("two samples are not a rate", w.unitsPerMilli(1_000));
		w.record(10_000, 1);
		w.record(20_000, 1);
		// 3 units over 20 s after the opening sample.
		assertEquals(3 / 20_000d, w.unitsPerMilli(20_000), 1e-12);
		assertEquals(Long.valueOf(60_000L), w.millisUntil(9, 20_000));
		assertNull("stale once the player stopped", w.unitsPerMilli(20_000 + 60_001));
		w.record(200_000, 1);
		assertEquals("a long gap starts over", 1, w.size());
		assertEquals(Long.valueOf(0L), w.millisUntil(0, 200_000));
	}

	// ---- merge -----------------------------------------------------------------------------------

	@Test
	public void merge_boundUsesMax_unboundAdds_neverDoubleCounts()
	{
		Map<Integer, Long> session = map(1, 10L);
		Map<Integer, Long> flushed = map(1, 12L);
		Map<Integer, Long> pending = map(1, 3L);
		assertEquals("hand-off tick: flushed ahead", Long.valueOf(15), SkillProductionMerge.merge(session, flushed, pending, true).get(1));
		assertEquals("caught up", Long.valueOf(15), SkillProductionMerge.merge(map(1, 12L), flushed, pending, true).get(1));
		assertEquals("after a restart the session is ahead", Long.valueOf(13),
			SkillProductionMerge.merge(session, Collections.<Integer, Long>emptyMap(), pending, true).get(1));
		assertEquals("not bound: flushed belongs to another session", Long.valueOf(13),
			SkillProductionMerge.merge(session, flushed, pending, false).get(1));
	}

	private static Map<Integer, Long> map(int k, long v)
	{
		Map<Integer, Long> m = new HashMap<>();
		m.put(k, v);
		return m;
	}
}
