package com.osrstelemetry.plugin.guidance;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

/**
 * The guidance visuals (canonical §15, LOCKED): path tiles in the world (a
 * look-ahead window of 16, thin gold outlines fading at the end), a gold line
 * on the minimap, a marker on the world map, the suggested teleport outlined
 * in the inventory and equipment, and a compact movable box with the
 * location and patch type, the next actions and the run's progress. They
 * only draw the latest {@link GuidanceModel}; nothing here decides anything.
 */
public final class GuidanceOverlays
{
	static final Color GOLD = new Color(0xD8, 0xB0, 0x4C);
	/**
	 * The path's colour (live feedback 2026-10-21: electric blue, keeping the fading tiles). Saturated enough to read on
	 * grass, sand and stone; a dark edge under it keeps it readable on bright ground. Patch statuses keep their own colours.
	 */
	static final Color PATH = new Color(0x1E, 0xB4, 0xFF);
	static final Color PATH_EDGE = new Color(0, 0x14, 0x30);
	private static final BasicStroke TILE_EDGE = new BasicStroke(3f);
	private static final BasicStroke TILE_LINE = new BasicStroke(1.5f);
	private static final BasicStroke MINIMAP_EDGE = new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke MINIMAP_LINE = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke ITEM_LINE = new BasicStroke(2f);
	private static final BasicStroke HULL_EDGE = new BasicStroke(4f);
	private static final BasicStroke HULL_LINE = new BasicStroke(2f);
	static final int WINDOW = 16;
	/** How far under the tile's centre the player label sits, in pixels. */
	static final int LABEL_DROP = 22;
	static final int FADE = 4;

	private final Supplier<GuidanceModel> model;
	private final WorldMapPointManager worldMap;
	private final Overlay box;
	private final Overlay tiles;
	private final Overlay minimap;
	private final Overlay items;
	private final Overlay highlights;
	private final Overlay spell;
	private WorldMapPoint mapPoint;
	private WorldPoint mapTarget;

	public GuidanceOverlays(Client client, Supplier<GuidanceModel> model, WorldMapPointManager worldMap)
	{
		this(client, model, worldMap, (id, q, c) -> null);
	}

	/** {@code outlines}: RuneLite's item sprite outlines (ItemManager.getItemOutline, cached there). */
	public GuidanceOverlays(Client client, Supplier<GuidanceModel> model, WorldMapPointManager worldMap,
		com.osrstelemetry.plugin.prep.PreparationBankOverlay.Outlines outlines)
	{
		this.model = model;
		this.worldMap = worldMap;
		this.box = new Box(model);
		this.tiles = new Tiles(client, model);
		this.minimap = new Minimap(client, model);
		this.items = new Items(model, outlines);
		this.highlights = new Highlights(client, model);
		this.spell = new Spell(client, model);
	}

	public void addTo(OverlayManager m)
	{
		m.add(box);
		m.add(tiles);
		m.add(minimap);
		m.add(items);
		m.add(highlights);
		m.add(spell);
	}

	public void removeFrom(OverlayManager m)
	{
		m.remove(box);
		m.remove(tiles);
		m.remove(minimap);
		m.remove(items);
		m.remove(highlights);
		m.remove(spell);
		clearMapPoint();
	}

	/** Client thread: keeps the world-map marker on the current target (removed when guidance is off or paused). */
	public void sync(GuidanceModel m)
	{
		WorldPoint t = m.isActive() ? m.getTarget() : null;
		if (t == null)
		{
			clearMapPoint();
			return;
		}
		if (t.equals(mapTarget) && mapPoint != null)
		{
			mapPoint.setTooltip("Next: " + m.getTitle());
			return;
		}
		clearMapPoint();
		mapPoint = WorldMapPoint.builder().worldPoint(t).image(marker()).tooltip("Next: " + m.getTitle()).snapToEdge(true).jumpOnClick(true).build();
		mapTarget = t;
		if (worldMap != null)
		{
			worldMap.add(mapPoint);
		}
	}

	private void clearMapPoint()
	{
		if (mapPoint != null && worldMap != null)
		{
			worldMap.remove(mapPoint);
		}
		mapPoint = null;
		mapTarget = null;
	}

	private static BufferedImage marker()
	{
		BufferedImage img = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.BLACK);
		g.fillOval(0, 0, 12, 12);
		g.setColor(PATH);
		g.fillOval(2, 2, 8, 8);
		g.dispose();
		return img;
	}

	/** The movable on-screen box: location and patch type, the next actions, progress. */
	static final class Box extends OverlayPanel
	{
		private final Supplier<GuidanceModel> model;

		Box(Supplier<GuidanceModel> model)
		{
			this.model = model;
			setPosition(OverlayPosition.TOP_LEFT);
			setMovable(true);
			getPanelComponent().setPreferredSize(new Dimension(190, 0));
		}

		@Override
		public Dimension render(Graphics2D g)
		{
			GuidanceModel m = model.get();
			if (m == null || !m.isActive())
			{
				return null;
			}
			getPanelComponent().getChildren().add(TitleComponent.builder().text(m.getTitle() == null ? "Farm run" : m.getTitle()).color(GOLD).build());
			// The current step only (canonical §28.13): the whole route stays in the NEXT card's expansion.
			if (m.getGuide() != null)
			{
				getPanelComponent().getChildren().add(LineComponent.builder().left(m.getGuide()).leftColor(Color.WHITE).build());
			}
			if (m.getProgress() != null)
			{
				getPanelComponent().getChildren().add(LineComponent.builder().left(m.getProgress()).leftColor(Color.LIGHT_GRAY).build());
			}
			return super.render(g);
		}
	}

	/** Path tiles: the next 16 from the player's position, the last 4 fading. */
	static final class Tiles extends Overlay
	{
		private final Client client;
		private final Supplier<GuidanceModel> model;

		Tiles(Client client, Supplier<GuidanceModel> model)
		{
			this.client = client;
			this.model = model;
			setPosition(OverlayPosition.DYNAMIC);
			setLayer(OverlayLayer.ABOVE_SCENE);
		}

		@Override
		public Dimension render(Graphics2D g)
		{
			GuidanceModel m = model.get();
			if (m == null || !m.isActive())
			{
				return null;
			}
			List<WorldPoint> path = m.getPath();
			int n = Math.min(WINDOW, path.size());
			for (int i = 0; i < n; i++)
			{
				LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), path.get(i));
				Polygon poly = lp == null ? null : Perspective.getCanvasTilePoly(client, lp);
				if (poly == null)
				{
					continue;
				}
				int alpha = Math.max(30, i < n - FADE ? 170 : 170 * (n - i) / (FADE + 1));
				g.setStroke(TILE_EDGE);
				g.setColor(new Color(PATH_EDGE.getRed(), PATH_EDGE.getGreen(), PATH_EDGE.getBlue(), alpha / 2));
				g.draw(poly);
				g.setStroke(TILE_LINE);
				g.setColor(new Color(PATH.getRed(), PATH.getGreen(), PATH.getBlue(), alpha));
				g.draw(poly);
			}
			drawPlayerLabel(g, m);
			return null;
		}

		/** The guide box's current action, under the player's feet (prepared text; nothing is worked out here). */
		private void drawPlayerLabel(Graphics2D g, GuidanceModel m)
		{
			String text = m.getPlayerLabel();
			net.runelite.api.Player me = text == null ? null : client.getLocalPlayer();
			LocalPoint lp = me == null ? null : me.getLocalLocation();
			if (lp == null)
			{
				return;
			}
			g.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
			Point at = Perspective.getCanvasTextLocation(client, g, lp, text, 0);
			if (at == null)
			{
				return;
			}
			// Below the tile's centre (the name and overhead text sit above the head).
			net.runelite.client.ui.overlay.OverlayUtil.renderTextLocation(g, new Point(at.getX(), at.getY() + LABEL_DROP), text, PATH);
		}
	}

	/** Minimap: a line through the path, a dot at its end. */
	static final class Minimap extends Overlay
	{
		private final Client client;
		private final Supplier<GuidanceModel> model;

		Minimap(Client client, Supplier<GuidanceModel> model)
		{
			this.client = client;
			this.model = model;
			setPosition(OverlayPosition.DYNAMIC);
			setLayer(OverlayLayer.ABOVE_WIDGETS);
		}

		@Override
		public Dimension render(Graphics2D g)
		{
			GuidanceModel m = model.get();
			if (m == null || !m.isActive() || m.getPath().isEmpty())
			{
				return null;
			}
			List<Point> points = new java.util.ArrayList<>();
			for (WorldPoint w : m.getPath())
			{
				LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), w);
				Point p = lp == null ? null : Perspective.localToMinimap(client, lp);
				if (p != null)
				{
					points.add(p);
				}
			}
			if (points.isEmpty())
			{
				return null;
			}
			// A dark edge under the blue line: readable on the minimap's greens and greys.
			for (int pass = 0; pass < 2; pass++)
			{
				g.setColor(pass == 0 ? PATH_EDGE : PATH);
				g.setStroke(pass == 0 ? MINIMAP_EDGE : MINIMAP_LINE);
				for (int i = 1; i < points.size(); i++)
				{
					g.drawLine(points.get(i - 1).getX(), points.get(i - 1).getY(), points.get(i).getX(), points.get(i).getY());
				}
			}
			Point end = points.get(points.size() - 1);
			g.setColor(PATH_EDGE);
			g.fillOval(end.getX() - 4, end.getY() - 4, 8, 8);
			g.setColor(PATH);
			g.fillOval(end.getX() - 3, end.getY() - 3, 6, 6);
			return null;
		}
	}

	/** The suggested teleport's item, its sprite outlined in the guidance blue where the player carries or wears it. */
	static final class Items extends WidgetItemOverlay
	{
		private final Supplier<GuidanceModel> model;
		private final com.osrstelemetry.plugin.prep.PreparationBankOverlay.Outlines outlines;

		Items(Supplier<GuidanceModel> model, com.osrstelemetry.plugin.prep.PreparationBankOverlay.Outlines outlines)
		{
			this.model = model;
			this.outlines = outlines;
			showOnInventory();
			showOnEquipment();
		}

		@Override
		public void renderItemOverlay(Graphics2D g, int itemId, WidgetItem item)
		{
			GuidanceModel m = model.get();
			if (m == null || !m.isActive())
			{
				return;
			}
			for (int id : m.getOutline())
			{
				if (id == itemId)
				{
					Rectangle r = item.getCanvasBounds();
					java.awt.image.BufferedImage o = r == null ? null : outlines.outline(itemId, item.getQuantity(), PATH);
					if (o != null)
					{
						g.drawImage(o, r.x, r.y, null);
					}
					return;
				}
			}
		}
	}

	/** The current action's target in the scene (the patch, the gardener, a fairy ring, a ladder), outlined in the path's blue. */
	static final class Highlights extends Overlay
	{
		private static final Color FILL = new Color(PATH.getRed(), PATH.getGreen(), PATH.getBlue(), 28);
		private static final Color QUIET = new Color(PATH.getRed(), PATH.getGreen(), PATH.getBlue(), 90);
		private final Supplier<GuidanceModel> model;
		private final Client client;

		Highlights(Client client, Supplier<GuidanceModel> model)
		{
			this.client = client;
			this.model = model;
			setPosition(OverlayPosition.DYNAMIC);
			setLayer(OverlayLayer.ABOVE_SCENE);
		}

		@Override
		public Dimension render(Graphics2D g)
		{
			GuidanceModel m = model.get();
			if (m == null || !m.isActive())
			{
				return null;
			}
			// A patch is one quiet ground outline (never every plant model): clear while walking to it, faint once there.
			for (int[] f : m.getFootprints())
			{
				drawFootprint(g, f, m.isQuiet());
			}
			for (net.runelite.api.TileObject o : m.getObjects())
			{
				draw(g, o.getClickbox());
			}
			for (net.runelite.api.NPC n : m.getNpcs())
			{
				draw(g, n.getConvexHull());
			}
			return null;
		}

		private void drawFootprint(Graphics2D g, int[] f, boolean quiet)
		{
			net.runelite.api.WorldView wv = client.getTopLevelWorldView();
			if (wv == null || f[4] != wv.getPlane())
			{
				return;
			}
			LocalPoint corner = LocalPoint.fromWorld(wv, f[0], f[1]);
			if (corner == null)
			{
				return;
			}
			int w = f[2] - f[0] + 1;
			int h = f[3] - f[1] + 1;
			LocalPoint centre = new LocalPoint(corner.getX() + (w - 1) * 64, corner.getY() + (h - 1) * 64, wv);
			Polygon poly = Perspective.getCanvasTileAreaPoly(client, centre, w, h, f[4], 0);
			if (poly == null)
			{
				return;
			}
			if (quiet)
			{
				g.setStroke(TILE_LINE);
				g.setColor(QUIET);
				g.draw(poly);
				return;
			}
			g.setStroke(TILE_EDGE);
			g.setColor(new Color(PATH_EDGE.getRed(), PATH_EDGE.getGreen(), PATH_EDGE.getBlue(), 110));
			g.draw(poly);
			g.setStroke(TILE_LINE);
			g.setColor(PATH);
			g.draw(poly);
		}

		private static void draw(Graphics2D g, java.awt.Shape s)
		{
			if (s == null)
			{
				return;
			}
			g.setColor(FILL);
			g.fill(s);
			g.setStroke(HULL_EDGE);
			g.setColor(PATH_EDGE);
			g.draw(s);
			g.setStroke(HULL_LINE);
			g.setColor(PATH);
			g.draw(s);
		}
	}

	/** The spell the current step casts (Fertile Soil), outlined in its spellbook slot while the spellbook shows it. */
	static final class Spell extends Overlay
	{
		private final Client client;
		private final Supplier<GuidanceModel> model;

		Spell(Client client, Supplier<GuidanceModel> model)
		{
			this.client = client;
			this.model = model;
			setPosition(OverlayPosition.DYNAMIC);
			setLayer(OverlayLayer.ABOVE_WIDGETS);
		}

		@Override
		public Dimension render(Graphics2D g)
		{
			GuidanceModel m = model.get();
			if (m == null || !m.isActive() || m.getSpellWidget() < 0)
			{
				return null;
			}
			net.runelite.api.widgets.Widget w = client.getWidget(m.getSpellWidget());
			Rectangle r = w == null || w.isHidden() ? null : w.getBounds();
			if (r == null || r.width <= 0)
			{
				return null;
			}
			g.setStroke(HULL_LINE);
			g.setColor(PATH);
			g.drawRoundRect(r.x - 1, r.y - 1, r.width + 1, r.height + 1, 6, 6);
			return null;
		}
	}
}
