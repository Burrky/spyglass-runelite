package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.SkillCompanionView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.BoxLayout;
import org.junit.Test;

/** The shared sprite + number grid: every item shown at every sidebar width, exact numbers, unknown never zero. */
public class SkillItemGridTest
{
	private static final int[] WIDTHS = {150, 180, 213, 260};

	private static List<SkillCompanionView.ItemStat> runes(int n)
	{
		List<SkillCompanionView.ItemStat> out = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			out.add(SkillCompanionView.ItemStat.of(554 + i, "Rune " + i, 1_000L + i, null));
		}
		return out;
	}

	private static SkillItemGrid laidOut(SkillItemGrid grid, int width)
	{
		JPanel host = new JPanel();
		host.setLayout(new BoxLayout(host, BoxLayout.Y_AXIS));
		host.add(grid);
		host.setSize(width, 2_000);
		host.doLayout();
		grid.setSize(width, grid.getPreferredSize().height);
		grid.doLayout();
		return grid;
	}

	@Test
	public void tiles_everyItemShown_atEveryWidth_noMoreLabel_noPercent()
	{
		for (int width : WIDTHS)
		{
			SkillItemGrid grid = laidOut(new SkillItemGrid("g", null, runes(14), null), width);
			assertEquals(SkillItemGrid.Shape.TILES, grid.shape());
			assertEquals("all 14 runes @" + width, 14, grid.cells().size());
			for (SkillItemGrid.Cell c : grid.cells())
			{
				assertTrue("inside the width @" + width, c.getX() + c.getWidth() <= width);
				assertTrue(c.getWidth() > 0 && c.getHeight() > 0);
				assertFalse(c.texts().get(0).contains("%"));
				assertFalse(c.texts().get(0).contains("more"));
			}
			int cols = grid.columnsFor(width);
			int rows = (14 + cols - 1) / cols;
			assertTrue("tall enough for every row @" + width, grid.getPreferredSize().height >= rows * SkillItemGrid.TILE_H);
		}
		assertTrue("wider sidebars fit more per row", new SkillItemGrid("g", null, runes(14), null).columnsFor(260)
			> new SkillItemGrid("g", null, runes(14), null).columnsFor(150));
	}

	@Test
	public void rows_perResourceColumns_exactNumbers_unknownIsADash()
	{
		List<SkillCompanionView.ItemStat> items = Arrays.asList(
			new SkillCompanionView.ItemStat(440, "Iron ore", Arrays.asList(1_103L, 742L), null, null),
			new SkillCompanionView.ItemStat(453, "Coal", Arrays.asList(94L, null), null, null));
		for (int width : WIDTHS)
		{
			SkillItemGrid grid = laidOut(new SkillItemGrid("g", Arrays.asList("MINED", "BANKED"), items, null), width);
			assertEquals("MINED|BANKED;440:1,103|742;453:94|-", grid.describe());
			for (SkillItemGrid.Cell c : grid.cells())
			{
				assertTrue(c.getX() + c.getWidth() <= width);
			}
		}
	}

	@Test
	public void stackText_followsTheGame_tooltipKeepsTheExactNumber()
	{
		assertEquals("99,999", SkillItemGrid.stack(99_999));
		assertEquals("100K", SkillItemGrid.stack(100_000));
		assertEquals("9999K", SkillItemGrid.stack(9_999_999));
		assertEquals("10M", SkillItemGrid.stack(10_000_000));
		SkillItemGrid grid = new SkillItemGrid("g", null,
			Collections.singletonList(SkillCompanionView.ItemStat.of(560, "Death rune", 123_456L, null)), null);
		assertEquals("Death rune: 123,456", grid.cells().get(0).getToolTipText());
		SkillItemGrid unknown = new SkillItemGrid("g", null,
			Collections.singletonList(SkillCompanionView.ItemStat.of(560, "Death rune", null, null)), null);
		assertEquals("-", unknown.cells().get(0).texts().get(0));
		assertEquals("Death rune: unknown", unknown.cells().get(0).getToolTipText());
	}

	@Test
	public void lines_spriteWithShortText()
	{
		SkillItemGrid grid = new SkillItemGrid("g", null,
			Collections.singletonList(SkillCompanionView.ItemStat.line(453, "Coal", "54 loaded", null)), null);
		assertEquals(SkillItemGrid.Shape.LINES, grid.shape());
		assertEquals(";453:54 loaded", grid.describe());
	}

	@Test
	public void tileNumber_sitsOnTheSpritesOwnCorner_inAWideTile()
	{
		// Pass 28: a 36 px sprite centred in a 90 px tile starts at 27; the stack number follows it.
		org.junit.Assert.assertEquals(27, SkillItemGrid.spriteLeft(90, 36));
		org.junit.Assert.assertEquals(0, SkillItemGrid.spriteLeft(36, 36));
		org.junit.Assert.assertEquals("never left of the tile", 0, SkillItemGrid.spriteLeft(30, 36));
	}
}
