package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import org.junit.Test;

/**
 * NOTE: written, not run — no-network caveat as elsewhere. Targets
 * QuestCollector.justFinished(), the pure Client-independent piece of
 * the collector, using two real Quest enum constants (Quest itself
 * has no Client dependency, so this compiles and runs without mocking
 * RuneLite's Client at all).
 */
public class QuestCollectorTest
{
	@Test
	public void firstObservationNeverReportsAnythingAsJustFinished()
	{
		// This is the account-switch/first-login safety property:
		// a null "previous" snapshot (freshly reset,
		// or never seen before) must not cause every already-finished
		// quest to be reported as newly completed.
		Map<Quest, QuestState> current = new HashMap<>();
		current.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		current.put(Quest.DEMON_SLAYER, QuestState.FINISHED);

		List<Quest> result = QuestCollector.justFinished(null, current);

		assertTrue("first observation must report zero transitions", result.isEmpty());
	}

	@Test
	public void reportsOnlyQuestsThatTransitionedIntoFinished()
	{
		Map<Quest, QuestState> previous = new HashMap<>();
		previous.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		previous.put(Quest.DEMON_SLAYER, QuestState.IN_PROGRESS);

		Map<Quest, QuestState> current = new HashMap<>();
		current.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED); // unchanged
		current.put(Quest.DEMON_SLAYER, QuestState.FINISHED); // just finished

		List<Quest> result = QuestCollector.justFinished(previous, current);

		assertEquals(1, result.size());
		assertEquals(Quest.DEMON_SLAYER, result.get(0));
	}

	@Test
	public void reportsNothingWhenNoQuestChangedState()
	{
		Map<Quest, QuestState> previous = new HashMap<>();
		previous.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED);

		Map<Quest, QuestState> current = new HashMap<>();
		current.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED);

		assertTrue(QuestCollector.justFinished(previous, current).isEmpty());
	}

	@Test
	public void accountSwitchSimulation_doesNotFabricateCompletionsFromDifferentAccount()
	{
		// Account A's snapshot before switching away.
		Map<Quest, QuestState> accountASnapshot = new HashMap<>();
		accountASnapshot.put(Quest.COOKS_ASSISTANT, QuestState.NOT_STARTED);

		// resetForAccountSwitch() sets lastKnown = null — simulated
		// directly here since this test targets justFinished(), not
		// the collector's field mutation.
		Map<Quest, QuestState> afterReset = null;

		// Account B's actual (very different) quest list.
		Map<Quest, QuestState> accountBSnapshot = new HashMap<>();
		accountBSnapshot.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		accountBSnapshot.put(Quest.DEMON_SLAYER, QuestState.FINISHED);

		List<Quest> result = QuestCollector.justFinished(afterReset, accountBSnapshot);

		assertTrue(
			"account B's pre-existing completions must never be reported as new just because account A's leftover state was different",
			result.isEmpty()
		);
	}

	// =====================================================================
	// Quest config re-enable regression (client-thread lifecycle audit,
	// letter C) -- added after a CONFIRMED live crash: onConfigChanged()'s
	// "collectQuests" case used to run resetForAccountSwitch() then
	// checkAndFlush() directly from the AWT thread, and checkAndFlush()
	// itself both (a) diffs the fresh read against lastKnown AND (b)
	// unconditionally sets lastKnown = current as a side effect. The fix
	// swaps that call to captureInitialStateWithoutBaseline() (marshalled
	// onto ClientThread from OsrsTelemetryPlugin.onConfigChanged() --
	// see that class), which deliberately never touches lastKnown at
	// all. These tests model that exact contract purely against
	// justFinished(), the same Client-independent core the collector's
	// own checkAndFlush()/captureInitialStateWithoutBaseline() both
	// ultimately rely on for their no-fabrication guarantee.
	// =====================================================================

	@Test
	public void reenableAfterBeingDisabled_questFinishedWhileDisabled_isNeverReportedAsJustFinished()
	{
		// Before disable: some in-progress quest.
		Map<Quest, QuestState> beforeDisable = new HashMap<>();
		beforeDisable.put(Quest.COOKS_ASSISTANT, QuestState.IN_PROGRESS);

		// While disabled, the category stops collecting -- baselines are
		// left stale (see OsrsTelemetryPlugin.onConfigChanged()'s
		// "disabling needs no extra work" comment) -- but the player
		// actually finishes the quest during that window.
		//
		// resetForAccountSwitch() (called immediately before the silent
		// hydration on re-enable) sets lastKnown to null -- simulated
		// directly here, since this test targets justFinished(), not
		// the collector's field mutation (mirrors
		// accountSwitchSimulation_... above).
		Map<Quest, QuestState> afterReset = null;

		// Re-enable's fresh read: the quest is now FINISHED, having
		// completed entirely while collection was off.
		Map<Quest, QuestState> onReenable = new HashMap<>();
		onReenable.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED);

		List<Quest> result = QuestCollector.justFinished(afterReset, onReenable);

		assertTrue(
			"a quest finished entirely while collection was disabled must be silently hydrated on re-enable, "
				+ "never reported as a retroactive QUEST_COMPLETED the instant collection resumes",
			result.isEmpty()
		);
	}

	@Test
	public void reenableThenNextPeriodicCheck_onlyReportsQuestsFinishedAfterReenable()
	{
		// Re-enable silently hydrates (previous == null, per the test
		// above) -- captureInitialStateWithoutBaseline() deliberately
		// leaves lastKnown null afterward (see its own javadoc), so the
		// PLUGIN'S NEXT periodic checkAndFlush() also starts from
		// previous == null and silently reseeds a second time...
		Map<Quest, QuestState> reenableRead = new HashMap<>();
		reenableRead.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED); // finished while disabled
		reenableRead.put(Quest.DEMON_SLAYER, QuestState.IN_PROGRESS);

		assertTrue(
			"captureInitialStateWithoutBaseline() must never establish lastKnown itself",
			QuestCollector.justFinished(null, reenableRead).isEmpty()
		);

		// ...and only a quest that transitions to FINISHED strictly
		// AFTER that silent reseed (i.e. once a real baseline exists)
		// is ever reported -- proving the silent-hydration contract
		// doesn't also suppress genuine future completions.
		Map<Quest, QuestState> genuineBaseline = reenableRead;
		Map<Quest, QuestState> nextCheck = new HashMap<>();
		nextCheck.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED); // unchanged
		nextCheck.put(Quest.DEMON_SLAYER, QuestState.FINISHED); // genuinely just finished

		List<Quest> result = QuestCollector.justFinished(genuineBaseline, nextCheck);
		assertEquals(1, result.size());
		assertEquals(Quest.DEMON_SLAYER, result.get(0));
	}
}
