package com.osrstelemetry.plugin.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.GotrStrategy;
import org.junit.Test;

/**
 * GotR rift advice: a winner only when the open rifts are known, usable and
 * the mechanics say one is meaningfully better -- otherwise EITHER / CAPPED /
 * NONE / INSUFFICIENT_EVIDENCE, never a forced winner.
 */
public class GotrAdviceTest
{
	private static GotrGameState game(int e, int c, GotrAltar elemental, GotrAltar catalytic, Boolean eOk, Boolean cOk)
	{
		return GotrGameState.of(50, e, c, elemental, catalytic, eOk, cOk);
	}

	private static GotrAdvice points(GotrGameState g, GotrRewardWallet w)
	{
		return GotrAdvice.of(GotrStrategy.POINTS, g, w);
	}

	private static GotrAdvice xp(GotrGameState g)
	{
		return GotrAdvice.of(GotrStrategy.XP, g, null);
	}

	// ---- POINTS ------------------------------------------------------------

	@Test
	public void points_catalyticBehind_inTheWallet_focusesTheSide_notAnAltar()
	{
		GotrAdvice a = points(game(200, 200, GotrAltar.AIR, GotrAltar.NATURE, true, true), GotrRewardWallet.of(31, 27));
		assertEquals(GotrAdvice.Outcome.FOCUS_CATALYTIC, a.getOutcome());
		assertEquals(GotrAltar.Type.CATALYTIC, a.getFocus());
		assertNull("which catalytic altar does not matter for points", a.getAltar());
		assertEquals("4 behind", a.getReason());
		assertTrue(a.getDetail().contains("catalytic is 4 points behind"));
	}

	@Test
	public void points_elementalBehind_countingThisGamesEnergyToo()
	{
		// Wallet even, this game 100 catalytic ahead: elemental is one point behind.
		GotrAdvice a = points(game(150, 250, GotrAltar.WATER, GotrAltar.CHAOS, true, true), GotrRewardWallet.of(10, 10));
		assertEquals(GotrAdvice.Outcome.FOCUS_ELEMENTAL, a.getOutcome());
		assertEquals(GotrAltar.Type.ELEMENTAL, a.getFocus());
	}

	@Test
	public void points_balanced_isEither_neverAFalseWinner()
	{
		GotrAdvice a = points(game(220, 240, GotrAltar.AIR, GotrAltar.MIND, true, true), GotrRewardWallet.of(12, 12));
		assertEquals(GotrAdvice.Outcome.EITHER, a.getOutcome());
		assertNull(a.getAltar());
		assertFalse(a.isRecommendation());
	}

	@Test
	public void points_unknownWallet_usesThisGamesEnergyOnly_andSaysSo()
	{
		GotrAdvice a = points(game(400, 100, GotrAltar.AIR, GotrAltar.CHAOS, true, true), null);
		assertEquals(GotrAdvice.Outcome.FOCUS_CATALYTIC, a.getOutcome());
		assertEquals("Behind this game", a.getReason());
	}

	@Test
	public void points_wikiPriority_bloodDeathThenFireEarth_bestCellsFeedBothTypes()
	{
		// Even with elemental behind, an open Death rift comes first (overcharged cells feed both types).
		GotrAdvice death = points(game(100, 400, GotrAltar.WATER, GotrAltar.DEATH, true, true), GotrRewardWallet.of(5, 20));
		assertEquals(GotrAltar.DEATH, death.getAltar());
		assertEquals("Best cells", death.getReason());
		assertEquals(GotrAdvice.Outcome.RECOMMEND_CATALYTIC, death.getOutcome());
		assertEquals(GotrAltar.BLOOD, points(game(0, 0, GotrAltar.FIRE, GotrAltar.BLOOD, true, true), null).getAltar());
		assertEquals(GotrAltar.FIRE, points(game(0, 0, GotrAltar.FIRE, GotrAltar.NATURE, true, true), null).getAltar());
		assertEquals(GotrAltar.EARTH, points(game(0, 0, GotrAltar.EARTH, GotrAltar.LAW, true, true), null).getAltar());
		assertEquals(GotrAltar.DEATH, points(game(0, 0, GotrAltar.EARTH, GotrAltar.DEATH, true, true), null).getAltar());
	}

	@Test
	public void points_priorityAltarTheyCannotUse_fallsBackToTheBalanceRule()
	{
		GotrAdvice a = points(game(300, 100, GotrAltar.AIR, GotrAltar.DEATH, true, false), null);
		assertEquals("Death is unusable, and catalytic is behind: nothing usable wins", GotrAdvice.Outcome.NONE, a.getOutcome());
		GotrAdvice b = points(game(100, 300, GotrAltar.AIR, GotrAltar.DEATH, true, false), null);
		assertEquals(GotrAdvice.Outcome.FOCUS_ELEMENTAL, b.getOutcome());
		assertEquals(GotrAltar.Type.ELEMENTAL, b.getFocus());
	}

	@Test
	public void points_caps_aCappedTypeIsNeverRecommended_totalCapMeansNoMorePoints()
	{
		GotrAdvice capped = points(game(700, 500, GotrAltar.AIR, GotrAltar.MIND, true, true), null);
		assertEquals(GotrAdvice.Outcome.CAPPED, capped.getOutcome());
		GotrAdvice typeCap = points(game(1000, 100, GotrAltar.FIRE, GotrAltar.MIND, true, true), GotrRewardWallet.of(0, 50));
		assertEquals("elemental is behind in the wallet but capped this game: no winner", GotrAdvice.Outcome.NONE, typeCap.getOutcome());
		assertEquals("Fire's cell priority never overrides its type cap", null, typeCap.getAltar());
	}

	@Test
	public void points_partialInformation_isInsufficientEvidence()
	{
		assertEquals(GotrAdvice.Outcome.INSUFFICIENT_EVIDENCE, points(null, null).getOutcome());
		assertEquals(GotrAdvice.Outcome.INSUFFICIENT_EVIDENCE, points(game(1, 1, GotrAltar.AIR, null, true, true), null).getOutcome());
		assertEquals(GotrAdvice.Outcome.INSUFFICIENT_EVIDENCE, points(game(1, 1, GotrAltar.AIR, GotrAltar.MIND, null, true), null).getOutcome());
		assertEquals(GotrAdvice.Outcome.INSUFFICIENT_EVIDENCE,
			points(GotrGameState.of(50, null, 3, GotrAltar.AIR, GotrAltar.MIND, true, true), null).getOutcome());
		assertNull(points(null, null).getReason());
	}

	// ---- XP ----------------------------------------------------------------

	@Test
	public void xp_higherXpPerEssence_amongTheOpenRifts()
	{
		GotrAdvice a = xp(game(0, 0, GotrAltar.EARTH, GotrAltar.DEATH, true, true));
		assertEquals(GotrAdvice.Outcome.RECOMMEND_CATALYTIC, a.getOutcome());
		assertEquals(GotrAltar.DEATH, a.getAltar());
		assertEquals("+3.5 / ess", a.getReason());
		assertEquals(GotrAltar.FIRE, xp(game(0, 0, GotrAltar.FIRE, GotrAltar.MIND, true, true)).getAltar());
	}

	@Test
	public void xp_similarXp_isEither()
	{
		assertEquals(GotrAdvice.Outcome.EITHER, xp(game(0, 0, GotrAltar.FIRE, GotrAltar.BODY, true, true)).getOutcome());
		assertEquals("1.5 XP per essence apart is meaningful", GotrAltar.COSMIC, xp(game(0, 0, GotrAltar.EARTH, GotrAltar.COSMIC, true, true)).getAltar());
		assertEquals("0.5 apart is not", GotrAdvice.Outcome.EITHER, xp(game(0, 0, GotrAltar.WATER, GotrAltar.MIND, true, true)).getOutcome());
	}

	@Test
	public void xp_theHighXpRiftTheyCannotUse_isNeverRecommended()
	{
		GotrAdvice a = xp(game(0, 0, GotrAltar.EARTH, GotrAltar.BLOOD, true, false));
		assertEquals(GotrAdvice.Outcome.RECOMMEND_ELEMENTAL, a.getOutcome());
		assertEquals(GotrAltar.EARTH, a.getAltar());
		assertEquals("Only usable", a.getReason());
		assertEquals(GotrAdvice.Outcome.NONE, xp(game(0, 0, GotrAltar.EARTH, GotrAltar.BLOOD, false, false)).getOutcome());
	}

	@Test
	public void xp_unknownEligibilityOrRifts_isInsufficientEvidence()
	{
		assertEquals(GotrAdvice.Outcome.INSUFFICIENT_EVIDENCE, xp(game(0, 0, GotrAltar.EARTH, GotrAltar.BLOOD, true, null)).getOutcome());
		assertEquals(GotrAdvice.Outcome.INSUFFICIENT_EVIDENCE, xp(game(0, 0, null, GotrAltar.BLOOD, true, true)).getOutcome());
	}

	// ---- PORTAL TALISMANS: more candidates, never a free recommendation ----

	private static GotrGameState withTalismans(int e, int c, GotrAltar el, GotrAltar cat, Object... altarUsable)
	{
		java.util.Map<GotrAltar, Boolean> t = new java.util.EnumMap<>(GotrAltar.class);
		for (int i = 0; i < altarUsable.length; i += 2)
		{
			t.put((GotrAltar) altarUsable[i], (Boolean) altarUsable[i + 1]);
		}
		return GotrGameState.of(50, e, c, el, cat, true, true, t);
	}

	@Test
	public void candidates_openRiftsThenTalismans_noDuplicateForAnOpenAltar()
	{
		GotrGameState g = withTalismans(0, 0, GotrAltar.AIR, GotrAltar.MIND, GotrAltar.MIND, true, GotrAltar.LAW, false, GotrAltar.FIRE, true);
		java.util.List<GotrCandidate> c = g.getCandidates();
		assertEquals(4, c.size());
		assertEquals(GotrAltar.AIR, c.get(0).getAltar());
		assertEquals(GotrCandidate.Access.OPEN, c.get(1).getAccess());
		assertEquals("the Mind talisman adds nothing while Mind is open", GotrAltar.FIRE, c.get(2).getAltar());
		assertEquals(GotrCandidate.Access.TALISMAN, c.get(2).getAccess());
		assertEquals(GotrAltar.LAW, c.get(3).getAltar());
		assertFalse(c.get(3).getUsable());
	}

	@Test
	public void xp_aTalismanAltar_winsOnlyOnItsXp()
	{
		GotrAdvice a = xp(withTalismans(0, 0, GotrAltar.EARTH, GotrAltar.MIND, GotrAltar.NATURE, true));
		assertEquals(GotrAltar.NATURE, a.getAltar());
		assertEquals("+2.5 / ess", a.getReason());
		GotrAdvice b = xp(withTalismans(0, 0, GotrAltar.EARTH, GotrAltar.DEATH, GotrAltar.AIR, true));
		assertEquals("a worse talisman changes nothing", GotrAltar.DEATH, b.getAltar());
		GotrAdvice c = xp(withTalismans(0, 0, GotrAltar.EARTH, GotrAltar.MIND, GotrAltar.BLOOD, false));
		assertEquals("a talisman for a locked altar is never advised", GotrAltar.EARTH, c.getAltar());
	}

	@Test
	public void xp_nearTieOfOneType_focusesThatType_acrossTypes_isEither()
	{
		GotrAdvice a = xp(withTalismans(0, 0, GotrAltar.EARTH, GotrAltar.DEATH, GotrAltar.BLOOD, true));
		assertEquals(GotrAdvice.Outcome.FOCUS_CATALYTIC, a.getOutcome());
		assertEquals("Similar XP", a.getReason());
	}

	@Test
	public void points_talismanForAPriorityAltar_isBestOnMerit_tiesFocusTheSide()
	{
		GotrAdvice blood = points(withTalismans(100, 100, GotrAltar.AIR, GotrAltar.MIND, GotrAltar.BLOOD, true), null);
		assertEquals(GotrAltar.BLOOD, blood.getAltar());
		GotrAdvice tie = points(withTalismans(100, 100, GotrAltar.AIR, GotrAltar.DEATH, GotrAltar.BLOOD, true), null);
		assertEquals("Death open + Blood talisman: same cells, so the side", GotrAdvice.Outcome.FOCUS_CATALYTIC, tie.getOutcome());
		GotrAdvice side = points(withTalismans(400, 100, GotrAltar.AIR, GotrAltar.MIND, GotrAltar.CHAOS, true), null);
		assertEquals(GotrAdvice.Outcome.FOCUS_CATALYTIC, side.getOutcome());
		assertEquals(GotrAdvice.Mark.FOCUS, side.markOf(new GotrCandidate(GotrAltar.CHAOS, GotrCandidate.Access.TALISMAN, true)));
		assertEquals(GotrAdvice.Mark.FOCUS, side.markOf(new GotrCandidate(GotrAltar.MIND, GotrCandidate.Access.OPEN, true)));
		assertEquals(GotrAdvice.Mark.NONE, side.markOf(new GotrCandidate(GotrAltar.AIR, GotrCandidate.Access.OPEN, true)));
		assertEquals(GotrAdvice.Mark.NONE, side.markOf(new GotrCandidate(GotrAltar.LAW, GotrCandidate.Access.TALISMAN, false)));
	}

	@Test
	public void unknownTalismanEligibility_isInsufficientEvidence()
	{
		assertEquals(GotrAdvice.Outcome.INSUFFICIENT_EVIDENCE,
			xp(withTalismans(0, 0, GotrAltar.EARTH, GotrAltar.MIND, GotrAltar.BLOOD, null)).getOutcome());
	}

	@Test
	public void portalTalismanItems_nativeIds_oneAltarEach()
	{
		java.util.Set<GotrAltar> all = java.util.EnumSet.noneOf(GotrAltar.class);
		for (int id : new int[]{net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_AIR, net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_WATER,
			net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_EARTH, net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_FIRE,
			net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_MIND, net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_BODY,
			net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_COSMIC, net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_CHAOS,
			net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_NATURE, net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_LAW,
			net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_DEATH, net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_BLOOD})
		{
			all.add(GotrPortalTalismans.altarOf(id));
		}
		assertEquals(12, all.size());
		assertEquals(GotrAltar.DEATH, GotrPortalTalismans.altarOf(net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_DEATH));
		assertNull("an ordinary talisman (reward) is not a portal talisman", GotrPortalTalismans.altarOf(net.runelite.api.gameval.ItemID.LAW_TALISMAN));
		java.util.Map<Integer, Long> inv = new java.util.HashMap<>();
		inv.put(net.runelite.api.gameval.ItemID.GOTR_PORTAL_TALISMAN_CHAOS, 2L);
		inv.put(net.runelite.api.gameval.ItemID.GOTR_GUARDIAN_ESSENCE, 20L);
		assertEquals(java.util.EnumSet.of(GotrAltar.CHAOS), GotrPortalTalismans.heldIn(inv));
	}

	// ---- WALLET / ALTAR TABLE ---------------------------------------------

	@Test
	public void wallet_searchesAreTheSmallerSide_limitingSide_balanced()
	{
		GotrRewardWallet w = GotrRewardWallet.of(31, 27);
		assertEquals(27, w.searches());
		assertEquals(GotrAltar.Type.CATALYTIC, w.limitingSide());
		assertEquals(4, w.imbalance());
		assertNull(GotrRewardWallet.of(9, 9).limitingSide());
		assertNull("both sides must be known", GotrRewardWallet.of(9, null));
		assertNull(GotrRewardWallet.of(-1, 3));
	}

	@Test
	public void altarTable_liveVerifiedIndices_andOutOfRangeIsUnknown()
	{
		assertEquals(GotrAltar.EARTH, GotrAltar.of(GotrAltar.Type.ELEMENTAL, 3));
		assertEquals(GotrAltar.FIRE, GotrAltar.of(GotrAltar.Type.ELEMENTAL, 4));
		assertEquals(GotrAltar.COSMIC, GotrAltar.of(GotrAltar.Type.CATALYTIC, 3));
		assertEquals(GotrAltar.NATURE, GotrAltar.of(GotrAltar.Type.CATALYTIC, 5));
		assertEquals(GotrAltar.DEATH, GotrAltar.of(GotrAltar.Type.CATALYTIC, 7));
		assertNull(GotrAltar.of(GotrAltar.Type.ELEMENTAL, 0));
		assertNull(GotrAltar.of(GotrAltar.Type.ELEMENTAL, 5));
		assertNull(GotrAltar.of(GotrAltar.Type.CATALYTIC, 9));
		assertNull(GotrAltar.of(GotrAltar.Type.CATALYTIC, null));
		double last = 0;
		for (GotrAltar a : new GotrAltar[]{GotrAltar.AIR, GotrAltar.MIND, GotrAltar.WATER, GotrAltar.EARTH, GotrAltar.FIRE, GotrAltar.BODY,
			GotrAltar.COSMIC, GotrAltar.CHAOS, GotrAltar.NATURE, GotrAltar.LAW, GotrAltar.DEATH, GotrAltar.BLOOD})
		{
			assertTrue("standard XP per essence rises with the altar's level", a.getXpPerEssence() > last);
			last = a.getXpPerEssence();
		}
	}
}
