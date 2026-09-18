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
 * Two distinct data paths, deliberately not conflated:
 *
 * 1. NEW-ITEM NOTIFICATION (implemented here): the game prints "New
 *    item added to your collection log: <item name>" as a game
 *    message when you receive a log item, live, regardless of whether
 *    you've ever opened the collection log interface.
 *
 *    NOTE: this message's presence depends on the player's in-game
 *    "Collection log - New item"
 *    chat notification setting being turned on. If that setting is
 *    off, this collector observes nothing for that item — and,
 *    consistent with "unknown is not zero" throughout this project,
 *    the ABSENCE of this message must never be read as "no new item
 *    occurred." It only proves a new item occurred when it fires; it
 *    proves nothing when it doesn't.
 *
 *    Message text can carry RuneScape formatting tags (e.g. colour
 *    tags) — stripped via Text.removeTags() before matching, per the
 *    same utility RuneLite's own core plugins use for this.
 *
 * 2. FULL PAGE STATE (still NOT implemented): reconstructing a
 *    complete page (every item on e.g. the "Zulrah" page, obtained or
 *    not, with quantities) requires reading the collection log
 *    widget/script output when the player opens that page in-game. I
 *    still do not have confirmed, current widget/script IDs for this
 *    interface, and am not willing to guess them. RuneLite's own
 *    built-in net.runelite.client.plugins.collectionlog is the
 *    reference implementation to read before building this — that
 *    read has not happened yet; it remains an honest
 *    gap, not a silent one. Unseen/unopened pages must remain
 *    represented as unknown, never as "nothing obtained".
 */
public class CollectionLogCollector
{
	private static final Pattern NEW_ITEM =
		Pattern.compile("New item added to your collection log: (.+)");

	private final Client client;
	private final EventLedger eventLedger;
	private final OsrsTelemetryConfig config;

	@Inject
	public CollectionLogCollector(Client client, EventLedger eventLedger, OsrsTelemetryConfig config)
	{
		this.client = client;
		this.eventLedger = eventLedger;
		this.config = config;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.collectCollectionLog())
		{
			return;
		}
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());
		Matcher matcher = NEW_ITEM.matcher(message);
		if (!matcher.find())
		{
			return;
		}

		String itemName = matcher.group(1).trim();
		// itemId is deliberately null — the chat message doesn't carry
		// one, and resolving name -> ID by guessing would be worse than
		// leaving it unresolved (see EventPayloads.CollectionLogNewItem
		// javadoc). Fill this in once the full-page widget read exists.
		eventLedger.append(
			client.getAccountHash(),
			EventType.COLLECTION_LOG_NEW_ITEM,
			new EventPayloads.CollectionLogNewItem(null, itemName)
		);
	}
}
