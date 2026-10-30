package com.osrstelemetry.plugin.ui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * A real game item sprite sitting directly on a Raised Tile's face, drawn at
 * its native 36×32 size (never scaled) and centred in its bounds.
 *
 * The component always reserves the sprite's full native size, so nothing
 * around it moves when the image arrives. The sprite comes from
 * {@link ItemManager#getImage(int)}, which fills its image in asynchronously
 * on the client thread; the icon asks for one repaint on the EDT once it has
 * loaded, and a paint only ever draws the in-memory image (no I/O). With no
 * {@link ItemManager} (tests) the space is simply left empty.
 */
final class ItemIcon extends JComponent
{
	static final int WIDTH = 36;
	static final int HEIGHT = 32;

	private final int itemId;
	private final BufferedImage sprite;

	ItemIcon(ItemManager itemManager, int itemId)
	{
		this.itemId = itemId;
		setOpaque(false);
		Dimension size = new Dimension(WIDTH, HEIGHT);
		setPreferredSize(size);
		setMinimumSize(size);
		BufferedImage image = null;
		if (itemManager != null)
		{
			AsyncBufferedImage async = itemManager.getImage(itemId);
			async.onLoaded(() -> SwingUtilities.invokeLater(this::repaint));
			image = async;
		}
		this.sprite = image;
	}

	int getItemId()
	{
		return itemId;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		if (sprite != null)
		{
			g.drawImage(sprite, (getWidth() - WIDTH) / 2, (getHeight() - HEIGHT) / 2, null);
		}
	}
}
