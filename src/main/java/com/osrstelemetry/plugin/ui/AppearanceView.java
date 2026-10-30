package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.SidebarTextContrast;
import com.osrstelemetry.plugin.SidebarTextSize;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Home → Text size &amp; contrast (pass 30): the sidebar's text size (Standard, Large, Extra large) and contrast (Standard,
 * High contrast), applied at once to every Spyglass page (this one included, so the page is its own live preview), a sample
 * card showing the text roles, and Reset to defaults. Saved in RuneLite's configuration, where the same two settings also
 * appear.
 */
final class AppearanceView extends HomeDetailPage
{
	private java.util.function.Supplier<Appearance> current = Appearance::current;
	private java.util.function.Consumer<Appearance> choose = a -> { };

	void attach(java.util.function.Supplier<Appearance> current, java.util.function.Consumer<Appearance> choose)
	{
		this.current = current == null ? Appearance::current : current;
		this.choose = choose == null ? a -> { } : choose;
		invalidateSignature();
	}

	@Override
	String signature(long now)
	{
		return current.get() + "|" + SpyglassFonts.scale() + SpyglassTheme.isHighContrast();
	}

	private void pick(Appearance a)
	{
		choose.accept(a);
		invalidateSignature();
		render(System.currentTimeMillis());
	}

	@Override
	void build(long now)
	{
		Appearance a = current.get();
		List<JComponent> size = new ArrayList<>();
		JPanel sizes = new JPanel(new ReflowGridLayout(3, 3, 3));
		sizes.setOpaque(false);
		for (SidebarTextSize s : SidebarTextSize.values())
		{
			RunTypeButton b = new RunTypeButton(s.getLabel(), () -> pick(current.get().withSize(s)));
			b.setName("appearance.size." + s.name());
			b.setSelected(a.getSize() == s);
			b.setToolTipText(s.getLabel() + " text on every Spyglass page");
			b.getAccessibleContext().setAccessibleName("Text size " + s.getLabel() + (a.getSize() == s ? ", selected" : ""));
			sizes.add(b);
		}
		size.add(sizes);
		add(card("TEXT SIZE", "Every Spyglass page in the sidebar. Pages wrap and scroll to fit; overlays in the game keep their size.", size, null));
		gap();

		List<JComponent> contrast = new ArrayList<>();
		JPanel contrasts = new JPanel(new ReflowGridLayout(2, 3, 3));
		contrasts.setOpaque(false);
		for (SidebarTextContrast c : SidebarTextContrast.values())
		{
			RunTypeButton b = new RunTypeButton(c.getLabel(), () -> pick(current.get().withContrast(c)));
			b.setName("appearance.contrast." + c.name());
			b.setSelected(a.getContrast() == c);
			b.getAccessibleContext().setAccessibleName("Text contrast " + c.getLabel() + (a.getContrast() == c ? ", selected" : ""));
			contrasts.add(b);
		}
		contrast.add(contrasts);
		add(card("TEXT CONTRAST", "High contrast brightens supporting text and colours.", contrast, null));
		gap();

		add(preview());
		gap();

		ActionButton reset = new ActionButton("Reset to defaults", ActionButton.Style.SECONDARY, () -> pick(Appearance.DEFAULT));
		reset.setName("appearance.reset");
		reset.setEnabled(!a.isDefault());
		reset.setToolTipText("Standard size, standard contrast");
		reset.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(reset);
		add(Box.createVerticalStrut(4));
		add(sentence("Also in RuneLite's Spyglass settings, under Text size & contrast.", SpyglassTheme.TEXT_SECONDARY));
	}

	/** A sample card in the pages' own roles: the primary value, readable progress, a quiet label and the accents. */
	private JComponent preview()
	{
		RaisedTile tile = new RaisedTile(null, 6, 8);
		tile.setRail(SpyglassTheme.ACCENT_GOLD);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		body.add(left(text("PREVIEW", SpyglassTheme.TILE_TEXT_QUIET, false, SpyglassFonts.smallBold())));
		body.add(left(new FitRow(text("Herb run", SpyglassTheme.TEXT_PRIMARY, false, SpyglassFonts.bold()),
			text("3 of 9 done", SpyglassTheme.TEXT_PRIMARY, true, SpyglassFonts.bold()))));
		body.add(left(text("Harvest and replant", SpyglassTheme.ACCENT_GOLD, false, SpyglassFonts.regular())));
		body.add(left(new FitRow(text("Ready in ~1h 40m", SpyglassTheme.TEXT_SECONDARY, false, SpyglassFonts.regular()),
			text("Diseased", SpyglassTheme.FARM_EMBER, true, SpyglassFonts.regular()))));
		body.add(left(text("Seen 10m ago · Falador", SpyglassTheme.TILE_TEXT_QUIET, false, SpyglassFonts.small())));
		body.add(left(text("Not read yet", SpyglassTheme.TEXT_MUTED, false, SpyglassFonts.small())));
		tile.add(body, BorderLayout.CENTER);
		tile.getAccessibleContext().setAccessibleName("Preview of the text size and contrast");
		return growing(tile);
	}

	private static javax.swing.JLabel text(String s, java.awt.Color c, boolean oneLine, java.awt.Font f)
	{
		javax.swing.JLabel l = oneLine ? new javax.swing.JLabel(s) : new WrappingHtmlLabel(s, false);
		l.setFont(f);
		l.setForeground(c);
		return l;
	}

	private static <T extends JComponent> T left(T c)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}
}
