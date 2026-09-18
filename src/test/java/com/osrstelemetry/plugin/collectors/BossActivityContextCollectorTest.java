package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * NOTE: written, not run — same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project. Josh's own
 * `.\gradlew.bat clean test` run is what actually executes these.
 *
 * Targets
 * BossActivityContextCollector.decide() — the pure, RuneLite-free core
 * of the collector's onInteractingChanged() handler. A plain HashMap
 * stands in for the production ConcurrentHashMap; KnownBossRegistry
 * itself is RuneLite-API-free (see its own class javadoc), so no
 * mocking is needed anywhere in this file, same as every other test
 * in this project (no Mockito dependency).
 */
public class BossActivityContextCollectorTest
{
	private static final long ACCOUNT_A = 555L;

	@Test
	public void nonBossNpc_noEmission()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Zulrah");
		Map<Long, String> tracked = new HashMap<>();

		String result = BossActivityContextCollector.decide(true, "Chicken", registry, ACCOUNT_A, tracked);

		assertNull(result);
	}

	@Test
	public void confirmedBoss_emitsWithCorrectCanonicalName()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Zulrah");
		Map<Long, String> tracked = new HashMap<>();

		String result = BossActivityContextCollector.decide(true, "Zulrah", registry, ACCOUNT_A, tracked);

		assertEquals("Zulrah", result);
		assertEquals("Zulrah", tracked.get(ACCOUNT_A));
	}

	@Test
	public void reInteractingSameAlreadyCurrentBoss_isDeduped()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Zulrah");
		Map<Long, String> tracked = new HashMap<>();

		String first = BossActivityContextCollector.decide(true, "Zulrah", registry, ACCOUNT_A, tracked);
		// Simulates a phase transition / sub-target flicker mid-fight:
		// several InteractingChanged deliveries for the SAME resolved
		// boss in a row.
		String second = BossActivityContextCollector.decide(true, "Zulrah", registry, ACCOUNT_A, tracked);
		String third = BossActivityContextCollector.decide(true, "Zulrah", registry, ACCOUNT_A, tracked);

		assertEquals("Zulrah", first);
		assertNull(second);
		assertNull(third);
	}

	@Test
	public void sourceNotLocalPlayer_isIgnored()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Zulrah");
		Map<Long, String> tracked = new HashMap<>();

		String result = BossActivityContextCollector.decide(false, "Zulrah", registry, ACCOUNT_A, tracked);

		assertNull(result);
		assertNull(tracked.get(ACCOUNT_A));
	}

	@Test
	public void targetNotNpc_isIgnored()
	{
		// The production caller passes npcName == null whenever
		// event.getTarget() is not an NPC instance at all.
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Zulrah");
		Map<Long, String> tracked = new HashMap<>();

		String result = BossActivityContextCollector.decide(true, null, registry, ACCOUNT_A, tracked);

		assertNull(result);
	}

	@Test
	public void breakingInteractionThenReEngagingSameBoss_emitsAgain()
	{
		// Not spam — a genuine disengage/re-engage. Matters for
		// resuming a SUSPENDED BOSSING session: fresh specific evidence
		// is what lets ActivitySignalClassifier.decideCombatBranch()'s
		// exact-identity-match fast path resume it.
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Zulrah");
		Map<Long, String> tracked = new HashMap<>();

		String first = BossActivityContextCollector.decide(true, "Zulrah", registry, ACCOUNT_A, tracked);
		// Interaction ends (target becomes null / non-NPC).
		String duringBreak = BossActivityContextCollector.decide(true, null, registry, ACCOUNT_A, tracked);
		// Player re-engages the SAME boss.
		String reEngaged = BossActivityContextCollector.decide(true, "Zulrah", registry, ACCOUNT_A, tracked);

		assertEquals("Zulrah", first);
		assertNull(duringBreak);
		assertEquals("Zulrah", reEngaged);
	}

	@Test
	public void switchingToADifferentConfirmedBoss_emitsTheNewOne()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Zulrah");
		registry.recordConfirmedBoss(ACCOUNT_A, "Vorkath");
		Map<Long, String> tracked = new HashMap<>();

		String first = BossActivityContextCollector.decide(true, "Zulrah", registry, ACCOUNT_A, tracked);
		String second = BossActivityContextCollector.decide(true, "Vorkath", registry, ACCOUNT_A, tracked);

		assertEquals("Zulrah", first);
		assertEquals("Vorkath", second);
	}

	@Test
	public void dawnThenDusk_bothResolveToGrotesqueGuardians_onlyFirstEmits()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		registry.recordConfirmedBoss(ACCOUNT_A, "Grotesque Guardians");
		Map<Long, String> tracked = new HashMap<>();

		String onDawn = BossActivityContextCollector.decide(true, "Dawn", registry, ACCOUNT_A, tracked);
		String onDusk = BossActivityContextCollector.decide(true, "Dusk", registry, ACCOUNT_A, tracked);

		assertEquals("Grotesque Guardians", onDawn);
		// Dusk resolves to the SAME canonical name already tracked, so
		// it dedupes rather than re-emitting.
		assertNull(onDusk);
	}

	// ----------------------------------------------------------------
	// REQUIRED REGRESSION COVERAGE (circular
	// first-boss recognition fix): full decide() pipeline, starting
	// from a freshly-constructed KnownBossRegistry with NO prior
	// recordConfirmedBoss() call and no kill-count message simulated
	// anywhere in the current runtime.
	// ----------------------------------------------------------------

	@Test
	public void freshStart_firstEverZulrahEncounter_emitsWithNoPriorKillCount()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		Map<Long, String> tracked = new HashMap<>();

		String result = BossActivityContextCollector.decide(true, "Zulrah", registry, ACCOUNT_A, tracked);

		assertEquals("Zulrah", result);
		assertEquals("Zulrah", tracked.get(ACCOUNT_A));
	}

	@Test
	public void freshStart_firstEverGrotesqueGuardiansEncounter_emitsViaDawnWithNoPriorKillCount()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		Map<Long, String> tracked = new HashMap<>();

		String result = BossActivityContextCollector.decide(true, "Dawn", registry, ACCOUNT_A, tracked);

		assertEquals("Grotesque Guardians", result);
	}

	@Test
	public void freshStart_firstEverGrotesqueGuardiansEncounter_emitsViaDuskWithNoPriorKillCount()
	{
		KnownBossRegistry registry = new KnownBossRegistry();
		Map<Long, String> tracked = new HashMap<>();

		String result = BossActivityContextCollector.decide(true, "Dusk", registry, ACCOUNT_A, tracked);

		assertEquals("Grotesque Guardians", result);
	}
}
