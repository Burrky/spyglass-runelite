package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Barbarian fishing COLD-START ownership: Fishing + Agility (+ Strength) XP on
 * one tick stays AMBIGUOUS on its own (SessionSignalBatchResolverTest's
 * Barbarian family is untouched); only FRESH, method-specific evidence from
 * the Fishing companion (a barbarian catch line / "Use-rod" click, reported
 * through ClassifierContext) breaks the tie -- and only for the skill it
 * names.
 */
public class SkillMethodEvidenceResolverTest
{
	private static final Instant T0 = Instant.parse("2026-10-12T20:00:00Z");
	private final ActivitySignalClassifier classifier = new ActivitySignalClassifier();

	private static List<SessionSignal> barbarianTick(Instant at)
	{
		Instant windowStart = at.minusSeconds(30);
		return Arrays.asList(
			SessionSignal.xpChange(at, "Fishing", 1_000, windowStart).withGameTick(7),
			SessionSignal.xpChange(at, "Agility", 100, windowStart).withGameTick(7),
			SessionSignal.xpChange(at, "Strength", 100, windowStart).withGameTick(7));
	}

	private Session sessionOf(ActivityIdentity identity, Instant at)
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(identity, at);
		return engine.getCurrentSession();
	}

	private static List<List<SessionSignal>> permutations(List<SessionSignal> items)
	{
		List<List<SessionSignal>> out = new ArrayList<>();
		permute(new ArrayList<>(items), 0, out);
		return out;
	}

	private static void permute(List<SessionSignal> items, int k, List<List<SessionSignal>> out)
	{
		if (k == items.size())
		{
			out.add(new ArrayList<>(items));
			return;
		}
		for (int i = k; i < items.size(); i++)
		{
			Collections.swap(items, k, i);
			permute(items, k + 1, out);
			Collections.swap(items, k, i);
		}
	}

	@Test
	public void freshBarbarianEvidence_coldStart_resolvesToFishing_inEveryOrder()
	{
		Instant at = T0.plusSeconds(60);
		for (List<SessionSignal> perm : permutations(barbarianTick(at)))
		{
			ClassifierContext ctx = new ClassifierContext();
			ctx.noteSkillMethodEvidence("fishing", at.minusSeconds(12));
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, null, classifier, ctx);
			assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
			assertEquals(ActivitySignalClassifier.skillingIdentity("fishing"), r.getLifecycleClassification().getIdentity());
			assertEquals("every XP metric survives", 3, r.getMetricUpdates().size());
		}
	}

	@Test
	public void noContext_staysAmbiguous()
	{
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(barbarianTick(T0.plusSeconds(60)), null, classifier,
			new ClassifierContext());
		assertEquals(BatchLifecycleOutcome.AMBIGUOUS, r.getOutcome());
	}

	@Test
	public void staleEvidence_fiveMinutesOld_staysAmbiguous()
	{
		Instant at = T0.plusSeconds(600);
		ClassifierContext ctx = new ClassifierContext();
		ctx.noteSkillMethodEvidence("fishing", at.minusSeconds(300));
		assertEquals(BatchLifecycleOutcome.AMBIGUOUS,
			SessionSignalBatchResolver.resolve(barbarianTick(at), null, classifier, ctx).getOutcome());
	}

	@Test
	public void staleEvidence_isNeverRescuedByALongXpWindow()
	{
		// A window that began 6 minutes ago (no XP in between) still only accepts evidence from the last ~45 s.
		Instant at = T0.plusSeconds(600);
		Instant windowStart = at.minusSeconds(360);
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(at, "Fishing", 50, windowStart).withGameTick(3),
			SessionSignal.xpChange(at, "Agility", 5, windowStart).withGameTick(3));
		ClassifierContext ctx = new ClassifierContext();
		ctx.noteSkillMethodEvidence("fishing", at.minusSeconds(300));
		assertEquals(BatchLifecycleOutcome.AMBIGUOUS, SessionSignalBatchResolver.resolve(batch, null, classifier, ctx).getOutcome());
	}

	@Test
	public void evidenceFromAfterTheXp_doesNotCount()
	{
		Instant at = T0.plusSeconds(60);
		ClassifierContext ctx = new ClassifierContext();
		ctx.noteSkillMethodEvidence("fishing", at.plusSeconds(30));
		assertEquals(BatchLifecycleOutcome.AMBIGUOUS,
			SessionSignalBatchResolver.resolve(barbarianTick(at), null, classifier, ctx).getOutcome());
	}

	@Test
	public void evidenceForAnotherSkill_neverHelpsFishing()
	{
		Instant at = T0.plusSeconds(60);
		ClassifierContext ctx = new ClassifierContext();
		ctx.noteSkillMethodEvidence("mining", at.minusSeconds(5));
		assertEquals(BatchLifecycleOutcome.AMBIGUOUS,
			SessionSignalBatchResolver.resolve(barbarianTick(at), null, classifier, ctx).getOutcome());
	}

	@Test
	public void existingAgilitySession_genericXp_isNotStolen()
	{
		Session agility = sessionOf(ActivitySignalClassifier.skillingIdentity("Agility"), T0);
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(barbarianTick(T0.plusSeconds(120)), agility,
			classifier, new ClassifierContext());
		assertEquals(agility.getActivityIdentity(), r.getLifecycleClassification().getIdentity());
	}

	@Test
	public void existingAgilitySession_freshBarbarianEvidence_isFishing()
	{
		Session agility = sessionOf(ActivitySignalClassifier.skillingIdentity("Agility"), T0);
		Instant at = T0.plusSeconds(120);
		ClassifierContext ctx = new ClassifierContext();
		ctx.noteSkillMethodEvidence("fishing", at.minusSeconds(4));
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(barbarianTick(at), agility, classifier, ctx);
		assertEquals("the catch proved it is fishing", ActivitySignalClassifier.skillingIdentity("fishing"),
			r.getLifecycleClassification().getIdentity());
	}

	@Test
	public void evidenceOnlyBreaksTies_itNeverStartsASessionWithoutFishingXp()
	{
		Instant at = T0.plusSeconds(60);
		ClassifierContext ctx = new ClassifierContext();
		ctx.noteSkillMethodEvidence("fishing", at.minusSeconds(3));
		List<SessionSignal> agilityOnly = Collections.singletonList(SessionSignal.xpChange(at, "Agility", 100, at.minusSeconds(30)).withGameTick(2));
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(agilityOnly, null, classifier, ctx);
		assertEquals("a lone Agility window is still Agility", ActivitySignalClassifier.skillingIdentity("agility"),
			r.getLifecycleClassification().getIdentity());
	}

	@Test
	public void otherSkillMixes_areUnaffectedByFishingEvidence()
	{
		Instant at = T0.plusSeconds(60);
		ClassifierContext ctx = new ClassifierContext();
		ctx.noteSkillMethodEvidence("fishing", at.minusSeconds(3));
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(at, "Woodcutting", 100, at.minusSeconds(30)).withGameTick(4),
			SessionSignal.xpChange(at, "Firemaking", 100, at.minusSeconds(30)).withGameTick(4));
		assertEquals(BatchLifecycleOutcome.AMBIGUOUS, SessionSignalBatchResolver.resolve(batch, null, classifier, ctx).getOutcome());
	}

	@Test
	public void freshnessWindow()
	{
		ClassifierContext ctx = new ClassifierContext();
		Instant at = T0.plusSeconds(100);
		assertFalse("nothing noted", ctx.hasFreshSkillMethodEvidence("fishing", null, at));
		ctx.noteSkillMethodEvidence("fishing", at.minusSeconds(44));
		assertTrue("one XP window + the click-hint freshness", ctx.hasFreshSkillMethodEvidence("fishing", null, at));
		assertFalse("the window began after it (minus 15 s)", ctx.hasFreshSkillMethodEvidence("fishing", at.minusSeconds(10), at));
		ctx.noteSkillMethodEvidence("fishing", at.minusSeconds(60));
		assertTrue("an older note never replaces a newer one", ctx.hasFreshSkillMethodEvidence("fishing", null, at));
		assertFalse(ctx.hasFreshSkillMethodEvidence("fishing", null, at.plusSeconds(10)));
	}
}
