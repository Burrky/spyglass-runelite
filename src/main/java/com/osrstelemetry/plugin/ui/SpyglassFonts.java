package com.osrstelemetry.plugin.ui;

import java.awt.Font;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.ui.FontManager;

/**
 * The sidebar's text roles (pass 30): RuneLite's own RuneScape fonts at the player's text size. Every Spyglass page takes
 * its fonts from here, never from {@link FontManager} directly, so one setting (Standard, Large, Extra large) reaches every
 * tab. Overlays keep their own sizes.
 *
 * <ul>
 *   <li>{@link #bold()}: titles, primary values, the current action.</li>
 *   <li>{@link #regular()}: readable supporting text and statuses.</li>
 *   <li>{@link #small()}: secondary labels and metadata.</li>
 *   <li>{@link #smallBold()}: small section headings ("STILL NEEDED").</li>
 * </ul>
 */
final class SpyglassFonts
{
	private SpyglassFonts()
	{
	}

	private static float scale = 1f;
	private static final Map<String, Font> CACHE = new HashMap<>();

	/** Sets the text size (EDT). Returns true when it changed. */
	static boolean setScale(float s)
	{
		float next = s <= 0 ? 1f : s;
		if (next == scale)
		{
			return false;
		}
		scale = next;
		CACHE.clear();
		return true;
	}

	static float scale()
	{
		return scale;
	}

	static Font small()
	{
		return scaled("small", FontManager.getRunescapeSmallFont());
	}

	static Font regular()
	{
		return scaled("regular", FontManager.getRunescapeFont());
	}

	static Font bold()
	{
		return scaled("bold", FontManager.getRunescapeBoldFont());
	}

	static Font smallBold()
	{
		Font f = CACHE.get("smallBold");
		if (f == null)
		{
			f = small().deriveFont(Font.BOLD);
			CACHE.put("smallBold", f);
		}
		return f;
	}

	/** A headline figure (the Kingdom's approval): RuneScape bold at one and a half times the text size (pass 31). */
	static Font large()
	{
		Font f = CACHE.get("large");
		if (f == null)
		{
			Font b = bold();
			f = b.deriveFont((float) Math.round(b.getSize2D() * 1.5f));
			CACHE.put("large", f);
		}
		return f;
	}

	/** RuneLite's default UI font at the text size (rare: the client's own small markers). */
	static Font defaultFont()
	{
		return scaled("default", FontManager.getDefaultFont());
	}

	/** {@code px} pixels at the text size, rounded (spacing and row heights that hold text). */
	static int px(int px)
	{
		return Math.round(px * scale);
	}

	private static Font scaled(String key, Font base)
	{
		if (scale == 1f)
		{
			return base;
		}
		Font f = CACHE.get(key);
		if (f == null)
		{
			f = base.deriveFont(base.getSize2D() * scale);
			CACHE.put(key, f);
		}
		return f;
	}
}
