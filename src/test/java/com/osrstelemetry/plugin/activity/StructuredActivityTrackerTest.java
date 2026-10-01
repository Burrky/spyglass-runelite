package com.osrstelemetry.plugin.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/** ARMED / ACTIVE / LOCKED semantics of the transient recognition tracker. */
public class StructuredActivityTrackerTest
{
	private final StructuredActivityRegistry registry = StructuredActivityRegistry.defaults();
	private final StructuredActivityDefinition gotr = registry.byKey(StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT);
	private final StructuredActivityDefinition tempoross = registry.byKey(StructuredActivityCatalog.TEMPOROSS);

	@Test
	public void startsOutside_andConfirmationsOutsideAreIgnored()
	{
		StructuredActivityTracker t = new StructuredActivityTracker();
		assertEquals(RecognitionState.OUTSIDE, t.getState());
		t.confirm(1L);
		assertEquals(RecognitionState.OUTSIDE, t.getState());
		assertNull(t.getDefinition());
	}

	@Test
	public void locationArmsOnly()
	{
		StructuredActivityTracker t = new StructuredActivityTracker();
		t.onLocation(gotr, LocationPolicy.EXCLUSIVE);
		assertEquals(RecognitionState.ARMED, t.getState());
		assertSame(gotr, t.getDefinition());
		assertEquals(0, t.getDistinctConfirmations());
		// A repeated location observation is not a confirmation.
		t.onLocation(gotr, LocationPolicy.EXCLUSIVE);
		assertEquals(RecognitionState.ARMED, t.getState());
	}

	@Test
	public void firstEvidenceActivates_distinctTicksLock()
	{
		StructuredActivityTracker t = new StructuredActivityTracker();
		t.onLocation(gotr, LocationPolicy.EXCLUSIVE);
		t.confirm(10L);
		assertEquals(RecognitionState.ACTIVE, t.getState());
		t.confirm(11L);
		assertEquals(RecognitionState.ACTIVE, t.getState());
		t.confirm(12L);
		assertEquals(RecognitionState.LOCKED, t.getState());
		assertEquals(StructuredActivityTracker.LOCK_DISTINCT_CONFIRMATIONS, t.getDistinctConfirmations());
	}

	@Test
	public void sameTickDuplicatesCountOnce()
	{
		StructuredActivityTracker t = new StructuredActivityTracker();
		t.onLocation(gotr, LocationPolicy.EXCLUSIVE);
		for (int i = 0; i < 10; i++)
		{
			t.confirm(42L);
		}
		assertEquals(1, t.getDistinctConfirmations());
		assertEquals(RecognitionState.ACTIVE, t.getState());
		t.confirm(43L);
		t.confirm(43L);
		assertEquals(RecognitionState.ACTIVE, t.getState());
	}

	@Test
	public void leavingClears_reEnteringReArmsFromScratch()
	{
		StructuredActivityTracker t = new StructuredActivityTracker();
		t.onLocation(gotr, LocationPolicy.EXCLUSIVE);
		t.confirm(1L);
		t.confirm(2L);
		t.confirm(3L);
		t.onLocation(null, null);
		assertEquals(RecognitionState.OUTSIDE, t.getState());
		assertNull(t.getPolicy());
		t.onLocation(gotr, LocationPolicy.EXCLUSIVE);
		assertEquals(RecognitionState.ARMED, t.getState());
		assertEquals(0, t.getDistinctConfirmations());
	}

	@Test
	public void movingBetweenRegionsOfOneActivityKeepsRecognition_policyFollowsRegion()
	{
		StructuredActivityTracker t = new StructuredActivityTracker();
		t.onLocation(tempoross, LocationPolicy.EXCLUSIVE);
		t.confirm(1L);
		t.confirm(2L);
		t.confirm(3L);
		t.onLocation(tempoross, LocationPolicy.NON_EXCLUSIVE);
		assertEquals(RecognitionState.LOCKED, t.getState());
		assertEquals(LocationPolicy.NON_EXCLUSIVE, t.getPolicy());
	}

	@Test
	public void enteringADifferentActivityReArms()
	{
		StructuredActivityTracker t = new StructuredActivityTracker();
		t.onLocation(gotr, LocationPolicy.EXCLUSIVE);
		t.confirm(1L);
		t.onLocation(tempoross, LocationPolicy.EXCLUSIVE);
		assertEquals(RecognitionState.ARMED, t.getState());
		assertSame(tempoross, t.getDefinition());
	}
}
