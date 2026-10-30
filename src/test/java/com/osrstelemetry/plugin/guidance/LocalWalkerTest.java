package com.osrstelemetry.plugin.guidance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** The scene walk used for guidance tiles: collision flags, walls and diagonal rules (synthetic grids). */
public class LocalWalkerTest
{
	private static int[][] open(int w, int h)
	{
		return new int[w][h];
	}

	@Test
	public void openGround_isAStraightDiagonalWalk_endingWithinTheRadius()
	{
		List<LocalWalker.Tile> p = LocalWalker.path(open(20, 20), new LocalWalker.Tile(1, 1), new LocalWalker.Tile(10, 10), 0);
		assertEquals(9, p.size());
		assertEquals(new LocalWalker.Tile(10, 10), p.get(p.size() - 1));
		assertEquals("radius 3 stops early", 6, LocalWalker.path(open(20, 20), new LocalWalker.Tile(1, 1), new LocalWalker.Tile(10, 10), 3).size());
	}

	@Test
	public void aBlockedColumn_isWalkedAround()
	{
		int[][] f = open(12, 12);
		for (int y = 0; y < 10; y++)
		{
			f[5][y] = LocalWalker.FULL;
		}
		List<LocalWalker.Tile> p = LocalWalker.path(f, new LocalWalker.Tile(2, 2), new LocalWalker.Tile(8, 2), 0);
		assertEquals(new LocalWalker.Tile(8, 2), p.get(p.size() - 1));
		for (LocalWalker.Tile t : p)
		{
			assertFalse("never through a blocked tile: " + t, t.x == 5 && t.y < 10);
		}
		assertTrue("goes round the end of the wall", p.stream().anyMatch(t -> t.x == 5 && t.y >= 10));
	}

	@Test
	public void wallFlags_blockTheirSide_andDiagonalsNeedBothCardinals()
	{
		int[][] f = open(5, 5);
		f[2][2] |= LocalWalker.E;
		assertFalse(LocalWalker.canStep(f, 2, 2, 1, 0));
		assertTrue(LocalWalker.canStep(f, 2, 2, 0, 1));
		assertFalse("diagonal past an east wall", LocalWalker.canStep(f, 2, 2, 1, 1));
		int[][] g = open(5, 5);
		g[3][2] = LocalWalker.FULL;
		assertFalse("no corner cutting past a blocked tile", LocalWalker.canStep(g, 2, 2, 1, 1));
	}

	@Test
	public void anUnreachableTarget_walksToTheClosestReachableTile()
	{
		int[][] f = open(10, 10);
		for (int y = 0; y < 10; y++)
		{
			f[6][y] = LocalWalker.FULL;
		}
		List<LocalWalker.Tile> p = LocalWalker.path(f, new LocalWalker.Tile(1, 5), new LocalWalker.Tile(8, 5), 0);
		assertFalse(p.isEmpty());
		assertEquals(5, p.get(p.size() - 1).x);
		assertTrue("already there: nothing to walk", LocalWalker.path(f, new LocalWalker.Tile(1, 5), new LocalWalker.Tile(1, 5), 0).isEmpty());
	}

	@Test
	public void toThePatch_endsBesideItsFootprint_neverOnIt_andNothingWhenAlreadyThere()
	{
		int[][] f = open(12, 12);
		for (int x = 5; x <= 6; x++)
		{
			for (int y = 5; y <= 6; y++)
			{
				f[x][y] = LocalWalker.FULL; // a 2x2 herb patch
			}
		}
		LocalWalker.Tile min = new LocalWalker.Tile(5, 5);
		LocalWalker.Tile max = new LocalWalker.Tile(6, 6);
		List<LocalWalker.Tile> p = LocalWalker.pathToObject(f, new LocalWalker.Tile(1, 1), min, max);
		LocalWalker.Tile end = p.get(p.size() - 1);
		assertTrue("ends beside the patch: " + end, LocalWalker.beside(end, min, max));
		assertFalse("never a corner-only tile", end.x == 4 && end.y == 4);
		assertTrue(LocalWalker.pathToObject(f, new LocalWalker.Tile(4, 5), min, max).isEmpty());
		assertTrue(LocalWalker.beside(new LocalWalker.Tile(4, 5), min, max));
	}

	@Test
	public void aClosedDoor_isWalkedThrough_butAWallIsNot()
	{
		int[][] f = open(10, 10);
		for (int y = 0; y < 10; y++)
		{
			f[5][y] |= LocalWalker.W; // a fence on the west side of column 5
			f[4][y] |= LocalWalker.E;
		}
		LocalWalker.Tile start = new LocalWalker.Tile(2, 5);
		LocalWalker.Tile target = new LocalWalker.Tile(8, 5);
		assertEquals("walled off: only up to the fence", 4, LocalWalker.path(f, start, target, 0).get(LocalWalker.path(f, start, target, 0).size() - 1).x);
		int[][] withDoor = LocalWalker.withOpenDoors(f, java.util.Collections.singletonList(new LocalWalker.Tile(5, 7)));
		List<LocalWalker.Tile> p = LocalWalker.path(withDoor, start, target, 0);
		assertEquals(target, p.get(p.size() - 1));
		assertTrue("through the door tile", p.stream().anyMatch(t -> t.x == 5 && t.y == 7));
		assertTrue("the original flags are untouched", (f[5][7] & LocalWalker.W) != 0);
	}
}
