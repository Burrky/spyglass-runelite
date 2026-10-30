package com.osrstelemetry.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.osrstelemetry.plugin.farming.FarmingPreferences;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;

/**
 * The Farming preferences of the logged-in RuneScape profile (Farming
 * correction pass 2). One value, saved in RuneLite's per-profile config under
 * {@code osrstelemetry.farmingPreferences}, so each account and profile keeps
 * its own and a hop or restart restores it. The Farming page is the only
 * editor; nothing else writes the key. Readers on any thread get an immutable
 * snapshot.
 */
@Slf4j
@Singleton
public class FarmingPreferenceStore
{
	static final String GROUP = "osrstelemetry";
	static final String KEY = "farmingPreferences";

	private final ConfigManager configManager;
	private final Gson gson;
	private volatile FarmingPreferences current = FarmingPreferences.defaults();

	@Inject
	public FarmingPreferenceStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	/** The current profile's preferences (defaults until a profile is known). Never null. */
	public FarmingPreferences get()
	{
		return current;
	}

	/** The player changed a preference on the Farming page: kept for this profile. */
	public void set(FarmingPreferences next)
	{
		if (next == null)
		{
			return;
		}
		current = next.copy();
		try
		{
			configManager.setRSProfileConfiguration(GROUP, KEY, gson.toJson(current));
		}
		catch (RuntimeException e)
		{
			log.debug("Farming preferences not saved (no profile yet)", e);
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged e)
	{
		load();
	}

	/** Reads the current profile's saved value; unreadable or missing reads as the defaults. */
	public void load()
	{
		FarmingPreferences loaded = null;
		try
		{
			String json = configManager.getRSProfileConfiguration(GROUP, KEY);
			loaded = parse(gson, json);
		}
		catch (RuntimeException e)
		{
			log.debug("Farming preferences not readable", e);
		}
		current = loaded == null ? FarmingPreferences.defaults() : loaded;
	}

	static FarmingPreferences parse(Gson gson, String json)
	{
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			FarmingPreferences p = gson.fromJson(json, FarmingPreferences.class);
			return p == null ? null : p.copy();
		}
		catch (JsonParseException | IllegalStateException e)
		{
			return null;
		}
	}
}
