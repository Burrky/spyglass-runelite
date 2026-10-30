package com.osrstelemetry.plugin.hunter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;

/** Pass 29: the bird house spaces as read, and their timers. */
public class BirdhouseTrapsTest
{
	static final long T0 = 1_800_000_000_000L;
	static final long MIN = 60_000L;

	/** Redwood (tier 8): built 25, some seeds 26, seeded 27. */
	static final int R_BUILT = 25;
	static final int R_PART = 26;
	static final int R_SEEDED = 27;

	static Map<BirdhouseSpace, Integer> all(int a, int b, int c, int d)
	{
		Map<BirdhouseSpace, Integer> m = new EnumMap<>(BirdhouseSpace.class);
		m.put(BirdhouseSpace.MEADOW_NORTH, a);
		m.put(BirdhouseSpace.MEADOW_SOUTH, b);
		m.put(BirdhouseSpace.VALLEY_NORTH, c);
		m.put(BirdhouseSpace.VALLEY_SOUTH, d);
		return m;
	}

	@Test
	public void decode_followsTheCacheTable()
	{
		assertEquals(BirdhouseState.Stage.SPACE, BirdhouseState.of(0).getStage());
		assertEquals(BirdhouseState.Stage.BUILT, BirdhouseState.of(1).getStage());
		assertEquals(BirdhouseTier.REGULAR, BirdhouseState.of(3).getTier());
		assertEquals(BirdhouseState.Stage.PARTLY_SEEDED, BirdhouseState.of(5).getStage());
		assertEquals(BirdhouseTier.OAK, BirdhouseState.of(5).getTier());
		assertEquals(BirdhouseState.Stage.SEEDED, BirdhouseState.of(R_SEEDED).getStage());
		assertEquals(BirdhouseTier.REDWOOD, BirdhouseState.of(R_SEEDED).getTier());
		assertEquals("never guessed", BirdhouseState.Stage.UNKNOWN, BirdhouseState.of(28).getStage());
	}

	@Test
	public void unseen_isNeverReadyOrEmpty()
	{
		BirdhouseTraps t = BirdhouseTraps.empty();
		for (BirdhouseTraps.Status s : t.statuses(T0 + 1000 * MIN))
		{
			assertEquals(BirdhouseTraps.Status.Kind.UNSEEN, s.getKind());
		}
		BirdhouseTraps.Summary s = t.summary(T0);
		assertEquals(0, s.ready);
		assertEquals(4, s.unseen);
		assertFalse(t.shouldNotify(T0 + 1000 * MIN));
	}

	@Test
	public void seededSeen_isTimedExactly_firstSeenSeeded_isAnUpperBound()
	{
		BirdhouseTraps t = BirdhouseTraps.empty().withReading(all(R_BUILT, R_SEEDED, 0, R_PART), T0);
		BirdhouseTraps.Status bound = t.status(BirdhouseSpace.MEADOW_SOUTH, T0);
		assertEquals(BirdhouseTraps.Status.Kind.CATCHING, bound.getKind());
		assertTrue(bound.isUpperBound());
		assertEquals(T0 + 50 * MIN, bound.getReadyAt());
		t = t.withReading(all(R_SEEDED, R_SEEDED, 0, R_SEEDED), T0 + 2 * MIN);
		BirdhouseTraps.Status exact = t.status(BirdhouseSpace.MEADOW_NORTH, T0 + 2 * MIN);
		assertFalse(exact.isUpperBound());
		assertEquals(T0 + 52 * MIN, exact.getReadyAt());
		assertEquals(BirdhouseTraps.Status.Kind.READY, t.status(BirdhouseSpace.MEADOW_NORTH, T0 + 52 * MIN).getKind());
		assertEquals(BirdhouseTraps.Status.Kind.SPACE, t.status(BirdhouseSpace.VALLEY_NORTH, T0).getKind());
		BirdhouseTraps.Summary s = t.summary(T0 + 51 * MIN);
		assertEquals(1, s.ready);
		assertEquals(2, s.catching);
		assertEquals(1, s.needsSetup);
		assertEquals(T0 + 52 * MIN, s.allReadyAt);
	}

	@Test
	public void restartAndTimeAway_keepEveryTimer_andTheSameReadingChangesNothing()
	{
		BirdhouseTraps t = BirdhouseTraps.empty().withReading(all(R_BUILT, R_BUILT, R_BUILT, R_BUILT), T0)
			.withReading(all(R_SEEDED, R_SEEDED, R_SEEDED, R_SEEDED), T0 + MIN);
		BirdhouseTraps back = new Gson().fromJson(new Gson().toJson(t), BirdhouseTraps.class);
		assertEquals(T0 + 51 * MIN, back.status(BirdhouseSpace.VALLEY_SOUTH, T0 + 3 * 60 * MIN).getReadyAt());
		assertSame(back, back.withReading(all(R_SEEDED, R_SEEDED, R_SEEDED, R_SEEDED), T0 + MIN + 30_000L));
		assertEquals(BirdhouseTraps.Status.Kind.READY, back.status(BirdhouseSpace.VALLEY_SOUTH, T0 + 3 * 60 * MIN).getKind());
	}

	@Test
	public void manySpacesDroppingAtOnce_isIgnored_oneEmptied_isRead()
	{
		BirdhouseTraps t = BirdhouseTraps.empty().withReading(all(R_SEEDED, R_SEEDED, R_SEEDED, R_SEEDED), T0);
		assertSame("not sent yet after arriving (RuneLite's guard)", t, t.withReading(all(0, 0, 0, R_SEEDED), T0 + MIN));
		BirdhouseTraps emptied = t.withReading(all(0, R_SEEDED, R_SEEDED, R_SEEDED), T0 + MIN);
		assertEquals(BirdhouseTraps.Status.Kind.SPACE, emptied.status(BirdhouseSpace.MEADOW_NORTH, T0 + MIN).getKind());
		assertNull(emptied.trap(BirdhouseSpace.MEADOW_NORTH).getArmedAt());
	}

	@Test
	public void notification_onceWhenEverySeenHouseIsFull()
	{
		BirdhouseTraps t = BirdhouseTraps.empty().withReading(all(R_BUILT, R_BUILT, 0, 0), T0).withReading(all(R_SEEDED, R_SEEDED, 0, 0), T0 + MIN);
		assertFalse(t.shouldNotify(T0 + 30 * MIN));
		assertTrue(t.shouldNotify(T0 + 51 * MIN));
		BirdhouseTraps n = t.withNotified(T0 + 51 * MIN);
		assertFalse(n.shouldNotify(T0 + 90 * MIN));
	}
}
