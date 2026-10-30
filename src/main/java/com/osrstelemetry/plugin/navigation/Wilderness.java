package com.osrstelemetry.plugin.navigation;

/**
 * The Wilderness bounds (canonical §28.10), from the Shortest Path plugin's WildernessChecker (BSD-2; see
 * THIRD_PARTY_NOTICES.md): above ground x 2944–3391, y 3525–3972 except Ferox Enclave and the strips south of the ditch,
 * and the Wilderness dungeons x 2944–3461, y 9918–10375. Farm-run routes never enter it (a player standing in it may walk
 * out).
 */
public final class Wilderness
{
	private Wilderness()
	{
	}

	private static boolean in(int x, int y, int ax, int ay, int w, int h)
	{
		return x >= ax && x < ax + w && y >= ay && y < ay + h;
	}

	public static boolean contains(int p)
	{
		int x = NavPoint.x(p);
		int y = NavPoint.y(p);
		if (in(x, y, 2944, 9918, 518, 458))
		{
			return true;
		}
		return in(x, y, 2944, 3525, 448, 448)
			&& !in(x, y, 3123, 3622, 2, 10) && !in(x, y, 3125, 3617, 16, 23) && !in(x, y, 3138, 3636, 18, 10) && !in(x, y, 3141, 3625, 14, 11)
			&& !in(x, y, 3141, 3619, 7, 6)
			&& !in(x, y, 2997, 3525, 34, 9) && !in(x, y, 3005, 3534, 21, 10) && !in(x, y, 3000, 3534, 5, 5) && !in(x, y, 3031, 3525, 2, 2);
	}
}
