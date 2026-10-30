package com.osrstelemetry.plugin;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.farming.FarmingTutorial;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;

/** The Farming tutorial's progress, per RuneScape profile (RuneLite's profile configuration); read from memory, written on change. */
@Slf4j
@Singleton
public class FarmingTutorialStore
{
	static final String GROUP = "osrstelemetry";
	static final String KEY = "farmingTutorial";

	private final ConfigManager configManager;
	private final Gson gson;
	private volatile FarmingTutorial current = FarmingTutorial.fresh();

	@Inject
	public FarmingTutorialStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	public FarmingTutorial get()
	{
		return current;
	}

	public void set(FarmingTutorial next)
	{
		if (next == null)
		{
			return;
		}
		current = next;
		try
		{
			configManager.setRSProfileConfiguration(GROUP, KEY, gson.toJson(next));
		}
		catch (RuntimeException e)
		{
			log.debug("Farming tutorial not saved (no profile yet)", e);
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged e)
	{
		load();
	}

	public void load()
	{
		FarmingTutorial t = null;
		try
		{
			String json = configManager.getRSProfileConfiguration(GROUP, KEY);
			t = json == null || json.isEmpty() ? null : gson.fromJson(json, FarmingTutorial.class);
		}
		catch (RuntimeException e)
		{
			log.debug("Farming tutorial not readable", e);
		}
		current = t == null ? FarmingTutorial.fresh() : t;
	}
}
