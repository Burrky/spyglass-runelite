package com.osrstelemetry.plugin.ui;

import java.awt.Color;

/**
 * The single shared source of truth for the plugin's dark, warm-bronze
 * palette, used across all three tabs (Session / Loot / History), the
 * shared tab row, cards, headers, dividers, hover states, secondary
 * text, and highlighted totals.
 *
 * SCOPE: colors only, nothing else. This class has no reference to,
 * and is never referenced by, any session/history/loot data or
 * persistence type in this codebase -- it is safe to read, reuse, or
 * revert in isolation from everything else.
 *
 * WHY SHARED (not duplicated per-file): {@link OsrsTelemetryPanel}'s tab
 * row, {@link CurrentSessionView}, {@link LootTrackerView}, and
 * {@link HistoryView}/{@link HistoryDetailView} all need to visually
 * read as parts of the SAME plugin -- defining the palette once and
 * referencing it from all of them is what keeps that cohesive rather
 * than several independently-eyeballed dark palettes drifting apart
 * over time.
 *
 * SEMANTIC vs. LOCAL COLOR: this palette is the NEUTRAL chrome layer
 * (backgrounds, card faces, borders, ordinary text) that sits around
 * -- and deliberately never replaces -- colors that carry their own
 * meaning: {@code CurrentSessionView}'s per-skill tint table
 * ({@code SKILL_TINTS}), the suspended/active session-state accents,
 * the progress-bar red/yellow/green interpolation, and Loot's own gold
 * value color (mapped onto {@link #ACCENT_GOLD} here, since that is
 * exactly what it already was). Never override these semantic colors
 * with a chrome token from this class.
 */
final class SpyglassTheme
{
	private SpyglassTheme()
	{
	}

	// -- Backgrounds / surfaces --------------------------------------

	/** The plugin's overall background -- outer panel fill behind every tab. */
	static final Color BACKGROUND_MAIN = new Color(0x23, 0x21, 0x1E);
	/** Secondary/panel background -- the shared tab row, toolbars, control strips. */
	static final Color BACKGROUND_PANEL = new Color(0x2B, 0x27, 0x23);
	/** A single card's own face -- source cards, history rows, skill cards' neutral base. */
	static final Color SURFACE_CARD = new Color(0x3A, 0x30, 0x26);
	/** Raised/hover surface -- a card or control lifted slightly above {@link #SURFACE_CARD}. */
	static final Color SURFACE_RAISED = new Color(0x45, 0x37, 0x2B);

	// -- Borders / dividers -------------------------------------------

	/** Primary border -- card outlines, the active tab's border, strong separators. */
	static final Color BORDER_PRIMARY = new Color(0x6B, 0x52, 0x35);
	/** Soft border/divider -- inactive-tab borders, in-card separator lines. */
	static final Color BORDER_SOFT = new Color(0x54, 0x42, 0x33);

	// -- Text -----------------------------------------------------------

	/** Primary text -- activity names, major metrics, key headings. */
	static final Color TEXT_PRIMARY = new Color(0xE6, 0xD8, 0xB8);
	/** Secondary text -- timestamps, rates, labels. */
	static final Color TEXT_SECONDARY = new Color(0xB8, 0xAA, 0x8D);
	/** Muted text -- unavailable states, subtle metadata, helper text. */
	static final Color TEXT_MUTED = new Color(0x8E, 0x84, 0x6F);

	// -- Accents (restrained -- see class javadoc's SEMANTIC vs. LOCAL note) --

	/** Important totals, key section headings, highlighted values -- an accent, never the default text color. */
	static final Color ACCENT_GOLD = new Color(0xD4, 0xA9, 0x3A);
	/** A quieter warm accent for secondary emphasis -- distinct from gold, used where a second accent tone avoids "everything is gold." */
	static final Color ACCENT_BRONZE = new Color(0xB8, 0x8A, 0x2C);
	/** Success/positive state -- e.g. a completed Slayer task, a maxed skill. */
	static final Color STATUS_SUCCESS = new Color(0x70, 0xB9, 0x6E);
	/** Suspended/paused/warm-warning state -- deliberately not red; "paused," not "broken." */
	static final Color STATUS_SUSPENDED = new Color(0xC9, 0x96, 0x52);
}
