package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillExternalStock;
import com.osrstelemetry.plugin.skills.SkillGoal;
import com.osrstelemetry.plugin.skills.SkillGoalPlanner;
import com.osrstelemetry.plugin.skills.herblore.HerbloreCompanionDefinition;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Experience;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Herblore TARGETS, decided in the shared model: LEVEL (above / reached, exact
 * XP left, ETA only from an established XP rate) and dose-normalized
 * STOCKPILE (bank variants + Potion Storage, unknown storage as bounds, ETA
 * only from what actually reached the bank / storage -- never the production
 * rate -- and only after the shared minimum deposits).
 */
public class HerbloreTargetTest
{
	private static final Gson GSON = new Gson();
	private static final Instant NOW = Instant.parse("2026-10-13T21:00:00Z");
	private static final int SR4 = ItemID._4DOSE2RESTORE;
	private static final int SR3 = ItemID._3DOSE2RESTORE;
	private static final int SR2 = ItemID._2DOSE2RESTORE;

	private static CurrentSessionSnapshot snapshot(long activeMillis, long xp)
	{
		Session s = GSON.fromJson(("{'sessionId':'s-h','activityIdentity':{'activityType':'SKILLING','activityKey':'herblore',"
			+ "'displayName':'Herblore'},'state':'ACTIVE','startedAt':'2026-10-13T19:58:00Z','lastActiveAt':'2026-10-13T21:00:00Z',"
			+ "'accumulatedActiveDurationMillis':" + activeMillis + ",'aggregates':{'xpGainedBySkill':{'HERBLORE':" + xp + "},'lootDrops':[],"
			+ "'producedItems':[{'itemId':" + SR3 + ",'itemName':'Super restore(3)','quantity':100}],"
			+ "'skillStats':{'skillKey':'herblore','methodMillis':{'super_restore':" + activeMillis + "},'lastMethod':'super_restore'}}}")
			.replace('\'', '"'), Session.class);
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s), NOW);
	}

	private static SkillCompanionView view(Map<Integer, SkillGoalPlanner.BankingSample> banking)
	{
		return new SkillCompanionView("herblore", "super_restore", "s-h", Collections.<Integer, Long>emptyMap(),
			Collections.<Integer, Long>emptyMap(), Collections.<String, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<Integer, Long>emptyMap(), Collections.<String, Long>emptyMap(), null,
			Collections.<SkillCompanionView.ContextLine>emptyList(), banking);
	}

	private static SkillCompanionModel.Target target(CurrentSessionSnapshot snap, SkillGoal goal, Integer xp, Map<Integer, Long> bank,
		SkillExternalStock storage, Map<Integer, SkillGoalPlanner.BankingSample> banking)
	{
		return SkillCompanionModel.build(snap, HerbloreCompanionDefinition.INSTANCE, view(banking), goal, xp,
			bank == null ? id -> null : id -> bank.getOrDefault(id, 0L), r -> storage).target;
	}

	@Test
	public void level_aboveCurrent_exactXpLeft_etaOnlyWithAnEstablishedRate()
	{
		int xp = Experience.getXpForLevel(85) + 1_000;
		SkillGoal goal = SkillGoal.DEFAULT.withTargetLevel(90);
		SkillCompanionModel.Target t = target(snapshot(3_600_000L, 200_000L), goal, xp, null, SkillExternalStock.NOT_APPLICABLE,
			Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		assertEquals(Long.valueOf(Experience.getXpForLevel(90) - xp), t.first);
		assertTrue(t.etaMillis != null && t.etaMillis > 0);
		SkillCompanionModel.Target early = target(snapshot(120_000L, 9_000L), goal, xp, null, SkillExternalStock.NOT_APPLICABLE,
			Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		assertNull("no rate yet, no ETA", early.etaMillis);
	}

	@Test
	public void level_equalOrBelow_isReached()
	{
		int xp = Experience.getXpForLevel(92);
		SkillCompanionModel.Target t = target(snapshot(3_600_000L, 200_000L), SkillGoal.DEFAULT.withTargetLevel(90), xp, null,
			SkillExternalStock.NOT_APPLICABLE, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		assertTrue(t.reached);
		assertEquals(Long.valueOf(0), t.first);
	}

	@Test
	public void stockpile_bankVariantsPlusStorage_ownedRemaining_neverDoubleCounted()
	{
		Map<Integer, Long> bank = new HashMap<>();
		bank.put(SR4, 10L);
		bank.put(SR3, 2L);
		bank.put(SR2, 1L);
		SkillGoal goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(SR4).withTargetQuantity(20);
		SkillCompanionModel.Target t = target(snapshot(3_600_000L, 200_000L), goal, 1, bank,
			SkillExternalStock.known("Potion Storage", 12L, null), Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		// 40 + 6 + 2 = 48 doses banked + 12 stored = 60 doses = 15 x 4-dose; target 80 doses -> 20 left = 5 x 4-dose.
		assertEquals(Long.valueOf(60), t.first);
		assertEquals("15 × 4-dose", t.firstText);
		assertEquals(Long.valueOf(20), t.remaining);
		assertEquals("5 × 4-dose", t.remainingText);
		assertEquals("Bank + Potion Storage", t.ownershipLine);
		assertFalse(t.ownershipAlert);
		assertNull("no deposits yet: no ETA", t.etaMillis);
	}

	@Test
	public void stockpile_eta_fromStoredDosesOnly_afterTheMinimumDeposits_neverFromProduction()
	{
		Map<Integer, Long> bank = Collections.singletonMap(SR4, 0L);
		SkillGoal goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(SR4).withTargetQuantity(100);
		Map<Integer, SkillGoalPlanner.BankingSample> one = Collections.singletonMap(SR3,
			new SkillGoalPlanner.BankingSample(100, 1, 3_600_000L));
		assertNull("one deposit is not a rate", target(snapshot(3_600_000L, 200_000L), goal, 1, bank, SkillExternalStock.NOT_APPLICABLE, one)
			.etaMillis);
		Map<Integer, SkillGoalPlanner.BankingSample> two = Collections.singletonMap(SR3,
			new SkillGoalPlanner.BankingSample(100, 2, 3_600_000L));
		SkillCompanionModel.Target t = target(snapshot(3_600_000L, 200_000L), goal, 1, bank, SkillExternalStock.NOT_APPLICABLE, two);
		// 300 doses stored per hour; 400 doses to go -> 80 minutes.
		assertEquals(Long.valueOf(80L * 60_000L), t.etaMillis);
	}

	@Test
	public void stockpile_unknownStorage_isALowerBound_withoutEta()
	{
		SkillGoal goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(SR4).withTargetQuantity(100);
		Map<Integer, SkillGoalPlanner.BankingSample> two = Collections.singletonMap(SR3,
			new SkillGoalPlanner.BankingSample(100, 2, 3_600_000L));
		SkillCompanionModel.Target t = target(snapshot(3_600_000L, 200_000L), goal, 1, Collections.singletonMap(SR4, 50L),
			SkillExternalStock.unknown("Potion Storage"), two);
		assertEquals("≥ 50 × 4-dose", t.firstText);
		assertEquals("≤ 50 × 4-dose", t.remainingText);
		assertTrue(t.ownershipAlert);
		assertNull(t.etaMillis);
	}

	@Test
	public void stockpile_unfinishedPotion_countsItemsInBankAndStorage()
	{
		CurrentSessionSnapshot snap = snapshot(3_600_000L, 0L);
		SkillGoal goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(ItemID.RANARRVIAL).withTargetQuantity(500);
		SkillCompanionModel.Target t = SkillCompanionModel.build(snap, HerbloreCompanionDefinition.INSTANCE,
			new SkillCompanionView("herblore", "ranarr_unf", "s-h", Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
				Collections.<String, Long>emptyMap(), Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
				Collections.<String, Long>emptyMap(), null, Collections.<SkillCompanionView.ContextLine>emptyList(),
				Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap()),
			goal, 1, id -> id == ItemID.RANARRVIAL ? 120L : 0L, r -> SkillExternalStock.known("Potion Storage", 80L, null)).target;
		assertEquals("200", t.firstText);
		assertEquals("300", t.remainingText);
	}
}
