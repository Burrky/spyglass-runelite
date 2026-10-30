package com.osrstelemetry.plugin.hunter;

/**
 * The four bird house spaces on Fossil Island (Hunter). Each space's state is one player variable the game sends while the
 * player is on Fossil Island ({@code birdhouse_transmit_a..d}, varps 1626–1629): the space object ({@code birdhouse_1..4},
 * locs 30565–30568) is a multiloc driven by it. Names and the varp ↔ space mapping as RuneLite's Time Tracking plugin
 * ({@code BirdHouseSpace}, BSD-2); tiles from the cache's map data (mejrs data_osrs, the space objects' placements).
 *
 * <p>Two stops: the two Verdant Valley spaces stand 7 tiles apart and are one stop; the Mushroom Meadow spaces are 67 tiles
 * apart and are a stop each.
 */
public enum BirdhouseSpace
{
	MEADOW_NORTH("Mushroom Meadow (North)", "Meadow (N)", 1626, 30565, 3677, 3882, "meadow_north"),
	MEADOW_SOUTH("Mushroom Meadow (South)", "Meadow (S)", 1627, 30566, 3679, 3815, "meadow_south"),
	VALLEY_NORTH("Verdant Valley (Northeast)", "Valley (NE)", 1628, 30567, 3768, 3761, "verdant_valley"),
	VALLEY_SOUTH("Verdant Valley (Southwest)", "Valley (SW)", 1629, 30568, 3763, 3755, "verdant_valley");

	/** RuneLite's Fossil Island regions: the spaces' variables are read only here, on the ground floor (sent while here). */
	public static final int[] FOSSIL_ISLAND_REGIONS = {14650, 14651, 14652, 14906, 14907, 15162, 15163};

	private final String title;
	private final String shortTitle;
	private final int varp;
	private final int objectId;
	private final int x;
	private final int y;
	private final String stop;

	BirdhouseSpace(String title, String shortTitle, int varp, int objectId, int x, int y, String stop)
	{
		this.title = title;
		this.shortTitle = shortTitle;
		this.varp = varp;
		this.objectId = objectId;
		this.x = x;
		this.y = y;
		this.stop = stop;
	}

	public String getTitle()
	{
		return title;
	}

	public String getShortTitle()
	{
		return shortTitle;
	}

	public int getVarp()
	{
		return varp;
	}

	public int getObjectId()
	{
		return objectId;
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
		return 0;
	}

	/** The stop this space belongs to ("verdant_valley"): one route destination for spaces next to each other. */
	public String getStop()
	{
		return stop;
	}

	/** The route data key of this space's stop ({@code stop:birdhouse.<stop>}). */
	public String getStopKey()
	{
		return stopKey(stop);
	}

	public static String stopKey(String stop)
	{
		return "stop:birdhouse." + stop;
	}

	/** The stop's name as the player reads it. */
	public static String stopTitle(String stop)
	{
		switch (stop)
		{
			case "verdant_valley":
				return "Verdant Valley";
			case "meadow_north":
				return "Mushroom Meadow (North)";
			case "meadow_south":
				return "Mushroom Meadow (South)";
			default:
				return stop;
		}
	}

	/** The tile route searches aim for at a stop (the first space of it). */
	public static BirdhouseSpace anchorOf(String stop)
	{
		for (BirdhouseSpace s : values())
		{
			if (s.stop.equals(stop))
			{
				return s;
			}
		}
		return null;
	}

	public static boolean onFossilIsland(int regionId, int plane)
	{
		if (plane != 0)
		{
			return false;
		}
		for (int r : FOSSIL_ISLAND_REGIONS)
		{
			if (r == regionId)
			{
				return true;
			}
		}
		return false;
	}

	public static BirdhouseSpace parse(String name)
	{
		try
		{
			return name == null ? null : valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}
}
