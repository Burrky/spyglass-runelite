package com.osrstelemetry.plugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.osrstelemetry.plugin.account.ChargeKnowledge;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;

/**
 * Teleport charges and daily uses as the game reports them (canonical §28.13): Check messages, the message after a use,
 * the daily counts. Read only (nothing is clicked or checked for the player); saved per RuneScape profile so a check done
 * yesterday or before a restart still counts (daily counts restart at 00:00 UTC). Client thread.
 */
@Slf4j
@Singleton
public class ChargeObserver
{
	static final String GROUP = "osrstelemetry";
	static final String KEY = "teleportCharges";

	private final Client client;
	private final ConfigManager configManager;
	private final Gson gson;
	private volatile ChargeKnowledge knowledge = ChargeKnowledge.EMPTY;

	@Inject
	public ChargeObserver(Client client, ConfigManager configManager, Gson gson)
	{
		this.client = client;
		this.configManager = configManager;
		this.gson = gson;
	}

	/** The current profile's charges (any thread). */
	public ChargeKnowledge knowledge()
	{
		return knowledge;
	}

	public void load()
	{
		ChargeKnowledge k = ChargeKnowledge.EMPTY;
		try
		{
			String json = configManager.getRSProfileConfiguration(GROUP, KEY);
			if (json != null && !json.isEmpty())
			{
				Map<String, long[]> saved = gson.fromJson(json, new TypeToken<Map<String, long[]>>()
				{
				}.getType());
				k = ChargeKnowledge.fromSaved(saved);
			}
		}
		catch (RuntimeException e)
		{
			log.debug("teleport charges not readable", e);
		}
		knowledge = k;
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged e)
	{
		load();
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		ChargeKnowledge next = knowledge.withMessage(e.getMessage(), oneWhistleCarried(), System.currentTimeMillis());
		if (next != knowledge)
		{
			knowledge = next;
			try
			{
				configManager.setRSProfileConfiguration(GROUP, KEY, gson.toJson(next.toSaved()));
			}
			catch (RuntimeException ex)
			{
				log.debug("teleport charges not saved", ex);
			}
		}
	}

	/** The whistle message names no tier: counted only when exactly one whistle is carried. */
	private boolean oneWhistleCarried()
	{
		int n = 0;
		for (int id : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			ItemContainer c = client.getItemContainer(id);
			if (c == null)
			{
				continue;
			}
			for (int item : new int[]{ItemID.HG_QUETZALWHISTLE_BASIC, ItemID.HG_QUETZALWHISTLE_ENHANCED, ItemID.HG_QUETZALWHISTLE_PERFECTED})
			{
				n += c.count(item) > 0 ? 1 : 0;
			}
		}
		return n == 1;
	}
}
