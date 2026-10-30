package com.osrstelemetry.plugin.navigation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The whole-world walking collision map (canonical §28.10): for every tile, whether its north and east edges are open, and
 * whether a wall or door sits on them. Read from {@code navigation/collision-map.zip}, the collision dump of the Shortest Path
 * plugin (Skretzo and contributors, BSD 2-Clause; see THIRD_PARTY_NOTICES.md): one zip entry per 64×64 region named
 * {@code <regionX>_<regionY>}, holding a bit set of four flags per tile per plane (north open, east open, wall north, wall
 * east), plane-major then y then x. Doors are closed edges here; opening them is a {@link Transition} like any other.
 *
 * <p>Immutable after loading; safe to share between threads. Tiles outside the dump read as blocked.
 */
public final class WorldCollision
{
	static final int REGION = 64;
	private static final int FLAGS = 4;
	private static final int FLAG_NORTH = 0;
	private static final int FLAG_EAST = 1;
	private static final int FLAG_WALL_NORTH = 2;
	private static final int FLAG_WALL_EAST = 3;
	private static final int BITS_PER_PLANE = REGION * REGION * FLAGS;
	private static final int WORDS_PER_PLANE = BITS_PER_PLANE / 64;

	private final int minRx;
	private final int minRy;
	private final int width;
	private final int height;
	private final int[] offset;
	private final byte[] planes;
	private final long[] words;

	private WorldCollision(Map<Integer, byte[]> regions, int minRx, int minRy, int maxRx, int maxRy)
	{
		this.minRx = minRx;
		this.minRy = minRy;
		this.width = maxRx - minRx + 1;
		this.height = maxRy - minRy + 1;
		this.offset = new int[width * height];
		this.planes = new byte[width * height];
		java.util.Arrays.fill(offset, -1);
		Map<Integer, long[]> decoded = new HashMap<>();
		int total = 0;
		for (Map.Entry<Integer, byte[]> e : regions.entrySet())
		{
			int rx = e.getKey() >> 16;
			int ry = e.getKey() & 0xFFFF;
			int index = (rx - minRx) + (ry - minRy) * width;
			BitSet bits = BitSet.valueOf(e.getValue());
			int planeCount = (bits.size() + BITS_PER_PLANE - 1) / BITS_PER_PLANE;
			planes[index] = (byte) planeCount;
			offset[index] = total;
			decoded.put(index, bits.toLongArray());
			total += planeCount * WORDS_PER_PLANE;
		}
		words = new long[total];
		for (Map.Entry<Integer, long[]> e : decoded.entrySet())
		{
			long[] w = e.getValue();
			System.arraycopy(w, 0, words, offset[e.getKey()], Math.min(w.length, planes[e.getKey()] * WORDS_PER_PLANE));
		}
	}

	private static volatile WorldCollision shared;

	/** The bundled map, loaded once (about 10 MB of bits). Thread-safe. */
	public static WorldCollision bundled()
	{
		WorldCollision c = shared;
		if (c == null)
		{
			synchronized (WorldCollision.class)
			{
				c = shared;
				if (c == null)
				{
					try (InputStream in = WorldCollision.class.getResourceAsStream("/com/osrstelemetry/plugin/navigation/collision-map.zip"))
					{
						if (in == null)
						{
							throw new IllegalStateException("Missing navigation/collision-map.zip");
						}
						c = read(in);
					}
					catch (IOException e)
					{
						throw new UncheckedIOException(e);
					}
					shared = c;
				}
			}
		}
		return c;
	}

	/** From region bit sets keyed {@code regionX << 16 | regionY} (tests build small synthetic worlds this way). */
	static WorldCollision fromRegions(Map<Integer, byte[]> regions)
	{
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int k : regions.keySet())
		{
			minX = Math.min(minX, k >> 16);
			maxX = Math.max(maxX, k >> 16);
			minY = Math.min(minY, k & 0xFFFF);
			maxY = Math.max(maxY, k & 0xFFFF);
		}
		return new WorldCollision(regions, minX, minY, maxX, maxY);
	}

	static WorldCollision read(InputStream zip) throws IOException
	{
		Map<Integer, byte[]> regions = new HashMap<>();
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		try (ZipInputStream in = new ZipInputStream(zip))
		{
			ZipEntry entry;
			byte[] buf = new byte[8192];
			while ((entry = in.getNextEntry()) != null)
			{
				String[] n = entry.getName().split("_");
				int x = Integer.parseInt(n[0]);
				int y = Integer.parseInt(n[1]);
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				int r;
				while ((r = in.read(buf)) > 0)
				{
					out.write(buf, 0, r);
				}
				regions.put(x << 16 | y, out.toByteArray());
				minX = Math.min(minX, x);
				minY = Math.min(minY, y);
				maxX = Math.max(maxX, x);
				maxY = Math.max(maxY, y);
			}
		}
		return new WorldCollision(regions, minX, minY, maxX, maxY);
	}

	private boolean flag(int x, int y, int z, int flag)
	{
		if (x < 0 || y < 0 || z < 0)
		{
			return false;
		}
		int rx = x / REGION - minRx;
		int ry = y / REGION - minRy;
		if (rx < 0 || ry < 0 || rx >= width || ry >= height)
		{
			return false;
		}
		int index = rx + ry * width;
		int off = offset[index];
		if (off < 0 || z >= planes[index])
		{
			return false;
		}
		int bit = (z * REGION * REGION + (y & (REGION - 1)) * REGION + (x & (REGION - 1))) * FLAGS + flag;
		return (words[off + (bit >> 6)] >>> (bit & 63) & 1L) != 0L;
	}

	public boolean north(int x, int y, int z)
	{
		return flag(x, y, z, FLAG_NORTH);
	}

	public boolean south(int x, int y, int z)
	{
		return flag(x, y - 1, z, FLAG_NORTH);
	}

	public boolean east(int x, int y, int z)
	{
		return flag(x, y, z, FLAG_EAST);
	}

	public boolean west(int x, int y, int z)
	{
		return flag(x - 1, y, z, FLAG_EAST);
	}

	/** A wall or door on the edge (whether or not it is open). */
	public boolean wallNorth(int x, int y, int z)
	{
		return flag(x, y, z, FLAG_WALL_NORTH);
	}

	public boolean wallEast(int x, int y, int z)
	{
		return flag(x, y, z, FLAG_WALL_EAST);
	}

	/** No open edge at all: the tile cannot be stood on. */
	public boolean blocked(int x, int y, int z)
	{
		return !north(x, y, z) && !south(x, y, z) && !east(x, y, z) && !west(x, y, z);
	}

	/** One step in direction (dx, dy), each -1..1; a diagonal needs both flanking cardinal moves (the game's rule). */
	public boolean canStep(int x, int y, int z, int dx, int dy)
	{
		if (dx == 0 && dy == 0)
		{
			return false;
		}
		if (dx == 0)
		{
			return dy > 0 ? north(x, y, z) : south(x, y, z);
		}
		if (dy == 0)
		{
			return dx > 0 ? east(x, y, z) : west(x, y, z);
		}
		boolean v = dy > 0 ? north(x, y, z) : south(x, y, z);
		boolean h = dx > 0 ? east(x, y, z) : west(x, y, z);
		if (!v || !h)
		{
			return false;
		}
		boolean hThenV = dy > 0 ? north(x + dx, y, z) : south(x + dx, y, z);
		boolean vThenH = dx > 0 ? east(x, y + dy, z) : west(x, y + dy, z);
		return hThenV && vThenH;
	}
}
