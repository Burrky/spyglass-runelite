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
import com.osrstelemetry.plugin.activity.StructuredActivityDefinition;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.activity.StructuredRewardSource;
import com.osrstelemetry.plugin.activity.TemporossGameState;
import com.osrstelemetry.plugin.activity.TemporossGameTracker;
import com.osrstelemetry.plugin.activity.StructuredActivityCatalog;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
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
			if (lastActivityKey != null && lastAccountHash != null)
			{
				eventLedger.append(lastAccountHash, EventType.STRUCTURED_ACTIVITY_STATE,
					new EventPayloads.StructuredActivityState(null, null, lastRegionId));
			}
			forgetLocation();
			disarmClaim();
			setBarrowsProgress(null);
			setTemporossGame(null);
			temporossTracker.reset();
			setGotrGame(null);
			resetGotrTracking();
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
		lastRegionId = regionId;

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
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM
			&& event.getType() != ChatMessageType.MESBOX)
		{
			return;
		}
		onGameMessage(Text.removeTags(event.getMessage()));
	}

	void onGameMessage(String message)
	{
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
			if (previousTick != null && tick - previousTick >= 0 && tick - previousTick <= COUNTLESS_COMPLETION_DEDUPE_TICKS)
			{
				return;
			}
			lastCountlessCompletionTick.put(key, tick);
		}
		eventLedger.append(client.getAccountHash(), EventType.STRUCTURED_ACTIVITY_COMPLETION,
			new EventPayloads.StructuredActivityCompletion(key, count, "CHAT"));
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
		if (event.getContainerId() != InventoryID.INV)
		{
			return;
		}
		Map<Integer, Long> inventory = itemsOf(event.getItemContainer());
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
