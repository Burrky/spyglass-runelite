package com.osrstelemetry.plugin.ui;

import java.awt.Color;

/**
 * The single shared source of truth for the plugin's dark, warm-bronze
 * palette, used across all three tabs (Session / Loot / History), the
 * shared tab row, cards, headers, dividers, hover states, secondary
 * text, and highlighted totals.
 *
 * TEXT ROLES (pass 30): the text and accent colours are roles, not fixed values: {@link #apply} sets them for the
 * player's contrast (Standard, already readable on every surface: supporting text ≥ 6:1, quiet labels ≥ 5:1, muted
 * metadata ≥ 4.5:1 on a tile face; or High contrast). Surfaces stay as they are. Code reads the roles when it paints or
 * builds, so a change reaches new and rebuilt components; the panel re-colours existing ones.
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
	// -- Raised Tiles (Home family): a recessed canvas with warmer, lighter tiles floating on it.
	// All opaque and pre-blended against TILE_CANVAS: no alpha per paint.

	/** The recessed page canvas the tiles float above (darker than {@link #BACKGROUND_MAIN}). */
	static final Color TILE_CANVAS = new Color(0x19, 0x16, 0x12);
	/** A tile's face at rest: warm, a clear step lighter than the canvas. */
	static final Color TILE_FACE = new Color(0x46, 0x3A, 0x2C);
	/** The face while hovered (interactive tiles only). */
	static final Color TILE_FACE_HOVER = new Color(0x51, 0x43, 0x32);
	/** The face while pressed: seated lower and darker. */
	static final Color TILE_FACE_PRESSED = new Color(0x3B, 0x31, 0x25);
	/** The fine dark rim that gives a tile its silhouette against the canvas. */
	static final Color TILE_RIM = new Color(0x12, 0x0F, 0x0C);
	/** 1 px lit upper edge just inside the rim. */
	static final Color TILE_EDGE_HIGHLIGHT = new Color(0x6A, 0x58, 0x41);
	/** The lit edge while hovered. */
	static final Color TILE_EDGE_HIGHLIGHT_HOVER = new Color(0x7A, 0x66, 0x4B);
	/** 1 px shade along the inside of the lower edge. */
	static final Color TILE_EDGE_SHADE = new Color(0x36, 0x2C, 0x21);
	/** Inner ring of the stepped shadow, below and right. */
	static final Color TILE_SHADOW_NEAR = new Color(0x0B, 0x09, 0x07);
	/** Outer ring of the stepped shadow. */
	static final Color TILE_SHADOW_FAR = new Color(0x11, 0x0F, 0x0C);
	/** Quiet supporting text on a tile face (lighter than {@link #TEXT_MUTED}, which is too dim on a face). */
	static Color TILE_TEXT_QUIET = new Color(0xBC, 0xAF, 0x92);

	// -- Fixed navigation -------------------------------------------------

	/** The fixed header band behind the nav row. */
	static final Color NAV_BAND = new Color(0x2B, 0x25, 0x1F);
	/** A nav control at rest. */
	static final Color NAV_FACE = new Color(0x35, 0x2D, 0x24);
	/** A nav control while hovered. */
	static final Color NAV_FACE_HOVER = new Color(0x41, 0x36, 0x2A);
	/** A nav control while pressed. */
	static final Color NAV_FACE_PRESSED = new Color(0x26, 0x20, 0x1A);
	/** The selected section's control: raised, the warmest face in the header. */
	static final Color NAV_FACE_SELECTED = new Color(0x4D, 0x3F, 0x2E);
	/** The child sub-header strip under the nav row. */
	static final Color NAV_SUBHEADER = new Color(0x22, 0x1D, 0x18);

	// -- Borders / dividers -------------------------------------------

	/** Primary border -- card outlines, the active tab's border, strong separators. */
	static final Color BORDER_PRIMARY = new Color(0x6B, 0x52, 0x35);
	/** Soft border/divider -- inactive-tab borders, in-card separator lines. */
	static final Color BORDER_SOFT = new Color(0x54, 0x42, 0x33);

	// -- Text -----------------------------------------------------------

	/** Primary text -- activity names, major metrics, key headings. */
	static Color TEXT_PRIMARY = new Color(0xE6, 0xD8, 0xB8);
	/** Secondary text -- timestamps, rates, labels. */
	static Color TEXT_SECONDARY = new Color(0xCA, 0xBE, 0xA2);
	/** Muted text -- unavailable states, subtle metadata, helper text. */
	static Color TEXT_MUTED = new Color(0xB3, 0xA7, 0x8E);

	// -- Accents (restrained -- see class javadoc's SEMANTIC vs. LOCAL note) --

	/** Important totals, key section headings, highlighted values -- an accent, never the default text color. */
	static Color ACCENT_GOLD = new Color(0xD4, 0xA9, 0x3A);
	/** A quieter warm accent for secondary emphasis -- distinct from gold, used where a second accent tone avoids "everything is gold." */
	static Color ACCENT_BRONZE = new Color(0xC9, 0x9A, 0x3C);
	/** Success/positive state -- e.g. a completed Slayer task, a maxed skill. */
	static Color STATUS_SUCCESS = new Color(0x70, 0xB9, 0x6E);
	/** Suspended/paused/warm-warning state -- deliberately not red; "paused," not "broken." */
	static Color STATUS_SUSPENDED = new Color(0xC9, 0x96, 0x52);

	// -- Activity meters (thin in-card bars mirroring a game's own HUD) --

	/** Unfilled track of an activity meter. */
	static final Color METER_TRACK = BACKGROUND_PANEL;
	/** A resource the player wants high (Tempoross energy). */
	static Color METER_PRIMARY = ACCENT_GOLD;
	/** A resource the player spends / refills (Tempoross essence). */
	static Color METER_SECONDARY = STATUS_SUCCESS;
	/** A pressure the player wants low (Tempoross storm intensity): a muted ember, not an alarm red. */
	static Color METER_DANGER = new Color(0xB8, 0x5C, 0x48);

	// Smart Slayer semantic accents -- deliberately muted, never card fills.
	/** Preservation (a kill that did not use up a task unit: Bracelet of slaughter). */
	static Color ACCENT_SAGE = new Color(0x93, 0xAE, 0x7C);
	/** Acceleration / upcoming milestone (Expeditious bracelet, streak bonus context). */
	static Color ACCENT_AMBER = new Color(0xC7, 0x9A, 0x5B);
	/** A warning that will cost the player something (a skill companion's DANGER line): red, used sparingly. */
	static Color STATUS_DANGER = new Color(0xE0, 0x5A, 0x48);
	/** Farming attention (diseased / dead): a warm ember, readable on the tile faces. */
	static Color FARM_EMBER = new Color(0xE8, 0x86, 0x6A);

	/** A mode that is on (Farming's "Combining run types"): a deep gold face the cream text reads on (pass 32). */
	static final Color MODE_ON_FACE = new Color(0x6B, 0x52, 0x22);

	/** A tracked (selected) activity card's face: a muted green the cream text reads on (pass 31). */
	static final Color CARD_SELECTED = new Color(0x33, 0x40, 0x2B);
	static final Color CARD_SELECTED_HOVER = new Color(0x3C, 0x4A, 0x32);
	/** An untracked activity card's face: a muted red, quiet but distinct from the selected green. */
	static final Color CARD_UNSELECTED = new Color(0x42, 0x2C, 0x27);
	static final Color CARD_UNSELECTED_HOVER = new Color(0x4C, 0x33, 0x2D);

	/** A role's value for each contrast: {standard, high}. */
	private static final int[][] ROLES = {
		// TEXT_PRIMARY, TEXT_SECONDARY, TILE_TEXT_QUIET, TEXT_MUTED
		{0xE6D8B8, 0xFAF1DC}, {0xCABEA2, 0xE8DEC6}, {0xBCAF92, 0xDDD2B8}, {0xB3A78E, 0xD2C7AD},
		// ACCENT_GOLD, ACCENT_BRONZE, STATUS_SUCCESS, STATUS_SUSPENDED, ACCENT_SAGE, ACCENT_AMBER, STATUS_DANGER, FARM_EMBER, METER_DANGER
		{0xD4A93A, 0xEDC456}, {0xC99A3C, 0xDDAE52}, {0x70B96E, 0x8FDA8C}, {0xC99652, 0xE2B072}, {0x93AE7C, 0xB0D29A}, {0xC79A5B, 0xE6BC86},
		{0xE05A48, 0xF57F6C}, {0xE8866A, 0xF5A285}, {0xB85C48, 0xD57A64},
	};

	private static boolean high;

	/** Sets every text and accent role for the contrast (EDT). Returns true when anything changed. */
	static boolean apply(boolean highContrast)
	{
		if (highContrast == high)
		{
			return false;
		}
		high = highContrast;
		int i = highContrast ? 1 : 0;
		TEXT_PRIMARY = new Color(ROLES[0][i]);
		TEXT_SECONDARY = new Color(ROLES[1][i]);
		TILE_TEXT_QUIET = new Color(ROLES[2][i]);
		TEXT_MUTED = new Color(ROLES[3][i]);
		ACCENT_GOLD = new Color(ROLES[4][i]);
		ACCENT_BRONZE = new Color(ROLES[5][i]);
		STATUS_SUCCESS = new Color(ROLES[6][i]);
		STATUS_SUSPENDED = new Color(ROLES[7][i]);
		ACCENT_SAGE = new Color(ROLES[8][i]);
		ACCENT_AMBER = new Color(ROLES[9][i]);
		STATUS_DANGER = new Color(ROLES[10][i]);
		FARM_EMBER = new Color(ROLES[11][i]);
		METER_DANGER = new Color(ROLES[12][i]);
		METER_PRIMARY = ACCENT_GOLD;
		METER_SECONDARY = STATUS_SUCCESS;
		return true;
	}

	static boolean isHighContrast()
	{
		return high;
	}

	/** Every role's current value, in a fixed order (re-colouring existing components maps old values to new). */
	static Color[] roles()
	{
		return new Color[]{TEXT_PRIMARY, TEXT_SECONDARY, TILE_TEXT_QUIET, TEXT_MUTED, ACCENT_GOLD, ACCENT_BRONZE, STATUS_SUCCESS, STATUS_SUSPENDED,
			ACCENT_SAGE, ACCENT_AMBER, STATUS_DANGER, FARM_EMBER, METER_DANGER};
	}
}
