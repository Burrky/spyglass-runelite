package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.CurrentSessionSectionOrder;
import com.osrstelemetry.plugin.GotrStrategy;
import com.osrstelemetry.plugin.LootValuationMode;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.SlayerXpScope;
import com.osrstelemetry.plugin.model.SlayerLiveStatus;
import com.osrstelemetry.plugin.ui.model.SlayerDashboard;
import com.osrstelemetry.plugin.activity.BarrowsBrother;
import com.osrstelemetry.plugin.activity.BarrowsRunProgress;
import com.osrstelemetry.plugin.activity.StructuredActivityCatalog;
import com.osrstelemetry.plugin.activity.GotrAdvice;
import com.osrstelemetry.plugin.activity.GotrAltar;
import com.osrstelemetry.plugin.activity.GotrCandidate;
import com.osrstelemetry.plugin.activity.GotrGameState;
import com.osrstelemetry.plugin.activity.GotrRewardWallet;
import com.osrstelemetry.plugin.activity.TemporossGameState;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.session.SessionState;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSeparator;
import javax.swing.border.Border;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * A fully-functional read-only rendering of one
 * {@link CurrentSessionSnapshot} -- {@link #render} takes only the
 * already-built, immutable view-model (plus {@link ItemManager} and
 * {@link OsrsTelemetryConfig} for authentic item sprites/prices), per
 * the required architecture (runtime -&gt; snapshot -&gt; this panel).
 * This class still has NO reference to {@code Session},
 * {@code SessionRuntimeCoordinator}, or any other session/runtime
 * type, and has no controls that change session identity: it is not,
 * and must never become, a second place that decides activity identity.
 *
 * NPC IMAGERY: deliberately NOT implemented. No RuneLite API surface
 * exists, across {@code client-1.12.38.jar}/{@code runelite-api-1.12.38.jar},
 * to rasterize an arbitrary NPC into a standalone 2D image:
 * <ul>
 *   <li>{@code net.runelite.client.game.SpriteManager} -- serves only
 *       pre-baked INTERFACE sprite-archive lookups
 *       ({@code getSprite(int spriteId, int spriteIndex)}), the same
 *       primitive RuneLite's own UI chrome (buttons, panel backgrounds)
 *       uses -- no NPC-specific capability whatsoever, not even a
 *       lookup by NPC id;</li>
 *   <li>{@code net.runelite.client.game.NpcUtil} -- exposes only
 *       {@code isDying(NPC)} plus its own internal death-animation
 *       event handling; no image/rendering surface of any kind;</li>
 *   <li>{@code net.runelite.client.game.NPCManager} -- exposes only
 *       {@code getNpcInfo(int)}/{@code getHealth(int)}, both
 *       network-sourced numeric combat stats; again no image/rendering
 *       surface;</li>
 *   <li>{@code Client.loadModelData/loadModel/mergeModels/applyTransformations}
 *       produce raw model geometry, not a 2D rasterizer;
 *       {@code Client.createItemSprite} is item-specific with no NPC
 *       equivalent; {@code ModelOutlineRenderer} only outlines an
 *       already-on-screen model, producing no standalone thumbnail;
 *       {@code ImageCapture}/{@code ScreenshotPlugin} produce
 *       whole-canvas screenshots, not an isolated per-NPC portrait; and
 *       the client's own chathead-dialogue rendering pipeline is
 *       invoked only by a live NPC dialogue, with no on-demand public
 *       API.</li>
 * </ul>
 * Per the "if not safely feasible, do not substitute generic artwork"
 * rule, NPC identity is communicated as TEXT ONLY (see renderHeader()'s
 * activity line -- canonical activity + actual current NPC name, kept
 * visually distinct), never a fabricated or generic graphic.
 *
 * REBUILD-ON-EVERY-RENDER, NOT INCREMENTAL LABEL UPDATES.
 *
 * THREADING: {@link #render} must only ever be called on the EDT.
 */
final class CurrentSessionView extends JPanel
{
	// Suspended state uses {@link SpyglassTheme#STATUS_SUSPENDED} (still
	// deliberately not a red/error color -- "paused," not "broken"),
	// "good news" states (active session, a completed Slayer task, a
	// maxed skill) use {@link SpyglassTheme#STATUS_SUCCESS},
	// de-emphasized secondary text (like the "(frozen)" qualifier -- see
	// renderHeader()) uses {@link SpyglassTheme#TEXT_MUTED}, and the loot
	// section's own card chrome uses
	// {@link SpyglassTheme#SURFACE_CARD}/{@code BORDER_SOFT}/
	// {@code TEXT_PRIMARY}/{@code ACCENT_GOLD} -- do not scatter literal
	// RGB/hex values through the UI. SKILL_TINTS below deliberately
	// stays its own local palette -- skill colors should remain local.

	// This table starts from each skill's own real OSRS association
	// (combat-style capes, the classic Prayer white/gold, Slayer's
	// neutral bone/cream, gathering-skill greens/blues/browns, etc.) and
	// then desaturates/darkens every value down to a tasteful, muted
	// range -- see blend()'s own fixed low blend weight. Never a loud
	// fully-saturated background, and a plugin-original styling choice
	// (not a game asset) with no "authentic OSRS asset" obligation the
	// way an icon/sprite would. A skill with no entry here (should not
	// occur for a real Skill enum value) simply gets no tint -- see
	// skillTint(String).
	private static final Map<String, Color> SKILL_TINTS = buildSkillTints();

	private static Map<String, Color> buildSkillTints()
	{
		Map<String, Color> tints = new HashMap<>();
		// Combat styles -- Attack red, Strength green, Defence blue,
		// Ranged its own distinct green-teal (so it never reads as
		// "the same as Strength"), Magic purple/blue, Hitpoints
		// orange-red (every combat style feeds it, so it gets its own
		// warm "shared" tone rather than copying one style's color).
		tints.put("ATTACK", new Color(150, 60, 55));
		tints.put("STRENGTH", new Color(70, 130, 70));
		tints.put("DEFENCE", new Color(60, 95, 150));
		tints.put("RANGED", new Color(60, 130, 95));
		tints.put("MAGIC", new Color(110, 80, 165));
		tints.put("HITPOINTS", new Color(165, 85, 45));
		// Prayer cape's own real white/gold -- rendered here as a muted
		// warm cream rather than a bright white (which would fight the
		// card's white text).
		tints.put("PRAYER", new Color(150, 138, 100));
		// Slayer: deliberately neutral -- a muted cream/bone bordering on
		// grey, evoking Slayer's skull motif rather than any one boss's
		// color, per the explicit "muted cream / skull / neutral" guidance.
		tints.put("SLAYER", new Color(140, 130, 112));
		// Gathering/production skills, themed on each skill's own real
		// cape/identity color, all pulled into the same muted register.
		tints.put("MINING", new Color(100, 115, 135));
		tints.put("SMITHING", new Color(135, 110, 75));
		tints.put("FISHING", new Color(60, 120, 155));
		tints.put("COOKING", new Color(150, 90, 100));
		tints.put("FIREMAKING", new Color(165, 95, 50));
		tints.put("WOODCUTTING", new Color(95, 125, 70));
		tints.put("RUNECRAFT", new Color(80, 130, 150));
		tints.put("AGILITY", new Color(110, 120, 75));
		tints.put("HERBLORE", new Color(75, 135, 85));
		tints.put("THIEVING", new Color(105, 85, 115));
		tints.put("CRAFTING", new Color(140, 105, 65));
		tints.put("FLETCHING", new Color(115, 125, 75));
		tints.put("FARMING", new Color(85, 130, 70));
		tints.put("HUNTER", new Color(110, 115, 75));
		tints.put("CONSTRUCTION", new Color(130, 85, 70));
		return Collections.unmodifiableMap(tints);
	}

	private static Color skillTint(String skillName)
	{
		return skillName == null ? null : SKILL_TINTS.get(skillName);
	}

	private final JPanel content = new JPanel();

	/** View-local Slayer task-ledger expand/collapse state -- see SlayerLedgerCollapseState. */
	private final SlayerLedgerCollapseState ledgerCollapseState = new SlayerLedgerCollapseState();

	/** Last rendered snapshot, so a ledger click re-renders immediately rather than waiting for the next refresh. */
	private CurrentSessionSnapshot lastSnapshot;

	// RuneLite's own cached/synchronous skill-icon lookup -- the same
	// authentic OSRS skill-icon plumbing net.runelite.client.plugins.
	// xptracker's own XpTrackerPlugin uses, per the project's hard
	// "OSRS asset or do not use it" UI rule.
	private final SkillIconManager skillIconManager;

	// Authentic OSRS item sprites
	// (getImage) and prices (getItemPrice/getItemComposition().getHaPrice())
	// for the Loot section -- see renderLootSection()/unitValue().
	private final ItemManager itemManager;

	// Resolves GE/High Alch prices
	// off the EDT via RuneLite's client thread -- see its own javadoc
	// for why ItemManager.getItemPrice/getItemComposition cannot be
	// called directly from render()/unitValue() the way getImage()
	// safely can.
	private final ItemValuationCache itemValuationCache;

	// Read live on every render
	// (config.lootValuationMode()) -- never cached, so a settings change
	// applies on the next render.
	private final OsrsTelemetryConfig config;

	CurrentSessionView(SkillIconManager skillIconManager, ItemManager itemManager, ClientThread clientThread, OsrsTelemetryConfig config)
	{
		this.skillIconManager = skillIconManager;
		this.itemManager = itemManager;
		this.itemValuationCache = new ItemValuationCache(itemManager, clientThread);
		this.config = config;
		setLayout(new BorderLayout());
		setBackground(SpyglassTheme.BACKGROUND_MAIN);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		// A small margin -- every card below is still built with its own
		// border, so this never reads as "content touching the panel edge."
		content.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		add(content, BorderLayout.NORTH);

		render(CurrentSessionSnapshot.empty());
	}

	/**
	 * LAYOUT: 1. activity header/hero, 2. optional Slayer status, 3./4.
	 * one card per actively-gaining skill, and loot -- in the order chosen
	 * by config.currentSessionSectionOrder() (default: XP then loot). Each section collapses
	 * away entirely when it has nothing to show -- there is no "no loot
	 * recorded yet" / "no Slayer task" placeholder text.
	 */
	/**
	 * Live Barrows run (the game's brother varbits, mirrored by the
	 * structured-activity collector). Presentation only; null = none.
	 */
	private volatile java.util.function.Supplier<BarrowsRunProgress> barrowsProgressSupplier;

	void setBarrowsProgressSupplier(java.util.function.Supplier<BarrowsRunProgress> supplier)
	{
		this.barrowsProgressSupplier = supplier;
	}

	/**
	 * Live Tempoross game (the game's own HUD widgets + points varbit,
	 * mirrored by the structured-activity collector). Presentation only;
	 * null = no game HUD up / nothing readable.
	 */
	private volatile java.util.function.Supplier<TemporossGameState> temporossGameSupplier;

	void setTemporossGameSupplier(java.util.function.Supplier<TemporossGameState> supplier)
	{
		this.temporossGameSupplier = supplier;
	}

	/**
	 * Live Guardians of the Rift game (HUD script power + earned-this-game
	 * varbits, mirrored by the structured-activity collector). Presentation
	 * only; null = no game HUD up.
	 */
	private volatile java.util.function.Supplier<GotrGameState> gotrGameSupplier;

	void setGotrGameSupplier(java.util.function.Supplier<GotrGameState> supplier)
	{
		this.gotrGameSupplier = supplier;
	}

	/** Live GotR reward wallet (null = not stated by the game this login). Presentation only. */
	private volatile java.util.function.Supplier<GotrRewardWallet> gotrWalletSupplier;

	void setGotrWalletSupplier(java.util.function.Supplier<GotrRewardWallet> supplier)
	{
		this.gotrWalletSupplier = supplier;
	}

	/**
	 * Remembers the in-panel Points · XP choice (null in tests: stays
	 * view-local). Stored under its own config key through RuneLite's
	 * ConfigManager -- deliberately NOT an @ConfigItem, so the Settings page is
	 * unchanged: it is an in-panel interpretation toggle, not a setting.
	 */
	private volatile java.util.function.Consumer<GotrStrategy> gotrStrategyWriter;
	private volatile java.util.function.Supplier<GotrStrategy> gotrStrategyReader;

	void setGotrStrategyReader(java.util.function.Supplier<GotrStrategy> reader)
	{
		this.gotrStrategyReader = reader;
	}
	/** The in-panel choice until the config reflects it (keeps the click instant). */
	private GotrStrategy gotrStrategyOverride;

	void setGotrStrategyWriter(java.util.function.Consumer<GotrStrategy> writer)
	{
		this.gotrStrategyWriter = writer;
	}

	/** Interpretation only: never changes game or session data. Default Points. */
	GotrStrategy effectiveGotrStrategy()
	{
		java.util.function.Supplier<GotrStrategy> reader = gotrStrategyReader;
		GotrStrategy configured = reader == null ? null : reader.get();
		if (gotrStrategyOverride != null && reader != null && gotrStrategyOverride == configured)
		{
			gotrStrategyOverride = null;
		}
		if (gotrStrategyOverride != null)
		{
			return gotrStrategyOverride;
		}
		return configured == null ? GotrStrategy.POINTS : configured;
	}

	/** Package-private so tests drive the selector exactly as a click does. */
	void selectGotrStrategy(GotrStrategy strategy)
	{
		gotrStrategyOverride = strategy;
		java.util.function.Consumer<GotrStrategy> writer = gotrStrategyWriter;
		if (writer != null)
		{
			writer.accept(strategy);
		}
		if (lastSnapshot != null)
		{
			render(lastSnapshot);
		}
	}

	/**
	 * Live native Slayer status (master, points, streak, location, modifier),
	 * from SlayerCollector's committed state. Presentation only; null = none.
	 */
	private volatile java.util.function.Supplier<SlayerLiveStatus> slayerStatusSupplier;

	void setSlayerStatusSupplier(java.util.function.Supplier<SlayerLiveStatus> supplier)
	{
		this.slayerStatusSupplier = supplier;
	}

	/**
	 * Persists an in-session Current Task / All Tasks choice to the same
	 * config item Settings shows (null in tests: the choice stays view-local).
	 */
	private volatile java.util.function.Consumer<SlayerXpScope> xpScopeWriter;

	/** The in-session choice until the config reflects it (keeps the click instant). */
	private SlayerXpScope xpScopeOverride;

	void setXpScopeWriter(java.util.function.Consumer<SlayerXpScope> writer)
	{
		this.xpScopeWriter = writer;
	}

	/** Display scope only: never resets, moves or re-attributes XP. */
	SlayerXpScope effectiveXpScope()
	{
		SlayerXpScope configured = config.slayerXpScope();
		if (xpScopeOverride != null && xpScopeOverride == configured)
		{
			xpScopeOverride = null;
		}
		if (xpScopeOverride != null)
		{
			return xpScopeOverride;
		}
		return configured == null ? SlayerXpScope.ALL_TASKS : configured;
	}

	/** Package-private so tests drive the selector exactly as a click does. */
	void selectXpScope(SlayerXpScope scope)
	{
		xpScopeOverride = scope;
		java.util.function.Consumer<SlayerXpScope> writer = xpScopeWriter;
		if (writer != null)
		{
			writer.accept(scope);
		}
		if (lastSnapshot != null)
		{
			render(lastSnapshot);
		}
	}

	/** Resolved prices request a coalesced refresh instead of waiting for the next timer tick. */
	void setValuationListener(Runnable listener)
	{
		itemValuationCache.setOnResolved(listener);
	}

	void render(CurrentSessionSnapshot snapshot)
	{
		lastSnapshot = snapshot;
		content.removeAll();

		if (!snapshot.isPresent())
		{
			renderEmptyState();
		}
		else
		{
			renderHeader(snapshot);
			if (isBarrows(snapshot))
			{
				renderBarrowsBlock(snapshot);
			}
			else if (isTempoross(snapshot))
			{
				renderTemporossBlock(snapshot);
			}
			else if (isGotr(snapshot))
			{
				renderGotrBlock(snapshot);
			}
			renderSlayerSection(snapshot);
			// XP / Loot order is a live, player-facing config choice. The
			// SAME two renderers run either way -- only their insertion
			// order changes (see orderedSections()).
			renderSections(orderedSections(config.currentSessionSectionOrder()),
				section -> section == Section.XP
					? !displayedXpEntries(snapshot).isEmpty() || showsXpScopeSelector(snapshot)
					: hasLootSection(snapshot) || showsGotrRewards(snapshot),
				section ->
				{
					if (section == Section.XP)
					{
						renderSkillCards(snapshot);
					}
					else
					{
						// GotR REWARDS sits immediately above LOOT (its reward pulls).
						boolean rewards = showsGotrRewards(snapshot);
						if (rewards)
						{
							renderGotrRewards(snapshot);
						}
						if (hasLootSection(snapshot))
						{
							if (rewards)
							{
								content.add(Box.createVerticalStrut(SECTION_GAP));
							}
							renderLootSection(snapshot);
						}
					}
				},
				() -> content.add(Box.createVerticalStrut(SECTION_GAP)));
		}

		content.revalidate();
		content.repaint();
	}

	/** The two reorderable Current Session sections. */
	enum Section
	{
		XP,
		LOOT
	}

	/**
	 * Pure, testable mapping from the config choice to section insertion
	 * order. Null (defensive; RuneLite always supplies the default) keeps
	 * the original production order, XP then Loot.
	 */
	static List<Section> orderedSections(CurrentSessionSectionOrder order)
	{
		return order == CurrentSessionSectionOrder.LOOT_FIRST
			? Arrays.asList(Section.LOOT, Section.XP)
			: Arrays.asList(Section.XP, Section.LOOT);
	}

	/** Vertical gap between the two major sections (XP and Loot), in either order. */
	static final int SECTION_GAP = 6;

	/**
	 * Renders the reorderable sections in `order`, skipping any with
	 * nothing to show, and inserting exactly ONE {@code addGap} between
	 * two sections that both render -- never before the first, never
	 * after the last, and the same rule for XP_FIRST and LOOT_FIRST.
	 * Pure w.r.t. Swing (all effects go through the callbacks), so the
	 * boundary rule is directly unit-testable.
	 */
	static void renderSections(List<Section> order, java.util.function.Predicate<Section> hasContent,
		java.util.function.Consumer<Section> render, Runnable addGap)
	{
		boolean anyRendered = false;
		for (Section section : order)
		{
			if (!hasContent.test(section))
			{
				continue;
			}
			if (anyRendered)
			{
				addGap.run();
			}
			render.accept(section);
			anyRendered = true;
		}
	}

	/**
	 * FIRST USE: true when the player already has tracked data (History or
	 * Loot) -- or when that is not known yet (logged out, first scan still
	 * running). Only a definite "nothing tracked yet" shows the welcome; null
	 * (tests, or before wiring) keeps the ordinary empty state.
	 */
	private volatile java.util.function.BooleanSupplier trackedDataSupplier;

	void setTrackedDataSupplier(java.util.function.BooleanSupplier supplier)
	{
		this.trackedDataSupplier = supplier;
	}

	static final String EMPTY_TITLE_NAME = "sessionEmptyTitle";
	static final String WELCOME_TITLE = "Welcome to Spyglass";
	static final String WELCOME_TEXT = "Start playing normally. Spyglass detects and tracks your activity automatically.";
	static final String NO_SESSION_TITLE = "No active session";
	static final String NO_SESSION_TEXT = "Start playing normally and activity will appear here.";

	private void renderEmptyState()
	{
		java.util.function.BooleanSupplier tracked = trackedDataSupplier;
		boolean welcome = tracked != null && !tracked.getAsBoolean();
		JLabel title = new JLabel(welcome ? WELCOME_TITLE : NO_SESSION_TITLE);
		title.setName(EMPTY_TITLE_NAME);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(SpyglassTheme.TEXT_PRIMARY);
		title.setAlignmentX(Component.CENTER_ALIGNMENT);

		// Wraps to the ACTUAL available width -- see WrappingHtmlLabel's
		// javadoc for why the previous fixed CSS width:180px (really ~233px
		// in Swing's HTML engine) clipped against the sidebar edge.
		JLabel subtitle = new WrappingHtmlLabel(welcome ? WELCOME_TEXT : NO_SESSION_TEXT, true);
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		subtitle.setForeground(SpyglassTheme.TEXT_SECONDARY);
		subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

		content.add(Box.createVerticalStrut(24));
		content.add(title);
		content.add(Box.createVerticalStrut(6));
		content.add(subtitle);
	}

	/**
	 * Top line: the PRIMARY SKILL signifier (icon + name, from
	 * {@link CurrentSessionSnapshot#getPrimarySkill()}) on the left, a
	 * ACTIVE/SUSPENDED on the right. Second line: the SPECIFIC ACTIVITY (canonical
	 * display name), with the actual current NPC name appended when
	 * known and different -- e.g. "Basilisks &#8212; Basilisk Knight" --
	 * text only, no NPC portrait (see class javadoc). Third line:
	 * compact secondary info -- elapsed duration, plus a reliable
	 * kill/completion count when one has been observed (Slayer's own
	 * remaining-count figure lives in renderSlayerSection() instead, so
	 * it is never shown twice).
	 *
	 * RELEASE CANDIDATE: the former manual "Re-evaluate Session" button was
	 * removed. It could only re-classify the current target as generic
	 * combat -- never repair a Slayer or boss identity -- so it misled more
	 * than it helped. The backend method stays (tests and internal paths).
	 */
	private void renderHeader(CurrentSessionSnapshot snapshot)
	{
		boolean suspended = snapshot.getState() == SessionState.SUSPENDED;
		Color accent = suspended ? SpyglassTheme.STATUS_SUSPENDED : SpyglassTheme.STATUS_SUCCESS;

		JPanel topRow = new JPanel(new BorderLayout(6, 0));
		topRow.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		topRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		// PRIMARY TITLE: see CurrentSessionSnapshot#getHeadline(). A
		// structured activity or boss owns the title (no skill icon); the
		// skills it produces are shown in the XP section only.
		boolean activityHeadline = snapshot.isActivityHeadline();
		String primarySkill = activityHeadline ? null : snapshot.getPrimarySkill();
		JPanel skillIdentity = new JPanel(new BorderLayout(6, 0));
		skillIdentity.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		if (primarySkill != null)
		{
			skillIdentity.add(new JLabel(skillIcon(primarySkill)), BorderLayout.WEST);
		}

		String headline = activityHeadline
			? snapshot.getHeadline()
			: primarySkill != null
				? toTitleCase(primarySkill)
				: (snapshot.getDisplayName() == null ? "Unknown activity" : snapshot.getDisplayName());
		JLabel headlineLabel = new JLabel(headline);
		headlineLabel.setName(HEADLINE_NAME);
		headlineLabel.setFont(FontManager.getRunescapeBoldFont());
		headlineLabel.setForeground(SpyglassTheme.TEXT_PRIMARY);
		// A long boss/activity name shrinks with an ellipsis (full name on
		// hover) instead of pushing ACTIVE/SUSPENDED off the sidebar.
		headlineLabel.setToolTipText(headline);
		skillIdentity.add(headlineLabel, BorderLayout.CENTER);

		JLabel stateLabel = new JLabel(suspended ? "◌ SUSPENDED" : "● ACTIVE");
		stateLabel.setFont(FontManager.getRunescapeSmallFont());
		stateLabel.setForeground(accent);

		// Shares the EAST region with stateLabel via a tight, zero-gap
		// FlowLayout -- never a second full-width row.
		JPanel statusGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		statusGroup.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		statusGroup.add(stateLabel);

		// CENTER, not WEST: BorderLayout gives WEST its full preferred width,
		// which overflowed narrow sidebars; CENTER takes only what is left.
		topRow.add(skillIdentity, BorderLayout.CENTER);
		topRow.add(statusGroup, BorderLayout.EAST);

		String activityText = snapshot.getDisplayName() == null ? "" : snapshot.getDisplayName();
		String npcName = snapshot.getCurrentNpcName();
		if (activityHeadline)
		{
			// The activity is already the title: this line only adds the
			// current target, when there is one worth naming.
			activityText = "";
		}
		if (npcName != null && !npcName.equalsIgnoreCase(snapshot.getDisplayName() == null ? "" : snapshot.getDisplayName()))
		{
			activityText = activityText.isEmpty() ? npcName : activityText + "  —  " + npcName;
		}
		JLabel activityLabel = new JLabel(activityText.isEmpty() ? "Unknown activity" : activityText);
		activityLabel.setName(ACTIVITY_LINE_NAME);
		activityLabel.setToolTipText(activityText.isEmpty() ? null : activityText);
		activityLabel.setFont(FontManager.getRunescapeSmallFont());
		activityLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
		activityLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		// The duration (and any kill/completion count) always renders in
		// the same "normal" tone used in the non-suspended case; only the
		// "(frozen)" qualifier itself gets a quieter, muted color, so it
		// reads as secondary/quiet information rather than as important
		// as the number itself. This is presentation only -- ACTIVE/
		// SUSPENDED timing/state semantics are entirely unchanged (see
		// snapshot.getActiveDurationMillis() and SessionState, neither
		// touched here).
		String durationText = formatDuration(snapshot.getActiveDurationMillis());
		// Wraps between facts at narrow widths instead of clipping (FactLine).
		FactLine secondaryLabel = new FactLine(FontManager.getRunescapeSmallFont(), SpyglassTheme.TEXT_SECONDARY);
		secondaryLabel.setName(SESSION_FACTS_NAME);
		secondaryLabel.fact(durationText);
		if (suspended)
		{
			secondaryLabel.append(" (frozen)", SpyglassTheme.TEXT_MUTED);
		}
		else
		{
			secondaryLabel.append(" elapsed", SpyglassTheme.TEXT_SECONDARY);
		}
		if (snapshot.getReliableCount() != null)
		{
			String label = snapshot.getReliableCount().getKind() == SessionAggregates.ReliableCountKind.KILLS ? "kills" : "completions";
			secondaryLabel.fact(formatNumber(snapshot.getReliableCount().getSessionOccurrences()) + " " + label);
		}

		content.add(topRow);
		content.add(Box.createVerticalStrut(2));
		if (!activityHeadline || !activityText.isEmpty())
		{
			content.add(activityLabel);
			content.add(Box.createVerticalStrut(2));
		}
		content.add(secondaryLabel);
		if (snapshot.getCompletionLabel() != null && !hasActivityBlock(snapshot))
		{
			// Structured activity: generic completion metric on its own line
			// so the elapsed line never overflows the sidebar.
			FactLine completionLabel = CompletionMetricText.factLine(snapshot.getCompletionLabel(),
				snapshot.getCompletionCount(), snapshot.getActiveDurationMillis());
			completionLabel.setName(COMPLETION_METRIC_NAME);
			content.add(Box.createVerticalStrut(2));
			content.add(completionLabel);
		}
		content.add(Box.createVerticalStrut(6));
		content.add(separator());
		content.add(Box.createVerticalStrut(6));
	}

	static final String SLAYER_BLOCK_NAME = "slayerDashboard";
	static final String SLAYER_REMAINING_NAME = "slayerRemaining";
	static final String SLAYER_PROGRESS_NAME = "slayerProgress";
	static final String SLAYER_TASK_COMPLETE_NAME = "slayerTaskComplete";
	static final String SLAYER_STAT_PREFIX = "slayerStat:";
	static final String KILLS_CELL = "KILLS";
	static final String KILLS_PER_HOUR_CELL = "KILLS/HR";
	static final String LOOT_PER_KILL_CELL = "LOOT/KILL";
	static final String POINTS_CELL = "POINTS";
	static final String STREAK_CELL = "STREAK";
	static final String NEXT_BONUS_CELL = "NEXT BONUS";
	static final String SLAYER_BRACELET_ROW_NAME = "slayerBracelets";
	static final String SLAYER_SLAUGHTER_NAME = "slayerSlaughter";
	static final String SLAYER_EXPEDITIOUS_NAME = "slayerExpeditious";
	static final String SLAYER_POINTS_MASTER_HINT_NAME = "slayerPointsMasterHint";

	/**
	 * SMART SLAYER DASHBOARD, between the header (SLAYER / task / state /
	 * elapsed) and the XP section:
	 * <ul>
	 * <li>CURRENT TASK hero -- remaining (prominent) and progress % over a thin
	 * bronze bar, then the Slayer XP estimate. Never the assignment's size.
	 * While awaiting a new task: the completion state instead.</li>
	 * <li>Master context -- full-width rows: master, Konar location / Mortimer
	 * modifier when the game reports one.</li>
	 * <li>The six-stat grid ({@link SlayerStatGrid}): KILLS / KILLS/HR /
	 * LOOT/KILL over POINTS / STREAK / NEXT BONUS -- 3 x 2, or 2 x 3 when the
	 * sidebar is too narrow.</li>
	 * <li>Bracelet effects beneath the grid, only when they happened, then the
	 * points-master hint only when Turael boosting is recognized.</li>
	 * </ul>
	 * Omitted entirely for non-Slayer sessions.
	 */
	private void renderSlayerSection(CurrentSessionSnapshot snapshot)
	{
		CurrentSessionSnapshot.SlayerCurrentTaskView task = snapshot.getSlayerCurrentTask();
		if (snapshot.getActivityType() != ActivityType.SLAYER || (task == null && snapshot.getSlayerCurrentRemaining() == null))
		{
			return;
		}
		java.util.function.Supplier<SlayerLiveStatus> supplier = slayerStatusSupplier;
		SlayerLiveStatus status = supplier == null ? null : supplier.get();
		Integer remaining = task != null ? task.getRemaining() : snapshot.getSlayerCurrentRemaining();
		boolean complete = remaining != null && remaining == 0;
		// Live task context (location, modifier, master) applies only while the
		// game's live task IS this assignment.
		boolean liveMatches = status != null && status.getTaskName() != null && task != null
			&& status.getTaskName().equalsIgnoreCase(task.getTaskName() == null ? "" : task.getTaskName());

		JPanel block = new JPanel();
		block.setName(SLAYER_BLOCK_NAME);
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		block.setAlignmentX(Component.LEFT_ALIGNMENT);

		block.add(caption("Current task"));
		block.add(Box.createVerticalStrut(3));
		if (complete)
		{
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setOpaque(false);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			JLabel done = new JLabel("Task complete");
			done.setName(SLAYER_TASK_COMPLETE_NAME);
			done.setFont(FontManager.getRunescapeBoldFont());
			done.setForeground(SpyglassTheme.STATUS_SUCCESS);
			row.add(done, BorderLayout.WEST);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
			block.add(row);
			JLabel next = smallLabel("Grab a new task", SpyglassTheme.TEXT_SECONDARY);
			next.setAlignmentX(Component.LEFT_ALIGNMENT);
			block.add(next);
		}
		else if (remaining != null)
		{
			Integer percent = task == null ? null : task.getProgressPercent();
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setOpaque(false);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			JLabel remainingLabel = new JLabel(formatNumber(remaining) + " remaining");
			remainingLabel.setName(SLAYER_REMAINING_NAME);
			remainingLabel.setFont(FontManager.getRunescapeBoldFont());
			remainingLabel.setForeground(SpyglassTheme.TEXT_PRIMARY);
			row.add(remainingLabel, BorderLayout.WEST);
			if (percent != null)
			{
				JLabel pct = smallLabel(percent + "%", SpyglassTheme.TEXT_SECONDARY);
				pct.setName(SLAYER_PROGRESS_NAME);
				pct.setHorizontalAlignment(JLabel.RIGHT);
				row.add(pct, BorderLayout.EAST);
			}
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
			block.add(row);
			if (percent != null)
			{
				block.add(Box.createVerticalStrut(2));
				ThinBar bar = new ThinBar(percent, SpyglassTheme.ACCENT_BRONZE);
				bar.setName(SLAYER_PROGRESS_NAME + ":bar:" + percent);
				bar.setToolTipText(percent + "% of this task done");
				block.add(bar);
			}
			Long estimatedSlayerXpRemaining = snapshot.getEstimatedSlayerXpRemaining();
			if (estimatedSlayerXpRemaining != null)
			{
				block.add(Box.createVerticalStrut(2));
				JLabel xpLeft = smallLabel("~" + QuantityFormatter.quantityToStackSize(estimatedSlayerXpRemaining) + " Slayer XP remaining",
					SpyglassTheme.TEXT_MUTED);
				xpLeft.setAlignmentX(Component.LEFT_ALIGNMENT);
				block.add(xpLeft);
			}
		}
		// Breathing room between the task hero and its context.
		block.add(Box.createVerticalStrut(8));

		// MASTER CONTEXT: full-width rows (values like "Duradel / Kuradal" or a
		// Konar area are too wide for a grid cell).
		Integer masterId = task != null && task.getMasterId() != null ? task.getMasterId()
			: liveMatches ? Integer.valueOf(status.getMasterId()) : null;
		addStat(block, "Master", SlayerDashboard.masterName(masterId), SpyglassTheme.TEXT_PRIMARY);
		if (liveMatches && !complete)
		{
			addStat(block, "Location", status.getTaskLocation(), SpyglassTheme.TEXT_PRIMARY);
			addStat(block, "Modifier", SlayerDashboard.modifierText(status.getModifierId(), status.getModifierValue(),
				status.isModifierNegative()), SpyglassTheme.TEXT_PRIMARY);
		}
		block.add(Box.createVerticalStrut(6));

		// THE SIX PERMANENT STATS: this task's performance over the account's
		// Slayer chain. A value the game/Spyglass does not know yet shows a quiet
		// dash -- the six cells are always the same six.
		SlayerStatGrid grid = new SlayerStatGrid();
		Integer kills = task == null ? null : task.getKills();
		Long perHour = task == null ? null : task.getKillsPerHour();
		Long perKill = null;
		if (task != null)
		{
			long taskLootValue = LootPricing.sumTotalValue(LootPricing.valueAndSort(task.getLootEntries(),
				itemId -> unitValue(itemId, config.lootValuationMode())));
			// Omitted while nothing is valued yet (prices resolve asynchronously).
			perKill = taskLootValue > 0 ? SlayerDashboard.perKill(taskLootValue, kills) : null;
		}
		grid.addCell(KILLS_CELL, kills == null ? null : formatNumber(kills), SpyglassTheme.TEXT_PRIMARY,
			"Kills on this task (bracelet effects accounted for)");
		grid.addCell(KILLS_PER_HOUR_CELL, perHour == null ? null : formatNumber(perHour), SpyglassTheme.TEXT_PRIMARY,
			perHour == null ? "Kills per hour of active time on this task. " + RATE_WARMUP_TOOLTIP : "Kills per hour of active time on this task");
		grid.addCell(LOOT_PER_KILL_CELL, perKill == null ? null : QuantityFormatter.quantityToStackSize(perKill), SpyglassTheme.ACCENT_GOLD,
			perKill == null ? null : formatNumber(perKill) + " gp per kill on this task");
		String streakTip = status == null ? null
			: "WILDERNESS".equals(status.getStreakLabel()) ? "Krystilia's Wilderness task streak"
			: "MORTIMER".equals(status.getStreakLabel()) ? "Mortimer's task streak" : "Slayer task streak";
		grid.addCell(POINTS_CELL, status == null ? null : formatNumber(status.getPoints()), SpyglassTheme.ACCENT_GOLD, "Slayer reward points");
		grid.addCell(STREAK_CELL, status == null ? null : formatNumber(status.getStreak()), SpyglassTheme.TEXT_PRIMARY, streakTip);
		String nextBonus = status == null ? null : SlayerDashboard.milestoneText(status.getStreak(), status.getStreakLabel());
		grid.addCell(NEXT_BONUS_CELL, nextBonus, SpyglassTheme.ACCENT_AMBER,
			status != null && "MORTIMER".equals(status.getStreakLabel())
				? "Mortimer's points come from his task modifier, not streak bonuses"
				: "Bonus points on every 10th task (larger on every 50th, 100th, 250th and 1,000th)");
		block.add(grid);

		// Conditional bracelet effects: outside the grid, only when they happened.
		String slaughter = task == null ? null : positive(task.getSlaughterProcs());
		String expeditious = task == null ? null : positive(task.getExpeditiousProcs());
		if (slaughter != null || expeditious != null)
		{
			JPanel bracelets = new JPanel(new BorderLayout(6, 0));
			bracelets.setName(SLAYER_BRACELET_ROW_NAME);
			bracelets.setOpaque(false);
			bracelets.setAlignmentX(Component.LEFT_ALIGNMENT);
			if (slaughter != null)
			{
				JLabel l = smallLabel("Slaughter " + slaughter, SpyglassTheme.ACCENT_SAGE);
				l.setName(SLAYER_SLAUGHTER_NAME);
				l.setToolTipText("Bracelet of slaughter: kills that did not use up a task unit");
				bracelets.add(l, BorderLayout.WEST);
			}
			if (expeditious != null)
			{
				JLabel l = smallLabel("Expeditious " + expeditious, SpyglassTheme.ACCENT_AMBER);
				l.setName(SLAYER_EXPEDITIOUS_NAME);
				l.setToolTipText("Expeditious bracelet: kills that counted as an extra task unit");
				bracelets.add(l, slaughter != null ? BorderLayout.EAST : BorderLayout.WEST);
			}
			bracelets.setMaximumSize(new Dimension(Integer.MAX_VALUE, bracelets.getPreferredSize().height));
			block.add(Box.createVerticalStrut(4));
			block.add(bracelets);
		}

		if (status != null && SlayerDashboard.showPointsMasterHint(task != null && task.isTuraelBoosting(), status.getStreak(), status.getStreakLabel()))
		{
			JLabel hint = smallLabel("Use a points master", SpyglassTheme.TEXT_SECONDARY);
			hint.setName(SLAYER_POINTS_MASTER_HINT_NAME);
			hint.setAlignmentX(Component.LEFT_ALIGNMENT);
			hint.setToolTipText("Recent tasks this session came from a zero-point master; the next task earns bonus points");
			block.add(Box.createVerticalStrut(3));
			block.add(hint);
		}

		content.add(block);
		content.add(Box.createVerticalStrut(6));
		content.add(separator());
		content.add(Box.createVerticalStrut(6));
	}

	private static String positive(Integer value)
	{
		return value == null || value <= 0 ? null : String.valueOf(value);
	}

	/** A wrap-aware "label ... value" line; nothing at all when the value is unknown. */
	private static java.util.Optional<StatLine> addStat(JPanel block, String label, String value, Color valueColor)
	{
		if (value == null || value.isEmpty())
		{
			return java.util.Optional.empty();
		}
		StatLine line = new StatLine(label, value, SpyglassTheme.TEXT_SECONDARY, valueColor, false);
		line.setName(SLAYER_STAT_PREFIX + label);
		block.add(line);
		block.add(Box.createVerticalStrut(1));
		return java.util.Optional.of(line);
	}

	/** XP cards for the live Slayer XP scope -- see CurrentSessionSnapshot#getXpEntries(SlayerXpScope). */
	private List<CurrentSessionSnapshot.XpEntry> displayedXpEntries(CurrentSessionSnapshot snapshot)
	{
		return snapshot.getXpEntries(effectiveXpScope());
	}

	/**
	 * The Current Task / All Tasks selector lives in the Slayer XP section
	 * itself, whenever the current assignment tracks its own XP -- including
	 * while the current task has none yet, so the player can always switch back.
	 */
	static boolean showsXpScopeSelector(CurrentSessionSnapshot snapshot)
	{
		return snapshot.getActivityType() == ActivityType.SLAYER && snapshot.hasTaskXpScope()
			&& !snapshot.getXpEntries(SlayerXpScope.ALL_TASKS).isEmpty();
	}

	static final String XP_SCOPE_ALL_NAME = "xpScope:ALL_TASKS";
	static final String XP_SCOPE_CURRENT_NAME = "xpScope:CURRENT_TASK";
	static final String XP_SCOPE_EMPTY_NAME = "xpScopeEmpty";

	/** "XP .......... All tasks · Current task": caption left, the two scope options right. */
	private JPanel xpScopeHeader()
	{
		SlayerXpScope scope = effectiveXpScope();
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(caption("XP"), BorderLayout.WEST);
		JPanel options = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		options.setOpaque(false);
		options.add(scopeOption("All tasks", XP_SCOPE_ALL_NAME, SlayerXpScope.ALL_TASKS, scope));
		options.add(smallLabel(" · ", SpyglassTheme.TEXT_MUTED));
		options.add(scopeOption("Current task", XP_SCOPE_CURRENT_NAME, SlayerXpScope.CURRENT_TASK, scope));
		row.add(options, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private JLabel scopeOption(String text, String name, SlayerXpScope option, SlayerXpScope selected)
	{
		boolean active = option == selected;
		JLabel label = smallLabel(text, active ? SpyglassTheme.ACCENT_GOLD : SpyglassTheme.TEXT_MUTED);
		label.setName(name + (active ? ":selected" : ""));
		label.setToolTipText(option == SlayerXpScope.CURRENT_TASK
			? "Show XP from the current assignment only (display only -- nothing is reset)"
			: "Show XP from the whole Slayer session");
		label.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		label.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				selectXpScope(option);
			}
		});
		return label;
	}

	/**
	 * One rectangular card per actively-gaining skill -- never a
	 * combined textual list. Cards stack in a single column
	 * (PluginPanel width is narrow; no forced two-column grid).
	 */
	private void renderSkillCards(CurrentSessionSnapshot snapshot)
	{
		List<CurrentSessionSnapshot.XpEntry> entries = displayedXpEntries(snapshot);
		boolean selector = showsXpScopeSelector(snapshot);
		if (entries.isEmpty() && !selector)
		{
			return;
		}
		if (selector)
		{
			content.add(xpScopeHeader());
			content.add(Box.createVerticalStrut(4));
			if (entries.isEmpty())
			{
				JLabel none = smallLabel("No XP on this task yet", SpyglassTheme.TEXT_MUTED);
				none.setName(XP_SCOPE_EMPTY_NAME);
				none.setAlignmentX(Component.LEFT_ALIGNMENT);
				content.add(none);
				return;
			}
		}

		// Same 6px between skill cards as before; the trailing strut that
		// used to follow the LAST card is gone -- the gap after the XP
		// section is now SECTION_GAP, added by renderSections() only when
		// another section actually follows (so XP_FIRST looks identical
		// to before, and LOOT_FIRST gets the same gap above the XP block).
		String primarySkill = snapshot.getPrimarySkill();
		boolean first = true;
		for (CurrentSessionSnapshot.XpEntry entry : entries)
		{
			if (!first)
			{
				content.add(Box.createVerticalStrut(6));
			}
			boolean isPrimary = primarySkill != null && primarySkill.equals(entry.getSkill());
			content.add(skillCard(entry, isPrimary));
			first = false;
		}
	}

	/**
	 * A fixed, compact row layout close to RuneLite's own XP Tracker
	 * density:
	 * <pre>
	 * [icon] Strength
	 * Lv 96                                          35.8k XP/hr
	 * [======================= 63% to 97 =====================]
	 * +9,591 XP                        367,694 XP to lvl 97
	 * </pre>
	 * Row 1 is icon + skill name only; Row 1b is level (west) / XP-per-
	 * hour (east) on its own compact row; then the progress bar; then
	 * the XP-gained/XP-to-next-level footer row. Applies generically to
	 * every skilling entry rendered here -- this method has no
	 * per-skill special-casing (see {@link #skillTint}) -- and has no
	 * effect on {@link #renderSlayerSection} or any other combat/boss/
	 * Slayer card, which are built by entirely separate methods that
	 * never call this one.
	 *
	 * Every card always has exactly one progress bar row (a MAX skill
	 * gets a full green bar reading "MAX" instead of an omitted row) so
	 * card height stays uniform regardless of state. The session-XP-
	 * gained and XP-remaining-to-next-level figures share one row via a
	 * west/east split, so cards never grow tall.
	 *
	 * The card's chrome (background tint + left accent stripe) carries a
	 * per-skill color identity that dominates the card background, with
	 * a faint bronze cast for cohesion with the surrounding Dark Bronze
	 * chrome -- see {@link #cardPanel(Color, boolean)}'s own javadoc,
	 * and {@link #SKILL_TINTS} for the tint source itself.
	 *
	 * {@code isPrimary} (this skill is the session's primary signifier
	 * -- see {@link CurrentSessionSnapshot#getPrimarySkill()}) gets a
	 * slightly stronger tint/accent rather than a larger card, so card
	 * height never grows for the primary skill -- the header
	 * (renderHeader()) remains the main primary-skill signifier.
	 *
	 * Uses RuneLite's own real-level/XP-table math already computed by
	 * {@link CurrentSessionSnapshot} (via {@code net.runelite.api.Experience})
	 * -- this method only renders it.
	 */
	private JPanel skillCard(CurrentSessionSnapshot.XpEntry entry, boolean isPrimary)
	{
		JPanel card = cardPanel(skillTint(entry.getSkill()), isPrimary);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

		CurrentSessionSnapshot.LevelProgress levelProgress = entry.getLevelProgress();

		// Row 1: icon + skill name only -- level/XP-per-hour have their
		// own lane below (Row 1b), so the three facts never visually run
		// together.
		JPanel titleRow = new JPanel(new BorderLayout(4, 0));
		titleRow.setOpaque(false);
		titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		titleRow.add(new JLabel(skillIcon(entry.getSkill())), BorderLayout.WEST);

		JLabel nameLabel = new JLabel(toTitleCase(entry.getSkill()));
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(SpyglassTheme.TEXT_PRIMARY);
		titleRow.add(nameLabel, BorderLayout.CENTER);
		card.add(titleRow);
		card.add(Box.createVerticalStrut(2));

		// Row 1b: level ................ XP/hr -- its own compact lane,
		// distinct from the title above and the XP-gained/XP-to-level
		// row below, so the three facts never visually run together.
		JPanel statsRow = new JPanel(new BorderLayout(6, 0));
		statsRow.setOpaque(false);
		statsRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		if (levelProgress != null)
		{
			JLabel levelLabel = new JLabel("Lv " + levelProgress.getCurrentLevel());
			levelLabel.setFont(FontManager.getRunescapeSmallFont());
			levelLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
			statsRow.add(levelLabel, BorderLayout.WEST);
		}

		if (entry.getXpPerHour() != null)
		{
			JLabel rateLabel = new JLabel(formatNumber(entry.getXpPerHour()) + " XP/hr");
			rateLabel.setName(XP_RATE_NAME);
			rateLabel.setFont(FontManager.getRunescapeSmallFont());
			rateLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
			statsRow.add(rateLabel, BorderLayout.EAST);
		}
		else if (entry.getRateWarmupRemainingMillis() != null)
		{
			// Not projected yet (shared rate floor): a quiet, honest
			// placeholder instead of a wild extrapolation or a blank lane.
			JLabel rateLabel = new JLabel(rateWarmupText(entry.getRateWarmupRemainingMillis()));
			rateLabel.setName(XP_RATE_NAME);
			rateLabel.setFont(FontManager.getRunescapeSmallFont());
			rateLabel.setForeground(SpyglassTheme.TEXT_MUTED);
			rateLabel.setToolTipText(RATE_WARMUP_TOOLTIP);
			statsRow.add(rateLabel, BorderLayout.EAST);
		}
		card.add(statsRow);
		card.add(Box.createVerticalStrut(4));

		// Row 2: level-progress bar. Always present (a MAX skill shows
		// a full green bar reading "MAX") so every card has the same
		// fixed row structure/height.
		JProgressBar bar = new JProgressBar(0, 100);
		bar.setStringPainted(true);
		bar.setFont(FontManager.getRunescapeSmallFont());
		bar.setBorderPainted(false);
		bar.setBackground(SpyglassTheme.BORDER_SOFT);
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (levelProgress != null && levelProgress.isMaxLevel())
		{
			bar.setValue(100);
			bar.setString("MAX");
			bar.setForeground(SpyglassTheme.STATUS_SUCCESS);
		}
		else if (levelProgress != null)
		{
			int percent = levelProgress.getProgressPercent();
			bar.setValue(percent);
			bar.setString(percent + "% to " + (levelProgress.getCurrentLevel() + 1));
			bar.setForeground(progressColor(percent));
		}
		else
		{
			// Defense in depth only (see LevelProgress' own javadoc --
			// this cannot happen for a skill with nonzero session XP in
			// practice): no absolute-XP data to compute real progress
			// yet. Keep the row present at a fixed height rather than
			// omitting it, so card height never varies by state.
			bar.setValue(0);
			bar.setString("");
			bar.setForeground(SpyglassTheme.BORDER_SOFT);
		}
		card.add(bar);
		card.add(Box.createVerticalStrut(4));

		// Row 3: session XP gained ................ XP remaining to
		// next level (omitted at MAX -- there is no next level).
		JPanel footerRow = new JPanel(new BorderLayout(6, 0));
		footerRow.setOpaque(false);
		footerRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel xpLabel = new JLabel("+" + formatNumber(entry.getXpGained()) + " XP");
		xpLabel.setFont(FontManager.getRunescapeSmallFont());
		xpLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
		footerRow.add(xpLabel, BorderLayout.WEST);

		if (levelProgress != null && !levelProgress.isMaxLevel())
		{
			JLabel remainingLabel = new JLabel(formatNumber(levelProgress.getXpRemainingToNextLevel()) + " XP to lvl " + (levelProgress.getCurrentLevel() + 1));
			remainingLabel.setFont(FontManager.getRunescapeSmallFont());
			remainingLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
			footerRow.add(remainingLabel, BorderLayout.EAST);
		}
		card.add(footerRow);

		return card;
	}

	/**
	 * A smooth interpolated red (0%) -&gt; yellow
	 * ({@link ColorScheme#PROGRESS_INPROGRESS_COLOR}, 50%) -&gt; green
	 * (100%) fill, using RuneLite's own three progress-bar palette
	 * colors ({@link ColorScheme#PROGRESS_ERROR_COLOR}/
	 * PROGRESS_INPROGRESS_COLOR/PROGRESS_COMPLETE_COLOR) as the
	 * interpolation anchors rather than hand-picked colors -- plain
	 * linear RGB interpolation across the two half-ranges, no external
	 * animation.
	 */
	private static Color progressColor(int percent)
	{
		int clamped = Math.max(0, Math.min(100, percent));
		Color from;
		Color to;
		float localT;
		if (clamped <= 50)
		{
			from = ColorScheme.PROGRESS_ERROR_COLOR;
			to = ColorScheme.PROGRESS_INPROGRESS_COLOR;
			localT = clamped / 50f;
		}
		else
		{
			from = ColorScheme.PROGRESS_INPROGRESS_COLOR;
			to = ColorScheme.PROGRESS_COMPLETE_COLOR;
			localT = (clamped - 50) / 50f;
		}
		int r = Math.round(from.getRed() + (to.getRed() - from.getRed()) * localT);
		int g = Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * localT);
		int b = Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * localT);
		return new Color(r, g, b);
	}

	/**
	 * Non-Slayer: loot section only when there is loot (unchanged). Slayer
	 * with a task ledger: always -- the current assignment card exists
	 * from the moment the task does, even at 0 gp.
	 */
	static boolean hasLootSection(CurrentSessionSnapshot snapshot)
	{
		return !snapshot.getLootEntries().isEmpty() || !snapshot.getSlayerAssignments().isEmpty();
	}

	/**
	 * Renders the loot section using {@link SpyglassTheme}'s shared
	 * {@code SURFACE_CARD}/{@code BORDER_SOFT}/{@code TEXT_PRIMARY}/
	 * {@code ACCENT_GOLD} tokens. The header row and grid sit inside one
	 * bordered {@code section} container, the same usable width as the
	 * skill-card section above it, so the loot area reads as its own
	 * clearly-separated panel. The header row and grid are both
	 * {@code setOpaque(false)} so the section's own background shows
	 * through the grid's inter-cell gaps -- the grid itself does not
	 * paint its own background.
	 *
	 * Authentic item sprites ({@link ItemManager#getImage(int)}),
	 * {@link #LOOT_ITEMS_PER_ROW} to a row, item name/value in a hover
	 * tooltip, grouped-and-summed quantities (already done by
	 * {@link CurrentSessionSnapshot}), and sorting by TOTAL stack value
	 * descending under the configured valuation mode ({@link LootPricing}).
	 * Omitted when there is nothing to show (see hasLootSection()).
	 */
	private void renderLootSection(CurrentSessionSnapshot snapshot)
	{
		if (!hasLootSection(snapshot))
		{
			return;
		}

		LootValuationMode mode = config.lootValuationMode();
		List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(snapshot.getLootEntries(), itemId -> unitValue(itemId, mode));
		long totalValue = LootPricing.sumTotalValue(rows);

		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(true);
		section.setBackground(SpyglassTheme.SURFACE_CARD);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
		section.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT),
			BorderFactory.createEmptyBorder(5, 7, 5, 7)));

		JPanel headerRow = new JPanel(new BorderLayout());
		headerRow.setOpaque(false);
		headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		// "LOOT ×36" -- a genuine PHYSICAL-KILL count only, from the
		// existing reliable boss-kill counter (getReliableCount()'s
		// sessionOccurrences). Deliberately never Slayer's own
		// slayerProgressDelta (a TASK-UNIT total, not a physical-kill
		// count -- see that field's own javadoc): an ordinary Slayer
		// session with no independently-authoritative kill count renders
		// a plain "LOOT" header instead, never a fabricated or
		// mislabeled count -- see
		// CurrentSessionSnapshot#getLootHeaderKillCount().
		Integer killCount = snapshot.getLootHeaderKillCount();
		// SMART SLAYER: the assignment cards ARE the session's task history
		// (each card owning its canonical loot), so the section is titled
		// TASK HISTORY -- one history presentation, never a second LOOT
		// section beside it. Every other session keeps its LOOT heading.
		boolean taskHistory = !snapshot.getSlayerAssignments().isEmpty();
		JLabel titleLabel = new JLabel(taskHistory ? TASK_HISTORY_TITLE
			: killCount == null ? "LOOT" : "LOOT  ×" + killCount);
		titleLabel.setName(LOOT_SECTION_TITLE_NAME);
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(SpyglassTheme.TEXT_PRIMARY);
		titleLabel.setBorder(BorderFactory.createEmptyBorder(3, 0, 5, 0));
		headerRow.add(titleLabel, BorderLayout.WEST);

		JLabel totalLabel = new JLabel(QuantityFormatter.quantityToStackSize(totalValue) + " gp");
		totalLabel.setFont(FontManager.getRunescapeBoldFont());
		totalLabel.setForeground(SpyglassTheme.ACCENT_GOLD);
		headerRow.add(totalLabel, BorderLayout.EAST);
		section.add(headerRow);
		section.add(Box.createVerticalStrut(6));

		String modeLabel = mode == LootValuationMode.HIGH_ALCH ? "High Alch" : "Grand Exchange";
		if (snapshot.getSlayerAssignments().isEmpty())
		{
			// Non-Slayer (or ledger-less) session: the original single grid, unchanged.
			section.add(lootGrid(rows, modeLabel));
		}
		else
		{
			renderSlayerTaskLedger(section, snapshot, mode, modeLabel);
		}

		content.add(section);
	}

	private JPanel lootGrid(List<LootPricing.ValuedLootRow> rows, String modeLabel)
	{
		JPanel grid = new JPanel(LootGridCell.newGridLayout());
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
		for (LootPricing.ValuedLootRow row : rows)
		{
			grid.add(lootGridCell(row, modeLabel));
		}
		return grid;
	}

	/**
	 * SMART SLAYER ASSIGNMENT LEDGER. Below the unchanged, session-wide
	 * LOOT header: one collapsible card per assignment INSTANCE, current
	 * first then older newest-first (the snapshot's own order). Each card
	 * header is "[chevron] Task name ... value" (same chevron glyphs, fonts
	 * and colors as the Loot tab's source cards); an expanded card shows
	 * that assignment's items in the same shared 5-column LootGridCell grid
	 * with the same valuation/tooltip behavior as the session grid.
	 * Expand/collapse state is view-local -- see SlayerLedgerCollapseState.
	 */
	private void renderSlayerTaskLedger(JPanel section, CurrentSessionSnapshot snapshot, LootValuationMode mode, String modeLabel)
	{
		List<CurrentSessionSnapshot.SlayerAssignmentView> assignments = snapshot.getSlayerAssignments();
		ledgerCollapseState.sync(snapshot.getSessionId(), assignments.get(0).getOrdinal());

		boolean first = true;
		for (CurrentSessionSnapshot.SlayerAssignmentView assignment : assignments)
		{
			if (!first)
			{
				section.add(Box.createVerticalStrut(4));
			}
			first = false;

			List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(assignment.getLootEntries(), itemId -> unitValue(itemId, mode));
			boolean expanded = ledgerCollapseState.isExpanded(assignment.getOrdinal());
			int ordinal = assignment.getOrdinal();
			JPanel card = SlayerTaskCards.card(SLAYER_TASK_CARD_NAME_PREFIX + ordinal, expanded,
				slayerTaskCardTitle(assignment), slayerTaskCardTooltip(assignment),
				assignment.isCurrent() ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY,
				LootPricing.sumTotalValue(rows), () -> toggleSlayerTaskCard(ordinal),
				expanded && !rows.isEmpty() ? lootGrid(rows, modeLabel) : null);
			section.add(card);
		}
	}

	/** See SlayerTaskCards#title. */
	static String slayerTaskCardTitle(CurrentSessionSnapshot.SlayerAssignmentView assignment)
	{
		return SlayerTaskCards.title(assignment.getTaskName(), assignment.getUnitsCompleted(), assignment.getEndReason());
	}

	/** See SlayerTaskCards#tooltip. */
	static String slayerTaskCardTooltip(CurrentSessionSnapshot.SlayerAssignmentView assignment)
	{
		List<String> extra = new java.util.ArrayList<>();
		if (assignment.getKills() != null)
		{
			extra.add(assignment.getKills() + (assignment.getKills() == 1 ? " kill" : " kills"));
		}
		if (assignment.getActiveMillis() != null && assignment.getActiveMillis() > 0)
		{
			extra.add(formatDuration(assignment.getActiveMillis()) + " on task");
		}
		String master = SlayerDashboard.masterName(assignment.getMasterId());
		if (master != null)
		{
			extra.add(master);
		}
		return SlayerTaskCards.tooltip(assignment.getTaskName(), assignment.getUnitsCompleted(),
			assignment.isBootstrapped(), assignment.getRemaining(), assignment.getEndReason(), extra);
	}

	/** Package-private so tests can drive a header click exactly as the mouse listener does. */
	void toggleSlayerTaskCard(int ordinal)
	{
		ledgerCollapseState.toggle(ordinal);
		if (lastSnapshot != null)
		{
			render(lastSnapshot);
		}
	}

	static final String TASK_HISTORY_TITLE = "TASK HISTORY";
	static final String LOOT_SECTION_TITLE_NAME = "lootSectionTitle";

	/** Component name prefix for a ledger card (tests locate cards by it). */
	static final String SLAYER_TASK_CARD_NAME_PREFIX = "slayerTask:";
	/** Component name of the structured-activity completion metric line (tests). */
	static final String COMPLETION_METRIC_NAME = "completionMetric";

	// Target density: see LootGridCell.GRID_COLUMNS (shared with the Loot tab).
	static final int LOOT_ITEMS_PER_ROW = LootGridCell.GRID_COLUMNS;

	/**
	 * One loot grid cell: authentic item sprite (still
	 * {@link ItemManager#getImage(int)} -- never a placeholder/fake
	 * icon; a resolution failure is defensive-only and simply renders
	 * an empty slot), an OSRS-style colored quantity badge, and a hover
	 * tooltip carrying the item name, quantity, per-unit value under the
	 * active valuation mode, and the row's total stack value -- detail
	 * that only appears on hover, which is what keeps the grid compact.
	 */
	private LootGridCell lootGridCell(LootPricing.ValuedLootRow row, String modeLabel)
	{
		AsyncBufferedImage icon = null;
		if (row.getItemId() != null)
		{
			try
			{
				// `stackable` must stay false here: passing true asks
				// ItemManager to bake the game's own quantity number onto
				// the returned sprite, which LootGridCell.paintComponent()
				// would then double-count by painting its own gold/black-
				// outlined quantity number on top. The `quantity` argument
				// is still passed through because it also selects the
				// correct sprite art variant for some items (e.g. coin
				// piles render different art at different quantity tiers)
				// independent of whether any text is drawn. LootGridCell's
				// own overlay remains the only quantity counter.
				icon = itemManager.getImage(row.getItemId(), LootGridCell.clampToInt(row.getQuantity()), false);
			}
			catch (RuntimeException e)
			{
				// Defensive only -- an unresolvable item id must never
				// take the panel down; the cell simply renders with no
				// icon (never a fake/placeholder graphic).
			}
		}

		// Tooltip HTML is built by the shared LootPricing.buildTooltipHtml()
		// helper so the Loot Tracker/History views render the identical
		// tooltip shape instead of duplicating it here.
		String tooltip = LootPricing.buildTooltipHtml(
			row.getItemName(), row.getQuantity(), modeLabel, row.getUnitValue(), row.getTotalValue(), null);

		return new LootGridCell(icon, row.getQuantity(), tooltip);
	}

	private static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	// Small helper so a single JLabel can carry two differently-colored
	// segments (the duration figure vs. the muted "(frozen)" qualifier)
	// -- see renderHeader().
	private static String colorSpan(String text, Color color)
	{
		return "<span style='color:rgb(" + color.getRed() + "," + color.getGreen() + "," + color.getBlue() + ")'>"
			+ escapeHtml(text) + "</span>";
	}

	// LootGridCell is a top-level ui.LootGridCell class (not nested here)
	// so LootTrackerView can render item cells identically instead of
	// duplicating this widget -- see that file's own javadoc.

	/**
	 * The one place GE vs. High Alch is actually resolved, via RuneLite's
	 * own price/item APIs only -- {@link ItemManager#getItemPrice(int)}
	 * for the live GE price, {@code ItemComposition#getHaPrice()} (via
	 * {@link ItemManager#getItemComposition(int)}) for the static
	 * High Alch value. No external pricing service.
	 *
	 * THREAD SAFETY: those two ItemManager calls assert they run on
	 * RuneLite's client thread and must never be called directly from
	 * here -- this method runs on the Swing EDT (via
	 * render() -> renderLootSection(), driven by OsrsTelemetryPanel's
	 * coalesced refresh or its clock timer). Resolution is fully
	 * delegated to {@link #itemValuationCache}, which returns an
	 * already-resolved value or 0 while scheduling the real lookup on
	 * the client thread -- see its javadoc.
	 */
	private long unitValue(int itemId, LootValuationMode mode)
	{
		if (mode == LootValuationMode.HIGH_ALCH)
		{
			return itemValuationCache.getHighAlchPrice(itemId);
		}
		return itemValuationCache.getGePrice(itemId);
	}

	/**
	 * Shared rectangular-card chrome.
	 *
	 * The card background is the skill's own true tint ({@code base}),
	 * blended only slightly TOWARD {@link SpyglassTheme#SURFACE_CARD}
	 * (not the other way around) so it still coheres with the
	 * surrounding Dark Bronze chrome without reading as an unrelated
	 * flat color swatch. {@code isPrimary} gets a stronger/purer
	 * identity than a secondary card (less bronze blended in).
	 * {@code tint} null (the empty/no-skill case never actually reaches
	 * this) falls back to a plain {@link SpyglassTheme#SURFACE_CARD}
	 * card with no accent stripe. A thin {@link SpyglassTheme#BORDER_SOFT}
	 * border, full-width -- no rounded corners, no gradients, no drop
	 * shadow.
	 *
	 * SHARED WITH {@link #renderSlayerSection}: that method calls this
	 * same helper with Slayer's own tint, so the Slayer/task card picks
	 * up the identical coloring as a side effect of sharing this widget.
	 */
	private JPanel cardPanel(Color tint, boolean isPrimary)
	{
		JPanel card = new JPanel();
		card.setOpaque(true);
		card.setBackground(tint == null ? SpyglassTheme.SURFACE_CARD : blend(tint, SpyglassTheme.SURFACE_CARD, isPrimary ? 0.12f : 0.22f));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);

		Border outer = BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT);
		Border padding = BorderFactory.createEmptyBorder(5, 7, 5, 7);
		Border chrome = tint == null
			? BorderFactory.createCompoundBorder(outer, padding)
			: BorderFactory.createCompoundBorder(
				BorderFactory.createCompoundBorder(outer, BorderFactory.createMatteBorder(0, isPrimary ? 4 : 3, 0, 0, tint)),
				padding);
		card.setBorder(chrome);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
		return card;
	}

	// A simple linear RGB blend from `base` toward `tint` at `weight`.
	// Callers pass the skill's own true color as `base` and
	// SpyglassTheme's bronze SURFACE_CARD as `tint`, at a low weight --
	// so the card background is primarily the skill's own color with
	// only a small bronze cast mixed in for cohesion (see cardPanel()'s
	// own javadoc for why).
	private static Color blend(Color base, Color tint, float weight)
	{
		int r = clampByte(Math.round(base.getRed() + (tint.getRed() - base.getRed()) * weight));
		int g = clampByte(Math.round(base.getGreen() + (tint.getGreen() - base.getGreen()) * weight));
		int b = clampByte(Math.round(base.getBlue() + (tint.getBlue() - base.getBlue()) * weight));
		return new Color(r, g, b);
	}

	private static int clampByte(int value)
	{
		return Math.max(0, Math.min(255, value));
	}

	/**
	 * Authentic OSRS skill icon via RuneLite's own {@link SkillIconManager}
	 * -- never a custom/approximated icon, per the project's hard UI
	 * asset rule. {@code skillName} is always a real {@link Skill#name()}
	 * value here; the try/catch is defense in depth only.
	 */
	private ImageIcon skillIcon(String skillName)
	{
		try
		{
			return new ImageIcon(skillIconManager.getSkillImage(Skill.valueOf(skillName)));
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			return new ImageIcon();
		}
	}

	static final String HEADLINE_NAME = "sessionHeadline";
	static final String ACTIVITY_LINE_NAME = "sessionActivityLine";
	/** The header's wrapping "elapsed / kills" fact line. */
	static final String SESSION_FACTS_NAME = "sessionFacts";
	/** A skill card's XP/hr lane (the rate, or its warm-up placeholder). */
	static final String XP_RATE_NAME = "xpRate";
	static final String RATE_WARMUP_TOOLTIP = "Rates appear after 5 minutes of activity, so one early XP drop or kill can't project an unrealistic hourly rate.";

	/** "XP/hr in 4m" -- whole minutes left, rounded up, never "in 0m". */
	static String rateWarmupText(long remainingMillis)
	{
		long minutes = Math.max(1L, (remainingMillis + 59_999L) / 60_000L);
		return "XP/hr in " + minutes + "m";
	}
	static final String BARROWS_BLOCK_NAME = "barrowsBlock";
	static final String BARROWS_BROTHER_COUNT_NAME = "barrowsBrotherCount";
	static final String BARROWS_BROTHER_PREFIX = "barrowsBrother:";

	private static boolean isBarrows(CurrentSessionSnapshot snapshot)
	{
		return snapshot.getActivityType() == ActivityType.STRUCTURED_ACTIVITY
			&& StructuredActivityCatalog.BARROWS.equals(snapshot.getActivityKey());
	}

	/**
	 * BARROWS: activity-native block between the header and XP/Loot.
	 * CURRENT RUN mirrors the game's own brother varbits (omitted while they
	 * are unknown -- never guessed); the six brothers sit on a 3x3 grid by
	 * their mound positions, cleared ones muted and ticked. SESSION carries
	 * the existing chest count/rate (moved here from the header, not
	 * duplicated). Descriptive only: nothing here suggests an order.
	 */
	private void renderBarrowsBlock(CurrentSessionSnapshot snapshot)
	{
		JPanel block = new JPanel();
		block.setName(BARROWS_BLOCK_NAME);
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		block.setAlignmentX(Component.LEFT_ALIGNMENT);

		java.util.function.Supplier<BarrowsRunProgress> supplier = barrowsProgressSupplier;
		BarrowsRunProgress progress = supplier == null ? null : supplier.get();
		if (progress != null)
		{
			block.add(caption("Current run"));
			block.add(Box.createVerticalStrut(2));
			JLabel count = new JLabel("Brothers " + progress.getClearedCount() + " / " + BarrowsRunProgress.total());
			count.setName(BARROWS_BROTHER_COUNT_NAME);
			count.setFont(FontManager.getRunescapeBoldFont());
			count.setForeground(SpyglassTheme.TEXT_PRIMARY);
			count.setAlignmentX(Component.LEFT_ALIGNMENT);
			block.add(count);
			block.add(Box.createVerticalStrut(3));
			block.add(brothersSchematic(progress));
			block.add(Box.createVerticalStrut(6));
		}

		if (snapshot.getCompletionLabel() != null)
		{
			block.add(caption("Session"));
			block.add(Box.createVerticalStrut(2));
			block.add(activitySessionLine(snapshot));
			block.add(Box.createVerticalStrut(6));
		}

		if (block.getComponentCount() == 0)
		{
			return;
		}
		content.add(block);
		content.add(separator());
		content.add(Box.createVerticalStrut(6));
	}

	static final String TEMPOROSS_BLOCK_NAME = "temporossBlock";
	static final String TEMPOROSS_BAR_PREFIX = "temporossBar:";
	static final String TEMPOROSS_POINTS_NAME = "temporossPoints";
	static final String TEMPOROSS_PERMITS_NAME = "temporossPermits";
	static final String TEMPOROSS_AVG_NAME = "temporossAvgPoints";
	static final String TEMPOROSS_BEST_NAME = "temporossBestPoints";

	private static boolean isTempoross(CurrentSessionSnapshot snapshot)
	{
		return snapshot.getActivityType() == ActivityType.STRUCTURED_ACTIVITY
			&& StructuredActivityCatalog.TEMPOROSS.equals(snapshot.getActivityKey());
	}

	/** Activities whose own block carries the completion line (so the header must not repeat it). */
	private static boolean hasActivityBlock(CurrentSessionSnapshot snapshot)
	{
		return isBarrows(snapshot) || isTempoross(snapshot) || isGotr(snapshot);
	}

	/**
	 * TEMPOROSS: activity-native block between the header and XP/Loot.
	 * CURRENT GAME mirrors the game's own HUD (Energy / Essence / Storm
	 * as thin bars) and the points varbit -- each value only when the game
	 * actually shows it, never guessed, and the whole section is omitted
	 * outside a game. SESSION carries the existing games count/rate (moved
	 * here from the header, not duplicated).
	 */
	private void renderTemporossBlock(CurrentSessionSnapshot snapshot)
	{
		JPanel block = new JPanel();
		block.setName(TEMPOROSS_BLOCK_NAME);
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		block.setAlignmentX(Component.LEFT_ALIGNMENT);

		java.util.function.Supplier<TemporossGameState> supplier = temporossGameSupplier;
		TemporossGameState game = null;
		if (supplier != null && snapshot.getState() == SessionState.ACTIVE)
		{
			game = supplier.get();
		}
		if (game != null)
		{
			block.add(caption("Current game"));
			block.add(Box.createVerticalStrut(3));
			if (game.getPoints() != null)
			{
				JPanel pointsRow = new JPanel(new BorderLayout(4, 0));
				pointsRow.setOpaque(false);
				pointsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
				JLabel label = new JLabel("Points");
				label.setFont(FontManager.getRunescapeSmallFont());
				label.setForeground(SpyglassTheme.TEXT_SECONDARY);
				JLabel value = new JLabel(formatNumber(game.getPoints()));
				value.setName(TEMPOROSS_POINTS_NAME);
				value.setFont(FontManager.getRunescapeBoldFont());
				value.setForeground(SpyglassTheme.ACCENT_GOLD);
				value.setHorizontalAlignment(JLabel.RIGHT);
				pointsRow.add(label, BorderLayout.WEST);
				pointsRow.add(value, BorderLayout.EAST);
				pointsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, pointsRow.getPreferredSize().height));
				block.add(pointsRow);
				block.add(Box.createVerticalStrut(4));
			}
			addGameBar(block, "Energy", game.getEnergy(), SpyglassTheme.METER_PRIMARY);
			addGameBar(block, "Essence", game.getEssence(), SpyglassTheme.METER_SECONDARY);
			addGameBar(block, "Storm", game.getStorm(), SpyglassTheme.METER_DANGER);
			block.add(Box.createVerticalStrut(3));
		}

		if (snapshot.getCompletionLabel() != null)
		{
			block.add(caption("Session"));
			block.add(Box.createVerticalStrut(2));
			block.add(activitySessionLine(snapshot));
			addTemporossSessionStats(block, snapshot);
			block.add(Box.createVerticalStrut(6));
		}

		if (block.getComponentCount() == 0)
		{
			return;
		}
		content.add(block);
		content.add(separator());
		content.add(Box.createVerticalStrut(6));
	}

	/**
	 * SESSION summary rows under "Games / Games/hr": permits EARNED this
	 * session (never the balance) and Avg / Best final points of fully
	 * observed completed games. Each row only when it has a value.
	 */
	private static void addTemporossSessionStats(JPanel block, CurrentSessionSnapshot snapshot)
	{
		String currency = CompletionMetricText.currencyLabelFor(snapshot.getActivityKey());
		long gained = snapshot.getActivityCurrencyGained();
		if (currency != null && gained > 0)
		{
			block.add(Box.createVerticalStrut(2));
			JPanel row = statRow(currency, TEMPOROSS_PERMITS_NAME + ":label", CompletionMetricText.currencyText(gained),
				TEMPOROSS_PERMITS_NAME, SpyglassTheme.TEXT_PRIMARY);
			row.setToolTipText(currency + " earned this session (spending never reduces it)");
			block.add(row);
		}
		Long average = snapshot.getAverageScore();
		Integer best = snapshot.getBestScore();
		if (average != null && best != null)
		{
			block.add(Box.createVerticalStrut(2));
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setOpaque(false);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			JLabel avg = smallLabel("Avg " + CompletionMetricText.pointsText(average), SpyglassTheme.TEXT_PRIMARY);
			avg.setName(TEMPOROSS_AVG_NAME);
			JLabel top = smallLabel("Best " + CompletionMetricText.pointsText(best), SpyglassTheme.TEXT_PRIMARY);
			top.setName(TEMPOROSS_BEST_NAME);
			top.setHorizontalAlignment(JLabel.RIGHT);
			row.add(avg, BorderLayout.WEST);
			row.add(top, BorderLayout.EAST);
			int scored = snapshot.getScoredGames();
			String basis = scored + " fully observed game" + (scored == 1 ? "" : "s")
				+ (scored < snapshot.getCompletionCount() ? " of " + snapshot.getCompletionCount() : "");
			row.setToolTipText("Final points per completed game (" + basis + ")");
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
			block.add(row);
		}
	}

	static final String GOTR_BLOCK_NAME = "gotrBlock";
	static final String GOTR_POWER_NAME = "gotrGuardianPower";
	static final String GOTR_ENERGY_PREFIX = "gotrEnergy:";
	static final String RUNES_CRAFTED_NAME = "runesCrafted";
	static final String RUNES_CRAFTED_TOTAL_NAME = "runesCraftedTotal";

	private static boolean isGotr(CurrentSessionSnapshot snapshot)
	{
		return snapshot.getActivityType() == ActivityType.STRUCTURED_ACTIVITY
			&& StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT.equals(snapshot.getActivityKey());
	}

	/**
	 * GUARDIANS OF THE RIFT: activity-native block between the header and
	 * XP/Loot.
	 * <ul>
	 * <li>CURRENT GAME: Guardian Power (prominent, with a thin bar) and this
	 * player's Elemental / Catalytic / Total energy -- each only when the
	 * game actually reports it, the whole section only while the GotR HUD is
	 * up.</li>
	 * <li>SESSION: games and rate (moved here from the header), reward points
	 * EARNED this session per currency, and Avg / Best total energy of fully
	 * observed won games.</li>
	 * <li>RUNES CRAFTED: cumulative runes produced at GotR altars this session
	 * (confirmed crafts only) as an item grid -- output, never loot.</li>
	 * </ul>
	 */
	private void renderGotrBlock(CurrentSessionSnapshot snapshot)
	{
		JPanel block = new JPanel();
		block.setName(GOTR_BLOCK_NAME);
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		block.setAlignmentX(Component.LEFT_ALIGNMENT);

		java.util.function.Supplier<GotrGameState> supplier = gotrGameSupplier;
		GotrGameState game = supplier != null && snapshot.getState() == SessionState.ACTIVE ? supplier.get() : null;
		if (game != null)
		{
			block.add(caption("Current game"));
			block.add(Box.createVerticalStrut(3));
			if (game.getGuardianPowerPercent() != null)
			{
				JPanel powerRow = new JPanel(new BorderLayout(4, 0));
				powerRow.setOpaque(false);
				powerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
				JLabel label = new JLabel("Guardian Power");
				label.setFont(FontManager.getRunescapeSmallFont());
				label.setForeground(SpyglassTheme.TEXT_SECONDARY);
				JLabel value = new JLabel(game.getGuardianPowerPercent() + "%");
				value.setName(GOTR_POWER_NAME);
				value.setFont(FontManager.getRunescapeBoldFont());
				value.setForeground(SpyglassTheme.ACCENT_GOLD);
				value.setHorizontalAlignment(JLabel.RIGHT);
				powerRow.add(label, BorderLayout.WEST);
				powerRow.add(value, BorderLayout.EAST);
				powerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, powerRow.getPreferredSize().height));
				block.add(powerRow);
				block.add(Box.createVerticalStrut(1));
				ThinBar bar = new ThinBar(game.getGuardianPowerPercent(), SpyglassTheme.METER_PRIMARY);
				bar.setName(TEMPOROSS_BAR_PREFIX + "Guardian Power:" + game.getGuardianPowerPercent());
				bar.setToolTipText("The Great Guardian's power: " + game.getGuardianPowerPercent() + "%");
				block.add(bar);
				block.add(Box.createVerticalStrut(4));
			}
			addEnergyRow(block, "Elemental", game.getElementalEnergy(), "Elemental energy earned this game");
			addEnergyRow(block, "Catalytic", game.getCatalyticEnergy(), "Catalytic energy earned this game");
			addEnergyRow(block, "Total", game.getTotalEnergy(), "Total energy earned this game (elemental + catalytic)");
			block.add(Box.createVerticalStrut(6));
			if (!game.getCandidates().isEmpty())
			{
				// STRATEGY: its own section right after CURRENT GAME.
				block.add(gotrStrategyRow());
				block.add(Box.createVerticalStrut(3));
				addGotrStrategy(block, game);
				block.add(Box.createVerticalStrut(6));
			}
		}

		if (snapshot.getCompletionLabel() != null)
		{
			block.add(caption("Session"));
			block.add(Box.createVerticalStrut(3));
			block.add(gotrSessionGrid(snapshot));
			block.add(Box.createVerticalStrut(6));
		}

		List<CurrentSessionSnapshot.ProducedItemView> runes = snapshot.getProducedItems();
		if (!runes.isEmpty())
		{
			// Caption left, total right: fits the narrowest sidebar (one long
			// "RUNES CRAFTED • 14,274 total" string does not at 150px).
			long total = snapshot.getProducedItemTotal();
			String number = ProducedItemGrid.compactTotal(total);
			JLabel header = caption("Runes crafted");
			header.setName(RUNES_CRAFTED_NAME);
			JLabel totalLabel = smallLabel(number + " total", SpyglassTheme.TEXT_MUTED);
			JPanel headerRow = new JPanel(new BorderLayout(6, 0))
			{
				@Override
				public void doLayout()
				{
					// "12,818 total" where it fits, just "12,818" in the narrowest sidebar.
					String full = number + " total";
					int fullWidth = totalLabel.getFontMetrics(totalLabel.getFont()).stringWidth(full);
					String want = header.getPreferredSize().width + 6 + fullWidth <= getWidth() || getWidth() <= 0 ? full : number;
					if (!want.equals(totalLabel.getText()))
					{
						totalLabel.setText(want);
					}
					super.doLayout();
				}
			};
			headerRow.setOpaque(false);
			headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			totalLabel.setName(RUNES_CRAFTED_TOTAL_NAME);
			totalLabel.setHorizontalAlignment(JLabel.RIGHT);
			headerRow.add(header, BorderLayout.WEST);
			headerRow.add(totalLabel, BorderLayout.EAST);
			headerRow.setToolTipText(String.format(java.util.Locale.ROOT, "%,d", total)
				+ " runes produced at Guardians of the Rift altars this session (confirmed crafts only)");
			headerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, headerRow.getPreferredSize().height));
			block.add(headerRow);
			block.add(Box.createVerticalStrut(3));
			block.add(ProducedItemGrid.build(runes, itemManager));
			block.add(Box.createVerticalStrut(6));
		}

		if (block.getComponentCount() == 0)
		{
			return;
		}
		content.add(block);
		content.add(separator());
		content.add(Box.createVerticalStrut(6));
	}

	private static void addEnergyRow(JPanel block, String label, Integer value, String tooltip)
	{
		if (value == null)
		{
			return;
		}
		JPanel row = statRow(label, GOTR_ENERGY_PREFIX + label + ":label", formatNumber(value), GOTR_ENERGY_PREFIX + label,
			SpyglassTheme.TEXT_PRIMARY);
		row.setToolTipText(tooltip);
		block.add(row);
		block.add(Box.createVerticalStrut(1));
	}

	/** Reward points EARNED this session (never balances) per currency, then Avg / Best total energy. */
	static final String GOTR_STRATEGY_POINTS_NAME = "gotrStrategy:POINTS";
	static final String GOTR_STRATEGY_XP_NAME = "gotrStrategy:XP";
	static final String GOTR_STRATEGY_GRID_NAME = "gotrStrategyGrid";
	static final String GOTR_ALTAR_PREFIX = "gotrAltar:";
	static final String GOTR_ADVICE_NAME = "gotrAdvice";
	static final String GOTR_SESSION_GRID_NAME = "gotrSessionGrid";
	static final String GOTR_GAMES_CELL = "GAMES";
	static final String GOTR_GAMES_PER_HOUR_CELL = "GAMES/HR";
	static final String GOTR_AVG_CELL = "AVG";
	static final String GOTR_ELEMENTAL_CELL = "ELEMENTAL";
	static final String GOTR_CATALYTIC_CELL = "CATALYTIC";
	static final String GOTR_BEST_CELL = "BEST";
	static final String GOTR_REWARDS_NAME = "gotrRewards";
	static final String GOTR_REWARDS_GRID_NAME = "gotrRewardsGrid";
	static final String GOTR_SEARCHES_CELL = "SEARCHES";
	static final String GOTR_BALANCE_CELL = "BALANCE";
	static final String GOTR_WALLET_ELEMENTAL_CELL = "ELEMENTAL";
	static final String GOTR_WALLET_CATALYTIC_CELL = "CATALYTIC";
	static final String GOTR_WALLET_UNKNOWN_NAME = "gotrWalletUnknown";

	/** "STRATEGY ......... Points · XP": caption left, the two choices right (like the Slayer XP scope). */
	private JPanel gotrStrategyRow()
	{
		GotrStrategy strategy = effectiveGotrStrategy();
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(caption("Strategy"), BorderLayout.WEST);
		JPanel options = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		options.setOpaque(false);
		options.add(strategyOption("Points", GOTR_STRATEGY_POINTS_NAME, GotrStrategy.POINTS, strategy));
		options.add(smallLabel(" · ", SpyglassTheme.TEXT_MUTED));
		options.add(strategyOption("XP", GOTR_STRATEGY_XP_NAME, GotrStrategy.XP, strategy));
		row.add(options, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private JLabel strategyOption(String text, String name, GotrStrategy option, GotrStrategy selected)
	{
		boolean active = option == selected;
		JLabel label = smallLabel(text, active ? SpyglassTheme.ACCENT_GOLD : SpyglassTheme.TEXT_MUTED);
		label.setName(name + (active ? ":selected" : ""));
		label.setToolTipText(option == GotrStrategy.POINTS
			? "Advise for even reward points, so searches are not wasted"
			: "Advise for Runecraft experience");
		label.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		label.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				selectGotrStrategy(option);
			}
		});
		return label;
	}

	/**
	 * STRATEGY grid: one cell per altar the player can enter right now -- the
	 * open rifts and any held portal talisman's altar (the access shown only
	 * when it is a talisman) -- each with its name, the advice mark
	 * (BEST / FOCUS) and one concise reason; the fuller "why" lives in the
	 * tooltip. Reflows like the other grids. Advice only when Spyglass can
	 * actually judge (see GotrAdvice's CONFIDENCE RULE); when no cell carries
	 * a mark, one quiet line says why (either / capped). Never persisted.
	 */
	private void addGotrStrategy(JPanel block, GotrGameState game)
	{
		GotrStrategy strategy = effectiveGotrStrategy();
		GotrAdvice advice = GotrAdvice.of(strategy, game, gotrWallet());
		java.util.List<GotrCandidate> candidates = game.getCandidates();
		SlayerStatGrid grid = new SlayerStatGrid();
		grid.setName(GOTR_STRATEGY_GRID_NAME);
		grid.setMaxColumns(candidates.size() == 4 ? 2 : 3);
		boolean anyMark = false;
		for (GotrCandidate candidate : candidates)
		{
			GotrAltar altar = candidate.getAltar();
			GotrAdvice.Mark mark = advice.markOf(candidate);
			boolean marked = mark != GotrAdvice.Mark.NONE;
			boolean locked = Boolean.FALSE.equals(candidate.getUsable());
			anyMark |= marked;
			String suffix = mark == GotrAdvice.Mark.BEST ? ":best" : mark == GotrAdvice.Mark.FOCUS ? ":focus" : locked ? ":locked" : "";

			JLabel name = new JLabel(altar.getDisplayName());
			name.setName(GOTR_ALTAR_PREFIX + altar.name() + suffix);
			name.setFont(FontManager.getRunescapeBoldFont());
			name.setForeground(marked ? SpyglassTheme.ACCENT_GOLD : locked ? SpyglassTheme.TEXT_MUTED : SpyglassTheme.TEXT_PRIMARY);

			JLabel access = null;
			if (candidate.getAccess() == GotrCandidate.Access.TALISMAN)
			{
				access = smallLabel(candidate.getAccess().getLabel(), SpyglassTheme.TEXT_MUTED);
				access.setName(GOTR_ALTAR_PREFIX + altar.name() + ":access");
			}
			JLabel markLabel = null;
			if (marked)
			{
				markLabel = smallLabel(markText(mark), SpyglassTheme.ACCENT_GOLD);
				markLabel.setName(GOTR_ALTAR_PREFIX + altar.name() + ":mark");
			}
			String reasonText = marked ? advice.getReason()
				: locked ? "Locked"
				: strategy == GotrStrategy.XP ? GotrAdvice.perEssence(altar.getXpPerEssence())
				: altar.getType().getLabel();
			JLabel reason = smallLabel(reasonText, SpyglassTheme.TEXT_MUTED);
			reason.setName(GOTR_ALTAR_PREFIX + altar.name() + ":reason");

			grid.addLinesCell(altar.name(), altarTooltip(candidate, marked ? advice.getDetail() : null),
				name, access, markLabel, reason);
		}
		block.add(grid);

		String summary = null;
		if (!anyMark)
		{
			if (advice.getOutcome() == GotrAdvice.Outcome.EITHER)
			{
				summary = "Either side";
			}
			else if (advice.getOutcome() == GotrAdvice.Outcome.CAPPED)
			{
				summary = "No more points this game";
			}
		}
		if (summary != null)
		{
			FactLine line = new FactLine(FontManager.getRunescapeSmallFont(), SpyglassTheme.TEXT_SECONDARY);
			line.setName(GOTR_ADVICE_NAME);
			line.fact(summary, SpyglassTheme.TEXT_SECONDARY);
			if (advice.getOutcome() == GotrAdvice.Outcome.EITHER && advice.getReason() != null)
			{
				line.fact(advice.getReason(), SpyglassTheme.TEXT_MUTED);
			}
			if (advice.getDetail() != null)
			{
				line.setToolTipText(advice.getDetail());
			}
			block.add(Box.createVerticalStrut(2));
			block.add(line);
		}
	}

	/** The advice mark ("★ BEST"), with a plain "*" where the font lacks the star. */
	private static String markText(GotrAdvice.Mark mark)
	{
		String label = mark.getLabel();
		return FontManager.getRunescapeSmallFont().canDisplay('★') ? label : label.replace('★', '*');
	}

	private static String altarTooltip(GotrCandidate candidate, String detail)
	{
		GotrAltar altar = candidate.getAltar();
		StringBuilder sb = new StringBuilder(altar.getDisplayName()).append(" altar (")
			.append(altar.getType().getLabel().toLowerCase(java.util.Locale.ROOT)).append(", ")
			.append(String.format(java.util.Locale.ROOT, "%.1f", altar.getXpPerEssence())).append(" XP per essence)");
		sb.append(candidate.getAccess() == GotrCandidate.Access.TALISMAN
			? " -- reachable with your portal talisman (used up unless its rift is open)"
			: " -- rift open now");
		if (Boolean.FALSE.equals(candidate.getUsable()))
		{
			sb.append(" -- needs Runecraft ").append(altar.getRunecraftLevel())
				.append(altar.getQuest() == null ? "" : " and " + altar.getQuest().getName());
		}
		if (detail != null)
		{
			sb.append(". ").append(detail);
		}
		return sb.toString();
	}

	private GotrRewardWallet gotrWallet()
	{
		java.util.function.Supplier<GotrRewardWallet> supplier = gotrWalletSupplier;
		return supplier == null ? null : supplier.get();
	}

	/**
	 * SESSION, 3 x 2 (2 x 3 when narrow):
	 * GAMES | GAMES/HR | AVG over ELEMENTAL | CATALYTIC | BEST -- Avg / Best
	 * being total energy per fully observed won game (said in their tooltips;
	 * short captions so the grid still reflows to 2 x 3 at 150 px).
	 * Elemental / Catalytic are reward points EARNED this session (the game's
	 * own end-of-game award), never this game's energy and never the wallet.
	 * Unknown values are a quiet dash, never a guess.
	 */
	private static SlayerStatGrid gotrSessionGrid(CurrentSessionSnapshot snapshot)
	{
		SlayerStatGrid grid = new SlayerStatGrid();
		grid.setName(GOTR_SESSION_GRID_NAME);
		int games = snapshot.getCompletionCount();
		Double rate = CompletionMetricText.perHour(games, snapshot.getActiveDurationMillis());
		grid.addCell(GOTR_GAMES_CELL, formatNumber(games), SpyglassTheme.TEXT_PRIMARY, "Won games this session (a lost game never counts)");
		grid.addCell(GOTR_GAMES_PER_HOUR_CELL, rate == null ? null : String.format(java.util.Locale.ROOT, "%.1f", rate), SpyglassTheme.TEXT_PRIMARY,
			rate == null ? "Won games per hour of active play. " + RATE_WARMUP_TOOLTIP : "Won games per hour of active play");
		Long average = snapshot.getAverageScore();
		Integer best = snapshot.getBestScore();
		int scored = snapshot.getScoredGames();
		String basis = scored + " fully observed won game" + (scored == 1 ? "" : "s")
			+ (scored < games ? " of " + games + " (games joined part-way never enter Avg / Best)" : "");
		grid.addCell(GOTR_AVG_CELL, average == null ? null : CompletionMetricText.pointsText(average), SpyglassTheme.TEXT_PRIMARY,
			"Average total energy per won game -- " + basis);
		java.util.List<String> keys = CompletionMetricText.currencyKeysFor(snapshot.getActivityKey());
		long elemental = keys.size() == 2 ? snapshot.getActivityCurrencyGained(keys.get(0)) : 0L;
		long catalytic = keys.size() == 2 ? snapshot.getActivityCurrencyGained(keys.get(1)) : 0L;
		grid.addCell(GOTR_ELEMENTAL_CELL, elemental > 0 ? "+" + formatNumber(elemental) : null, SpyglassTheme.ACCENT_GOLD,
			"Elemental reward points earned this session (searches never reduce it)");
		grid.addCell(GOTR_CATALYTIC_CELL, catalytic > 0 ? "+" + formatNumber(catalytic) : null, SpyglassTheme.ACCENT_GOLD,
			"Catalytic reward points earned this session (searches never reduce it)");
		grid.addCell(GOTR_BEST_CELL, best == null ? null : CompletionMetricText.pointsText(best), SpyglassTheme.TEXT_PRIMARY,
			"Best total energy in a won game -- " + basis);
		return grid;
	}

	/** REWARDS (GotR only, right above LOOT): the reward wallet, usable searches and which side limits them. */
	boolean showsGotrRewards(CurrentSessionSnapshot snapshot)
	{
		return isGotr(snapshot) && (gotrWallet() != null || snapshot.getState() == SessionState.ACTIVE);
	}

	/**
	 * REWARDS, 2 x 2 (1 x 4 when narrow): SEARCHES | BALANCE over
	 * ELEMENTAL | CATALYTIC. Searches in gold; Balanced in muted sage, a
	 * behind side in muted amber; balances in normal strong text. Unknown
	 * wallet: no grid and no zeroes -- one quiet line, the hint in its tooltip.
	 */
	private void renderGotrRewards(CurrentSessionSnapshot snapshot)
	{
		GotrRewardWallet wallet = gotrWallet();
		JPanel block = new JPanel();
		block.setName(GOTR_REWARDS_NAME);
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setOpaque(false);
		block.setAlignmentX(Component.LEFT_ALIGNMENT);
		block.add(caption("Rewards"));
		block.add(Box.createVerticalStrut(3));
		if (wallet == null)
		{
			JLabel unknown = smallLabel("Reward balance unknown", SpyglassTheme.TEXT_MUTED);
			unknown.setName(GOTR_WALLET_UNKNOWN_NAME);
			unknown.setToolTipText("Use Check at the Rewards Guardian, or finish a game, and the balance appears here");
			unknown.setAlignmentX(Component.LEFT_ALIGNMENT);
			block.add(unknown);
		}
		else
		{
			SlayerStatGrid grid = new SlayerStatGrid();
			grid.setName(GOTR_REWARDS_GRID_NAME);
			grid.setMaxColumns(2);
			grid.addCell(GOTR_SEARCHES_CELL, formatNumber(wallet.searches()), SpyglassTheme.ACCENT_GOLD,
				"Rewards Guardian searches you can make now (each costs one elemental and one catalytic point)");
			GotrAltar.Type limiting = wallet.limitingSide();
			grid.addCell(GOTR_BALANCE_CELL, limiting == null ? "Balanced" : limiting.getLabel() + " -" + wallet.imbalance(),
				limiting == null ? SpyglassTheme.ACCENT_SAGE : SpyglassTheme.ACCENT_AMBER,
				limiting == null
					? "Both point types are even: no search points are going to waste"
					: limiting.getLabel() + " is " + wallet.imbalance() + " behind: " + wallet.imbalance() + " "
						+ (limiting == GotrAltar.Type.ELEMENTAL ? "catalytic" : "elemental") + " point"
						+ (wallet.imbalance() == 1 ? "" : "s") + " cannot be used until "
						+ limiting.getLabel().toLowerCase(java.util.Locale.ROOT) + " catches up");
			grid.addCell(GOTR_WALLET_ELEMENTAL_CELL, formatNumber(wallet.getElemental()), SpyglassTheme.TEXT_PRIMARY,
				"Elemental reward points you hold");
			grid.addCell(GOTR_WALLET_CATALYTIC_CELL, formatNumber(wallet.getCatalytic()), SpyglassTheme.TEXT_PRIMARY,
				"Catalytic reward points you hold");
			block.add(grid);
		}
		block.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
		content.add(block);
	}

	/** Label at the left, value at the right: one compact line that never clips. */
	private static JPanel statRow(String label, String labelName, String value, String valueName, Color valueColor)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel l = smallLabel(label, SpyglassTheme.TEXT_SECONDARY);
		l.setName(labelName);
		JLabel v = smallLabel(value, valueColor);
		v.setName(valueName);
		v.setHorizontalAlignment(JLabel.RIGHT);
		row.add(l, BorderLayout.WEST);
		row.add(v, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private static JLabel smallLabel(String text, Color color)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		return label;
	}

	/** "Energy ... 87%" over a thin painted bar; nothing at all when the value is unknown. */
	private static void addGameBar(JPanel block, String name, Integer percent, Color color)
	{
		if (percent == null)
		{
			return;
		}
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel label = new JLabel(name);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(SpyglassTheme.TEXT_SECONDARY);
		JLabel value = new JLabel(percent + "%");
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(SpyglassTheme.TEXT_PRIMARY);
		value.setHorizontalAlignment(JLabel.RIGHT);
		row.add(label, BorderLayout.WEST);
		row.add(value, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		block.add(row);
		block.add(Box.createVerticalStrut(1));
		ThinBar bar = new ThinBar(percent, color);
		bar.setName(TEMPOROSS_BAR_PREFIX + name + ":" + percent);
		bar.setToolTipText(name + ": " + percent + "%");
		block.add(bar);
		block.add(Box.createVerticalStrut(4));
	}

	/** A 4px custom-painted fill bar: no JProgressBar L&F chrome, scales to any sidebar width. */
	static final class ThinBar extends JPanel
	{
		private static final int HEIGHT = 4;
		private final int percent;
		private final Color fill;

		ThinBar(int percent, Color fill)
		{
			this.percent = Math.max(0, Math.min(100, percent));
			this.fill = fill;
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			setPreferredSize(new Dimension(10, HEIGHT));
			setMinimumSize(new Dimension(0, HEIGHT));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
		}

		int getPercent()
		{
			return percent;
		}

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			int w = getWidth();
			int h = getHeight();
			g.setColor(SpyglassTheme.METER_TRACK);
			g.fillRect(0, 0, w, h);
			int filled = (int) Math.round(w * (percent / 100.0));
			if (filled > 0)
			{
				g.setColor(fill);
				g.fillRect(0, 0, filled, h);
			}
		}
	}

	static final String COMPLETION_RATE_NAME = "completionRate";

	/**
	 * An activity block's SESSION line: count at the left, rate at the
	 * right (two labels, not one long string), so "Games: 6 • Games/hr: 7.7"
	 * never clips in the narrowest sidebar.
	 */
	private static JPanel activitySessionLine(CurrentSessionSnapshot snapshot)
	{
		String label = snapshot.getCompletionLabel();
		int count = snapshot.getCompletionCount();
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel countLabel = new JLabel(CompletionMetricText.countText(label, count));
		countLabel.setName(COMPLETION_METRIC_NAME);
		countLabel.setFont(FontManager.getRunescapeSmallFont());
		countLabel.setForeground(SpyglassTheme.TEXT_PRIMARY);
		row.add(countLabel, BorderLayout.WEST);
		Double rate = CompletionMetricText.perHour(count, snapshot.getActiveDurationMillis());
		if (rate != null)
		{
			JLabel rateLabel = new JLabel(label + "/hr: " + String.format(java.util.Locale.ROOT, "%.1f", rate));
			rateLabel.setName(COMPLETION_RATE_NAME);
			rateLabel.setFont(FontManager.getRunescapeSmallFont());
			rateLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
			rateLabel.setHorizontalAlignment(JLabel.RIGHT);
			row.add(rateLabel, BorderLayout.EAST);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private static JLabel caption(String text)
	{
		JLabel label = new JLabel(text.toUpperCase(java.util.Locale.ROOT));
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(SpyglassTheme.TEXT_MUTED);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/** Six compact chips on a 3x3 grid by mound position (empty cells stay blank). */
	private static JPanel brothersSchematic(BarrowsRunProgress progress)
	{
		JPanel grid = new JPanel(new GridLayout(3, 3, 3, 3));
		grid.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		Component[] cells = new Component[9];
		for (BarrowsBrother brother : BarrowsBrother.values())
		{
			cells[brother.getSchematicRow() * 3 + brother.getSchematicColumn()] = brotherChip(brother, progress.isCleared(brother));
		}
		for (Component cell : cells)
		{
			if (cell == null)
			{
				JPanel blank = new JPanel();
				blank.setOpaque(false);
				grid.add(blank);
			}
			else
			{
				grid.add(cell);
			}
		}
		// Never taller than its three rows of chips.
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
		return grid;
	}

	private static JPanel brotherChip(BarrowsBrother brother, boolean cleared)
	{
		JPanel chip = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 1));
		chip.setName(BARROWS_BROTHER_PREFIX + brother.name() + (cleared ? ":cleared" : ":remaining"));
		chip.setOpaque(true);
		chip.setBackground(cleared ? SpyglassTheme.BACKGROUND_PANEL : SpyglassTheme.SURFACE_CARD);
		chip.setBorder(BorderFactory.createLineBorder(cleared ? SpyglassTheme.BORDER_SOFT : SpyglassTheme.BORDER_PRIMARY));
		chip.setToolTipText(brother.getDisplayName() + (cleared ? ": defeated this run" : ": not yet defeated this run"));

		JLabel name = new JLabel(brother.getDisplayName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(cleared ? SpyglassTheme.TEXT_MUTED : SpyglassTheme.TEXT_PRIMARY);
		chip.add(name);
		if (cleared)
		{
			// The RuneScape fonts have no check glyph (RuneLite's own Barrows
			// overlay uses the default font for it too).
			JLabel tick = new JLabel("\u2713");
			tick.setFont(FontManager.getDefaultFont());
			tick.setForeground(SpyglassTheme.STATUS_SUCCESS.darker());
			chip.add(tick);
		}
		return chip;
	}

	private JSeparator separator()
	{
		JSeparator separator = new JSeparator();
		separator.setForeground(SpyglassTheme.BORDER_SOFT);
		separator.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		separator.setAlignmentX(Component.LEFT_ALIGNMENT);
		return separator;
	}

	private static String toTitleCase(String enumName)
	{
		if (enumName == null || enumName.isEmpty())
		{
			return "";
		}
		String[] parts = enumName.split("_");
		StringBuilder sb = new StringBuilder();
		for (String part : parts)
		{
			if (part.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(part.substring(0, 1).toUpperCase()).append(part.substring(1).toLowerCase());
		}
		return sb.toString();
	}

	private static String formatNumber(long value)
	{
		return String.format("%,d", value);
	}

	private static String formatDuration(long millis)
	{
		long totalSeconds = millis / 1000L;
		long hours = totalSeconds / 3600L;
		long minutes = (totalSeconds % 3600L) / 60L;
		long seconds = totalSeconds % 60L;
		if (hours > 0)
		{
			return String.format("%d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format("%d:%02d", minutes, seconds);
	}
}
