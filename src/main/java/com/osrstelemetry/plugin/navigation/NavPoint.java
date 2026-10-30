package com.osrstelemetry.plugin.navigation;

/** A world tile packed into an int: x (15 bits), y (15 bits), plane (2 bits). */
public final class NavPoint
{
	private NavPoint()
	{
	}

	public static int pack(int x, int y, int plane)
	{
		return (x & 0x7FFF) | (y & 0x7FFF) << 15 | (plane & 3) << 30;
	}

	public static int x(int p)
	{
		return p & 0x7FFF;
	}

	public static int y(int p)
	{
		return p >>> 15 & 0x7FFF;
	}

	public static int plane(int p)
	{
		return p >>> 30 & 3;
	}

	/** Chebyshev distance on the same plane; Integer.MAX_VALUE across planes. */
	public static int distance(int a, int b)
	{
		if (plane(a) != plane(b))
		{
			return Integer.MAX_VALUE;
		}
		return Math.max(Math.abs(x(a) - x(b)), Math.abs(y(a) - y(b)));
	}

	public static String text(int p)
	{
		return x(p) + "," + y(p) + "," + plane(p);
	}
}
