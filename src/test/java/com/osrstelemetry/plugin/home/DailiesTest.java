package com.osrstelemetry.plugin.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Test;

/** The daily checklist (canonical Home §11): reset rules, uses, unlocks, unread never "to do", persistence. */
public class DailiesTest
{
	private static final long DAY = Dailies.DAY_MILLIS;
	/** 2026-10-28 10:00 UTC. */
	private static final long NOW = 20_754L * DAY + 10 * 3_600_000L;

	private static Dailies.Read read(int zaff, int robin, int herbBoxes)
	{
		Dailies.Read r = new Dailies.Read();
		for (DailyTask t : DailyTask.values())
		{
			r.claim(t, 0);
			if (t.getGateVarbit() >= 0)
			{
				r.gate(t, t == DailyTask.SAND ? 160 : 1);
			}
		}
		r.claim(DailyTask.BATTLESTAVES, zaff).claim(DailyTask.BONEMEAL, robin).claim(DailyTask.HERB_BOXES, herbBoxes);
		return r.morytania(1, 0).account(0, 20_000);
	}

	@Test
	public void nothingRead_isNotRead_neverToDo()
	{
		Dailies d = Dailies.empty().withTracked(DailyTask.BATTLESTAVES, true);
		assertEquals(Dailies.State.NOT_READ, d.status(DailyTask.BATTLESTAVES, NOW).getState());
		assertEquals(0, d.availableCount(NOW));
	}

	@Test
	public void claimedToday_isDone_andTheResetMakesItAvailable_evenWhileLoggedInAcrossMidnight()
	{
		Dailies d = Dailies.empty().withTracked(DailyTask.BATTLESTAVES, true).with(read(1, 0, 0), true, NOW);
		assertEquals(Dailies.State.DONE, d.status(DailyTask.BATTLESTAVES, NOW).getState());
		// Still logged in after 00:00 UTC, the value unchanged: the reset has passed, so it's available ("new day").
		Dailies later = d.with(read(1, 0, 0), false, NOW + DAY);
		assertSame("an unchanged value keeps its day: nothing to save", d, later);
		Dailies.Status s = later.status(DailyTask.BATTLESTAVES, NOW + DAY);
		assertEquals(Dailies.State.AVAILABLE, s.getState());
		assertTrue(s.isNewDay());
		// Logging in again re-reads: the game's value is the truth from then on.
		assertEquals(Dailies.State.DONE, later.with(read(1, 0, 0), true, NOW + DAY).status(DailyTask.BATTLESTAVES, NOW + DAY).getState());
		assertEquals(Dailies.State.AVAILABLE, later.with(read(0, 0, 0), true, NOW + DAY).status(DailyTask.BATTLESTAVES, NOW + DAY).getState());
	}

	@Test
	public void counted_dailies_keepUsesLeft_apartFromCompletion()
	{
		Dailies d = Dailies.empty().with(read(0, 4, 15), true, NOW);
		Dailies.Status robin = d.status(DailyTask.BONEMEAL, NOW);
		assertEquals("medium and hard Morytania: 26 a day", 26, robin.getUses());
		assertEquals(22, robin.getRemaining());
		assertEquals(Dailies.State.AVAILABLE, robin.getState());
		Dailies.Status boxes = d.status(DailyTask.HERB_BOXES, NOW);
		assertEquals(Dailies.State.DONE, boxes.getState());
		assertEquals(0, boxes.getRemaining());
	}

	@Test
	public void unlocks_andAccountTypes_gateEachDaily()
	{
		Dailies.Read r = read(0, 0, 0).gate(DailyTask.ESSENCE, 0).account(2, 0);
		Dailies d = Dailies.empty().with(r, true, NOW);
		assertEquals(Dailies.State.LOCKED, d.status(DailyTask.ESSENCE, NOW).getState());
		assertEquals("Needs Ardougne medium diary", d.status(DailyTask.ESSENCE, NOW).getNote());
		assertEquals("ultimate ironmen can't take Bert's sand", Dailies.State.LOCKED, d.status(DailyTask.SAND, NOW).getState());
		assertEquals("no ironman takes herb boxes", Dailies.State.LOCKED, d.status(DailyTask.HERB_BOXES, NOW).getState());
		Dailies poor = Dailies.empty().with(read(0, 0, 0).account(0, 100), true, NOW);
		assertEquals(Dailies.State.NEEDS, poor.status(DailyTask.HERB_BOXES, NOW).getState());
	}

	@Test
	public void theChoiceAndReadings_surviveSaving()
	{
		Gson gson = new Gson();
		Dailies d = Dailies.empty().withTracked(DailyTask.FLAX, true).with(read(1, 3, 0), true, NOW);
		Dailies back = gson.fromJson(gson.toJson(d), Dailies.class);
		assertTrue(back.isTracked(DailyTask.FLAX));
		assertFalse(back.isTracked(DailyTask.BATTLESTAVES));
		assertEquals(Dailies.State.DONE, back.status(DailyTask.BATTLESTAVES, NOW).getState());
		assertEquals(23, back.status(DailyTask.BONEMEAL, NOW).getRemaining());
		assertEquals(14 * 3_600_000L, Dailies.untilReset(NOW));
	}
}
