package com.osrstelemetry.plugin.home;

import com.google.gson.Gson;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;

/**
 * Reads the Kingdom of Miscellania and the daily claims from the game's own variables (canonical Home §10, §11): no other
 * plugin's state. Client thread; a few dozen variable reads every {@link #EVERY_TICKS} ticks (the first tick of a login at
 * once). Saved per RuneScape profile (RuneLite's profile configuration) only when something changed; the UI reads the
 * latest state from any thread.
 */
@Slf4j
@Singleton
public class HomeObserver
{
	static final String GROUP = "osrstelemetry";
	static final String DAILIES_KEY = "dailies";
	static final String KINGDOM_KEY = "kingdom";
	static final String RECURRING_KEY = "recurring";
	static final int EVERY_TICKS = 5;
	private static final int KINGDOM_REGION_A = 10044;
	private static final int KINGDOM_REGION_B = 10300;

	private final Client client;
	private final ConfigManager configManager;
	private final Gson gson;
	private final ChatMessageManager chat;
	private volatile Dailies dailies = Dailies.empty();
	private volatile Kingdom kingdom = Kingdom.empty();
	private volatile Recurring recurring = Recurring.empty();
	private final net.runelite.client.Notifier notifier;
	private final com.osrstelemetry.plugin.OsrsTelemetryConfig config;
	/** The last dialogue text applied (each line is read once). */
	private String lastDialogue;
	private volatile boolean onIslands;
	private volatile boolean loggedIn;
	private boolean loginRead;
	private int ticks;

	public HomeObserver(Client client, ConfigManager configManager, Gson gson, ChatMessageManager chat)
	{
		this(client, configManager, gson, chat, null, null);
	}

	@Inject
	public HomeObserver(Client client, ConfigManager configManager, Gson gson, ChatMessageManager chat, net.runelite.client.Notifier notifier,
		com.osrstelemetry.plugin.OsrsTelemetryConfig config)
	{
		this.notifier = notifier;
		this.config = config;
		this.client = client;
		this.configManager = configManager;
		this.gson = gson;
		this.chat = chat;
	}

	public Dailies dailies()
	{
		return dailies;
	}

	public Kingdom kingdom()
	{
		return kingdom;
	}

	/** Weekly activities, daily uses and what is shown (any thread). */
	public Recurring recurring()
	{
		return recurring;
	}

	/** The player chose to show or hide a weekly activity, timer or daily use (any thread; saved for this profile). */
	public void setShown(Recurring.Entry entry, boolean on)
	{
		Recurring next = recurring.withShown(entry, on);
		recurring = next;
		save(RECURRING_KEY, next);
	}

	/** Miscellania and Etceteria's map regions (the kingdom's readings are taken only there). */
	public static boolean isKingdomRegion(int region)
	{
		return region == KINGDOM_REGION_A || region == KINGDOM_REGION_B;
	}

	/** The player stands in the kingdom now: the coffer and approval shown are this moment's. */
	public boolean isOnIslands()
	{
		return onIslands && loggedIn;
	}

	public boolean isLoggedIn()
	{
		return loggedIn;
	}

	/** The player ticked or unticked a daily (any thread; saved for this profile). */
	public void setTracked(DailyTask task, boolean on)
	{
		Dailies next = dailies.withTracked(task, on);
		dailies = next;
		save(DAILIES_KEY, next);
	}

	public void load()
	{
		dailies = read(DAILIES_KEY, Dailies.class, Dailies.empty());
		kingdom = read(KINGDOM_KEY, Kingdom.class, Kingdom.empty());
		recurring = read(RECURRING_KEY, Recurring.class, Recurring.empty());
		lastDialogue = null;
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged e)
	{
		load();
		loginRead = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			loggedIn = true;
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			loggedIn = false;
			onIslands = false;
			loginRead = false;
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		loggedIn = true;
		boolean first = !loginRead;
		readDialogue(System.currentTimeMillis());
		if (!first && ++ticks % EVERY_TICKS != 0)
		{
			return;
		}
		loginRead = true;
		long now = System.currentTimeMillis();
		readKingdom(now);
		if (first)
		{
			warnOnLogin(now);
		}
		if (client.getVarcIntValue(VarClientID.PLAYERMEMBER) == 1)
		{
			readDailies(first, now);
			readRecurring(first, now);
		}
	}

	/**
	 * Tears of Guthix and the daily uses (members' worlds): the quest, the minigame's own values, quest points and total XP;
	 * the diary tiers and today's uses. Saved only when something changed.
	 */
	private void readRecurring(boolean login, long now)
	{
		TearsOfGuthix.Read t = new TearsOfGuthix.Read(Quest.TEARS_OF_GUTHIX.getState(client) == QuestState.FINISHED,
			client.getVarpValue(VarPlayerID.QP), client.getVarbitValue(VarbitID.TOG_QP_BEFORE_RETURN), client.getVarbitValue(VarbitID.TOG_TEARS_COLLECTED),
			client.getVarbitValue(VarbitID.TOG_MINIGAME_COLLECTING) == 1, client.getOverallExperience());
		DailyUses.Read u = new DailyUses.Read(tier(VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE), client.getVarbitValue(VarbitID.LUMBRIDGE_FREE_ALCHS),
			client.getVarbitValue(VarbitID.LUMBRIDGE_ENERGY_RESTORE), tier(VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE), client.getVarbitValue(VarbitID.FALADOR_SHIELD_RECHARGE));
		Recurring r = recurring;
		Recurring next = r.withTears(r.tears().with(t, login, now)).withUses(r.uses().with(u, now));
		if (config != null && config.recurringNotifications() && next.isShown(Recurring.Entry.TEARS) && next.tears().shouldNotify(now))
		{
			next = next.withTears(next.tears().withNotified());
			if (notifier != null)
			{
				notifier.notify("Tears of Guthix: Juna will let you in again.");
			}
		}
		if (next != r)
		{
			recurring = next;
			save(RECURRING_KEY, next);
		}
	}

	/** The highest diary tier done (0 none, 1 easy … 4 elite). */
	private int tier(int easy, int medium, int hard, int elite)
	{
		return client.getVarbitValue(elite) == 1 ? 4 : client.getVarbitValue(hard) == 1 ? 3 : client.getVarbitValue(medium) == 1 ? 2
			: client.getVarbitValue(easy) == 1 ? 1 : 0;
	}

	/**
	 * Juna's dialogue (and the narration after it) as the game shows it, read once per line: when the cave opens and the XP
	 * still due. Nothing is answered or clicked.
	 */
	private void readDialogue(long now)
	{
		String text = null;
		net.runelite.api.widgets.Widget name = client.getWidget(net.runelite.api.gameval.InterfaceID.ChatLeft.NAME);
		net.runelite.api.widgets.Widget line = client.getWidget(net.runelite.api.gameval.InterfaceID.ChatLeft.TEXT);
		if (name != null && !name.isHidden() && line != null && !line.isHidden() && "Juna".equalsIgnoreCase(clean(name.getText())))
		{
			text = line.getText();
		}
		net.runelite.api.widgets.Widget box = client.getWidget(net.runelite.api.gameval.InterfaceID.Messagebox.TEXT);
		if (text == null && box != null && !box.isHidden() && box.getText() != null && box.getText().contains("one quest point or"))
		{
			text = box.getText();
		}
		if (text == null || text.equals(lastDialogue))
		{
			return;
		}
		lastDialogue = text;
		Recurring r = recurring;
		Recurring next = r.withTears(r.tears().withDialogue(text, client.getOverallExperience(), now));
		if (next != r)
		{
			recurring = next;
			save(RECURRING_KEY, next);
		}
	}

	private static String clean(String s)
	{
		return s == null ? "" : s.replaceAll("<[^>]*>", "").trim();
	}

	private void readKingdom(long now)
	{
		Player p = client.getLocalPlayer();
		int region = p == null || p.getWorldLocation() == null ? -1 : p.getWorldLocation().getRegionID();
		onIslands = region == KINGDOM_REGION_A || region == KINGDOM_REGION_B;
		boolean quest = client.getVarpValue(VarPlayerID.MISC_QUEST) > 0;
		boolean royal = Quest.ROYAL_TROUBLE.getState(client) == QuestState.FINISHED;
		Kingdom next = kingdom.with(quest, royal, onIslands, client.getVarbitValue(VarbitID.MISC_COFFERS),
			client.getVarbitValue(VarbitID.MISC_APPROVAL), now);
		if (next != kingdom)
		{
			kingdom = next;
			save(KINGDOM_KEY, next);
		}
	}

	/**
	 * The login warning (approval under 90%, or a coffer that can't pay the full daily maximum), never from an unread value and
	 * at most once an hour: the time it fired is saved with the profile, so relogging, hopping or restarting can't repeat it.
	 */
	private void warnOnLogin(long now)
	{
		Kingdom k = kingdom;
		String text = k.loginWarning(onIslands, now);
		if (text == null || k.isWarningCoolingDown(now))
		{
			return;
		}
		Kingdom next = k.withWarned(now);
		kingdom = next;
		save(KINGDOM_KEY, next);
		if (chat != null)
		{
			chat.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(new ChatMessageBuilder().append(ChatColorType.HIGHLIGHT).append(text).build()).build());
		}
	}

	private void readDailies(boolean login, long now)
	{
		Dailies.Read r = new Dailies.Read();
		for (DailyTask t : DailyTask.values())
		{
			r.claim(t, client.getVarbitValue(t.getClaimVarbit()));
			if (t.getGateVarbit() >= 0)
			{
				r.gate(t, client.getVarbitValue(t.getGateVarbit()));
			}
		}
		r.morytania(client.getVarbitValue(VarbitID.MORYTANIA_DIARY_HARD_COMPLETE), client.getVarbitValue(VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE));
		r.account(client.getVarbitValue(VarbitID.IRONMAN), client.getVarpValue(VarPlayerID.NZONE_REWARDPOINTS));
		Dailies next = dailies.with(r, login, now);
		if (next != dailies)
		{
			dailies = next;
			save(DAILIES_KEY, next);
		}
	}

	private <T> T read(String key, Class<T> type, T fallback)
	{
		try
		{
			String json = configManager.getRSProfileConfiguration(GROUP, key);
			T v = json == null || json.isEmpty() ? null : gson.fromJson(json, type);
			return v == null ? fallback : v;
		}
		catch (RuntimeException ex)
		{
			log.debug("{} not readable", key, ex);
			return fallback;
		}
	}

	private void save(String key, Object value)
	{
		try
		{
			configManager.setRSProfileConfiguration(GROUP, key, gson.toJson(value));
		}
		catch (RuntimeException ex)
		{
			log.debug("{} not saved (no profile yet)", key, ex);
		}
	}
}
