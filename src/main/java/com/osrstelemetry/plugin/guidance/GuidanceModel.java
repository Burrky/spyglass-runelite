package com.osrstelemetry.plugin.guidance;

import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * What the guidance overlays draw (canonical §9.3): an immutable snapshot
 * published on the client thread and read on the render thread. Overlays
 * never compute, read the run or decide anything; they draw this.
 */
public final class GuidanceModel
{
	public enum State
	{
		/** No active run (or logged out, or ended): nothing is drawn. */
		OFF,
		/** Bank open, loading or hopping: nothing is drawn until it clears. */
		PAUSED,
		ACTIVE
	}

	public static final GuidanceModel OFF = new GuidanceModel(State.OFF, null, Collections.emptyList(), null, null, Collections.emptyList(), new int[0],
		null);

	private final State state;
	private final String title;
	private final List<String> lines;
	private final String progress;
	private final WorldPoint target;
	private final List<WorldPoint> path;
	private final int[] outline;
	private final String kind;
	/** Under the player: the current action's short label (written for that place, never cut from a sentence). */
	private final String playerLabel;
	/** The guide box and the NEXT card: the current action's fuller instruction. */
	private final String guide;
	/** Destination, cost or requirements of the current action, or null. */
	private final String tooltip;
	/** What the current action is about, resolved in the scene (drawn in the path's blue); empty when none is loaded. */
	private final List<net.runelite.api.TileObject> objects;
	private final List<net.runelite.api.NPC> npcs;
	/** The current patch's footprints (world x1, y1, x2, y2, plane): one ground outline per patch, never per plant model. */
	private List<int[]> footprints = Collections.emptyList();
	/** At the patch: the outline is kept faint so the crop stays visible. */
	private boolean quiet;
	/** A spellbook widget to outline (Fertile Soil), or -1. */
	private int spellWidget = -1;

	/** This model with a spell to outline while the spellbook shows it. */
	public GuidanceModel withSpell(int widgetId)
	{
		GuidanceModel m = copyExtras(new GuidanceModel(state, title, lines, progress, target, path, outline, kind, playerLabel, guide, tooltip, objects, npcs));
		m.spellWidget = widgetId;
		return m;
	}

	public int getSpellWidget()
	{
		return spellWidget;
	}

	private GuidanceModel copyExtras(GuidanceModel m)
	{
		m.footprints = footprints;
		m.quiet = quiet;
		m.spellWidget = spellWidget;
		return m;
	}

	public GuidanceModel(State state, String title, List<String> lines, String progress, WorldPoint target, List<WorldPoint> path, int[] outline,
		String kind)
	{
		this(state, title, lines, progress, target, path, outline, kind, null, null, null, Collections.emptyList(), Collections.emptyList());
	}

	private GuidanceModel(State state, String title, List<String> lines, String progress, WorldPoint target, List<WorldPoint> path, int[] outline,
		String kind, String playerLabel, String guide, String tooltip, List<net.runelite.api.TileObject> objects, List<net.runelite.api.NPC> npcs)
	{
		this.state = state;
		this.title = title;
		this.lines = Collections.unmodifiableList(lines);
		this.progress = progress;
		this.target = target;
		this.path = Collections.unmodifiableList(path);
		this.outline = outline == null ? new int[0] : outline.clone();
		this.kind = kind;
		this.playerLabel = state == State.ACTIVE ? playerLabel : null;
		this.guide = guide;
		this.tooltip = tooltip;
		this.objects = Collections.unmodifiableList(new java.util.ArrayList<>(objects));
		this.npcs = Collections.unmodifiableList(new java.util.ArrayList<>(npcs));
	}

	/** This model with the one current action's wording (label under the player, guide line, tooltip). */
	public GuidanceModel withAction(String label, String guide, String tooltip)
	{
		return copyExtras(new GuidanceModel(state, title, lines, progress, target, path, outline, kind, label, guide, tooltip, objects, npcs));
	}

	/** This model with the current action's targets in the scene. */
	public GuidanceModel withHighlights(List<net.runelite.api.TileObject> objects, List<net.runelite.api.NPC> npcs)
	{
		return copyExtras(new GuidanceModel(state, title, lines, progress, target, path, outline, kind, playerLabel, guide, tooltip,
			objects == null ? Collections.emptyList() : objects, npcs == null ? Collections.emptyList() : npcs));
	}

	/** This model with the patch's ground footprints ({@code quiet}: the player is at it). */
	public GuidanceModel withFootprints(List<int[]> f, boolean quiet)
	{
		GuidanceModel m = copyExtras(new GuidanceModel(state, title, lines, progress, target, path, outline, kind, playerLabel, guide, tooltip, objects, npcs));
		m.footprints = f == null ? Collections.emptyList() : Collections.unmodifiableList(new java.util.ArrayList<>(f));
		m.quiet = quiet;
		return m;
	}

	public List<int[]> getFootprints()
	{
		return footprints;
	}

	public boolean isQuiet()
	{
		return quiet;
	}

	/** The short instruction under the player, or null. */
	public String getPlayerLabel()
	{
		return playerLabel;
	}

	/** The current instruction for the guide box and the NEXT card, or null. */
	public String getGuide()
	{
		return guide;
	}

	/** Destination, cost or requirements of the current action, or null. */
	public String getTooltip()
	{
		return tooltip;
	}

	public List<net.runelite.api.TileObject> getObjects()
	{
		return objects;
	}

	public List<net.runelite.api.NPC> getNpcs()
	{
		return npcs;
	}

	public State getState()
	{
		return state;
	}

	public boolean isActive()
	{
		return state == State.ACTIVE;
	}

	/** Location and patch type ("Catherby · Herb"), or a bank's name. */
	public String getTitle()
	{
		return title;
	}

	/** The whole stop: the route still to go and every patch's state (an optional expansion; never shown by default). */
	public List<String> getLines()
	{
		return lines;
	}

	/** "3 / 9 patches · 17m". */
	public String getProgress()
	{
		return progress;
	}

	/** Where the objective is (approximate for banks and farming markers); null when none. */
	public WorldPoint getTarget()
	{
		return target;
	}

	/** Walking path tiles from the player (loaded scene only; empty when not walking). */
	public List<WorldPoint> getPath()
	{
		return path;
	}

	/** Item ids to outline in the inventory and equipment (the suggested teleport). */
	public int[] getOutline()
	{
		return outline.clone();
	}

	/** "TRAVEL", "ACT", "BANK", "COMPLETE". */
	public String getKind()
	{
		return kind;
	}
}
