package com.osrstelemetry.plugin.farming;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The one current action of a Farming run (canonical §28.13), from which every surface takes its own wording: the short
 * {@link #getLabel label} under the player, the fuller {@link #getGuide guide} line in the guide box and the NEXT card,
 * and a {@link #getTooltip tooltip} (destination, cost, requirements). Nothing on a surface works out an action itself, and
 * a short label is written for its place, never cut from the guide line. Immutable.
 */
public final class FarmingAction
{
	public enum Stage
	{
		/** Nothing to guide. */
		NONE,
		/** Use a teleport (an item, a spell, a house facility) to start the journey. */
		TELEPORT,
		/** Walk: to the patch, to a fairy ring, to a ladder, with the tiles still to go. */
		WALK,
		/** Take a door, ladder, boat, dive or other transition on the way. */
		TRANSITION,
		/** Use a fairy ring or spirit tree. */
		HOP,
		/** Act at the patch (the player is in reach of it). */
		ACT,
		/** Every patch is done or skipped. */
		COMPLETE
	}

	/** What the action is about in the world: highlighted for the player, resolved from the scene by identity. */
	public static final class Target
	{
		public enum Kind
		{
			NONE,
			/** The patch objects with these varbits. */
			PATCH,
			/** A scene object with one of these ids, or of this name with this action, near a tile. */
			OBJECT,
			/** An NPC of this name, or offering this action, nearest a tile (a gardener, a ferryman). */
			NPC
		}

		public static final Target NONE = new Target(Kind.NONE, Collections.emptySet(), null, null, -1, -1, -1, 0);

		/** {@link Kind#PATCH}: the farming regions the patches belong to (the same varbit means another patch elsewhere). */
		public final Set<Integer> regions;

		public final Kind kind;
		/** Object ids, or patch varbits for {@link Kind#PATCH}. */
		public final Set<Integer> ids;
		/** The object's or NPC's name (any case), or null. */
		public final String name;
		/** A menu action the object or NPC must offer (prefix, any case), or null. */
		public final String action;
		/** Near this world tile (x &lt; 0: anywhere in the scene, e.g. inside the house). */
		public final int x;
		public final int y;
		public final int plane;
		public final int radius;

		public Target(Kind kind, Set<Integer> ids, String name, String action, int x, int y, int plane, int radius)
		{
			this(kind, ids, Collections.emptySet(), name, action, x, y, plane, radius);
		}

		public Target(Kind kind, Set<Integer> ids, Set<Integer> regions, String name, String action, int x, int y, int plane, int radius)
		{
			this.regions = Collections.unmodifiableSet(new LinkedHashSet<>(regions));
			this.kind = kind;
			this.ids = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
			this.name = name;
			this.action = action;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.radius = radius;
		}

		public static Target patches(Set<Integer> varbits)
		{
			return new Target(Kind.PATCH, varbits, null, null, -1, -1, -1, 0);
		}

		/** The patch objects with these varbits in these regions (one patch, or the stop's patches). */
		public static Target patches(Set<Integer> varbits, Set<Integer> regions)
		{
			return new Target(Kind.PATCH, varbits, regions, null, null, -1, -1, -1, 0);
		}

		public static Target object(Set<Integer> ids, String name, String action, int x, int y, int plane, int radius)
		{
			return new Target(Kind.OBJECT, ids, name, action, x, y, plane, radius);
		}

		public static Target npc(String name, String action, int x, int y, int plane, int radius)
		{
			return new Target(Kind.NPC, Collections.emptySet(), name, action, x, y, plane, radius);
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Target))
			{
				return false;
			}
			Target t = (Target) o;
			return kind == t.kind && ids.equals(t.ids) && regions.equals(t.regions) && java.util.Objects.equals(name, t.name) && java.util.Objects.equals(action, t.action)
				&& x == t.x && y == t.y && plane == t.plane && radius == t.radius;
		}

		@Override
		public int hashCode()
		{
			return java.util.Objects.hash(kind, ids, regions, name, action, x, y, plane, radius);
		}
	}

	public static final FarmingAction NONE = new FarmingAction(Stage.NONE, null, null, null, Target.NONE, -1);

	private final Stage stage;
	private final String label;
	private final String guide;
	private final String tooltip;
	private final Target target;
	private final int tilesLeft;
	/** A spellbook widget to outline (Fertile Soil), or -1. */
	private int spellWidget = -1;

	public FarmingAction(Stage stage, String label, String guide, String tooltip, Target target, int tilesLeft)
	{
		this.stage = stage;
		this.label = label;
		this.guide = guide;
		this.tooltip = tooltip;
		this.target = target == null ? Target.NONE : target;
		this.tilesLeft = tilesLeft;
	}

	public Stage getStage()
	{
		return stage;
	}

	/** Under the player: short and immediately actionable ("Pay for protection", "Walk to patch · 42 tiles"). */
	public String getLabel()
	{
		return label;
	}

	/** The guide box and the NEXT card: the fuller instruction ("Pay the gardener to protect this crop (25 coconuts)"). */
	public String getGuide()
	{
		return guide;
	}

	/** Destination, cost or requirements, for a tooltip; null when there is nothing to add. */
	public String getTooltip()
	{
		return tooltip;
	}

	public Target getTarget()
	{
		return target;
	}

	/** Walking: the traversable tiles still to go (the countdown); -1 otherwise. */
	public int getTilesLeft()
	{
		return tilesLeft;
	}

	/** This action with a spell to outline in the spellbook. */
	public FarmingAction withSpell(int widgetId)
	{
		FarmingAction a = new FarmingAction(stage, label, guide, tooltip, target, tilesLeft);
		a.spellWidget = widgetId;
		return a;
	}

	public int getSpellWidget()
	{
		return spellWidget;
	}

	/** The same action with the walking countdown set (label and guide follow it). */
	public FarmingAction withTiles(int tiles)
	{
		if (stage != Stage.WALK || tiles == tilesLeft)
		{
			return this;
		}
		String base = label.contains(" · ") ? label.substring(0, label.indexOf(" · ")) : label;
		String g = guide.contains(" · ") ? guide.substring(0, guide.indexOf(" · ")) : guide;
		String count = tiles >= 0 ? " · " + tiles + (tiles == 1 ? " tile" : " tiles") : "";
		return new FarmingAction(stage, base + count, g + count, tooltip, target, tiles);
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof FarmingAction))
		{
			return false;
		}
		FarmingAction a = (FarmingAction) o;
		return stage == a.stage && java.util.Objects.equals(label, a.label) && java.util.Objects.equals(guide, a.guide)
			&& java.util.Objects.equals(tooltip, a.tooltip) && target.equals(a.target) && tilesLeft == a.tilesLeft && spellWidget == a.spellWidget;
	}

	@Override
	public int hashCode()
	{
		return java.util.Objects.hash(stage, label, guide, tooltip, target, tilesLeft);
	}

	@Override
	public String toString()
	{
		return stage + " [" + label + "] " + guide;
	}
}
