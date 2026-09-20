package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.model.ActivityState;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Deliberately generic, not a per-boss enum.
 *
 * A "completion count" message alone does not by itself imply
 * RAID_COMPLETION. Only the three verified raids
 * (Chambers of Xeric, Theatre of Blood, Tombs of Amascut) get that
 * event type; the Corrupted Gauntlet, minigames, and anything else
 * that happens to print a completion-count line get the generic
 * ACTIVITY_COMPLETION type instead. RAID_IDS is intentionally a small,
 * explicit allowlist rather than a guess at what "sounds like" a raid.
 */
public class ActivityKillCountCollector
{
	private static final Pattern BOSS_KC =
		Pattern.compile("Your (.+?) kill count is:? (\\d[\\d,]*)\\.?");

	private static final Pattern COMPLETION_COUNT =
		Pattern.compile("Your (?:completed )?(.+?) (?:completion )?count is:? (\\d[\\d,]*)\\.?");

	/**
	 * Slugs (see slugify()) for the only three activities this
	 * collector will ever label RAID_COMPLETION. VERIFY: exact
	 * in-game wording for each (e.g. Challenge Mode variants) may
	 * slugify differently than assumed here — check against real
	 * completion messages during manual testing and extend this set
	 * rather than loosening the classification logic itself.
	 */
	static final Set<String> RAID_IDS = new HashSet<>();
	static
	{
		RAID_IDS.add("chambers_of_xeric");
		RAID_IDS.add("theatre_of_blood");
		RAID_IDS.add("tombs_of_amascut");
	}

	private final Client client;
	private final LocalStateStore store;
	private final EventLedger eventLedger;
	private final OsrsTelemetryConfig config;
	private final KnownBossRegistry knownBossRegistry;

	/**
	 * eventId uniqueness only protects against uploading the same event
	 * twice — it does nothing to
	 * stop this collector from OBSERVING and emitting the same real
	 * game event twice in the first place (e.g. two chat messages
	 * with an identical count due to a client redraw, or genuinely
	 * re-processing the same message). Tracked per accountHash so one
	 * account's kill counts never suppress another's, and cleared on
	 * account switch (see resetForAccountSwitch()).
	 */
	private final Map<String, Integer> lastObservedCount = new ConcurrentHashMap<>();

	@Inject
	public ActivityKillCountCollector(Client client, LocalStateStore store, EventLedger eventLedger, OsrsTelemetryConfig config, KnownBossRegistry knownBossRegistry)
	{
		this.client = client;
		this.store = store;
		this.eventLedger = eventLedger;
		this.config = config;
		this.knownBossRegistry = knownBossRegistry;
	}

	public void resetForAccountSwitch()
	{
		lastObservedCount.clear();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.collectBossKc())
		{
			return;
		}
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());

		Matcher bossMatch = BOSS_KC.matcher(message);
		if (bossMatch.find())
		{
			recordKillCount(bossMatch.group(1), parseCount(bossMatch.group(2)));
			return;
		}

		Matcher completionMatch = COMPLETION_COUNT.matcher(message);
		if (completionMatch.find())
		{
			recordCompletionCount(completionMatch.group(1), parseCount(completionMatch.group(2)));
		}
	}

	/** Pure — unit-testable without a Client. Stable and idempotent:
	 * the same display name always slugifies to the same activityId,
	 * so re-observing the same message (e.g. after a client restart)
	 * updates the same activity file rather than creating a duplicate. */
	static String slugify(String displayName)
	{
		return displayName.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_");
	}

	/** Pure — unit-testable without a Client. */
	static EventType classifyCompletion(String activityId)
	{
		return RAID_IDS.contains(activityId) ? EventType.RAID_COMPLETION : EventType.ACTIVITY_COMPLETION;
	}

	private int parseCount(String raw)
	{
		return Integer.parseInt(raw.replace(",", ""));
	}

	private void recordKillCount(String rawDisplayName, int count)
	{
		String displayName = rawDisplayName.trim();
		String activityId = slugify(displayName);

		// Task 5: recorded BEFORE the countAdvanced() early-return below --
		// a real, authoritative kill-count message confirms this boss name
		// for BossActivityContextCollector's resolution regardless of
		// whether the count itself advanced (e.g. a duplicate/redraw
		// re-observation of the same message is still genuine confirmation
		// that this boss name is real for this account, even though it is
		// not new forward progress for the KC state file/BOSS_KILL event
		// below). See KnownBossRegistry's own javadoc.
		knownBossRegistry.recordConfirmedBoss(client.getAccountHash(), displayName);

		if (!countAdvanced(activityId, count))
		{
			return;
		}

		writeState(activityId, displayName, count);
		eventLedger.append(
			client.getAccountHash(),
			EventType.BOSS_KILL,
			new EventPayloads.BossKill(activityId, displayName, count, "AUTHORITATIVE")
		);
	}

	private void recordCompletionCount(String rawDisplayName, int count)
	{
		String displayName = rawDisplayName.trim();
		String activityId = slugify(displayName);

		if (!countAdvanced(activityId, count))
		{
			return;
		}

		writeState(activityId, displayName, count);

		EventType eventType = classifyCompletion(activityId);
		Object payload = eventType == EventType.RAID_COMPLETION
			? new EventPayloads.RaidCompletion(activityId, displayName, count, "AUTHORITATIVE")
			: new EventPayloads.ActivityCompletion(activityId, displayName, count, "AUTHORITATIVE");

		eventLedger.append(client.getAccountHash(), eventType, payload);
	}

	/**
	 * @return true if this observation represents real forward
	 * progress (count strictly greater than the last one seen for
	 * this activity) and should therefore produce state/event output.
	 * A duplicate or non-advancing observation (same count seen
	 * again, or a stale/out-of-order lower count) is silently ignored
	 * — not written, not emitted.
	 */
	private boolean countAdvanced(String activityId, int count)
	{
		String key = client.getAccountHash() + ":" + activityId;
		Integer previous = lastObservedCount.get(key);
		if (!isAdvancing(previous, count))
		{
			return false;
		}
		lastObservedCount.put(key, count);
		return true;
	}

	/** Pure — unit-testable without a Client. previous == null means
	 * "never seen before", which always counts as advancing (it's new
	 * information, not a duplicate). */
	static boolean isAdvancing(Integer previous, int newCount)
	{
		return previous == null || newCount > previous;
	}

	private void writeState(String activityId, String displayName, int count)
	{
		ActivityState state = new ActivityState(activityId);
		state.setDisplayName(displayName);
		state.setSource(ActivityState.Source.AUTHORITATIVE);
		state.setKillCount(count);
		state.setLastUpdatedAt(Instant.now().toString());

		store.write(
			TelemetryPaths.activityFile(client.getAccountHash(), activityId),
			state
		);
	}
}
