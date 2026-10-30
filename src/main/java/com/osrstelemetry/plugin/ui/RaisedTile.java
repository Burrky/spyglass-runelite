package com.osrstelemetry.plugin.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

/**
 * A Raised Tile: a warm card face floating above the recessed
 * {@link SpyglassTheme#TILE_CANVAS}. Square and pixel-built, like an OSRS
 * interface panel:
 *
 * <ul>
 *   <li>a fine dark rim for the silhouette;</li>
 *   <li>a 1 px lit upper edge and a 1 px shade along the lower edge, inside the rim;</li>
 *   <li>a shallow 2 px stepped shadow below and to the right;</li>
 *   <li>an optional 3 px semantic state rail on the left.</li>
 * </ul>
 *
 * Every colour is opaque and pre-blended: a handful of {@code fillRect}s per
 * paint, no alpha, no rendering hints, no animation.
 *
 * Two kinds share that construction:
 * <ul>
 *   <li><b>Status</b> ({@code onActivate == null}): no hover, press, hand
 *   cursor or focus, so it never promises an interaction it does not have.</li>
 *   <li><b>Interactive</b>: hover warms the face and brightens the upper edge.
 *   Press seats the whole tile 1 px down and right into its shadow (the shadow
 *   shrinks to 1 px) and the content moves with it. Activation fires on
 *   release INSIDE; dragging out and releasing cancels. Keyboard: focusable,
 *   with a visible focus rim; Space or Enter presses, and release activates.</li>
 * </ul>
 *
 * Content is added like any {@link JPanel} (default {@link BorderLayout}).
 * The border reserves padding, rail and shadow space. Children should be
 * non-opaque; on an interactive tile they carry no mouse listeners or
 * tooltips, so the tile receives the mouse. A status tile may host its own
 * small controls (Farming patch cards: include box, expand chevron, sprite
 * tooltip). Height is bounded to the preferred height so a BoxLayout parent
 * can never stretch a tile.
 */
class RaisedTile extends JPanel
{
	/** Shadow depth reserved on the bottom and right. */
	static final int SHADOW = 2;
	static final int RAIL_WIDTH = 3;
	private static final int PAD = 8;

	private final int padV;
	private final int padH;

	private final Runnable onActivate;
	private Color rail;

	private boolean hovered;
	/** Width of the last layout; a change triggers one re-layout so wrapped text settles at the real width. */
	private int laidOutWidth = -1;
	/** Mouse button 1 went down on this tile and has not been released yet. */
	private boolean mouseDown;
	/** Space/Enter went down while focused and has not been released yet. */
	private boolean keyDown;

	RaisedTile(Runnable onActivate)
	{
		this(onActivate, PAD, PAD);
	}

	/**
	 * A tile with its own inner padding (vertical, horizontal); the rim, edges,
	 * rail and shadow are unchanged. Compact list cards (Farming patch cards)
	 * use a tighter padding than Home's tiles.
	 */
	RaisedTile(Runnable onActivate, int padV, int padH)
	{
		super(new BorderLayout());
		this.onActivate = onActivate;
		this.padV = padV;
		this.padH = padH;
		setOpaque(true);
		setBackground(SpyglassTheme.TILE_CANVAS);
		setBorder(new TileBorder());
		setAlignmentX(Component.LEFT_ALIGNMENT);
		if (onActivate != null)
		{
			installInteraction();
		}
		else
		{
			setFocusable(false);
		}
		// Wrapping labels size themselves from their parent's width, which is
		// only known after the first layout. Static content (Home) is never
		// re-rendered, so re-lay out once whenever the width actually changes.
		addComponentListener(new java.awt.event.ComponentAdapter()
		{
			@Override
			public void componentResized(java.awt.event.ComponentEvent e)
			{
				int width = getWidth();
				if (width != laidOutWidth)
				{
					laidOutWidth = width;
					SwingUtilities.invokeLater(() ->
					{
						revalidate();
						repaint();
					});
				}
			}
		});
	}

	boolean isInteractive()
	{
		return onActivate != null;
	}

	/** The 3 px semantic rail colour, or null for none (neutral and unknown cards have none). */
	void setRail(Color rail)
	{
		if (rail == null ? this.rail == null : rail.equals(this.rail))
		{
			return;
		}
		this.rail = rail;
		revalidate();
		repaint();
	}

	Color getRail()
	{
		return rail;
	}

	boolean isHovered()
	{
		return hovered;
	}

	/** Visually pressed: the mouse is down and still over the tile, or the activation key is held. */
	boolean isPressedVisual()
	{
		return onActivate != null && ((mouseDown && hovered) || keyDown);
	}

	private void installInteraction()
	{
		setFocusable(true);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hovered = true;
				repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				// Dragging out with the button held also arrives here: the
				// press visual goes away, and a release out there cancels.
				hovered = false;
				repaint();
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					mouseDown = true;
					hovered = contains(e.getPoint());
					repaint();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (!SwingUtilities.isLeftMouseButton(e) || !mouseDown)
				{
					return;
				}
				mouseDown = false;
				boolean inside = contains(e.getPoint());
				hovered = inside;
				repaint();
				if (inside)
				{
					activate();
				}
			}
		};
		addMouseListener(mouse);

		bindKey("SPACE");
		bindKey("ENTER");

		addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				repaint();
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				keyDown = false;
				repaint();
			}
		});

		// Activation usually hides this tile (navigation), so no exit event
		// will come: clear transient state whenever it stops showing.
		addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing())
			{
				hovered = false;
				mouseDown = false;
				keyDown = false;
			}
		});
	}

	private void bindKey(String key)
	{
		String press = "tile-press-" + key;
		String release = "tile-release-" + key;
		getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("pressed " + key), press);
		getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("released " + key), release);
		getActionMap().put(press, new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				if (!keyDown)
				{
					keyDown = true;
					repaint();
				}
			}
		});
		getActionMap().put(release, new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				if (keyDown)
				{
					keyDown = false;
					repaint();
					activate();
				}
			}
		});
	}

	void activate()
	{
		hovered = false;
		repaint();
		if (onActivate != null)
		{
			onActivate.run();
		}
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		// Canvas everywhere (the shadow margin shows it), then the face inside the rim.
		g.setColor(getBackground());
		g.fillRect(0, 0, getWidth(), getHeight());
		int off = isPressedVisual() ? 1 : 0;
		int w = getWidth() - SHADOW;
		int h = getHeight() - SHADOW;
		if (w <= 4 || h <= 4)
		{
			return;
		}
		g.setColor(faceColor());
		g.fillRect(off + 1, off + 1, w - 2, h - 2);
	}

	@Override
	protected void paintChildren(Graphics g)
	{
		if (isPressedVisual())
		{
			// Content travels with the seated face.
			Graphics shifted = g.create();
			try
			{
				shifted.translate(1, 1);
				super.paintChildren(shifted);
			}
			finally
			{
				shifted.dispose();
			}
			return;
		}
		super.paintChildren(g);
	}

	/** A face of its own (selected / unselected activity cards; pass 31), at rest and hovered; null: the standard face. */
	private Color face;
	private Color faceHover;

	void setFace(Color face, Color hover)
	{
		this.face = face;
		this.faceHover = hover == null ? face : hover;
		repaint();
	}

	Color getFace()
	{
		return face;
	}

	private Color faceColor()
	{
		if (isPressedVisual())
		{
			return face != null ? face : SpyglassTheme.TILE_FACE_PRESSED;
		}
		if (face != null)
		{
			return onActivate != null && hovered ? faceHover : face;
		}
		return onActivate != null && hovered ? SpyglassTheme.TILE_FACE_HOVER : SpyglassTheme.TILE_FACE;
	}

	/** Rim, edges, rail and shadow; also reserves padding / rail / shadow space as insets. */
	private final class TileBorder implements Border
	{
		@Override
		public void paintBorder(Component c, Graphics g, int x, int y, int width, int height)
		{
			int w = width - SHADOW;
			int h = height - SHADOW;
			if (w <= 4 || h <= 4)
			{
				return;
			}
			boolean pressed = isPressedVisual();
			boolean lifted = onActivate != null && hovered && !pressed;
			boolean focused = onActivate != null && isFocusOwner();
			int ox = x + (pressed ? 1 : 0);
			int oy = y + (pressed ? 1 : 0);

			// Shadow first (the face never covers it at rest): 2 px stepped at
			// rest; 1 px once the tile has seated down into it.
			if (pressed)
			{
				g.setColor(SpyglassTheme.TILE_SHADOW_FAR);
				g.fillRect(ox + 1, oy + h, w, 1);
				g.setColor(SpyglassTheme.TILE_SHADOW_FAR);
				g.fillRect(ox + w, oy + 1, 1, h);
			}
			else
			{
				g.setColor(SpyglassTheme.TILE_SHADOW_NEAR);
				g.fillRect(x + 1, y + h, w, 1);
				g.fillRect(x + w, y + 1, 1, h);
				g.setColor(SpyglassTheme.TILE_SHADOW_FAR);
				g.fillRect(x + 2, y + h + 1, w, 1);
				g.fillRect(x + w + 1, y + 2, 1, h);
			}

			// Fine rim (warms to bronze while keyboard-focused).
			g.setColor(focused ? SpyglassTheme.ACCENT_BRONZE : SpyglassTheme.TILE_RIM);
			g.drawRect(ox, oy, w - 1, h - 1);

			int innerLeft = ox + 1;
			if (rail != null)
			{
				g.setColor(rail);
				g.fillRect(ox + 1, oy + 1, RAIL_WIDTH, h - 2);
				innerLeft = ox + 1 + RAIL_WIDTH;
			}
			int innerWidth = ox + w - 1 - innerLeft;

			if (!pressed)
			{
				// Lit upper edge; a seated tile loses its light.
				g.setColor(lifted ? SpyglassTheme.TILE_EDGE_HIGHLIGHT_HOVER : SpyglassTheme.TILE_EDGE_HIGHLIGHT);
				g.fillRect(innerLeft, oy + 1, innerWidth, 1);
			}
			// Shade along the inside of the lower edge.
			g.setColor(SpyglassTheme.TILE_EDGE_SHADE);
			g.fillRect(innerLeft, oy + h - 2, innerWidth, 1);
		}

		@Override
		public Insets getBorderInsets(Component c)
		{
			int left = 1 + padH + (rail != null ? RAIL_WIDTH : 0);
			return new Insets(1 + padV, left, 1 + padV + SHADOW, 1 + padH + SHADOW);
		}

		@Override
		public boolean isBorderOpaque()
		{
			return true;
		}
	}
}
