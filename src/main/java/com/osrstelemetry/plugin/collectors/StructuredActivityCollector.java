package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.activity.BarrowsBrother;
import com.osrstelemetry.plugin.activity.BarrowsRunProgress;
import com.osrstelemetry.plugin.activity.GotrAltar;
import com.osrstelemetry.plugin.activity.GotrGameState;
import com.osrstelemetry.plugin.activity.GotrGameTracker;
import com.osrstelemetry.plugin.activity.GotrPortalTalismans;
import com.osrstelemetry.plugin.activity.GotrRewardPointTracker;
import com.osrstelemetry.plugin.activity.GotrRewardWallet;
import com.osrstelemetry.plugin.activity.GotrRunes;
import com.osrstelemetry.plugin.activity.ProductionTracker;
import com.osrstelemetry.plugin.activity.LocationPolicy;
import com.osrstelemetry.plugin.activity.PestControlGameState;
import com.osrstelemetry.plugin.activity.PestControlGameTracker;
import com.osrstelemetry.plugin.activity.StructuredActivityDefinition;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.activity.StructuredRewardSource;
import com.osrstelemetry.plugin.activity.TemporossGameState;
import com.osrstelemetry.plugin.activity.TemporossGameTracker;
import com.osrstelemetry.plugin.activity.WintertodtRoundTracker;
import com.osrstelemetry.plugin.activity.WintertodtState;
import com.osrstelemetry.plugin.wintertodt.WintertodtTools;
import com.osrstelemetry.plugin.activity.StructuredActivityCatalog;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.pestcontrol.PestControlXp;
import com.osrstelemetry.plugin.pestcontrol.VoidOwnership;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Normalized evidence for structured activities (see
 * activity.StructuredActivityRegistry). Emits three generic events and
 * never touches session state:
 *
 *  - STRUCTURED_ACTIVITY_STATE when the resolved (activity, policy) of the
 *    player's TEMPLATE region changes. One int compare per tick; the
 *    registry lookup runs only when the region id itself changes. Instances
 *    resolve through WorldPoint.fromLocalInstance, so instanced arenas map to
 *    their template region. Logging out / hopping forgets the location, so
 *    the first tick after login re-emits it (re-arm) -- and nothing else.
 *  - STRUCTURED_ACTIVITY_COMPLETION from an activity's own isolated chat
 *    pattern, deduplicated by the game's running count when it has one.
 *  - STRUCTURED_REWARD for each claim of a declared reward source, observed
 *    here from the game itself -- NOT from RuneLite's optional Loot Tracker
 *    plugin (its LootReceived event is posted only by that plugin, so it
 *    silently disappears when the plugin is disabled). Each source has one
 *    producer (see activity.StructuredRewardSource.Producer):
 *      REWARD_INTERFACE -- WidgetLoaded for the source's reward interface,
 *        then the exact contents of its item container (Barrows chest);
 *      CLAIM_MESSAGE -- the source's claim message in one of its regions
 *        arms ONE inventory correlation: the items added by the next
 *        inventory change within CLAIM_WINDOW_TICKS become the reward, then
 *        it disarms (GotR rewards guardian, Tempoross reward pool,
 *        Wintertodt reward cart). This is the same signal pair RuneLite's
 *        own plugin keys these rewards on; there is no open-ended inventory
 *        watch and no item-name rule. Unrelated chests, caskets and supply
 *        crates have no declared source and are never emitted.
 *
 * All of this runs on the client thread (RuneLite event dispatch); only
 * plain item ids/names/quantities leave it, inside the appended event.
 */
@Slf4j
public class StructuredActivityCollector
{
	private static final int NO_REGION = Integer.MIN_VALUE;

	private final Client client;
	/** Item id -> name, read on the client thread (ItemManager in production). */
	private final IntFunction<String> itemNames;
	private final EventLedger eventLedger;
	private final StructuredActivityRegistry registry;

	private int lastRegionId = NO_REGION;
	private String lastActivityKey;
	private LocationPolicy lastPolicy;
	private Long lastAccountHash;
	private final Map<String, Integer> lastCompletionCount = new HashMap<>();
	/** Tick of the last count-less completion per activity; see onGameMessage. */
	private final Map<String, Integer> lastCountlessCompletionTick = new HashMap<>();
	/** Two identical count-less completion texts this close together are one game (e.g. game message + message box). */
	static final int COUNTLESS_COMPLETION_DEDUPE_TICKS = 2;

	/** A claim message arms one inventory correlation for at most this many ticks (RuneLite's own window is 10). */
	static final int CLAIM_WINDOW_TICKS = 10;

	/**
	 * BARROWS CURRENT RUN: the game's own per-brother "killed this run"
	 * varbits, mirrored for presentation only (see BarrowsRunProgress).
	 * Null = not read yet / logged out. Written on the client thread,
	 * read by the UI; never an event, never lifecycle evidence.
	 */
	private volatile BarrowsRunProgress barrowsProgress;
	/**
	 * TEMPOROSS CURRENT GAME, read from the game's own HUD / points varbit
	 * while inside the Tempoross location with its HUD up; null otherwise
	 * (lobby, reward cove, left, logged out). Presentation only.
	 */
	private volatile TemporossGameState temporossGame;
	private volatile Runnable progressListener;
	/** Tempoross final points / permits bookkeeping (client thread only). */
	private final TemporossGameTracker temporossTracker = new TemporossGameTracker();

	/**
	 * GUARDIANS OF THE RIFT CURRENT GAME (Guardian Power and the open rifts from
	 * the HUD's own update script, energy from the game's earned-this-game
	 * varbits) while a game is in progress; null otherwise. Presentation only.
	 *
	 * STABILITY (live QA 2026-10-01): the HUD widget is briefly absent while a
	 * region loads (stepping through a rift into an altar, and back), yet the
	 * game goes on -- its update script kept firing inside the altars. The
	 * state is therefore KEPT (last known) through a short gap instead of
	 * blinking out, and cleared only when the game ends (win / loss), the
	 * player leaves the game for longer than the gap, logs out, hops, switches
	 * account or re-enables the plugin. Between games (after a win or loss,
	 * until the next game's Guardian Power rises from 0) there is no current
	 * game, so the previous game's values never leak into the next.
	 */
	private volatile GotrGameState gotrGame;
	/** GotR HUD update clientscript; its args carry the Great Guardian's power (no gameval constant exists). */
	static final int GOTR_HUD_UPDATE_SCRIPT = 5980;
	static final int GOTR_SCRIPT_ARG_CURRENT_POWER = 3;
	static final int GOTR_SCRIPT_ARG_MAX_POWER = 4;
	/** The HUD script's open-rift guardian indices: elemental 1-4, catalytic 1-8 (see GotrAltar). */
	static final int GOTR_SCRIPT_ARG_ACTIVE_ELEMENTAL = 6;
	static final int GOTR_SCRIPT_ARG_ACTIVE_CATALYTIC = 7;
	/** Ticks the game state survives with neither the HUD nor GOTR_IS_PLAYING (a region load), ~5 s. */
	static final int GOTR_IN_GAME_GRACE_TICKS = 8;
	/** Ticks the "successfully closed the rift" line waits for its rifts-closed count before standing in for it. */
	static final int GOTR_WIN_CORROBORATION_TICKS = 2;
	/** Last Guardian Power reported by the HUD script (client thread). */
	private Integer gotrPowerCurrent;
	private Integer gotrPowerMax;
	private GotrAltar gotrActiveElemental;
	private GotrAltar gotrActiveCatalytic;
	/** Last tick the game was confirmed in progress (HUD loaded or GOTR_IS_PLAYING); null = not in a game. */
	private Integer gotrLastInGameTick;
	/** The last game ended (win / loss); no current game until the next one's power rises from 0. */
	private boolean gotrGameEnded;
	private boolean gotrEndedPowerReset;
	/** Tick of a "successfully closed the rift" line still waiting for its count; null = none. */
	private Integer gotrPendingWinTick;
	/** Tick a count-less win was recorded (so a late count line for the same game is not a second game). */
	private Integer gotrCountlessWinTick;
	/** Whether this player can enter an altar (level + quest, read from the game); replaceable in tests. */
	private java.util.function.Predicate<GotrAltar> gotrAltarUsable = this::readAltarUsable;
	private final Map<GotrAltar, Boolean> gotrUsableCache = new java.util.EnumMap<>(GotrAltar.class);
	private int gotrUsableLevel = -1;
	private volatile GotrRewardWallet gotrWallet;
	/** Altars of the portal talismans in the inventory (from the INV container already observed). */
	private Set<GotrAltar> gotrTalismans = EnumSet.noneOf(GotrAltar.class);
	/** GotR game context on the last tick (HUD loaded, GOTR_IS_PLAYING, or the GotR location): crafting attribution only. */
	private boolean gotrContext;
	private final GotrGameTracker gotrTracker = new GotrGameTracker();
	private final GotrRewardPointTracker gotrPoints = new GotrRewardPointTracker();
	private final ProductionTracker gotrRunes = new ProductionTracker(GotrRunes.RECIPE);
	/**
	 * Rune pouch slot type (varbit value) -> rune item id, via the game's own
	 * RUNEPOUCH_RUNE enum; replaceable in tests. Returns <= 0 when unknown.
	 */
	private java.util.function.IntUnaryOperator runePouchRuneItem = this::runePouchRuneItemFromEnum;

	private StructuredRewardSource pendingClaim;
	private Map<Integer, Long> claimSnapshot = new HashMap<>();
	private int claimTicksLeft;

	/**
	 * PEST CONTROL CURRENT GAME (native status overlay, interface 408) while
	 * the player is on the game island (template region 10536); null
	 * otherwise -- the outpost (10537), the lander, anywhere else, logged
	 * out. Cleared on every exit, so one round never leaks into the next.
	 * Presentation only.
	 */
	public static final int PEST_CONTROL_ISLAND_REGION = 10536;
	public static final int PEST_CONTROL_OUTPOST_REGION = 10537;
	private volatile PestControlGameState pestGame;
	private final PestControlGameTracker pestTracker = new PestControlGameTracker();
	/** Last commendation balance the game stated this login (or remembered for this account); null = unknown. */
	private volatile Integer pestBalance;
	/** Planning-state sink for a newly stated balance (null = now unknown); never telemetry. */
	private volatile java.util.function.Consumer<Integer> pestBalanceListener;
	/** Void pieces owned now (inventory / worn / bank): goal planning only. */
	private final VoidOwnership voidOwnership = new VoidOwnership();
	/** Exact XP of the Pest Control reward skills, from StatChanged (goal planning only). */
	private final Map<Skill, Integer> pestSkillXp = new java.util.concurrent.ConcurrentHashMap<>();
	/** Last HUD diagnostic line logged (change-only debug logging while the HUD reads are unverified). */
	private String pestHudDiagnostic;
	private java.time.Clock clock = java.time.Clock.systemUTC();

	@Inject
	public StructuredActivityCollector(Client client, ItemManager itemManager, EventLedger eventLedger)
	{
		this(client, id -> itemManager.getItemComposition(id).getName(), eventLedger, StructuredActivityRegistry.defaults());
	}

	StructuredActivityCollector(Client client, IntFunction<String> itemNames, EventLedger eventLedger, StructuredActivityRegistry registry)
	{
		this.client = client;
		this.itemNames = itemNames;
		this.eventLedger = eventLedger;
		this.registry = registry;
	}

	/**
	 * Plugin re-enable: a reward claim armed before the disable must never
	 * be correlated with inventory changes made while the plugin was off.
	 * The remembered location is deliberately KEPT: if the player left the
	 * activity while disabled, the first region observed afterwards differs
	 * from it and the usual exit (disarm) event is sent.
	 */
	public void disarmPendingClaim()
	{
		disarmClaim();
		// Whatever changed while disabled is unknown: re-baseline permits,
		// and a game in progress can no longer be proven observed from start.
		temporossTracker.reset();
		resetGotrTracking();
		// A round open before the disable cannot be timed across it: dropped,
		// and a round still in progress resumes as NOT observed from start.
		resetPestControl();
		resetWintertodt();
	}

	/** GotR: balances, game provenance, Guardian Power, open rifts and the rune baseline are all unknown again. */
	private void resetGotrTracking()
	{
		gotrTracker.reset();
		gotrPoints.reset();
		gotrRunes.reset();
		gotrPowerCurrent = null;
		gotrPowerMax = null;
		gotrActiveElemental = null;
		gotrActiveCatalytic = null;
		gotrContext = false;
		gotrLastInGameTick = null;
		gotrGameEnded = false;
		gotrEndedPowerReset = false;
		gotrPendingWinTick = null;
		gotrCountlessWinTick = null;
		gotrUsableCache.clear();
		gotrUsableLevel = -1;
		gotrTalismans = EnumSet.noneOf(GotrAltar.class);
		refreshGotrWallet();
	}

	/** The player's GotR reward wallet when the game has stated both balances this login; null = unknown. */
	public GotrRewardWallet getGotrWallet()
	{
		return gotrWallet;
	}

	private void refreshGotrWallet()
	{
		GotrRewardWallet w = gotrPoints.wallet();
		if (!Objects.equals(w, gotrWallet))
		{
			gotrWallet = w;
			notifyProgressListener();
		}
	}

	/** Test seam: altar eligibility without a live client. */
	void setGotrAltarUsable(java.util.function.Predicate<GotrAltar> usable)
	{
		this.gotrAltarUsable = usable;
		gotrUsableCache.clear();
	}

	/** Runecraft level (boosts count at altars) and the altar's quest, from the game itself. Client thread. */
	private boolean readAltarUsable(GotrAltar altar)
	{
		if (client.getBoostedSkillLevel(Skill.RUNECRAFT) < altar.getRunecraftLevel())
		{
			return false;
		}
		return altar.getQuest() == null || altar.getQuest().getState(client) == net.runelite.api.QuestState.FINISHED;
	}

	/** Cached per altar until the Runecraft level changes; null when it cannot be read. */
	private Boolean altarUsable(GotrAltar altar)
	{
		if (altar == null)
		{
			return null;
		}
		int level = client.getBoostedSkillLevel(Skill.RUNECRAFT);
		if (level != gotrUsableLevel)
		{
			gotrUsableCache.clear();
			gotrUsableLevel = level;
		}
		Boolean cached = gotrUsableCache.get(altar);
		if (cached != null)
		{
			return cached;
		}
		try
		{
			boolean usable = gotrAltarUsable.test(altar);
			gotrUsableCache.put(altar, usable);
			return usable;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** A win or loss ended the current game: no current game until the next one starts. */
	private void endGotrGame()
	{
		gotrGameEnded = true;
		gotrEndedPowerReset = false;
		setGotrGame(null);
	}

	public void resetForAccountSwitch()
	{
		resetWintertodt();
		wtSkillXp.clear();
		resetPestControl();
		voidOwnership.reset();
		pestSkillXp.clear();
		setBarrowsProgress(null);
		setTemporossGame(null);
		temporossTracker.reset();
		setGotrGame(null);
		resetGotrTracking();
		forgetLocation();
		disarmClaim();
		lastCompletionCount.clear();
		lastCountlessCompletionTick.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			// Disarm (for the account that was inside), then forget, so the
			// next login re-arms from scratch instead of inheriting a lock.
			// A round in progress ends here for this login (its time so far is real round time).
			closePestRound(lastAccountHash);
			emitWintertodtRound(wtTracker.exit(client.getTickCount()), lastAccountHash);
			resetWintertodt();
			if (lastActivityKey != null && lastAccountHash != null)
			{
				eventLedger.append(lastAccountHash, EventType.STRUCTURED_ACTIVITY_STATE,
					new EventPayloads.StructuredActivityState(null, null, lastRegionId));
			}
			resetPestControl();
			forgetLocation();
			disarmClaim();
			setBarrowsProgress(null);
			setTemporossGame(null);
			temporossTracker.reset();
			setGotrGame(null);
			resetGotrTracking();
		}
	}

	// ---- Wintertodt -------------------------------------------------------------

	public static final int WINTERTODT_ARENA_REGION = 6462;
	public static final int WINTERTODT_CAMP_REGION = 6461;
	public static final String WINTERTODT_REWARDS = "reward_cart_rewards";
	private final WintertodtRoundTracker wtTracker = new WintertodtRoundTracker();
	private volatile WintertodtState wtState;
	private volatile java.util.Set<WintertodtTools> wtTools = java.util.Collections.emptySet();
	private Map<Integer, Long> wtInventory = java.util.Collections.emptyMap();
	private Map<Integer, Long> wtWorn = java.util.Collections.emptyMap();
	private final Map<Skill, Integer> wtSkillXp = new java.util.concurrent.ConcurrentHashMap<>();
	private String wtDiagnostic;
	/** The effective Wintertodt playstyle label (planning state) stamped on each round for History; null = none. */
	private volatile java.util.function.Supplier<String> wtPlaystyleLabel;
	private String wtCartDiagnostic;
	/** The cart balance now (see getWintertodtCartRewards); null = unknown this login. */
	private volatile Integer wtCartBalance;
	/** The reward-pool varbit was read in the arena this login (only then is the camp read trusted). */
	private boolean wtPoolSeen;
	private com.osrstelemetry.plugin.wintertodt.WintertodtPanelSource.Location wtLocation;

	/** Wired by the plugin: the account's effective Wintertodt playstyle label (e.g. "Mass • Points", "Auto"). */
	public void setWintertodtPlaystyleLabel(java.util.function.Supplier<String> label)
	{
		this.wtPlaystyleLabel = label;
	}

	/** The Wintertodt arena as the game shows it now, or null outside it. Presentation only. */
	public WintertodtState getWintertodtState()
	{
		return wtState;
	}

	/** Tools carried or worn (only refreshed around Wintertodt). */
	public java.util.Set<WintertodtTools> getWintertodtTools()
	{
		return wtTools;
	}

	/** Exact Firemaking / Agility XP as the game last reported it this login; null when unknown. */
	public Integer wintertodtSkillXp(Skill skill)
	{
		return skill == null ? null : wtSkillXp.get(skill);
	}

	private boolean nearWintertodt()
	{
		return lastRegionId == WINTERTODT_ARENA_REGION || lastRegionId == WINTERTODT_CAMP_REGION;
	}

	/** Arena / camp / elsewhere, from the native region only (presentation context, never telemetry). */
	public com.osrstelemetry.plugin.wintertodt.WintertodtPanelSource.Location getWintertodtLocation()
	{
		return wintertodtLocationOf(lastRegionId);
	}

	static com.osrstelemetry.plugin.wintertodt.WintertodtPanelSource.Location wintertodtLocationOf(int regionId)
	{
		return regionId == WINTERTODT_ARENA_REGION ? com.osrstelemetry.plugin.wintertodt.WintertodtPanelSource.Location.ARENA
			: regionId == WINTERTODT_CAMP_REGION ? com.osrstelemetry.plugin.wintertodt.WintertodtPanelSource.Location.CAMP
			: com.osrstelemetry.plugin.wintertodt.WintertodtPanelSource.Location.ELSEWHERE;
	}

	/**
	 * Rewards still owed in the Reward Cart now; null = not known this login.
	 * The game's reward-pool varbit (WINT_REWARD_POOL, live-verified as the
	 * cart balance: 0 -> 3 -> 6 -> 9, and down on Search / Big-search) read in
	 * the arena, and in the camp once it was seen in the arena this login;
	 * otherwise the game's own "now owed" / Check line. A balance only --
	 * never what the session earned. Ephemeral: never persisted.
	 */
	public Integer getWintertodtCartRewards()
	{
		return wtCartBalance;
	}

	private void setWintertodtCartBalance(Integer balance)
	{
		if (java.util.Objects.equals(balance, wtCartBalance))
		{
			return;
		}
		wtCartBalance = balance;
		notifyProgressListener();
	}

	/** Region class changes (arena / camp / elsewhere) re-render the companion; nothing is recorded. */
	private void trackWintertodtLocation()
	{
		com.osrstelemetry.plugin.wintertodt.WintertodtPanelSource.Location now = getWintertodtLocation();
		if (now != wtLocation)
		{
			wtLocation = now;
			notifyProgressListener();
		}
	}

	/** Camp: the cart balance only, once the arena showed the varbit this login (one varbit read, camp only). */
	private void trackWintertodtCampCart()
	{
		if (lastRegionId != WINTERTODT_CAMP_REGION || !wtPoolSeen)
		{
			return;
		}
		int pool = client.getVarbitValue(VarbitID.WINT_REWARD_POOL);
		if (pool >= 0 && pool <= com.osrstelemetry.plugin.wintertodt.WintertodtRewards.CART_MAX)
		{
			setWintertodtCartBalance(pool);
		}
		if (log.isDebugEnabled())
		{
			String cart = "rewardPool=" + pool + " (camp)";
			if (!cart.equals(wtCartDiagnostic))
			{
				wtCartDiagnostic = cart;
				log.debug("Wintertodt cart: {}", cart);
			}
		}
	}

	/**
	 * Client thread, once per logged-in tick: the Wintertodt round from native
	 * state only -- the status overlay's own texts (interface 396: warmth,
	 * energy, points), the respawn-timer varbit and the reward-pool varbit.
	 * Three widget lookups and three varbit reads, only in the arena; nothing
	 * anywhere else. Ephemeral: nothing here is persisted except a finished
	 * round's time and counted actions (see emitWintertodtRound).
	 */
	void trackWintertodtTick()
	{
		trackWintertodtLocation();
		trackWintertodtCampCart();
		boolean inArena = lastRegionId == WINTERTODT_ARENA_REGION;
		if (!inArena && wtState == null && !wtTracker.isInRound())
		{
			return;
		}
		int tick = client.getTickCount();
		String warmth = null;
		String energy = null;
		String points = null;
		boolean hud = false;
		int respawn = 0;
		int pool = -1;
		if (inArena)
		{
			energy = widgetText(InterfaceID.WintStatus.ENERGY_TITLE);
			hud = energy != null;
			warmth = widgetText(InterfaceID.WintStatus.WARMTH_TITLE);
			points = widgetText(InterfaceID.WintStatus.POINTS);
			respawn = client.getVarbitValue(VarbitID.WINT_TRANSMIT_RESPAWNDELAY);
			pool = client.getVarbitValue(VarbitID.WINT_REWARD_POOL);
			if (pool >= 0 && pool <= com.osrstelemetry.plugin.wintertodt.WintertodtRewards.CART_MAX)
			{
				wtPoolSeen = true;
				setWintertodtCartBalance(pool);
			}
		}
		WintertodtRoundTracker.RoundEnd ended = wtTracker.onTick(tick, clock.instant(), inArena, hud, warmth, energy, points, respawn, pool);
		emitWintertodtRound(ended, client.getAccountHash());
		setWintertodtState(wtTracker.state());
		if (inArena && log.isDebugEnabled())
		{
			String line = "warmth='" + warmth + "' (varbit " + client.getVarbitValue(VarbitID.WINT_WARMTH) + ") energy='" + energy
				+ "' points='" + points + "' respawn=" + respawn + " needsWarning=" + client.getVarbitValue(VarbitID.WINT_NEEDS_WARNING)
				+ " kills=" + client.getVarpValue(net.runelite.api.gameval.VarPlayerID.TOTAL_WINTERTODT_KILLS)
				+ " wintInt=" + client.getVarpValue(net.runelite.api.gameval.VarPlayerID.WINT_INT);
			if (!line.equals(wtDiagnostic))
			{
				wtDiagnostic = line;
				log.debug("Wintertodt HUD: {} -> {}", line, wtState);
			}
			String cart = "rewardPool=" + pool;
			if (!cart.equals(wtCartDiagnostic))
			{
				wtCartDiagnostic = cart;
				log.debug("Wintertodt cart: {}", cart);
			}
		}
	}

	/** A finished (or left) round: its time and its counted actions -- one event each, never per tick. */
	private void emitWintertodtRound(WintertodtRoundTracker.RoundEnd end, Long accountHash)
	{
		if (end == null || accountHash == null)
		{
			return;
		}
		String key = StructuredActivityCatalog.WINTERTODT;
		if (end.getMillis() > 0)
		{
			EventPayloads.StructuredActivityRoundTime round = new EventPayloads.StructuredActivityRoundTime(key, end.getMillis());
			round.setCompleteRound(end.isComplete());
			java.util.function.Supplier<String> style = wtPlaystyleLabel;
			round.setPlaystyle(style == null ? null : style.get());
			eventLedger.append(accountHash, EventType.STRUCTURED_ACTIVITY_ROUND_TIME, round);
		}
		for (Map.Entry<String, Integer> a : end.getActions().entrySet())
		{
			eventLedger.append(accountHash, EventType.STRUCTURED_ACTIVITY_ACTION,
				new EventPayloads.StructuredActivityAction(key, a.getKey(), a.getValue()));
		}
		log.debug("Wintertodt round: ended millis={} complete={} points={} actions={}", end.getMillis(), end.isComplete(),
			end.getPoints(), end.getActions());
	}

	private static final java.util.regex.Pattern WT_SUBDUE = java.util.regex.Pattern.compile("Your subdued Wintertodt count is: ([0-9,]+)");
	private Integer lastWtSubdueCount;

	/**
	 * "Your subdued Wintertodt count is: N" (the game's own running count --
	 * the same line the generic completion path counts): deduped by N, then
	 * the round's final points (Avg Points) and the rewards it owes.
	 */
	void onWintertodtSubdueLine(String message)
	{
		if (message == null || !message.startsWith("Your subdued Wintertodt"))
		{
			return;
		}
		java.util.regex.Matcher m = WT_SUBDUE.matcher(message);
		if (!m.find())
		{
			return;
		}
		int count;
		try
		{
			count = Integer.parseInt(m.group(1).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return;
		}
		if (lastWtSubdueCount != null && count <= lastWtSubdueCount)
		{
			return;
		}
		lastWtSubdueCount = count;
		recordWintertodtSubdue(StructuredActivityCatalog.WINTERTODT, count);
	}

	/** The subdued round's final points and the rewards it owes (2 at 500 points, +1 per further 500; none below 500). */
	private void recordWintertodtSubdue(String key, Integer count)
	{
		Integer points = wtTracker.pointsForSubdue(client.getTickCount());
		if (points == null)
		{
			return;
		}
		eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_SCORE,
			new EventPayloads.StructuredActivityScore(key, count, points, true));
		// Rewards are NOT derived from the points here: the game states the
		// actual award (which can include a chance-based extra reward) in its
		// own line -- see onWintertodtRewardLine.
	}

	/**
	 * The game's own award line, LIVE-VERIFIED 2026-10-02 (GAMEMESSAGE, same tick
	 * as the subdue line): "You're owed an additional 3 rewards from the reward
	 * cart. You're now owed 9 rewards." The ADDITIONAL amount is what this
	 * session earned (it can exceed the 500-point formula's guaranteed count);
	 * the "now owed" total is only the cart balance, which can include rewards
	 * from before the session.
	 */
	static final java.util.regex.Pattern WT_REWARD_AWARD = java.util.regex.Pattern.compile(
		"You're owed an additional ([0-9,]+) rewards? from the reward cart");
	static final java.util.regex.Pattern WT_REWARD_NOW_OWED = java.util.regex.Pattern.compile("You're now owed ([0-9,]+) rewards?");
	/**
	 * The cart's "Check" answer, LIVE-VERIFIED 2026-10-02: "You are owed 9 more
	 * rewards from the cart." Balance only -- never earned rewards.
	 */
	static final java.util.regex.Pattern WT_REWARD_CHECK = java.util.regex.Pattern.compile("You are owed ([0-9,]+) more rewards? from the cart");
	/** The same award line delivered again this soon is one award. */
	static final int WT_AWARD_DEDUPE_TICKS = 5;
	private String lastWtAwardLine;
	private int lastWtAwardTick;
	/** Rewards waiting in the cart as the game last stated it (award line / Check); null = not stated this login. */
	private volatile Integer wtCartOwed;

	/** Rewards waiting in the Reward Cart as the game last stated it this login; null = unknown. Never session-earned rewards. */
	public Integer getWintertodtCartOwed()
	{
		return wtCartOwed;
	}

	/** Award and Check lines (Wintertodt only). Returns true when the message was one of them. */
	boolean onWintertodtRewardLine(String message)
	{
		if (message == null || !nearWintertodt())
		{
			return false;
		}
		if (message.startsWith("You are owed "))
		{
			Integer owed = firstNumber(WT_REWARD_CHECK, message);
			if (owed == null)
			{
				return false;
			}
			setWintertodtCartOwed(owed, "check");
			return true;
		}
		if (!message.startsWith("You're owed an additional"))
		{
			return false;
		}
		Integer additional = firstNumber(WT_REWARD_AWARD, message);
		if (additional == null)
		{
			return false;
		}
		Integer owed = firstNumber(WT_REWARD_NOW_OWED, message);
		int tick = client.getTickCount();
		if (message.equals(lastWtAwardLine) && tick - lastWtAwardTick >= 0 && tick - lastWtAwardTick <= WT_AWARD_DEDUPE_TICKS)
		{
			// The same line delivered twice (two chat types / a re-sent line): one award.
			return true;
		}
		lastWtAwardLine = message;
		lastWtAwardTick = tick;
		if (additional > 0)
		{
			eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_CURRENCY,
				new EventPayloads.StructuredActivityCurrency(StructuredActivityCatalog.WINTERTODT, WINTERTODT_REWARDS, additional,
					owed == null ? -1 : owed));
		}
		if (owed != null)
		{
			setWintertodtCartOwed(owed, "award");
		}
		return true;
	}

	private void setWintertodtCartOwed(int owed, String source)
	{
		if (Integer.valueOf(owed).equals(wtCartOwed))
		{
			// The same statement again still corrects the balance (the varbit may have moved since).
			setWintertodtCartBalance(owed);
			return;
		}
		wtCartOwed = owed;
		log.debug("Wintertodt cart: owed={} (from {})", owed, source);
		wtCartBalance = owed;
		notifyProgressListener();
	}

	private static Integer firstNumber(java.util.regex.Pattern pattern, String message)
	{
		java.util.regex.Matcher m = pattern.matcher(message);
		if (!m.find())
		{
			return null;
		}
		try
		{
			return Integer.parseInt(m.group(1).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private void onWintertodtInventory(Map<Integer, Long> inventory)
	{
		wtInventory = inventory;
		int roots = (int) (long) inventory.getOrDefault(net.runelite.api.gameval.ItemID.WINT_BRUMA_ROOT, 0L);
		int kindling = (int) (long) inventory.getOrDefault(net.runelite.api.gameval.ItemID.WINT_BRUMA_KINDLING, 0L);
		int doses = 4 * (int) (long) inventory.getOrDefault(net.runelite.api.gameval.ItemID.WINT_POTION4, 0L)
			+ 3 * (int) (long) inventory.getOrDefault(net.runelite.api.gameval.ItemID.WINT_POTION3, 0L)
			+ 2 * (int) (long) inventory.getOrDefault(net.runelite.api.gameval.ItemID.WINT_POTION2, 0L)
			+ (int) (long) inventory.getOrDefault(net.runelite.api.gameval.ItemID.WINT_POTION1, 0L);
		boolean before = wtTools.contains(WintertodtTools.KNIFE);
		refreshWintertodtTools();
		wtTracker.onInventory(client.getTickCount(), roots, kindling, doses, wtTools.contains(WintertodtTools.KNIFE));
		if (nearWintertodt() || before != wtTools.contains(WintertodtTools.KNIFE))
		{
			setWintertodtState(wtTracker.state());
		}
	}

	/** Tools from the inventory + worn items, by item name -- only around Wintertodt (bounded). */
	private void refreshWintertodtTools()
	{
		if (!nearWintertodt())
		{
			return;
		}
		Map<Integer, Long> all = new HashMap<>(wtInventory);
		wtWorn.forEach((k, v) -> all.merge(k, v, Long::sum));
		java.util.Set<WintertodtTools> tools = WintertodtTools.in(all, itemNames);
		if (!tools.equals(wtTools))
		{
			wtTools = java.util.Collections.unmodifiableSet(tools);
			notifyProgressListener();
		}
	}

	private void setWintertodtState(WintertodtState state)
	{
		if (Objects.equals(state, wtState))
		{
			return;
		}
		wtState = state;
		notifyProgressListener();
	}

	/** Logout / hop / re-enable / account switch: no round, no live state (a round in progress resumes as joined part-way). */
	private void resetWintertodt()
	{
		wtTracker.reset();
		lastWtSubdueCount = null;
		lastWtAwardLine = null;
		wtCartOwed = null;
		wtCartBalance = null;
		wtPoolSeen = false;
		wtDiagnostic = null;
		wtCartDiagnostic = null;
		setWintertodtState(null);
	}

	// ---- Pest Control ----------------------------------------------------------

	/** The current Pest Control game as the game's status overlay shows it, or null when not on the island. */
	public PestControlGameState getPestControlGame()
	{
		return pestGame;
	}

	/** Last commendation balance the game stated (or remembered for this account); null = unknown. */
	public Integer getPestBalance()
	{
		return pestBalance;
	}

	/** Void pieces owned now (inventory / worn / bank), for goal planning. */
	public VoidOwnership getVoidOwnership()
	{
		return voidOwnership;
	}

	/** Exact XP of a Pest Control reward skill as the game last reported it; null = not seen this login. */
	public Integer pestSkillXp(Skill skill)
	{
		return skill == null ? null : pestSkillXp.get(skill);
	}

	/** Receives each newly stated balance (null = no longer known); planning state only. */
	public void setPestBalanceListener(java.util.function.Consumer<Integer> listener)
	{
		this.pestBalanceListener = listener;
	}

	/** Login: the balance last remembered for this account, until the game states it again. */
	public void seedPestBalance(Integer remembered)
	{
		pestTracker.seedBalance(remembered);
		Integer b = pestTracker.getBalance();
		if (!Objects.equals(b, pestBalance))
		{
			pestBalance = b;
			notifyProgressListener();
		}
	}

	/** Login: the bank as Spyglass last saw it (its own bank.json), until the bank is opened again. */
	public void seedBankOwnership(Map<Integer, Long> bankItems)
	{
		if (bankItems != null && !voidOwnership.getSnapshot().isBankKnown())
		{
			voidOwnership.update(VoidOwnership.Container.BANK, bankItems);
		}
	}

	/** Test seam: the wall clock for a round's start time (durations always come from game ticks). */
	void setClock(java.time.Clock clock)
	{
		this.clock = clock;
	}

	/**
	 * Island entry / exit (template regions): entering 10536 opens a round
	 * (observed from start only when arriving from another region); leaving
	 * it closes the round and records its time.
	 */
	private void onPestControlRegion(int previousRegion, int regionId, boolean firstObservation)
	{
		boolean wasIsland = previousRegion == PEST_CONTROL_ISLAND_REGION;
		boolean isIsland = regionId == PEST_CONTROL_ISLAND_REGION;
		if (isIsland && !wasIsland)
		{
			pestTracker.onEnter(client.getTickCount(), !firstObservation, clock.instant());
			pestHudDiagnostic = null;
			setPestGame(pestTracker.state());
		}
		else if (wasIsland && !isIsland)
		{
			closePestRound(client.getAccountHash());
		}
	}

	/** Ends an open round: its island time becomes ROUND TIME (the Commendations/hr denominator). */
	private void closePestRound(Long accountHash)
	{
		Long millis = pestTracker.onExit(client.getTickCount());
		setPestGame(null);
		if (millis != null && accountHash != null)
		{
			eventLedger.append(accountHash, EventType.STRUCTURED_ACTIVITY_ROUND_TIME,
				new EventPayloads.StructuredActivityRoundTime(StructuredActivityCatalog.PEST_CONTROL, millis));
		}
	}

	/** Logout / hop / re-enable / account switch: no round, no HUD, balance unknown (re-seeded at login). */
	private void resetPestControl()
	{
		pestTracker.reset();
		pestHudDiagnostic = null;
		setPestGame(null);
		if (pestBalance != null)
		{
			pestBalance = null;
			notifyProgressListener();
		}
	}

	/**
	 * Client thread, once per logged-in tick. On the island: the status
	 * overlay (interface 408) -- two widget widths, one health text and four
	 * portal texts. In the outpost: the lander overlay's points line. Nothing
	 * anywhere else.
	 */
	void trackPestControlTick()
	{
		if (!pestTracker.isInGame() && lastRegionId == PEST_CONTROL_ISLAND_REGION)
		{
			// Still on the island after a re-enable: a round joined mid-game.
			pestTracker.onEnter(client.getTickCount(), false, clock.instant());
		}
		if (pestTracker.isInGame())
		{
			readPestHud();
			setPestGame(pestTracker.state());
		}
		else if (lastRegionId == PEST_CONTROL_OUTPOST_REGION)
		{
			String points = widgetText(InterfaceID.PestLanderOverlay.PEST_LANDER_OVER_POINTS);
			if (points != null)
			{
				publishPestBalance(pestTracker.onLanderPoints(points));
			}
		}
	}

	private static final int[] PEST_PORTAL_HP = {
		InterfaceID.PestStatusOverlay.PEST_STATUS_PORTTXT1, InterfaceID.PestStatusOverlay.PEST_STATUS_PORTTXT2,
		InterfaceID.PestStatusOverlay.PEST_STATUS_PORTTXT3, InterfaceID.PestStatusOverlay.PEST_STATUS_PORTTXT4};

	/**
	 * SOURCES (read only while Jagex's own status overlay, interface 408, is
	 * LOADED -- never only when visible):
	 *  - ACTIVITY: the game's activity varbit (PEST_ACTIVITY_COUNTER, 0..50),
	 *    shown as value / 50. Live QA 2026-10-02 proved the overlay's
	 *    ACTIVITY_CONTAINER / ACTIVITY_BAR layers are static frame pieces
	 *    (widths 145 / 141 all game), so their width ratio was a constant
	 *    ~97%; the moving fill is drawn by the game script from this varbit.
	 *  - VOID KNIGHT: PEST_STATUS_OVER_HEALTH's text, hitpoints of 200.
	 *  - PORTALS: a portal's health text reading exactly "0" is destroyed --
	 *    the same rule RuneLite's own overlay uses to mark a portal dead.
	 * Change-only debug diagnostics (activity geometry included) stay for
	 * live QA.
	 */
	private void readPestHud()
	{
		Widget frame = client.getWidget(InterfaceID.PestStatusOverlay.ACTIVITY_CONTAINER);
		Widget fill = client.getWidget(InterfaceID.PestStatusOverlay.ACTIVITY_BAR);
		int counter = client.getVarbitValue(VarbitID.PEST_ACTIVITY_COUNTER);
		// The varbit is only meaningful with the game's own activity meter up.
		Integer activity = frame == null ? null : PestControlGameTracker.activityPercent(counter);
		String health = widgetText(InterfaceID.PestStatusOverlay.PEST_STATUS_OVER_HEALTH);
		Integer knight = PestControlGameTracker.knightPercent(health);
		Integer portals = null;
		StringBuilder hp = new StringBuilder();
		for (int id : PEST_PORTAL_HP)
		{
			String text = widgetText(id);
			hp.append(text == null ? "-" : text.trim()).append(',');
			if (text != null)
			{
				portals = (portals == null ? 0 : portals) + (PestControlGameTracker.isPortalDead(text) ? 1 : 0);
			}
		}
		pestTracker.onHud(activity, knight, portals);
		if (log.isDebugEnabled())
		{
			String line = "activity: counter=" + counter + "/" + PestControlGameTracker.ACTIVITY_MAX
				+ " source=" + (frame == null ? "none(overlay not loaded)" : "varbit5662")
				+ " container=" + geometry(frame) + " progress=" + geometry(fill)
				+ " | health='" + health + "' portalHp=" + hp;
			if (!line.equals(pestHudDiagnostic))
			{
				pestHudDiagnostic = line;
				log.debug("Pest Control HUD: {} -> activity={} knight={} portals={}", line, activity, knight, portals);
			}
		}
	}

	/** Diagnostics only: a widget's geometry, and its first child's (the game may draw a fill there). */
	private static String geometry(Widget w)
	{
		if (w == null)
		{
			return "-";
		}
		StringBuilder sb = new StringBuilder();
		sb.append(w.getId()).append("{hidden=").append(w.isHidden()).append(" w=").append(w.getWidth())
			.append(" orig=").append(w.getOriginalWidth());
		java.awt.Rectangle b = w.getBounds();
		sb.append(" bounds=").append(b == null ? "-" : String.valueOf(b.width));
		Widget parent = w.getParent();
		sb.append(" parentW=").append(parent == null ? "-" : String.valueOf(parent.getWidth()));
		Widget child = w.getChild(0);
		if (child != null)
		{
			java.awt.Rectangle cb = child.getBounds();
			sb.append(" child0{hidden=").append(child.isHidden()).append(" w=").append(child.getWidth())
				.append(" orig=").append(child.getOriginalWidth()).append(" bounds=").append(cb == null ? "-" : String.valueOf(cb.width))
				.append('}');
		}
		return sb.append('}').toString();
	}

	private void setPestGame(PestControlGameState state)
	{
		if (Objects.equals(state, pestGame))
		{
			return;
		}
		pestGame = state;
		notifyProgressListener();
	}

	private void publishPestBalance(Integer stated)
	{
		if (stated == null || stated.equals(pestBalance))
		{
			return;
		}
		pestBalance = stated;
		notifyBalanceListener(stated);
		notifyProgressListener();
	}

	/**
	 * The Void Knight reward shop opened: a purchase there changes the
	 * balance without the game restating it, so the balance is unknown
	 * again until it does (never a stale number). Its TOTAL text is logged
	 * for live QA (format unverified, so not used).
	 */
	void onPestRewardShopOpened()
	{
		log.debug("Pest Control reward shop: total='{}'", widgetText(InterfaceID.PestRewardshop.TOTAL));
		pestTracker.forgetBalance();
		if (pestBalance != null)
		{
			pestBalance = null;
			notifyBalanceListener(null);
			notifyProgressListener();
		}
	}

	private void notifyBalanceListener(Integer balance)
	{
		java.util.function.Consumer<Integer> listener = pestBalanceListener;
		if (listener != null)
		{
			try
			{
				listener.accept(balance);
			}
			catch (RuntimeException e)
			{
				// Planning state only: never let it affect collection.
			}
		}
	}

	/** The current Tempoross game as the game's HUD reports it, or null when not in a game. */
	public TemporossGameState getTemporossGame()
	{
		return temporossGame;
	}

	/**
	 * Client thread, once per game tick while in the Tempoross location:
	 * three widget texts and one varbit (cheap). Publishes only on change.
	 * Outside the location, or without the game HUD, the game state is
	 * cleared -- never kept as stale bars.
	 *
	 * SOURCE: interface 437 (TemporossHud) is Jagex's own game HUD. Each bar
	 * is a layer (ENERGY / ESSENCE / STORM_INTENSITY -- no text of its own)
	 * whose *_TITLE text component carries "Energy: 70%" etc.; the fill
	 * width is presentation only and is not read. The gate is the interface
	 * being LOADED, not visible: another plugin hiding the vanilla HUD must
	 * never blank Spyglass (the game keeps updating the hidden text).
	 */
	void readTemporossGame()
	{
		if (!StructuredActivityCatalog.TEMPOROSS.equals(lastActivityKey) || !temporossHudLoaded())
		{
			setTemporossGame(null);
			return;
		}
		setTemporossGame(TemporossGameState.of(
			TemporossGameState.percentOf(widgetText(InterfaceID.TemporossHud.ENERGY_TITLE)),
			TemporossGameState.percentOf(widgetText(InterfaceID.TemporossHud.ESSENCE_TITLE)),
			TemporossGameState.percentOf(widgetText(InterfaceID.TemporossHud.STORM_INTENSITY_TITLE)),
			client.getVarbitValue(VarbitID.TEMPOROSS_CURRENT_POINTS)));
	}

	private boolean temporossHudLoaded()
	{
		return client.getWidget(InterfaceID.TemporossHud.CONTENT) != null;
	}

	private String widgetText(int componentId)
	{
		Widget widget = client.getWidget(componentId);
		return widget == null ? null : widget.getText();
	}

	/**
	 * Client thread, once per logged-in tick: Tempoross permits earned and
	 * game provenance (see TemporossGameTracker). Two varbit reads and one
	 * widget lookup, only inside the Tempoross location.
	 */
	void trackTemporossTick()
	{
		boolean inTempoross = StructuredActivityCatalog.TEMPOROSS.equals(lastActivityKey);
		int gained = inTempoross
			? temporossTracker.onTick(true, temporossHudLoaded(), client.getVarbitValue(VarbitID.TEMPOROSS_CURRENT_POINTS),
				client.getVarbitValue(VarbitID.TEMPOROSS_REWARDPERMITS))
			: temporossTracker.onTick(false, false, 0, 0);
		if (gained > 0)
		{
			eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_CURRENCY,
				new EventPayloads.StructuredActivityCurrency(StructuredActivityCatalog.TEMPOROSS, "reward_permits", gained,
					client.getVarbitValue(VarbitID.TEMPOROSS_REWARDPERMITS)));
		}
	}

	private void setTemporossGame(TemporossGameState state)
	{
		if (Objects.equals(state, temporossGame))
		{
			return;
		}
		temporossGame = state;
		notifyProgressListener();
	}

	/** The current Guardians of the Rift game as the game's HUD reports it, or null when not in a game. */
	public GotrGameState getGotrGame()
	{
		return gotrGame;
	}

	/**
	 * GotR HUD update script (5980): the Great Guardian's current / maximum
	 * power are its arguments 3 and 4 (layout corroborated by two independent
	 * Plugin Hub plugins; evidence only -- none is used). Read from the
	 * arguments, never from the HUD's power TEXT, which other plugins are
	 * known to overwrite. Every other script returns at once.
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != GOTR_HUD_UPDATE_SCRIPT || event.getScriptEvent() == null)
		{
			return;
		}
		onGotrHudScript(event.getScriptEvent().getArguments());
	}

	void onGotrHudScript(Object[] args)
	{
		Integer current = intArg(args, GOTR_SCRIPT_ARG_CURRENT_POWER);
		Integer max = intArg(args, GOTR_SCRIPT_ARG_MAX_POWER);
		if (current == null || max == null)
		{
			return;
		}
		gotrPowerCurrent = current;
		gotrPowerMax = max;
		gotrActiveElemental = GotrAltar.of(GotrAltar.Type.ELEMENTAL, intArg(args, GOTR_SCRIPT_ARG_ACTIVE_ELEMENTAL));
		gotrActiveCatalytic = GotrAltar.of(GotrAltar.Type.CATALYTIC, intArg(args, GOTR_SCRIPT_ARG_ACTIVE_CATALYTIC));
		if (gotrGameEnded)
		{
			// The ended game's power drops to 0; the next game's rises from it.
			if (current <= 0)
			{
				gotrEndedPowerReset = true;
			}
			else if (gotrEndedPowerReset)
			{
				gotrGameEnded = false;
				gotrEndedPowerReset = false;
			}
		}
	}

	static Integer intArg(Object[] args, int index)
	{
		if (args == null || index < 0 || index >= args.length || args[index] == null)
		{
			return null;
		}
		Object v = args[index];
		if (v instanceof Integer)
		{
			return (Integer) v;
		}
		try
		{
			return Integer.parseInt(v.toString().trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private boolean gotrHudLoaded()
	{
		return client.getWidget(InterfaceID.GotrHud.CONTENT) != null;
	}

	/**
	 * Client thread, once per logged-in tick: the GotR current game (see the
	 * gotrGame field's STABILITY note), final-energy provenance, a pending
	 * win line's corroboration window, the Rewards Guardian "Check" message
	 * box, and the crafting-candidate window. Two varbit reads, at most two
	 * widget lookups, and an altar-eligibility read only when the open rifts
	 * change (cached).
	 */
	void trackGotrTick()
	{
		int tick = client.getTickCount();
		boolean hudLoaded = gotrHudLoaded();
		boolean playing = client.getVarbitValue(VarbitID.GOTR_IS_PLAYING) == 1;
		// Crafting context: the game HUD, the game's own "playing" varbit, or
		// the GotR location -- the altars are outside the temple region, so
		// the HUD/varbit (not the region) is what covers an altar trip.
		gotrContext = hudLoaded || playing || StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT.equals(lastActivityKey);
		gotrRunes.onTick(tick);
		if (gotrPendingWinTick != null && tick - gotrPendingWinTick >= GOTR_WIN_CORROBORATION_TICKS)
		{
			// "Successfully closed the rift" with no count line after it.
			recordGotrWin(null);
		}
		if (hudLoaded || playing)
		{
			gotrLastInGameTick = tick;
		}
		else if (gotrLastInGameTick == null || tick - gotrLastInGameTick > GOTR_IN_GAME_GRACE_TICKS)
		{
			// Really out of the game: nothing is kept.
			gotrLastInGameTick = null;
			gotrPowerCurrent = null;
			gotrPowerMax = null;
			gotrActiveElemental = null;
			gotrActiveCatalytic = null;
			setGotrGame(null);
			return;
		}
		else
		{
			// A short gap (region load): keep the last known game as it is.
			return;
		}
		int elemental = client.getVarbitValue(VarbitID.GOTR_ELEMENTAL_EARNED_THIS_GAME);
		int catalytic = client.getVarbitValue(VarbitID.GOTR_CATALYTIC_EARNED_THIS_GAME);
		gotrTracker.onEnergy(elemental, catalytic);
		if (gotrGameEnded)
		{
			setGotrGame(null);
		}
		else
		{
			setGotrGame(GotrGameState.of(GotrGameState.powerPercent(gotrPowerCurrent, gotrPowerMax), elemental, catalytic,
				gotrActiveElemental, gotrActiveCatalytic, altarUsable(gotrActiveElemental), altarUsable(gotrActiveCatalytic),
				talismanCandidates()));
		}
		if (hudLoaded && StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT.equals(lastActivityKey))
		{
			// Rewards Guardian "Check" answer, if its message box is up: baseline only.
			gotrPoints.onCheckText(widgetText(InterfaceID.Messagebox.TEXT));
			refreshGotrWallet();
		}
	}

	/** Held portal talismans -> whether this player can use that altar (cached eligibility); empty when none. */
	private Map<GotrAltar, Boolean> talismanCandidates()
	{
		if (gotrTalismans.isEmpty())
		{
			return java.util.Collections.emptyMap();
		}
		Map<GotrAltar, Boolean> out = new java.util.EnumMap<>(GotrAltar.class);
		for (GotrAltar altar : gotrTalismans)
		{
			out.put(altar, altarUsable(altar));
		}
		return out;
	}

	private void setGotrGame(GotrGameState state)
	{
		if (Objects.equals(state, gotrGame))
		{
			return;
		}
		gotrGame = state;
		notifyProgressListener();
	}

	/** Runecraft XP corroborates a GotR altar craft (see ProductionTracker). */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() == Skill.FIREMAKING || event.getSkill() == Skill.AGILITY)
		{
			// Wintertodt goal / between-round opportunity: exact XP (cheap map put).
			wtSkillXp.put(event.getSkill(), event.getXp());
			return;
		}
		if (PestControlXp.isEligible(event.getSkill()))
		{
			// Pest Control XP goals: the exact XP the game reports (cheap map put).
			pestSkillXp.put(event.getSkill(), event.getXp());
			return;
		}
		if (event.getSkill() != Skill.RUNECRAFT)
		{
			return;
		}
		emitProduction(gotrRunes.onSkillXp(event.getXp(), client.getTickCount()));
	}

	/** RUNES CRAFTED: one confirmed GotR altar craft's exact rune output. */
	private void emitProduction(Map<Integer, Long> produced)
	{
		if (produced == null || produced.isEmpty())
		{
			return;
		}
		List<EventPayloads.ProducedItem> items = new ArrayList<>(produced.size());
		for (Map.Entry<Integer, Long> e : produced.entrySet())
		{
			items.add(new EventPayloads.ProducedItem(e.getKey(), itemNames.apply(e.getKey()),
				(int) Math.min(Integer.MAX_VALUE, e.getValue())));
		}
		eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_PRODUCTION,
			new EventPayloads.StructuredActivityProduction(StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT, items));
	}

	/** Brother-killed varbits only; every other varbit change is ignored at once. */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (BarrowsBrother.forKilledVarbit(event.getVarbitId()) != null)
		{
			readBarrowsProgress();
		}
		else if (GotrRunes.isRunePouchVarbit(event.getVarbitId()))
		{
			// Crafted runes of a type the pouch holds go straight into it.
			observeRunePouch();
		}
	}

	/**
	 * RUNES CRAFTED, second destination: the rune pouch (also the divine
	 * rune pouch -- same varbits), read natively from its slot varbits.
	 * Twelve varbit reads; only output deltas correlated with a confirmed
	 * craft ever count (see ProductionTracker).
	 */
	private void observeRunePouch()
	{
		emitProduction(gotrRunes.onSecondaryStore(readRunePouch(), client.getTickCount(), gotrContext));
	}

	Map<Integer, Long> readRunePouch()
	{
		return GotrRunes.readRunePouch(client::getVarbitValue, runePouchRuneItem);
	}

	private int runePouchRuneItemFromEnum(int type)
	{
		net.runelite.api.EnumComposition runes = client.getEnum(net.runelite.api.EnumID.RUNEPOUCH_RUNE);
		return runes == null ? -1 : runes.getIntValue(type);
	}

	/** Test seam: the pouch slot type -> rune item mapping (the game enum is not available headless). */
	void setRunePouchRuneItem(java.util.function.IntUnaryOperator mapping)
	{
		this.runePouchRuneItem = mapping;
	}

	/** Client thread: re-reads all six brother varbits (cheap) and publishes a change. */
	void readBarrowsProgress()
	{
		setBarrowsProgress(BarrowsRunProgress.read(client::getVarbitValue));
	}

	/** The current Barrows run as the game reports it, or null when unknown (not read yet / logged out). */
	public BarrowsRunProgress getBarrowsProgress()
	{
		return barrowsProgress;
	}

	/** Called (client thread) whenever the mirrored activity progress changes; UI invalidation only. */
	public void setProgressListener(Runnable listener)
	{
		this.progressListener = listener;
	}

	private void setBarrowsProgress(BarrowsRunProgress progress)
	{
		if (Objects.equals(progress, barrowsProgress))
		{
			return;
		}
		barrowsProgress = progress;
		notifyProgressListener();
	}

	private void notifyProgressListener()
	{
		Runnable listener = progressListener;
		if (listener != null)
		{
			try
			{
				listener.run();
			}
			catch (RuntimeException e)
			{
				// Presentation only: never let the UI affect collection.
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickClaimWindow();
		if (barrowsProgress == null && client.getGameState() == GameState.LOGGED_IN)
		{
			// First read after login / enable: the varbits are already synced,
			// so a run in progress shows its real brothers at once.
			readBarrowsProgress();
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		LocalPoint local = player.getLocalLocation();
		if (local == null)
		{
			return;
		}
		WorldPoint template = WorldPoint.fromLocalInstance(client, local);
		if (template == null)
		{
			return;
		}
		onRegion(template.getRegionID());
		readTemporossGame();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			trackTemporossTick();
			trackGotrTick();
			trackPestControlTick();
			trackWintertodtTick();
		}
	}

	/** Region -> at most one STRUCTURED_ACTIVITY_STATE, only when the resolved activity/policy changes. */
	void onRegion(int regionId)
	{
		if (regionId == lastRegionId)
		{
			return;
		}
		boolean firstObservation = lastRegionId == NO_REGION;
		int previousRegion = lastRegionId;
		lastRegionId = regionId;
		onPestControlRegion(previousRegion, regionId, firstObservation);

		StructuredActivityDefinition def = registry.forRegion(regionId);
		String key = def == null ? null : def.getKey();
		LocationPolicy policy = def == null ? null : def.policyFor(regionId);
		boolean changed = !Objects.equals(key, lastActivityKey) || policy != lastPolicy;
		lastActivityKey = key;
		lastPolicy = policy;
		// Outside on the first observation: nothing to disarm, nothing to say.
		if (!changed || (firstObservation && key == null))
		{
			return;
		}
		lastAccountHash = client.getAccountHash();
		eventLedger.append(lastAccountHash, EventType.STRUCTURED_ACTIVITY_STATE,
			new EventPayloads.StructuredActivityState(key, policy == null ? null : policy.name(), regionId));
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.DIALOG)
		{
			// NPC dialogue ("Speaker|text"): ONLY completion patterns of
			// activities that declare completionInDialogue are tried -- never
			// the GotR / Tempoross / claim parsing that game messages get.
			onDialogueMessage(Text.removeTags(event.getMessage()));
			return;
		}
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM
			&& event.getType() != ChatMessageType.MESBOX)
		{
			return;
		}
		onGameMessage(Text.removeTags(event.getMessage()));
	}

	/**
	 * One NPC dialogue line as RuneLite delivers it ("Squire|Congratulations!
	 * ..."): the speaker prefix is dropped and only dialogue-declared
	 * completion patterns are matched (live-verified 2026-10-01: the Pest
	 * Control win arrives this way, never as a game message).
	 */
	void onDialogueMessage(String message)
	{
		if (message == null)
		{
			return;
		}
		int bar = message.indexOf('|');
		String text = bar >= 0 ? message.substring(bar + 1) : message;
		StructuredActivityRegistry.ChatCompletion match = registry.matchCompletionDialogue(text);
		if (match != null)
		{
			recordChatCompletion(match);
		}
	}

	void onGameMessage(String message)
	{
		wtTracker.onChat(client.getTickCount(), message);
		onWintertodtSubdueLine(message);
		if (onWintertodtRewardLine(message))
		{
			return;
		}
		Integer pestStated = pestTracker.onBalanceMessage(message);
		if (pestStated != null)
		{
			publishPestBalance(pestStated);
			return;
		}
		TemporossGameTracker.Result game = temporossTracker.onGameMessage(message, client.getVarbitValue(VarbitID.TEMPOROSS_CURRENT_POINTS));
		if (game != null)
		{
			// The completion itself arrives as BOSS_KILL ("Tempoross"); this is
			// only that game's final score, for Avg / Best.
			eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_SCORE,
				new EventPayloads.StructuredActivityScore(StructuredActivityCatalog.TEMPOROSS, game.getKillCount(), game.getPoints(),
					game.isObservedFromStart()));
		}
		onGotrGameMessage(message);
		StructuredRewardSource claim = registry.rewardForClaimMessage(message, lastRegionId);
		if (claim != null)
		{
			if (StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT.equals(claim.getActivityKey()))
			{
				// A Rewards Guardian search spends one point of each.
				gotrPoints.onSearch();
				refreshGotrWallet();
			}
			armClaim(claim, itemsOf(client.getItemContainer(InventoryID.INV)));
			return;
		}
		StructuredActivityRegistry.ChatCompletion match = registry.matchCompletionChat(message);
		if (match == null)
		{
			return;
		}
		recordChatCompletion(match);
	}

	/**
	 * One matched completion line -> at most one STRUCTURED_ACTIVITY_COMPLETION
	 * (deduped by running count, or by a per-activity tick window when the
	 * message has no count), followed by the currency award the SAME line
	 * carries, if any -- so a duplicate line can add neither a second game
	 * nor a second award.
	 */
	private void recordChatCompletion(StructuredActivityRegistry.ChatCompletion match)
	{
		String key = match.getDefinition().getKey();
		Integer count = match.getCount();
		if (StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT.equals(key))
		{
			onGotrRiftsClosed(count);
			return;
		}
		if (count != null)
		{
			Integer previous = lastCompletionCount.get(key);
			if (previous != null && count <= previous)
			{
				return;
			}
			lastCompletionCount.put(key, count);
		}
		else
		{
			// No running count to dedupe on (Pest Control): the same text
			// reaching us through two accepted chat types for one game must
			// not become two games.
			int tick = client.getTickCount();
			Integer previousTick = lastCountlessCompletionTick.get(key);
			Integer window = match.getDefinition().getCompletionDedupeTicks();
			int dedupeTicks = window != null ? window : COUNTLESS_COMPLETION_DEDUPE_TICKS;
			if (previousTick != null && tick - previousTick >= 0 && tick - previousTick <= dedupeTicks)
			{
				return;
			}
			lastCountlessCompletionTick.put(key, tick);
		}
		boolean pestControl = StructuredActivityCatalog.PEST_CONTROL.equals(key);
		if (pestControl)
		{
			// AVG GAME: this won round's entry-to-win time, only when its start was observed.
			Integer seconds = pestTracker.onWin(client.getTickCount());
			if (seconds != null)
			{
				eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_SCORE,
					new EventPayloads.StructuredActivityScore(key, null, seconds, true));
			}
		}
		eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_COMPLETION,
			new EventPayloads.StructuredActivityCompletion(key, count, "CHAT"));
		String currencyKey = match.getDefinition().getCompletionCurrencyKey();
		Integer amount = match.getAmount();
		if (currencyKey != null && amount != null && amount > 0)
		{
			// Balance unknown from this line (the game states it separately).
			eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_CURRENCY,
				new EventPayloads.StructuredActivityCurrency(key, currencyKey, amount, -1));
			if (pestControl)
			{
				// A known balance advances; the "You now have N" box restates it shortly after.
				publishPestBalance(pestTracker.onAward(amount));
			}
		}
	}

	/**
	 * "Amount of rifts you have closed: N." -- a WON game, exactly once: a
	 * repeat of the same count (two chat types, a re-delivered line) is
	 * ignored, and a count arriving after its "successfully closed" line
	 * already stood in for it only records the count.
	 */
	private void onGotrRiftsClosed(Integer count)
	{
		String key = StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT;
		if (count != null)
		{
			Integer previous = lastCompletionCount.get(key);
			if (previous != null && count <= previous)
			{
				return;
			}
			lastCompletionCount.put(key, count);
		}
		if (gotrCountlessWinTick != null && client.getTickCount() - gotrCountlessWinTick <= GOTR_IN_GAME_GRACE_TICKS)
		{
			// This game was already counted from its "successfully closed" line.
			gotrCountlessWinTick = null;
			return;
		}
		recordGotrWin(count);
	}

	/** One won game: its final energy for Avg / Best, then the completion that counts it. */
	private void recordGotrWin(Integer count)
	{
		String key = StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT;
		gotrPendingWinTick = null;
		gotrCountlessWinTick = count == null ? client.getTickCount() : null;
		GotrGameTracker.Result won = gotrTracker.onCompletion(count,
			client.getVarbitValue(VarbitID.GOTR_ELEMENTAL_EARNED_THIS_GAME),
			client.getVarbitValue(VarbitID.GOTR_CATALYTIC_EARNED_THIS_GAME));
		eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_SCORE,
			new EventPayloads.StructuredActivityScore(key, won.getRiftsClosed(), won.getTotalEnergy(), won.isObservedFromStart()));
		eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_COMPLETION,
			new EventPayloads.StructuredActivityCompletion(key, count, "CHAT"));
		endGotrGame();
	}

	/** GotR win / failure lines, end-of-game reward points, and the "Check" answer if it reaches chat. */
	void onGotrGameMessage(String message)
	{
		if (GotrGameTracker.isFailure(message) || GotrGameTracker.isNoContribution(message))
		{
			// Lost, or closed without crediting this player: not a game won,
			// and it cancels a "successfully closed" line still waiting.
			gotrTracker.onFailure();
			gotrPendingWinTick = null;
			endGotrGame();
			return;
		}
		if (GotrGameTracker.isWin(message))
		{
			// Corroborates the count line that follows on the same tick; stands
			// in for it only if it never comes (see trackGotrTick).
			if (gotrPendingWinTick == null)
			{
				gotrPendingWinTick = client.getTickCount();
			}
			return;
		}
		if (gotrPoints.onCheckText(message))
		{
			refreshGotrWallet();
			return;
		}
		GotrRewardPointTracker.Gains gains = gotrPoints.onGameMessage(message);
		refreshGotrWallet();
		if (gains == null)
		{
			return;
		}
		if (gains.getElementalGained() > 0)
		{
			eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_CURRENCY,
				new EventPayloads.StructuredActivityCurrency(StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT,
					GOTR_ELEMENTAL_POINTS, gains.getElementalGained(), gains.getElementalBalance()));
		}
		if (gains.getCatalyticGained() > 0)
		{
			eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_CURRENCY,
				new EventPayloads.StructuredActivityCurrency(StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT,
					GOTR_CATALYTIC_POINTS, gains.getCatalyticGained(), gains.getCatalyticBalance()));
		}
	}

	public static final String GOTR_ELEMENTAL_POINTS = "elemental_points";
	public static final String GOTR_CATALYTIC_POINTS = "catalytic_points";

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.PEST_REWARDSHOP)
		{
			onPestRewardShopOpened();
			return;
		}
		StructuredRewardSource reward = registry.rewardForInterface(event.getGroupId());
		if (reward == null)
		{
			return;
		}
		onRewardInterface(reward, itemsOf(client.getItemContainer(reward.getContainerId())), itemNames);
	}

	/** REWARD_INTERFACE producer: the container's exact contents are the reward. */
	void onRewardInterface(StructuredRewardSource reward, Map<Integer, Long> containerItems, IntFunction<String> itemName)
	{
		emitReward(reward, containerItems, itemName);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.WORN)
		{
			Map<Integer, Long> worn = itemsOf(event.getItemContainer());
			voidOwnership.update(VoidOwnership.Container.EQUIPMENT, worn);
			wtWorn = worn;
			refreshWintertodtTools();
			return;
		}
		if (event.getContainerId() == InventoryID.BANK)
		{
			voidOwnership.update(VoidOwnership.Container.BANK, itemsOf(event.getItemContainer()));
			return;
		}
		if (event.getContainerId() != InventoryID.INV)
		{
			return;
		}
		Map<Integer, Long> inventory = itemsOf(event.getItemContainer());
		voidOwnership.update(VoidOwnership.Container.INVENTORY, inventory);
		onWintertodtInventory(inventory);
		// The pouch is read first so its baseline exists before any craft
		// (it may not have changed since login).
		observeRunePouch();
		// Portal talismans held: extra altars the player can enter (STRATEGY).
		gotrTalismans = GotrPortalTalismans.heldIn(inventory);
		// RUNES CRAFTED: guardian essence consumed + runes gained, in the GotR
		// game (HUD loaded) -- confirmed by Runecraft XP (ProductionTracker).
		emitProduction(gotrRunes.onInventory(inventory, client.getTickCount(), gotrContext));
		if (pendingClaim == null)
		{
			return;
		}
		onInventoryChanged(inventory, itemNames);
	}

	/**
	 * CLAIM_MESSAGE producer, second half: the ONE inventory change after an
	 * armed claim is consumed -- whatever it added (possibly nothing) -- and
	 * the correlation disarms.
	 */
	void onInventoryChanged(Map<Integer, Long> inventory, IntFunction<String> itemName)
	{
		StructuredRewardSource reward = pendingClaim;
		if (reward == null)
		{
			return;
		}
		Map<Integer, Long> added = new LinkedHashMap<>();
		for (Map.Entry<Integer, Long> e : inventory.entrySet())
		{
			long before = claimSnapshot.getOrDefault(e.getKey(), 0L);
			if (e.getValue() > before)
			{
				added.put(e.getKey(), e.getValue() - before);
			}
		}
		disarmClaim();
		emitReward(reward, added, itemName);
	}

	/** CLAIM_MESSAGE producer, first half: arm (or keep) the correlation for this source. */
	void armClaim(StructuredRewardSource reward, Map<Integer, Long> inventoryNow)
	{
		if (pendingClaim != reward)
		{
			pendingClaim = reward;
			claimSnapshot = new HashMap<>(inventoryNow);
		}
		claimTicksLeft = CLAIM_WINDOW_TICKS;
	}

	/** One game tick of the claim window; an unanswered claim expires silently. */
	void tickClaimWindow()
	{
		if (pendingClaim != null && --claimTicksLeft <= 0)
		{
			disarmClaim();
		}
	}

	boolean isClaimArmed()
	{
		return pendingClaim != null;
	}

	private void disarmClaim()
	{
		pendingClaim = null;
		claimSnapshot = new HashMap<>();
		claimTicksLeft = 0;
	}

	private void emitReward(StructuredRewardSource reward, Map<Integer, Long> items, IntFunction<String> itemName)
	{
		if (items.isEmpty())
		{
			return;
		}
		List<EventPayloads.StructuredRewardItem> payloadItems = new ArrayList<>(items.size());
		for (Map.Entry<Integer, Long> e : items.entrySet())
		{
			payloadItems.add(new EventPayloads.StructuredRewardItem(e.getKey(), itemName.apply(e.getKey()), (int) Math.min(Integer.MAX_VALUE, e.getValue())));
		}
		eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_REWARD,
			new EventPayloads.StructuredReward(reward.getActivityKey(), reward.getKey(), reward.getDisplayName(), payloadItems));
	}

	/** Item id -> total quantity, first-seen order; empty slots skipped. */
	static Map<Integer, Long> itemsOf(ItemContainer container)
	{
		Map<Integer, Long> items = new LinkedHashMap<>();
		if (container == null)
		{
			return items;
		}
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				items.merge(item.getId(), (long) item.getQuantity(), Long::sum);
			}
		}
		return items;
	}

	private void forgetLocation()
	{
		lastRegionId = NO_REGION;
		lastActivityKey = null;
		lastPolicy = null;
	}
}
