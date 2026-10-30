package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.farming.FarmingPatchType;
import com.osrstelemetry.plugin.farming.FarmingProduce;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** Coverage of the patch-card sprite table: every crop and every patch type has a genuine item. */
public class FarmingIconsTest
{
	@Test
	public void everyCrop_hasItsOwnSprite_exceptWeeds()
	{
		for (FarmingProduce p : FarmingProduce.values())
		{
			if (p == FarmingProduce.WEEDS)
			{
				assertEquals("weeds are no crop identity", -1, FarmingIcons.cropItem(p));
				continue;
			}
			assertTrue(p + " has a sprite", FarmingIcons.cropItem(p) > 0);
		}
		assertEquals(-1, FarmingIcons.cropItem(null));
	}

	@Test
	public void cropsWithinAType_neverShareASprite_soEachIsRecognisable()
	{
		for (FarmingPatchType t : FarmingPatchType.values())
		{
			Set<Integer> seen = new HashSet<>();
			for (FarmingProduce p : FarmingProduce.values())
			{
				if (p.getPatchType() == t)
				{
					assertTrue(t + ": " + p + " shares a sprite", seen.add(FarmingIcons.cropItem(p)));
				}
			}
		}
	}

	@Test
	public void everyPatchType_hasAFallback_thatIsNeverOneOfItsOwnCrops()
	{
		for (FarmingPatchType t : FarmingPatchType.values())
		{
			int fallback = FarmingIcons.patchItem(t);
			assertTrue(t + " fallback", fallback > 0);
			for (FarmingProduce p : FarmingProduce.values())
			{
				if (p.getPatchType() == t)
				{
					assertFalse(t + " fallback looks like " + p, fallback == FarmingIcons.cropItem(p));
				}
			}
		}
	}

	@Test
	public void theNamedExamples()
	{
		assertEquals("the herb, not the seed", ItemID.SNAPDRAGON, FarmingIcons.cropItem(FarmingProduce.SNAPDRAGON));
		assertEquals(ItemID.YEW_LOGS, FarmingIcons.cropItem(FarmingProduce.YEW));
		assertEquals(ItemID.MARIGOLD, FarmingIcons.cropItem(FarmingProduce.MARIGOLD));
		assertEquals(ItemID.SLAYER_HERB_SACK, FarmingIcons.patchItem(FarmingPatchType.HERB));
		assertEquals(ItemID.PLANTPOT_OAK_SAPLING, FarmingIcons.patchItem(FarmingPatchType.TREE));
	}
}
