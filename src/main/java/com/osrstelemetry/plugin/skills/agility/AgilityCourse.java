package com.osrstelemetry.plugin.skills.agility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One Agility COURSE as declarative route data (no per-course code): which
 * obstacles make up a lap, where a lap starts and ends, which shortcut edges
 * are legitimate, and which course reward (if any) it hands out.
 *
 * <h3>Route model</h3>
 * A lap is an ordered ROUTE of {@link Node}s. A node is one obstacle
 * position on the route and may be cleared through any of several object ids
 * (alternates: Gnome's two pipes, Werewolf's three hurdle variants). The same
 * object id may appear in several nodes (Barbarian's three crumbling walls):
 * a success clears the FIRST not-yet-cleared node that accepts it. Nodes the
 * game grants no XP for ({@link Node#isXpless()}, Prifddinas' tree ladder) are
 * credited when a later node is cleared. SHORTCUT edges (Prifddinas' portals)
 * legitimately skip the next node: the skipped node is credited, never a
 * broken route. COUNT-ONLY courses (Agility Pyramid, Penguin) repeat the same
 * obstacle objects many times over several floors, so their progress is the
 * number of obstacles cleared this lap, with no fixed denominator (never a
 * made-up one).
 *
 * <h3>Start / completion</h3>
 * The lap starts at the START node (optionally only on one plane: the
 * Pyramid's stairs repeat on every floor). The lap completes when Agility XP
 * is gained on one of the course's END tiles (RuneLite's own native course
 * semantics: Courses#courseEndWorldPoints), or, for courses the game tracks
 * with a progress varbit (Colossal Wyrm), when that varbit reaches its
 * completion value.
 */
public final class AgilityCourse
{
	/** How the player thinks of the course (also: which native fall evidence applies). */
	public enum Kind
	{
		ROOFTOP,
		STANDARD,
		ADVANCED,
		SPECIAL
	}

	/** One obstacle position on the route. */
	public static final class Node
	{
		private final String label;
		private final Set<Integer> objectIds;
		private final boolean xpless;

		Node(String label, boolean xpless, int... objectIds)
		{
			this.label = Objects.requireNonNull(label, "label");
			this.xpless = xpless;
			Set<Integer> s = new LinkedHashSet<>();
			for (int id : objectIds)
			{
				s.add(id);
			}
			this.objectIds = Collections.unmodifiableSet(s);
		}

		public String getLabel()
		{
			return label;
		}

		public Set<Integer> getObjectIds()
		{
			return objectIds;
		}

		/** The game grants no XP for it: credited when a later node clears. */
		public boolean isXpless()
		{
			return xpless;
		}

		public boolean accepts(int objectId)
		{
			return objectIds.contains(objectId);
		}
	}

	/** An end tile (Agility XP gained here completes the lap). */
	public static final class Tile
	{
		private final int x;
		private final int y;
		private final int plane;

		Tile(int x, int y, int plane)
		{
			this.x = x;
			this.y = y;
			this.plane = plane;
		}

		public int getX()
		{
			return x;
		}

		public int getY()
		{
			return y;
		}

		public int getPlane()
		{
			return plane;
		}

		public boolean is(int px, int py, int pplane)
		{
			return x == px && y == py && plane == pplane;
		}

		/** The 64x64 region the tile is in. */
		public int regionId()
		{
			return ((x >> 6) << 8) | (y >> 6);
		}
	}

	/** The course's own reward (an item the course hands out), if any. */
	public static final class Reward
	{
		private final int itemId;
		private final String itemName;
		private final String shortName;
		private final String caption;
		private final String rateNoun;
		private final Set<Integer> triggerObjectIds;

		Reward(int itemId, String itemName, String shortName, String caption, String rateNoun, int... triggerObjectIds)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.shortName = shortName;
			this.caption = caption;
			this.rateNoun = rateNoun;
			Set<Integer> s = new LinkedHashSet<>();
			for (int id : triggerObjectIds)
			{
				s.add(id);
			}
			this.triggerObjectIds = Collections.unmodifiableSet(s);
		}

		public int getItemId()
		{
			return itemId;
		}

		public String getItemName()
		{
			return itemName;
		}

		public String getShortName()
		{
			return shortName;
		}

		/** REWARDS cell caption ("MARKS"). */
		public String getCaption()
		{
			return caption;
		}

		/** Rate noun ("marks" -> "MARKS / HR"). */
		public String getRateNoun()
		{
			return rateNoun;
		}

		/**
		 * Objects whose use CAUSES the reward (Prifddinas portal -> a crystal
		 * shard; Pyramid climbing rocks -> the pyramid top). Empty = the reward
		 * is picked up on the course itself (Marks of Grace).
		 */
		public Set<Integer> getTriggerObjectIds()
		{
			return triggerObjectIds;
		}
	}

	private final String key;
	private final String name;
	private final Kind kind;
	private final Set<Integer> regions;
	private final List<Tile> endTiles;
	private final List<Node> nodes;
	private final Integer startPlane;
	private final Set<Integer> shortcutObjectIds;
	private final Reward reward;
	private final boolean countOnly;
	private final Integer completionVarbit;
	private final int completionValue;
	private final boolean planeDropFalls;

	private AgilityCourse(Builder b)
	{
		this.key = b.key;
		this.name = b.name;
		this.kind = b.kind;
		this.regions = Collections.unmodifiableSet(new LinkedHashSet<>(b.regions));
		this.endTiles = Collections.unmodifiableList(new ArrayList<>(b.endTiles));
		this.nodes = Collections.unmodifiableList(new ArrayList<>(b.nodes));
		this.startPlane = b.startPlane;
		this.shortcutObjectIds = Collections.unmodifiableSet(new LinkedHashSet<>(b.shortcuts));
		this.reward = b.reward;
		this.countOnly = b.countOnly;
		this.completionVarbit = b.completionVarbit;
		this.completionValue = b.completionValue;
		this.planeDropFalls = b.planeDropFalls;
	}

	static Builder builder(String key, String name, Kind kind)
	{
		return new Builder(key, name, kind);
	}

	static final class Builder
	{
		private final String key;
		private final String name;
		private final Kind kind;
		private final List<Integer> regions = new ArrayList<>();
		private final List<Tile> endTiles = new ArrayList<>();
		private final List<Node> nodes = new ArrayList<>();
		private Integer startPlane;
		private final List<Integer> shortcuts = new ArrayList<>();
		private Reward reward;
		private boolean countOnly;
		private Integer completionVarbit;
		private int completionValue;
		private boolean planeDropFalls;

		private Builder(String key, String name, Kind kind)
		{
			this.key = key;
			this.name = name;
			this.kind = kind;
		}

		Builder regions(Integer... ids)
		{
			regions.addAll(Arrays.asList(ids));
			return this;
		}

		Builder end(int x, int y, int plane)
		{
			endTiles.add(new Tile(x, y, plane));
			return this;
		}

		Builder node(String label, int... objectIds)
		{
			nodes.add(new Node(label, false, objectIds));
			return this;
		}

		Builder xplessNode(String label, int... objectIds)
		{
			nodes.add(new Node(label, true, objectIds));
			return this;
		}

		Builder startPlane(int plane)
		{
			startPlane = plane;
			return this;
		}

		Builder shortcuts(int... objectIds)
		{
			for (int id : objectIds)
			{
				shortcuts.add(id);
			}
			return this;
		}

		Builder reward(Reward r)
		{
			reward = r;
			return this;
		}

		Builder countOnly()
		{
			countOnly = true;
			return this;
		}

		Builder completionVarbit(int varbit, int value)
		{
			completionVarbit = varbit;
			completionValue = value;
			return this;
		}

		Builder planeDropFalls()
		{
			planeDropFalls = true;
			return this;
		}

		AgilityCourse build()
		{
			return new AgilityCourse(this);
		}
	}

	public String getKey()
	{
		return key;
	}

	public String getName()
	{
		return name;
	}

	public Kind getKind()
	{
		return kind;
	}

	/** Regions the course runs through (empty = identified by its own objects only). */
	public Set<Integer> getRegions()
	{
		return regions;
	}

	public List<Tile> getEndTiles()
	{
		return endTiles;
	}

	public List<Node> getNodes()
	{
		return nodes;
	}

	public Node startNode()
	{
		return nodes.get(0);
	}

	/** Null = the start node counts on any plane. */
	public Integer getStartPlane()
	{
		return startPlane;
	}

	public Set<Integer> getShortcutObjectIds()
	{
		return shortcutObjectIds;
	}

	/** Null when the course hands out no course reward. */
	public Reward getReward()
	{
		return reward;
	}

	/** Progress is "obstacles cleared" with no fixed total (repeating floors). */
	public boolean isCountOnly()
	{
		return countOnly;
	}

	/** Null = completes on an end tile; otherwise this varbit reaching {@link #getCompletionValue()} completes the lap. */
	public Integer getCompletionVarbit()
	{
		return completionVarbit;
	}

	public int getCompletionValue()
	{
		return completionValue;
	}

	/**
	 * Rooftops: a fall drops the player from the roofs to the ground (plane
	 * 0) with damage; that pair is the only fall evidence used.
	 */
	public boolean isPlaneDropFalls()
	{
		return planeDropFalls;
	}

	/** Route length shown as the CURRENT LAP denominator; null for count-only courses. */
	public Integer denominator()
	{
		return countOnly ? null : nodes.size();
	}

	/** Every object id that is part of this course (nodes + shortcuts). */
	public Set<Integer> objectIds()
	{
		Set<Integer> all = new LinkedHashSet<>();
		for (Node n : nodes)
		{
			all.addAll(n.getObjectIds());
		}
		all.addAll(shortcutObjectIds);
		return all;
	}

	public boolean isEndTile(int x, int y, int plane)
	{
		for (Tile t : endTiles)
		{
			if (t.is(x, y, plane))
			{
				return true;
			}
		}
		return false;
	}

	/** Whether a region is part of the course (a course without declared regions accepts any). */
	public boolean inRegion(int regionId)
	{
		return regions.isEmpty() || regions.contains(regionId);
	}

	@Override
	public String toString()
	{
		return key;
	}
}
