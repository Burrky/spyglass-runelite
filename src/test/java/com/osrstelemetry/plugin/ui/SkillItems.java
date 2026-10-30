package com.osrstelemetry.plugin.ui;

import java.awt.Component;
import java.awt.Container;

/** Test helper: what a skill companion's sprite grid shows ({@link SkillItemGrid#describe()}), found by name. */
final class SkillItems
{
	private SkillItems()
	{
	}

	static SkillItemGrid find(Container root, String name)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof SkillItemGrid && name.equals(c.getName()))
			{
				return (SkillItemGrid) c;
			}
			if (c instanceof Container)
			{
				SkillItemGrid g = find((Container) c, name);
				if (g != null)
				{
					return g;
				}
			}
		}
		return null;
	}

	/** "MINED|BANKED;440:1,103|742;453:94|0" -- or null when no such grid is shown. */
	static String grid(Container root, String name)
	{
		SkillItemGrid g = find(root, name);
		return g == null ? null : g.describe();
	}

	/** The production's main sprites. */
	static String production(Container root)
	{
		return grid(root, CurrentSessionView.SC_PRODUCTION_ITEMS_PREFIX + 0);
	}

	/** The i-th group after the main sprites (by-products, other methods' output). */
	static String byproduct(Container root, int i)
	{
		return grid(root, CurrentSessionView.SC_BYPRODUCT_PREFIX + i);
	}

	/** The i-th group's title, or null. */
	static String title(Container root, String prefix, int i)
	{
		Component c = named(root, prefix + i + CurrentSessionView.SC_ITEMS_TITLE_SUFFIX);
		return c == null ? null : ((javax.swing.JLabel) c).getText().replaceAll("<[^>]*>", "").trim();
	}

	private static Component named(Container root, String name)
	{
		for (Component c : root.getComponents())
		{
			if (name.equals(c.getName()))
			{
				return c;
			}
			if (c instanceof Container)
			{
				Component k = named((Container) c, name);
				if (k != null)
				{
					return k;
				}
			}
		}
		return null;
	}
}
