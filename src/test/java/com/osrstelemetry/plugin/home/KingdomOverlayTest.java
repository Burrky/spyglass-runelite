package com.osrstelemetry.plugin.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * Pass 31: the Kingdom box on the islands and the page's actions come from one reading and the same rules (approval strictly
 * under 90%, a coffer under ten times the daily maximum), never from an unread value, never repeating a value in the prompts.
 */
public class KingdomOverlayTest
{
	private static final long DAY = Dailies.DAY_MILLIS;
	private static final long NOW = 20_754L * DAY + 10 * 3_600_000L;

	@Test
	public void hidden_offTheIslands_beforeTheVisitsFirstReading_whenOff_andWithoutTheQuest()
	{
		Kingdom k = Kingdom.empty().with(true, false, true, 1_000_000, 127, NOW);
		assertNotNull(KingdomOverlay.model(k, true, true, true));
		assertNull("off the islands", KingdomOverlay.model(k, true, false, true));
		assertNull("not read this visit yet (a hop, a login)", KingdomOverlay.model(k, true, true, false));
		assertNull("turned off", KingdomOverlay.model(k, false, true, true));
		assertNull("never read", KingdomOverlay.model(Kingdom.empty().with(true, false, false, 0, 0, NOW), true, true, true));
		assertNull("no quest", KingdomOverlay.model(Kingdom.empty().with(false, false, true, 5, 5, NOW), true, true, true));
	}

	@Test
	public void theBox_andThePage_readTheSameValues_andPrompts()
	{
		// 114 points = 89%: strictly under 90%. 400K: under the 500K that pays the full 50K.
		Kingdom k = Kingdom.empty().with(true, false, true, 400_000, 114, NOW);
		KingdomOverlay.Model m = KingdomOverlay.model(k, true, true, true);
		assertEquals(89, m.approvalPercent);
		assertTrue(m.approvalLow);
		assertEquals(400_000, m.coffer);
		assertTrue(m.cofferLow);
		assertEquals(3, m.cofferLevel);
		assertEquals("Raise approval", m.prompt);
		List<Kingdom.Action> page = k.actions(true, NOW);
		assertEquals("Raise approval", page.get(0).title);
		assertEquals("Top up coffer", page.get(1).title);
		for (Kingdom.Action a : page)
		{
			assertFalse("the values are shown once, not in the prompts: " + a, a.how.contains("89%") || a.how.contains("400K") || a.how.contains("400,000"));
		}
		// 115 points = 90%: not under 90%.
		Kingdom ok = Kingdom.empty().with(true, false, true, 600_000, 115, NOW);
		KingdomOverlay.Model fine = KingdomOverlay.model(ok, true, true, true);
		assertFalse(fine.approvalLow);
		assertFalse(fine.cofferLow);
		assertNull(fine.prompt);
		assertEquals(4, fine.cofferLevel);
	}

	@Test
	public void coinStacks_growWithTheBalance_againstTheFullPayment()
	{
		int full = 500_000;
		assertEquals(0, Kingdom.cofferLevel(0, full));
		assertEquals(1, Kingdom.cofferLevel(10_000, full));
		assertEquals(2, Kingdom.cofferLevel(100_000, full));
		assertEquals(3, Kingdom.cofferLevel(499_999, full));
		assertEquals(4, Kingdom.cofferLevel(500_000, full));
		assertEquals(4, Kingdom.cofferLevel(7_500_000, 750_000));
		assertEquals(10_000, Kingdom.cofferStackQuantity(4));
		assertEquals(100, Kingdom.cofferStackQuantity(1));
	}

	@Test
	public void awayFromTheIslands_actionsUseTheWorkedOutValues_andTheVisitRule_butNeverAnUnreadValue()
	{
		assertTrue(Kingdom.empty().actions(false, NOW).isEmpty());
		assertTrue(Kingdom.empty().with(true, false, false, 0, 0, NOW).actions(false, NOW).isEmpty());
		Kingdom k = Kingdom.empty().with(true, false, true, 5_000_000, 127, NOW);
		assertTrue(k.actions(false, NOW + DAY).isEmpty());
		assertEquals("Visit within 4 days", k.actions(false, NOW + 26 * DAY).get(0).title);
		assertEquals("Visit the kingdom", k.actions(false, NOW + 31 * DAY).get(0).title);
	}
}
