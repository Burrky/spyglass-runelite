package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.ui.model.XpEntryFixtures;
import org.junit.Test;

/** Skill cards: XP/hr first, the chosen second metric next, quiet facts last; the time to level obeys the rate's gate. */
public class XpCardTextTest
{
	private static final XpCardText.SecondMetric XP = XpCardText.SecondMetric.XP_REMAINING;
	private static final XpCardText.SecondMetric TIME = XpCardText.SecondMetric.TIME_TO_LEVEL;

	@Test
	public void xpRemaining_isTheDefault_andTimeToLevel_usesTheSameRate()
	{
		XpCardText t = XpCardText.of(XpEntryFixtures.entry(9_591, 36_000L, null, 96, 72_000L), XP);
		assertEquals("36,000 XP/hr", t.rate);
		assertTrue(t.rateReady);
		assertEquals("72,000 XP to 97", t.second);
		assertEquals("Lv 96 · +9,591 XP", t.quiet);
		XpCardText time = XpCardText.of(XpEntryFixtures.entry(9_591, 36_000L, null, 96, 72_000L), TIME);
		assertEquals("~2h 00m to 97", time.second);
		assertEquals(TIME, time.shown);
	}

	@Test
	public void beforeTheRateIsReady_orWithNoUsableRate_xpRemainingIsShown_neverAnInventedTime()
	{
		XpCardText warming = XpCardText.of(XpEntryFixtures.entry(500, null, 120_000L, 50, 3_000L), TIME);
		assertFalse(warming.rateReady);
		assertEquals("XP/hr in 2m", warming.rate);
		assertEquals("3,000 XP to 51", warming.second);
		assertEquals(XP, warming.shown);
		assertTrue(warming.secondTip.contains("after 5 minutes"));
		XpCardText zero = XpCardText.of(XpEntryFixtures.entry(0, 0L, null, 50, 3_000L), TIME);
		assertEquals("a zero rate: no infinity", "3,000 XP to 51", zero.second);
	}

	@Test
	public void atMaxLevel_thereIsNoNextLevel()
	{
		XpCardText max = XpCardText.of(XpEntryFixtures.entry(120_000, 400_000L, null, 99, null), TIME);
		assertNull(max.second);
		assertEquals("Lv 99 · +120,000 XP", max.quiet);
		assertEquals("400,000 XP/hr", max.rate);
	}

	@Test
	public void theChoice_isSavedAndSharedByEveryCard()
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, null);
		String[] saved = {null};
		v.setXpMetricStore(() -> saved[0], x -> saved[0] = x);
		assertEquals(XP, v.xpMetric());
		v.toggleXpMetric();
		assertEquals("TIME_TO_LEVEL", saved[0]);
		assertEquals(TIME, v.xpMetric());
		saved[0] = "nonsense";
		assertEquals("an unknown saved value reads as the default", XP, v.xpMetric());
	}
}
