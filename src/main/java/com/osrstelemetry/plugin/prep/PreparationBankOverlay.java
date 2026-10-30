package com.osrstelemetry.plugin.prep;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Tier 1 in-bank presentation (canonical §8.4 b; §28.14: the item's sprite outlined in the guidance blue, not its slot): outlines the bank items
 * items a preparation still needs, with a small badge of how many are still to
 * withdraw. Drawing only: no widget is changed, nothing is clicked, the bank's
 * organisation never changes. Inactive (draws nothing) whenever the supplier
 * returns no highlights, i.e. no preparation is open or everything is carried.
 *
 * <p>"Find" on the run page only marks one need as focused: its slots get a
 * brighter, thicker outline here. Nothing is typed into the bank search, no
 * tab is switched and no script or menu action runs; if the item is not in
 * the bank view the player has open, the page says so instead.
 */
public final class PreparationBankOverlay extends WidgetItemOverlay
{
	/** The guidance blue (GuidanceOverlays.PATH): the item's own sprite outlined, never a slot square. */
	static final Color OUTLINE = new Color(0x1E, 0xB4, 0xFF);
	static final Color BADGE_BG = new Color(0, 0, 0, 170);
	/** The item the player asked to Find: a lighter blue. */
	static final Color FOCUS = new Color(0xBF, 0xEA, 0xFF);

	/** Outline images of an item's sprite (RuneLite's ItemManager.getItemOutline, cached there); null draws nothing. */
	public interface Outlines
	{
		java.awt.image.BufferedImage outline(int itemId, int quantity, Color color);
	}

	private Outlines outlines = (id, q, c) -> null;

	public PreparationBankOverlay withOutlines(Outlines o)
	{
		this.outlines = o == null ? (id, q, c) -> null : o;
		return this;
	}

	private final Supplier<Map<Integer, Long>> highlights;
	private final Supplier<Set<Integer>> focus;

	public PreparationBankOverlay(Supplier<Map<Integer, Long>> highlights)
	{
		this(highlights, Collections::emptySet);
	}

	/** @param focus item ids the player asked to Find (drawn brighter); empty for none */
	public PreparationBankOverlay(Supplier<Map<Integer, Long>> highlights, Supplier<Set<Integer>> focus)
	{
		this.highlights = highlights;
		this.focus = focus;
		showOnBank();
	}

	@Override
	public void renderItemOverlay(Graphics2D g, int itemId, WidgetItem item)
	{
		Map<Integer, Long> map = current();
		Long still = map.get(itemId);
		if (still == null || still <= 0 || item.getQuantity() <= 0)
		{
			return; // not needed, or a placeholder
		}
		Rectangle r = item.getCanvasBounds();
		if (r == null)
		{
			return;
		}
		boolean focused = focused().contains(itemId);
		java.awt.Shape clip = g.getClip();
		net.runelite.api.widgets.Widget container = item.getWidget();
		if (container != null && container.getBounds() != null)
		{
			g.clip(container.getBounds()); // a slot scrolled half out of the bank view is drawn only where it shows
		}
		java.awt.image.BufferedImage o = outlines.outline(itemId, item.getQuantity(), focused ? FOCUS : OUTLINE);
		if (o != null)
		{
			g.drawImage(o, r.x, r.y, null);
		}
		String text = "×" + still;
		Font font = FontManager.getRunescapeSmallFont();
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		int w = fm.stringWidth(text) + 4;
		int h = fm.getAscent() + 1;
		int x = r.x + r.width - w;
		int y = r.y + r.height - h;
		g.setColor(BADGE_BG);
		g.fillRect(x, y, w, h);
		g.setColor(OUTLINE);
		g.drawString(text, x + 2, y + fm.getAscent());
		g.setClip(clip);
	}

	Set<Integer> focused()
	{
		Set<Integer> f = focus == null ? null : focus.get();
		return f == null ? Collections.emptySet() : f;
	}

	Map<Integer, Long> current()
	{
		Map<Integer, Long> m = highlights == null ? null : highlights.get();
		return m == null ? Collections.emptyMap() : m;
	}
}
