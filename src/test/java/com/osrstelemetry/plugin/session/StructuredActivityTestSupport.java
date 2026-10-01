package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.activity.RecognitionState;
import java.time.Instant;

/** Shared coordinator-level helpers for the structured-activity tests. */
abstract class StructuredActivityTestSupport extends SlayerCoordinatorTestSupport
{
	static final String GOTR = "guardians_of_the_rift";
	static final int GOTR_REGION = 14484;
	static final String BARROWS = "barrows";
	static final int BARROWS_SURFACE = 14131;
	static final int LUMBRIDGE = 12850;

	/** Every signal on ONE game tick, then close that tick. */
	void onTick(Instant at, SessionSignal... signals)
	{
		for (SessionSignal s : signals)
		{
			coordinator.testEnqueueSignal(s.withGameTick(tick));
		}
		coordinator.testProcessTick(++tick, at);
	}

	static SessionSignal enter(String key, int region, Instant at)
	{
		return SessionSignal.structuredActivityLocation(at, key, region);
	}

	static SessionSignal leave(Instant at)
	{
		return SessionSignal.structuredActivityLocation(at, null, LUMBRIDGE);
	}

	static SessionSignal xpAt(String skill, long delta, Instant at)
	{
		return SessionSignal.xpChange(at, skill, delta, at.minusSeconds(3));
	}

	static SessionSignal gotrGame(int runningCount, Instant at)
	{
		return SessionSignal.structuredActivityCompletion(at, GOTR, runningCount);
	}

	RecognitionState recognition()
	{
		return classifierContext().getStructuredActivity().getState();
	}

	ClassifierContext classifierContext()
	{
		try
		{
			java.lang.reflect.Field f = SessionRuntimeCoordinator.class.getDeclaredField("classifierContext");
			f.setAccessible(true);
			return (ClassifierContext) f.get(coordinator);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	static boolean isGotr(Session s)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.STRUCTURED_ACTIVITY
			&& GOTR.equals(s.getActivityIdentity().getActivityKey());
	}

	/** Enter GotR and play a few distinct ticks of expected XP: locked, returns the session. */
	Session lockedGotr(Instant base)
	{
		onTick(base, enter(GOTR, GOTR_REGION, base));
		onTick(base.plusSeconds(10), xpAt("MINING", 50, base.plusSeconds(10)));
		onTick(base.plusSeconds(20), xpAt("CRAFTING", 20, base.plusSeconds(20)));
		onTick(base.plusSeconds(30), xpAt("RUNECRAFT", 300, base.plusSeconds(30)));
		assertEquals(RecognitionState.LOCKED, recognition());
		assertTrue(isGotr(current()));
		return current();
	}

}
