package com.osrstelemetry.plugin.wintertodt;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Wintertodt tools the player carries or wears, by the game's own item names
 * (ItemComposition names, read on the client thread): one semantic rule per
 * tool rather than a scattered id list, so every axe / knife / hammer variant
 * the game names that way counts. The crates inside the Doors of Dinh hand
 * out most tools (OSRS Wiki), so a missing one is a note, never a blocker.
 */
public enum WintertodtTools
{
	AXE("axe"),
	KNIFE("knife"),
	HAMMER("hammer"),
	TINDERBOX("tinderbox");

	private final String label;

	WintertodtTools(String label)
	{
		this.label = label;
	}

	public String getLabel()
	{
		return label;
	}

	/** The tool this item name is, or null. */
	public static WintertodtTools of(String itemName)
	{
		if (itemName == null)
		{
			return null;
		}
		String n = itemName.toLowerCase(Locale.ROOT).trim();
		if (n.endsWith(" axe") && !n.contains("battle"))
		{
			// "Bronze axe" .. "Dragon axe", "Infernal axe", "Crystal axe", "Dragon felling axe" -- never "Pickaxe" / "Battleaxe".
			return AXE;
		}
		if (n.equals("knife") || n.equals("fletching knife"))
		{
			return KNIFE;
		}
		if (n.equals("hammer") || n.startsWith("imcando hammer"))
		{
			return HAMMER;
		}
		if (n.equals("tinderbox") || n.startsWith("bruma torch"))
		{
			// The bruma torch lights braziers in place of a tinderbox (OSRS Wiki, Bruma torch).
			return TINDERBOX;
		}
		return null;
	}

	/** Tools present in these containers (item id -> quantity). */
	public static Set<WintertodtTools> in(Map<Integer, Long> items, IntFunction<String> names)
	{
		Set<WintertodtTools> out = EnumSet.noneOf(WintertodtTools.class);
		if (items == null)
		{
			return out;
		}
		for (Map.Entry<Integer, Long> e : items.entrySet())
		{
			if (e.getValue() == null || e.getValue() <= 0)
			{
				continue;
			}
			String name;
			try
			{
				name = names.apply(e.getKey());
			}
			catch (RuntimeException ex)
			{
				continue;
			}
			WintertodtTools t = of(name);
			if (t != null)
			{
				out.add(t);
			}
		}
		return out;
	}
}
