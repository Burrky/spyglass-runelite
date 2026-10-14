package com.osrstelemetry.plugin.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * The generic UNIT family (Skills Phase 6): items holding 1..N of one unit
 * are one resource counted in units; equivalences are exact whole items plus
 * a remainder (never a fractional item); moving units between variants never
 * changes the total; non-members are ignored (ordinary item counts untouched).
 */
public class UnitFamilyTest
{
	private static final int FOUR = 104;
	private static final int THREE = 103;
	private static final int TWO = 102;
	private static final int ONE = 101;

	static UnitFamily family()
	{
		Map<Integer, Integer> m = new LinkedHashMap<>();
		m.put(FOUR, 4);
		m.put(THREE, 3);
		m.put(TWO, 2);
		m.put(ONE, 1);
		return new UnitFamily("thing", "Thing", m, 4, FOUR, "unit", "units", "full");
	}

	private static Map<Integer, Long> items(long... pairs)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			m.put((int) pairs[i], pairs[i + 1]);
		}
		return m;
	}

	@Test
	public void eachVariant_holdsItsOwnUnits()
	{
		UnitFamily f = family();
		assertEquals(1L, f.units(items(ONE, 1)));
		assertEquals(2L, f.units(items(TWO, 1)));
		assertEquals(3L, f.units(items(THREE, 1)));
		assertEquals(4L, f.units(items(FOUR, 1)));
		assertEquals(0, f.unitsOf(999));
		assertFalse(f.contains(999));
		assertTrue(f.contains(TWO));
	}

	@Test
	public void mixedOwnership_isOneTotal_andItsFullEquivalent()
	{
		UnitFamily f = family();
		// 100 x (4) + 20 x (3) + 4 x (1) = 464 units = 116 full.
		long units = f.units(items(FOUR, 100, THREE, 20, ONE, 4, 555, 1_000));
		assertEquals(464L, units);
		assertEquals(116L, f.fullItems(units));
		assertEquals("116 × full", f.equivalentText(units));
		assertEquals("464 units", f.unitsText(units));
	}

	@Test
	public void equivalence_isExactWholeItemsPlusARemainder_neverAFraction()
	{
		UnitFamily f = family();
		assertEquals("271 × full + 2 units", f.equivalentText(1_086));
		assertEquals("3 units", f.equivalentText(3));
		assertEquals("1 unit", f.equivalentText(1));
		assertEquals("1,000 × full", f.fullText(1_000));
		assertEquals(271L, f.fullItems(1_087));
	}

	@Test
	public void mergingOrSplittingVariants_neverChangesTheTotal()
	{
		UnitFamily f = family();
		Map<Integer, Long> before = items(ONE, 4, THREE, 1, TWO, 1);
		// Merge four (1) into one (4); (3) + (2) -> (4) + (1).
		Map<Integer, Long> after = items(FOUR, 2, ONE, 1);
		assertEquals(f.units(before), f.units(after));
	}
}
