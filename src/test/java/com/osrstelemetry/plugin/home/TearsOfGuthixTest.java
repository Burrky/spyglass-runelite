package com.osrstelemetry.plugin.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Test;

/** Pass 29: Tears of Guthix's weekly availability from the game's own values and Juna's words. */
public class TearsOfGuthixTest
{
	static final long DAY = TearsOfGuthix.DAY_MILLIS;
	/** Monday 2026-10-26 00:00 UTC. */
	static final long MON = 1792972800000L;

	static TearsOfGuthix.Read read(boolean quest, int qp, int qpBefore, int tears, boolean collecting, long xp)
	{
		return new TearsOfGuthix.Read(quest, qp, qpBefore, tears, collecting, xp);
	}

	/** A player who played before (QP 200 at the last game), now playing a game that ends at {@code end}. */
	static TearsOfGuthix played(long end, long xpAfter)
	{
		TearsOfGuthix t = TearsOfGuthix.empty().with(read(true, 200, 190, 120, false, 5_000_000L), true, end - 60_000L);
		t = t.with(read(true, 200, 190, 120, true, 5_000_000L), false, end - 30_000L);
		t = t.with(read(true, 200, 200, 150, false, 5_000_000L), false, end);
		return t.with(read(true, 200, 200, 150, false, xpAfter), false, end + 10_000L);
	}

	@Test
	public void notRead_locked_andNeverPlayed()
	{
		assertEquals(TearsOfGuthix.State.NOT_READ, TearsOfGuthix.empty().status(MON).getState());
		assertEquals(TearsOfGuthix.State.LOCKED, TearsOfGuthix.empty().with(read(false, 10, 0, 0, false, 1000), true, MON).status(MON).getState());
		TearsOfGuthix first = TearsOfGuthix.empty().with(read(true, 43, 0, 0, false, 1000), true, MON);
		assertEquals("never played: the first game needs nothing", TearsOfGuthix.State.READY, first.status(MON).getState());
	}

	@Test
	public void afterAGame_opensAtTheStartOfTheSeventhUtcDay_notSevenTimes24Hours()
	{
		long end = MON + DAY - 60_000L; // Monday 23:59 UTC
		TearsOfGuthix t = played(end, 5_010_000L);
		assertEquals(Long.valueOf(MON + 8 * DAY - DAY), t.getOpensAt()); // next Monday 00:00 UTC: 6 days and 1 minute later
		assertEquals(TearsOfGuthix.State.COUNTING_DOWN, t.status(MON + 7 * DAY - 1).getState());
	}

	@Test
	public void timeReadyButXpIneligible_isNotReady_andSaysWhatIsLeft()
	{
		TearsOfGuthix t = played(MON + 3_600_000L, 5_010_000L);
		long opens = t.getOpensAt();
		// The day has come; 9,999 XP later than the mark's base: not ready.
		TearsOfGuthix later = t.with(read(true, 200, 200, 150, false, 5_020_000L), false, opens + 1000);
		TearsOfGuthix.Status s = later.status(opens + 1000);
		assertEquals(TearsOfGuthix.State.NEEDS_REQUIREMENT, s.getState());
		assertFalse(s.getState() == TearsOfGuthix.State.READY);
		assertEquals(90_000L, s.getXpLeft());
		assertTrue(s.getDetail(), s.getDetail().contains("90K more XP"));
		// 100,000 XP since the reward: ready.
		TearsOfGuthix enough = later.with(read(true, 200, 200, 150, false, 5_110_000L), false, opens + 2000);
		assertEquals(TearsOfGuthix.State.READY, enough.status(opens + 2000).getState());
	}

	@Test
	public void aQuestPoint_settlesTheRequirement()
	{
		TearsOfGuthix t = played(MON, 5_010_000L);
		TearsOfGuthix qp = t.with(read(true, 201, 200, 150, false, 5_010_000L), false, t.getOpensAt());
		assertEquals(TearsOfGuthix.State.READY, qp.status(t.getOpensAt()).getState());
		assertEquals("before the day, still counting down", TearsOfGuthix.State.COUNTING_DOWN, qp.status(t.getOpensAt() - 1).getState());
	}

	@Test
	public void playedElsewhere_dropsTheDate_andNeverBecomesReadyByTimeAlone()
	{
		TearsOfGuthix t = played(MON, 5_010_000L);
		// Next login: the game shows another game was played (different tears, QP mark).
		TearsOfGuthix login = t.with(read(true, 205, 205, 90, false, 6_000_000L), true, MON + 3 * DAY);
		assertNull(login.getOpensAt());
		assertEquals(TearsOfGuthix.State.UNKNOWN_DATE, login.status(MON + 30 * DAY).getState());
		// The same values at login keep the saved date.
		TearsOfGuthix same = t.with(read(true, 200, 200, 150, false, 5_010_000L), true, MON + DAY);
		assertEquals(t.getOpensAt(), same.getOpensAt());
	}

	@Test
	public void junaSays_whenAndHowMuch()
	{
		TearsOfGuthix base = TearsOfGuthix.empty().with(read(true, 200, 200, 150, false, 5_000_000L), true, MON + 3_600_000L);
		assertEquals(TearsOfGuthix.State.UNKNOWN_DATE, base.status(MON + 3_600_000L).getState());
		TearsOfGuthix days = base.withDialogue("It has not been long since your last visit. Come back in 3 days.", 5_000_000L, MON + 3_600_000L);
		assertEquals(Long.valueOf(MON + 3 * DAY), days.getOpensAt());
		TearsOfGuthix tomorrow = base.withDialogue("It has not been long since your last visit. Come back tomorrow.", 5_000_000L, MON + 3_600_000L);
		assertEquals(Long.valueOf(MON + DAY), tomorrow.getOpensAt());
		TearsOfGuthix due = days.withDialogue("You cannot enter the cave again until you have gained either one quest point or 45,320 total XP.", 5_000_000L,
			MON + 3_600_000L);
		TearsOfGuthix.Status s = due.with(read(true, 200, 200, 150, false, 5_000_000L), false, MON + 3 * DAY).status(MON + 3 * DAY);
		assertEquals(TearsOfGuthix.State.NEEDS_REQUIREMENT, s.getState());
		assertEquals(45_320L, s.getXpLeft());
		TearsOfGuthix ok = base.withDialogue("Your stories have entertained me. I will let you into the cave for a short time.", 5_000_000L, MON + 7200_000L);
		assertEquals(TearsOfGuthix.State.READY, ok.status(MON + 7200_000L).getState());
		assertSame("unrelated words change nothing", base, base.withDialogue("Hello there.", 5_000_000L, MON));
	}

	@Test
	public void savedAndRestored_andNotifiedOnce()
	{
		TearsOfGuthix t = played(MON, 5_010_000L).with(read(true, 201, 200, 150, false, 5_010_000L), false, MON + 7 * DAY);
		TearsOfGuthix back = new Gson().fromJson(new Gson().toJson(t), TearsOfGuthix.class);
		assertEquals(TearsOfGuthix.State.READY, back.status(MON + 7 * DAY).getState());
		assertTrue(back.shouldNotify(MON + 7 * DAY));
		assertFalse(back.withNotified().shouldNotify(MON + 7 * DAY + 1));
	}
}
