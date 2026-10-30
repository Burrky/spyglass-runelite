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
 * XP left, ETA only from an established XP rate). Herblore offers no
 * resource (stockpile) target: a saved one reads as its Level target.
 */
public class HerbloreTargetTest
{
	private static final Gson GSON = new Gson();
	private static final Instant NOW = Instant.parse("2026-10-13T21:00:00Z");
	private static final int SR4 = ItemID._4DOSE2RESTORE;
	private static final int SR3 = ItemID._3DOSE2RESTORE;

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
	public void herblore_offersNoResourceTarget()
	{
		assertFalse("potions are made to be used: Level is Herblore's only target", HerbloreCompanionDefinition.INSTANCE.offersResourceTarget());
	}

	@Test
	public void savedStockpilePreference_readsAsTheLevelTarget_theSavedLevelIsKept()
	{
		int xp = Experience.getXpForLevel(85) + 1_000;
		Map<Integer, Long> bank = new HashMap<>();
		bank.put(SR4, 10L);
		SkillGoal saved = SkillGoal.DEFAULT.withTargetLevel(90).withFamily(SkillGoal.Family.STOCKPILE).withResource(SR4).withTargetQuantity(20);
		SkillCompanionModel.Target t = target(snapshot(3_600_000L, 200_000L), saved, xp, bank,
			SkillExternalStock.known("Potion Storage", 12L, null), Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		assertEquals(SkillGoal.Family.LEVEL, t.goal.getFamily());
		assertEquals(90, t.goal.getTargetLevel());
		assertEquals(Long.valueOf(Experience.getXpForLevel(90) - xp), t.first);
		assertNull("no stockpile line", t.ownershipLine);
		assertEquals("the saved preference object is never rewritten by reading it", SkillGoal.Family.STOCKPILE, saved.getFamily());
		assertEquals(Integer.valueOf(SR4), saved.getResourceItemId());
	}
}
