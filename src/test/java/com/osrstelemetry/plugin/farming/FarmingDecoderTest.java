package com.osrstelemetry.plugin.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Decoder boundaries and the distinctions Spyglass keeps that RuneLite's PatchState collapses. */
public class FarmingDecoderTest
{
	private static PatchReading herb(int raw)
	{
		return FarmingDecoder.decode(FarmingPatchType.HERB, raw);
	}

	private static PatchReading tree(int raw)
	{
		return FarmingDecoder.decode(FarmingPatchType.TREE, raw);
	}

	private static PatchReading allotment(int raw)
	{
		return FarmingDecoder.decode(FarmingPatchType.ALLOTMENT, raw);
	}

	private static void assertReading(PatchReading r, PatchCondition condition, FarmingProduce produce, int stage)
	{
		assertEquals(r.toString(), condition, r.getCondition());
		assertEquals(r.toString(), produce, r.getProduce());
		assertEquals(r.toString(), stage, r.getStage());
	}

	@Test
	public void weeds_raked_isEmpty_andWeedLevelsAreWeeds()
	{
		assertReading(herb(3), PatchCondition.EMPTY, null, 0);
		assertReading(herb(0), PatchCondition.WEEDS, null, 3);
		assertReading(herb(2), PatchCondition.WEEDS, null, 1);
		assertReading(allotment(3), PatchCondition.EMPTY, null, 0);
		assertReading(tree(3), PatchCondition.EMPTY, null, 0);
	}

	@Test
	public void herbBoundaries_ranarr()
	{
		assertReading(herb(32), PatchCondition.GROWING, FarmingProduce.RANARR, 0);
		assertReading(herb(35), PatchCondition.GROWING, FarmingProduce.RANARR, 3);
		assertReading(herb(36), PatchCondition.HARVESTABLE, FarmingProduce.RANARR, 2);
		assertReading(herb(38), PatchCondition.HARVESTABLE, FarmingProduce.RANARR, 0);
		assertReading(herb(140), PatchCondition.DISEASED, FarmingProduce.RANARR, 1);
		assertReading(herb(142), PatchCondition.DISEASED, FarmingProduce.RANARR, 3);
		// Neighbours belong to other herbs.
		assertEquals(FarmingProduce.HARRALANDER, herb(31).getProduce());
		assertEquals(FarmingProduce.TOADFLAX, herb(39).getProduce());
	}

	@Test
	public void deadHerb_hasNoKnownProduce()
	{
		assertReading(herb(170), PatchCondition.DEAD, null, 1);
		assertReading(herb(172), PatchCondition.DEAD, null, 3);
	}

	@Test
	public void valuesRuneLiteCannotDecode_areUnknown_neverEmpty()
	{
		for (int raw : new int[] {110, 118, 127, 220, 256, -1, 1000})
		{
			PatchReading r = herb(raw);
			assertEquals("herb " + raw, PatchCondition.UNKNOWN, r.getCondition());
			assertNull(r.getProduce());
		}
		for (int raw : new int[] {76, 85, 96, 109, 124, 140, 149, 160, 173, 188})
		{
			assertEquals("tree " + raw, PatchCondition.UNKNOWN, tree(raw).getCondition());
		}
	}

	@Test
	public void tree_checkHealth_standing_stump_areDistinct()
	{
		assertReading(tree(11), PatchCondition.GROWING, FarmingProduce.OAK, 3);
		assertReading(tree(12), PatchCondition.CHECK_HEALTH, FarmingProduce.OAK, FarmingProduce.OAK.getStages() - 1);
		PatchReading standing = tree(13);
		PatchReading stump = tree(14);
		assertEquals(PatchCondition.HARVESTABLE, standing.getCondition());
		assertEquals(PatchSubState.STANDING, standing.getSubState());
		assertEquals(PatchCondition.HARVESTABLE, stump.getCondition());
		assertEquals(PatchSubState.STUMP, stump.getSubState());
		assertNotEquals(standing, stump);
		// The same three states for magic trees.
		assertEquals(PatchCondition.CHECK_HEALTH, tree(60).getCondition());
		assertEquals(PatchSubState.STANDING, tree(61).getSubState());
		assertEquals(PatchSubState.STUMP, tree(62).getSubState());
	}

	@Test
	public void tree_disease_and_death_keepTheirProduce()
	{
		assertReading(tree(73), PatchCondition.DISEASED, FarmingProduce.OAK, 1);
		assertReading(tree(137), PatchCondition.DEAD, FarmingProduce.OAK, 1);
		assertReading(tree(189), PatchCondition.DEAD, FarmingProduce.MAGIC, 12);
	}

	@Test
	public void allotment_boundaries_includingSnapeGrassArithmetic()
	{
		assertReading(allotment(6), PatchCondition.GROWING, FarmingProduce.POTATO, 0);
		assertReading(allotment(9), PatchCondition.GROWING, FarmingProduce.POTATO, 3);
		assertReading(allotment(10), PatchCondition.HARVESTABLE, FarmingProduce.POTATO, 0);
		// RuneLite: "3 + value - 201" for diseased snape grass.
		assertReading(allotment(202), PatchCondition.DISEASED, FarmingProduce.SNAPE_GRASS, 4);
	}

	@Test
	public void everyRawValueDecodes_andCarriesItsRawAndVersion()
	{
		for (FarmingPatchType type : FarmingPatchType.values())
		{
			for (int raw = 0; raw <= 255; raw++)
			{
				PatchReading r = FarmingDecoder.decode(type, raw);
				assertEquals(raw, r.getRaw());
				assertEquals(FarmingDecoder.VERSION, r.getDecoderVersion());
				assertTrue(r.getCondition() == PatchCondition.UNKNOWN || r.getStage() >= 0);
				if (r.getProduce() != null && r.getProduce() != FarmingProduce.WEEDS)
				{
					assertEquals(type + " " + raw, type, r.getProduce().getPatchType());
				}
			}
		}
	}
}
