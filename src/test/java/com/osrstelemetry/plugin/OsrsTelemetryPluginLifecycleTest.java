package com.osrstelemetry.plugin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * NOTE: written, not run — no-network caveat as elsewhere (see
 * plugin/README.md / other test files' identical notes).
 *
 * Targets OsrsTelemetryPlugin.StartupHydrationGate directly — the
 * Client/ClientThread-independent piece extracted from the
 * client-thread lifecycle audit (see startUp()/onGameStateChanged()).
 * This project has no Mockito dependency and never mocks RuneLite's
 * Client or ClientThread anywhere (see e.g. QuestCollectorTest's own
 * note) — both are real RuneLite classes whose exact internal
 * threading behavior can't be faithfully faked here, so this test
 * covers exactly what IS safely testable without them: the pure
 * duplicate-initialization guard requirements 2 and 5 asked for.
 *
 * What this does NOT cover (verified instead by source inspection):
 * that startUp()
 * actually calls clientThread.invokeLater() rather than calling
 * handleLogin() directly, and that onConfigChanged()'s entire switch
 * runs inside clientThread.invokeLater(). Those are structural
 * properties of the production code, not decisions this gate makes.
 */
public class OsrsTelemetryPluginLifecycleTest
{
	@Test
	public void consumeWithoutArmingReturnsFalse()
	{
		OsrsTelemetryPlugin.StartupHydrationGate gate = new OsrsTelemetryPlugin.StartupHydrationGate();
		assertFalse("nothing was armed; the deferred callback must not run handleLogin()", gate.consumeIfPending());
	}

	@Test
	public void armThenConsumeReturnsTrueExactlyOnce()
	{
		OsrsTelemetryPlugin.StartupHydrationGate gate = new OsrsTelemetryPlugin.StartupHydrationGate();
		gate.arm();
		assertTrue("first consume after arm() must run the deferred hydration", gate.consumeIfPending());
		assertFalse("a second consume for the same arm() must not run hydration again", gate.consumeIfPending());
	}

	@Test
	public void realLoginSupersedesStillPendingDeferredHydration()
	{
		// Models: startUp() runs on AWT while already logged in and
		// arms the gate, but a genuine GameStateChanged(LOGGED_IN)
		// (e.g. a fast relog) reaches onGameStateChanged() and calls
		// supersede() BEFORE the deferred ClientThread callback from
		// startUp() gets a chance to run.
		OsrsTelemetryPlugin.StartupHydrationGate gate = new OsrsTelemetryPlugin.StartupHydrationGate();
		gate.arm();
		gate.supersede();

		assertFalse(
			"a real login event must make the deferred startup hydration a no-op, "
				+ "since onGameStateChanged()'s own handleLogin() call already covers it",
			gate.consumeIfPending()
		);
	}

	@Test
	public void supersedeBeforeArmIsSafeAndDoesNotArmAnything()
	{
		OsrsTelemetryPlugin.StartupHydrationGate gate = new OsrsTelemetryPlugin.StartupHydrationGate();
		gate.supersede();
		assertFalse(gate.consumeIfPending());
	}

	@Test
	public void arm_consume_armAgain_consumeAgain_eachEnableGetsExactlyOneHydration()
	{
		// Models plugin disable -> re-enable -> disable -> re-enable:
		// each enable that reaches startUp() while already logged in
		// must independently produce exactly one hydration.
		OsrsTelemetryPlugin.StartupHydrationGate gate = new OsrsTelemetryPlugin.StartupHydrationGate();

		gate.arm();
		assertTrue(gate.consumeIfPending());
		assertFalse(gate.consumeIfPending());

		gate.arm();
		assertTrue(gate.consumeIfPending());
		assertFalse(gate.consumeIfPending());
	}
}
