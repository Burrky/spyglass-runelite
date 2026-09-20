package com.osrstelemetry.plugin;

import com.google.inject.Provides;
import com.osrstelemetry.plugin.collectors.ActivityKillCountCollector;
import com.osrstelemetry.plugin.collectors.BossActivityContextCollector;
import com.osrstelemetry.plugin.collectors.CollectionLogCollector;
import com.osrstelemetry.plugin.collectors.CombatAchievementCollector;
import com.osrstelemetry.plugin.collectors.ContainerCollector;
import com.osrstelemetry.plugin.collectors.IdentityCollector;
import com.osrstelemetry.plugin.collectors.KnownBossRegistry;
import com.osrstelemetry.plugin.collectors.LoadoutArchiveCollector;
import com.osrstelemetry.plugin.collectors.LootCollector;
import com.osrstelemetry.plugin.collectors.NpcDeathCollector;
import com.osrstelemetry.plugin.collectors.NpcInteractionTargetCollector;
import com.osrstelemetry.plugin.collectors.PotionStorageCollector;
import com.osrstelemetry.plugin.collectors.QuestCollector;
import com.osrstelemetry.plugin.collectors.ServerNpcLootCollector;
import com.osrstelemetry.plugin.collectors.SkillsCollector;
import com.osrstelemetry.plugin.collectors.SlayerCollector;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.history.HistoryCoordinator;
import com.osrstelemetry.plugin.loottracker.LootTrackerCoordinator;
import com.osrstelemetry.plugin.loottracker.LootTrackerPreferences;
import com.osrstelemetry.plugin.session.SessionRuntimeCoordinator;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.ui.OsrsTelemetryPanel;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;

/**
 * Telemetry only: this plugin observes and records. It never sends
 * input, never touches Jagex auth, and (in this step) never makes a
 * network call — everything here writes to local files only. Remote
 * sync is a later step, added on top of this without changing how
 * collectors behave.
 */
// RENAMED (Current Session hysteresis pass, PART 3 -- public branding
// cleanup, live UI bug: the plugin/settings panel still showed "OSRS
// Telemetry" instead of the current public name, Spyglass). PRESENTATION
// ONLY -- @PluginDescriptor.name is RuneLite's own display string for the
// Plugin Hub/plugin list/settings panel title; it is NOT the config
// group id (that stays "osrstelemetry" -- see OsrsTelemetryConfig's
// @ConfigGroup, deliberately untouched), the Java package name, or any
// other compatibility-sensitive identifier.
// ADDED (Plugin Hub maintainer review -- Filepath migration).
// Filepath requires a Plugin Hub internal name. Plugin Hub validates this
// against the real internal name assigned by the Hub entry, so this must be
// "spyglass" to match plugins/spyglass. This is intentionally independent of
// the existing "osrstelemetry" config group and Java package, which remain
// unchanged for compatibility.
// legacyDataDirectory = "osrs-telemetry" is the exact literal old
// TelemetryPaths.ROOT_DIR_NAME -- see TelemetryPaths' own class
// javadoc for the one-time automatic move RuneLite's own
// getPluginDirectory() performs using this value, and
// PLUGIN_HUB_FILEPATH_MIGRATION_REPORT for the full audit.
@PluginDescriptor(
	name = "Spyglass — Activity & Loot Tracker",
	description = "Passively records account state to local files for read-only AI/tool consumption.",
	tags = {"telemetry", "export", "json", "spyglass"},
	internalName = "spyglass",
	legacyDataDirectory = "osrs-telemetry"
)
@PluginDependency(SlayerPlugin.class)
public class OsrsTelemetryPlugin extends Plugin
{
	// Quest state changes rarely; polling it every tick would be
	// wasted work for ~200+ quests. Checked roughly every 30s.
	private static final int QUEST_CHECK_INTERVAL_TICKS = 50;

	// FIX for review item 16: XP_CHANGE events are closed out on this
	// slow cadence, independent of the (still every-tick) state-file
	// write — see SkillsCollector javadoc.
	private static final int XP_FLUSH_INTERVAL_TICKS = 50;

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	// ADDED (client-thread lifecycle audit): needed to marshal
	// Client-only work performed by startUp()/onConfigChanged() (both
	// of which RuneLite can invoke on the AWT/Swing thread) onto the
	// client thread -- see startUp()/onConfigChanged() below.
	@Inject
	private ClientThread clientThread;

	@Inject
	private LocalStateStore store;

	@Inject
	private EventLedger eventLedger;

	@Inject
	private AccountContext accountContext;

	@Inject
	private IdentityCollector identityCollector;

	@Inject
	private SkillsCollector skillsCollector;

	@Inject
	private ContainerCollector containerCollector;

	// ADDED (Spyglass History implementation pass, Checkpoint 2). See
	// LoadoutArchiveCollector/LoadoutArchive's own class javadoc --
	// entirely independent of containerCollector above (deliberately
	// touches none of its already-hardened bank-snapshot/dedup logic),
	// feeding a short in-memory archive SessionPersistence uses to
	// resolve a finalized session's starting/ending loadout. Registered/
	// unregistered exactly like every other collector below, plus the
	// same login-time seed / account-switch reset containerCollector
	// already gets (see handleLogin() below).
	@Inject
	private LoadoutArchiveCollector loadoutArchiveCollector;

	@Inject
	private QuestCollector questCollector;

	@Inject
	private SlayerCollector slayerCollector;

	@Inject
	private CollectionLogCollector collectionLogCollector;

	@Inject
	private CombatAchievementCollector combatAchievementCollector;

	@Inject
	private ActivityKillCountCollector activityKillCountCollector;

	@Inject
	private LootCollector lootCollector;

	// ADDED (server-side loot data-foundation pass) — see class javadoc.
	// Independent of lootCollector/NPC_LOOT_ATTRIBUTED; both are
	// registered/unregistered together only because they share the
	// same collectLoot() config gate, not because either depends on
	// the other.
	@Inject
	private ServerNpcLootCollector serverNpcLootCollector;

	@Inject
	private PotionStorageCollector potionStorageCollector;

	// FIX (Step 6 — event truth-semantics correction): renamed from
	// npcKillCollector — see NpcDeathCollector's class javadoc.
	@Inject
	private NpcDeathCollector npcDeathCollector;

	// ADDED (Task 5 — generic first-boss encounter context pass). See
	// EventType.BOSS_ACTIVITY_CONTEXT's and BossActivityContextCollector's
	// own class javadoc.
	@Inject
	private BossActivityContextCollector bossActivityContextCollector;

	// ADDED (universal generic NPC recognition pass). See
	// EventType.NPC_INTERACTION_TARGET's and NpcInteractionTargetCollector's
	// own class javadoc. Independent of bossActivityContextCollector --
	// both subscribe to the same InteractingChanged event for different,
	// non-conflicting purposes (see that constant's own javadoc).
	@Inject
	private NpcInteractionTargetCollector npcInteractionTargetCollector;

	// ADDED (Task 5 — generic first-boss encounter context pass).
	// Injected here (not just into the two collectors that use it)
	// because handleLogin()'s real-account-switch block below needs to
	// call its resetForAccountSwitch(previousAccountHash) directly, the
	// same way it already does for activityKillCountCollector/
	// serverNpcLootCollector.
	@Inject
	private KnownBossRegistry knownBossRegistry;

	// ADDED (live session-runtime wiring pass). See its own class
	// javadoc for the full design -- receives already-normalized
	// telemetry via an EventLedger listener, batches/resolves/persists
	// session lifecycle and aggregates. Registered/unregistered exactly
	// like every other collector below (same enable/disable/re-enable
	// restartability requirement), PLUS an EventLedger listener
	// registration, which is the one part no other collector needs.
	@Inject
	private SessionRuntimeCoordinator sessionRuntimeCoordinator;

	// ADDED (Spyglass Phase 2 -- persistent Loot Tracker). Entirely
	// independent of sessionRuntimeCoordinator -- see its own class
	// javadoc. Registered/unregistered as an EventLedger listener the
	// exact same way sessionRuntimeCoordinator is (both below), plus its
	// own start()/shutdown() lifecycle for the background rebuild
	// executor (see its own javadoc for why that rebuild must never run
	// on the client thread).
	@Inject
	private LootTrackerCoordinator lootTrackerCoordinator;

	// ADDED (Spyglass Phase 2 QOL -- favorites/hide/reset/collapse).
	// Loaded per-account in handleLogin() exactly like
	// lootTrackerCoordinator, but carries no telemetry of its own -- see
	// its own class javadoc for the durable-preference/durable-telemetry
	// split.
	@Inject
	private LootTrackerPreferences lootTrackerPreferences;

	// ADDED (Spyglass History implementation pass, Checkpoint 3). Own
	// start()/shutdown()/ensureAccountLoaded() lifecycle, mirroring
	// lootTrackerCoordinator immediately above -- see HistoryCoordinator's
	// own class javadoc. Not yet consumed by any UI (that is Checkpoint
	// 4/5's job -- the Session | Loot tab row is unchanged this
	// checkpoint); wiring its lifecycle in now keeps the device never
	// half-wired between checkpoints while giving the background index a
	// head start before the History tab exists to read it.
	@Inject
	private HistoryCoordinator historyCoordinator;

	// ADDED (player-facing Current Session UI pass). Standard RuneLite
	// sidebar-panel wiring: inject ClientToolbar, build a NavigationButton
	// wrapping this plugin's own OsrsTelemetryPanel in startUp(), add it
	// via clientToolbar.addNavigation(), and remove it in shutDown() via
	// clientToolbar.removeNavigation() -- see startUp()/shutDown() below.
	// This is the first UI code in this project (see
	// PLAYER_FACING_UI_ARCHITECTURE_BLUEPRINT.md, which previously noted
	// "zero UI code" existed).
	@Inject
	private ClientToolbar clientToolbar;

	// ADDED (Current Session XP level-progress pass). RuneLite's own
	// singleton skill-icon lookup, injected here (this class IS
	// Guice-managed, unlike OsrsTelemetryPanel/CurrentSessionView
	// below, which are plain-`new`'d) and threaded down through
	// OsrsTelemetryPanel's constructor -- see startUp() below.
	@Inject
	private SkillIconManager skillIconManager;

	// ADDED (Current Session UI facelift). Authentic OSRS item sprites
	// for the Loot section (ItemManager#getImage) and GE prices
	// (ItemManager#getItemPrice) -- the same RuneLite-native plumbing
	// this project's own collectors already use (see
	// ContainerCollector/LootCollector/ServerNpcLootCollector).
	@Inject
	private ItemManager itemManager;

	// ADDED (Current Session UI facelift). Threaded down into
	// CurrentSessionView so it can read config.lootValuationMode() live
	// on every render -- the same "read the live config value, don't
	// cache it" idiom every collector in this project already follows.
	@Inject
	private OsrsTelemetryConfig config;

	private OsrsTelemetryPanel panel;
	private NavigationButton navButton;

	private int questCheckTickCounter = 0;
	private int xpFlushTickCounter = 0;

	// Identity capture must wait for
	// client.getLocalPlayer() to be non-null: capturing immediately
	// on GameStateChanged(LOGGED_IN) is a well-known RuneLite gotcha
	// where the local Player entity hasn't spawned yet for a tick or
	// two, which is exactly why a real identity.json was observed with
	// displayName=null (IdentityCollector.captureOnLogin() defensively
	// null-checks getLocalPlayer(), so it silently succeeded with a
	// null name rather than throwing). This flag + trySeedIdentity()
	// retries on GameTick — readiness-gated, not an arbitrary sleep —
	// until the player object actually exists, then captures exactly
	// once per login.
	private volatile boolean identityCapturePending = false;

	/**
	 * ADDED (client-thread lifecycle audit -- requirement 2/5:
	 * duplicate-initialization guard). Client-independent so it can be
	 * unit-tested directly (see OsrsTelemetryPluginLifecycleTest) --
	 * same "extract the pure decision, test that" pattern as
	 * SlayerCollector.SlayerBaselineHydration/AccountContext elsewhere
	 * in this codebase. See startUp()/onGameStateChanged() for how it's
	 * used.
	 */
	private final StartupHydrationGate startupHydrationGate = new StartupHydrationGate();

	@Provides
	OsrsTelemetryConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsTelemetryConfig.class);
	}

	@Override
	protected void startUp() throws IOException
	{
		// ADDED (Plugin Hub maintainer review -- Filepath migration).
		// Establishes the ONE root every TelemetryPaths call resolves
		// against, before anything else below can possibly touch
		// storage (store.start()/every collector's own I/O all go
		// through TelemetryPaths). getPluginDirectory() is RuneLite's
		// own Filepath API (Plugin.getPluginDirectory(), declared to
		// throw IOException -- allowed to propagate here: a plugin that
		// cannot establish its own storage root has nothing safe to do,
		// so failing this enable outright is correct, not a
		// papered-over fallback). Idempotent to call again on every
		// startUp() -- see TelemetryPaths.init()'s own javadoc.
		Filepath pluginDirectory = getPluginDirectory();
		TelemetryPaths.init(pluginDirectory);

		// FIX for review item 9: executors are (re)created here, not
		// assumed to exist from construction — safe across repeated
		// enable/disable/enable within one client process.
		store.start();
		eventLedger.start();
		eventLedger.startNewSession();
		// ADDED (Spyglass Phase 2 -- persistent Loot Tracker). Same
		// restartability requirement as every other executor-owning
		// component here -- see its own start()/shutdown() javadoc.
		lootTrackerCoordinator.start();
		// ADDED (Spyglass History implementation pass, Checkpoint 3). See
		// HistoryCoordinator's own start()/shutdown() javadoc -- same
		// restartability contract as lootTrackerCoordinator above.
		historyCoordinator.start();

		eventBus.register(skillsCollector);
		eventBus.register(containerCollector);
		eventBus.register(loadoutArchiveCollector);
		eventBus.register(slayerCollector);
		eventBus.register(collectionLogCollector);
		eventBus.register(combatAchievementCollector);
		eventBus.register(activityKillCountCollector);
		eventBus.register(lootCollector);
		eventBus.register(serverNpcLootCollector);
		eventBus.register(potionStorageCollector);
		eventBus.register(npcDeathCollector);
		eventBus.register(bossActivityContextCollector);
		eventBus.register(npcInteractionTargetCollector);
		eventBus.register(sessionRuntimeCoordinator);

		// ADDED (live session-runtime wiring pass). Paired with
		// removeListener() in shutDown() below -- EventLedger's listener
		// list is NOT cleared by eventLedger.start()/shutdown() (see its
		// own javadoc), so omitting the shutDown() removal would silently
		// double-register this coordinator on every re-enable, exactly
		// the class of bug the client-thread lifecycle audit already
		// found and fixed for EventBus registration itself.
		eventLedger.addListener(sessionRuntimeCoordinator);
		// ADDED (Spyglass Phase 2 -- persistent Loot Tracker). Paired with
		// removeListener() in shutDown() below, same reasoning as
		// sessionRuntimeCoordinator's own registration immediately above.
		eventLedger.addListener(lootTrackerCoordinator);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// RuneLite can invoke Plugin.startUp() directly on the
			// AWT/Swing thread when the user enables the plugin from the
			// plugin panel. handleLogin() transitively touches Client APIs
			// through every collector it calls (identity, skills,
			// container, quest, slayer) and asserts it runs on the client
			// thread, so it must never run directly on whatever thread
			// called startUp() -- marshal onto ClientThread instead.
			//
			// Idempotency: a normal GameStateChanged(LOGGED_IN) could in
			// principle race in before this deferred callback runs (e.g. a
			// fast relog right after the plugin is enabled). Both this
			// deferred callback and onGameStateChanged() ultimately
			// execute ON the client thread, so there is no data race
			// between them -- startupHydrationGate exists purely to stop
			// handleLogin() from running twice for the same enable, not
			// to protect concurrent access.
			startupHydrationGate.arm();
			clientThread.invokeLater(() ->
			{
				if (startupHydrationGate.consumeIfPending())
				{
					handleLogin();
				}
			});
		}
		// ADDED (player-facing Current Session UI pass). Panel
		// construction is plain Swing/Java (no Client access), so unlike
		// handleLogin() above it needs no ClientThread marshalling and is
		// safe to run directly here regardless of which thread invoked
		// startUp(). Re-created on every startUp() (paired with the
		// removeNavigation()/shutdown() teardown in shutDown() below) so a
		// disable/re-enable cycle never leaves a stale panel/timer behind.
		// ADDED (Spyglass History implementation pass, Checkpoint 5). Threads
		// historyCoordinator through to the panel so its third (History) tab
		// can actually read recent entries / load detail -- see
		// OsrsTelemetryPanel's own updated constructor and class javadoc.
		panel = new OsrsTelemetryPanel(sessionRuntimeCoordinator, lootTrackerCoordinator, lootTrackerPreferences,
			historyCoordinator, skillIconManager, itemManager, clientThread, npcInteractionTargetCollector, config);

		// REPLACED (approved spyglass sidebar icon pass). The approved
		// gold/blue spyglass artwork the user supplied, cropped to its own
		// content bounding box and resized to the project's existing
		// RuneLite sidebar icons use the 24x24 convention.
		// Loaded via the exact same
		// ImageUtil.loadImageResource()/NavigationButton mechanism already
		// used by this plugin; nothing about that mechanism changed.
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/com/osrstelemetry/plugin/ui/spyglass.png");
		// RENAMED (Current Session hysteresis pass, PART 3 -- public
		// branding cleanup, live UI bug: sidebar icon hover tooltip still
		// showed "OSRS Telemetry"). Short label context -> just "Spyglass",
		// per the same presentation-only rule as @PluginDescriptor.name
		// above.
		navButton = NavigationButton.builder()
			.tooltip("Spyglass")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(skillsCollector);
		eventBus.unregister(containerCollector);
		eventBus.unregister(loadoutArchiveCollector);
		eventBus.unregister(slayerCollector);
		eventBus.unregister(collectionLogCollector);
		eventBus.unregister(combatAchievementCollector);
		eventBus.unregister(activityKillCountCollector);
		eventBus.unregister(lootCollector);
		eventBus.unregister(serverNpcLootCollector);
		eventBus.unregister(potionStorageCollector);
		eventBus.unregister(npcDeathCollector);
		eventBus.unregister(bossActivityContextCollector);
		eventBus.unregister(npcInteractionTargetCollector);
		// sessionRuntimeCoordinator is deliberately NOT unregistered here
		// -- see the comment at its actual unregistration point below,
		// right before store.shutdown()/eventLedger.shutdown(), for why.

		// RuneLite can invoke Plugin.shutDown() directly on the AWT/Swing
		// thread (e.g. an explicit plugin disable from the plugin panel).
		// Bank finalization is durability-critical AND gated/irreversible
		// (see ContainerCollector's own durability javadoc), so it runs
		// FIRST, immediately after unregistering, unconditionally, so
		// nothing later in this method can ever prevent it from running --
		// in particular, questCollector's own check below must never run
		// before this line, since it can throw and abort shutDown() before
		// bank finalization is reached.
		//
		// flushPendingAndWait() (not the async flushPending()) blocks
		// until the snapshot -> pointer -> event chain is actually done
		// before the executors below are allowed to shut down.
		containerCollector.flushPendingAndWait();

		// questCollector.checkAndFlush() deliberately does NOT run here.
		// Unlike Bank
		// (gated/irreversible -- see above), quest completion is
		// CONTINUOUSLY OBSERVABLE: the plugin's own periodic GameTick
		// cadence (QUEST_CHECK_INTERVAL_TICKS, ~30s) already keeps
		// quests.json fresh and diffs for QUEST_COMPLETED while the
		// plugin is enabled, and the very next observation window after
		// a relaunch/re-enable silently reseeds (checkAndFlush()'s own
		// justFinished(null, ...) guard / captureInitialStateWithoutBaseline())
		// rather than replaying what was missed. Worst case from
		// removing this call: a quest finished in the last <30s before
		// an explicit disable goes unrecorded until the next session
		// observes its FINISHED state anew -- a delayed/lost
		// continuously-observable reading, not lost durable/gated
		// state, and an explicitly acceptable tradeoff versus either
		// (a) the confirmed shutdown crash above, or (b) blocking the
		// calling thread on a synchronous ClientThread round-trip here.
		// (b) was deliberately rejected: shutDown() can itself already
		// run ON the client thread in the normal (non-AWT-triggered)
		// client-exit path, and even when it doesn't, the client thread
		// can be blocked waiting on AWT (e.g. a Swing dialog) during
		// shutdown -- AWT blocking on ClientThread while ClientThread
		// may itself be blocked on AWT is exactly the mutual-deadlock
		// shape a synchronous wait here would risk, for a read this
		// method does not actually need. skillsCollector.
		// closeXpWindowsIfDue() below is unaffected by any of this: it
		// only reads client.getAccountHash() (a cached identifier, not
		// a live client-thread-guarded read) and otherwise operates
		// purely on SkillsCollector's own in-memory state.
		skillsCollector.closeXpWindowsIfDue();

		// ADDED (live session-runtime wiring pass, ARCHITECTURAL
		// REQUIREMENT 8); ORDERING FIXED (adversarial hardening pass, item
		// 2): flush any tick batch still pending (there may be no further
		// GameTick to do it -- the plugin is disabling) and persist current
		// session state exactly as it is, without fabricating a
		// timeout-driven transition (disabling is not itself a real
		// inactivity gap). This MUST run -- and the coordinator's
		// EventLedger listener MUST still be registered -- AFTER
		// containerCollector.flushPendingAndWait() and
		// skillsCollector.closeXpWindowsIfDue() above, because both of
		// those can themselves emit final telemetry (BANK_SNAPSHOT,
		// XP_CHANGE) that the session runtime needs to actually see. Only
		// once this flush is done is it safe to stop the coordinator from
		// receiving anything further.
		sessionRuntimeCoordinator.flushPendingAndShutdown();

		// Now safe to stop the coordinator from receiving anything further
		// -- every collector flush able to emit final telemetry has
		// already run and already been consumed above.
		eventBus.unregister(sessionRuntimeCoordinator);
		eventLedger.removeListener(sessionRuntimeCoordinator);
		// ADDED (Spyglass Phase 2 -- persistent Loot Tracker). Paired with
		// addListener()/start() in startUp() -- see EventLedger's own
		// javadoc for why an unpaired addListener() would silently
		// double-register on every re-enable.
		eventLedger.removeListener(lootTrackerCoordinator);
		lootTrackerCoordinator.shutdown();
		// ADDED (Spyglass History implementation pass, Checkpoint 3).
		// historyCoordinator is not an EventLedger listener (it reads
		// finalized session files directly, never events.jsonl -- see
		// its own class javadoc), so only its executor needs draining.
		historyCoordinator.shutdown();

		// ADDED (player-facing Current Session UI pass). Mirror image of
		// startUp()'s addNavigation()/panel construction: stop the panel's
		// live-update timer first (nothing left to poll once the plugin is
		// disabling), then remove the sidebar icon. Both are plain
		// Swing/RuneLite-UI calls -- no ClientThread marshalling needed,
		// unlike the Client-touching work above.
		if (panel != null)
		{
			panel.shutdown();
			panel = null;
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}

		// Graceful drain — see LocalStateStore/EventLedger javadoc.
		store.shutdown();
		eventLedger.shutdown();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// A genuine login-type transition always supersedes any
			// still-pending deferred startup hydration armed by
			// startUp() above -- this event is delivered on the client
			// thread, so calling handleLogin() directly here remains
			// safe (unchanged from before this pass).
			startupHydrationGate.supersede();
			handleLogin();
		}
	}

	/**
	 * FIX for review item 8 (multi-account lifecycle) + item 17
	 * (initial state capture). Runs on every LOGGED_IN transition,
	 * which covers both "opened the client" and "switched accounts
	 * without closing the client" — RuneLite has no separate signal
	 * for the latter, but it always passes through LOGGED_IN.
	 */
	private void handleLogin()
	{
		long newAccountHash = client.getAccountHash();
		long previousAccountHash = accountContext.update(newAccountHash);

		// ADDED (live session-runtime wiring pass). See
		// SessionRuntimeCoordinator.ensureAccountLoaded()'s own javadoc for
		// why this is safe and correct to call unconditionally here,
		// covering a fresh plugin start, a real account switch, AND a
		// same-account world hop/relog alike without a hop ever resetting
		// or reloading in-memory session state.
		sessionRuntimeCoordinator.ensureAccountLoaded(newAccountHash);
		// ADDED (Spyglass Phase 2 -- persistent Loot Tracker). Unconditional
		// on every LOGGED_IN transition, exactly like
		// sessionRuntimeCoordinator.ensureAccountLoaded() immediately
		// above -- both methods are internally no-ops for a same-account
		// call (a world hop/relog), so this never re-runs the durable
		// rebuild or re-reads the preferences file for continuity within
		// the same account. See each method's own javadoc.
		lootTrackerCoordinator.ensureAccountLoaded(newAccountHash);
		lootTrackerPreferences.loadForAccount(newAccountHash);
		// ADDED (Spyglass History implementation pass, Checkpoint 3 --
		// spec Part S). Same unconditional-on-every-LOGGED_IN-transition
		// call as lootTrackerCoordinator/sessionRuntimeCoordinator above;
		// internally a no-op for a same-account call (see
		// HistoryCoordinator.ensureAccountLoaded()'s own javadoc).
		historyCoordinator.ensureAccountLoaded(newAccountHash);

		if (accountContext.isRealSwitch(previousAccountHash, newAccountHash))
		{
			// Flush anything the OLD account still owed (e.g. an
			// outstanding XP window) before clearing baselines, then
			// clear every collector's in-memory state so account B's
			// first observations are never diffed against account A's
			// leftovers.
			skillsCollector.resetForAccountSwitch(previousAccountHash);
			questCollector.resetForAccountSwitch();
			containerCollector.resetForAccountSwitch();
			// ADDED (Spyglass History implementation pass, Checkpoint 2 --
			// spec Part S, account isolation). See LoadoutArchive's own
			// class javadoc for why a full clear (no per-account keying)
			// is the correct, sufficient reset here.
			loadoutArchiveCollector.resetForAccountSwitch();
			activityKillCountCollector.resetForAccountSwitch();
			serverNpcLootCollector.resetForAccountSwitch();
			// ADDED (Task 5 — generic first-boss encounter context pass):
			// per-account confirmed-boss state and the collector's own
			// per-account "currently tracked boss" dedup state must never
			// leak from the OLD account to the NEW one, same reasoning as
			// every other real-switch reset on this list.
			knownBossRegistry.resetForAccountSwitch(previousAccountHash);
			bossActivityContextCollector.resetForAccountSwitch(previousAccountHash);
			npcInteractionTargetCollector.resetForAccountSwitch(previousAccountHash);
			eventLedger.startNewSession();
		}

		// Unlike the real-switch-only resets above, Slayer's hydration
		// must be reset on EVERY LOGGED_IN transition -- a fresh plugin
		// start, a real account switch, AND a same-account world
		// hop/relog -- not just a real switch. A world hop fires this
		// same GameStateChanged(LOGGED_IN) path (see this method's own
		// class javadoc) but is NOT a "real switch"
		// (accountContext.isRealSwitch() is keyed on accountHash, which
		// does not change on a hop). If slayerCollector's hydration were
		// left untouched across a hop, the OLD trusted baseline would
		// carry straight through a period where RuneLite's Slayer state
		// (SlayerPluginService/varps) can transiently blank out mid-hop,
		// producing a fabricated SLAYER_TASK_COMPLETED/SLAYER_TASK_ASSIGNED
		// pair for what was actually continuity. Calling
		// slayerCollector.resetForAccountSwitch() here unconditionally
		// re-opens a fresh SlayerBaselineHydration window on every
		// login-type transition, so the very next
		// captureInitialStateWithoutBaseline() call below (also
		// unconditional, already run on every login/hop) re-hydrates
		// silently from the same stabilization-tolerant/no-fabricated-event
		// machinery already proven correct for a fresh client launch --
		// no separate suppression system, no arbitrary delay. This is
		// scoped to Slayer only; the other collectors' account-switch-only
		// resets above are unaffected.
		slayerCollector.resetForAccountSwitch();

		// Restart both periodic settle windows on EVERY LOGGED_IN
		// transition — a fresh plugin start, a real account switch, AND
		// a same-account world hop/relog (which also fires
		// GameStateChanged(LOGGED_IN) with no separate signal — see
		// this method's own class javadoc). Without this reset, a hop
		// landing partway through an already-progressing tick cycle
		// could let the next periodic quest/XP check
		// (QuestCollector.checkAndFlush()/SkillsCollector.closeXpWindowsIfDue())
		// run before the new session's client-side data has resynced,
		// producing a false transition burst. This is not a new/arbitrary
		// delay — it guarantees a FULL fresh run of the plugin's
		// existing periodic cadence (QUEST_CHECK_INTERVAL_TICKS /
		// XP_FLUSH_INTERVAL_TICKS) after every login, same as a fresh
		// plugin start already got.
		questCheckTickCounter = 0;
		xpFlushTickCounter = 0;

		// Identity capture is deferred to
		// trySeedIdentity(), retried on GameTick until
		// client.getLocalPlayer() is confirmed non-null — see the
		// identityCapturePending field javadoc. Re-armed on every login
		// (including hops) so identity is always re-captured fresh.
		identityCapturePending = true;
		trySeedIdentity();

		// Seed continuously-observable state immediately rather than
		// waiting for the next incidental event — see each collector's
		// captureInitialState()/captureInitialStateWithoutBaseline()/
		// captureInitialContinuousState().
		//
		// questCollector uses captureInitialStateWithoutBaseline()
		// instead of checkAndFlush() here — see that method's javadoc
		// for the full root-cause explanation (the immediate post-login
		// read is not reliably synced, and checkAndFlush() would have
		// let that unsettled read become the diff baseline).
		// skillsCollector.captureInitialState() was changed the same
		// way internally (see its javadoc) — its call site here is
		// unchanged.
		//
		// slayerCollector likewise uses
		// captureInitialStateWithoutBaseline() instead of
		// seedSilently() here, to avoid a false SLAYER_TASK_ASSIGNED on
		// plain login into an account with an already-assigned task.
		// See that method's javadoc on SlayerCollector for the full
		// root-cause trace. seedSilently() itself is unchanged and
		// still used by onConfigChanged()'s collectSlayer re-enable
		// path, where the immediate-seed behavior remains correct (see
		// its own javadoc).
		skillsCollector.captureInitialState();
		containerCollector.captureInitialContinuousState();
		// ADDED (Spyglass History implementation pass, Checkpoint 2). See
		// LoadoutArchiveCollector.captureInitialState()'s own javadoc --
		// same "seed immediately" reasoning as containerCollector above.
		loadoutArchiveCollector.captureInitialState();
		questCollector.captureInitialStateWithoutBaseline();
		slayerCollector.captureInitialStateWithoutBaseline();
	}

	/**
	 * Readiness-gated identity capture — see identityCapturePending's
	 * javadoc. Called once immediately from handleLogin() (covers the
	 * common case where the local player already exists by the time
	 * GameStateChanged(LOGGED_IN) is delivered) and then retried on
	 * every GameTick until it succeeds. No arbitrary duration: this
	 * resolves in exactly however many ticks RuneLite actually takes to
	 * spawn the local Player entity, which is the authoritative
	 * readiness signal for the data IdentityCollector reads.
	 */
	private void trySeedIdentity()
	{
		if (!identityCapturePending)
		{
			return;
		}
		if (client.getLocalPlayer() == null)
		{
			return;
		}
		identityCollector.captureOnLogin();
		identityCapturePending = false;
	}

	/**
	 * FIX for Step 6C review item 6. Every category's collector was
	 * already gated to stop collecting/emitting the instant its toggle
	 * goes false (see each collector's config.xxx() check) — that part
	 * needed no new code. What was missing: RE-enabling a category
	 * must silently reseed its baseline from current authoritative
	 * state, never diff against whatever was last seen before it was
	 * disabled — otherwise everything that changed while disabled
	 * (a quest finished, a Slayer task completed, XP gained) would
	 * fire as a single fabricated "transition" the moment collection
	 * resumes. Reuses the exact same reset/reseed methods built for
	 * account switching, since the correctness requirement is
	 * identical: don't diff across a gap, reseed clean instead.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"osrstelemetry".equals(event.getGroup()))
		{
			return;
		}

		boolean nowEnabled = Boolean.parseBoolean(event.getNewValue());
		if (!nowEnabled)
		{
			// Disabling needs no extra work: every collector already
			// checks its own config.xxx() live, so it simply stops
			// producing state/events from this point on. Baselines
			// are left as-is (stale) rather than cleared, because the
			// re-enable path below always overwrites them with a
			// fresh reseed rather than reading them.
			return;
		}

		// ConfigChanged is delivered on the Swing/AWT thread, not the
		// client thread. Every active case below eventually touches
		// Client (skills: client.getSkillExperience()/etc via
		// captureInitialState(); inventory/equipment:
		// client.getItemContainer() via captureInitialContinuousState();
		// quests/slayer: their own Client reads), so the whole switch is
		// marshalled onto ClientThread here rather than just the
		// branches that touch Client most directly. Each collector
		// method invoked below already re-checks its own config.xxx()
		// gate internally (see each one's javadoc), so if a category is
		// toggled off again before this deferred callback actually
		// runs, it silently no-ops instead of applying a stale queued
		// "enable" action.
		clientThread.invokeLater(() ->
		{
			switch (event.getKey())
			{
				case "collectSkills":
					// captureInitialState() alone is not sufficient here --
					// it deliberately does not touch the XP/level transition
					// baselines (see its own javadoc), so
					// reseedTransitionBaselinesSilently() must also run for
					// re-enabling Skills to reseed those correctly. See
					// SkillsCollector.reseedTransitionBaselinesSilently()'s
					// javadoc for the full reasoning.
					skillsCollector.captureInitialState();
					skillsCollector.reseedTransitionBaselinesSilently();
					break;
				case "collectInventoryEquipment":
					containerCollector.captureInitialContinuousState();
					break;
				case "collectQuests":
					// Deliberately calls captureInitialStateWithoutBaseline(),
					// not checkAndFlush(): checkAndFlush() immediately sets
					// lastKnown = current as a side effect, establishing a
					// live diff baseline straight from one unverified read --
					// exactly the class of risk
					// captureInitialStateWithoutBaseline() exists to avoid
					// (see its "Mirrors SlayerCollector.seedSilently()"
					// javadoc note): it persists state but deliberately
					// leaves lastKnown null, deferring real baseline
					// establishment to the plugin's existing periodic
					// checkAndFlush() cadence. This gives quest re-enable the
					// same silent-hydration contract already trusted at
					// login.
					questCollector.resetForAccountSwitch();
					questCollector.captureInitialStateWithoutBaseline();
					break;
				case "collectSlayer":
					slayerCollector.resetForAccountSwitch();
					slayerCollector.seedSilently();
					break;
				default:
					// collectBank / collectSeedVault / collectGroupStorage /
					// collectCollectionLog / collectCombatAchievements /
					// collectBossKc / collectLoot / collectNpcDeaths: none
					// of these maintain a cross-event baseline that could
					// produce a retroactive transition on re-enable (see
					// class javadoc reasoning in the corresponding
					// collectors) — nothing to reseed.
					// (BossActivityContextCollector has no config toggle at
					// all — see its class javadoc — so this switch never
					// dispatches for it; its own per-account dedup map is a
					// cheap, self-correcting heartbeat baseline that is
					// unaffected by the plugin's collectX toggles entirely.)
					break;
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Cheap, always runs (no-op once identityCapturePending is
		// false): retry deferred identity capture — see trySeedIdentity().
		trySeedIdentity();

		// Cheap, always runs: flush any dirty in-memory state to disk.
		skillsCollector.flushStateIfDirty();

		xpFlushTickCounter++;
		if (xpFlushTickCounter >= XP_FLUSH_INTERVAL_TICKS)
		{
			xpFlushTickCounter = 0;
			skillsCollector.closeXpWindowsIfDue();
		}

		questCheckTickCounter++;
		if (questCheckTickCounter >= QUEST_CHECK_INTERVAL_TICKS)
		{
			questCheckTickCounter = 0;
			questCollector.checkAndFlush();
		}
	}

	/**
	 * ADDED (client-thread lifecycle audit). Owns the one bit of
	 * mutable state needed to stop startUp()'s deferred ClientThread
	 * hydration from running twice for the same plugin enable -- see
	 * startUp()/onGameStateChanged() above. Package-private (not
	 * private) and Client-independent by construction, mirroring
	 * SlayerCollector.SlayerBaselineHydration's "extract the pure
	 * decision, test that directly" pattern, so
	 * OsrsTelemetryPluginLifecycleTest (same package) can drive it
	 * without any RuneLite Client/ClientThread/EventBus dependency.
	 */
	static final class StartupHydrationGate
	{
		private volatile boolean pending = false;

		/** Called from startUp() before scheduling the deferred
		 * ClientThread hydration. */
		void arm()
		{
			pending = true;
		}

		/**
		 * Called from inside the deferred ClientThread callback.
		 * Returns true exactly once per arm() -- true the first time
		 * it's called while a hydration is still pending (and
		 * immediately disarms), false on every other call (nothing
		 * armed, or a real login already superseded it via
		 * supersede()).
		 */
		boolean consumeIfPending()
		{
			if (!pending)
			{
				return false;
			}
			pending = false;
			return true;
		}

		/**
		 * Called from a genuine GameStateChanged(LOGGED_IN) event.
		 * Unconditionally disarms any still-pending deferred startup
		 * hydration, so it becomes a no-op if/when it eventually runs
		 * -- the real login event's own handleLogin() call already
		 * covers the exact same work.
		 */
		void supersede()
		{
			pending = false;
		}
	}
}
