package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.SidebarTextContrast;
import com.osrstelemetry.plugin.SidebarTextSize;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.swing.JComponent;
import net.runelite.client.ui.FontManager;

/**
 * The sidebar's text size and contrast (pass 30): one immutable value, read from and written to RuneLite's configuration
 * ({@code textSize}, {@code textContrast}) through a {@link Store}, applied to the shared text roles
 * ({@link SpyglassFonts}, {@link SpyglassTheme}) and to every component already on screen.
 */
final class Appearance
{
	static final Appearance DEFAULT = new Appearance(SidebarTextSize.STANDARD, SidebarTextContrast.STANDARD);

	/** Where the choice is kept: the plugin's configuration (a map in tests). */
	interface Store
	{
		Appearance read();

		void write(Appearance a);
	}

	private final SidebarTextSize size;
	private final SidebarTextContrast contrast;

	Appearance(SidebarTextSize size, SidebarTextContrast contrast)
	{
		this.size = size == null ? SidebarTextSize.STANDARD : size;
		this.contrast = contrast == null ? SidebarTextContrast.STANDARD : contrast;
	}

	SidebarTextSize getSize()
	{
		return size;
	}

	SidebarTextContrast getContrast()
	{
		return contrast;
	}

	Appearance withSize(SidebarTextSize s)
	{
		return new Appearance(s, contrast);
	}

	Appearance withContrast(SidebarTextContrast c)
	{
		return new Appearance(size, c);
	}

	boolean isDefault()
	{
		return equals(DEFAULT);
	}

	@Override
	public boolean equals(Object o)
	{
		return o instanceof Appearance && ((Appearance) o).size == size && ((Appearance) o).contrast == contrast;
	}

	@Override
	public int hashCode()
	{
		return size.hashCode() * 31 + contrast.hashCode();
	}

	@Override
	public String toString()
	{
		return size.getLabel() + " · " + contrast.getLabel();
	}

	/** The roles as they are now (applied last). */
	static Appearance current()
	{
		SidebarTextSize s = SidebarTextSize.STANDARD;
		for (SidebarTextSize t : SidebarTextSize.values())
		{
			if (t.getScale() == SpyglassFonts.scale())
			{
				s = t;
			}
		}
		return new Appearance(s, SpyglassTheme.isHighContrast() ? SidebarTextContrast.HIGH : SidebarTextContrast.STANDARD);
	}

	/** Sets the shared roles only (before any component exists: the panel is then built with them). EDT. */
	static boolean applyRoles(Appearance a)
	{
		boolean changed = SpyglassFonts.setScale(a.size.getScale());
		changed |= SpyglassTheme.apply(a.contrast == SidebarTextContrast.HIGH);
		return changed;
	}

	/**
	 * Sets the roles and brings every existing component under {@code root} to them: RuneScape fonts re-sized by the ratio,
	 * text colours that are a role moved to that role's new value, and heights fixed when a row was built (a row's maximum
	 * height) grown or shrunk by what its content gained or lost. Views rebuild what they cache on their next render.
	 * Returns false (and touches nothing) when nothing changed. EDT.
	 */
	static boolean apply(Container root, Appearance a)
	{
		float oldScale = SpyglassFonts.scale();
		Color[] oldRoles = SpyglassTheme.roles();
		if (!applyRoles(a))
		{
			return false;
		}
		float ratio = SpyglassFonts.scale() / oldScale;
		Color[] newRoles = SpyglassTheme.roles();
		Map<JComponent, Integer> frozen = new IdentityHashMap<>();
		collectFrozen(root, frozen);
		restyle(root, ratio, oldRoles, newRoles);
		for (Map.Entry<JComponent, Integer> e : frozen.entrySet())
		{
			JComponent c = e.getKey();
			Dimension max = c.getMaximumSize();
			int grown = c.getPreferredSize().height - e.getValue();
			c.setMaximumSize(new Dimension(max.width, Math.max(0, max.height + grown)));
		}
		root.revalidate();
		root.repaint();
		return true;
	}

	/** Components whose maximum height was fixed from their content when built (full width, a finite height). */
	private static void collectFrozen(Component c, Map<JComponent, Integer> out)
	{
		if (c instanceof JComponent && c.isMaximumSizeSet())
		{
			Dimension max = c.getMaximumSize();
			if (max.width == Integer.MAX_VALUE && max.height < Short.MAX_VALUE)
			{
				out.put((JComponent) c, c.getPreferredSize().height);
			}
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				collectFrozen(k, out);
			}
		}
	}

	private static void restyle(Component c, float ratio, Color[] oldRoles, Color[] newRoles)
	{
		if (c.isFontSet() && ratio != 1f && isRuneScape(c.getFont()))
		{
			Font f = c.getFont();
			c.setFont(f.deriveFont(Math.max(1f, Math.round(f.getSize2D() * ratio * 4f) / 4f)));
		}
		if (c.isForegroundSet())
		{
			int rgb = c.getForeground().getRGB();
			for (int i = 0; i < oldRoles.length; i++)
			{
				if (oldRoles[i].getRGB() == rgb)
				{
					c.setForeground(newRoles[i]);
					break;
				}
			}
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				restyle(k, ratio, oldRoles, newRoles);
			}
		}
	}

	private static boolean isRuneScape(Font f)
	{
		if (f == null)
		{
			return false;
		}
		String family = f.getFamily();
		return family.equals(FontManager.getRunescapeFont().getFamily()) || family.equals(FontManager.getRunescapeSmallFont().getFamily())
			|| family.equals(FontManager.getRunescapeBoldFont().getFamily());
	}
}
