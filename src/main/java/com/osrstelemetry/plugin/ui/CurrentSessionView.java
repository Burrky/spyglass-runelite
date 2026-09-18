package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.LootValuationMode;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.session.SessionState;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
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
 * type -- the new "Re-evaluate Session" button (see
 * {@link #renderHeader(CurrentSessionSnapshot)}) invokes only a plain
 * {@link Runnable} supplied by the constructor; it carries no
 * knowledge of what that Runnable does. All session-classification
 * logic for that button lives entirely in
 * {@code SessionRuntimeCoordinator.reEvaluateCurrentSession()} and
 * {@code ActivitySignalClassifier.classifyManualNpcTarget()} -- this
 * view is not, and must never become, a second place that decides
 * activity identity.
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
 * THREADING: {@link #render} must only ever be called on
 * the EDT. The "Re-evaluate Session" button's own click handler
 * (see {@link #renderHeader(CurrentSessionSnapshot)}) runs on the EDT
 * too (a Swing {@code ActionListener} always does), but does nothing
 * more than invoke the constructor-supplied {@link Runnable} -- that
 * Runnable (built by {@code OsrsTelemetryPanel}) is what dispatches the
 * real work onto RuneLite's client thread via
 * {@code ClientThread.invokeLater()}, so this button click itself never
 * blocks the EDT.
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
	// (config.lootValuationMode()) -- never cached -- same "read the
	// live value" idiom every collector in this project already
	// follows for its own config.xxx() checks.
	private final OsrsTelemetryConfig config;

	// The ENTIRE
	// surface this view has onto the "Re-evaluate Session" button's
	// backend action -- a single opaque callback, invoked verbatim from
	// the button's ActionListener (see renderHeader()) with no
	// session/classification knowledge of any kind on this class's part.
	// Built by OsrsTelemetryPanel (which owns the SessionRuntimeCoordinator
	// and NpcInteractionTargetCollector references this actually needs)
	// as a ClientThread.invokeLater() dispatch -- see that class's own
	// wiring and this class's own class-level THREADING note. Never
	// null in production; guarded defensively in the click handler only
	// so a future test harness can construct this view with a no-op.
	private final Runnable onReEvaluateRequested;

	CurrentSessionView(SkillIconManager skillIconManager, ItemManager itemManager, ClientThread clientThread, OsrsTelemetryConfig config, Runnable onReEvaluateRequested)
	{
		this.skillIconManager = skillIconManager;
		this.itemManager = itemManager;
		this.itemValuationCache = new ItemValuationCache(itemManager, clientThread);
		this.config = config;
		this.onReEvaluateRequested = onReEvaluateRequested;
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
	 * LAYOUT: 1. activity header/hero, 2. optional Slayer status, 3. one
	 * card per actively-gaining skill, 4. loot. Each section collapses
	 * away entirely when it has nothing to show -- there is no "no loot
	 * recorded yet" / "no Slayer task" placeholder text.
	 */
	void render(CurrentSessionSnapshot snapshot)
	{
		content.removeAll();

		if (!snapshot.isPresent())
		{
			renderEmptyState();
		}
		else
		{
			renderHeader(snapshot);
			renderSlayerSection(snapshot);
			renderSkillCards(snapshot);
			renderLootSection(snapshot);
		}

		content.revalidate();
		content.repaint();
	}

	private void renderEmptyState()
	{
		JLabel title = new JLabel("No active session");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(SpyglassTheme.TEXT_PRIMARY);
		title.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel subtitle = new JLabel("<html><div style='text-align:center;width:180px;'>"
			+ "Start playing normally and activity will appear here.</div></html>");
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
	 * compact "Re-evaluate Session" refresh button plus ACTIVE/SUSPENDED
	 * on the right. Second line: the SPECIFIC ACTIVITY (canonical
	 * display name), with the actual current NPC name appended when
	 * known and different -- e.g. "Basilisks &#8212; Basilisk Knight" --
	 * text only, no NPC portrait (see class javadoc). Third line:
	 * compact secondary info -- elapsed duration, plus a reliable
	 * kill/completion count when one has been observed (Slayer's own
	 * remaining-count figure lives in renderSlayerSection() instead, so
	 * it is never shown twice).
	 *
	 * RE-EVALUATE BUTTON: a small, compact, borderless {@link JButton}
	 * sharing the EAST region with the ACTIVE/SUSPENDED label -- not a
	 * full-width row. Rendered as a plain standard refresh glyph
	 * ("&#8635;") rather than an image: this project's RuneLite 1.12.38
	 * jars carry no bundled, general-purpose refresh icon suitable for
	 * reuse by another plugin -- every "refresh/reload/loop"-shaped PNG
	 * found lives inside another plugin's own private package (e.g.
	 * {@code net.runelite.client.plugins.timetracking.loop_icon.png}),
	 * not a shared RuneLite UI asset, and not safe/intended for
	 * cross-plugin reuse -- so a plain Unicode refresh arrow is used
	 * instead of any fabricated icon. Clicking it invokes ONLY
	 * {@link #onReEvaluateRequested} -- see that field's own javadoc
	 * and this class's class-level javadoc for why no
	 * session/classification logic lives here.
	 */
	private void renderHeader(CurrentSessionSnapshot snapshot)
	{
		boolean suspended = snapshot.getState() == SessionState.SUSPENDED;
		Color accent = suspended ? SpyglassTheme.STATUS_SUSPENDED : SpyglassTheme.STATUS_SUCCESS;

		JPanel topRow = new JPanel(new BorderLayout(6, 0));
		topRow.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		topRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		String primarySkill = snapshot.getPrimarySkill();
		JPanel skillIdentity = new JPanel(new BorderLayout(6, 0));
		skillIdentity.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		if (primarySkill != null)
		{
			skillIdentity.add(new JLabel(skillIcon(primarySkill)), BorderLayout.WEST);
		}

		String headline = primarySkill != null
			? toTitleCase(primarySkill)
			: (snapshot.getDisplayName() == null ? "Unknown activity" : snapshot.getDisplayName());
		JLabel headlineLabel = new JLabel(headline);
		headlineLabel.setFont(FontManager.getRunescapeBoldFont());
		headlineLabel.setForeground(SpyglassTheme.TEXT_PRIMARY);
		skillIdentity.add(headlineLabel, BorderLayout.CENTER);

		JLabel stateLabel = new JLabel(suspended ? "◌ SUSPENDED" : "● ACTIVE");
		stateLabel.setFont(FontManager.getRunescapeSmallFont());
		stateLabel.setForeground(accent);

		// Shares the EAST region with stateLabel via a tight, zero-gap
		// FlowLayout -- never a second full-width row.
		JPanel statusGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		statusGroup.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		statusGroup.add(reEvaluateButton());
		statusGroup.add(stateLabel);

		topRow.add(skillIdentity, BorderLayout.WEST);
		topRow.add(statusGroup, BorderLayout.EAST);

		String activityText = snapshot.getDisplayName() == null ? "" : snapshot.getDisplayName();
		String npcName = snapshot.getCurrentNpcName();
		if (npcName != null && !npcName.equalsIgnoreCase(activityText))
		{
			activityText = activityText.isEmpty() ? npcName : activityText + "  —  " + npcName;
		}
		JLabel activityLabel = new JLabel(activityText.isEmpty() ? "Unknown activity" : activityText);
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
		StringBuilder secondaryHtml = new StringBuilder("<html>")
			.append(colorSpan(durationText, SpyglassTheme.TEXT_SECONDARY));
		if (suspended)
		{
			secondaryHtml.append(colorSpan(" (frozen)", SpyglassTheme.TEXT_MUTED));
		}
		else
		{
			secondaryHtml.append(colorSpan(" elapsed", SpyglassTheme.TEXT_SECONDARY));
		}
		if (snapshot.getReliableCount() != null)
		{
			String label = snapshot.getReliableCount().getKind() == SessionAggregates.ReliableCountKind.KILLS ? "kills" : "completions";
			secondaryHtml.append(colorSpan("   •   " + snapshot.getReliableCount().getSessionOccurrences() + " " + label, SpyglassTheme.TEXT_SECONDARY));
		}
		secondaryHtml.append("</html>");
		JLabel secondaryLabel = new JLabel(secondaryHtml.toString());
		secondaryLabel.setFont(FontManager.getRunescapeSmallFont());
		secondaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		content.add(topRow);
		content.add(Box.createVerticalStrut(2));
		content.add(activityLabel);
		content.add(Box.createVerticalStrut(2));
		content.add(secondaryLabel);
		content.add(Box.createVerticalStrut(6));
		content.add(separator());
		content.add(Box.createVerticalStrut(6));
	}

	/**
	 * A tiny, flat, borderless refresh button -- deliberately styled like the panel's
	 * existing minimal tab buttons ({@code OsrsTelemetryPanel.tabButton()}:
	 * no border, small font) rather than a standard raised Swing button,
	 * so it reads as a compact RuneLite-native control rather than a
	 * generic desktop-app widget. {@code onReEvaluateRequested} is
	 * invoked directly and unconditionally on click -- see that field's
	 * own javadoc for the full non-blocking dispatch story; this method
	 * contributes no logic beyond wiring the click.
	 */
	private JButton reEvaluateButton()
	{
		JButton button = new JButton("↻");
		button.setToolTipText("Re-evaluate Session");
		// The preferred size below must fit this glyph once Swing's
		// default button UI applies its own internal margins --
		// BasicGraphicsUtils clips/truncates the label to "..." when it
		// doesn't. Still one of FontManager's own RuneLite fonts (just a
		// larger derived size), never custom art.
		button.setFont(FontManager.getRunescapeBoldFont().deriveFont(14f));
		button.setForeground(SpyglassTheme.TEXT_SECONDARY);
		button.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		button.setFocusPainted(false);
		button.setBorderPainted(false);
		button.setContentAreaFilled(false);
		button.setMargin(new java.awt.Insets(0, 2, 0, 2));
		Dimension size = new Dimension(22, 18);
		button.setPreferredSize(size);
		// Prevents FlowLayout from ever shrinking the button back below
		// what the glyph needs, which is what caused the clipping.
		button.setMinimumSize(size);
		button.addActionListener(e ->
		{
			if (onReEvaluateRequested != null)
			{
				onReEvaluateRequested.run();
			}
		});
		return button;
	}

	/**
	 * A single compact card showing either the numeric remaining count
	 * or the "Task complete / Grab a new task" state. Task NAME identity
	 * is not repeated here: it already appears on the header's activity
	 * line (renderHeader()), so this card is purely the live
	 * count/complete state -- no useless repetition. Omitted entirely
	 * whenever no SLAYER_TASK_PROGRESS carrying a currentRemaining has
	 * been observed this session.
	 *
	 * Uses the same subtle skill-tint card chrome as the skill cards
	 * below (Slayer's own muted neutral tone), for visual consistency
	 * with the rest of the panel.
	 */
	private void renderSlayerSection(CurrentSessionSnapshot snapshot)
	{
		if (snapshot.getSlayerCurrentRemaining() == null)
		{
			return;
		}

		JPanel card = cardPanel(skillTint(Skill.SLAYER.name()), false);
		card.setLayout(new BorderLayout(8, 0));

		card.add(new JLabel(skillIcon(Skill.SLAYER.name())), BorderLayout.WEST);

		if (snapshot.isSlayerTaskComplete())
		{
			JPanel textColumn = new JPanel();
			textColumn.setLayout(new BoxLayout(textColumn, BoxLayout.Y_AXIS));
			textColumn.setOpaque(false);

			JLabel title = new JLabel("Task complete");
			title.setFont(FontManager.getRunescapeBoldFont());
			title.setForeground(SpyglassTheme.STATUS_SUCCESS);
			title.setAlignmentX(Component.LEFT_ALIGNMENT);

			JLabel subtitle = new JLabel("Grab a new task");
			subtitle.setFont(FontManager.getRunescapeSmallFont());
			subtitle.setForeground(SpyglassTheme.TEXT_SECONDARY);
			subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

			textColumn.add(title);
			textColumn.add(subtitle);
			card.add(textColumn, BorderLayout.CENTER);
		}
		else
		{
			JPanel textColumn = new JPanel();
			textColumn.setLayout(new BoxLayout(textColumn, BoxLayout.Y_AXIS));
			textColumn.setOpaque(false);

			// "{remaining} remaining" is the primary figure; the
			// authoritative kill/task-unit count plus its
			// active-duration-only rate follow directly underneath --
			// reuses
			// CurrentSessionSnapshot#getSlayerProgressDelta()/#getSlayerKillsPerHour(),
			// the exact same exact-once-accumulated counter
			// SessionAggregateUpdater already maintains from each
			// SLAYER_TASK_PROGRESS event's own taskUnitsConsumed (never
			// NPC_DEATH, never a second counter -- see that field's own
			// javadoc). Omitted entirely until at least one kill has
			// actually been observed this session.
			//
			// Each label is its own left-aligned row in the
			// BoxLayout.Y_AXIS textColumn (the same pattern the
			// XP-remaining line below uses) so horizontal overlap between
			// the remaining-count and kills/rate text is structurally
			// impossible regardless of text length -- no absolute/fixed
			// positioning, ordinary layout-managed components only.
			JLabel remainingLabel = new JLabel(snapshot.getSlayerCurrentRemaining() + " remaining");
			remainingLabel.setFont(FontManager.getRunescapeBoldFont());
			remainingLabel.setForeground(SpyglassTheme.TEXT_PRIMARY);
			remainingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			textColumn.add(remainingLabel);

			Integer kills = snapshot.getSlayerProgressDelta();
			if (kills != null && kills > 0)
			{
				StringBuilder killsText = new StringBuilder()
					.append(kills)
					.append(kills == 1 ? " kill" : " kills");
				Long killsPerHour = snapshot.getSlayerKillsPerHour();
				if (killsPerHour != null)
				{
					killsText.append("   •   ").append(killsPerHour).append("/hr");
				}
				JLabel killsLabel = new JLabel(killsText.toString());
				killsLabel.setFont(FontManager.getRunescapeSmallFont());
				killsLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
				killsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
				textColumn.add(killsLabel);
			}

			// Compact muted "~X Slayer XP remaining" directly underneath
			// the remaining-count row -- see
			// CurrentSessionSnapshot#getEstimatedSlayerXpRemaining()
			// for the exact, session-observed-data-only calculation. Null
			// (nothing rendered) until there is a real average to
			// extrapolate from.
			Long estimatedSlayerXpRemaining = snapshot.getEstimatedSlayerXpRemaining();
			String xpRemainingEstimate = estimatedSlayerXpRemaining == null ? null
				: "~" + QuantityFormatter.quantityToStackSize(estimatedSlayerXpRemaining) + " Slayer XP remaining";
			if (xpRemainingEstimate != null)
			{
				JLabel xpRemainingLabel = new JLabel(xpRemainingEstimate);
				xpRemainingLabel.setFont(FontManager.getRunescapeSmallFont());
				xpRemainingLabel.setForeground(SpyglassTheme.TEXT_MUTED);
				xpRemainingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
				textColumn.add(xpRemainingLabel);
			}

			card.add(textColumn, BorderLayout.CENTER);
		}

		content.add(card);
		content.add(Box.createVerticalStrut(6));
	}

	/**
	 * One rectangular card per actively-gaining skill -- never a
	 * combined textual list. Cards stack in a single column
	 * (PluginPanel width is narrow; no forced two-column grid).
	 */
	private void renderSkillCards(CurrentSessionSnapshot snapshot)
	{
		if (snapshot.getXpEntries().isEmpty())
		{
			return;
		}

		String primarySkill = snapshot.getPrimarySkill();
		for (CurrentSessionSnapshot.XpEntry entry : snapshot.getXpEntries())
		{
			boolean isPrimary = primarySkill != null && primarySkill.equals(entry.getSkill());
			content.add(skillCard(entry, isPrimary));
			content.add(Box.createVerticalStrut(6));
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
			rateLabel.setFont(FontManager.getRunescapeSmallFont());
			rateLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
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
	 * Omitted entirely when there is no loot yet.
	 */
	private void renderLootSection(CurrentSessionSnapshot snapshot)
	{
		if (snapshot.getLootEntries().isEmpty())
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

		// "LOOT ×36" -- the same authoritative counter used for Slayer
		// (getSlayerProgressDelta()), generalized to also cover a
		// non-Slayer session's own reliable boss-kill counter
		// (getReliableCount()) so this header is not Slayer-only. Never a
		// second/new counter, and omitted entirely (no "x0") whenever
		// neither is available -- see
		// CurrentSessionSnapshot#getLootHeaderKillCount().
		Integer killCount = snapshot.getLootHeaderKillCount();
		JLabel titleLabel = new JLabel(killCount == null ? "LOOT" : "LOOT  ×" + killCount);
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

		JPanel grid = new JPanel(new GridLayout(0, LOOT_ITEMS_PER_ROW, 4, 4));
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));

		String modeLabel = mode == LootValuationMode.HIGH_ALCH ? "High Alch" : "Grand Exchange";
		for (LootPricing.ValuedLootRow row : rows)
		{
			grid.add(lootGridCell(row, modeLabel));
		}
		section.add(grid);

		content.add(section);
	}

	// Target density: 5 items per row.
	private static final int LOOT_ITEMS_PER_ROW = 5;

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
	 * render() -> renderLootSection(), driven by
	 * OsrsTelemetryPanel's javax.swing.Timer). Resolution is fully
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
