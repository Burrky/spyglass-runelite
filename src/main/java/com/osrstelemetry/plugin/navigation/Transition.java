package com.osrstelemetry.plugin.navigation;

/**
 * One move that ordinary walking can't make (canonical §28.10): a door or gate, stairs, a ladder, an agility shortcut, a
 * boat, a quetzal flight. From a tile to a tile (planes may differ), with what it needs, how long it takes and how the game
 * names it ({@code Open Door}, {@code Climb-up Ladder}). Arrival is observed by position: the player standing at (or next to)
 * {@link #to} on its plane; a click or an animation never counts.
 */
public final class Transition
{
	public enum Family
	{
		/** Doors, gates, stairs, ladders, caves, passages. */
		PASSAGE,
		AGILITY_SHORTCUT,
		BOAT,
		SHIP,
		QUETZAL,
		MAGIC_MUSHTREE,
		/** The hot air balloons (Enlightened Journey): a basket at each landing, one log of the destination's kind a flight. */
		BALLOON
	}

	public final int from;
	public final int to;
	public final Family family;
	/** The menu option ("Open", "Climb-up", "Travel"). */
	public final String option;
	/** The menu target ("Door", "Ladder", "Monk of Entrana"). */
	public final String target;
	public final int objectId;
	public final NavRequirement requirement;
	/** Game ticks. */
	public final int duration;
	/** Where it goes, as the data names it ("Kastori"), or null. */
	public final String info;

	/** Where arrival counts (x1, y1, x2, y2, plane), when the landing tile isn't exact; null: within {@link #ARRIVAL_SLACK} of {@link #to}. */
	public final int[] arrivalArea;

	/** Arrival at a transition's tile is observed within this many tiles of it, on its plane. */
	public static final int ARRIVAL_SLACK = 4;

	Transition(int from, int to, Family family, String option, String target, int objectId, NavRequirement requirement, int duration, String info)
	{
		this(from, to, family, option, target, objectId, requirement, duration, info, null);
	}

	Transition(int from, int to, Family family, String option, String target, int objectId, NavRequirement requirement, int duration, String info,
		int[] arrivalArea)
	{
		this.arrivalArea = arrivalArea;
		this.from = from;
		this.to = to;
		this.family = family;
		this.option = option;
		this.target = target;
		this.objectId = objectId;
		this.requirement = requirement;
		this.duration = duration;
		this.info = info;
	}

	/** Whether a player at {@code p} has arrived through this transition (observed position; never a click). */
	public boolean arrivedAt(int p)
	{
		if (arrivalArea != null)
		{
			return NavPoint.plane(p) == arrivalArea[4] && NavPoint.x(p) >= arrivalArea[0] && NavPoint.x(p) <= arrivalArea[2]
				&& NavPoint.y(p) >= arrivalArea[1] && NavPoint.y(p) <= arrivalArea[3];
		}
		int dTo = NavPoint.distance(p, to);
		// Same plane (a door, a gate, a shortcut): nearer the far side than the near side, so standing before it never counts.
		return dTo <= ARRIVAL_SLACK && (NavPoint.plane(from) != NavPoint.plane(to) || dTo < NavPoint.distance(p, from));
	}

	/** Walking-tile equivalent: running covers two tiles a tick. */
	public int cost()
	{
		return Math.max(2, duration * 2);
	}

	private static final java.util.Set<String> VERBS = new java.util.HashSet<>(java.util.Arrays.asList("travel", "take-boat", "board",
		"dive", "use", "ride", "port", "quick-travel"));

	/**
	 * The instruction, worded from the game's menu: "Open the door", "Climb up the ladder", "Follow Elkoy", "Take the achilka
	 * to Kastori", "Travel with Monk of Entrana to Entrana", "Dive from the rowboat".
	 */
	public String instruction()
	{
		String t = target == null ? "" : target.trim();
		String opt = option == null ? "" : option.trim();
		if (requirement.getRestrictions().contains(NavRequirement.ENTRANA))
		{
			return "Travel with " + (t.isEmpty() ? "the monks" : t) + " to Entrana (no weapons or armour)";
		}
		if (family == Family.BALLOON)
		{
			String fuel = balloonFuel();
			return "Fly the balloon to " + (info == null ? "the next landing" : info) + (fuel == null ? "" : " (" + fuel + "; at most "
				+ NavRequirement.BALLOON_MAX_KG + " kg carried)");
		}
		if (family == Family.MAGIC_MUSHTREE)
		{
			// "4. Mushroom Meadow" is the mushtree menu's own line (Shortest Path's display text): its number is the option.
			String[] d = mushtreeDestination();
			return d == null ? "Take the magic mushtree" : "Take the magic mushtree to " + d[1] + (d[0] == null ? "" : " (option " + d[0] + ")");
		}
		boolean vehicle = family == Family.BOAT || family == Family.SHIP || family == Family.QUETZAL || family == Family.MAGIC_MUSHTREE;
		if (vehicle)
		{
			if ("Dive".equalsIgnoreCase(opt))
			{
				return "Dive from the " + (t.isEmpty() ? "boat" : t.toLowerCase(java.util.Locale.ROOT));
			}
			String where = info != null ? info : !opt.isEmpty() && !VERBS.contains(opt.toLowerCase(java.util.Locale.ROOT)) ? opt : null;
			String who = t.isEmpty() ? "the boat" : person(t) ? "with " + t : "the " + t.toLowerCase(java.util.Locale.ROOT);
			String verb = who.startsWith("with ") ? "Travel " : "Take ";
			return verb + who + (where == null ? "" : " to " + where);
		}
		String[] place = menuPlace();
		if (place != null && (person(t) || "Travel".equalsIgnoreCase(opt)) && !t.isEmpty())
		{
			// A guide or ferryman with a destination menu: "1: Mount Quidamortem." (the table's own display text).
			return "Travel with " + t + " to " + place[1] + (place[0] == null ? "" : " (option " + place[0] + ")");
		}
		String verb = opt.isEmpty() ? "Use" : Character.toUpperCase(opt.charAt(0)) + opt.substring(1).replace('-', ' ').toLowerCase(java.util.Locale.ROOT);
		if (t.isEmpty())
		{
			return verb;
		}
		boolean npc = opt.equalsIgnoreCase("Follow") || opt.equalsIgnoreCase("Talk-to") || opt.equalsIgnoreCase("Travel") || person(t);
		return verb + " " + (npc ? t : "the " + t.toLowerCase(java.util.Locale.ROOT));
	}

	/**
	 * The short form drawn under the player (canonical §28.13), written for that place rather than cut from
	 * {@link #instruction()}: "Climb up ladder", "Open door", "Captain Barnaby → Rimmington", "Dive".
	 */
	public String label()
	{
		String t = target == null ? "" : target.trim();
		String opt = option == null ? "" : option.trim();
		if (requirement.getRestrictions().contains(NavRequirement.ENTRANA))
		{
			return (t.isEmpty() ? "Monks" : t) + " → Entrana";
		}
		if (family == Family.BALLOON)
		{
			return "Balloon → " + (info == null ? "landing" : info);
		}
		if (family == Family.MAGIC_MUSHTREE)
		{
			String[] d = mushtreeDestination();
			return "Mushtree → " + (d == null ? "landing" : d[1]);
		}
		boolean vehicle = family == Family.BOAT || family == Family.SHIP || family == Family.QUETZAL || family == Family.MAGIC_MUSHTREE;
		if (vehicle)
		{
			if ("Dive".equalsIgnoreCase(opt))
			{
				return "Dive";
			}
			String where = info != null ? info : !opt.isEmpty() && !VERBS.contains(opt.toLowerCase(java.util.Locale.ROOT)) ? opt : null;
			String who = t.isEmpty() ? "Boat" : person(t) ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1).toLowerCase(java.util.Locale.ROOT);
			return who + (where == null ? "" : " → " + where);
		}
		String[] place = menuPlace();
		if (place != null && (person(t) || "Travel".equalsIgnoreCase(opt)) && !t.isEmpty())
		{
			return t + " → " + place[1];
		}
		String verb = opt.isEmpty() ? "Use" : Character.toUpperCase(opt.charAt(0)) + opt.substring(1).replace('-', ' ').toLowerCase(java.util.Locale.ROOT);
		return t.isEmpty() ? verb : verb + " " + noun();
	}

	/** A magic mushtree's destination from its menu line ("4. Mushroom Meadow"): {option number or null, place}, or null. */
	String[] mushtreeDestination()
	{
		if (info == null || family != Family.MAGIC_MUSHTREE)
		{
			return null;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:(\\d+)\\.\\s*)?(.+?)\\.?$").matcher(info.trim());
		return m.find() ? new String[]{m.group(1), m.group(2)} : null;
	}

	/** A walking-table transport's destination from its display text: {menu number or null, place}, or null. */
	String[] menuPlace()
	{
		if (info == null || family != Family.PASSAGE)
		{
			return null;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:(\\d+):\\s*)?(.+?)\\.?$").matcher(info.trim());
		return m.find() ? new String[]{m.group(1), m.group(2)} : null;
	}

	/** The log a balloon flight burns, in words ("1 willow log"), from the flight's own item requirement; null for others. */
	public String balloonFuel()
	{
		if (family != Family.BALLOON)
		{
			return null;
		}
		for (NavRequirement.ItemNeed n : requirement.getItems())
		{
			if (n.optionIds.size() == 1 && n.optionIds.get(0).length == 1)
			{
				String name = LOGS.get(n.optionIds.get(0)[0]);
				if (name != null)
				{
					return n.optionQuantities.get(0) + " " + name + (n.optionQuantities.get(0) == 1 ? "" : "s");
				}
			}
		}
		return null;
	}

	/** Balloon fuel by item id (OSRS Wiki "Balloon transport system", 2026-10-22; ids from the flight rows). */
	public static final java.util.Map<Integer, String> LOGS = new java.util.HashMap<>();

	static
	{
		LOGS.put(1511, "log");
		LOGS.put(1521, "oak log");
		LOGS.put(1519, "willow log");
		LOGS.put(1515, "yew log");
		LOGS.put(1513, "magic log");
	}

	/** What the player walks to before this step: "ladder", "door", "Captain Barnaby" (a person keeps their name). */
	public String noun()
	{
		if (family == Family.BALLOON)
		{
			return "balloon";
		}
		String t = target == null ? "" : target.trim();
		if (t.isEmpty())
		{
			return "the next step";
		}
		return person(t) ? t : t.toLowerCase(java.util.Locale.ROOT);
	}

	/** "Monk of Entrana", "Captain Barnaby": a named person rather than an object. */
	private static boolean person(String t)
	{
		String[] w = t.split(" ");
		if (w.length < 2)
		{
			return false;
		}
		int caps = 0;
		for (String x : w)
		{
			caps += !x.isEmpty() && Character.isUpperCase(x.charAt(0)) ? 1 : 0;
		}
		return caps >= 2;
	}

	@Override
	public String toString()
	{
		return instruction() + " " + NavPoint.text(from) + " -> " + NavPoint.text(to) + (requirement.isNone() ? "" : " [" + requirement + "]");
	}
}
