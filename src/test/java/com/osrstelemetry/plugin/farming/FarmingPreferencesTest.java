package com.osrstelemetry.plugin.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Test;

/** Farming preferences: verified per family, copy-on-write, and round-trip through the saved JSON. */
public class FarmingPreferencesTest
{
	@Test
	public void defaults_andMechanicsPerFamily()
	{
		FarmingPreferences d = FarmingPreferences.defaults();
		assertEquals(FarmingPreferences.TreeClearing.PAY, d.getTreeClearing());
		assertTrue(d.isPickFruitFirst());
		assertTrue(d.isIncludeAllotments());
		assertFalse("flowers are never auto-included", d.isIncludeFlowers());
		assertTrue(FarmingPreferences.paidRemovalOffered(FarmingPatchType.TREE));
		assertTrue(FarmingPreferences.paidRemovalOffered(FarmingPatchType.FRUIT_TREE));
		assertFalse("nothing to remove on a herb patch", FarmingPreferences.paidRemovalOffered(FarmingPatchType.HERB));
		assertEquals(200, FarmingPreferences.REMOVAL_COINS);
	}

	@Test
	public void withCopies_leaveTheOriginal_andSurviveJson()
	{
		FarmingPreferences d = FarmingPreferences.defaults();
		FarmingPreferences c = d.withTreeClearing(FarmingPreferences.TreeClearing.CHOP).withIncludeFlowers(true).withProtect(FarmingRunType.ALLOTMENT, true)
			.withPickFruitFirst(false);
		assertEquals(FarmingPreferences.TreeClearing.PAY, d.getTreeClearing());
		assertNotEquals(d, c);
		Gson g = new Gson();
		FarmingPreferences back = g.fromJson(g.toJson(c), FarmingPreferences.class);
		assertEquals(c, back);
		assertTrue(back.isProtect(FarmingRunType.ALLOTMENT));
		assertFalse(back.isProtect(FarmingRunType.HERB));
		assertEquals(FarmingPreferences.TreeClearing.CHOP, back.clearingFor(FarmingPatchType.TREE));
	}
}
