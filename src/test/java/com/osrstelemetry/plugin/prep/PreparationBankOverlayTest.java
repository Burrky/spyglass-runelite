package com.osrstelemetry.plugin.prep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Collections;
import net.runelite.api.widgets.WidgetItem;
import org.junit.Test;

/** The bank highlight draws only on needed items still to withdraw, and never on placeholders. */
public class PreparationBankOverlayTest
{
	private static int paintedPixels(PreparationBankOverlay o, int itemId, int quantity)
	{
		BufferedImage img = new BufferedImage(60, 60, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		o.renderItemOverlay(g, itemId, new WidgetItem(itemId, quantity, new Rectangle(10, 10, 36, 32), null, null));
		g.dispose();
		int n = 0;
		for (int x = 0; x < 60; x++)
		{
			for (int y = 0; y < 60; y++)
			{
				if ((img.getRGB(x, y) >>> 24) != 0)
				{
					n++;
				}
			}
		}
		return n;
	}

	@Test
	public void drawsOnlyForHighlightedItems()
	{
		PreparationBankOverlay o = new PreparationBankOverlay(() -> Collections.singletonMap(5295, 4L));
		assertNotEquals(0, paintedPixels(o, 5295, 30));
		assertEquals("not needed", 0, paintedPixels(o, 952, 1));
		assertEquals("a placeholder (quantity 0) is never outlined", 0, paintedPixels(o, 5295, 0));
		assertEquals("inactive: nothing at all", 0, paintedPixels(new PreparationBankOverlay(Collections::emptyMap), 5295, 30));
		assertEquals(0, paintedPixels(new PreparationBankOverlay(() -> null), 5295, 30));
	}

	@Test
	public void theItemsOwnSpriteOutline_isDrawnOnTheItem_inTheGuidanceBlue_neverASlotSquare()
	{
		// A stand-in sprite outline: a 6x6 ring in the middle of a 36x32 image, as ItemManager.getItemOutline returns.
		java.awt.Color[] asked = new java.awt.Color[1];
		PreparationBankOverlay o = new PreparationBankOverlay(() -> Collections.singletonMap(5295, 4L), () -> Collections.singleton(5295)).withOutlines(
			(id, q, c) ->
			{
				asked[0] = c;
				BufferedImage sprite = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
				Graphics2D sg = sprite.createGraphics();
				sg.setColor(c);
				sg.drawOval(15, 13, 6, 6);
				sg.dispose();
				return sprite;
			});
		BufferedImage img = new BufferedImage(60, 60, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		o.renderItemOverlay(g, 5295, new WidgetItem(5295, 30, new Rectangle(10, 10, 36, 32), null, null));
		g.dispose();
		assertEquals("Find: the lighter blue", PreparationBankOverlay.FOCUS, asked[0]);
		int ring = 0;
		for (int x = 25; x <= 31; x++)
		{
			for (int y = 23; y <= 29; y++)
			{
				ring += (img.getRGB(x, y) >>> 24) != 0 ? 1 : 0;
			}
		}
		assertNotEquals("the sprite outline sits on the item (offset 10, 10)", 0, ring);
		assertEquals("no slot square: the slot's corner stays clear", 0, img.getRGB(10, 10) >>> 24);
		assertEquals(0, img.getRGB(45, 10) >>> 24);
	}
}
