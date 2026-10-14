package com.osrstelemetry.plugin.skills.woodcutting;

import java.util.Locale;
import net.runelite.api.gameval.ItemID;

/**
 * Woodcutting METHODS: the KIND OF TREE being cut, identified by what it
 * actually produced (every log belongs to exactly one tree kind here) and
 * hinted by the tree the player clicked. The session stays SKILLING /
 * woodcutting whatever the tree: a tree change is a method change inside
 * the same session, never a new one.
 *
 * <ul>
 * <li>{@code product}: the primary resource (the log; Hollow trees: bark;
 * Sulliuscep: mushrooms, with fungus and the rare cap as further primary
 * outputs).</li>
 * <li>{@code clickNames}: lower-case words of the tree's own name as the
 * game shows it on "Chop down" ("yew", "magic tree") -- a HINT only.</li>
 * <li>{@code infernalBurns}: whether an infernal axe can burn this tree's
 * product as it is cut (OSRS Wiki, Infernal axe: never bruma roots,
 * sulliuscep or celastrus; blisterwood and hollow-tree bark are not
 * Firemaking logs). Only gates the OWNERSHIP evidence Spyglass gives the
 * session engine; an auto-burn is still only ever booked from its own
 * causal evidence.</li>
 * <li>{@code focus}: whether the CURRENT TREE section makes sense (a
 * sulliuscep moves to the next spot after every cut, so it never does).</li>
 * </ul>
 *
 * Deferred, each for a reason: bruma roots (Wintertodt -- a structured
 * activity, never a Woodcutting resource here); celastrus (farming patch,
 * bark harvested, a different loop); ent trunks (Slayer / combat context);
 * canoes and quest-only trees (dramen, Kharazi jungle, etc.: no stable
 * production loop).
 */
public enum WoodcuttingMethod
{
	NORMAL("Normal trees", "LOGS CUT", true, true, new int[]{ItemID.LOGS},
		"tree", "dead tree", "evergreen", "evergreen tree", "jungle tree", "dying tree", "burnt tree", "dead tree stump"),
	OAK("Oak", "OAK LOGS", true, true, new int[]{ItemID.OAK_LOGS}, "oak", "oak tree"),
	WILLOW("Willow", "WILLOW LOGS", true, true, new int[]{ItemID.WILLOW_LOGS}, "willow", "willow tree"),
	TEAK("Teak", "TEAK LOGS", true, true, new int[]{ItemID.TEAK_LOGS}, "teak", "teak tree"),
	JUNIPER("Juniper", "JUNIPER LOGS", true, true, new int[]{ItemID.JUNIPER_LOGS}, "juniper", "juniper tree"),
	MAPLE("Maple", "MAPLE LOGS", true, true, new int[]{ItemID.MAPLE_LOGS}, "maple", "maple tree"),
	MAHOGANY("Mahogany", "MAHOGANY LOGS", true, true, new int[]{ItemID.MAHOGANY_LOGS}, "mahogany", "mahogany tree"),
	ARCTIC_PINE("Arctic pine", "ARCTIC PINE LOGS", true, true, new int[]{ItemID.ARCTIC_PINE_LOG}, "arctic pine", "arctic pine tree"),
	ACHEY("Achey", "ACHEY LOGS", true, true, new int[]{ItemID.ACHEY_TREE_LOGS}, "achey", "achey tree"),
	HOLLOW("Hollow trees", "BARK", false, true, new int[]{ItemID.HOLLOW_BARK}, "hollow tree"),
	YEW("Yew", "YEW LOGS", true, true, new int[]{ItemID.YEW_LOGS}, "yew", "yew tree"),
	BLISTERWOOD("Blisterwood", "BLISTERWOOD LOGS", false, true, new int[]{ItemID.BLISTERWOOD_LOGS}, "blisterwood", "blisterwood tree"),
	MAGIC("Magic", "MAGIC LOGS", true, true, new int[]{ItemID.MAGIC_LOGS}, "magic tree", "magic"),
	REDWOOD("Redwood", "REDWOOD LOGS", true, true, new int[]{ItemID.REDWOOD_LOGS}, "redwood", "redwood tree"),
	/** Fossil Island: each cut moves the next sulliuscep along a fixed route (no CURRENT TREE). */
	SULLIUSCEP("Sulliuscep", "MUSHROOMS", false, false,
		new int[]{ItemID.BITTERCAP_MUSHROOM, ItemID.MORTMYREMUSHROOM, ItemID.FOSSIL_SULLIUSCEP_CAP}, "sulliuscep"),
	// Sailing (2025-26) trees.
	JATOBA("Jatoba", "JATOBA LOGS", true, true, new int[]{ItemID.JATOBA_LOGS}, "jatoba", "jatoba tree"),
	CAMPHOR("Camphor", "CAMPHOR LOGS", true, true, new int[]{ItemID.CAMPHOR_LOGS}, "camphor", "camphor tree"),
	IRONWOOD("Ironwood", "IRONWOOD LOGS", true, true, new int[]{ItemID.IRONWOOD_LOGS}, "ironwood", "ironwood tree"),
	ROSEWOOD("Rosewood", "ROSEWOOD LOGS", true, true, new int[]{ItemID.ROSEWOOD_LOGS}, "rosewood", "rosewood tree");

	private final String label;
	private final String productionCaption;
	private final boolean infernalBurns;
	private final boolean focus;
	private final int[] products;
	private final String[] clickNames;

	WoodcuttingMethod(String label, String productionCaption, boolean infernalBurns, boolean focus, int[] products, String... clickNames)
	{
		this.label = label;
		this.productionCaption = productionCaption;
		this.infernalBurns = infernalBurns;
		this.focus = focus;
		this.products = products;
		this.clickNames = clickNames;
	}

	public String getKey()
	{
		return name().toLowerCase(Locale.ROOT);
	}

	public String getLabel()
	{
		return label;
	}

	public String getProductionCaption()
	{
		return productionCaption;
	}

	public boolean isInfernalBurns()
	{
		return infernalBurns;
	}

	public boolean hasFocus()
	{
		return focus;
	}

	/** The primary products, the main one (the log) first. */
	public int[] getProducts()
	{
		return products.clone();
	}

	/** The main product (what an auto-burn of this tree burned). */
	public int getMainProduct()
	{
		return products[0];
	}

	public static WoodcuttingMethod fromKey(String key)
	{
		if (key == null)
		{
			return null;
		}
		for (WoodcuttingMethod m : values())
		{
			if (m.getKey().equals(key))
			{
				return m;
			}
		}
		return null;
	}

	/** The tree whose primary product this is, or null. */
	public static WoodcuttingMethod producing(int itemId)
	{
		for (WoodcuttingMethod m : values())
		{
			for (int p : m.products)
			{
				if (p == itemId)
				{
					return m;
				}
			}
		}
		return null;
	}

	/**
	 * The tree a clicked object's name names ("Yew tree", "Magic tree",
	 * "Redwood tree", "Tree"), or null. Exact names first, then the most
	 * specific word it contains ("Oak tree (sapling-grown)"); a generic "tree"
	 * only matches as the whole name.
	 */
	public static WoodcuttingMethod forTreeName(String objectName)
	{
		if (objectName == null)
		{
			return null;
		}
		String n = objectName.trim().toLowerCase(Locale.ROOT);
		if (n.isEmpty())
		{
			return null;
		}
		for (WoodcuttingMethod m : values())
		{
			for (String c : m.clickNames)
			{
				if (c.equals(n))
				{
					return m;
				}
			}
		}
		WoodcuttingMethod best = null;
		int bestLength = 0;
		for (WoodcuttingMethod m : values())
		{
			if (m == NORMAL)
			{
				continue;
			}
			for (String c : m.clickNames)
			{
				if (c.length() > bestLength && containsWord(n, c))
				{
					best = m;
					bestLength = c.length();
				}
			}
		}
		return best;
	}

	private static boolean containsWord(String haystack, String word)
	{
		int i = haystack.indexOf(word);
		while (i >= 0)
		{
			boolean startOk = i == 0 || !Character.isLetter(haystack.charAt(i - 1));
			int end = i + word.length();
			boolean endOk = end == haystack.length() || !Character.isLetter(haystack.charAt(end));
			if (startOk && endOk)
			{
				return true;
			}
			i = haystack.indexOf(word, i + 1);
		}
		return false;
	}
}
