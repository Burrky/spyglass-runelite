package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;

/**
 * Skill-to-skill takeover through the REAL session pipeline (live QA: Woodcutting
 * -> Runecraft and Runecraft -> Herblore took minutes / ~30 potions). Root
 * cause: a SUSPENDED session yields only to a SECOND XP window of the new
 * skill, and XP windows close every ~30 s -- so ownership waited for two
 * windows of the new skill, however much real output it already had (and a
 * once-per-trip Runecraft craft could miss a window entirely). The fix: XP
 * whose window holds a CAUSALLY CONFIRMED success of that same skill (its
 * companion saw the XP and the matching items change together) is SPECIFIC
 * evidence against a SUSPENDED different session -- it takes over at once,
 * and the waiting skill's XP window is closed early. XP amount is never
 * consulted; support XP without such output, ACTIVE sessions and Smart
 * Slayer's combat hysteresis are untouched.
 */
public class SkillTakeoverSessionTest extends StructuredActivityTestSupport
{
	private static final Instant T0 = Instant.parse("2026-10-14T18:00:00Z");

	private static SessionSignal xp(String skill, long delta, Instant at, Instant windowStart)
	{
		return SessionSignal.xpChange(at, skill, delta, windowStart);
	}

	private static boolean is(Session s, String skillKey)
	{
		return s != null && s.getState() != SessionState.FINALIZED && s.getActivityIdentity().getActivityType() == ActivityType.SKILLING
			&& skillKey.equals(s.getActivityIdentity().getActivityKey());
	}

	/** Three ~30 s windows of a skill: its session, established and ACTIVE. */
	private Instant establish(String skill, Instant start)
	{
		Instant t = start;
		for (int i = 1; i <= 3; i++)
		{
			Instant from = t;
			t = start.plusSeconds(30L * i);
			onTick(t, xp(skill, 900, t, from));
		}
		assertTrue(is(current(), skill.toLowerCase()));
		return t;
	}

	/** The established session goes idle long enough to suspend (the coordinator's own tick advances time). */
	private Instant suspend(Instant lastActive)
	{
		Instant t = lastActive.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(30);
		onTick(t);
		assertEquals(SessionState.SUSPENDED, current().getState());
		return t;
	}

	@Test
	public void strongRunecraft_displacesSuspendedWoodcutting_onItsFirstWindow()
	{
		Instant t = suspend(establish("WOODCUTTING", T0));
		String woodcutting = current().getSessionId();
		// One altar craft: the companion saw essence become runes with the Runecraft XP.
		Instant craft = t.plusSeconds(40);
		coordinator.noteSkillProductionEvidence("runecraft", craft);
		assertEquals("the waiting skill's XP window is asked to close now", "runecraft", coordinator.drainEarlyXpWindowSkill());
		Instant window = craft.plusSeconds(1);
		onTick(window, xp("RUNECRAFT", 2_400, window, t));
		assertTrue("Runecraft owns the session after ONE window", is(current(), "runecraft"));
		assertTrue(!woodcutting.equals(current().getSessionId()));
	}

	@Test
	public void strongHerblore_displacesSuspendedRunecraft_promptly()
	{
		Instant t = suspend(establish("RUNECRAFT", T0));
		Instant potion = t.plusSeconds(20);
		coordinator.noteSkillProductionEvidence("herblore", potion);
		Instant window = potion.plusSeconds(1);
		onTick(window, xp("HERBLORE", 75, window, potion.minusSeconds(2)));
		assertTrue("one potion's window, not ~30 potions", is(current(), "herblore"));
	}

	@Test
	public void withoutConfirmedOutput_theSuspendedSessionStillWantsTwoWindows()
	{
		Instant t = suspend(establish("RUNECRAFT", T0));
		Instant w1 = t.plusSeconds(30);
		onTick(w1, xp("HERBLORE", 75, w1, t));
		assertTrue("XP alone (any amount) is never a shortcut", is(current(), "runecraft"));
		assertEquals(SessionState.SUSPENDED, current().getState());
		Instant w2 = w1.plusSeconds(30);
		onTick(w2, xp("HERBLORE", 75, w2, w1));
		assertTrue("the existing two-window confirmation still works", is(current(), "herblore"));
	}

	@Test
	public void hugeXpWithoutOutput_isNoShortcut()
	{
		Instant t = suspend(establish("WOODCUTTING", T0));
		Instant w1 = t.plusSeconds(30);
		onTick(w1, xp("HERBLORE", 250_000, w1, t));
		assertTrue(is(current(), "woodcutting"));
	}

	@Test
	public void staleOutput_fromBeforeTheWindow_neverCounts()
	{
		Instant t = suspend(establish("RUNECRAFT", T0));
		// A potion made long before this XP window began (the player then went idle): not this window's evidence.
		coordinator.noteSkillProductionEvidence("herblore", t.minusSeconds(200));
		Instant w1 = t.plusSeconds(60);
		onTick(w1, xp("HERBLORE", 75, w1, t.plusSeconds(30)));
		assertTrue(is(current(), "runecraft"));
	}

	@Test
	public void activeSession_isNeverDislodgedByOneWindow_evenWithOutput()
	{
		Instant t = establish("MINING", T0);
		coordinator.noteSkillProductionEvidence("fishing", t.plusSeconds(10));
		assertNull("no early window for an ACTIVE session's challenger", coordinator.drainEarlyXpWindowSkill());
		Instant w1 = t.plusSeconds(20);
		onTick(w1, xp("FISHING", 300, w1, t));
		assertTrue("ACTIVE hysteresis untouched: one window never switches", is(current(), "mining"));
	}

	@Test
	public void supportXp_withoutItsOwnOutput_neverStealsTheSuspendedSession()
	{
		// Barbarian fishing: Fishing has confirmed catches; Strength / Agility XP of the same catches has no output of its own.
		Instant t = suspend(establish("FISHING", T0));
		coordinator.noteSkillProductionEvidence("fishing", t.plusSeconds(10));
		Instant w = t.plusSeconds(12);
		onTick(w, xp("AGILITY", 15, w, t), xp("STRENGTH", 15, w, t), xp("FISHING", 140, w, t));
		assertTrue("Fishing resumes; support XP never owns it", is(current(), "fishing"));
	}

	@Test
	public void incidentalFletching_whileWoodcuttingSuspended_needsItsOwnSustainedEvidence()
	{
		Instant t = suspend(establish("WOODCUTTING", T0));
		Instant w = t.plusSeconds(30);
		onTick(w, xp("FLETCHING", 60, w, t));
		assertTrue("one incidental window never switches", is(current(), "woodcutting"));
		Instant back = w.plusSeconds(20);
		onTick(back, xp("WOODCUTTING", 175, back, w));
		assertTrue(is(current(), "woodcutting"));
		assertEquals(SessionState.ACTIVE, current().getState());
	}

	@Test
	public void suspendedCombat_keepsItsOwnHysteresis_evenAgainstSkillOutput()
	{
		// A SUSPENDED combat-branch session (Smart Slayer's own anti-churn territory) is never part of the skill takeover.
		Instant t = T0;
		for (int i = 1; i <= 3; i++)
		{
			t = T0.plusSeconds(10L * i);
			onTick(t, SessionSignal.xpChange(t, "ATTACK", 120, t.minusSeconds(10)));
		}
		assertEquals(ActivityType.COMBAT, current().getActivityIdentity().getActivityType());
		Instant s = suspend(t);
		coordinator.noteSkillProductionEvidence("herblore", s.plusSeconds(5));
		assertNull(coordinator.drainEarlyXpWindowSkill());
		Instant w = s.plusSeconds(6);
		onTick(w, xp("HERBLORE", 75, w, s));
		assertEquals("one window never finalizes the suspended combat session", ActivityType.COMBAT,
			current().getActivityIdentity().getActivityType());
	}

	@Test
	public void sameSkillOutput_neverRequestsAnEarlyWindow()
	{
		Instant t = suspend(establish("HERBLORE", T0));
		coordinator.noteSkillProductionEvidence("herblore", t.plusSeconds(5));
		assertNull("resuming its own session needs no takeover", coordinator.drainEarlyXpWindowSkill());
	}

	@Test
	public void miningFishingAgility_fastTransitionsStayHealthy()
	{
		Instant t = suspend(establish("MINING", T0));
		coordinator.noteSkillProductionEvidence("fishing", t.plusSeconds(5));
		Instant w = t.plusSeconds(6);
		onTick(w, xp("FISHING", 300, w, t));
		assertTrue(is(current(), "fishing"));
		Instant t2 = suspend(establish("FISHING", w.plusSeconds(1)));
		coordinator.noteSkillProductionEvidence("agility", t2.plusSeconds(5));
		Instant w2 = t2.plusSeconds(6);
		onTick(w2, xp("AGILITY", 500, w2, t2));
		assertTrue(is(current(), "agility"));
	}
}
