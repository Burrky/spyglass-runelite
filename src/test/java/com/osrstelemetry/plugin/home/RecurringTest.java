package com.osrstelemetry.plugin.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.account.ChargeKnowledge;
import java.util.List;
import org.junit.Test;

/** Pass 29: Elnock's impling jars, the daily uses, and the recurring choice's persistence. */
public class RecurringTest
{
	private static final long DAY = Dailies.DAY_MILLIS;
	private static final long NOW = 20_754L * DAY + 10 * 3_600_000L;

	static Dailies.Read jars(int bought)
	{
		Dailies.Read r = new Dailies.Read();
		for (DailyTask t : DailyTask.values())
		{
			r.claim(t, 0);
			if (t.getGateVarbit() >= 0)
			{
				r.gate(t, 0);
			}
		}
		r.claim(DailyTask.IMPLING_JARS, bought);
		return r.morytania(0, 0).account(1, 0);
	}

	@Test
	public void implingJars_tenADay_partialPurchasesCount_andTheResetRestocks()
	{
		Dailies d = Dailies.empty().withTracked(DailyTask.IMPLING_JARS, true).with(jars(4), true, NOW);
		Dailies.Status s = d.status(DailyTask.IMPLING_JARS, NOW);
		assertEquals("no unlock beyond a members' world (ironmen too)", Dailies.State.AVAILABLE, s.getState());
		assertEquals(6, s.getRemaining());
		assertEquals(10, s.getUses());
		assertEquals(Dailies.State.DONE, d.with(jars(10), false, NOW + 60_000).status(DailyTask.IMPLING_JARS, NOW + 60_000).getState());
		Dailies.Status next = d.with(jars(10), false, NOW + 60_000).status(DailyTask.IMPLING_JARS, NOW + DAY);
		assertEquals(10, next.getRemaining());
		assertTrue(next.isNewDay());
		assertEquals(Dailies.State.NOT_READ, Dailies.empty().status(DailyTask.IMPLING_JARS, NOW).getState());
	}

	@Test
	public void dailyUses_fromTheGame_withTheirLimits_andOnlyAsLastReadAfterTheReset()
	{
		DailyUses u = DailyUses.empty().with(new DailyUses.Read(2, 12, 1, 4, 1), NOW);
		List<DailyUses.Status> s = u.statuses(ChargeKnowledge.EMPTY, NOW);
		assertEquals(18, s.get(0).getLeft()); // alchemy 30 - 12
		assertEquals(2, s.get(1).getLeft()); // ring 2: 3 restores - 1
		assertEquals(3, s.get(1).getLimit());
		assertEquals(1, s.get(2).getLeft()); // shield 4: twice a day - 1
		assertTrue(s.get(2).isFresh());
		assertFalse("never reported: unknown, not zero", s.get(3).isKnown());
		List<DailyUses.Status> tomorrow = u.statuses(ChargeKnowledge.EMPTY, NOW + DAY);
		assertFalse("not claimed as refreshed before it is read again", tomorrow.get(0).isFresh());
		assertEquals(18, tomorrow.get(0).getLeft());
		DailyUses none = DailyUses.empty().with(new DailyUses.Read(0, 0, 0, 0, 0), NOW);
		assertTrue(none.statuses(ChargeKnowledge.EMPTY, NOW).get(0).isLocked());
		assertTrue(none.statuses(ChargeKnowledge.EMPTY, NOW).get(2).isLocked());
		ChargeKnowledge cloak = ChargeKnowledge.EMPTY.withMessage("You have used 2 of your 5 Ardougne Farm teleports for today.", false, NOW);
		assertEquals(3, u.statuses(cloak, NOW).get(3).getLeft());
	}

	@Test
	public void choice_andReadings_saveAndRestore_perProfileDocument()
	{
		Recurring r = Recurring.empty().withShown(Recurring.Entry.TEARS, true).withShown(Recurring.Entry.PRAYER, true)
			.withUses(DailyUses.empty().with(new DailyUses.Read(4, 3, 0, 1, 0), NOW));
		Recurring back = new Gson().fromJson(new Gson().toJson(r), Recurring.class);
		assertTrue(back.isShown(Recurring.Entry.TEARS));
		assertFalse(back.isShown(Recurring.Entry.ALCHEMY));
		assertEquals(27, back.uses().statuses(null, NOW).get(0).getLeft());
		assertFalse(Recurring.empty().isChosen());
	}
}
