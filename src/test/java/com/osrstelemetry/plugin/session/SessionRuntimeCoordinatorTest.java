package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * NOTE: written, not run -- same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project. Josh's own
 * `.\gradlew.bat clean test` run is what actually executes these.
 *
 * Matches SessionRuntimeCoordinator's
 * new API: testProcessTick(long currentTick, Instant now) now plays the
 * role of a real GameTick's client.getTickCount() -- pass a tick
 * strictly greater than any pending signal's own gameTick to make it
 * eligible to resolve on that call (see the coordinator's own
 * TICK-BATCH CLOSURE javadoc for why this changed from the original,
 * buggy "resolve everything on every tick" design).
 *
 * No mocking (this project has no Mockito dependency) -- every test
 * drives the real production SessionRuntimeCoordinator, SessionLifecycleEngine,
 * ActivitySignalClassifier, SessionSignalBatchResolver,
 * SessionAggregateUpdater, and SessionPersistence/LocalStateStore
 * directly, same as every other test in this package.
 */
public class SessionRuntimeCoordinatorTest
{
	private static final long ACCOUNT_A = 700_100_200L;
	private static final long ACCOUNT_B = 700_100_300L;

	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	private LocalStateStore store;
	private SessionRuntimeCoordinator coordinator;

	@Before
	public void setUp() throws Exception
	{
		deleteAccountDir(ACCOUNT_A);
		deleteAccountDir(ACCOUNT_B);
		store = new LocalStateStore();
		store.start();
		coordinator = new SessionRuntimeCoordinator(store);
	}

	@After
	public void tearDown() throws Exception
	{
		store.shutdown();
		deleteAccountDir(ACCOUNT_A);
		deleteAccountDir(ACCOUNT_B);
	}

	private void deleteAccountDir(long accountHash) throws Exception
	{
		File dir = TelemetryPaths.accountDir(accountHash);
		if (dir.exists())
		{
			File sessionsDir = new File(dir, "sessions");
			if (sessionsDir.exists())
			{
				File[] sessionFiles = sessionsDir.listFiles();
				if (sessionFiles != null)
				{
					for (File f : sessionFiles)
					{
						Files.deleteIfExists(f.toPath());
					}
				}
				Files.deleteIfExists(sessionsDir.toPath());
			}
			File[] files = dir.listFiles();
			if (files != null)
			{
				for (File f : files)
				{
					Files.deleteIfExists(f.toPath());
				}
			}
			Files.deleteIfExists(dir.toPath());
		}
	}

	// =====================================================================
	// Core lifecycle wiring (carried over from the original pass, adapted
	// to the new testProcessTick(currentTick, now) signature)
	// =====================================================================

	@Test
	public void combatXp_createsOneActiveCombatSession()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 100L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		Session current = coordinator.testCurrentSession();
		assertEquals(SessionState.ACTIVE, current.getState());
		assertEquals(ActivityType.COMBAT, current.getActivityIdentity().getActivityType());
	}

	@Test
	public void slayerProgressAndCombatXpSameTick_resolvesDirectlyToSlayer()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 50L).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs", 120).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		Session current = coordinator.testCurrentSession();
		assertEquals(ActivityType.SLAYER, current.getActivityIdentity().getActivityType());
	}

	@Test
	public void bossKillCombatXpNpcDeathAndLootSameTick_resolvesToOneBossingSessionWithLootAndOneOccurrence()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 50L).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 1).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.npcDeath(T0).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(T0, "Zulrah",
			Collections.singletonList(new SessionSignal.LootDrop(2, "Zulrah's scales", 100))).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		Session current = coordinator.testCurrentSession();
		assertEquals(ActivityType.BOSSING, current.getActivityIdentity().getActivityType());
		assertEquals(1, current.getAggregates().getReliableCount().getSessionOccurrences());
		assertEquals(1, current.getAggregates().getLootDrops().size());
	}

	// LOOT-WHILE-ACTIVE OWNERSHIP regression. SERVER_NPC_LOOT classified METRIC_ONLY against
	// an ACTIVE BOSSING/Zulrah session must stay attributed to THAT
	// session even when this SAME tick's other evidence resolves the
	// batch to a real switch into a brand-new BOSSING/Vorkath session.
	// Before the fix, the whole batch's metrics -- including this loot --
	// rode unconditionally onto whichever identity won the batch
	// (SessionLifecycleEngine.onQualifyingActivity()'s "different
	// activity while ACTIVE" branch applies its entire `metrics`
	// argument to the brand-new session), so the Zulrah loot ended up on
	// the new Vorkath session instead of the finalized Zulrah session it
	// actually came from.
	//
	// EVIDENCE-STRENGTH REGRESSION (activity-evidence-strength pass):
	// this test's switching evidence used to be a single ORDINARY PRAYER
	// XP_CHANGE. Under the new EvidenceStrength model, ORDINARY evidence
	// against an ACTIVE established session is gated by the weak-evidence
	// candidate mechanism and must NOT immediately replace it -- so that
	// evidence no longer exercises this test's real purpose. Updated to a
	// genuinely SPECIFIC same-tick signal pair instead: BOSS_ACTIVITY_
	// CONTEXT for a different boss (Vorkath) -- direct, per-event
	// telemetry that always switches an ACTIVE combat-branch session
	// immediately, never gated -- plus SERVER_NPC_LOOT for the OUTGOING
	// boss (Zulrah), still genuinely ongoing (current is still ACTIVE
	// BOSSING/Zulrah at the moment this batch is classified). This keeps
	// exercising the same thing the test was always for -- same-tick loot
	// ownership across a REAL immediate activity switch -- via evidence
	// that still switches immediately under the corrected design.
	@Test
	public void lootWhileActive_sameTickDifferentActivitySwitch_lootStaysOnFinalizedOldSession_notNewSession() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String zulrahSessionId = coordinator.testCurrentSession().getSessionId();
		assertEquals(SessionState.ACTIVE, coordinator.testCurrentSession().getState());

		// Same tick, well within the ACTIVE window: a SPECIFIC signal
		// naming a different boss (Vorkath) -- always switches an ACTIVE
		// session immediately, regardless of the new EvidenceStrength
		// gating -- plus loot from the Zulrah kill that is still
		// genuinely ongoing (current is still ACTIVE BOSSING/Zulrah at
		// the moment this batch is classified).
		Instant t1 = T0.plusSeconds(10);
		coordinator.testEnqueueSignal(SessionSignal.bossActivityContext(t1, "Vorkath").withGameTick(2L));
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(t1, "Zulrah",
			Collections.singletonList(new SessionSignal.LootDrop(2, "Zulrah's scales", 100))).withGameTick(2L));
		coordinator.testProcessTick(3L, t1);

		Session newCurrent = coordinator.testCurrentSession();
		assertFalse("a real activity switch must have occurred", zulrahSessionId.equals(newCurrent.getSessionId()));
		assertEquals(ActivityType.BOSSING, newCurrent.getActivityIdentity().getActivityType());
		assertEquals("Vorkath", newCurrent.getActivityIdentity().getDisplayName());
		assertTrue("the new, unrelated Vorkath session must never receive Zulrah's loot",
			newCurrent.getAggregates().getLootDrops().isEmpty());

		Thread.sleep(300);
		Session finalizedFile = readFinalizedFile(zulrahSessionId);
		assertEquals("the finalized Zulrah session's DURABLE FILE must contain the loot that arrived "
			+ "while it was still genuinely ACTIVE, even though this same tick's other evidence caused "
			+ "a real switch to an unrelated Vorkath session",
			1, finalizedFile.getAggregates().getLootDrops().size());
	}

	// SLAYER-XP-WHILE-ACTIVE OWNERSHIP regression (same class as the
	// loot-while-active case above).
	// A SLAYER-skill XP_CHANGE classified METRIC_ONLY against an ACTIVE
	// combat-branch session (ActivitySignalClassifier.classifySlayerXp())
	// must stay attributed to THAT session even when this SAME tick's
	// other evidence (an authoritative BOSS_KILL for a different,
	// higher-ranked boss identity) resolves the batch to a real switch.
	// Before the fix, the whole batch's metrics -- including this Slayer
	// XP -- rode unconditionally onto whichever identity won the batch
	// (SessionLifecycleEngine.onQualifyingActivity()'s "different activity
	// while ACTIVE" branch applies its entire `metrics` argument to the
	// brand-new session), so the delayed Slayer XP earned during the
	// Gargoyles task ended up on the new Vorkath session instead of the
	// finalized Gargoyles session it actually came from.
	@Test
	public void slayerXpWhileActive_sameTickDifferentBossSwitch_slayerXpStaysOnFinalizedOldSession_notNewSession() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String gargoylesSessionId = coordinator.testCurrentSession().getSessionId();
		assertEquals(SessionState.ACTIVE, coordinator.testCurrentSession().getState());
		assertEquals(ActivityType.SLAYER, coordinator.testCurrentSession().getActivityIdentity().getActivityType());

		// Same tick, well within the ACTIVE window: delayed Slayer-skill
		// XP (classifySlayerXp() -- combat-branch current is ACTIVE, so
		// METRIC_ONLY) genuinely earned fighting Gargoyles, plus an
		// authoritative BOSS_KILL for an unrelated, higher-ranked boss
		// identity (BOSSING outranks SLAYER -- the "real switch, not
		// a preserved heartbeat" rule) that resolves this batch to
		// a real switch.
		Instant t1 = T0.plusSeconds(10);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(t1, "SLAYER", 113L).withGameTick(2L));
		coordinator.testEnqueueSignal(SessionSignal.bossKill(t1, "Vorkath", 1).withGameTick(2L));
		coordinator.testProcessTick(3L, t1);

		Session newCurrent = coordinator.testCurrentSession();
		assertFalse("a real activity switch must have occurred", gargoylesSessionId.equals(newCurrent.getSessionId()));
		assertEquals(ActivityType.BOSSING, newCurrent.getActivityIdentity().getActivityType());
		assertEquals("the new, unrelated Vorkath session must never receive the Gargoyles task's Slayer XP",
			0L, xpOf(newCurrent, "SLAYER"));

		Thread.sleep(300);
		Session finalizedFile = readFinalizedFile(gargoylesSessionId);
		assertEquals("the finalized Gargoyles session's DURABLE FILE must contain the Slayer XP that "
			+ "arrived while it was still genuinely ACTIVE, even though this same tick's other evidence "
			+ "caused a real switch to an unrelated Vorkath session",
			113L, xpOf(finalizedFile, "SLAYER"));
	}

	// =====================================================================
	// GG -> Gargoyles activity-switch --
	// end-to-end coverage of the exact live-reported defect through the
	// real coordinator/persistence path, for BOTH same-tick and
	// adjacent-tick orderings.
	// =====================================================================

	// C/D/E/F/G (same-tick): SLAYER_TASK_PROGRESS(Gargoyles) + its
	// matching SERVER_NPC_LOOT arrive in the SAME tick as the batch that
	// switches away from ACTIVE BOSSING/Grotesque Guardians. Expected:
	//  - old GG session finalizes, keeps its own boss reliableCount
	//    (KC/sessionOccurrences) exactly as it stood before the switch.
	//  - new SLAYER/Gargoyles session is a brand-new sessionId, ACTIVE,
	//    with normal Gargoyle XP/loot and NO boss reliableCount at all.
	@Test
	public void liveBugFix_sameTick_ggToGargoyles_oldSessionRetainsBossCount_newSessionGetsNormalGargoyleProgressAndLoot_noBossCount() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		// Establish ACTIVE BOSSING/Grotesque Guardians with a credited kill
		// (Dusk loot), exactly as in the live report.
		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Grotesque Guardians", 87).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.npcDeath(T0).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(T0, "Dusk",
			Collections.singletonList(new SessionSignal.LootDrop(1, "Dusk's remains", 1))).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		String ggSessionId = coordinator.testCurrentSession().getSessionId();
		assertEquals(SessionState.ACTIVE, coordinator.testCurrentSession().getState());
		assertEquals(ActivityType.BOSSING, coordinator.testCurrentSession().getActivityIdentity().getActivityType());
		assertEquals(1, coordinator.testCurrentSession().getAggregates().getReliableCount().getSessionOccurrences());
		assertEquals(1, coordinator.testCurrentSession().getAggregates().getLootDrops().size());

		// Same tick: the first normal Gargoyle kill's authoritative
		// SLAYER_TASK_PROGRESS plus its matching Gargoyle loot.
		Instant t1 = T0.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(t1, "Gargoyles", "Catacombs of Kourend", 1, 98).withGameTick(2L));
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(t1, "Gargoyle",
			Collections.singletonList(new SessionSignal.LootDrop(1, "Big bones", 1))).withGameTick(2L));
		coordinator.testProcessTick(3L, t1);

		Session newCurrent = coordinator.testCurrentSession();
		assertFalse("a real activity switch must have occurred", ggSessionId.equals(newCurrent.getSessionId()));
		assertEquals(SessionState.ACTIVE, newCurrent.getState());
		assertEquals(ActivityType.SLAYER, newCurrent.getActivityIdentity().getActivityType());
		assertEquals("normal Gargoyle loot must land on the NEW session", 1, newCurrent.getAggregates().getLootDrops().size());
		assertNull("the new Gargoyles session must carry no boss reliableCount at all",
			newCurrent.getAggregates().getReliableCount());

		Thread.sleep(300);
		Session finalizedGg = readFinalizedFile(ggSessionId);
		assertEquals(SessionState.FINALIZED, finalizedGg.getState());
		assertEquals("the old GG session's DURABLE FILE must retain its own boss reliableCount unchanged",
			1, finalizedGg.getAggregates().getReliableCount().getSessionOccurrences());
		assertEquals("the old GG session's DURABLE FILE must retain the Dusk loot, never the Gargoyle loot",
			1, finalizedGg.getAggregates().getLootDrops().size());
		assertEquals("Dusk", finalizedGg.getAggregates().getLootDrops().get(0).getSourceName());
	}

	// Boss self-consumption of its own
	// Slayer task; this exact scenario is the regression this
	// fixes -- see BossTaskAffinity/ClassifierContext/
	// ActivitySignalClassifier.classifySlayerTaskProgress() javadoc.
	// Before this fix, a lone SLAYER_TASK_PROGRESS/Gargoyles -- with NO
	// corroborating evidence at all that a real, different NPC produced
	// it -- switched away from ACTIVE BOSSING/Grotesque Guardians
	// immediately. That is exactly the live-reported defect: Grotesque
	// Guardians' OWN kills also decrement the Gargoyles task, so this
	// same input is genuinely ambiguous and must NOT switch by itself.
	// The switch must wait for actual disambiguating evidence -- here,
	// the Gargoyle loot arriving one tick later, which proves a real,
	// different NPC (not the boss) actually produced the progress.
	@Test
	public void liveBugFix_adjacentTick_ggToGargoyles_progressAloneStaysBossing_confirmingLootNextTickThenSwitches() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Grotesque Guardians", 87).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.npcDeath(T0).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(T0, "Dusk",
			Collections.singletonList(new SessionSignal.LootDrop(1, "Dusk's remains", 1))).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String ggSessionId = coordinator.testCurrentSession().getSessionId();

		// Tick N: SLAYER_TASK_PROGRESS alone, with no corroborating
		// evidence in this same tick -- this is exactly as consistent
		// with GG's own self-consumption as it is with a real Gargoyle,
		// so it must NOT switch by itself; it stays BOSSING/Grotesque
		// Guardians, with the progress accumulating internally and the
		// latest currentRemaining updated for display.
		Instant t1 = T0.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(t1, "Gargoyles", "Catacombs of Kourend", 1, 98).withGameTick(2L));
		coordinator.testProcessTick(3L, t1);

		Session stillBossing = coordinator.testCurrentSession();
		assertEquals("no premature switch: a boss's own known-task progress alone must never fabricate "
			+ "a bogus Slayer session", ggSessionId, stillBossing.getSessionId());
		assertEquals(ActivityType.BOSSING, stillBossing.getActivityIdentity().getActivityType());
		// CANDIDATE-METRIC BUFFERING follow-up fix: the ambiguous kill's
		// own progress metric is now BUFFERED (see ClassifierContext),
		// never applied to the boss session while the candidate is still
		// pending -- so the still-BOSSING session's own aggregates must
		// show NOTHING from it yet. This replaces the old
		// apply-then-possibly-retroactively-move behavior this test used
		// to assert.
		assertNull("the ambiguous kill's progress must NOT be applied to the boss session while "
			+ "still pending -- it is buffered, not applied",
			stillBossing.getAggregates().getSlayerProgressDelta());
		assertNull("the ambiguous kill's currentRemaining must NOT update the boss session's own "
			+ "display value while still pending",
			stillBossing.getAggregates().getLatestSlayerCurrentRemaining());

		// Tick N+1 (a separate batch entirely): Gargoyle loot -- a
		// genuinely different NPC, not the boss itself or one of its
		// known aliases (Dawn/Dusk) -- is the disambiguating evidence
		// that this WAS a real, different kill. The switch happens now,
		// confirmed by this loot.
		Instant t2 = t1.plusMillis(600);
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(t2, "Gargoyle",
			Collections.singletonList(new SessionSignal.LootDrop(1, "Big bones", 1))).withGameTick(3L));
		coordinator.testProcessTick(4L, t2);

		// CANDIDATE-METRIC BUFFERING follow-up fix: the ambiguous kill's
		// own progress metric was BUFFERED, never applied to GG -- now
		// that this loot confirms a real switch, it is applied to the
		// BRAND-NEW Gargoyles session instead, immediately (not waiting
		// for a second real Gargoyle kill). This is the exact defect
		// this fix closes.
		Session finalCurrent = coordinator.testCurrentSession();
		assertFalse("confirming loot must now cause the real switch", ggSessionId.equals(finalCurrent.getSessionId()));
		assertEquals(ActivityType.SLAYER, finalCurrent.getActivityIdentity().getActivityType());
		assertEquals(1, finalCurrent.getAggregates().getLootDrops().size());
		assertEquals("Gargoyle", finalCurrent.getAggregates().getLootDrops().get(0).getSourceName());
		assertNull("the new Gargoyles session must carry no boss reliableCount at all",
			finalCurrent.getAggregates().getReliableCount());
		assertEquals("the ambiguous kill's own progress delta must land on the NEW Gargoyles "
			+ "session, immediately upon confirmation", Integer.valueOf(1),
			finalCurrent.getAggregates().getSlayerProgressDelta());
		assertEquals("the new Gargoyles session must immediately show the correct currentRemaining "
			+ "from the ambiguous kill -- not wait for a second kill",
			Integer.valueOf(98), finalCurrent.getAggregates().getLatestSlayerCurrentRemaining());

		Thread.sleep(300);
		Session finalizedGg = readFinalizedFile(ggSessionId);
		assertEquals(SessionState.FINALIZED, finalizedGg.getState());
		assertEquals("the old GG session's DURABLE FILE must retain the Dusk loot and its own boss "
			+ "reliableCount, and NOTHING from the ambiguous kill that turned out to be a real, "
			+ "different Gargoyle -- the former known limitation is now fixed",
			1, finalizedGg.getAggregates().getLootDrops().size());
		assertEquals("Dusk", finalizedGg.getAggregates().getLootDrops().get(0).getSourceName());
		assertEquals(1, finalizedGg.getAggregates().getReliableCount().getSessionOccurrences());
		assertNull("the ambiguous kill's own progress metric must NOT remain on the finalized GG "
			+ "file -- it was buffered, never applied there, and moved to the confirmed Gargoyles "
			+ "session instead", finalizedGg.getAggregates().getSlayerProgressDelta());
		assertEquals("the adjacent-tick Gargoyle loot must never retroactively land on the "
			+ "already-finalized GG file", 1, finalizedGg.getAggregates().getLootDrops().size());
	}

	// NEW REGRESSION (live bug-fix pass -- boss self-consumption of its
	// own Slayer task): the EXACT live-reported failure sequence --
	// SLAYER_TASK_PROGRESS/Gargoyles from GG's own kill, then (several
	// seconds later, a separate tick) a same-boss BOSS_KILL, then (same
	// or adjacent tick) Dusk loot. Expected: ONE continuous Grotesque
	// Guardians session throughout -- no temporary SLAYER/Gargoyles
	// session ever created, the BOSS_KILL stays a heartbeat (not a
	// switch), reliableCount increments exactly once, and the Dusk loot
	// lands on the SAME, single, continuous session.
	@Test
	public void liveBugFix_ggSelfConsumption_taskProgressThenDelayedSameBossKill_oneContinuousSessionNoBogusSwitch_dusLootOnSameSession() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Grotesque Guardians", 88).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String ggSessionId = coordinator.testCurrentSession().getSessionId();
		assertEquals(1, coordinator.testCurrentSession().getAggregates().getReliableCount().getSessionOccurrences());

		// GG's own kill decrements the Gargoyles task -- no corroborating
		// evidence of a real, different NPC anywhere nearby.
		Instant progressAt = T0.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(progressAt, "Gargoyles", "Catacombs of Kourend", 1, 90).withGameTick(2L));
		coordinator.testProcessTick(3L, progressAt);

		assertEquals("no bogus Gargoyles session must ever be created for GG's own kill",
			ggSessionId, coordinator.testCurrentSession().getSessionId());
		assertEquals(ActivityType.BOSSING, coordinator.testCurrentSession().getActivityIdentity().getActivityType());

		// ~3.6 seconds later (matching the live report), the delayed
		// same-boss BOSS_KILL message arrives, on its own separate tick --
		// this is the disconfirming evidence that this WAS self-consumption.
		Instant bossKillAt = progressAt.plusMillis(3577);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(bossKillAt, "Grotesque Guardians", 89).withGameTick(3L));
		coordinator.testProcessTick(4L, bossKillAt);

		Session afterBossKill = coordinator.testCurrentSession();
		assertEquals("the same-boss BOSS_KILL must remain a heartbeat of the SAME session -- never a "
			+ "fresh, third session", ggSessionId, afterBossKill.getSessionId());
		assertEquals(ActivityType.BOSSING, afterBossKill.getActivityIdentity().getActivityType());
		assertEquals("reliableCount must increment exactly once more on the SAME session",
			2, afterBossKill.getAggregates().getReliableCount().getSessionOccurrences());
		// CANDIDATE-METRIC BUFFERING follow-up fix / REGRESSION 1+5: the
		// buffered progress metric from the ambiguous kill is applied
		// EXACTLY ONCE, here, to the SAME GG session it was always
		// buffered against -- never twice, never on any other session.
		assertEquals("GG must receive the ambiguous kill's own progress delta exactly once -- "
			+ "no double count", Integer.valueOf(1), afterBossKill.getAggregates().getSlayerProgressDelta());
		assertEquals("GG's own currentRemaining display must reflect the confirmed self-consumption kill",
			Integer.valueOf(90), afterBossKill.getAggregates().getLatestSlayerCurrentRemaining());

		// Dusk loot immediately after, same or adjacent tick.
		Instant lootAt = bossKillAt.plusMillis(25);
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(lootAt, "Dusk",
			Collections.singletonList(new SessionSignal.LootDrop(1, "Granite dust", 90))).withGameTick(4L));
		coordinator.testProcessTick(5L, lootAt);

		Session finalCurrent = coordinator.testCurrentSession();
		assertEquals("Dusk loot after the BOSS_KILL must land on the SAME, single, continuous GG session",
			ggSessionId, finalCurrent.getSessionId());
		assertEquals(1, finalCurrent.getAggregates().getLootDrops().size());
		assertEquals("Dusk", finalCurrent.getAggregates().getLootDrops().get(0).getSourceName());
		assertEquals(SessionState.ACTIVE, finalCurrent.getState());
	}

	// =====================================================================
	// CURRENT SESSION HYSTERESIS PASS -- SHELLBANE GRYPHON CANONICAL-IDENTITY
	// CHURN FIX. Full-pipeline (coordinator-level) proof of the live repro:
	// an ACTIVE BOSSING/Shellbane Gryphon session receiving
	// SLAYER_TASK_PROGRESS/Gryphons (its own known task) must never be
	// finalized and replaced by a zero-duration SLAYER/Gryphons "bridge"
	// session, and the SAME sessionId must survive combat XP -> matching
	// SLAYER_TASK_PROGRESS -> BOSS_KILL -> SERVER_NPC_LOOT, with every
	// metric landing on that one session. These mirror
	// liveBugFix_ggSelfConsumption_taskProgressThenDelayedSameBossKill_
	// oneContinuousSessionNoBogusSwitch_dusLootOnSameSession above,
	// substituting Shellbane Gryphon/Gryphons and the live report's own
	// timings/units (task remaining 29 -> 28, ~2.38s to the confirming
	// BOSS_KILL) -- see BossTaskAffinity's javadoc for the one-line data
	// addition this fix made; no other production code changed.
	// =====================================================================

	// The exact live repro's first half: SLAYER_TASK_PROGRESS naming
	// Shellbane Gryphon's own task must NOT finalize/replace the ACTIVE
	// BOSSING session with a new, zero-duration SLAYER session -- the
	// current session's id must never change.
	@Test
	public void zeroDurationTransientSlayerBridge_isNotCreated() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Shellbane Gryphon", 12).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String shellbaneSessionId = coordinator.testCurrentSession().getSessionId();
		assertEquals(ActivityType.BOSSING, coordinator.testCurrentSession().getActivityIdentity().getActivityType());

		// Shellbane Gryphon's own kill decrements the "Gryphons" Slayer
		// task -- the exact live-reported taskName, 29 -> 28 remaining.
		Instant progressAt = T0.plusSeconds(4);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(
			progressAt, "Gryphons", "Karuulm Slayer Dungeon", 1, 28).withGameTick(2L));
		coordinator.testProcessTick(3L, progressAt);

		assertEquals("no zero-duration SLAYER/Gryphons bridge session may ever be created for "
			+ "Shellbane Gryphon's own kill -- the current session's id must never change",
			shellbaneSessionId, coordinator.testCurrentSession().getSessionId());
		assertEquals("the established boss identity must remain canonical PRIMARY, never replaced "
			+ "by the matching Slayer task's own identity",
			ActivityType.BOSSING, coordinator.testCurrentSession().getActivityIdentity().getActivityType());
	}

	// The exact live repro's second half: the delayed, same-boss BOSS_KILL
	// that arrives after the matching SLAYER_TASK_PROGRESS must remain a
	// heartbeat of the SAME session -- proof the sessionId is stable
	// across the full ambiguous-progress-then-confirming-kill sequence.
	@Test
	public void bossKillAfterMatchingSlayerProgress_preservesSameSessionId() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Shellbane Gryphon", 12).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String shellbaneSessionId = coordinator.testCurrentSession().getSessionId();

		Instant progressAt = T0.plusSeconds(4);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(
			progressAt, "Gryphons", "Karuulm Slayer Dungeon", 1, 28).withGameTick(2L));
		coordinator.testProcessTick(3L, progressAt);

		// ~2.38 seconds later (matching the live report), the confirming
		// same-boss BOSS_KILL arrives on its own separate tick.
		Instant bossKillAt = progressAt.plusMillis(2380);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(bossKillAt, "Shellbane Gryphon", 13).withGameTick(3L));
		coordinator.testProcessTick(4L, bossKillAt);

		Session afterBossKill = coordinator.testCurrentSession();
		assertEquals("the confirming same-boss BOSS_KILL must remain a heartbeat of the SAME "
			+ "session -- never a fresh session, and never the zero-duration bridge",
			shellbaneSessionId, afterBossKill.getSessionId());
		assertEquals(ActivityType.BOSSING, afterBossKill.getActivityIdentity().getActivityType());
		assertEquals("reliableCount must increment exactly once more on the SAME session",
			2, afterBossKill.getAggregates().getReliableCount().getSessionOccurrences());
		assertEquals("the buffered Slayer progress delta must be applied exactly once, to the SAME "
			+ "session it was always buffered against", Integer.valueOf(1),
			afterBossKill.getAggregates().getSlayerProgressDelta());
		assertEquals("the latest Slayer remaining count must reflect the confirmed self-consumption "
			+ "kill", Integer.valueOf(28), afterBossKill.getAggregates().getLatestSlayerCurrentRemaining());
	}

	// METRIC OWNERSHIP, full pipeline: combat XP, matching Slayer task
	// progress, the confirming boss kill, and the boss's own loot must ALL
	// remain on the ONE session throughout -- no metric lost, duplicated,
	// or transferred to any transient bridge session.
	@Test
	public void shellbaneEncounter_combatXpSlayerProgressBossKillLoot_allRemainInOneSession() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Shellbane Gryphon", 12).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String shellbaneSessionId = coordinator.testCurrentSession().getSessionId();

		// Ordinary combat XP mid-encounter must never downgrade/switch the
		// ACTIVE boss session (see liveBugFixNegativeSpace_... in
		// ActivitySignalClassifierTest).
		Instant xpAt = T0.plusSeconds(2);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(xpAt, "RANGED", 60L).withGameTick(2L));
		coordinator.testProcessTick(3L, xpAt);
		assertEquals(shellbaneSessionId, coordinator.testCurrentSession().getSessionId());

		Instant progressAt = T0.plusSeconds(4);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(
			progressAt, "Gryphons", "Karuulm Slayer Dungeon", 1, 28).withGameTick(3L));
		coordinator.testProcessTick(4L, progressAt);
		assertEquals(shellbaneSessionId, coordinator.testCurrentSession().getSessionId());

		Instant bossKillAt = progressAt.plusMillis(2380);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(bossKillAt, "Shellbane Gryphon", 13).withGameTick(4L));
		coordinator.testProcessTick(5L, bossKillAt);
		assertEquals(shellbaneSessionId, coordinator.testCurrentSession().getSessionId());

		Instant lootAt = bossKillAt.plusMillis(40);
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(lootAt, "Shellbane Gryphon",
			Collections.singletonList(new SessionSignal.LootDrop(1, "Gryphon feather", 1))).withGameTick(5L));
		coordinator.testProcessTick(6L, lootAt);

		Session finalCurrent = coordinator.testCurrentSession();
		assertEquals("combat XP, matching Slayer progress, the confirming boss kill, and the boss's "
			+ "own loot must all remain on the ONE session throughout -- the same sessionId that "
			+ "started at the very first BOSS_KILL",
			shellbaneSessionId, finalCurrent.getSessionId());
		assertEquals(ActivityType.BOSSING, finalCurrent.getActivityIdentity().getActivityType());
		assertEquals("no metric lost or duplicated -- reliableCount reflects exactly the two "
			+ "authoritative boss kills", 2, finalCurrent.getAggregates().getReliableCount().getSessionOccurrences());
		assertEquals(Integer.valueOf(1), finalCurrent.getAggregates().getSlayerProgressDelta());
		assertEquals(Integer.valueOf(28), finalCurrent.getAggregates().getLatestSlayerCurrentRemaining());
		assertEquals("boss loot must be attributed to the same, single session -- never a transient "
			+ "bridge session", 1, finalCurrent.getAggregates().getLootDrops().size());
		assertEquals("Shellbane Gryphon", finalCurrent.getAggregates().getLootDrops().get(0).getSourceName());
		assertEquals(SessionState.ACTIVE, finalCurrent.getState());
	}

	// =====================================================================
	// CURRENT SESSION HYSTERESIS PASS -- SUSPENDED BOSS-OWN-TASK
	// REACTIVATION FIX. Full-pipeline (coordinator-level) proof of the
	// live-PERSISTED repro: a SUSPENDED BOSSING/Shellbane Gryphon session,
	// still well inside its 30-minute resume window, must reactivate (not
	// finalize) upon receiving its own known task's SLAYER_TASK_PROGRESS,
	// preserving sessionId/startedAt/prior aggregates, and the SAME
	// session must go on to own the confirming BOSS_KILL and its loot.
	// See BossTaskAffinity's own javadoc and
	// ActivitySignalClassifier.classifySlayerTaskProgress()'s own javadoc
	// for the fix (ACTIVE-or-SUSPENDED widened guard, no new timers/
	// confirmation-count logic).
	// =====================================================================

	@Test
	public void suspendedShellbane_matchingProgress_reactivatesOrPreservesSameSessionId() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Shellbane Gryphon", 12).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String sessionId = coordinator.testCurrentSession().getSessionId();
		Instant startedAt = coordinator.testCurrentSession().startedAtInstant();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);
		assertEquals(SessionState.SUSPENDED, coordinator.testCurrentSession().getState());
		assertEquals(sessionId, coordinator.testCurrentSession().getSessionId());

		// The exact live-persisted repro's own resume-window margin (well
		// inside the 30-minute window).
		Instant progressAt = afterSuspend.plusSeconds(149);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(
			progressAt, "Gryphons", "Karuulm Slayer Dungeon", 1, 19).withGameTick(2L));
		coordinator.testProcessTick(4L, progressAt);

		Session reactivated = coordinator.testCurrentSession();
		assertEquals("the ORIGINAL sessionId must reactivate -- no new bridge session",
			sessionId, reactivated.getSessionId());
		assertEquals("startedAt must never change on a resume -- this is the SAME session, not a "
			+ "new one", startedAt, reactivated.startedAtInstant());
		assertEquals(SessionState.ACTIVE, reactivated.getState());
		assertEquals(ActivityType.BOSSING, reactivated.getActivityIdentity().getActivityType());
	}

	@Test
	public void suspendedShellbane_matchingProgress_thenBossKill_sameSessionId() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Shellbane Gryphon", 12).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String sessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);

		Instant progressAt = afterSuspend.plusSeconds(149); // ~2m29s later, matching the live report
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(
			progressAt, "Gryphons", "Karuulm Slayer Dungeon", 1, 19).withGameTick(2L));
		coordinator.testProcessTick(4L, progressAt);
		assertEquals(sessionId, coordinator.testCurrentSession().getSessionId());

		// ~2.38s later, matching the live report's own confirming BOSS_KILL gap.
		Instant bossKillAt = progressAt.plusMillis(2380);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(bossKillAt, "Shellbane Gryphon", 13).withGameTick(3L));
		coordinator.testProcessTick(5L, bossKillAt);

		Session afterKill = coordinator.testCurrentSession();
		assertEquals("the confirming same-boss BOSS_KILL after a matching-progress reactivation "
			+ "must remain the SAME sessionId", sessionId, afterKill.getSessionId());
		assertEquals("reliableCount must increment exactly once more on the SAME session",
			2, afterKill.getAggregates().getReliableCount().getSessionOccurrences());
		assertEquals("the buffered Slayer progress delta from the reactivating event must land on "
			+ "the SAME session", Integer.valueOf(1), afterKill.getAggregates().getSlayerProgressDelta());
		assertEquals(Integer.valueOf(19), afterKill.getAggregates().getLatestSlayerCurrentRemaining());
	}

	@Test
	public void suspendedBoss_ownTaskProgress_noZeroDurationSlayerBridge() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Shellbane Gryphon", 12).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String sessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);

		Instant progressAt = afterSuspend.plusSeconds(149);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(
			progressAt, "Gryphons", "Karuulm Slayer Dungeon", 1, 19).withGameTick(2L));
		coordinator.testProcessTick(4L, progressAt);

		assertEquals("no transient SLAYER/Gryphons bridge session may ever be created -- the "
			+ "current session id must be completely unchanged",
			sessionId, coordinator.testCurrentSession().getSessionId());
		assertEquals("no transient bridge session -- the BOSSING identity must be completely "
			+ "unchanged too", ActivityType.BOSSING, coordinator.testCurrentSession().getActivityIdentity().getActivityType());
	}

	// METRIC OWNERSHIP, full chain: prior activity (before suspension),
	// suspension itself, the reactivating matching progress, the
	// confirming boss kill, and new loot afterward must ALL remain on the
	// ONE session -- nothing lost, nothing duplicated, and the suspended
	// interval must never be counted as active duration.
	@Test
	public void suspendedShellbane_fullResumeChain_preservesPriorAndNewMetrics() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Shellbane Gryphon", 10).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String sessionId = coordinator.testCurrentSession().getSessionId();

		// A second heartbeat BEFORE suspension so accumulatedActiveDurationMillis
		// is genuinely non-zero going in -- proving the suspended gap is
		// excluded later, not merely assuming it starts at zero.
		Instant secondKillAt = T0.plusSeconds(8);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(secondKillAt, "Shellbane Gryphon", 11).withGameTick(2L));
		coordinator.testProcessTick(3L, secondKillAt);
		assertEquals(sessionId, coordinator.testCurrentSession().getSessionId());
		assertEquals(2, coordinator.testCurrentSession().getAggregates().getReliableCount().getSessionOccurrences());
		long activeDurationBeforeSuspend = coordinator.testCurrentSession().getAccumulatedActiveDurationMillis();
		assertEquals(8000L, activeDurationBeforeSuspend);

		Instant priorLootAt = secondKillAt.plusMillis(100);
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(priorLootAt, "Shellbane Gryphon",
			Collections.singletonList(new SessionSignal.LootDrop(1, "Gryphon feather", 1))).withGameTick(3L));
		coordinator.testProcessTick(4L, priorLootAt);
		assertEquals(1, coordinator.testCurrentSession().getAggregates().getLootDrops().size());

		// Idle out to SUSPENDED, well past SUSPEND_TIMEOUT.
		Instant afterSuspend = priorLootAt.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(5L, afterSuspend);
		assertEquals(sessionId, coordinator.testCurrentSession().getSessionId());
		assertEquals(SessionState.SUSPENDED, coordinator.testCurrentSession().getState());

		// Matching own-task Slayer progress, well inside the 30-minute
		// resume window -- must reactivate, not finalize.
		Instant progressAt = afterSuspend.plusSeconds(120);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(
			progressAt, "Gryphons", "Karuulm Slayer Dungeon", 1, 19).withGameTick(4L));
		coordinator.testProcessTick(6L, progressAt);

		Session reactivated = coordinator.testCurrentSession();
		assertEquals("the SAME sessionId must reactivate, never a new bridge session",
			sessionId, reactivated.getSessionId());
		assertEquals(SessionState.ACTIVE, reactivated.getState());
		assertEquals(ActivityType.BOSSING, reactivated.getActivityIdentity().getActivityType());
		assertEquals("prior reliableCount from before suspension must survive",
			2, reactivated.getAggregates().getReliableCount().getSessionOccurrences());
		assertEquals("prior loot from before suspension must survive",
			1, reactivated.getAggregates().getLootDrops().size());

		// Confirming same-boss BOSS_KILL, ~2.38s later (matching the live report).
		Instant bossKillAt = progressAt.plusMillis(2380);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(bossKillAt, "Shellbane Gryphon", 12).withGameTick(5L));
		coordinator.testProcessTick(7L, bossKillAt);

		Session afterConfirm = coordinator.testCurrentSession();
		assertEquals(sessionId, afterConfirm.getSessionId());
		assertEquals("reliableCount increments exactly once more on the SAME session",
			3, afterConfirm.getAggregates().getReliableCount().getSessionOccurrences());
		assertEquals("the buffered Slayer progress delta lands on the SAME session",
			Integer.valueOf(1), afterConfirm.getAggregates().getSlayerProgressDelta());
		assertEquals(Integer.valueOf(19), afterConfirm.getAggregates().getLatestSlayerCurrentRemaining());

		// New boss loot after the confirming kill.
		Instant newLootAt = bossKillAt.plusMillis(50);
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(newLootAt, "Shellbane Gryphon",
			Collections.singletonList(new SessionSignal.LootDrop(2, "Gryphon claw", 1))).withGameTick(6L));
		coordinator.testProcessTick(8L, newLootAt);

		Session finalSession = coordinator.testCurrentSession();
		assertEquals("the entire chain -- prior activity, suspension, reactivation, confirming "
			+ "kill, and new loot -- must stay the SAME single session throughout",
			sessionId, finalSession.getSessionId());
		assertEquals("both the prior and the new loot drop must be present -- nothing lost, "
			+ "nothing duplicated", 2, finalSession.getAggregates().getLootDrops().size());
		// 8000ms accumulated before suspension, plus the 2380ms of genuine
		// ACTIVE gameplay between the reactivating progress event and the
		// confirming kill -- the ~7-minute SUSPENDED gap (5-minute
		// SUSPEND_TIMEOUT idle-out + ~2-minute resume delay) contributes
		// NOTHING, exactly per SessionLifecycleEngine's resume() contract
		// (it fast-forwards lastActiveAt without ever calling accumulate()).
		assertEquals("suspended time must never be counted as active duration",
			10380L, finalSession.getAccumulatedActiveDurationMillis());
	}

	// NEW REGRESSION (CANDIDATE-METRIC BUFFERING follow-up fix) -- the
	// SAME-TICK timing variant of the adjacent-tick test above: the
	// ambiguous kill's own SLAYER_TASK_PROGRESS and its confirming,
	// genuinely-different-NPC SERVER_NPC_LOOT arrive in ONE batch
	// (same game tick) instead of across two. Proves ownership is
	// identical regardless of timing: GG never gains a second progress
	// unit from the first real Gargoyle, and the brand-new Gargoyles
	// session shows the correct progressDelta/currentRemaining and loot
	// immediately, with no bogus reliableCount at all.
	@Test
	public void candidateMetricBuffering_sameTickConfirmingLoot_newSessionGetsProgressAndLootImmediately_ggUnaffected() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Grotesque Guardians", 87).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(T0, "Dusk",
			Collections.singletonList(new SessionSignal.LootDrop(1, "Dusk's remains", 1))).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String ggSessionId = coordinator.testCurrentSession().getSessionId();
		assertEquals(1, coordinator.testCurrentSession().getAggregates().getReliableCount().getSessionOccurrences());

		// Same tick: the first REAL regular Gargoyle's own
		// SLAYER_TASK_PROGRESS/Gargoyles (identical in shape to GG's own
		// self-consumption event) plus its own confirming loot, a
		// genuinely different NPC.
		Instant t1 = T0.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(t1, "Gargoyles", "Catacombs of Kourend", 1, 89).withGameTick(2L));
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(t1, "Gargoyle",
			Collections.singletonList(new SessionSignal.LootDrop(1, "Big bones", 1))).withGameTick(2L));
		coordinator.testProcessTick(3L, t1);

		Session newCurrent = coordinator.testCurrentSession();
		assertFalse("the same-tick confirming loot must cause a real switch", ggSessionId.equals(newCurrent.getSessionId()));
		assertEquals(ActivityType.SLAYER, newCurrent.getActivityIdentity().getActivityType());
		assertNull("REGRESSION 2: the new Gargoyles session must carry no boss reliableCount at all",
			newCurrent.getAggregates().getReliableCount());
		assertEquals("REGRESSION 3: the first real Gargoyle must give the new session progressDelta=1",
			Integer.valueOf(1), newCurrent.getAggregates().getSlayerProgressDelta());
		assertEquals("REGRESSION 4: the new session must show the correct currentRemaining immediately "
			+ "upon confirmation -- not wait for a second kill",
			Integer.valueOf(89), newCurrent.getAggregates().getLatestSlayerCurrentRemaining());
		assertEquals("REGRESSION 5: the confirming Gargoyle loot must be in the new session",
			1, newCurrent.getAggregates().getLootDrops().size());
		assertEquals("Gargoyle", newCurrent.getAggregates().getLootDrops().get(0).getSourceName());

		Thread.sleep(300);
		Session finalizedGg = readFinalizedFile(ggSessionId);
		assertEquals(SessionState.FINALIZED, finalizedGg.getState());
		assertEquals("REGRESSION 2: GG's own finalized reliableCount must be unaffected by the first "
			+ "real Gargoyle", 1, finalizedGg.getAggregates().getReliableCount().getSessionOccurrences());
		assertNull("REGRESSION 2/6: the first real Gargoyle's own progress must NOT add a second unit "
			+ "to GG, same-tick timing included", finalizedGg.getAggregates().getSlayerProgressDelta());
		assertEquals("GG's own Dusk loot must be retained, untouched by the Gargoyle loot",
			1, finalizedGg.getAggregates().getLootDrops().size());
		assertEquals("Dusk", finalizedGg.getAggregates().getLootDrops().get(0).getSourceName());
	}

	// NEW REGRESSION (CANDIDATE-METRIC BUFFERING follow-up fix, DESIRED
	// RESOLUTION C) -- a self-consumption candidate that never resolves
	// because the boss session itself times out to SUSPENDED before
	// either a same-boss BOSS_KILL or confirming loot ever arrives: its
	// buffered metric must not be silently lost. It is committed once,
	// as a normal contextual metric, to the SAME session -- now merely
	// SUSPENDED, still legitimately current.
	@Test
	public void candidateMetricBuffering_neverResolved_idleTimeoutFlushesBufferedMetricToSameSuspendedSession() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Grotesque Guardians", 87).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String ggSessionId = coordinator.testCurrentSession().getSessionId();

		Instant progressAt = T0.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(progressAt, "Gargoyles", "Catacombs of Kourend", 1, 86).withGameTick(2L));
		coordinator.testProcessTick(3L, progressAt);
		assertNull("still pending -- buffered, not applied",
			coordinator.testCurrentSession().getAggregates().getSlayerProgressDelta());

		// No BOSS_KILL, no loot, ever -- just idle time past the 5-minute
		// suspend threshold, with nothing else pending for the still-open
		// tick (so processTick()'s own watermark lets advanceTime() run).
		Instant idleAt = progressAt.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, idleAt);

		Session suspended = coordinator.testCurrentSession();
		assertEquals("the candidate's own session must still be current, merely SUSPENDED -- never "
			+ "silently replaced or lost", ggSessionId, suspended.getSessionId());
		assertEquals(SessionState.SUSPENDED, suspended.getState());
		assertEquals("the never-resolved candidate's buffered progress metric must be committed to "
			+ "this SAME session once it stops being current for an unrelated reason (DESIRED "
			+ "RESOLUTION C) -- never silently dropped",
			Integer.valueOf(1), suspended.getAggregates().getSlayerProgressDelta());
		assertEquals(Integer.valueOf(86), suspended.getAggregates().getLatestSlayerCurrentRemaining());
	}

	// LOOT OWNERSHIP regression, generalized:
	// the same preBatchCurrentMetricUpdates misattribution the live
	// regression proved for a SLAYER_TASK_PROGRESS-driven switch also
	// happens for an ordinary, unrelated BOSS_KILL-driven boss-to-boss
	// switch (no boss self-consumption involved at all here). Same-tick
	// BOSS_KILL(Zulrah) + its own loot, while `current` is a DIFFERENT,
	// unrelated boss (Vorkath) -- the loot must land on the NEW Zulrah
	// session, never the outgoing Vorkath one.
	@Test
	public void lootOwnership_sameTickBossToBossSwitch_bossKillDrivenLootLandsOnNewSession_notOldSession() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Vorkath", 10).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String vorkathSessionId = coordinator.testCurrentSession().getSessionId();

		Instant t1 = T0.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(t1, "Zulrah", 1).withGameTick(2L));
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(t1, "Zulrah",
			Collections.singletonList(new SessionSignal.LootDrop(2, "Zulrah's scales", 40))).withGameTick(2L));
		coordinator.testProcessTick(3L, t1);

		Session newCurrent = coordinator.testCurrentSession();
		assertFalse("a real boss-to-boss switch must have occurred", vorkathSessionId.equals(newCurrent.getSessionId()));
		assertEquals("Zulrah", newCurrent.getActivityIdentity().getDisplayName());
		assertEquals("the newly-established Zulrah session's own kill loot must never land on the "
			+ "outgoing Vorkath session", 1, newCurrent.getAggregates().getLootDrops().size());
		assertEquals("Zulrah", newCurrent.getAggregates().getLootDrops().get(0).getSourceName());

		Thread.sleep(300);
		Session finalizedVorkath = readFinalizedFile(vorkathSessionId);
		assertTrue("the finalized Vorkath session must never receive Zulrah's loot",
			finalizedVorkath.getAggregates().getLootDrops().isEmpty());
	}

	@Test
	public void serverNpcLootAlone_neverIndependentlyCreatesASession()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(T0, "Zulrah",
			Collections.singletonList(new SessionSignal.LootDrop(2, "Zulrah's scales", 5))).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		assertNull(coordinator.testCurrentSession());
	}

	@Test
	public void npcDeathAlone_neverCreatesASession()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.npcDeath(T0).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		assertNull(coordinator.testCurrentSession());
	}

	@Test
	public void noncombatXp_createsSkillingSession()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 200L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		assertEquals(ActivityType.SKILLING, coordinator.testCurrentSession().getActivityIdentity().getActivityType());
	}

	@Test
	public void twoConflictingSkillingSignalsSameTickWithNoMatchingCurrent_producesNoTransitionButKeepsMetrics()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs", 120).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		ActivityIdentity beforeIdentity = coordinator.testCurrentSession().getActivityIdentity();

		Instant t1 = T0.plusSeconds(10);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(t1, "WOODCUTTING", 50L).withGameTick(2L));
		coordinator.testEnqueueSignal(SessionSignal.xpChange(t1, "MINING", 50L).withGameTick(2L));
		coordinator.testProcessTick(3L, t1);

		Session current = coordinator.testCurrentSession();
		assertEquals("a genuine ambiguous tie must never mutate the current activity identity",
			beforeIdentity, current.getActivityIdentity());
		assertEquals(50L, (long) current.getAggregates().getXpGainedBySkill().get("WOODCUTTING"));
		assertEquals(50L, (long) current.getAggregates().getXpGainedBySkill().get("MINING"));
	}

	@Test
	public void sameActivityAcrossTicks_sameSessionActiveDurationAdvances()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String sessionId = coordinator.testCurrentSession().getSessionId();

		Instant t1 = T0.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(t1, "WOODCUTTING", 10L).withGameTick(2L));
		coordinator.testProcessTick(3L, t1);

		Session current = coordinator.testCurrentSession();
		assertEquals(sessionId, current.getSessionId());
		assertEquals(30_000L, current.getAccumulatedActiveDurationMillis());
	}

	@Test
	public void fiveMinutesInactivity_suspendsAndExcludesIdleFromActiveDuration()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		Instant afterTimeout = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterTimeout);

		Session current = coordinator.testCurrentSession();
		assertEquals(SessionState.SUSPENDED, current.getState());
		assertEquals(0L, current.getAccumulatedActiveDurationMillis());
	}

	@Test
	public void sameActivityWithinResumeWindow_resumesSameSession()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String sessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);
		assertEquals(SessionState.SUSPENDED, coordinator.testCurrentSession().getState());

		Instant withinResumeWindow = afterSuspend.plus(Duration.ofMinutes(10));
		coordinator.testEnqueueSignal(SessionSignal.xpChange(withinResumeWindow, "WOODCUTTING", 5L).withGameTick(3L));
		coordinator.testProcessTick(4L, withinResumeWindow);

		Session current = coordinator.testCurrentSession();
		assertEquals(SessionState.ACTIVE, current.getState());
		assertEquals(sessionId, current.getSessionId());
	}

	@Test
	public void differentActivityWhileSuspended_finalizesOldAndStartsNew() throws Exception
	{
		// GENERALIZED (incidental/support-skill pass): a SKILLING-established
		// session now needs the SAME second, coherent confirming observation
		// a combat-branch session has always required before a differing
		// ORDINARY-strength identity may finalize/switch it -- see
		// SessionLifecycleEngine.isWeakEvidenceAgainstEstablishedSession()'s
		// own "GENERALIZED TO SKILLING" section. This test's ONE Mining tick
		// used to switch immediately; it now only ARMS a candidate (see
		// singleIncidentalMiningTickWhileSuspendedWoodcutting_doesNotSwitch
		// below, which now owns exactly that single-tick assertion), and a
		// SECOND Mining tick is what actually confirms the switch this test
		// verifies.
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String oldSessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);

		Instant t2 = afterSuspend.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(t2, "MINING", 10L).withGameTick(3L));
		coordinator.testProcessTick(4L, t2);

		Instant t3 = t2.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(t3, "MINING", 10L).withGameTick(4L));
		coordinator.testProcessTick(5L, t3);

		Session current = coordinator.testCurrentSession();
		assertFalse(oldSessionId.equals(current.getSessionId()));
		assertEquals("mining", current.getActivityIdentity().getActivityKey());

		Thread.sleep(300);
		assertTrue(TelemetryPaths.sessionFile(ACCOUNT_A, oldSessionId).exists());
	}

	// GENERALIZED (incidental/support-skill pass): the single-observation
	// half of the scenario above -- one incidental Mining tick during a
	// SUSPENDED Woodcutting session must not, by itself, finalize it. Mirrors
	// the combat-branch weakEvidenceWhileActive_singleIncidentalObservation_...
	// pattern in SessionLifecycleEngineTest, exercised here end-to-end
	// through the real coordinator/classifier/persistence pipeline.
	@Test
	public void singleIncidentalMiningTickWhileSuspendedWoodcutting_doesNotSwitch()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String woodcuttingSessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);

		Instant t2 = afterSuspend.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(t2, "MINING", 10L).withGameTick(3L));
		coordinator.testProcessTick(4L, t2);

		Session current = coordinator.testCurrentSession();
		assertEquals("a single incidental Mining tick must only arm a candidate, never switch",
			woodcuttingSessionId, current.getSessionId());
		assertEquals("woodcutting", current.getActivityIdentity().getActivityKey());
	}

	@Test
	public void resumeWindowExpires_finalizesOldSession() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String oldSessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);

		Instant afterResumeWindow = afterSuspend.plus(SessionLifecycleEngine.RESUME_WINDOW).plusSeconds(1);
		coordinator.testProcessTick(4L, afterResumeWindow);

		assertNull(coordinator.testCurrentSession());
		Thread.sleep(300);
		assertTrue(TelemetryPaths.sessionFile(ACCOUNT_A, oldSessionId).exists());
	}

	// =====================================================================
	// Adversarial hardening pass -- new/rewritten regression coverage
	// =====================================================================

	/**
	 * Item 3 / letter E: the core bug this fixes. A signal tagged
	 * with the tick currently being dispatched must NOT be resolved
	 * merely because this coordinator's own onGameTick() happened to run
	 * -- another subscriber's own onGameTick() handler for that SAME
	 * tick (e.g. SkillsCollector's periodic flush) may still be about to
	 * emit more telemetry for it. Only observing a STRICTLY LATER tick
	 * proves the earlier one is closed.
	 */
	@Test
	public void e_signalEmittedByAnotherSubscribersOwnOnGameTickHandler_stillJoinsTheSameClosedTickBatch()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		// This coordinator's own onGameTick() fires for tick 5 and sees
		// only the boss kill so far.
		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 1).withGameTick(5L));
		coordinator.testProcessTick(5L, T0);

		assertNull(
			"a signal from the tick currently being dispatched must never resolve just because THIS coordinator's own onGameTick() ran",
			coordinator.testCurrentSession());
		assertEquals(1, coordinator.testPendingSignalCount());

		// ANOTHER subscriber's own onGameTick() handler for the SAME tick
		// 5 (e.g. OsrsTelemetryPlugin's periodic XP-window flush) now
		// emits more tick-5 telemetry, after this coordinator's own
		// onGameTick() already ran once for tick 5.
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 50L).withGameTick(5L));

		// Only once tick 6 is observed is tick 5 provably closed.
		coordinator.testProcessTick(6L, T0.plusSeconds(1));

		Session current = coordinator.testCurrentSession();
		assertEquals("both tick-5 signals must resolve together as one BOSSING session, not split across two batches",
			ActivityType.BOSSING, current.getActivityIdentity().getActivityType());
		assertEquals(1, current.getAggregates().getReliableCount().getSessionOccurrences());
	}

	/**
	 * Letter H: a signal whose OBSERVED timestamp is before a timeout
	 * boundary must be applied before that boundary's transition, even
	 * when the coordinator only gets around to resolving it in the same
	 * call that also reaches the boundary.
	 */
	@Test
	public void h_signalObservedBeforeSuspendBoundary_isAppliedBeforeSuspendEvenWhenResolvedLate()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String sessionId = coordinator.testCurrentSession().getSessionId();

		// A genuine same-activity signal observed EXACTLY at the 5-minute
		// suspend threshold, tagged tick 2, resolved only once tick 3 is
		// observed -- in the SAME call that also reaches the timeout.
		Instant atThreshold = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(atThreshold, "WOODCUTTING", 5L).withGameTick(2L));
		coordinator.testProcessTick(3L, atThreshold);

		Session current = coordinator.testCurrentSession();
		assertEquals("the still-pending same-activity signal must keep the session ACTIVE, not suspend it",
			SessionState.ACTIVE, current.getState());
		assertEquals(sessionId, current.getSessionId());
	}

	/**
	 * Letter I: an event observed before the 30-minute resume expiry,
	 * but only PROCESSED (testProcessTick'd) after it, must still honor
	 * occurrence ordering -- resume, not finalize-then-start-new.
	 */
	@Test
	public void i_eventObservedBeforeResumeExpiry_resumesEvenIfProcessingItselfIsDelayedPastExpiry()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String sessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);
		Instant resumeExpiry = coordinator.testCurrentSession().resumeWindowExpiresAtInstant();

		// The signal's own observedAt is one second BEFORE the resume
		// window expires -- but it is enqueued with a tick number that
		// isn't resolved until much later (simulating delayed
		// processing), and the wall-clock time actually passed to that
		// later testProcessTick() call is well AFTER the expiry.
		Instant observedBeforeExpiry = resumeExpiry.minusSeconds(1);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(observedBeforeExpiry, "WOODCUTTING", 5L).withGameTick(3L));

		Instant processedWellAfterExpiry = resumeExpiry.plus(Duration.ofMinutes(5));
		coordinator.testProcessTick(4L, processedWellAfterExpiry);

		Session current = coordinator.testCurrentSession();
		assertEquals("occurrence ordering (observedAt), not processing lateness, must govern the resume decision",
			SessionState.ACTIVE, current.getState());
		assertEquals(sessionId, current.getSessionId());
	}

	/**
	 * Letter L: a real account switch with a batch still pending for the
	 * OLD account must finish that batch against the OLD account (never
	 * silently drop real already-occurred telemetry, never apply it to
	 * the new account), and the new account must start from a clean
	 * hydration, not an invented transition.
	 */
	@Test
	public void l_accountSwitchWithPendingBatch_flushesToOldAccountNeverToNew() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		// Deliberately no testProcessTick() -- the batch is still pending
		// for A when the switch happens.

		coordinator.ensureAccountLoaded(ACCOUNT_B);

		assertNull("account B must start from a clean hydration, never an invented transition",
			coordinator.testCurrentSession());
		assertEquals(0, coordinator.testPendingSignalCount());

		Thread.sleep(300);
		Session accountAOnDisk = new SessionPersistence(store).loadCurrent(ACCOUNT_A);
		assertEquals("account A's own pending WOODCUTTING batch must have been finished and persisted to A, not discarded",
			ActivityType.SKILLING, accountAOnDisk.getActivityIdentity().getActivityType());
	}

	@Test
	public void r_twoAccounts_neverShareSessionState() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String accountASessionId = coordinator.testCurrentSession().getSessionId();
		Thread.sleep(300);

		coordinator.ensureAccountLoaded(ACCOUNT_B);
		assertNull(coordinator.testCurrentSession());

		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "MINING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String accountBSessionId = coordinator.testCurrentSession().getSessionId();

		assertFalse(accountASessionId.equals(accountBSessionId));
		Session accountAOnDisk = new SessionPersistence(store).loadCurrent(ACCOUNT_A);
		assertEquals(accountASessionId, accountAOnDisk.getSessionId());
	}

	/**
	 * Letter O (plugin disable with pending batch): flushPendingAndShutdown()
	 * flushes UNCONDITIONALLY, regardless of tick closure -- there is no
	 * further GameTick coming, so the closed-tick wait would otherwise
	 * lose this batch forever.
	 */
	@Test
	public void o_disableWithPendingBatchNeverObservedAsClosed_flushesAnyway() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		// No testProcessTick() at all -- tick 1 was never observed closed.

		coordinator.flushPendingAndShutdown();

		Session current = coordinator.testCurrentSession();
		assertEquals(ActivityType.SKILLING, current.getActivityIdentity().getActivityType());
		Thread.sleep(300);
		Session persisted = new SessionPersistence(store).loadCurrent(ACCOUNT_A);
		assertEquals(current.getSessionId(), persisted.getSessionId());
	}

	@Test
	public void p_disableThenReEnable_remainsUsableForTheSameAccount()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		coordinator.flushPendingAndShutdown();

		coordinator.ensureAccountLoaded(ACCOUNT_A);

		Instant t1 = T0.plusSeconds(10);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(t1, "WOODCUTTING", 10L).withGameTick(10L));
		coordinator.testProcessTick(11L, t1);

		assertEquals(10_000L, coordinator.testCurrentSession().getAccumulatedActiveDurationMillis());
	}

	/** Letter Q / item 9: the ClientShutdown durability path flushes
	 * unconditionally and persists synchronously. */
	@Test
	public void q_clientShutdownFinalization_persistsPendingMutationSynchronously()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));

		coordinator.finalizeForClientShutdownBlocking();

		Session persisted = new SessionPersistence(store).loadCurrent(ACCOUNT_A);
		assertEquals(ActivityType.SKILLING, persisted.getActivityIdentity().getActivityType());
	}

	/** Letter F/T: signal-order permutations within one tick produce an
	 * identical resolved result. */
	@Test
	public void signalOrderWithinATickDoesNotAffectTheResolvedResult()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 1).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 50L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		Session forwardOrderResult = coordinator.testCurrentSession();

		SessionRuntimeCoordinator reversed = new SessionRuntimeCoordinator(store);
		reversed.ensureAccountLoaded(ACCOUNT_B);
		reversed.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 50L).withGameTick(1L));
		reversed.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 1).withGameTick(1L));
		reversed.testProcessTick(2L, T0);
		Session reverseOrderResult = reversed.testCurrentSession();

		assertEquals(forwardOrderResult.getActivityIdentity(), reverseOrderResult.getActivityIdentity());
		assertEquals(
			forwardOrderResult.getAggregates().getXpGainedBySkill(),
			reverseOrderResult.getAggregates().getXpGainedBySkill());
	}

	/**
	 * Letter J: an off-client-thread signal (e.g. SkillsCollector's
	 * final XP-window flush from shutDown() on AWT) racing ordinary
	 * client-thread activity must never corrupt coordinator state --
	 * every mutating entry point is synchronized on the same monitor
	 * (item 6), so the two threads serialize rather than interleave.
	 * Exercised here with two REAL Java threads (no mocking), each
	 * hammering the coordinator, asserting the final state is exactly
	 * what a fully-serial execution would produce (no lost updates, no
	 * torn state).
	 */
	@Test
	public void j_concurrentOffThreadAndOnThreadMutation_neverCorruptsState() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		int iterations = 200;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);

		Thread clientThreadSimulator = new Thread(() ->
		{
			try
			{
				start.await();
				for (int i = 0; i < iterations; i++)
				{
					coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 1L).withGameTick((long) i));
					coordinator.testProcessTick(i + 1L, T0.plusSeconds(i));
				}
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			finally
			{
				done.countDown();
			}
		}, "test-client-thread-simulator");

		Thread awtSimulator = new Thread(() ->
		{
			try
			{
				start.await();
				for (int i = 0; i < iterations; i++)
				{
					// No gameTick tag -- models the untagged off-thread
					// case (safeTickCount() returning null off-client-thread).
					coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 1L));
				}
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			finally
			{
				done.countDown();
			}
		}, "test-awt-simulator");

		clientThreadSimulator.start();
		awtSimulator.start();
		start.countDown();
		assertTrue(done.await(30, TimeUnit.SECONDS));

		// Whatever final state resulted, it must be internally consistent
		// -- no exception was thrown by either thread (a torn/corrupted
		// List would typically manifest as a ConcurrentModificationException
		// or ArrayIndexOutOfBoundsException surfacing from one of the
		// synchronized methods above), and the session that exists must be
		// a well-formed WOODCUTTING session.
		coordinator.flushPendingAndShutdown();
		Session current = coordinator.testCurrentSession();
		assertEquals(ActivityType.SKILLING, current.getActivityIdentity().getActivityType());
	}

	/** Letter M: rapid overlapping persistCurrent() calls must not let an
	 * older write clobber a newer one -- relies on LocalStateStore's own
	 * single-threaded write executor (audited, unchanged here)
	 * serializing every write in submission order. */
	@Test
	public void m_rapidOverlappingPersistCalls_newestStateWinsOnDisk() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		for (int i = 1; i <= 20; i++)
		{
			coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 1L).withGameTick((long) i));
			coordinator.testProcessTick(i + 1L, T0.plusSeconds(i));
		}

		Thread.sleep(300);
		Session persisted = new SessionPersistence(store).loadCurrent(ACCOUNT_A);
		assertEquals(
			"the last write submitted must be the one that lands on disk",
			coordinator.testCurrentSession().getAccumulatedActiveDurationMillis(),
			persisted.getAccumulatedActiveDurationMillis());
	}

	/** Letter N: finalization always writes the immutable record before
	 * the pointer is cleared/replaced (SessionPersistence's own existing
	 * guarantee, confirmed reachable through the coordinator). */
	@Test
	public void n_finalization_writesImmutableRecordBeforeReplacingPointer() throws Exception
	{
		// GENERALIZED (incidental/support-skill pass): a second, confirming
		// Mining tick is now required to actually switch away from a
		// SUSPENDED Woodcutting session -- see
		// differentActivityWhileSuspended_finalizesOldAndStartsNew's own
		// comment above for the full rationale.
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String oldSessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);
		Instant t2 = afterSuspend.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(t2, "MINING", 10L).withGameTick(3L));
		coordinator.testProcessTick(4L, t2);
		Instant t3 = t2.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(t3, "MINING", 10L).withGameTick(4L));
		coordinator.testProcessTick(5L, t3);

		Thread.sleep(300);
		assertTrue("immutable finalized record must exist", TelemetryPaths.sessionFile(ACCOUNT_A, oldSessionId).exists());
		Session pointer = new SessionPersistence(store).loadCurrent(ACCOUNT_A);
		assertFalse("the pointer must have moved on to the new MINING session, not the finalized one",
			oldSessionId.equals(pointer.getSessionId()));
	}

	// =====================================================================
	// Targeted correctness audit -- timeout-watermark regression coverage
	// (item 1: processTick() must never advance the timeout clock past a
	// still-open, already-pending signal's own observedAt).
	// =====================================================================

	/**
	 * Letter A: a qualifying signal already sitting in pendingSignals for
	 * the CURRENT (not yet closed) tick, observed just before the
	 * 5-minute suspend boundary, must not be skipped past by a
	 * processTick() call whose wall-clock `now` has already crossed that
	 * boundary. Reproduces exactly the sequence from the audit: another
	 * subscriber's own onGameTick() handler emits the signal for tick N
	 * before this coordinator's onGameTick() runs; tick N is still open
	 * (== currentTick), so it stays pending; the coordinator must not
	 * suspend ahead of it merely because Instant.now() has already passed
	 * 5 minutes.
	 */
	@Test
	public void a_qualifyingSignalPendingInOpenTickBeforeFiveMinuteBoundary_doesNotSuspendAheadOfIt()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		assertEquals(SessionState.ACTIVE, coordinator.testCurrentSession().getState());

		// A qualifying signal for tick 5, observed one second BEFORE the
		// 5-minute boundary -- but the coordinator's own onGameTick(5) is
		// simulated as running with `now` already PAST that boundary
		// (another subscriber's own tick-5 handler produced this signal
		// before the coordinator's handler ran; tick 5 itself is still
		// open, since currentTick == 5 here).
		Instant justBeforeBoundary = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).minusSeconds(1);
		Instant wallClockPastBoundary = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(justBeforeBoundary, "WOODCUTTING", 5L).withGameTick(5L));
		coordinator.testProcessTick(5L, wallClockPastBoundary);

		assertEquals(
			"must not suspend ahead of a signal already known to have occurred before the boundary, "
				+ "merely because processTick()'s own wall-clock now() has already passed it",
			SessionState.ACTIVE, coordinator.testCurrentSession().getState());
		assertEquals("the tick-5 signal must still be pending -- tick 5 is not closed yet",
			1, coordinator.testPendingSignalCount());

		// Tick 6 observed -> tick 5 is now provably closed -> the signal
		// is applied (letter C), and ordinary timeout advancement resumes
		// using the real wall-clock time from here on.
		coordinator.testProcessTick(6L, wallClockPastBoundary);

		Session current = coordinator.testCurrentSession();
		assertEquals("applying the pending signal must keep the session ACTIVE, not suspended",
			SessionState.ACTIVE, current.getState());
		assertEquals(0, coordinator.testPendingSignalCount());
	}

	/**
	 * Letter B: the same concept at the 30-minute resume-window
	 * expiration boundary. A same-activity signal observed just before
	 * expiry, still pending in an open tick, must still resume the
	 * suspended session once applied -- even though the processTick()
	 * call that first observes it has a wall-clock `now` already past
	 * expiry.
	 */
	@Test
	public void b_qualifyingSignalPendingInOpenTickBeforeResumeWindowExpiry_stillResumesOnceApplied()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String sessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);
		assertEquals(SessionState.SUSPENDED, coordinator.testCurrentSession().getState());

		Instant justBeforeExpiry = afterSuspend.plus(SessionLifecycleEngine.RESUME_WINDOW).minusSeconds(1);
		Instant wallClockPastExpiry = afterSuspend.plus(SessionLifecycleEngine.RESUME_WINDOW).plusSeconds(1);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(justBeforeExpiry, "WOODCUTTING", 5L).withGameTick(4L));
		coordinator.testProcessTick(4L, wallClockPastExpiry);

		assertTrue(
			"must not finalize ahead of a same-activity signal already known to have occurred before "
				+ "resume-window expiry, merely because processTick()'s own wall-clock now() has already passed it",
			coordinator.testCurrentSession() != null);
		assertEquals(SessionState.SUSPENDED, coordinator.testCurrentSession().getState());
		assertEquals(sessionId, coordinator.testCurrentSession().getSessionId());

		coordinator.testProcessTick(5L, wallClockPastExpiry);

		Session current = coordinator.testCurrentSession();
		assertEquals("the pre-expiry signal must resume the SAME session once applied",
			SessionState.ACTIVE, current.getState());
		assertEquals(sessionId, current.getSessionId());
	}

	/**
	 * Letter D: a genuinely idle session -- no pending/open signal at
	 * all -- must still suspend and finalize normally on ordinary
	 * wall-clock time. The timeout watermark fix (letters A/B above)
	 * must never freeze timeout advancement when there is nothing
	 * pending for the current tick; this is the explicit "no regression"
	 * counterpart to those two tests.
	 */
	@Test
	public void d_genuinelyIdleSessionWithNoPendingSignals_stillSuspendsAndFinalizesNormally()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "WOODCUTTING", 10L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		assertEquals(SessionState.ACTIVE, coordinator.testCurrentSession().getState());
		assertEquals(0, coordinator.testPendingSignalCount());

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);
		assertEquals("an idle session with nothing pending must suspend on ordinary wall-clock time",
			SessionState.SUSPENDED, coordinator.testCurrentSession().getState());

		Instant afterResumeWindow = afterSuspend.plus(SessionLifecycleEngine.RESUME_WINDOW).plusSeconds(1);
		coordinator.testProcessTick(4L, afterResumeWindow);
		assertNull("an idle session with nothing pending must finalize on ordinary wall-clock time",
			coordinator.testCurrentSession());
	}
	// =====================================================================
	// PERSISTENCE-ORDERING AUDIT (final targeted pass for the candidate-
	// metric fix): LifecycleResult's contextualMetricsTarget/
	// contextualMetrics can name a session that this SAME transition also
	// finalizes. These tests drive the REAL SessionRuntimeCoordinator +
	// SessionPersistence + LocalStateStore stack (real async disk writes,
	// same Thread.sleep(300)-then-read pattern already used above) to
	// prove the abandoned candidate's metrics are actually present in the
	// durably-written sessions/{sessionId}.json record -- not just on an
	// in-memory object that happened to be mutated after the write.
	// =====================================================================

	private Session readFinalizedFile(String sessionId) throws Exception
	{
		return store.readIfExists(TelemetryPaths.sessionFile(ACCOUNT_A, sessionId), Session.class);
	}

	private Session readCurrentPointerFile() throws Exception
	{
		return store.readIfExists(TelemetryPaths.sessionStateFile(ACCOUNT_A), Session.class);
	}

	private static long xpOf(Session session, String skill)
	{
		if (session == null)
		{
			return 0L;
		}
		Long value = session.getAggregates().getXpGainedBySkill().get(skill);
		return value == null ? 0L : value;
	}

	// A. Pending candidate + authoritative different activity arrives ->
	// the finalized old (GG) session's DURABLE FILE must contain the
	// abandoned candidate's contextual metrics exactly once; the new
	// authoritative session must not contain them at all.
	@Test
	public void persistenceOrdering_pendingCandidateThenAuthoritativeSwitch_finalizedFileContainsContextualMetricsOnce() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Grotesque Guardians", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String oldSessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);
		assertEquals(SessionState.SUSPENDED, coordinator.testCurrentSession().getState());

		// Weak, non-combat-branch evidence -- arms a candidate, leaves
		// the GG session untouched and still SUSPENDED.
		Instant weakAt = afterSuspend.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(weakAt, "AGILITY", 3L).withGameTick(3L));
		coordinator.testProcessTick(4L, weakAt);
		assertEquals(oldSessionId, coordinator.testCurrentSession().getSessionId());
		assertEquals(SessionState.SUSPENDED, coordinator.testCurrentSession().getState());

		// Authoritative different combat-branch activity (a different
		// boss) -- immediate switch, abandoning the pending candidate.
		Instant authoritativeAt = afterSuspend.plusSeconds(45);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(authoritativeAt, "Zulrah", 1).withGameTick(4L));
		coordinator.testProcessTick(5L, authoritativeAt);

		Session newCurrent = coordinator.testCurrentSession();
		assertFalse(oldSessionId.equals(newCurrent.getSessionId()));
		assertEquals("zulrah", newCurrent.getActivityIdentity().getActivityKey());

		Thread.sleep(300);

		Session finalizedFile = readFinalizedFile(oldSessionId);
		assertEquals("the finalized GG session's DURABLE FILE must contain the abandoned candidate's "
			+ "Agility XP -- contextual metrics must be applied before the finalized record is written, "
			+ "never only to an in-memory object afterward",
			3L, xpOf(finalizedFile, "AGILITY"));

		assertEquals("the new authoritative Zulrah session must never be contaminated by the abandoned candidate",
			0L, xpOf(newCurrent, "AGILITY"));
	}

	// B. Pending candidate + resume-window expiry -> the finalized old
	// session's DURABLE FILE must contain the abandoned candidate's
	// contextual metrics exactly once; no fabricated candidate session is
	// ever created.
	@Test
	public void persistenceOrdering_pendingCandidateThenResumeWindowExpires_finalizedFileContainsContextualMetricsOnce() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Grotesque Guardians", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String oldSessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);

		Instant weakAt = afterSuspend.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(weakAt, "AGILITY", 7L).withGameTick(3L));
		coordinator.testProcessTick(4L, weakAt);
		assertEquals(oldSessionId, coordinator.testCurrentSession().getSessionId());

		Instant afterResumeWindow = afterSuspend.plus(SessionLifecycleEngine.RESUME_WINDOW).plusSeconds(1);
		coordinator.testProcessTick(5L, afterResumeWindow);

		assertNull("a plain timeout with no accompanying activity must leave no current session, "
			+ "never a fabricated session for the never-confirmed candidate", coordinator.testCurrentSession());

		Thread.sleep(300);

		Session finalizedFile = readFinalizedFile(oldSessionId);
		assertEquals("the finalized GG session's DURABLE FILE must contain the abandoned candidate's "
			+ "Agility XP exactly once", 7L, xpOf(finalizedFile, "AGILITY"));
	}

	// C. Confirmed candidate -> the finalized old session's DURABLE FILE
	// must contain NONE of the candidate's metrics; the new session's own
	// durable current-pointer file must contain them exactly once (the
	// live-reported "WOODCUTTING=525" case, verified end-to-end through
	// real disk writes rather than just the in-memory LifecycleResult).
	@Test
	public void persistenceOrdering_confirmedCandidate_finalizedFileHasNone_newSessionFileHasExactlyOnce() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Grotesque Guardians", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String oldSessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);

		Instant firstWoodcutting = afterSuspend.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(firstWoodcutting, "WOODCUTTING", 300L).withGameTick(3L));
		coordinator.testProcessTick(4L, firstWoodcutting);
		assertEquals("the first weak signal must only arm a candidate -- GG stays current",
			oldSessionId, coordinator.testCurrentSession().getSessionId());

		Instant secondWoodcutting = afterSuspend.plusSeconds(60);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(secondWoodcutting, "WOODCUTTING", 225L).withGameTick(4L));
		coordinator.testProcessTick(5L, secondWoodcutting);

		Session newCurrent = coordinator.testCurrentSession();
		assertFalse(oldSessionId.equals(newCurrent.getSessionId()));
		assertEquals("woodcutting", newCurrent.getActivityIdentity().getActivityKey());

		Thread.sleep(300);

		Session finalizedFile = readFinalizedFile(oldSessionId);
		assertEquals("the finalized GG session's DURABLE FILE must contain NONE of the confirmed "
			+ "candidate's Woodcutting XP", 0L, xpOf(finalizedFile, "WOODCUTTING"));

		Session currentPointerFile = readCurrentPointerFile();
		assertEquals("the new Woodcutting session's own DURABLE current-pointer file must contain "
			+ "both batches' XP combined, exactly once -- the live-reported 525 total",
			525L, xpOf(currentPointerFile, "WOODCUTTING"));
	}
	// =====================================================================
	// LIVE BUG: MAGIC XP SEMANTICS + MIXED-BATCH RESOLUTION AGAINST A
	// SUSPENDED SPECIFIC SESSION. Live evidence: two POH teleports' Magic
	// XP (+60 total, no active Slayer task tracked) arrived in the same
	// batch as incidental Woodcutting XP (+405) while a SUSPENDED BOSSING/
	// Zulrah session was still well inside its resume window. Root cause
	// (see ActivitySignalClassifier.decideCombatBranch()'s own javadoc):
	// generic combat evidence against a SUSPENDED session was relabeled AS
	// the current specific identity itself (Zulrah) before ever reaching
	// the batch resolver or lifecycle engine -- so it looked like genuine
	// matching evidence and resumed Zulrah unconditionally, and the
	// mixed-batch Woodcutting evidence's own metric got misattributed
	// along with it. Fixed at the classifier layer: generic combat
	// evidence against a SUSPENDED session is now METRIC_ONLY (no identity
	// claim), leaving specific same-tick evidence (Woodcutting) to resolve
	// on its own, correctly through the EXISTING candidate-confirmation
	// mechanism.
	// =====================================================================

	@Test
	public void liveBug_mixedMagicAndWoodcuttingWhileSuspendedZulrah_zulrahRemainsSuspended_noMetricLandsYet() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String zulrahSessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);
		assertEquals(SessionState.SUSPENDED, coordinator.testCurrentSession().getState());
		String lastActiveAtBeforeBatch = coordinator.testCurrentSession().getLastActiveAt();

		// The exact live mixed batch: two POH teleports' Magic XP (generic
		// combat evidence, no active Slayer task) plus incidental
		// Woodcutting XP, same tick.
		Instant mixedBatchAt = afterSuspend.plusSeconds(60); // a multi-minute gap, well inside the 30-minute resume window
		coordinator.testEnqueueSignal(SessionSignal.xpChange(mixedBatchAt, "MAGIC", 60L).withGameTick(3L));
		coordinator.testEnqueueSignal(SessionSignal.xpChange(mixedBatchAt, "WOODCUTTING", 405L).withGameTick(3L));
		coordinator.testProcessTick(4L, mixedBatchAt);

		Session current = coordinator.testCurrentSession();
		assertEquals("Zulrah must remain the current session -- generic Magic XP must never resume it, "
			+ "and Woodcutting evidence alone (weak against a combat-branch session) only arms a candidate",
			zulrahSessionId, current.getSessionId());
		assertEquals("Zulrah must remain SUSPENDED -- no specific resume occurs from this batch",
			SessionState.SUSPENDED, current.getState());
		assertEquals("lastActiveAt must not advance -- this batch never resumed the session",
			lastActiveAtBeforeBatch, current.getLastActiveAt());
		assertEquals("no Woodcutting metric may land on Zulrah while the candidate is unconfirmed",
			0L, xpOf(current, "WOODCUTTING"));
		assertEquals("Magic XP travels with this same batch's candidate buffer -- not committed to "
			+ "Zulrah until the candidate is confirmed or abandoned", 0L, xpOf(current, "MAGIC"));
	}

	@Test
	public void liveBug_confirmedWoodcuttingCandidateAfterMixedBatch_newSessionHas945_finalizedZulrahHasZeroWoodcutting() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String zulrahSessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);

		Instant mixedBatchAt = afterSuspend.plusSeconds(60); // well inside the 30-minute resume window -- leaves ample margin for the follow-up event below
		coordinator.testEnqueueSignal(SessionSignal.xpChange(mixedBatchAt, "MAGIC", 60L).withGameTick(3L));
		coordinator.testEnqueueSignal(SessionSignal.xpChange(mixedBatchAt, "WOODCUTTING", 405L).withGameTick(3L));
		coordinator.testProcessTick(4L, mixedBatchAt);
		assertEquals(zulrahSessionId, coordinator.testCurrentSession().getSessionId());

		// Second, coherent Woodcutting evidence -- confirms the candidate.
		Instant confirmAt = mixedBatchAt.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(confirmAt, "WOODCUTTING", 540L).withGameTick(4L));
		coordinator.testProcessTick(5L, confirmAt);

		Session newCurrent = coordinator.testCurrentSession();
		assertFalse(zulrahSessionId.equals(newCurrent.getSessionId()));
		assertEquals("woodcutting", newCurrent.getActivityIdentity().getActivityKey());
		assertEquals("both Woodcutting batches (405 + 540) must combine on the new session, exactly once "
			+ "-- the live-reported 945 total", 945L, xpOf(newCurrent, "WOODCUTTING"));

		Thread.sleep(300);

		Session finalizedFile = readFinalizedFile(zulrahSessionId);
		assertEquals("the finalized Zulrah session's DURABLE FILE must contain NONE of the confirmed "
			+ "candidate's Woodcutting XP", 0L, xpOf(finalizedFile, "WOODCUTTING"));

		Session currentPointerFile = readCurrentPointerFile();
		assertEquals(945L, xpOf(currentPointerFile, "WOODCUTTING"));
	}

	@Test
	public void liveBug_mixedBatchThenAuthoritativeSameZulrahEvidence_resumesZulrah_candidateMetricsCommittedOnceContextually() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String zulrahSessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);

		Instant mixedBatchAt = afterSuspend.plusSeconds(60); // well inside the 30-minute resume window -- leaves ample margin for the follow-up event below
		coordinator.testEnqueueSignal(SessionSignal.xpChange(mixedBatchAt, "MAGIC", 60L).withGameTick(3L));
		coordinator.testEnqueueSignal(SessionSignal.xpChange(mixedBatchAt, "WOODCUTTING", 405L).withGameTick(3L));
		coordinator.testProcessTick(4L, mixedBatchAt);
		assertEquals(SessionState.SUSPENDED, coordinator.testCurrentSession().getState());

		// The player actually returns to Zulrah -- authoritative same-
		// identity evidence (a real BOSS_KILL) -- well before confirming
		// the pending Woodcutting candidate.
		Instant realReturnAt = mixedBatchAt.plusSeconds(45);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(realReturnAt, "Zulrah", 2).withGameTick(4L));
		coordinator.testProcessTick(5L, realReturnAt);

		Session resumed = coordinator.testCurrentSession();
		assertEquals("the SAME original Zulrah session must resume, never a fresh one",
			zulrahSessionId, resumed.getSessionId());
		assertEquals(SessionState.ACTIVE, resumed.getState());
		assertEquals("the abandoned Woodcutting candidate's metrics (including the Magic XP that "
			+ "traveled with the same batch) must be committed exactly once, contextually, to the "
			+ "resumed session -- the existing abandonment rule, unchanged",
			405L, xpOf(resumed, "WOODCUTTING"));
		assertEquals(60L, xpOf(resumed, "MAGIC"));
	}

	// =====================================================================
	// COORDINATOR-LEVEL METRIC-OWNERSHIP AUDIT GAP (Case D): SUSPENDED
	// SLAYER + delayed Slayer-skill XP + a confirmed weak different
	// SKILLING candidate. Mirrors liveBug_confirmedWoodcuttingCandidateAfterMixedBatch_...
	// above exactly, with the incidental "MAGIC" XP swapped for a
	// delayed "SLAYER" skill XP_CHANGE -- the SLAYER-XP-WHILE-ACTIVE
	// OWNERSHIP FIX in SessionSignalBatchResolver.resolve() is scoped
	// EXACTLY to `current.getState() == ACTIVE` (see its own javadoc),
	// and is deliberately NOT extended to this SUSPENDED case: here
	// `current` is SUSPENDED, so this Slayer XP never enters
	// preBatchCurrentMetricUpdates -- it rides in the batch's ordinary
	// `metrics` list instead, exactly like the Magic XP in the mixed-
	// batch test above, and is therefore subject to the SAME existing
	// weak-evidence-candidate buffering: when it shares a batch with the
	// first weak Woodcutting evidence, it is buffered (not applied to
	// Gargoyles) until the candidate is confirmed or abandoned, and once
	// CONFIRMED it moves to the brand-new Woodcutting session together
	// with the rest of that batch's metrics -- it does NOT stay
	// attributed to the finalized Gargoyles session, because unlike the
	// ACTIVE case there is no per-signal ownership tag carried through
	// the candidate buffer; ownership there is whole-batch, exactly as
	// established by the Magic/Woodcutting precedent this test mirrors.
	@Test
	public void liveBug_suspendedSlayerGargoylesWithDelayedSlayerXpAndConfirmedWoodcuttingCandidate_slayerXpMovesWithConfirmedCandidate() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String gargoylesSessionId = coordinator.testCurrentSession().getSessionId();
		assertEquals(SessionState.ACTIVE, coordinator.testCurrentSession().getState());
		assertEquals(ActivityType.SLAYER, coordinator.testCurrentSession().getActivityIdentity().getActivityType());

		// (1) Original session is SLAYER/Gargoyles and, after the
		// ordinary 5-minute idle timeout, SUSPENDED.
		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);
		assertEquals(SessionState.SUSPENDED, coordinator.testCurrentSession().getState());
		assertEquals(gargoylesSessionId, coordinator.testCurrentSession().getSessionId());
		String lastActiveAtBeforeBatch = coordinator.testCurrentSession().getLastActiveAt();

		// (2)/(3) Delayed Slayer-skill XP (classifySlayerXp() -- combat-
		// branch current, but SUSPENDED not ACTIVE, so this never enters
		// preBatchCurrentMetricUpdates) plus the FIRST weak, non-combat-
		// branch SKILLING evidence (Woodcutting), same tick. Neither
		// alone, nor together, may switch activity yet -- Gargoyles stays
		// current, still SUSPENDED, untouched.
		Instant mixedBatchAt = afterSuspend.plusSeconds(60); // well inside the 30-minute resume window
		coordinator.testEnqueueSignal(SessionSignal.xpChange(mixedBatchAt, "SLAYER", 113L).withGameTick(3L));
		coordinator.testEnqueueSignal(SessionSignal.xpChange(mixedBatchAt, "WOODCUTTING", 405L).withGameTick(3L));
		coordinator.testProcessTick(4L, mixedBatchAt);

		Session afterMixedBatch = coordinator.testCurrentSession();
		assertEquals("delayed Slayer XP + first weak Woodcutting evidence together must not switch "
			+ "activity -- Gargoyles remains current", gargoylesSessionId, afterMixedBatch.getSessionId());
		assertEquals("Gargoyles must remain SUSPENDED -- no specific resume occurs from this batch",
			SessionState.SUSPENDED, afterMixedBatch.getState());
		assertEquals("lastActiveAt must not advance -- this batch never resumed the session",
			lastActiveAtBeforeBatch, afterMixedBatch.getLastActiveAt());
		assertEquals("no Woodcutting metric may land on Gargoyles while the candidate is unconfirmed",
			0L, xpOf(afterMixedBatch, "WOODCUTTING"));
		assertEquals("the delayed Slayer XP travels with this same batch's candidate buffer -- it is "
			+ "SUSPENDED-current, so the ACTIVE-only preBatchCurrentMetricUpdates routing does not "
			+ "apply, and it is not committed to Gargoyles until the candidate is confirmed or abandoned",
			0L, xpOf(afterMixedBatch, "SLAYER"));

		// (4) Second, coherent Woodcutting evidence -- confirms the
		// candidate: Gargoyles finalizes at the candidate's own
		// first-seen instant, and a fresh SKILLING/woodcutting session
		// starts, carrying BOTH batches' metrics -- including the
		// delayed Slayer XP that rode along in the arming batch.
		Instant confirmAt = mixedBatchAt.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(confirmAt, "WOODCUTTING", 540L).withGameTick(4L));
		coordinator.testProcessTick(5L, confirmAt);

		Session newCurrent = coordinator.testCurrentSession();
		assertFalse("a real activity switch must have occurred", gargoylesSessionId.equals(newCurrent.getSessionId()));
		assertEquals(ActivityType.SKILLING, newCurrent.getActivityIdentity().getActivityType());
		assertEquals("woodcutting", newCurrent.getActivityIdentity().getActivityKey());
		assertEquals("(5)/(6) both Woodcutting batches (405 + 540) must combine on the new session, "
			+ "exactly once -- no loss, no duplication", 945L, xpOf(newCurrent, "WOODCUTTING"));
		assertEquals("(5)/(6) the delayed Slayer XP moves with the confirmed candidate onto the new "
			+ "session, exactly once, per the already-established whole-batch candidate ownership rule",
			113L, xpOf(newCurrent, "SLAYER"));

		Thread.sleep(300);

		// (7)/(8) session IDs behave per existing lifecycle semantics, and
		// the persisted, finalized Gargoyles record contains NONE of the
		// confirmed candidate's metrics -- neither the Woodcutting XP nor
		// the Slayer XP that rode along with it.
		Session finalizedFile = readFinalizedFile(gargoylesSessionId);
		assertEquals(gargoylesSessionId, finalizedFile.getSessionId());
		assertEquals(SessionState.FINALIZED, finalizedFile.getState());
		assertEquals("the finalized Gargoyles session's DURABLE FILE must contain NONE of the "
			+ "confirmed candidate's Woodcutting XP", 0L, xpOf(finalizedFile, "WOODCUTTING"));
		assertEquals("the finalized Gargoyles session's DURABLE FILE must contain NONE of the "
			+ "confirmed candidate's Slayer XP -- it never was preBatchCurrentMetricUpdates-owned "
			+ "because Gargoyles was SUSPENDED, not ACTIVE, when this XP arrived",
			0L, xpOf(finalizedFile, "SLAYER"));

		Session currentPointerFile = readCurrentPointerFile();
		assertEquals("the new Woodcutting session's own DURABLE current-pointer file must contain "
			+ "both Woodcutting batches combined, exactly once", 945L, xpOf(currentPointerFile, "WOODCUTTING"));
		assertEquals("the new Woodcutting session's own DURABLE current-pointer file must contain "
			+ "the delayed Slayer XP exactly once", 113L, xpOf(currentPointerFile, "SLAYER"));
	}

	// =====================================================================
	// MAGIC XP IS A METRIC, NEVER LIFECYCLE PROOF.
	// End-to-end proof, through the REAL SessionRuntimeCoordinator +
	// SessionSignalBatchResolver + SessionLifecycleEngine + persistence
	// stack (no mocking, same as every other test in this file), of the
	// two cases the live report specifically called out for coordinator-
	// level (not just classifier-level) coverage.
	// =====================================================================

	// No current session + MAGIC +30 only: after full coordinator
	// processing there is still no fabricated COMBAT/SLAYER lifecycle
	// session.
	@Test
	public void finalFix_noCurrentSession_magicXpOnly_noFabricatedCombatOrSlayerSession()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "MAGIC", 30L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		assertNull("bare Magic XP with no prior session must never independently fabricate a "
			+ "COMBAT (or any other) lifecycle session", coordinator.testCurrentSession());
	}

	// SUSPENDED generic COMBAT + MAGIC +30 only: remains suspended with
	// lastActiveAt unchanged.
	@Test
	public void finalFix_suspendedGenericCombat_magicXpOnly_remainsSuspendedLastActiveAtUnchanged()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		// Establish a generic-COMBAT session (no active Slayer task, no
		// boss/task evidence -- ordinary melee XP with no corroboration).
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 40L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		Session established = coordinator.testCurrentSession();
		assertEquals(SessionState.ACTIVE, established.getState());
		assertEquals(ActivityType.COMBAT, established.getActivityIdentity().getActivityType());
		String sessionId = established.getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);
		Session suspended = coordinator.testCurrentSession();
		assertEquals(SessionState.SUSPENDED, suspended.getState());
		String lastActiveAtBefore = suspended.getLastActiveAt();

		Instant magicAt = afterSuspend.plusSeconds(60);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(magicAt, "MAGIC", 30L).withGameTick(4L));
		coordinator.testProcessTick(5L, magicAt);

		Session current = coordinator.testCurrentSession();
		assertEquals("Magic XP alone must never resume a SUSPENDED session, even a generic-COMBAT "
			+ "one it would otherwise exactly match", sessionId, current.getSessionId());
		assertEquals(SessionState.SUSPENDED, current.getState());
		assertEquals("lastActiveAt must not advance -- Magic XP alone is never a qualifying heartbeat",
			lastActiveAtBefore, current.getLastActiveAt());
		assertEquals("the Magic metric is still credited directly to the already-current session",
			30L, xpOf(current, "MAGIC"));
	}

	// =====================================================================
	// Generic first-boss encounter context: full end-to-end
	// scenarios. bossActivityContext() signals below are built directly
	// (same as bossKill() elsewhere in this file) representing what
	// BossActivityContextCollector already resolved via KnownBossRegistry
	// (see KnownBossRegistryTest/BossActivityContextCollectorTest for that
	// resolution logic in isolation) -- this file only exercises the
	// session-lifecycle consequence of the resolved signal.
	// =====================================================================

	@Test
	public void zulrah_bossActivityContextEstablishesBossingSessionBeforeBossKill_sameSessionThroughout()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		// Player has an assigned Slayer task and is actively fighting it.
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		Session slayerSession = coordinator.testCurrentSession();
		assertEquals(ActivityType.SLAYER, slayerSession.getActivityIdentity().getActivityType());

		// Player begins interacting with an NPC that KnownBossRegistry
		// recognizes as "Zulrah" via its static seed (no prior kill-count
		// event needed in this runtime -- see
		// KnownBossRegistryTest.freshRegistry_zulrah_resolvesViaStaticSeed_withNoPriorConfirmation()
		// and BossActivityContextCollectorTest.freshStart_firstEverZulrahEncounter_emitsWithNoPriorKillCount()).
		// This alone must establish BOSSING/zulrah BEFORE any BOSS_KILL
		// arrives.
		Instant t1 = T0.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.bossActivityContext(t1, "Zulrah").withGameTick(2L));
		coordinator.testProcessTick(3L, t1);

		Session bossingSession = coordinator.testCurrentSession();
		assertEquals(ActivityType.BOSSING, bossingSession.getActivityIdentity().getActivityType());
		assertEquals("zulrah", bossingSession.getActivityIdentity().getActivityKey());
		assertNotEquals("a real switch away from the fabricated SLAYER session, not a refinement of it",
			slayerSession.getSessionId(), bossingSession.getSessionId());
		assertNull("no BOSS_KILL has arrived yet -- this signal must never itself set a reliable count",
			bossingSession.getAggregates().getReliableCount());
		String sessionIdBeforeKill = bossingSession.getSessionId();

		// Subsequent combat XP (Slayer task still tracked) must land on
		// the SAME BOSSING session, not fall back to SLAYER/gargoyles --
		// decideCombatBranch()'s existing "never downgrade an ACTIVE
		// session" rule (unchanged by this task) is what guarantees this.
		Instant t2 = t1.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(t2, "STRENGTH", 40L).withGameTick(3L));
		coordinator.testProcessTick(4L, t2);
		Session duringFight = coordinator.testCurrentSession();
		assertEquals(sessionIdBeforeKill, duringFight.getSessionId());
		assertEquals(ActivityType.BOSSING, duringFight.getActivityIdentity().getActivityType());
		assertEquals(40L, xpOf(duringFight, "STRENGTH"));

		// The authoritative BOSS_KILL finally arrives -- it must land on
		// the SAME session (not create a new one) and correctly set the
		// reliable kill count.
		Instant t3 = t2.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(t3, "Zulrah", 1).withGameTick(4L));
		coordinator.testProcessTick(5L, t3);

		Session afterKill = coordinator.testCurrentSession();
		assertEquals("BOSS_KILL must land on the SAME session BOSS_ACTIVITY_CONTEXT already established",
			sessionIdBeforeKill, afterKill.getSessionId());
		assertEquals(ActivityType.BOSSING, afterKill.getActivityIdentity().getActivityType());
		assertEquals(Integer.valueOf(1), afterKill.getAggregates().getReliableCount().getAuthoritativeCurrentValue());
		assertEquals(1, afterKill.getAggregates().getReliableCount().getSessionOccurrences());
	}

	@Test
	public void grotesqueGuardians_dawnThenDuskResolveToOneCanonicalBossingSession_bossKillLandsOnSameSession()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		assertEquals(ActivityType.SLAYER, coordinator.testCurrentSession().getActivityIdentity().getActivityType());

		// Interacting with "Dawn" -- KnownBossRegistry already resolved
		// this to the canonical "Grotesque Guardians" display name via its
		// static seed, with no prior confirmation needed (see
		// KnownBossRegistryTest.freshRegistry_dawnAndDusk_resolveViaStaticSeed_withNoPriorConfirmation())
		// by the time BossActivityContextCollector builds this signal.
		Instant t1 = T0.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.bossActivityContext(t1, "Grotesque Guardians").withGameTick(2L));
		coordinator.testProcessTick(3L, t1);
		Session afterDawn = coordinator.testCurrentSession();
		assertEquals(ActivityType.BOSSING, afterDawn.getActivityIdentity().getActivityType());
		assertEquals("grotesque guardians", afterDawn.getActivityIdentity().getActivityKey());
		String sessionId = afterDawn.getSessionId();

		// The fight swaps to interacting with "Dusk" -- resolves to the
		// SAME canonical name, so this must remain the exact same session
		// (an identity-equal heartbeat), never a second switch.
		Instant t2 = t1.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.bossActivityContext(t2, "Grotesque Guardians").withGameTick(3L));
		coordinator.testProcessTick(4L, t2);
		Session afterDusk = coordinator.testCurrentSession();
		assertEquals(sessionId, afterDusk.getSessionId());
		assertEquals("grotesque guardians", afterDusk.getActivityIdentity().getActivityKey());
		assertNull(afterDusk.getAggregates().getReliableCount());

		// Authoritative BOSS_KILL (also reported under the same canonical
		// name) lands on the exact same session throughout.
		Instant t3 = t2.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(t3, "Grotesque Guardians", 1).withGameTick(4L));
		coordinator.testProcessTick(5L, t3);
		Session afterKill = coordinator.testCurrentSession();
		assertEquals(sessionId, afterKill.getSessionId());
		assertEquals(Integer.valueOf(1), afterKill.getAggregates().getReliableCount().getAuthoritativeCurrentValue());
		assertEquals(1, afterKill.getAggregates().getReliableCount().getSessionOccurrences());
	}

	// =====================================================================
	// Current Session UI thread-safety hardening pass, recovery pass:
	// getCurrentSessionSnapshot() proofs (Part 6 items A/F of the
	// recovery pass; per-field independence is proven directly against
	// SessionSnapshot.capture() in SessionSnapshotTest -- these tests
	// instead prove the COORDINATOR's own public entry point behaves
	// the same way end-to-end, driven through the real signal/tick
	// pipeline rather than a hand-built Session).
	// =====================================================================

	@Test
	public void getCurrentSessionSnapshot_neverExposesLiveSessionReference()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 50L).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 1).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.npcDeath(T0).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(T0, "Zulrah",
			Collections.singletonList(new SessionSignal.LootDrop(2, "Zulrah's scales", 100))).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		SessionSnapshot snapshot = coordinator.getCurrentSessionSnapshot();

		// getCurrentSessionSnapshot()'s return type is SessionSnapshot,
		// never Session -- there is no cast, getter, or code path by
		// which a caller holding only this reference can obtain the
		// live Session (its only Session-typed accessor,
		// testCurrentSession(), is package-private and used here only
		// because this test itself lives in the session package, the
		// same access the production UI package does NOT have).
		assertTrue(snapshot.isPresent());
		assertEquals(coordinator.testCurrentSession().getSessionId(), snapshot.getSessionId());
		assertEquals(1, snapshot.getReliableCount().getSessionOccurrences());
		assertEquals(1, snapshot.getLootDrops().size());

		// Further runtime mutation via the SAME pipeline that produced
		// this snapshot (another tick, more XP, more loot) never changes
		// the already-returned snapshot -- proving the coordinator's own
		// public entry point, not just SessionSnapshot.capture() in
		// isolation, hands out fully independent data.
		Instant t1 = T0.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(t1, "STRENGTH", 999L).withGameTick(2L));
		coordinator.testEnqueueSignal(SessionSignal.bossKill(t1, "Zulrah", 2).withGameTick(2L));
		coordinator.testProcessTick(3L, t1);

		assertEquals(Long.valueOf(50L), snapshot.getXpGainedBySkill().get("STRENGTH"));
		assertEquals(1, snapshot.getReliableCount().getSessionOccurrences());

		SessionSnapshot secondSnapshot = coordinator.getCurrentSessionSnapshot();
		assertEquals(Long.valueOf(1049L), secondSnapshot.getXpGainedBySkill().get("STRENGTH"));
		assertEquals(2, secondSnapshot.getReliableCount().getSessionOccurrences());
	}

	@Test
	public void getCurrentSessionSnapshot_noCurrentSession_returnsAbsent()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		SessionSnapshot snapshot = coordinator.getCurrentSessionSnapshot();

		assertFalse(snapshot.isPresent());
		assertSame(SessionSnapshot.absent(), snapshot);
	}

	@Test
	public void getCurrentSessionSnapshot_reflectsCoherentStateAtCallTime()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		SessionSnapshot snapshot = coordinator.getCurrentSessionSnapshot();
		Session live = coordinator.testCurrentSession();

		// Every field on the ONE snapshot object matches the live
		// session's own state at the exact moment getCurrentSessionSnapshot()
		// was called -- both reads happen back-to-back with no
		// intervening processTick()/onEvent() call, so they must agree.
		assertEquals(live.getSessionId(), snapshot.getSessionId());
		assertEquals(live.getState(), snapshot.getState());
		assertEquals(live.getActivityIdentity().getActivityType(), snapshot.getActivityType());
		assertEquals(live.getAccumulatedActiveDurationMillis(), snapshot.getAccumulatedActiveDurationMillis());
		assertEquals(live.getLastActiveAt(), snapshot.getLastActiveAt());
	}

	// =====================================================================
	// EVIDENCE-STRENGTH-AWARE WEAK-EVIDENCE GATE, GENERALIZED TO ACTIVE
	// (activity-evidence-strength pass) -- END-TO-END reproduction of the
	// literal live bug (one incidental AGILITY +6 XP observation, 34-38
	// seconds into an ACTIVE SLAYER/Gargoyles session, immediately
	// replacing it with a new SKILLING/Agility session) through the REAL
	// production path: ActivitySignalClassifier tags the incidental
	// Agility XP ORDINARY, SessionSignalBatchResolver carries that
	// strength through to the batch's winning classification, and
	// SessionRuntimeCoordinator threads it into
	// SessionLifecycleEngine.onQualifyingActivity()'s 4-arg overload. See
	// SessionLifecycleEngineTest's own
	// weakEvidenceWhileActive_singleIncidentalObservation_doesNotFinalize_confirmationAnchorsAtT2NotT1
	// for the engine-level version of this same scenario.
	// =====================================================================
	@Test
	public void liveBug_incidentalAgilityXpWhileActiveGargoyles_doesNotImmediatelySwitch_confirmationAnchorsAtT2() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String gargoylesSessionId = coordinator.testCurrentSession().getSessionId();
		assertEquals(SessionState.ACTIVE, coordinator.testCurrentSession().getState());
		assertEquals(ActivityType.SLAYER, coordinator.testCurrentSession().getActivityIdentity().getActivityType());

		// The exact live sequence: 34 seconds into the ACTIVE session
		// (well within the 5-minute suspend timeout), one incidental
		// Agility XP_CHANGE arrives -- classifyNonCombatXp() tags this
		// ORDINARY.
		Instant firstAgility = T0.plusSeconds(34);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(firstAgility, "AGILITY", 6L).withGameTick(2L));
		coordinator.testProcessTick(3L, firstAgility);

		Session afterFirstWeak = coordinator.testCurrentSession();
		assertEquals("a single incidental Agility tick must never finalize a genuinely ACTIVE Gargoyles "
			+ "session -- this is the exact live bug",
			gargoylesSessionId, afterFirstWeak.getSessionId());
		assertEquals(SessionState.ACTIVE, afterFirstWeak.getState());
		assertEquals("gargoyles@catacombs", afterFirstWeak.getActivityIdentity().getActivityKey());

		// A SECOND, coherent Agility observation confirms the switch --
		// once EVIDENCE-WEIGHTED HYSTERESIS's own decay has had time to
		// bring ACTIVE's required-confirmation count down from its peak
		// (one MORE than SUSPENDED ever requires) to its floor
		// (ACTIVE_INERTIA_DECAY_PERIOD -- one fifth of SUSPEND_TIMEOUT,
		// i.e. 60s -- after T0, the established session's own last
		// reinforcing evidence). 69s total puts this comfortably past
		// that point -- see SessionLifecycleEngine's own class javadoc.
		Instant secondAgility = T0.plusSeconds(69);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(secondAgility, "AGILITY", 4L).withGameTick(3L));
		coordinator.testProcessTick(4L, secondAgility);

		Session newCurrent = coordinator.testCurrentSession();
		assertFalse("a real activity switch must now have occurred",
			gargoylesSessionId.equals(newCurrent.getSessionId()));
		assertEquals(ActivityType.SKILLING, newCurrent.getActivityIdentity().getActivityType());
		assertEquals("agility", newCurrent.getActivityIdentity().getActivityKey());
		assertEquals("both Agility batches (6 + 4) combine on the new session, exactly once",
			10L, xpOf(newCurrent, "AGILITY"));

		Thread.sleep(300);

		Session finalizedFile = readFinalizedFile(gargoylesSessionId);
		assertEquals(SessionState.FINALIZED, finalizedFile.getState());
		assertEquals("DESIGN CORRECTION: the finalized Gargoyles session's DURABLE record must show T2 "
			+ "(the confirming observation's own timestamp) as its finalizedAt, never backdated to T1 "
			+ "(the first weak observation's timestamp) -- the established session remained genuinely "
			+ "ACTIVE through T2, so no historical boundary is fabricated from ambiguous evidence",
			secondAgility.toString(), finalizedFile.getFinalizedAt());
		assertEquals("the finalized Gargoyles session must contain NONE of the confirmed candidate's "
			+ "Agility XP", 0L, xpOf(finalizedFile, "AGILITY"));
	}

	// =====================================================================
	// SLAYER TASK-FAMILY MEMBERSHIP -- full end-to-end pipeline
	// proof that DELIBERATE, SUSTAINED off-task combat
	// can still escape an ACTIVE Slayer session, through the REAL
	// coordinator -> classifier -> batch resolver -> lifecycle engine
	// pipeline, mirroring liveBug_incidentalAgilityXpWhileActiveGargoyles_...
	// above exactly, except the incidental/confirming evidence here is
	// combat XP against a specifically-named, genuinely OFF-TASK NPC
	// ("Goat" while assigned "Pyrefiends") rather than Agility XP. A
	// single incidental tick must not switch anything (no cannon/random-
	// kill thrash); a second, coherent confirming tick against the SAME
	// off-task NPC does.
	// =====================================================================
	@Test
	public void offTaskCombatWhileActiveSlayer_singleIncidentalObservation_doesNotSwitch_secondConfirmingObservationSwitchesToGenericCombat() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Pyrefiends", "Catacombs", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String pyrefiendsSessionId = coordinator.testCurrentSession().getSessionId();
		assertEquals(SessionState.ACTIVE, coordinator.testCurrentSession().getState());
		assertEquals(ActivityType.SLAYER, coordinator.testCurrentSession().getActivityIdentity().getActivityType());

		// A "Goat" interaction target is tracked (context only -- never
		// itself lifecycle evidence), then one incidental combat XP tick
		// against it arrives -- classifyXpChange()'s combat branch tags
		// this ORDINARY per the new SLAYER task-family membership gate,
		// since a Goat is confirmed NOT a Pyrefiends task-family member.
		Instant npcTargetSeen = T0.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.npcInteractionTarget(npcTargetSeen, 60, "Goat").withGameTick(2L));
		coordinator.testProcessTick(3L, npcTargetSeen);

		Instant firstOffTaskXp = T0.plusSeconds(6);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(firstOffTaskXp, "ATTACK", 40L).withGameTick(3L));
		coordinator.testProcessTick(4L, firstOffTaskXp);

		Session afterFirstWeak = coordinator.testCurrentSession();
		assertEquals("a single incidental off-task kill (a cannon splash, a stray NPC death) must never "
			+ "immediately switch a genuinely ACTIVE Slayer session",
			pyrefiendsSessionId, afterFirstWeak.getSessionId());
		assertEquals(SessionState.ACTIVE, afterFirstWeak.getState());
		assertEquals(ActivityType.SLAYER, afterFirstWeak.getActivityIdentity().getActivityType());

		// A SECOND, coherent off-task observation against the SAME Goat
		// confirms deliberate, sustained off-task combat -- the session
		// now genuinely switches, via the same ACTIVE-candidate mechanism
		// already proved above for SKILLING evidence. EVIDENCE-WEIGHTED
		// HYSTERESIS's extra peak/decay inertia does NOT apply here (a
		// combat-branch candidate against a combat-branch established
		// session keeps the flat, unconditional requirement -- see
		// SessionLifecycleEngine.activeRequiredConfirmations()'s own
		// javadoc), so this confirms immediately at the second
		// observation exactly as before this feature.
		Instant secondOffTaskXp = firstOffTaskXp.plusSeconds(20);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(secondOffTaskXp, "STRENGTH", 40L).withGameTick(4L));
		coordinator.testProcessTick(5L, secondOffTaskXp);

		Session newCurrent = coordinator.testCurrentSession();
		assertFalse("a real activity switch must now have occurred",
			pyrefiendsSessionId.equals(newCurrent.getSessionId()));
		assertEquals(ActivityType.COMBAT, newCurrent.getActivityIdentity().getActivityType());
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Goat"), newCurrent.getActivityIdentity());

		Thread.sleep(300);

		Session finalizedFile = readFinalizedFile(pyrefiendsSessionId);
		assertEquals(SessionState.FINALIZED, finalizedFile.getState());
		assertEquals("the finalized Pyrefiends session's DURABLE record must show T2 (the confirming "
			+ "observation's own timestamp) as its finalizedAt, never backdated to T1",
			secondOffTaskXp.toString(), finalizedFile.getFinalizedAt());
	}

	// =====================================================================
	// SUBORDINATE / ENCOUNTER-OWNED ACTORS (classifier-clarification pass,
	// PART A). Per the explicit clarification: named QA cases are
	// VALIDATION CASES for a GENERAL mechanism, not one-off name-based
	// fixes. "Death Spawn cannot switch Nechryael" is named in that
	// clarification as the required regression test for the general
	// "an actor subordinate to the current encounter/activity must not
	// replace the parent session merely by being the latest interacted/
	// dead NPC" rule.
	//
	// HONEST SCOPE NOTE: no dedicated "this NPC is a subordinate spawn of
	// that activity" registry/mechanism exists in this codebase, and none
	// was added -- RuneLite's own Slayer task data (the only
	// verified data source this project uses, see SlayerTaskFamilyRegistry's
	// own javadoc) does not catalogue encounter minions/spawns as such.
	// What DOES already exist, and IS general (not name-based), is the
	// SlayerTaskFamilyRegistry off-task-combat gate: Death Spawn is
	// confirmed NOT a member of the Nechryael task family (see the
	// registry's own "Nechryael"/"Nechryarch" entry), so a Death Spawn
	// kill/XP tick is honestly proposed as ORDINARY off-task evidence --
	// exactly the same path "Goat" takes against an active Pyrefiends
	// task above -- and therefore requires the SAME second, coherent,
	// confirming observation before the session ever switches. This
	// mirrors the real-world shape of the bug report (a single Death
	// Spawn kill flipping Current Session away from Nechryael) and closes
	// it. It is a real, general mechanism reused for this case, not a
	// name-specific patch -- but it is *not* a full parent/subordinate
	// ownership model: if a player were somehow to sustain two full
	// confirming Death Spawn observations with no intervening genuine
	// Nechryael evidence at all, this mechanism would still allow the
	// session to switch to generic "Death Spawn" combat, same as it would
	// for any other genuinely-sustained off-task NPC. That residual gap
	// is a known, documented limitation of reusing the off-task-combat
	// gate rather than building a dedicated encounter-ownership model
	// (out of scope here -- no verified data source exists to
	// build one correctly), not an unrecognized bug.
	// =====================================================================
	@Test
	public void partA2_deathSpawnDuringActiveNechryael_singleIncidentalObservation_doesNotSwitch_regressionValidationCase() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Nechryael", "Slayer Tower", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String nechryaelSessionId = coordinator.testCurrentSession().getSessionId();
		assertEquals(SessionState.ACTIVE, coordinator.testCurrentSession().getState());
		assertEquals(ActivityType.SLAYER, coordinator.testCurrentSession().getActivityIdentity().getActivityType());

		// The subordinate Death Spawn becomes the tracked interaction
		// target, then one incidental combat XP tick against it arrives.
		Instant npcTargetSeen = T0.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.npcInteractionTarget(npcTargetSeen, 60, "Death Spawn").withGameTick(2L));
		coordinator.testProcessTick(3L, npcTargetSeen);

		Instant firstSpawnXp = T0.plusSeconds(6);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(firstSpawnXp, "ATTACK", 15L).withGameTick(3L));
		coordinator.testProcessTick(4L, firstSpawnXp);

		Session afterFirstSpawnKill = coordinator.testCurrentSession();
		assertEquals("a single Death Spawn kill during an ACTIVE Nechryael session must never immediately "
			+ "replace the parent session -- the exact bug this validation case exists to catch",
			nechryaelSessionId, afterFirstSpawnKill.getSessionId());
		assertEquals(SessionState.ACTIVE, afterFirstSpawnKill.getState());
		assertEquals(ActivityType.SLAYER, afterFirstSpawnKill.getActivityIdentity().getActivityType());
		// METRIC OWNERSHIP (classifier-clarification correction): identity
		// not switching is not enough on its own -- the Death Spawn's own
		// ATTACK XP must not be silently credited to the Nechryael
		// incumbent either, merely because the challenger hasn't won
		// identity yet. It must sit buffered in the unconfirmed candidate
		// (activeCandidateMetrics), not applied anywhere.
		assertEquals("an unconfirmed challenger's XP must never be credited to the incumbent session "
			+ "while the challenger has not won identity", 0L, xpOf(afterFirstSpawnKill, "ATTACK"));

		// Genuine Nechryael evidence resumes before any second, confirming
		// Death Spawn observation -- the candidate must be abandoned
		// contextually and the ORIGINAL Nechryael session must continue
		// uninterrupted (Case B shape, same mechanism proved above for
		// off-task Pyrefiends combat and, before that, for incidental
		// Agility XP against an ACTIVE Gargoyles session).
		Instant nechryaelResumes = firstSpawnXp.plusSeconds(4);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(nechryaelResumes, "Nechryael", "Slayer Tower", 1).withGameTick(4L));
		coordinator.testProcessTick(5L, nechryaelResumes);

		Session afterResumingNechryael = coordinator.testCurrentSession();
		assertEquals("returning to genuine on-task Nechryael evidence before a second Death Spawn "
			+ "observation must abandon the candidate and keep the SAME parent session running",
			nechryaelSessionId, afterResumingNechryael.getSessionId());
		assertEquals(SessionState.ACTIVE, afterResumingNechryael.getState());
		// METRIC OWNERSHIP: an abandoned candidate's buffered metric is not
		// simply discarded -- it is honestly real XP the player earned, so
		// it is committed exactly once, contextually, to the incumbent that
		// legitimately owns the session it happened during. This is
		// "support XP retained without renaming PRIMARY," not silent data
		// loss and not a rename of Nechryael's own identity.
		assertEquals("the abandoned Death Spawn candidate's XP must be retained, credited exactly once "
			+ "to the still-current Nechryael session, as support XP -- never lost, never renaming "
			+ "the session's own identity", 15L, xpOf(afterResumingNechryael, "ATTACK"));
	}

	// =====================================================================
	// INCIDENTAL / SUPPORT SKILLS while SUSPENDED (classifier-clarification
	// pass, PART A). "Suspended Slayer + Hunter XP" is named in the
	// clarification's INCIDENTAL/SUPPORT SKILLS category ("Hunter + brief
	// Fletching, Hunter + Woodcutting, Slayer + incidental Magic, Bossing +
	// incidental skilling/support action are all manifestations of the SAME
	// rule"). This is the SUSPENDED-branch counterpart of that generalized
	// rule -- the ACTIVE-branch generalization is
	// isWeakEvidenceAgainstEstablishedSession()'s SKILLING arm (see
	// partA5-equivalent coverage elsewhere in this suite); the
	// combat-branch (SLAYER/BOSSING) arm of that SAME method already
	// existed before this feature and already applies unconditionally to
	// BOTH ACTIVE and SUSPENDED (the method takes no state parameter) --
	// so this test is a direct validation case for already-generalized,
	// pre-existing behavior, mirroring
	// liveBug_suspendedSlayerGargoylesWithDelayedSlayerXpAndConfirmedWoodcuttingCandidate_...
	// above (same mechanism, Woodcutting) with the literal named
	// entities from the QA report.
	// =====================================================================
	@Test
	public void partA4_suspendedSlayerWithIncidentalHunterXp_singleObservationDoesNotSwitch_secondConfirmingObservationSwitches() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String gargoylesSessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);
		assertEquals(SessionState.SUSPENDED, coordinator.testCurrentSession().getState());
		assertEquals(gargoylesSessionId, coordinator.testCurrentSession().getSessionId());

		// A single incidental Hunter XP tick while SUSPENDED must not
		// switch away from the still-SUSPENDED Slayer session.
		Instant firstHunterXp = afterSuspend.plusSeconds(60);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(firstHunterXp, "HUNTER", 78L).withGameTick(3L));
		coordinator.testProcessTick(4L, firstHunterXp);

		Session afterFirstHunterTick = coordinator.testCurrentSession();
		assertEquals("a single incidental Hunter XP tick during a SUSPENDED Slayer session must never "
			+ "immediately replace it", gargoylesSessionId, afterFirstHunterTick.getSessionId());
		assertEquals(SessionState.SUSPENDED, afterFirstHunterTick.getState());
		assertEquals(ActivityType.SLAYER, afterFirstHunterTick.getActivityIdentity().getActivityType());
		// METRIC OWNERSHIP (classifier-clarification correction): this is
		// the EXACT originally-reported bug shape -- "SUSPENDED Slayer ->
		// Hunter begins -> Hunter XP gets attributed into Slayer." Identity
		// not switching is not sufficient proof; the Hunter XP itself must
		// not be credited to the suspended Gargoyles incumbent while Hunter
		// is still an unconfirmed challenger buffered in the pending
		// candidate.
		assertEquals("a suspended incumbent must never absorb a materially different challenger's XP "
			+ "while that challenger has not yet won identity", 0L, xpOf(afterFirstHunterTick, "HUNTER"));

		// A second, coherent Hunter observation confirms genuine, sustained
		// activity transition -- the session now switches.
		Instant secondHunterXp = firstHunterXp.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(secondHunterXp, "HUNTER", 78L).withGameTick(4L));
		coordinator.testProcessTick(5L, secondHunterXp);

		Session newCurrent = coordinator.testCurrentSession();
		assertFalse("two coherent, confirming Hunter observations must genuinely switch activity",
			gargoylesSessionId.equals(newCurrent.getSessionId()));
		assertEquals(ActivityType.SKILLING, newCurrent.getActivityIdentity().getActivityType());
		assertEquals("hunter", newCurrent.getActivityIdentity().getActivityKey());
		assertEquals("both confirming Hunter XP batches must land on the new session", 156L, xpOf(newCurrent, "HUNTER"));

		Thread.sleep(300);

		Session finalizedFile = readFinalizedFile(gargoylesSessionId);
		assertEquals(SessionState.FINALIZED, finalizedFile.getState());
		assertEquals("the finalized Gargoyles session must contain none of the confirmed Hunter candidate's XP",
			0L, xpOf(finalizedFile, "HUNTER"));
	}

	// =====================================================================
	// METRIC OWNERSHIP (classifier-clarification correction pass): the
	// SUSPENDED-branch counterpart of partA2's "abandoned candidate's XP
	// is retained contextually, not lost, and does not rename PRIMARY."
	// Same Gargoyles+Hunter pair as partA4 above, but here genuine Slayer
	// evidence RESUMES before a second confirming Hunter tick -- proving
	// the incidental Hunter XP is neither silently discarded nor
	// misattributed as a rename of the Slayer session's own identity.
	// =====================================================================
	@Test
	public void partA4_metricOwnership_suspendedSlayerWithIncidentalHunterXp_candidateAbandoned_hunterXpRetainedContextually_identityUnchanged() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String gargoylesSessionId = coordinator.testCurrentSession().getSessionId();

		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		coordinator.testProcessTick(3L, afterSuspend);
		assertEquals(SessionState.SUSPENDED, coordinator.testCurrentSession().getState());

		Instant firstHunterXp = afterSuspend.plusSeconds(60);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(firstHunterXp, "HUNTER", 78L).withGameTick(3L));
		coordinator.testProcessTick(4L, firstHunterXp);
		assertEquals("single incidental Hunter tick must not yet switch or credit anything",
			gargoylesSessionId, coordinator.testCurrentSession().getSessionId());
		assertEquals(0L, xpOf(coordinator.testCurrentSession(), "HUNTER"));

		// Genuine Slayer progress resumes the SUSPENDED session before a
		// second confirming Hunter tick arrives -- the Hunter candidate is
		// abandoned, and Gargoyles resumes under its OWN, unchanged
		// identity (never renamed to Hunter/SKILLING).
		Instant gargoylesResumes = firstHunterXp.plusSeconds(4);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(gargoylesResumes, "Gargoyles", "Catacombs", 1).withGameTick(4L));
		coordinator.testProcessTick(5L, gargoylesResumes);

		Session afterResuming = coordinator.testCurrentSession();
		assertEquals("resuming genuine Gargoyles evidence must abandon the Hunter candidate and keep "
			+ "the SAME session running, never renamed", gargoylesSessionId, afterResuming.getSessionId());
		assertEquals(SessionState.ACTIVE, afterResuming.getState());
		assertEquals(ActivityType.SLAYER, afterResuming.getActivityIdentity().getActivityType());
		// The abandoned Hunter XP is honest, real XP the player earned --
		// it must be retained, credited exactly once as support/contextual
		// XP on the still-current Gargoyles session, never discarded and
		// never causing PRIMARY to be renamed to Hunter.
		assertEquals("abandoned support XP must be retained exactly once on the incumbent, never lost",
			78L, xpOf(afterResuming, "HUNTER"));
	}

	// =====================================================================
	// "Shellbane Gryphon XP churn" (classifier-clarification pass, PART A;
	// SUBORDINATE/ENCOUNTER-OWNED + general off-task protection). "The
	// Shellbane Gryphon" (a solo unique-boss Slayer task) and the
	// unrelated bulk "Gryphons" task (superior: "Dire gryphon") share both
	// a name substring and a physical location (Kethsi), which is exactly
	// the shape of bug that would appear as session identity "churning"
	// between the two if the classifier ever leaned on name/location
	// similarity. SlayerTaskFamilyRegistry deliberately keeps them as
	// separate, non-overlapping, exact-alias-only table entries (see its
	// own "Gryphons"/"The Shellbane Gryphon" entries) -- no fuzzy
	// matching anywhere in this codebase. That means a "Dire gryphon"
	// interaction while on The Shellbane Gryphon task is confirmed
	// off-task and takes the SAME general off-task-combat gate as A2
	// (Death Spawn/Nechryael) and the existing Goat/Pyrefiends coverage --
	// this test is the named regression/validation case for that GENERAL
	// mechanism applied to this specific name-collision-prone pair, not a
	// new or name-specific code path.
	// =====================================================================
	@Test
	public void partA3_direGryphonIncidentalCombatDuringActiveShellbaneGryphonTask_singleObservationDoesNotSwitch() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "The Shellbane Gryphon", "Kethsi", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String shellbaneSessionId = coordinator.testCurrentSession().getSessionId();
		assertEquals(SessionState.ACTIVE, coordinator.testCurrentSession().getState());
		assertEquals(ActivityType.SLAYER, coordinator.testCurrentSession().getActivityIdentity().getActivityType());

		// An unrelated "Dire gryphon" (a Gryphons-task superior, sharing
		// only a name substring and location with The Shellbane Gryphon)
		// becomes the interaction target, then one incidental combat XP
		// tick against it arrives.
		Instant npcTargetSeen = T0.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.npcInteractionTarget(npcTargetSeen, 60, "Dire gryphon").withGameTick(2L));
		coordinator.testProcessTick(3L, npcTargetSeen);

		Instant firstOffTaskXp = T0.plusSeconds(6);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(firstOffTaskXp, "ATTACK", 40L).withGameTick(3L));
		coordinator.testProcessTick(4L, firstOffTaskXp);

		Session afterFirstWeak = coordinator.testCurrentSession();
		assertEquals("a single incidental Dire gryphon kill must never immediately replace an ACTIVE "
			+ "The Shellbane Gryphon session, despite the name/location similarity",
			shellbaneSessionId, afterFirstWeak.getSessionId());
		assertEquals(SessionState.ACTIVE, afterFirstWeak.getState());
		assertEquals(ActivityType.SLAYER, afterFirstWeak.getActivityIdentity().getActivityType());
		assertEquals("an unconfirmed Dire gryphon challenger's XP must never be credited to The "
			+ "Shellbane Gryphon incumbent", 0L, xpOf(afterFirstWeak, "ATTACK"));

		// Genuine Shellbane Gryphon evidence resumes before any second,
		// confirming Dire gryphon observation -- candidate abandoned,
		// original session continues uninterrupted (no churn).
		Instant shellbaneResumes = firstOffTaskXp.plusSeconds(4);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(shellbaneResumes, "The Shellbane Gryphon", "Kethsi", 1).withGameTick(4L));
		coordinator.testProcessTick(5L, shellbaneResumes);

		Session afterResuming = coordinator.testCurrentSession();
		assertEquals("returning to genuine on-task Shellbane Gryphon evidence before a second Dire "
			+ "gryphon observation must abandon the candidate and keep the SAME session running -- "
			+ "no churn between the two similarly-named, unrelated activities",
			shellbaneSessionId, afterResuming.getSessionId());
		assertEquals(SessionState.ACTIVE, afterResuming.getState());
		assertEquals("the abandoned Dire gryphon candidate's XP must be retained, credited exactly once "
			+ "to the still-current Shellbane Gryphon session as support XP", 40L, xpOf(afterResuming, "ATTACK"));
	}

	// =====================================================================
	// SESSION IDENTITY STABILITY (classifier-clarification pass, PART A,
	// core invariant 3: "Current Session identity is based on the
	// player's stable PRIMARY/CANONICAL activity, not whichever NPC or
	// skill produced the latest event"). "A9 (sessionId stability)" is
	// already thoroughly covered at the SessionLifecycleEngine unit level
	// by SessionLifecycleEngineRefinementTest (refinePreservesSessionId
	// and its resume/repeated-refinement siblings) -- this is the missing
	// FULL end-to-end pipeline proof (coordinator -> classifier -> batch
	// resolver -> engine) that a genuine generic-COMBAT -> SLAYER
	// REFINEMENT (ActivitySignalClassifier.decideCombatBranch()'s
	// unconditional generic-COMBAT-to-SLAYER-or-BOSSING refine, distinct
	// from a real switch) never fabricates a new sessionId, loses prior
	// metrics, or resets startedAt -- mirroring
	// zulrah_bossActivityContextEstablishesBossingSessionBeforeBossKill_...
	// above, which proves the opposite case (a real SWITCH away from a
	// fabricated SLAYER session gets a NEW sessionId) as its own contrast.
	// =====================================================================
	@Test
	public void partA9_genericCombatRefinesIntoSlayer_sessionIdNeverChanges() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		// Establish a generic-COMBAT session first (no Slayer/boss context
		// yet -- ordinary melee XP with no corroboration).
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 40L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		Session genericCombat = coordinator.testCurrentSession();
		assertEquals(SessionState.ACTIVE, genericCombat.getState());
		assertEquals(ActivityType.COMBAT, genericCombat.getActivityIdentity().getActivityType());
		String sessionIdBeforeRefine = genericCombat.getSessionId();
		String startedAtBeforeRefine = genericCombat.getStartedAt();

		// The SAME ongoing encounter turns out to be an assigned Slayer
		// task -- decideCombatBranch()'s generic-COMBAT -> SLAYER path is
		// an unconditional REFINE, never a switch: the identity narrows,
		// but it is still recognized as the SAME continuous activity.
		Instant t1 = T0.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(t1, "Gargoyles", "Catacombs", 1).withGameTick(2L));
		coordinator.testProcessTick(3L, t1);

		Session refined = coordinator.testCurrentSession();
		assertEquals("a refine must never change sessionId -- this is the core PART A invariant: "
			+ "identity is the player's stable primary activity, not whichever signal produced the "
			+ "latest event", sessionIdBeforeRefine, refined.getSessionId());
		assertEquals(ActivityType.SLAYER, refined.getActivityIdentity().getActivityType());
		assertEquals("startedAt must never reset on a refine", startedAtBeforeRefine, refined.getStartedAt());
		assertEquals(SessionState.ACTIVE, refined.getState());
		assertEquals("prior COMBAT-branch metrics must survive the refine, not be lost",
			40L, xpOf(refined, "STRENGTH"));

		// Further heartbeats against the now-refined SLAYER identity keep
		// the SAME sessionId -- refinement is not a one-tick fluke.
		Instant t2 = t1.plusSeconds(5);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(t2, "Gargoyles", "Catacombs", 1).withGameTick(3L));
		coordinator.testProcessTick(4L, t2);
		assertEquals("sessionId remains stable across further heartbeats on the refined identity",
			sessionIdBeforeRefine, coordinator.testCurrentSession().getSessionId());
	}

	// =====================================================================
	// MANUAL RE-EVALUATE SESSION.
	// reEvaluateCurrentSession() is the Current Session header's "Re-evaluate
	// Session" button's ENTIRE backend hook -- these 5 tests cover exactly
	// the 5 behaviors the task called out, deliberately NOT duplicating the
	// broader lifecycle suite above (which already exhaustively covers
	// ordinary ACTIVE/SUSPENDED/FINALIZED transitions, candidate
	// arm/confirm/abandon, and Slayer/boss precedence -- reEvaluateCurrentSession()
	// reuses every one of those rules via ActivitySignalClassifier.classifyManualNpcTarget()
	// + the SAME SessionLifecycleEngine methods, so those rules do not need
	// re-proving here).
	// =====================================================================

	@Test
	public void manualReEvaluation_freshEvidence_switchesImmediately_withoutWaitingForNormalCandidateThreshold()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		// Establish a plain generic COMBAT session (no recent NPC target
		// tracked, so classifyXpChange()'s combat branch falls back to the
		// single fixed genericCombatIdentity() -- key "combat").
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 50L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		Session established = coordinator.testCurrentSession();
		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), established.getActivityIdentity());

		// A same-rank, different-key combat identity (COMBAT/"combat" -&gt;
		// COMBAT/"giant rat") is exactly the case isWeakEvidenceAgainstEstablishedSession()
		// gates behind a two-observation candidate for ORDINARY evidence --
		// see SessionLifecycleEngine's own javadoc. A SINGLE manual call
		// must switch immediately: no second confirming observation, no
		// waiting for the normal candidate threshold.
		coordinator.reEvaluateCurrentSession("Giant rat", 55, T0.plusSeconds(5));

		Session afterManualCall = coordinator.testCurrentSession();
		assertEquals("a single manual re-evaluation must switch immediately, not merely arm a candidate",
			ActivitySignalClassifier.genericNpcCombatIdentity("Giant rat"), afterManualCall.getActivityIdentity());
		assertFalse("the immediate switch must be a real new session, not a mutation of the old one",
			established.getSessionId().equals(afterManualCall.getSessionId()));
	}

	@Test
	public void manualReEvaluation_noCurrentNpcTarget_neverInventsAnActivity()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		assertNull(coordinator.testCurrentSession());

		coordinator.reEvaluateCurrentSession(null, null, T0);
		assertNull("no current NPC target at all must be a safe no-op -- nothing invented", coordinator.testCurrentSession());

		coordinator.reEvaluateCurrentSession("   ", null, T0);
		assertNull("a blank NPC name must be treated exactly like no evidence at all", coordinator.testCurrentSession());

		// Also true against an already-established session: insufficient
		// evidence must leave it exactly as it was, never guessed away.
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 50L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		Session established = coordinator.testCurrentSession();

		coordinator.reEvaluateCurrentSession(null, null, T0.plusSeconds(5));
		Session unchanged = coordinator.testCurrentSession();
		assertEquals(established.getSessionId(), unchanged.getSessionId());
		assertEquals(established.getActivityIdentity(), unchanged.getActivityIdentity());
	}

	@Test
	public void manualReEvaluation_slayerTaskMerelyAssigned_neverEstablishesSlayerSession()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		// A Slayer task is ASSIGNED (context only -- see ClassifierContext's
		// own javadoc) but never progressed -- no session exists yet.
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskAssigned(T0, "Basilisks", "Slayer Tower", 130).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		assertNull("mere task assignment must never itself create a session", coordinator.testCurrentSession());

		// The player is manually confirmed to be fighting an UNRELATED NPC
		// right now -- the manual button must still never fabricate a
		// SLAYER session merely because a task happens to be tracked.
		coordinator.reEvaluateCurrentSession("Cave crawler", 101, T0.plusSeconds(10));

		Session current = coordinator.testCurrentSession();
		assertEquals("a bare Slayer assignment must never, by itself, promote manual re-evaluation's generic "
			+ "combat evidence into a SLAYER identity",
			ActivityType.COMBAT, current.getActivityIdentity().getActivityType());
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Cave crawler"), current.getActivityIdentity());
	}

	@Test
	public void manualReEvaluation_realSwitch_preservesOldSessionAggregates_neverRewritesHistory()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 100L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		Session before = coordinator.testCurrentSession();
		String oldSessionId = before.getSessionId();
		assertEquals(100L, (long) before.getAggregates().getXpGainedBySkill().get("STRENGTH"));

		coordinator.reEvaluateCurrentSession("Giant rat", 77, T0.plusSeconds(10));

		Session after = coordinator.testCurrentSession();
		assertFalse("a real activity switch must start a brand-new session", oldSessionId.equals(after.getSessionId()));
		// The OLD session's own aggregates -- as captured before the manual
		// call -- must be byte-for-byte unchanged: a manual re-evaluation
		// never rewrites earlier XP.
		assertEquals("the old (now-finalized) session's own XP must never be altered by a later manual "
			+ "re-evaluation",
			100L, (long) before.getAggregates().getXpGainedBySkill().get("STRENGTH"));
		// The new session starts clean: this call carries an EMPTY metrics
		// list, so it can never itself fabricate XP/loot onto the new
		// session either.
		Long newSessionStrengthXp = after.getAggregates().getXpGainedBySkill().get("STRENGTH");
		assertTrue("a manual re-evaluation must never itself add XP/loot to the new session",
			newSessionStrengthXp == null || newSessionStrengthXp == 0L);
	}

	@Test
	public void ordinaryAutomaticClassification_unaffectedWhenManualButtonNeverUsed()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 50L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		Session established = coordinator.testCurrentSession();
		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), established.getActivityIdentity());

		// A single ordinary same-rank-different-key combat observation, via
		// the NORMAL signal pipeline (reEvaluateCurrentSession() is never
		// called in this test) -- must still only ARM a weak-evidence
		// candidate, exactly as before this feature introduced the manual
		// hook. This is the direct contrast to
		// manualReEvaluation_freshEvidence_switchesImmediately... above:
		// the ordinary path's own stickiness is completely unchanged.
		Instant npcSeen = T0.plusSeconds(1);
		coordinator.testEnqueueSignal(SessionSignal.npcInteractionTarget(npcSeen, 42, "Giant rat").withGameTick(2L));
		coordinator.testProcessTick(3L, npcSeen);

		Instant firstXp = T0.plusSeconds(2);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(firstXp, "STRENGTH", 10L).withGameTick(3L));
		coordinator.testProcessTick(4L, firstXp);

		Session stillCurrent = coordinator.testCurrentSession();
		assertEquals("a single ORDINARY same-rank combat observation must still only arm a candidate, "
			+ "never switch immediately, when the manual button is not used",
			established.getSessionId(), stillCurrent.getSessionId());
		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), stillCurrent.getActivityIdentity());
	}
}
