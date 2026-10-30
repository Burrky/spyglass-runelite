package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.SidebarTextContrast;
import com.osrstelemetry.plugin.SidebarTextSize;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Test;

/**
 * Pass 30: the sidebar's text size and contrast: chosen on the page (live: the roles and every component on screen change at
 * once), saved through the store (the plugin's configuration), read back after a restart, and reset to defaults. Default
 * secondary text is readable without High contrast.
 */
public class AppearanceTest
{
	@After
	public void restore() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> Appearance.applyRoles(Appearance.DEFAULT));
	}

	/** The plugin's configuration, as two values. */
	static final class Store implements Appearance.Store
	{
		Appearance saved = Appearance.DEFAULT;
		int writes;

		@Override
		public Appearance read()
		{
			return saved;
		}

		@Override
		public void write(Appearance a)
		{
			saved = a;
			writes++;
		}
	}

	static List<Component> all(Container c)
	{
		List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(all((Container) k));
			}
		}
		return out;
	}

	static Component named(Container c, String name)
	{
		for (Component k : all(c))
		{
			if (name.equals(k.getName()))
			{
				return k;
			}
		}
		return null;
	}

	@Test
	public void choosing_isSaved_appliedLive_readBackAfterRestart_andReset() throws Exception
	{
		Store store = new Store();
		SwingUtilities.invokeAndWait(() ->
		{
			AppearanceView page = new AppearanceView();
			JPanel host = new JPanel();
			JLabel existing = new JLabel("Supporting text");
			existing.setFont(SpyglassFonts.small());
			existing.setForeground(SpyglassTheme.TEXT_SECONDARY);
			JPanel frozenRow = new JPanel();
			frozenRow.add(new JLabel("row"));
			((JLabel) frozenRow.getComponent(0)).setFont(SpyglassFonts.regular());
			frozenRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, frozenRow.getPreferredSize().height));
			int frozenBefore = frozenRow.getMaximumSize().height;
			host.add(existing);
			host.add(frozenRow);
			host.add(page);
			page.attach(store::read, a ->
			{
				store.write(a);
				Appearance.apply(host, a);
			});
			page.render(0);
			assertFalse("defaults: nothing to reset", ((ActionButton) named(page, "appearance.reset")).isEnabled());
			float before = existing.getFont().getSize2D();

			((RunTypeButton) named(page, "appearance.size.EXTRA_LARGE")).press();
			((RunTypeButton) named(page, "appearance.contrast.HIGH")).press();
			assertEquals(new Appearance(SidebarTextSize.EXTRA_LARGE, SidebarTextContrast.HIGH), store.saved);
			assertEquals(1.5f, SpyglassFonts.scale(), 0f);
			assertTrue(SpyglassTheme.isHighContrast());
			assertEquals("a component already on screen grows with the text", before * 1.5f, existing.getFont().getSize2D(), 0.3f);
			assertEquals("and takes the role's new colour", SpyglassTheme.TEXT_SECONDARY, existing.getForeground());
			assertTrue("a row whose height was fixed when built grows with its text", frozenRow.getMaximumSize().height > frozenBefore);
			assertTrue(((RunTypeButton) named(page, "appearance.size.EXTRA_LARGE")).isSelected());
			assertTrue(((ActionButton) named(page, "appearance.reset")).isEnabled());

			// A restart: the roles start from the saved values.
			Appearance.applyRoles(Appearance.DEFAULT);
			assertEquals(1f, SpyglassFonts.scale(), 0f);
			Appearance.applyRoles(store.read());
			assertEquals(1.5f, SpyglassFonts.scale(), 0f);

			((ActionButton) named(page, "appearance.reset")).press();
			assertEquals(Appearance.DEFAULT, store.saved);
			assertEquals(1f, SpyglassFonts.scale(), 0f);
			assertFalse(SpyglassTheme.isHighContrast());
			assertEquals(before, existing.getFont().getSize2D(), 0.3f);
			assertEquals(frozenBefore, frozenRow.getMaximumSize().height);
		});
	}

	@Test
	public void defaultSecondaryText_isReadable_andHighContrastRaisesEveryRole() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			java.awt.Color face = SpyglassTheme.TILE_FACE;
			assertTrue("secondary ≥ 5.5:1 on a tile face", contrast(SpyglassTheme.TEXT_SECONDARY, face) >= 5.5);
			assertTrue("quiet labels ≥ 4.5:1", contrast(SpyglassTheme.TILE_TEXT_QUIET, face) >= 4.5);
			assertTrue("muted metadata ≥ 4.5:1", contrast(SpyglassTheme.TEXT_MUTED, face) >= 4.5);
			assertTrue("hierarchy kept: primary > secondary > quiet > muted", contrast(SpyglassTheme.TEXT_PRIMARY, face) > contrast(SpyglassTheme.TEXT_SECONDARY, face)
				&& contrast(SpyglassTheme.TEXT_SECONDARY, face) > contrast(SpyglassTheme.TILE_TEXT_QUIET, face)
				&& contrast(SpyglassTheme.TILE_TEXT_QUIET, face) > contrast(SpyglassTheme.TEXT_MUTED, face));
			java.awt.Color[] standard = SpyglassTheme.roles();
			SpyglassTheme.apply(true);
			java.awt.Color[] high = SpyglassTheme.roles();
			for (int i = 0; i < standard.length; i++)
			{
				assertNotEquals(standard[i], high[i]);
				assertTrue("role " + i + " is brighter on the face", contrast(high[i], face) > contrast(standard[i], face));
			}
			assertTrue(contrast(SpyglassTheme.TEXT_MUTED, face) >= 6.0);
		});
	}

	static double contrast(java.awt.Color a, java.awt.Color b)
	{
		double la = lum(a);
		double lb = lum(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double lum(java.awt.Color c)
	{
		double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
		for (int i = 0; i < 3; i++)
		{
			v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
		}
		return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
	}
}
