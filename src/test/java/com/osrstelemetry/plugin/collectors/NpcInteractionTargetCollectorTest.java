package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * NOTE: written, not run — same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project. Josh's own
 * `.\gradlew.bat clean test` run is what actually executes these.
 *
 * Targets
 * NpcInteractionTargetCollector.isAttackableNpc() and .decide() — the
 * pure, RuneLite-free core of the collector's onInteractingChanged()
 * handler. Mirrors BossActivityContextCollectorTest's exact structure
 * and precedent: a plain HashMap stands in for the production
 * ConcurrentHashMap, no mocking is needed anywhere in this file (no
 * Mockito dependency in this project), and both methods are exercised
 * purely through primitives (String[] / String / long / Map), never
 * through real RuneLite NPCComposition/NPC/Actor instances.
 *
 * This file directly satisfies REQUIRED TEST COVERAGE item 3 ("a
 * non-attackable NPC interaction does not establish generic combat
 * identity") at the collector layer: isAttackableNpc() returning false
 * for banker/shopkeeper/talk-to-style action sets is what stops such
 * NPCs from ever reaching decide() with a non-null attackableNpcName in
 * the first place, and decide() itself never fabricates a name when
 * given null.
 */
public class NpcInteractionTargetCollectorTest
{
	private static final long ACCOUNT_A = 555L;

	// ----------------------------------------------------------------
	// isAttackableNpc()
	// ----------------------------------------------------------------

	@Test
	public void actionsContainingAttack_isAttackable()
	{
		assertTrue(NpcInteractionTargetCollector.isAttackableNpc(new String[] { "Attack", "Examine" }));
	}

	@Test
	public void attackNotFirstElement_stillAttackable()
	{
		assertTrue(NpcInteractionTargetCollector.isAttackableNpc(new String[] { null, "Examine", "Attack" }));
	}

	@Test
	public void bankerStyleActions_bankAndCollect_isNotAttackable()
	{
		assertFalse(NpcInteractionTargetCollector.isAttackableNpc(new String[] { "Bank", "Collect", "Examine" }));
	}

	@Test
	public void shopkeeperStyleActions_tradeAndExchange_isNotAttackable()
	{
		assertFalse(NpcInteractionTargetCollector.isAttackableNpc(new String[] { "Trade", "Exchange", "Examine" }));
	}

	@Test
	public void randomTalkToNpc_isNotAttackable()
	{
		assertFalse(NpcInteractionTargetCollector.isAttackableNpc(new String[] { "Talk-to", "Examine" }));
	}

	@Test
	public void nullActionsArray_isNotAttackable()
	{
		assertFalse(NpcInteractionTargetCollector.isAttackableNpc(null));
	}

	@Test
	public void emptyActionsArray_isNotAttackable()
	{
		assertFalse(NpcInteractionTargetCollector.isAttackableNpc(new String[0]));
	}

	@Test
	public void actionsArrayOfAllNulls_isNotAttackable()
	{
		// Real RuneLite NPCComposition#getActions() arrays commonly
		// contain null slots for unused menu-entry positions.
		assertFalse(NpcInteractionTargetCollector.isAttackableNpc(new String[] { null, null, null, null, null }));
	}

	// ----------------------------------------------------------------
	// decide()
	// ----------------------------------------------------------------

	@Test
	public void sourceNotLocalPlayer_isIgnored_noStateMutation()
	{
		Map<Long, String> tracked = new HashMap<>();

		String result = NpcInteractionTargetCollector.decide(false, "Abyssal spectre", ACCOUNT_A, tracked);

		assertNull(result);
		assertNull(tracked.get(ACCOUNT_A));
	}

	@Test
	public void nonAttackableTarget_nullName_clearsTrackedStateAndEmitsNothing()
	{
		Map<Long, String> tracked = new HashMap<>();
		tracked.put(ACCOUNT_A, "Hill Giant");

		String result = NpcInteractionTargetCollector.decide(true, null, ACCOUNT_A, tracked);

		assertNull(result);
		assertNull(tracked.get(ACCOUNT_A));
	}

	@Test
	public void firstAttackableEncounter_emitsAndTracks()
	{
		Map<Long, String> tracked = new HashMap<>();

		String result = NpcInteractionTargetCollector.decide(true, "Abyssal spectre", ACCOUNT_A, tracked);

		assertEquals("Abyssal spectre", result);
		assertEquals("Abyssal spectre", tracked.get(ACCOUNT_A));
	}

	@Test
	public void repeatedSameName_isDedupedAcrossMultipleCalls()
	{
		Map<Long, String> tracked = new HashMap<>();

		String first = NpcInteractionTargetCollector.decide(true, "Abyssal spectre", ACCOUNT_A, tracked);
		String second = NpcInteractionTargetCollector.decide(true, "Abyssal spectre", ACCOUNT_A, tracked);
		String third = NpcInteractionTargetCollector.decide(true, "Abyssal spectre", ACCOUNT_A, tracked);

		assertEquals("Abyssal spectre", first);
		assertNull(second);
		assertNull(third);
	}

	@Test
	public void reEngagingSameNpcAfterABreak_emitsAgain()
	{
		Map<Long, String> tracked = new HashMap<>();

		String first = NpcInteractionTargetCollector.decide(true, "Hill Giant", ACCOUNT_A, tracked);
		// Interaction ends (target becomes null / non-attackable).
		String duringBreak = NpcInteractionTargetCollector.decide(true, null, ACCOUNT_A, tracked);
		// Player re-engages the SAME NPC.
		String reEngaged = NpcInteractionTargetCollector.decide(true, "Hill Giant", ACCOUNT_A, tracked);

		assertEquals("Hill Giant", first);
		assertNull(duringBreak);
		assertEquals("Hill Giant", reEngaged);
	}

	@Test
	public void switchingToADifferentAttackableNpc_emitsTheNewOne()
	{
		Map<Long, String> tracked = new HashMap<>();

		String first = NpcInteractionTargetCollector.decide(true, "Hill Giant", ACCOUNT_A, tracked);
		String second = NpcInteractionTargetCollector.decide(true, "Moss Giant", ACCOUNT_A, tracked);

		assertEquals("Hill Giant", first);
		assertEquals("Moss Giant", second);
		assertEquals("Moss Giant", tracked.get(ACCOUNT_A));
	}

	@Test
	public void differentAccountsTrackedIndependently()
	{
		Map<Long, String> tracked = new HashMap<>();
		long accountB = 777L;

		String forA = NpcInteractionTargetCollector.decide(true, "Zygomite", ACCOUNT_A, tracked);
		String forB = NpcInteractionTargetCollector.decide(true, "Zygomite", accountB, tracked);
		// Repeating for A alone is still deduped independently of B.
		String forARepeat = NpcInteractionTargetCollector.decide(true, "Zygomite", ACCOUNT_A, tracked);

		assertEquals("Zygomite", forA);
		assertEquals("Zygomite", forB);
		assertNull(forARepeat);
	}
}
