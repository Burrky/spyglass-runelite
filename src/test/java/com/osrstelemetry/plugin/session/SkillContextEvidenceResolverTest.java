package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * SKILL-CONTEXT XP OWNERSHIP (Skills Phase 4): another skill's XP that a
 * skill companion PROVED belongs to its own activity (infernal axe
 * Firemaking; a Forestry event's Hunter / Farming / ... XP) stays a METRIC of
 * that activity -- only with fresh evidence from INSIDE the XP's own window,
 * only for the owner the evidence names, only when that owner is the current
 * session or is proposed in the same batch. Every XP metric survives.
 */
public class SkillContextEvidenceResolverTest
{
	private static final Instant T0 = Instant.parse("2026-10-13T20:00:00Z");
	private final ActivitySignalClassifier classifier = new ActivitySignalClassifier();

	private static List<SessionSignal> infernalTick(Instant at)
	{
		Instant windowStart = at.minusSeconds(30);
		return Arrays.asList(
			SessionSignal.xpChange(at, "Woodcutting", 2_000, windowStart).withGameTick(9),
			SessionSignal.xpChange(at, "Firemaking", 600, windowStart).withGameTick(9));
	}

	private static Session sessionOf(ActivityIdentity identity, Instant at)
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(identity, at);
		return engine.getCurrentSession();
	}

	@Test
	public void coldStart_withEvidenceInsideTheWindow_resolvesToWoodcutting_inEitherOrder()
	{
		Instant at = T0.plusSeconds(60);
		for (List<SessionSignal> batch : Arrays.asList(infernalTick(at), Arrays.asList(infernalTick(at).get(1), infernalTick(at).get(0))))
		{
			ClassifierContext ctx = new ClassifierContext();
			ctx.noteSkillContextEvidence("woodcutting", "FIREMAKING", at.minusSeconds(10));
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, null, classifier, ctx);
			assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
			assertEquals(ActivitySignalClassifier.skillingIdentity("woodcutting"), r.getLifecycleClassification().getIdentity());
			assertEquals("both XP metrics survive", 2, r.getMetricUpdates().size());
		}
	}

	@Test
	public void coldStart_withoutEvidence_staysAmbiguous()
	{
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(infernalTick(T0.plusSeconds(60)), null, classifier,
			new ClassifierContext());
		assertEquals(BatchLifecycleOutcome.AMBIGUOUS, r.getOutcome());
	}

	@Test
	public void evidenceFromBeforeTheWindow_neverCovers()
	{
		Instant at = T0.plusSeconds(60);
		ClassifierContext ctx = new ClassifierContext();
		ctx.noteSkillContextEvidence("woodcutting", "FIREMAKING", at.minusSeconds(32));
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(infernalTick(at), null, classifier, ctx);
		assertEquals(BatchLifecycleOutcome.AMBIGUOUS, r.getOutcome());
		assertFalse(ctx.hasFreshSkillContextEvidence("woodcutting", "Firemaking", at.minusSeconds(30), at));
	}

	@Test
	public void evidenceFreshness_window_age_andSlack()
	{
		ClassifierContext ctx = new ClassifierContext();
		Instant at = T0.plusSeconds(100);
		ctx.noteSkillContextEvidence("woodcutting", "HUNTER", at.minusSeconds(5));
		assertTrue(ctx.hasFreshSkillContextEvidence("woodcutting", "Hunter", at.minusSeconds(20), at));
		assertTrue("1 s lead before the window", ctx.hasFreshSkillContextEvidence("woodcutting", "hunter", at.minusMillis(4_500), at));
		assertFalse("window began after the evidence", ctx.hasFreshSkillContextEvidence("woodcutting", "HUNTER", at.minusSeconds(3), at));
		assertFalse("too old", ctx.hasFreshSkillContextEvidence("woodcutting", "HUNTER", null, at.plusSeconds(40)));
		assertFalse("another owner", ctx.hasFreshSkillContextEvidence("mining", "HUNTER", null, at));
		assertFalse("another skill", ctx.hasFreshSkillContextEvidence("woodcutting", "THIEVING", null, at));
		assertFalse("from well after the event", ctx.hasFreshSkillContextEvidence("woodcutting", "HUNTER", null, at.minusSeconds(15)));
	}

	@Test
	public void loneContextXp_withACurrentOwnerSession_isMetricOnly()
	{
		Instant at = T0.plusSeconds(60);
		Session wc = sessionOf(ActivitySignalClassifier.skillingIdentity("woodcutting"), T0);
		ClassifierContext ctx = new ClassifierContext();
		ctx.noteSkillContextEvidence("woodcutting", "HUNTER", at.minusSeconds(4));
		List<SessionSignal> batch = Collections.singletonList(SessionSignal.xpChange(at, "Hunter", 300, at.minusSeconds(10)).withGameTick(3));
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, wc, classifier, ctx);
		assertEquals(BatchLifecycleOutcome.NO_EVIDENCE, r.getOutcome());
		assertEquals("the Hunter XP is kept as a metric", 1, r.getMetricUpdates().size());
	}

	@Test
	public void loneContextXp_withoutTheOwnerAnywhere_isUntouched()
	{
		Instant at = T0.plusSeconds(60);
		Session mining = sessionOf(ActivitySignalClassifier.skillingIdentity("mining"), T0);
		ClassifierContext ctx = new ClassifierContext();
		ctx.noteSkillContextEvidence("woodcutting", "HUNTER", at.minusSeconds(4));
		List<SessionSignal> batch = Collections.singletonList(SessionSignal.xpChange(at, "Hunter", 300, at.minusSeconds(10)).withGameTick(3));
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, mining, classifier, ctx);
		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals(ActivitySignalClassifier.skillingIdentity("hunter"), r.getLifecycleClassification().getIdentity());
	}

	@Test
	public void theOwnersOwnXp_isNeverRewritten()
	{
		Instant at = T0.plusSeconds(60);
		Session wc = sessionOf(ActivitySignalClassifier.skillingIdentity("woodcutting"), T0);
		ClassifierContext ctx = new ClassifierContext();
		ctx.noteSkillContextEvidence("woodcutting", "WOODCUTTING", at.minusSeconds(4));
		List<SessionSignal> batch = Collections.singletonList(
			SessionSignal.xpChange(at, "Woodcutting", 300, at.minusSeconds(10)).withGameTick(3));
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, wc, classifier, ctx);
		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals(ActivitySignalClassifier.skillingIdentity("woodcutting"), r.getLifecycleClassification().getIdentity());
	}
}
