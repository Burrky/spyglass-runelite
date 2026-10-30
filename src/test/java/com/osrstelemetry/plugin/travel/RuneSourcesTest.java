package com.osrstelemetry.plugin.travel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

public class RuneSourcesTest
{
	private static Map<Integer, Integer> m(int... kv)
	{
		Map<Integer, Integer> out = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2)
		{
			out.put(kv[i], kv[i + 1]);
		}
		return out;
	}

	@Test
	public void combinationRunes_countForBothElements()
	{
		Map<Integer, Long> s = RuneSources.supply(m(ItemID.MISTRUNE, 4, ItemID.AIRRUNE, 1), Collections.emptyMap());
		assertEquals(5L, (long) s.get(RuneSources.AIR));
		assertEquals(4L, (long) s.get(RuneSources.WATER));
		assertNull(s.get(RuneSources.FIRE));
	}

	@Test
	public void wornStaves_andChargedTomes_supplyUnlimited_unchargedTomesNothing()
	{
		assertEquals(RuneSources.UNLIMITED, (long) RuneSources.supply(null, m(ItemID.MYSTIC_LAVA_STAFF, 1)).get(RuneSources.FIRE));
		assertEquals(RuneSources.UNLIMITED, (long) RuneSources.supply(null, m(ItemID.MYSTIC_LAVA_STAFF, 1)).get(RuneSources.EARTH));
		assertEquals(RuneSources.UNLIMITED, (long) RuneSources.supply(null, m(ItemID.TOME_OF_FIRE, 1)).get(RuneSources.FIRE));
		assertEquals(RuneSources.UNLIMITED, (long) RuneSources.supply(null, m(ItemID.TOME_OF_WATER, 1)).get(RuneSources.WATER));
		assertEquals(RuneSources.UNLIMITED, (long) RuneSources.supply(null, m(ItemID.KODAI_WAND, 1)).get(RuneSources.WATER));
		assertFalse(RuneSources.supply(null, m(ItemID.TOME_OF_FIRE_UNCHARGED, 1)).containsKey(RuneSources.FIRE));
		assertFalse(RuneSources.supply(null, m(ItemID.TOME_OF_WATER_UNCHARGED, 1)).containsKey(RuneSources.WATER));
	}

	@Test
	public void plus_keepsUnlimited()
	{
		Map<Integer, Long> a = RuneSources.supply(null, m(ItemID.STAFF_OF_AIR, 1));
		Map<Integer, Long> b = RuneSources.supply(m(ItemID.AIRRUNE, 10, ItemID.LAWRUNE, 2), null);
		Map<Integer, Long> sum = RuneSources.plus(a, b);
		assertEquals(RuneSources.UNLIMITED, (long) sum.get(RuneSources.AIR));
		assertEquals(2L, (long) sum.get(ItemID.LAWRUNE));
	}
}
