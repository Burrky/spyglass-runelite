package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.model.QuestsState;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;

/**
 * Quest state has no push event — polled on a slow cadence (driven by
 * the plugin's GameTick counter) rather than every tick, since
 * iterating 200+ quests every 0.6s is needless work for data that
 * changes rarely.
 */
public class QuestCollector
{
	/**
	 * Pure, Client-independent transition detection — factored out so
	 * it's unit-testable without mocking RuneLite's Client. Returns
	 * the quests that transitioned INTO FINISHED between two
	 * snapshots. previous == null (first-ever check, e.g. right after
	 * login or right after an account switch) must never be treated
	 * as "everything just finished" — that would fabricate history —
	 * so it always returns empty in that case.
	 */
	static List<Quest> justFinished(Map<Quest, QuestState> previous, Map<Quest, QuestState> current)
	{
		List<Quest> result = new ArrayList<>();
		if (previous == null)
		{
			return result;
		}
		for (Map.Entry<Quest, QuestState> entry : current.entrySet())
		{
			QuestState oldState = previous.get(entry.getKey());
			if (entry.getValue() == QuestState.FINISHED && oldState != QuestState.FINISHED)
			{
				result.add(entry.getKey());
			}
		}
		return result;
	}

	private final Client client;
	private final LocalStateStore store;
	private final EventLedger eventLedger;
	private final OsrsTelemetryConfig config;

	private Map<Quest, QuestState> lastKnown = null;

	@Inject
	public QuestCollector(Client client, LocalStateStore store, EventLedger eventLedger, OsrsTelemetryConfig config)
	{
		this.client = client;
		this.store = store;
		this.eventLedger = eventLedger;
		this.config = config;
	}

	/**
	 * Called on an account switch, before the new account's first
	 * checkAndFlush(). Clearing lastKnown to null makes the very next
	 * check a silent reseed (see justFinished()) instead of comparing
	 * account B's quest list against account A's leftover snapshot,
	 * which would misreport every already-completed quest on account
	 * B as newly finished.
	 */
	public void resetForAccountSwitch()
	{
		lastKnown = null;
	}

	public void checkAndFlush()
	{
		if (!config.collectQuests())
		{
			return;
		}

		Map<Quest, QuestState> current = readCurrent();

		if (Objects.equals(current, lastKnown))
		{
			return;
		}

		for (Quest finished : justFinished(lastKnown, current))
		{
			eventLedger.append(
				client.getAccountHash(),
				EventType.QUEST_COMPLETED,
				new EventPayloads.QuestCompleted(finished.getId(), finished.name(), finished.getName())
			);
		}

		lastKnown = current;
		writeState(current);
	}

	/**
	 * Called from OsrsTelemetryPlugin's handleLogin() INSTEAD of
	 * checkAndFlush(). Root cause this replaces: Quest.getState(client)
	 * read immediately on the GameStateChanged(LOGGED_IN) callback is
	 * not reliably synced yet for every quest (confirmed via real
	 * telemetry forensics — a burst of exactly
	 * account-total-FINISHED-count QUEST_COMPLETED events fired ~30s
	 * after every login, timed to the plugin's own
	 * QUEST_CHECK_INTERVAL_TICKS periodic cadence, which is only
	 * possible if the login-time read captured wrong/incomplete state).
	 *
	 * checkAndFlush() conflates three things: (A) persist current state
	 * to disk, (B) establish the diff baseline (lastKnown), (C) compare
	 * and emit events. Calling it at login risked doing all three from
	 * a read that might not be settled — (B) done wrong is what caused
	 * the flood, since the correct data 30s later would then look like
	 * hundreds of genuine transitions against a wrong baseline.
	 *
	 * This method does ONLY (A): it persists whatever is read right
	 * now (freshness — quests.json should not sit stale/absent for the
	 * ~30s until the first periodic check), and deliberately does NOT
	 * touch lastKnown. That leaves (B)+(C) to the plugin's EXISTING
	 * periodic checkAndFlush() cadence (GameTick-driven,
	 * QUEST_CHECK_INTERVAL_TICKS, unchanged) — by construction it is
	 * a proper "second read," and by then RuneLite's client-side quest
	 * data has always been fully synced (this is exactly the read that
	 * produced the CORRECT 184-quest state in the forensic evidence).
	 * Since lastKnown is still null at that point, checkAndFlush()'s
	 * own justFinished(null, ...) guard makes that periodic read a
	 * silent reseed — zero events — before ever comparing against a
	 * live baseline. No new/arbitrary tick delay was introduced: this
	 * reuses the plugin's own already-proven-sufficient cadence.
	 *
	 * Mirrors SlayerCollector.seedSilently()'s existing, already-correct
	 * separation of "persist/seed" from "compare and emit."
	 */
	public void captureInitialStateWithoutBaseline()
	{
		if (!config.collectQuests())
		{
			return;
		}

		writeState(readCurrent());
		// Deliberately no `lastKnown = current` here — see javadoc above.
	}

	private Map<Quest, QuestState> readCurrent()
	{
		Map<Quest, QuestState> current = new LinkedHashMap<>();
		for (Quest quest : Quest.values())
		{
			current.put(quest, quest.getState(client));
		}
		return current;
	}

	private void writeState(Map<Quest, QuestState> current)
	{
		QuestsState state = new QuestsState();
		for (Map.Entry<Quest, QuestState> entry : current.entrySet())
		{
			Quest quest = entry.getKey();
			state.getQuestsByKey().put(
				quest.name(),
				new QuestsState.QuestEntry(quest.getId(), quest.name(), quest.getName(), entry.getValue().name())
			);
		}
		state.setLastUpdated(Instant.now().toString());
		store.write(TelemetryPaths.stateFile(client.getAccountHash(), "quests"), state);
	}
}
