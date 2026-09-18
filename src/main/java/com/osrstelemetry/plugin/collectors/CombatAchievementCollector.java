package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Individual task completion fires live as a chat message and is
 * reliable to detect that way (VERIFY exact current wording — this is
 * the kind of string Jagex has tweaked before). Tags stripped via
 * Text.removeTags() same as the collection log collector.
 *
 * totalPoints and unlockedTier are NOT derivable from this message —
 * they live in the Combat Achievements interface (varbit or widget,
 * not yet verified). Left unimplemented here rather than invented;
 * the completion EVENT below is real and doesn't depend on that gap.
 */
public class CombatAchievementCollector
{
	private static final Pattern TASK_COMPLETED =
		Pattern.compile("Congratulations, you(?:'ve| have) completed an? (\\w+) combat task: (.+)\\.");

	private final Client client;
	private final EventLedger eventLedger;
	private final OsrsTelemetryConfig config;

	@Inject
	public CombatAchievementCollector(Client client, EventLedger eventLedger, OsrsTelemetryConfig config)
	{
		this.client = client;
		this.eventLedger = eventLedger;
		this.config = config;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.collectCombatAchievements())
		{
			return;
		}
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());
		Matcher matcher = TASK_COMPLETED.matcher(message);
		if (!matcher.find())
		{
			return;
		}

		String tier = matcher.group(1);
		String taskName = matcher.group(2).trim();

		eventLedger.append(
			client.getAccountHash(),
			EventType.COMBAT_ACHIEVEMENT_COMPLETED,
			new EventPayloads.CombatAchievementCompleted(tier, taskName)
		);
	}
}
