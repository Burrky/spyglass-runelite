package com.osrstelemetry.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * NOTE: written, not run — no-network caveat as elsewhere (see
 * plugin/README.md / other test files' identical notes).
 *
 * Targets AccountContext directly — this is the exact decision logic
 * that gates OsrsTelemetryPlugin.handleLogin()'s account-switch reset
 * dispatch block (skillsCollector/questCollector/containerCollector/
 * activityKillCountCollector/serverNpcLootCollector/knownBossRegistry/
 * bossActivityContextCollector.resetForAccountSwitch(...) plus
 * eventLedger.startNewSession()): that whole block only runs when
 * accountContext.isRealSwitch(previousAccountHash, newAccountHash) is
 * true, and previousAccountHash always comes from
 * accountContext.update(newAccountHash) — so this class alone decides
 * "first login vs. real switch vs. same-account relog/world hop" for
 * every collector on that list.
 *
 * handleLogin() itself is deliberately NOT targeted here: it also
 * touches client.getAccountHash(), sessionRuntimeCoordinator, and ~10
 * concrete collector fields, all @Inject-wired to real RuneLite types
 * (Client, ClientThread, EventBus) with no interfaces to substitute —
 * this project has no Mockito dependency and never mocks RuneLite's
 * Client anywhere (see e.g. QuestCollectorTest's/
 * OsrsTelemetryPluginLifecycleTest's own identical notes), so
 * constructing a real OsrsTelemetryPlugin here would require either a
 * live RuneLite client or invasive production refactoring (extracting
 * an interface layer over every collector) neither of which is in
 * scope here. This test covers exactly what IS safely
 * testable without them: AccountContext's own pure switch-detection
 * decision, in isolation.
 */
public class AccountContextTest
{
	private static final long ACCOUNT_A = 111L;
	private static final long ACCOUNT_B = 222L;

	@Test
	public void firstObservationIsNotARealSwitch()
	{
		// Property A: first account initialization must not trigger the
		// destructive reset-dispatch block in handleLogin() — update()'s
		// sentinel "no account observed yet" (-1) previous value must
		// make isRealSwitch() return false.
		AccountContext context = new AccountContext();

		long previous = context.update(ACCOUNT_A);

		assertFalse(
			"the very first accountHash observed must be treated as a seed, not a switch",
			context.isRealSwitch(previous, ACCOUNT_A)
		);
		assertEquals(ACCOUNT_A, context.getCurrentAccountHash());
	}

	@Test
	public void switchingToADifferentAccountIsARealSwitch()
	{
		// Property B: account A -> account B must be detected as a real
		// switch, so handleLogin() invokes every collector's
		// resetForAccountSwitch(...) exactly once for this transition.
		AccountContext context = new AccountContext();
		long firstPrevious = context.update(ACCOUNT_A);
		context.isRealSwitch(firstPrevious, ACCOUNT_A); // seed, as above

		long secondPrevious = context.update(ACCOUNT_B);

		assertTrue(
			"a genuinely different accountHash must be treated as a real switch",
			context.isRealSwitch(secondPrevious, ACCOUNT_B)
		);
		assertEquals(ACCOUNT_A, secondPrevious);
		assertEquals(ACCOUNT_B, context.getCurrentAccountHash());
	}

	@Test
	public void sameAccountReloginIsNotARealSwitch()
	{
		// Property C: a same-account relog/world hop (accountHash
		// unchanged) must NOT be treated as a real switch, so the
		// per-account collector state above is never falsely cleared —
		// only slayerCollector/tick-counter resets run unconditionally
		// in handleLogin(), by design (see its own comments), and that
		// unconditional-vs-real-switch-gated split is exactly what this
		// method decides.
		AccountContext context = new AccountContext();
		long firstPrevious = context.update(ACCOUNT_A);
		context.isRealSwitch(firstPrevious, ACCOUNT_A); // seed

		long secondPrevious = context.update(ACCOUNT_A);

		assertFalse(
			"re-observing the same accountHash (relog/world hop) must not be a real switch",
			context.isRealSwitch(secondPrevious, ACCOUNT_A)
		);
		assertEquals(ACCOUNT_A, secondPrevious);
	}

	@Test
	public void switchAwayThenBackToAnEarlierAccountIsStillARealSwitch()
	{
		// A -> B -> A: the second transition back to A must be detected
		// as a real switch too — B's leftovers must never be diffed
		// against, or leak into, A's telemetry.
		AccountContext context = new AccountContext();
		context.update(ACCOUNT_A);
		long previousAtB = context.update(ACCOUNT_B);
		context.isRealSwitch(previousAtB, ACCOUNT_B);

		long previousBackAtA = context.update(ACCOUNT_A);

		assertTrue(
			"switching back to a previously-seen account is still a real switch",
			context.isRealSwitch(previousBackAtA, ACCOUNT_A)
		);
		assertEquals(ACCOUNT_B, previousBackAtA);
	}

	@Test
	public void getCurrentAccountHashReflectsMostRecentUpdate()
	{
		AccountContext context = new AccountContext();
		context.update(ACCOUNT_A);
		assertEquals(ACCOUNT_A, context.getCurrentAccountHash());

		context.update(ACCOUNT_B);
		assertEquals(ACCOUNT_B, context.getCurrentAccountHash());
	}
}
