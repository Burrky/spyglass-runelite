package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;

/**
 * Skill-companion ownership evidence (method evidence: barbarian fishing;
 * context evidence: infernal axe / Forestry / Arceuus) belongs to the account
 * that produced it. An account switch (ClassifierContext#reset) forgets it,
 * so a quick relog onto another account can never have its first XP window
 * tie-broken by the previous account's evidence.
 */
public class SkillEvidenceAccountIsolationTest
{
	private static final Instant XP_AT = Instant.parse("2026-10-13T21:00:00Z");

	@Test
	public void accountSwitch_forgetsMethodAndContextEvidence()
	{
		ClassifierContext ctx = new ClassifierContext();
		ctx.noteSkillMethodEvidence("fishing", XP_AT.minusSeconds(5));
		ctx.noteSkillContextEvidence("woodcutting", "FIREMAKING", XP_AT.minusSeconds(5));
		assertTrue(ctx.hasFreshSkillMethodEvidence("fishing", XP_AT.minusSeconds(20), XP_AT));
		assertTrue(ctx.hasFreshSkillContextEvidence("woodcutting", "FIREMAKING", XP_AT.minusSeconds(20), XP_AT));

		ctx.reset();

		assertFalse(ctx.hasFreshSkillMethodEvidence("fishing", XP_AT.minusSeconds(20), XP_AT));
		assertFalse(ctx.hasFreshSkillContextEvidence("woodcutting", "FIREMAKING", XP_AT.minusSeconds(20), XP_AT));
		// Fresh evidence on the new account works as before.
		ctx.noteSkillMethodEvidence("fishing", XP_AT.minusSeconds(2));
		assertTrue(ctx.hasFreshSkillMethodEvidence("fishing", XP_AT.minusSeconds(20), XP_AT));
	}
}
