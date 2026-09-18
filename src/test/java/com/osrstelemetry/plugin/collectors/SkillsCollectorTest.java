package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * NOTE: written, not run — same no-network caveat as elsewhere in this
 * test module (see plugin/README.md). Targets
 * SkillsCollector.evaluateXpWindow(), the pure Client/EventLedger-
 * independent core of the "seed-once-then-diff" XP window decision —
 * factored out for the exact same reason QuestCollector.justFinished()
 * was: unit-testable without mocking RuneLite's Client.
 *
 * This covers the fix for the
 * startup XP_CHANGE flood (see
 * SkillsCollector's own javadoc for the full
 * root-cause trace) — every test below maps to one of the "BASELINE /
 * EVENT TESTS" scenarios for that fix.
 */
public class SkillsCollectorTest
{
	@Test
	public void firstObservationForASkillAlwaysSeedsNeverEmitsADelta()
	{
		// Scenario C/G: a session begins with no baseline for this
		// skill (fresh session, or just reset for an account switch) —
		// the null baseline must always mean "seed silently," never
		// "compare against zero," no matter how large currentXp is.
		SkillsCollector.XpWindowResult result = SkillsCollector.evaluateXpWindow(null, 5_000_000);
		assertTrue("a null baseline must always seed", result.shouldSeed);
	}

	@Test
	public void firstObservationSeedsRegardlessOfMagnitude_neverFabricatesAHistoricalDelta()
	{
		// Scenario I: an account with a LOT of pre-existing XP (the
		// "initial/default/incomplete state followed by real state"
		// case) must not have that real, large XP value misread as a
		// gain the moment it's first observed — the null-baseline path
		// has no notion of "previous" to compare against at all, so it
		// cannot fabricate a delta regardless of the value's size.
		int[] values = {0, 1, 1_000, 273_742, 200_000_000};
		for (int xp : values)
		{
			SkillsCollector.XpWindowResult result = SkillsCollector.evaluateXpWindow(null, xp);
			assertTrue("xp=" + xp + " must still seed, never emit a delta on first observation", result.shouldSeed);
		}
	}

	@Test
	public void secondIdenticalObservationComputesZeroDelta()
	{
		// Scenario D: once seeded, an unchanged read must compute a
		// zero delta (closeXpWindowsFor() only emits when delta > 0,
		// so this is what "emits nothing" reduces to at the pure-logic
		// level).
		SkillsCollector.XpWindowResult result = SkillsCollector.evaluateXpWindow(273_742, 273_742);
		assertFalse("an existing baseline must never re-seed", result.shouldSeed);
		assertEquals(0, result.delta);
	}

	@Test
	public void genuineIncreaseAfterSeedComputesTheCorrectPositiveDelta()
	{
		// Scenario F: a real XP gain after the baseline is established
		// must compute exactly the real delta.
		SkillsCollector.XpWindowResult result = SkillsCollector.evaluateXpWindow(273_742, 300_000);
		assertFalse(result.shouldSeed);
		assertEquals(300_000 - 273_742, result.delta);
	}

	@Test
	public void decreaseAgainstBaselineIsNeverTreatedAsAGain()
	{
		// XP cannot genuinely decrease, but this pins the sign
		// convention explicitly: closeXpWindowsFor() only emits when
		// delta > 0, so a negative delta (which should never actually
		// occur) is still handled safely by the >0 guard at the call
		// site rather than this pure function needing to clamp it.
		SkillsCollector.XpWindowResult result = SkillsCollector.evaluateXpWindow(300_000, 273_742);
		assertFalse(result.shouldSeed);
		assertEquals(273_742 - 300_000, result.delta);
	}

	// --- evaluateLevelTransition() ---
	//
	// Confirmed live-validation bug: LEVEL_UP fired for every skill
	// within milliseconds of login, all with previousLevel=0, because
	// the old comparison baseline (state.getSkills(), seeded by
	// captureInitialState()'s own unsynced login-time read) could hold
	// a wrong 0 the moment the first genuine, correctly-synced
	// StatChanged arrived. evaluateLevelTransition() is the pure core
	// of the fix: a null baseline must ALWAYS mean "seed silently,"
	// regardless of the observed level's value, exactly mirroring
	// evaluateXpWindow()'s contract. These five tests map directly to
	// the requested regression scenarios A-E.

	@Test
	public void scenarioA_firstLevelObservationSilentlySeeds_neverEmitsLevelUp()
	{
		// A. first level observation 0/unset -> 87 silently seeds, NO
		// LEVEL_UP — an existing level 87 must never be reported as a
		// fabricated 0 -> 87 transition just because it's the first
		// time this skill has been observed this session.
		SkillsCollector.LevelTransitionResult result = SkillsCollector.evaluateLevelTransition(null, 87);
		assertFalse("a null baseline must never emit LEVEL_UP", result.shouldEmit);
	}

	@Test
	public void scenarioB_secondIdenticalObservationEmitsNothing()
	{
		// B. second observation 87 -> 87 emits nothing.
		SkillsCollector.LevelTransitionResult result = SkillsCollector.evaluateLevelTransition(87, 87);
		assertFalse(result.shouldEmit);
	}

	@Test
	public void scenarioC_realIncreaseEmitsExactlyOneLevelUp()
	{
		// C. real 87 -> 88 emits exactly one LEVEL_UP (shouldEmit=true;
		// the call site emits at most once per StatChanged, so "exactly
		// one" reduces to "shouldEmit is true here and nowhere else").
		SkillsCollector.LevelTransitionResult result = SkillsCollector.evaluateLevelTransition(87, 88);
		assertTrue(result.shouldEmit);
	}

	@Test
	public void scenarioD_resetProducesANewSilentSeed()
	{
		// D. reset/session change (SkillsCollector.resetForAccountSwitch()
		// clears lastKnownLevel, which is exactly the null-baseline
		// input this pure function receives afterward) causes a new
		// silent seed, not a diff against the old account's level —
		// same null-in/no-emit behavior as scenario A, applied after a
		// reset instead of a fresh session.
		SkillsCollector.LevelTransitionResult result = SkillsCollector.evaluateLevelTransition(null, 42);
		assertFalse("post-reset baseline is null, so this must reseed silently, not diff", result.shouldEmit);
	}

	@Test
	public void scenarioE_accountABaselineCannotLeakIntoAccountB()
	{
		// E. account A's level baseline cannot leak into account B.
		// resetForAccountSwitch() clears lastKnownLevel per-skill (see
		// SkillsCollector), so by the time account B is observed, the
		// call site always passes null here regardless of what account
		// A's level was — pinned directly: a high account-A baseline
		// must never suppress or distort account B's own first
		// observation.
		SkillsCollector.LevelTransitionResult accountALeftover = SkillsCollector.evaluateLevelTransition(99, 99);
		assertFalse(accountALeftover.shouldEmit);

		SkillsCollector.LevelTransitionResult accountBFirstObservation = SkillsCollector.evaluateLevelTransition(null, 3);
		assertFalse("account B's first observation must seed silently regardless of account A's leftover level",
			accountBFirstObservation.shouldEmit);
	}

	@Test
	public void decreaseInLevelIsNeverTreatedAsALevelUp()
	{
		// A real level cannot genuinely decrease outside an account
		// switch (which resets the baseline to null first, covered by
		// scenario E), but this pins the strict-greater-than convention
		// explicitly, mirroring decreaseAgainstBaselineIsNeverTreatedAsAGain()
		// above.
		SkillsCollector.LevelTransitionResult result = SkillsCollector.evaluateLevelTransition(88, 87);
		assertFalse(result.shouldEmit);
	}

	// --- evaluateRawCombatXpPulse() -- universal generic NPC
	// recognition, temporal-design follow-up fix ---
	//
	// The pure core of the IMMEDIATE (per-StatChanged, not aggregated)
	// raw-combat-XP-pulse decision that backs EventType.RAW_COMBAT_XP_OBSERVED
	// -- see that constant's own javadoc for why this exists (closing
	// the gap between a fresh NPC interaction and XP_CHANGE's slow,
	// ~30s-aggregated flush cadence). Mirrors evaluateLevelTransition()'s
	// exact seed-once-then-diff contract, on its own SEPARATE baseline
	// map (lastKnownXpForRawPulse) so this new code path can never alter
	// XP_CHANGE's own accounting (windowBaselineXp is untouched by any
	// of this).

	@Test
	public void firstRawObservationForASkillAlwaysSeedsNeverEmitsAPulse()
	{
		// A session begins with no raw-pulse baseline for this skill
		// (fresh session, account switch, or a Skills re-enable) -- the
		// null baseline must always mean "seed silently," never "compare
		// against zero," no matter how large currentXp is (an existing
		// account's real XP total must never be misread as an
		// instantaneous gain the moment it is first observed).
		SkillsCollector.RawCombatXpPulseResult result = SkillsCollector.evaluateRawCombatXpPulse(null, 5_000_000);
		assertFalse("a null baseline must always seed, never emit", result.shouldEmit);
	}

	@Test
	public void firstRawObservationSeedsRegardlessOfMagnitude()
	{
		int[] values = {0, 1, 1_000, 273_742, 200_000_000};
		for (int xp : values)
		{
			SkillsCollector.RawCombatXpPulseResult result = SkillsCollector.evaluateRawCombatXpPulse(null, xp);
			assertFalse("xp=" + xp + " must still seed, never emit on first observation", result.shouldEmit);
		}
	}

	@Test
	public void secondIdenticalRawObservationEmitsNoPulse()
	{
		SkillsCollector.RawCombatXpPulseResult result = SkillsCollector.evaluateRawCombatXpPulse(273_742, 273_742);
		assertFalse(result.shouldEmit);
	}

	@Test
	public void genuineIncreaseAfterSeedEmitsExactlyOnePulse()
	{
		SkillsCollector.RawCombatXpPulseResult result = SkillsCollector.evaluateRawCombatXpPulse(273_742, 273_780);
		assertTrue(result.shouldEmit);
	}

	@Test
	public void decreaseAgainstRawBaselineIsNeverTreatedAsAPulse()
	{
		// XP cannot genuinely decrease, but pins the sign convention
		// explicitly, mirroring decreaseAgainstBaselineIsNeverTreatedAsAGain()
		// above.
		SkillsCollector.RawCombatXpPulseResult result = SkillsCollector.evaluateRawCombatXpPulse(300_000, 273_742);
		assertFalse(result.shouldEmit);
	}

	@Test
	public void resetProducesANewSilentRawSeed()
	{
		// resetForAccountSwitch()/reseedTransitionBaselinesSilently()
		// both clear lastKnownXpForRawPulse, which is exactly the
		// null-baseline input this pure function receives afterward --
		// a new silent seed, never a diff against a stale account's XP.
		SkillsCollector.RawCombatXpPulseResult result = SkillsCollector.evaluateRawCombatXpPulse(null, 42);
		assertFalse("post-reset baseline is null, so this must reseed silently, not diff", result.shouldEmit);
	}

	@Test
	public void accountARawBaselineCannotLeakIntoAccountB()
	{
		SkillsCollector.RawCombatXpPulseResult accountALeftover = SkillsCollector.evaluateRawCombatXpPulse(99_999, 99_999);
		assertFalse(accountALeftover.shouldEmit);

		SkillsCollector.RawCombatXpPulseResult accountBFirstObservation = SkillsCollector.evaluateRawCombatXpPulse(null, 3);
		assertFalse("account B's first observation must seed silently regardless of account A's leftover baseline",
			accountBFirstObservation.shouldEmit);
	}

	// ------------------------------------------------------------------
	// evaluateLiveXpDelta() -- immediate
	// Current Session XP display. Same seed-once-then-diff, positive-
	// only discipline as evaluateRawCombatXpPulse() above, but
	// deliberately generic across every skill (not just
	// RAW_COMBAT_SKILLS) -- these tests deliberately use both combat
	// (Strength) and non-combat (Woodcutting) skill names to pin that
	// genericity, even though the pure function itself takes no skill
	// name at all (SkillsCollector.onStatChanged() is what makes it
	// generic, by calling this for every skill unconditionally).
	// ------------------------------------------------------------------

	@Test
	public void firstLiveObservationForASkillAlwaysSeedsReturnsZero()
	{
		// Test 1/9 (required list): a null baseline (fresh session,
		// account switch) must always mean "seed silently," never
		// "compare against zero" -- an existing large XP total must
		// never be misread as an instantaneous live gain the moment it
		// is first observed.
		assertEquals(0L, SkillsCollector.evaluateLiveXpDelta(null, 5_000_000));
	}

	@Test
	public void firstLiveObservationSeedsRegardlessOfMagnitude()
	{
		int[] values = {0, 1, 1_000, 273_742, 200_000_000};
		for (int xp : values)
		{
			assertEquals("xp=" + xp + " must still seed (return 0), never emit a delta on first observation",
				0L, SkillsCollector.evaluateLiveXpDelta(null, xp));
		}
	}

	@Test
	public void noChangeInLiveXpProducesZeroDelta()
	{
		assertEquals(0L, SkillsCollector.evaluateLiveXpDelta(273_742, 273_742));
	}

	@Test
	public void genuineIncreaseAfterSeedProducesExactPositiveDelta()
	{
		// Test 2/5 (required list): works for combat XP (Strength is a
		// stand-in here -- the function itself is skill-name-agnostic).
		assertEquals(38L, SkillsCollector.evaluateLiveXpDelta(273_742, 273_780));
	}

	@Test
	public void genuineIncreaseForANonCombatSkillProducesExactPositiveDeltaToo()
	{
		// Test 4 (required list): Woodcutting/Agility must work exactly
		// like a combat skill -- evaluateLiveXpDelta() takes no skill
		// name at all, proving there is no combat-only special-casing.
		assertEquals(120L, SkillsCollector.evaluateLiveXpDelta(4_500, 4_620));
	}

	@Test
	public void multipleSuccessiveIncreasesEachProduceTheirOwnCorrectDelta()
	{
		// Test 2 (required list): "multiple raw XP gains accumulate
		// correctly" -- accumulation itself is LiveXpTracker's own job
		// (see LiveXpTrackerTest), this pins that each individual
		// SkillsCollector-level reading against its own immediately-
		// prior baseline produces the correct incremental delta.
		assertEquals(50L, SkillsCollector.evaluateLiveXpDelta(1_000, 1_050));
		assertEquals(25L, SkillsCollector.evaluateLiveXpDelta(1_050, 1_075));
	}

	@Test
	public void decreaseAgainstLiveBaselineNeverProducesAPositiveDelta()
	{
		// XP decrease/reset/reseed must never fabricate positive live XP.
		assertEquals(0L, SkillsCollector.evaluateLiveXpDelta(300_000, 273_742));
	}

	@Test
	public void resetProducesANewSilentLiveSeed()
	{
		// Test 9 (required list): resetForAccountSwitch() clears
		// lastKnownXpForLiveDelta, which is exactly the null-baseline
		// input this pure function receives afterward -- a new silent
		// seed, never a diff against a stale account's XP, so no fake
		// live XP is ever produced across an account switch.
		assertEquals("post-reset baseline is null, so this must reseed silently (return 0), not diff",
			0L, SkillsCollector.evaluateLiveXpDelta(null, 42));
	}

	@Test
	public void accountALiveBaselineCannotLeakIntoAccountB()
	{
		assertEquals(0L, SkillsCollector.evaluateLiveXpDelta(99_999, 99_999));
		assertEquals("account B's first observation must seed silently (return 0) regardless of account A's leftover baseline",
			0L, SkillsCollector.evaluateLiveXpDelta(null, 3));
	}
}
