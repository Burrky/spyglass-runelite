package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * NOTE: written, not run — same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project. Josh's own
 * `.\gradlew.bat clean test` run is what actually executes these.
 *
 * Targets
 * KnownBossRegistry directly — it is entirely RuneLite-API-free, so
 * every scenario below is a real, driveable unit test, not an
 * architectural note.
 *
 * This file previously contained a test named
 * "aliasTable_aloneIsNotConfirmation" asserting that a FRESH registry
 * (no prior recordConfirmedBoss() call) does NOT resolve "Dawn"/"Dusk"
 * to "Grotesque Guardians" — i.e. it encoded a circular defect
 * (a boss could never be recognized before its own
 * first kill-count message). That test has been corrected below to
 * reflect the fixed behavior: Grotesque Guardians is in the static
 * seed, so Dawn/Dusk now resolve on a fresh registry with zero prior
 * confirmation. See freshRegistry_* tests below for the required
 * regression coverage.
 *
 * Several tests below
 * previously used Zulrah and/or Vorkath — both statically seeded,
 * global, account-independent names — to try to exercise PER-ACCOUNT
 * confirmed-state behavior (isolation, reset). Because the static seed
 * resolves those names for every account regardless of any
 * recordConfirmedBoss()/resetForAccountSwitch() call, those tests
 * rested on an invalid premise and did not actually prove per-account
 * behavior. They have been rewritten below to use deliberately
 * unseeded synthetic boss names ("Test Boss A" / "Test Boss B") so the
 * per-account confirmed-state mechanism is what is actually under
 * test. The static-seed-specific tests (freshRegistry_* below) still
 * correctly use Zulrah and Grotesque Guardians/Dawn/Dusk, since those
 * are intentionally testing seeded/fresh-start recognition.
 */
public class KnownBossRegistryTest
{
	private static final long ACCOUNT_A = 111L;
	private static final long ACCOUNT_B = 222L;

	@Test
	public void unrecognizedNpcName_doesNotResolve()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		assertNull(registry.resolveCandidateBossName(ACCOUNT_A, "Chicken"));
	}

	@Test
	public void confirmedBossName_resolvesDirectly()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Zulrah");

		assertEquals("Zulrah", registry.resolveCandidateBossName(ACCOUNT_A, "Zulrah"));
	}

	@Test
	public void confirmedBossName_resolutionIsCaseAndWhitespaceInsensitive()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Zulrah");

		// The NPC's own name as read off the NPC object may not match
		// the chat message's exact casing/whitespace — matching must
		// still succeed, while the RETURNED display name is always the
		// one originally confirmed via the kill-count message (so it
		// matches BOSS_KILL's own displayName exactly).
		assertEquals("Zulrah", registry.resolveCandidateBossName(ACCOUNT_A, "  zulrah  "));
	}

	@Test
	public void aliasTable_resolvesDawnAndDuskToGrotesqueGuardians_onceConfirmed()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Grotesque Guardians");

		assertEquals("Grotesque Guardians", registry.resolveCandidateBossName(ACCOUNT_A, "Dawn"));
		assertEquals("Grotesque Guardians", registry.resolveCandidateBossName(ACCOUNT_A, "Dusk"));
	}

	@Test
	public void unseededUnconfirmedDirectNameDoesNotResolve()
	{
		// RENAMED/REFRAMED (was "aliasTable_aloneIsNotConfirmation_
		// forABossNotInTheStaticSeed"): that test directly looked up
		// "Vorkath", which IS in the static seed, so it exercised
		// nothing about aliases or non-seeded names and its name was
		// misleading. This test uses a guaranteed-unseeded synthetic
		// name, looked up directly (no alias involved), with zero
		// confirmation, and asserts it does not resolve. Real alias
		// behavior (Dawn/Dusk) is already covered by the
		// aliasTable_resolvesDawnAndDuskToGrotesqueGuardians_onceConfirmed
		// and freshRegistry_dawnAndDusk_* tests.
		KnownBossRegistry registry = new KnownBossRegistry();

		assertNull(registry.resolveCandidateBossName(ACCOUNT_A, "Test Boss A"));
	}

	// ----------------------------------------------------------------
	// REQUIRED REGRESSION COVERAGE (circular
	// first-boss recognition fix): a freshly-constructed registry, with
	// NO prior recordConfirmedBoss() call and no kill-count message
	// simulated anywhere, must still resolve well-known bosses via the
	// static seed alone.
	// ----------------------------------------------------------------

	@Test
	public void freshRegistry_zulrah_resolvesViaStaticSeed_withNoPriorConfirmation()
	{
		KnownBossRegistry registry = new KnownBossRegistry();

		assertEquals("Zulrah", registry.resolveCandidateBossName(ACCOUNT_A, "Zulrah"));
	}

	@Test
	public void freshRegistry_dawnAndDusk_resolveViaStaticSeed_withNoPriorConfirmation()
	{
		KnownBossRegistry registry = new KnownBossRegistry();

		assertEquals("Grotesque Guardians", registry.resolveCandidateBossName(ACCOUNT_A, "Dawn"));
		assertEquals("Grotesque Guardians", registry.resolveCandidateBossName(ACCOUNT_A, "Dusk"));
	}

	@Test
	public void freshRegistry_unseededUnconfirmedBoss_stillDoesNotResolve()
	{
		// The static seed does not make every boss resolvable — only
		// the curated seed list plus anything actually confirmed. An
		// unseeded, unconfirmed boss name must still return null.
		KnownBossRegistry registry = new KnownBossRegistry();

		assertNull(registry.resolveCandidateBossName(ACCOUNT_A, "Some Unlisted Boss"));
	}

	@Test
	public void confirmingANonSeededBoss_makesItResolvableGoingForward()
	{
		// Runtime-observed BOSS_KILL messages augment the static seed
		// (second-and-later fights of an unseeded boss get pre-kill
		// recognition too) without ever being a prerequisite for bosses
		// already in the seed.
		KnownBossRegistry registry = new KnownBossRegistry();
		assertNull(registry.resolveCandidateBossName(ACCOUNT_A, "Obor"));

		registry.recordConfirmedBoss(ACCOUNT_A, "Obor");

		assertEquals("Obor", registry.resolveCandidateBossName(ACCOUNT_A, "Obor"));
	}

	@Test
	public void perAccountIsolation_confirmingForOneAccountDoesNotLeakToAnother()
	{
		// FIXED: previously used "Zulrah" (statically seeded), so
		// account B would resolve it regardless of per-account
		// confirmation — the test did not actually prove isolation.
		// Uses a deliberately unseeded synthetic name instead.
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Test Boss A");

		assertEquals("Test Boss A", registry.resolveCandidateBossName(ACCOUNT_A, "Test Boss A"));
		assertNull(registry.resolveCandidateBossName(ACCOUNT_B, "Test Boss A"));
	}

	@Test
	public void resetForAccountSwitch_clearsThatAccountsConfirmedState()
	{
		// FIXED: previously used "Zulrah" (statically seeded), which
		// survives reset by design and so never actually proved
		// confirmed-state clearing. Uses a deliberately unseeded
		// synthetic name instead.
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Test Boss A");
		assertEquals("Test Boss A", registry.resolveCandidateBossName(ACCOUNT_A, "Test Boss A"));

		registry.resetForAccountSwitch(ACCOUNT_A);

		assertNull(registry.resolveCandidateBossName(ACCOUNT_A, "Test Boss A"));
	}

	@Test
	public void resetForAccountSwitch_doesNotAffectOtherAccounts()
	{
		// FIXED: previously used "Zulrah" and "Vorkath" (both
		// statically seeded), so neither assertion actually exercised
		// per-account confirmed-state semantics. Uses two deliberately
		// unseeded synthetic names instead.
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Test Boss A");
		registry.recordConfirmedBoss(ACCOUNT_B, "Test Boss B");

		registry.resetForAccountSwitch(ACCOUNT_A);

		assertNull(registry.resolveCandidateBossName(ACCOUNT_A, "Test Boss A"));
		assertEquals("Test Boss B", registry.resolveCandidateBossName(ACCOUNT_B, "Test Boss B"));
	}

	@Test
	public void blankOrNullDisplayName_isNeverRecorded()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "   ");
		registry.recordConfirmedBoss(ACCOUNT_A, null);

		assertNull(registry.resolveCandidateBossName(ACCOUNT_A, ""));
	}
}
