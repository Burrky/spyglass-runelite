package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Location -> transmitted patches: bounds, plane, alias regions and same-region context changes. */
public class FarmingPatchCatalogTest
{
	private final FarmingPatchCatalog catalog = FarmingPatchCatalog.standard();

	private String ids(FarmingLocation location)
	{
		return catalog.resolve(location).getKey();
	}

	@Test
	public void falador_north_of_3272_transmitsTheAllotmentsAndHerb()
	{
		assertEquals("allotment.falador.nw;allotment.falador.se;flower.falador;herb.falador;", ids(FALADOR));
		assertEquals("allotment.falador.nw;allotment.falador.se;flower.falador;herb.falador;", ids(new FarmingLocation(3055, 3272, 0)));
	}

	@Test
	public void sameRegion_southEdge_isPortSarims_notFaladors()
	{
		assertEquals(FALADOR.getRegionId(), PORT_SARIM_EDGE.getRegionId());
		assertEquals("spirit_tree.port_sarim;", ids(PORT_SARIM_EDGE));
		assertEquals("spirit_tree.port_sarim;", ids(new FarmingLocation(3055, 3271, 0)));
		assertNotEquals(catalog.resolve(FALADOR), catalog.resolve(PORT_SARIM_EDGE));
	}

	@Test
	public void catherby_upstairsAndEastOfTheBox_transmitSomethingElse()
	{
		assertEquals("allotment.catherby.n;allotment.catherby.s;flower.catherby;herb.catherby;", ids(CATHERBY));
		assertEquals(ids(CATHERBY), ids(CATHERBY_ALIAS_GROUND));
		assertEquals(CATHERBY_ALIAS_GROUND.getRegionId(), CATHERBY_ALIAS_UPSTAIRS.getRegionId());
		assertEquals("upstairs sends the fruit tree", "fruit_tree.catherby;", ids(CATHERBY_ALIAS_UPSTAIRS));
		assertEquals("fruit-tree side", "fruit_tree.catherby;", ids(CATHERBY_ALIAS_EAST));
	}

	@Test
	public void aliasRegions_resolveToTheirOwningArea()
	{
		assertEquals(12084, FALADOR_TREE_ALIAS.getRegionId());
		assertEquals("tree.falador;", ids(FALADOR_TREE_ALIAS));
		assertEquals("tree.falador;", ids(new FarmingLocation(3004, 3373, 0)));
		assertEquals(11828, patch("tree.falador").getOwningRegionId());
	}

	@Test
	public void leavingEveryMappedArea_resolvesToNothing()
	{
		assertTrue(catalog.resolve(NOWHERE).isEmpty());
		assertTrue(catalog.resolve(null).isEmpty());
	}

	@Test
	public void patchIdentity_isTheSlugBackedByOwningRegionAndVarbit()
	{
		FarmingPatchDef herb = patch("herb.falador");
		assertEquals(12083, herb.getOwningRegionId());
		assertEquals(D, herb.getVarbit());
		assertEquals(FarmingPatchType.HERB, herb.getType());
		// The same varbit is a different patch elsewhere.
		assertEquals(D, patch("herb.catherby").getVarbit());
		assertNotEquals(herb.getOwningRegionId(), patch("herb.catherby").getOwningRegionId());
	}
}
