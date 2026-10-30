package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import org.junit.Test;

/** Raised Tile interaction: activation on release inside only, keyboard activation, status tiles inert. */
public class RaisedTileTest
{
	private static void mouse(RaisedTile tile, int id, int x, int y)
	{
		int mods = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.BUTTON1_DOWN_MASK : 0;
		tile.dispatchEvent(new MouseEvent(tile, id, System.currentTimeMillis(), mods, x, y, 1, false, MouseEvent.BUTTON1));
	}

	private static RaisedTile sized(Runnable onActivate)
	{
		RaisedTile tile = new RaisedTile(onActivate);
		tile.setSize(200, 50);
		return tile;
	}

	@Test
	public void pressAndReleaseInside_activatesOnce_onRelease()
	{
		AtomicInteger activations = new AtomicInteger();
		RaisedTile tile = sized(activations::incrementAndGet);
		mouse(tile, MouseEvent.MOUSE_ENTERED, 20, 20);
		mouse(tile, MouseEvent.MOUSE_PRESSED, 20, 20);
		assertEquals("nothing on press", 0, activations.get());
		assertTrue("pressed visual while held inside", tile.isPressedVisual());
		mouse(tile, MouseEvent.MOUSE_RELEASED, 20, 20);
		assertEquals(1, activations.get());
		assertFalse(tile.isPressedVisual());
	}

	@Test
	public void dragOutAndReleaseOutside_cancels()
	{
		AtomicInteger activations = new AtomicInteger();
		RaisedTile tile = sized(activations::incrementAndGet);
		mouse(tile, MouseEvent.MOUSE_ENTERED, 20, 20);
		mouse(tile, MouseEvent.MOUSE_PRESSED, 20, 20);
		mouse(tile, MouseEvent.MOUSE_EXITED, 250, 20);
		assertFalse("leaving with the button held drops the press visual", tile.isPressedVisual());
		mouse(tile, MouseEvent.MOUSE_RELEASED, 250, 20);
		assertEquals(0, activations.get());
		// A fresh click afterwards still works.
		mouse(tile, MouseEvent.MOUSE_ENTERED, 20, 20);
		mouse(tile, MouseEvent.MOUSE_PRESSED, 20, 20);
		mouse(tile, MouseEvent.MOUSE_RELEASED, 20, 20);
		assertEquals(1, activations.get());
	}

	@Test
	public void releaseWithoutAPressOnTheTile_doesNothing()
	{
		AtomicInteger activations = new AtomicInteger();
		RaisedTile tile = sized(activations::incrementAndGet);
		mouse(tile, MouseEvent.MOUSE_RELEASED, 20, 20);
		assertEquals(0, activations.get());
	}

	@Test
	public void spaceAndEnter_activateOnRelease_whenFocusable()
	{
		AtomicInteger activations = new AtomicInteger();
		RaisedTile tile = sized(activations::incrementAndGet);
		assertTrue(tile.isFocusable());
		for (String key : new String[] {"SPACE", "ENTER"})
		{
			Object press = tile.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("pressed " + key));
			Object release = tile.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("released " + key));
			tile.getActionMap().get(press).actionPerformed(new ActionEvent(tile, 0, key));
			assertTrue(tile.isPressedVisual());
			tile.getActionMap().get(release).actionPerformed(new ActionEvent(tile, 0, key));
			assertFalse(tile.isPressedVisual());
		}
		assertEquals(2, activations.get());
	}

	@Test
	public void statusTile_hasNoInteractionAffordance()
	{
		RaisedTile tile = sized(null);
		assertFalse(tile.isInteractive());
		assertFalse(tile.isFocusable());
		assertEquals(0, tile.getMouseListeners().length);
		mouse(tile, MouseEvent.MOUSE_ENTERED, 20, 20);
		mouse(tile, MouseEvent.MOUSE_PRESSED, 20, 20);
		assertFalse("status cards never lift or press", tile.isHovered() || tile.isPressedVisual());
	}

	@Test
	public void rail_reservesItsWidth_onlyWhenSet()
	{
		RaisedTile tile = sized(null);
		int without = tile.getInsets().left;
		tile.setRail(SpyglassTheme.ACCENT_GOLD);
		assertEquals(without + RaisedTile.RAIL_WIDTH, tile.getInsets().left);
		assertEquals("shadow space reserved below the padding", tile.getInsets().top + RaisedTile.SHADOW, tile.getInsets().bottom);
	}
}
