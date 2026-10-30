package com.osrstelemetry.plugin.farming;

import java.util.Objects;

/** An immutable world position (x, y, plane) as the observer sees it; map region derived the way the game does. */
public final class FarmingLocation
{
	private final int x;
	private final int y;
	private final int plane;

	public FarmingLocation(int x, int y, int plane)
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

	/** {@code ((x >> 6) << 8) | (y >> 6)}, as {@code WorldPoint.getRegionID()}. */
	public int getRegionId()
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof FarmingLocation))
		{
			return false;
		}
		FarmingLocation l = (FarmingLocation) o;
		return x == l.x && y == l.y && plane == l.plane;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(x, y, plane);
	}

	@Override
	public String toString()
	{
		return "(" + x + "," + y + "," + plane + ")";
	}
}
