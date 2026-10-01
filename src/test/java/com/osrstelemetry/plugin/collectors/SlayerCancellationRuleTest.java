package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.model.SlayerState;
import org.junit.Test;

/** A task that disappears is only called cancelled on positive evidence (points down, streak not advanced). */
public class SlayerCancellationRuleTest
{
	private static SlayerState state(int points, int streak, String label)
	{
		SlayerState s = new SlayerState();
		s.setPoints(points);
		s.setStreak(streak);
		s.setStreakLabel(label);
		return s;
	}

	@Test
	public void pointsDownAndStreakUnchanged_isCancellation()
	{
		assertTrue(SlayerCollector.looksCancelled(state(100, 5, "NORMAL"), state(70, 5, "NORMAL")));
	}

	@Test
	public void completion_isNeverCancellation()
	{
		assertFalse(SlayerCollector.looksCancelled(state(100, 5, "NORMAL"), state(115, 6, "NORMAL")));
		// Early tasks award no points: streak still advances.
		assertFalse(SlayerCollector.looksCancelled(state(0, 2, "NORMAL"), state(0, 3, "NORMAL")));
	}

	@Test
	public void unsettledOrIncomparableState_staysACompletion()
	{
		assertFalse(SlayerCollector.looksCancelled(state(100, 5, "NORMAL"), state(100, 5, "NORMAL")));
		assertFalse(SlayerCollector.looksCancelled(state(100, 5, "NORMAL"), state(70, 1, "WILDERNESS")));
		assertFalse(SlayerCollector.looksCancelled(null, state(70, 5, "NORMAL")));
	}
}
