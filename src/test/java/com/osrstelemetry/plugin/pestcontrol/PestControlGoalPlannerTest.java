package com.osrstelemetry.plugin.pestcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The goal planner: Void (ownership-aware, verified costs), XP (exact
 * simulation) and supply packs (exact mineral contents; herb / seed never
 * fabricated), with POINTS LEFT = cost - balance (never below 0, never
 * assuming an unknown balance is 0) and ETA only from an observed rate.
 */
public class PestControlGoalPlannerTest
{
	private static final PestControlGoal VOID = PestControlGoal.DEFAULT;

	private static Map<Integer, Long> items(int... ids)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int id : ids)
		{
			m.merge(id, 1L, Long::sum);
		}
		return m;
	}

	/** Ownership with the bank seen (empty unless given). */
	private static VoidOwnership.Snapshot owned(int... ids)
	{
		VoidOwnership o = new VoidOwnership();
		o.update(VoidOwnership.Container.INVENTORY, items(ids));
		o.update(VoidOwnership.Container.BANK, items());
		return o.getSnapshot();
	}

	private static PestControlGoalPlanner.Plan plan(PestControlGoal goal, VoidOwnership.Snapshot o, Integer balance)
	{
		return PestControlGoalPlanner.plan(goal, o, balance, s -> -1L);
	}

	// ---- costs (OSRS Wiki, Void Knight Equipment / Void Knight Commendation points) ----

	@Test
	public void verifiedCosts()
	{
		assertEquals(250, VoidPiece.TOP.getCost());
		assertEquals(250, VoidPiece.ROBE.getCost());
		assertEquals(150, VoidPiece.GLOVES.getCost());
		assertEquals(250, VoidPiece.MACE.getCost());
		for (VoidStyle s : VoidStyle.values())
		{
			assertEquals(200, s.getHelm().getCost());
		}
		assertEquals(200, VoidPiece.ELITE_TOP.getCost());
		assertEquals(200, VoidPiece.ELITE_ROBE.getCost());
		assertEquals(VoidPiece.TOP, VoidPiece.ELITE_TOP.getUpgradesFrom());
		assertEquals(30, SupplyPack.HERB.getCost());
		assertEquals(15, SupplyPack.SEED.getCost());
		assertEquals(15, SupplyPack.MINERAL.getCost());
	}

	// ---- Void ----------------------------------------------------------------

	@Test
	public void fullSet_nothingOwned_is850()
	{
		PestControlGoalPlanner.Plan p = plan(VOID.withVoidGoal(PestControlGoal.VoidGoal.FULL_SET), owned(), 0);
		assertEquals(Long.valueOf(850), p.getCost());
		assertEquals("Need: Melee helm, Top, Robe, Gloves", p.getDetail());
	}

	@Test
	public void fullSet_ownedPiecesAreExcluded_byStyle()
	{
		VoidOwnership.Snapshot o = owned(ItemID.PEST_VOID_KNIGHT_TOP, ItemID.GAME_PEST_ARCHER_HELM);
		PestControlGoal ranged = VOID.withVoidGoal(PestControlGoal.VoidGoal.FULL_SET).withVoidStyle(VoidStyle.RANGED);
		PestControlGoalPlanner.Plan p = plan(ranged, o, 100);
		assertEquals(Long.valueOf(400), p.getCost());
		assertEquals(Long.valueOf(300), p.getPointsLeft());
		assertEquals("Ranger helm, Top", p.getOwned());
		assertEquals("Need: Robe, Gloves", p.getDetail());
	}

	@Test
	public void eliteSet_includesMissingBases_andUpgradesOwnedOnes()
	{
		PestControlGoal elite = VOID.withVoidGoal(PestControlGoal.VoidGoal.ELITE_SET).withVoidStyle(VoidStyle.MAGIC);
		assertEquals("850 + 400", Long.valueOf(1250), plan(elite, owned(), null).getCost());
		VoidOwnership.Snapshot o = owned(ItemID.PEST_VOID_KNIGHT_TOP, ItemID.PEST_VOID_KNIGHT_ROBES, ItemID.PEST_VOID_KNIGHT_GLOVES,
			ItemID.GAME_PEST_MAGE_HELM);
		PestControlGoalPlanner.Plan p = plan(elite, o, null);
		assertEquals(Long.valueOf(400), p.getCost());
		assertEquals("Need: Elite top upgrade, Elite robe upgrade", p.getDetail());
	}

	@Test
	public void eliteOwned_countsAsOwningItsBase()
	{
		VoidOwnership.Snapshot o = owned(ItemID.ELITE_VOID_KNIGHT_TOP);
		assertTrue(PestControlGoalPlanner.owns(o, VoidPiece.TOP));
		assertEquals(VoidPiece.ROBE, PestControlGoalPlanner.nextMissing(VoidStyle.MELEE, o));
	}

	@Test
	public void specificPiece_ownedIsComplete_zeroLeft()
	{
		PestControlGoal mace = VOID.withVoidGoal(PestControlGoal.VoidGoal.PIECE).withVoidPiece(VoidPiece.MACE);
		assertEquals(Long.valueOf(250), plan(mace, owned(), 0).getCost());
		PestControlGoalPlanner.Plan done = plan(mace, owned(ItemID.PEST_VOID_KNIGHT_MACE), 0);
		assertTrue(done.isComplete());
		assertEquals(Long.valueOf(0), done.getPointsLeft());
		assertEquals("Ready", PestControlGoalPlanner.etaText(done.etaMillis(null)));
	}

	@Test
	public void brokenAndTrouverVariants_countAsOwned()
	{
		assertEquals(VoidPiece.TOP, VoidPiece.ofItem(ItemID.PEST_VOID_KNIGHT_TOP_BROKEN));
		assertEquals(VoidPiece.GLOVES, VoidPiece.ofItem(ItemID.PEST_VOID_KNIGHT_GLOVES_TROUVER));
		assertEquals(VoidPiece.ELITE_ROBE, VoidPiece.ofItem(ItemID.ELITE_VOID_KNIGHT_ROBES_TROUVER_MANGLED));
		assertNull(VoidPiece.ofItem(ItemID.COINS));
	}

	@Test
	public void nextMissing_deterministicOrder()
	{
		// Top, robe, gloves, the style's helm, the other helms, the mace.
		assertEquals(VoidPiece.TOP, PestControlGoalPlanner.nextMissing(VoidStyle.MAGIC, owned()));
		VoidOwnership.Snapshot body = owned(ItemID.PEST_VOID_KNIGHT_TOP, ItemID.PEST_VOID_KNIGHT_ROBES, ItemID.PEST_VOID_KNIGHT_GLOVES);
		assertEquals(VoidPiece.MAGE_HELM, PestControlGoalPlanner.nextMissing(VoidStyle.MAGIC, body));
		assertEquals(VoidPiece.RANGER_HELM, PestControlGoalPlanner.nextMissing(VoidStyle.RANGED, body));
		VoidOwnership.Snapshot helms = owned(ItemID.PEST_VOID_KNIGHT_TOP, ItemID.PEST_VOID_KNIGHT_ROBES, ItemID.PEST_VOID_KNIGHT_GLOVES,
			ItemID.GAME_PEST_MAGE_HELM);
		assertEquals("then the other helms, melee first", VoidPiece.MELEE_HELM, PestControlGoalPlanner.nextMissing(VoidStyle.MAGIC, helms));
		VoidOwnership.Snapshot allHelms = owned(ItemID.PEST_VOID_KNIGHT_TOP, ItemID.PEST_VOID_KNIGHT_ROBES, ItemID.PEST_VOID_KNIGHT_GLOVES,
			ItemID.GAME_PEST_MAGE_HELM, ItemID.GAME_PEST_MELEE_HELM, ItemID.GAME_PEST_ARCHER_HELM);
		assertEquals(VoidPiece.MACE, PestControlGoalPlanner.nextMissing(VoidStyle.MAGIC, allHelms));
	}

	@Test
	public void nextMissing_everythingOwned_isCompleteNotAnError()
	{
		VoidOwnership.Snapshot all = owned(ItemID.PEST_VOID_KNIGHT_TOP, ItemID.PEST_VOID_KNIGHT_ROBES, ItemID.PEST_VOID_KNIGHT_GLOVES,
			ItemID.GAME_PEST_MAGE_HELM, ItemID.GAME_PEST_MELEE_HELM, ItemID.GAME_PEST_ARCHER_HELM, ItemID.PEST_VOID_KNIGHT_MACE);
		PestControlGoalPlanner.Plan p = plan(VOID, all, 5);
		assertTrue(p.isComplete());
		assertEquals("All standard Void owned", p.getDetail());
	}

	@Test
	public void allStandard_is1850()
	{
		assertEquals(Long.valueOf(250 + 250 + 150 + 600 + 250), plan(VOID.withVoidGoal(PestControlGoal.VoidGoal.ALL_STANDARD), owned(), 0).getCost());
	}

	@Test
	public void bankNotSeen_saysSo()
	{
		VoidOwnership o = new VoidOwnership();
		o.update(VoidOwnership.Container.INVENTORY, items());
		PestControlGoalPlanner.Plan p = plan(VOID, o.getSnapshot(), 0);
		assertEquals("Need: Top (bank not seen yet)", p.getDetail());
	}

	// ---- balance / ETA ---------------------------------------------------------

	@Test
	public void pointsLeft_clampsAtZero_andUnknownBalanceIsTheFullCost()
	{
		PestControlGoalPlanner.Plan rich = plan(VOID, owned(), 900);
		assertEquals(Long.valueOf(0), rich.getPointsLeft());
		PestControlGoalPlanner.Plan unknown = plan(VOID, owned(), null);
		assertNull(unknown.getBalance());
		assertEquals("never assumes 0 points", Long.valueOf(250), unknown.getPointsLeft());
	}

	@Test
	public void eta_onlyFromAnObservedRate()
	{
		PestControlGoalPlanner.Plan p = plan(VOID, owned(), 10);
		assertNull("no rate, no ETA (never a default rate)", p.etaMillis(null));
		assertNull(p.etaMillis(0.0));
		assertEquals(Long.valueOf(4L * 3_600_000L), p.etaMillis(60.0));
		assertEquals("~4h 00m", PestControlGoalPlanner.etaText(p.etaMillis(60.0)));
		assertEquals("~45m", PestControlGoalPlanner.etaText(45L * 60_000L));
	}

	// ---- XP --------------------------------------------------------------------

	@Test
	public void xpGoal_usesExactCurrentXp()
	{
		PestControlGoal goal = VOID.withFamily(PestControlGoal.Family.XP).withXpSkill(Skill.DEFENCE).withXpTargetLevel(99);
		PestControlGoalPlanner.Plan p = PestControlGoalPlanner.plan(goal, owned(), 500,
			s -> s == Skill.DEFENCE ? Experience.getXpForLevel(98) : -1L);
		assertEquals(Long.valueOf(2000), p.getCost());
		assertEquals(Long.valueOf(1500), p.getPointsLeft());
	}

	@Test
	public void xpGoal_unknownXp_belowLevel25_andReached()
	{
		PestControlGoal goal = VOID.withFamily(PestControlGoal.Family.XP).withXpSkill(Skill.PRAYER).withXpTargetLevel(70);
		assertEquals("Prayer XP not known yet", PestControlGoalPlanner.plan(goal, owned(), 0, s -> -1L).getProblem());
		assertNull(PestControlGoalPlanner.plan(goal, owned(), 0, s -> -1L).getPointsLeft());
		assertEquals("Needs 25 Prayer", PestControlGoalPlanner.plan(goal, owned(), 0, s -> 1000L).getProblem());
		PestControlGoalPlanner.Plan done = PestControlGoalPlanner.plan(goal, owned(), 0, s -> (long) Experience.getXpForLevel(75));
		assertTrue(done.isComplete());
		assertEquals(Long.valueOf(0), done.getPointsLeft());
	}

	@Test
	public void xpGoal_ineligibleSkill_isCoercedToAnEligibleOne()
	{
		PestControlGoal goal = VOID.withFamily(PestControlGoal.Family.XP).withXpSkill(Skill.MINING);
		assertTrue(PestControlXp.isEligible(goal.getXpSkill()));
	}

	// ---- Supplies ----------------------------------------------------------------

	@Test
	public void mineralPacks_exactContents()
	{
		PestControlGoal goal = VOID.withFamily(PestControlGoal.Family.SUPPLIES).withPack(SupplyPack.MINERAL).withPackTarget(20);
		PestControlGoalPlanner.Plan p = plan(goal, owned(), 100);
		assertEquals(Long.valueOf(300), p.getCost());
		assertEquals(Long.valueOf(200), p.getPointsLeft());
		assertEquals("500 coal • 360 iron ore", p.getDetail());
	}

	@Test
	public void herbAndSeedPacks_neverFabricateYields()
	{
		PestControlGoalPlanner.Plan herb = plan(VOID.withFamily(PestControlGoal.Family.SUPPLIES).withPack(SupplyPack.HERB).withPackTarget(10),
			owned(), null);
		assertEquals(Long.valueOf(300), herb.getCost());
		assertEquals("10 herb packs • random contents", herb.getDetail());
		PestControlGoalPlanner.Plan seed = plan(VOID.withFamily(PestControlGoal.Family.SUPPLIES).withPack(SupplyPack.SEED).withPackTarget(1),
			owned(), null);
		assertEquals(Long.valueOf(15), seed.getCost());
		assertEquals("1 seed pack • random contents", seed.getDetail());
		for (String d : Arrays.asList(herb.getDetail(), seed.getDetail()))
		{
			assertFalse("no invented per-item yield", d.toLowerCase(java.util.Locale.ROOT).matches(".*(ranarr|snapdragon|guam|torstol|expected|~).*"));
		}
	}

	@Test
	public void packTarget_isClamped()
	{
		assertEquals(1, VOID.withPackTarget(0).getPackTarget());
		assertEquals(PestControlGoal.MAX_PACKS, VOID.withPackTarget(1_000_000).getPackTarget());
		assertEquals(99, VOID.withXpTargetLevel(150).getXpTargetLevel());
	}
}
