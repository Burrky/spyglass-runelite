package com.osrstelemetry.plugin.guidance;

import java.util.List;

/**
 * Arrival at a stop's patches (canonical §28.14), pure. A patch is several objects (an allotment is a row of them), so the
 * distance is to the nearest tile of any of their footprints, never to one nominated tile or the patch centre. The player has
 * arrived when beside one (no walk left), or when the game shows one of the stop's patches change while the player stands
 * within 2 tiles (harvesting along an allotment: the game's evidence, not a hovered menu). Arrival holds until the player is
 * 3 tiles from every footprint, so moving along the patch while working never goes back to "Walk to patch".
 */
public final class PatchArrival
{
	/** Beside: at most this many tiles from a footprint. */
	public static final int BESIDE = 1;
	/** A patch change counts as the player's own work within this many tiles. */
	public static final int TOUCH = 2;
	/** Left: this many tiles from every footprint. */
	public static final int LEAVE = 3;

	private PatchArrival()
	{
	}

	/** Chebyshev tiles from (x, y) to the nearest footprint {x1, y1, x2, y2, ...} (same coordinates), or -1 with none. */
	public static int distance(int x, int y, List<int[]> boxes)
	{
		if (boxes == null || boxes.isEmpty())
		{
			return -1;
		}
		int best = Integer.MAX_VALUE;
		for (int[] b : boxes)
		{
			int dx = x < b[0] ? b[0] - x : x > b[2] ? x - b[2] : 0;
			int dy = y < b[1] ? b[1] - y : y > b[3] ? y - b[3] : 0;
			best = Math.min(best, Math.max(dx, dy));
		}
		return best;
	}

	/**
	 * Whether the player is at the stop now.
	 *
	 * @param wasThere arrived at this same stop on the previous resolution
	 * @param walkLeft tiles of walk still drawn to an interaction position (0: none)
	 * @param near     {@link #distance} to the stop's footprints (-1: unknown)
	 * @param touched  one of the stop's patches changed this tick
	 */
	public static boolean at(boolean wasThere, int walkLeft, int near, boolean touched)
	{
		if (walkLeft == 0 && near >= 0 && near <= BESIDE || touched && near >= 0 && near <= TOUCH)
		{
			return true;
		}
		if (!wasThere)
		{
			return false;
		}
		return near >= 0 ? near < LEAVE : walkLeft < LEAVE;
	}
}
