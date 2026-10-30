package com.osrstelemetry.plugin.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.Collections;
import org.junit.Test;

/** Kingdom of Miscellania (canonical Home §10): readings only on the islands, estimates by the verified daily rules. */
public class KingdomTest
{
	private static final long DAY = Dailies.DAY_MILLIS;
	private static final long NOW = 20_754L * DAY + 10 * 3_600_000L;

	@Test
	public void awayFromTheIslands_theCofferIsNeverRecorded()
	{
		Kingdom k = Kingdom.empty().with(true, false, false, 0, 0, NOW);
		assertTrue(k.getQuestDone());
		assertNull("no reading off the islands", k.getSeenAt());
		assertEquals(Collections.emptyList(), k.reminders(NOW));
	}

	@Test
	public void theEstimate_appliesTheDailyPayment_andApprovalFall_andStopsAfterThirtyDays()
	{
		Kingdom k = Kingdom.empty().with(true, false, true, 1_000_000, 127, NOW);
		assertSame("the same reading the same day: nothing to save", k, k.with(true, false, true, 1_000_000, 127, NOW + 60_000));
		// 1M > 10 x 50K: 50K a day for two midnights.
		assertEquals(900_000, k.estimatedCoffer(NOW + 2 * DAY));
		// 2.5% of 127 a day: two days, 6 points.
		assertEquals(121, k.estimatedApproval(NOW + 2 * DAY));
		// Under 500K: 10% a day.
		Kingdom low = Kingdom.empty().with(true, false, true, 400_000, 127, NOW);
		assertEquals(360_000, low.estimatedCoffer(NOW + DAY));
		assertTrue(low.reminders(NOW).toString(), low.reminders(true, NOW).contains("Coffer 400K: can't pay the full 50K a day"));
		// Collection pauses after 30 days without a visit.
		assertEquals(k.estimatedCoffer(NOW + 30 * DAY), k.estimatedCoffer(NOW + 45 * DAY));
		assertEquals("Collection paused: visit Miscellania", k.reminders(NOW + 31 * DAY).get(0));
		assertTrue(k.reminders(NOW + 26 * DAY).get(0).startsWith("Visit within 4 days"));
	}

	@Test
	public void royalTrouble_raisesThePayment_andSlowsTheFall_andSaves()
	{
		Kingdom k = Kingdom.empty().with(true, true, true, 2_000_000, 100, NOW);
		assertEquals(1_925_000, k.estimatedCoffer(NOW + DAY));
		assertEquals(99, k.estimatedApproval(NOW + DAY));
		Gson gson = new Gson();
		Kingdom back = gson.fromJson(gson.toJson(k), Kingdom.class);
		assertEquals(Long.valueOf(NOW), back.getSeenAt());
		assertTrue(back.isRoyalTrouble());
		assertEquals(78, Kingdom.percent(100));
	}

	@Test
	public void approvalWarns_strictlyBelow90_neverFromAnUnreadValue()
	{
		assertNull("never read: no warning", Kingdom.empty().with(true, false, false, 0, 0, NOW).loginWarning(false, NOW));
		// 115 points = 90% exactly: no warning; 114 = 89%: warning.
		Kingdom ninety = Kingdom.empty().with(true, false, true, 5_000_000, 115, NOW);
		assertEquals(90, Kingdom.percent(115));
		assertNull(ninety.loginWarning(true, NOW));
		Kingdom below = Kingdom.empty().with(true, false, true, 5_000_000, 114, NOW);
		assertEquals("Kingdom of Miscellania: approval 89%.", below.loginWarning(true, NOW));
		// Away, the calculated value is marked.
		assertEquals("Kingdom of Miscellania: approval 88%.", ninety.loginWarning(false, NOW + DAY));
	}

	@Test
	public void theCofferWarns_whenItCanNoLongerPayTheFullDailyMaximum()
	{
		assertNull("exactly 10x the maximum still pays it in full", Kingdom.empty().with(true, false, true, 500_000, 127, NOW).loginWarning(true, NOW));
		assertEquals("Kingdom of Miscellania: the coffer (499K) can't pay the full 50K a day.",
			Kingdom.empty().with(true, false, true, 499_999, 127, NOW).loginWarning(true, NOW));
		Kingdom royal = Kingdom.empty().with(true, true, true, 749_999, 127, NOW);
		assertTrue("after Royal Trouble the maximum is 75K", royal.cofferLow(true, NOW));
		assertTrue(royal.loginWarning(true, NOW).contains("can't pay the full 75K a day"));
		assertTrue(!Kingdom.empty().with(true, true, true, 750_000, 127, NOW).cofferLow(true, NOW));
	}

	@Test
	public void theLoginWarning_isSuppressedForAnHour_andTheCooldownSurvivesSaving()
	{
		Kingdom k = Kingdom.empty().with(true, false, true, 100_000, 100, NOW).withWarned(NOW);
		Gson gson = new Gson();
		Kingdom back = gson.fromJson(gson.toJson(k), Kingdom.class);
		assertTrue("relog 10 minutes later: suppressed", back.isWarningCoolingDown(NOW + 10 * 60_000L));
		assertTrue(back.isWarningCoolingDown(NOW + 3_599_999L));
		assertTrue("an hour on: warns again", !back.isWarningCoolingDown(NOW + 3_600_000L));
		assertSame("a new reading keeps the cooldown", back.getWarnedAt(), back.with(true, false, true, 90_000, 99, NOW + 60_000).getWarnedAt());
	}
}
