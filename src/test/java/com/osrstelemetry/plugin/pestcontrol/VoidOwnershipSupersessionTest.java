package com.osrstelemetry.plugin.pestcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Semantic Void ownership (live QA 2026-10-02): an owned item SATISFIES a
 * requirement when it is that piece in any verified form -- base, broken,
 * trouver, "(or)" ornament -- or a piece that supersedes it (elite top ->
 * Void knight top). Possession still comes only from the current
 * inventory / equipment / bank.
 */
public class VoidOwnershipSupersessionTest
{
	private static Map<Integer, Long> items(int... ids)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int id : ids)
		{
			m.merge(id, 1L, Long::sum);
		}
		return m;
	}

	/** Bank seen (empty unless given), plus the given container's items. */
	private static VoidOwnership.Snapshot in(VoidOwnership.Container where, int... ids)
	{
		VoidOwnership o = new VoidOwnership();
		o.update(VoidOwnership.Container.BANK, items());
		o.update(where, items(ids));
		return o.getSnapshot();
	}

	private static VoidOwnership.Snapshot worn(int... ids)
	{
		return in(VoidOwnership.Container.EQUIPMENT, ids);
	}

	private static PestControlGoalPlanner.Plan plan(PestControlGoal goal, VoidOwnership.Snapshot o)
	{
		return PestControlGoalPlanner.plan(goal, o, 0, s -> -1L);
	}

	private static final PestControlGoal NEXT_RANGED = PestControlGoal.DEFAULT.withVoidStyle(VoidStyle.RANGED);
	private static final PestControlGoal FULL_RANGED = NEXT_RANGED.withVoidGoal(PestControlGoal.VoidGoal.FULL_SET);
	private static final PestControlGoal ELITE_RANGED = NEXT_RANGED.withVoidGoal(PestControlGoal.VoidGoal.ELITE_SET);

	// ---- the supersession relation itself ---------------------------------------------

	@Test
	public void eliteSupersedesItsBase_neverTheReverse_neverAcrossSlots()
	{
		assertTrue(VoidPiece.ELITE_TOP.satisfies(VoidPiece.TOP));
		assertTrue(VoidPiece.ELITE_ROBE.satisfies(VoidPiece.ROBE));
		assertTrue(VoidPiece.TOP.satisfies(VoidPiece.TOP));
		assertFalse(VoidPiece.TOP.satisfies(VoidPiece.ELITE_TOP));
		assertFalse(VoidPiece.ELITE_TOP.satisfies(VoidPiece.ROBE));
		assertFalse(VoidPiece.MAGE_HELM.satisfies(VoidPiece.RANGER_HELM));
	}

	// ---- top / robe -------------------------------------------------------------------

	@Test
	public void regularTopOnly()
	{
		VoidOwnership.Snapshot o = worn(ItemID.PEST_VOID_KNIGHT_TOP);
		assertTrue(o.satisfies(VoidPiece.TOP));
		assertFalse(o.satisfies(VoidPiece.ELITE_TOP));
		assertEquals(VoidPiece.ROBE, PestControlGoalPlanner.nextMissing(VoidStyle.RANGED, o));
	}

	@Test
	public void eliteTopOnly_satisfiesTheRegularTop()
	{
		VoidOwnership.Snapshot o = worn(ItemID.ELITE_VOID_KNIGHT_TOP);
		assertTrue(o.satisfies(VoidPiece.TOP));
		assertTrue(o.satisfies(VoidPiece.ELITE_TOP));
		assertEquals("never 'Need: Top'", VoidPiece.ROBE, PestControlGoalPlanner.nextMissing(VoidStyle.RANGED, o));
	}

	@Test
	public void regularRobeOnly()
	{
		VoidOwnership.Snapshot o = worn(ItemID.PEST_VOID_KNIGHT_ROBES);
		assertTrue(o.satisfies(VoidPiece.ROBE));
		assertFalse(o.satisfies(VoidPiece.ELITE_ROBE));
	}

	@Test
	public void eliteRobeOnly_satisfiesTheRegularRobe()
	{
		VoidOwnership.Snapshot o = worn(ItemID.ELITE_VOID_KNIGHT_ROBES);
		assertTrue(o.satisfies(VoidPiece.ROBE));
		PestControlGoalPlanner.Plan p = plan(FULL_RANGED, o);
		assertEquals("helm 200 + top 250 + gloves 150", Long.valueOf(600), p.getCost());
		assertEquals("Need: Ranger helm, Top, Gloves", p.getDetail());
	}

	// ---- sets ---------------------------------------------------------------------------

	@Test
	public void fullRegularSet_isComplete_forItsStyle()
	{
		VoidOwnership.Snapshot o = worn(ItemID.PEST_VOID_KNIGHT_TOP, ItemID.PEST_VOID_KNIGHT_ROBES, ItemID.PEST_VOID_KNIGHT_GLOVES,
			ItemID.GAME_PEST_ARCHER_HELM);
		PestControlGoalPlanner.Plan p = plan(FULL_RANGED, o);
		assertTrue(p.isComplete());
		assertEquals(Long.valueOf(0), p.getPointsLeft());
		assertEquals("Owned", p.getDetail());
		assertEquals("Ready", PestControlGoalPlanner.etaText(p.etaMillis(null)));
	}

	@Test
	public void fullEliteBodyAndRobe_withHelmAndGloves_completesBothSetGoals()
	{
		VoidOwnership.Snapshot o = worn(ItemID.ELITE_VOID_KNIGHT_TOP, ItemID.ELITE_VOID_KNIGHT_ROBES, ItemID.PEST_VOID_KNIGHT_GLOVES,
			ItemID.GAME_PEST_ARCHER_HELM);
		assertTrue("the regular set is satisfied by elite pieces", plan(FULL_RANGED, o).isComplete());
		assertTrue(plan(ELITE_RANGED, o).isComplete());
	}

	@Test
	public void mixedRegularAndElite()
	{
		VoidOwnership.Snapshot o = worn(ItemID.ELITE_VOID_KNIGHT_TOP, ItemID.PEST_VOID_KNIGHT_ROBES, ItemID.PEST_VOID_KNIGHT_GLOVES,
			ItemID.GAME_PEST_ARCHER_HELM);
		assertTrue(plan(FULL_RANGED, o).isComplete());
		PestControlGoalPlanner.Plan elite = plan(ELITE_RANGED, o);
		assertEquals("only the robe upgrade is left", Long.valueOf(200), elite.getCost());
		assertEquals("Need: Elite robe upgrade", elite.getDetail());
	}

	// ---- where it is owned --------------------------------------------------------------

	@Test
	public void ownedInBank_inventory_orEquipment_allCount()
	{
		for (VoidOwnership.Container where : VoidOwnership.Container.values())
		{
			assertTrue(where.name(), in(where, ItemID.ELITE_VOID_KNIGHT_TOP).satisfies(VoidPiece.TOP));
		}
	}

	@Test
	public void duplicateCopies_doNotChangeSatisfaction()
	{
		VoidOwnership o = new VoidOwnership();
		Map<Integer, Long> two = new HashMap<>();
		two.put(ItemID.PEST_VOID_KNIGHT_GLOVES, 2L);
		o.update(VoidOwnership.Container.BANK, two);
		o.update(VoidOwnership.Container.EQUIPMENT, items(ItemID.PEST_VOID_KNIGHT_GLOVES));
		PestControlGoalPlanner.Plan p = plan(FULL_RANGED, o.getSnapshot());
		assertEquals("helm 200 + top 250 + robe 250", Long.valueOf(700), p.getCost());
		assertEquals("Gloves", p.getOwned());
	}

	// ---- helms / next missing -------------------------------------------------------------

	@Test
	public void anUnrelatedHelm_doesNotSatisfyTheSelectedStyle()
	{
		VoidOwnership.Snapshot o = worn(ItemID.ELITE_VOID_KNIGHT_TOP, ItemID.ELITE_VOID_KNIGHT_ROBES, ItemID.PEST_VOID_KNIGHT_GLOVES,
			ItemID.GAME_PEST_MAGE_HELM);
		assertEquals(VoidPiece.RANGER_HELM, PestControlGoalPlanner.nextMissing(VoidStyle.RANGED, o));
		PestControlGoalPlanner.Plan p = plan(FULL_RANGED, o);
		assertEquals(Long.valueOf(200), p.getCost());
		assertEquals("Need: Ranger helm", p.getDetail());
	}

	@Test
	public void selectedStyleComplete_nextMissingMovesOn_neverInventsARegularPiece()
	{
		VoidOwnership.Snapshot o = worn(ItemID.ELITE_VOID_KNIGHT_TOP, ItemID.ELITE_VOID_KNIGHT_ROBES, ItemID.PEST_VOID_KNIGHT_GLOVES,
			ItemID.GAME_PEST_ARCHER_HELM);
		assertEquals("then the other helms (melee first)", VoidPiece.MELEE_HELM, PestControlGoalPlanner.nextMissing(VoidStyle.RANGED, o));
		VoidOwnership.Snapshot all = worn(ItemID.ELITE_VOID_KNIGHT_TOP, ItemID.ELITE_VOID_KNIGHT_ROBES, ItemID.PEST_VOID_KNIGHT_GLOVES,
			ItemID.GAME_PEST_ARCHER_HELM, ItemID.GAME_PEST_MELEE_HELM, ItemID.GAME_PEST_MAGE_HELM, ItemID.PEST_VOID_KNIGHT_MACE);
		assertNull(PestControlGoalPlanner.nextMissing(VoidStyle.RANGED, all));
		PestControlGoalPlanner.Plan p = plan(NEXT_RANGED, all);
		assertTrue(p.isComplete());
		assertEquals("All standard Void owned", p.getDetail());
	}

	@Test
	public void theActualMissingComponent_isStillNamed()
	{
		VoidOwnership.Snapshot o = worn(ItemID.ELITE_VOID_KNIGHT_TOP, ItemID.GAME_PEST_ARCHER_HELM);
		assertEquals(VoidPiece.ROBE, PestControlGoalPlanner.nextMissing(VoidStyle.RANGED, o));
		assertEquals("Need: Robe", plan(NEXT_RANGED, o).getDetail());
	}

	@Test
	public void bankUnknown_staysConservative()
	{
		VoidOwnership o = new VoidOwnership();
		o.update(VoidOwnership.Container.EQUIPMENT, items(ItemID.ELITE_VOID_KNIGHT_TOP));
		PestControlGoalPlanner.Plan p = plan(NEXT_RANGED, o.getSnapshot());
		assertEquals("Need: Robe (bank not seen yet)", p.getDetail());
	}

	// ---- verified "(or)" ornament forms (Josh's live equipment / bank, 2026-10-02) ----------

	@Test
	public void ornamentForms_areTheSamePieces()
	{
		assertEquals(VoidPiece.ELITE_TOP, VoidPiece.ofItem(ItemID.LEAGUE_3_VOID_KNIGHT_TOP_ELITE));
		assertEquals(VoidPiece.ELITE_ROBE, VoidPiece.ofItem(ItemID.LEAGUE_3_VOID_KNIGHT_ROBES_ELITE));
		assertEquals(VoidPiece.TOP, VoidPiece.ofItem(ItemID.LEAGUE_3_VOID_KNIGHT_TOP));
		assertEquals(VoidPiece.ROBE, VoidPiece.ofItem(ItemID.LEAGUE_3_VOID_KNIGHT_ROBES));
		assertEquals(VoidPiece.GLOVES, VoidPiece.ofItem(ItemID.LEAGUE_3_VOID_KNIGHT_GLOVES));
		assertEquals(VoidPiece.MELEE_HELM, VoidPiece.ofItem(ItemID.LEAGUE_3_VOID_MELEE_HELM));
		assertEquals(VoidPiece.RANGER_HELM, VoidPiece.ofItem(ItemID.LEAGUE_3_VOID_RANGE_HELM));
		assertEquals(VoidPiece.MAGE_HELM, VoidPiece.ofItem(ItemID.LEAGUE_3_VOID_MAGE_HELM));
		assertEquals(VoidPiece.ELITE_TOP, VoidPiece.ofItem(ItemID.LEAGUE_3_VOID_KNIGHT_TOP_ELITE_TROUVER));
		assertEquals(VoidPiece.RANGER_HELM, VoidPiece.ofItem(ItemID.LEAGUE_3_VOID_RANGE_HELM_TROUVER));
		assertNull("the kit itself is not a piece", VoidPiece.ofItem(ItemID.LEAGUE_3_VOID_KIT));
		assertNull("the unobtainable dummy is not a piece", VoidPiece.ofItem(ItemID.DUMMY_PEST_VOID_KNIGHT_TOP));
	}

	@Test
	public void liveCase_ornamentedEliteVoid_rangedNextMissing_isNotTop()
	{
		// Spyglass's own state on 2026-10-02: worn melee helm (or), elite top (or), elite robe (or),
		// gloves (or); banked mage helm (or) and ranger helm (or). It showed "Need: Top".
		VoidOwnership o = new VoidOwnership();
		o.update(VoidOwnership.Container.EQUIPMENT, items(ItemID.LEAGUE_3_VOID_MELEE_HELM, ItemID.LEAGUE_3_VOID_KNIGHT_TOP_ELITE,
			ItemID.LEAGUE_3_VOID_KNIGHT_ROBES_ELITE, ItemID.LEAGUE_3_VOID_KNIGHT_GLOVES));
		o.update(VoidOwnership.Container.BANK, items(ItemID.LEAGUE_3_VOID_MAGE_HELM, ItemID.LEAGUE_3_VOID_RANGE_HELM,
			ItemID.PEST_SEAL_8));
		VoidOwnership.Snapshot s = o.getSnapshot();
		assertEquals("only the mace is missing", VoidPiece.MACE, PestControlGoalPlanner.nextMissing(VoidStyle.RANGED, s));
		assertEquals("Need: Mace", plan(NEXT_RANGED, s).getDetail());
		assertTrue(plan(FULL_RANGED, s).isComplete());
		assertTrue(plan(ELITE_RANGED, s).isComplete());
	}
}
