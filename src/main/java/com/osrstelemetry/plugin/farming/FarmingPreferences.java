package com.osrstelemetry.plugin.farming;

import java.util.EnumMap;
import java.util.Map;

/**
 * The player's standing Farming choices (Farming correction pass 2,
 * 2026-10-21). They inform preparation, the inventory plan and guidance;
 * what is recorded as done comes only from observation. Changing one during a
 * run updates the work still to do and never rewrites a finished patch.
 *
 * <h3>Verified mechanics (OSRS Wiki, checked 2026-10-21)</h3>
 * <ul>
 *   <li>Regular, fruit and hardwood trees: a grown tree is removed either by
 *   chopping it and digging up the stump with a spade, or by paying the
 *   patch's gardener 200 coins ("Regular trees, fruit trees and hardwoods can
 *   also be removed by paying 200 to the gardener instead", OSRS Wiki Farming,
 *   checked 2026-10-22). A redwood "can only be removed by paying Alexandra
 *   2,000" (same page). Calquat and spirit trees are dug up with a spade;
 *   celastrus and crystal trees are chopped down with an axe; no gardener
 *   removal is documented for those four, so they are cleared by hand.</li>
 *   <li>A grown fruit tree bears six fruit (one regrows every 40 minutes);
 *   picking them before the tree is removed keeps them. Paying with fruit
 *   still on the tree is not documented, so "pick fruit first" is the
 *   default.</li>
 *   <li>Protection is offered where a gardener takes payment
 *   ({@link FarmingRunType#offersProtection}); it is a plan, never evidence a
 *   patch is protected.</li>
 * </ul>
 * Facts the client can see (carried equipment, container variants, tool
 * leprechaun storage) are never preferences.
 */
public final class FarmingPreferences
{
	/**
	 * How runs travel (Farming settings): the fastest usable journey, or a journey through the player's house wherever one
	 * the game has shown them (a portal, a nexus destination, a jewellery box, a mounted item, a fairy ring or spirit tree in
	 * the house), with its own way in, is a reasonable alternative to the fastest.
	 */
	public enum Travel
	{
		FASTEST("Fastest journey"),
		HOUSE("Prefer my house"),
		/**
		 * Pass 32: plan only with what the player's setup already allows: the current spellbook (never a journey whose
		 * spell, or any spell on the way, is on another book), owned teleport items (carried or in storage: preparation
		 * still lists the withdrawal) and confirmed house facilities. With none, the stop says so; nothing falls back to a
		 * spell on another book.
		 */
		CURRENT("Use current setup");

		private final String label;

		Travel(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	/** How a grown tree is cleared before replanting. */
	public enum TreeClearing
	{
		/** Pay the gardener 200 coins (regular and fruit trees only). */
		PAY("Pay the gardener"),
		/** Chop the tree, then dig up the stump with a spade. */
		CHOP("Chop and dig up");

		private final String label;

		TreeClearing(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	/** Coins a tree, fruit tree or hardwood gardener charges to remove a grown tree (OSRS Wiki Farming). */
	public static final int REMOVAL_COINS = 200;
	/** Coins Alexandra charges to remove a redwood, the only way to clear it (OSRS Wiki Farming). */
	public static final int REDWOOD_REMOVAL_COINS = 2000;

	/** What the gardener charges to remove this family's grown tree, or 0 when no paid removal is documented. */
	public static int removalCoins(FarmingPatchType type)
	{
		switch (type)
		{
			case TREE:
			case FRUIT_TREE:
			case HARDWOOD_TREE:
				return REMOVAL_COINS;
			case REDWOOD:
				return REDWOOD_REMOVAL_COINS;
			default:
				return 0;
		}
	}

	/** Field names are the saved JSON (RuneLite profile config); missing fields read as the defaults. */
	private Map<FarmingRunType, Boolean> protect = new EnumMap<>(FarmingRunType.class);
	private TreeClearing treeClearing = TreeClearing.PAY;
	private Boolean pickFruitFirst = Boolean.TRUE;
	private Boolean includeAllotments = Boolean.TRUE;
	private Boolean includeFlowers = Boolean.FALSE;
	/** Compost bins the player chose to include in runs (canonical §28.15); seeing a bin never adds it. */
	private java.util.Set<String> runBins = new java.util.TreeSet<>();
	/** What to make of each bin's compost ({@link CompostBin.Upgrade} names); absent: take it as it rotted. */
	private Map<String, String> binUpgrades = new java.util.TreeMap<>();
	/** {@link Travel} name; absent: the fastest journey. */
	private String travel;
	/**
	 * Pass 31: the compost to use on each patch type ({@link FarmingPatchType} name → {@link FarmingPlanDraft.Compost} name;
	 * allotments and flowers apart). A plan, never an observation: choosing one never marks a patch as composted. Null: never
	 * set on this profile (filled once from the open run's choices, so a choice made before this pass is kept).
	 */
	private Map<String, String> compostByPatch;

	public static FarmingPreferences defaults()
	{
		return new FarmingPreferences();
	}

	public FarmingPreferences copy()
	{
		FarmingPreferences p = new FarmingPreferences();
		p.protect = new EnumMap<>(FarmingRunType.class);
		if (protect != null)
		{
			p.protect.putAll(protect);
		}
		p.treeClearing = getTreeClearing();
		p.pickFruitFirst = isPickFruitFirst();
		p.includeAllotments = isIncludeAllotments();
		p.includeFlowers = isIncludeFlowers();
		p.runBins = new java.util.TreeSet<>(getRunBins());
		p.binUpgrades = new java.util.TreeMap<>(binUpgrades == null ? java.util.Collections.emptyMap() : binUpgrades);
		p.travel = travel;
		p.compostByPatch = compostByPatch == null ? null : new java.util.TreeMap<>(compostByPatch);
		return p;
	}

	/** The compost chosen for this patch type (No compost when none chosen). */
	public FarmingPlanDraft.Compost compostFor(FarmingPatchType type)
	{
		String c = compostByPatch == null || type == null ? null : compostByPatch.get(type.name());
		try
		{
			return c == null ? FarmingPlanDraft.Compost.NONE : FarmingPlanDraft.Compost.valueOf(c);
		}
		catch (IllegalArgumentException e)
		{
			return FarmingPlanDraft.Compost.NONE;
		}
	}

	/** Whether a compost choice was ever saved on this profile (the one-time carry-over from a run's choices). */
	public boolean isCompostChosen()
	{
		return compostByPatch != null;
	}

	public FarmingPreferences withCompost(FarmingPatchType type, FarmingPlanDraft.Compost choice)
	{
		FarmingPreferences p = copy();
		if (p.compostByPatch == null)
		{
			p.compostByPatch = new java.util.TreeMap<>();
		}
		if (type != null)
		{
			p.compostByPatch.put(type.name(), (choice == null ? FarmingPlanDraft.Compost.NONE : choice).name());
		}
		return p;
	}

	/** Several at once (the carry-over); an empty map still marks the choices as made. */
	public FarmingPreferences withCompost(Map<FarmingPatchType, FarmingPlanDraft.Compost> choices)
	{
		FarmingPreferences p = copy();
		if (p.compostByPatch == null)
		{
			p.compostByPatch = new java.util.TreeMap<>();
		}
		for (Map.Entry<FarmingPatchType, FarmingPlanDraft.Compost> e : choices.entrySet())
		{
			p.compostByPatch.put(e.getKey().name(), e.getValue().name());
		}
		return p;
	}

	public Travel getTravel()
	{
		try
		{
			return travel == null ? Travel.FASTEST : Travel.valueOf(travel);
		}
		catch (IllegalArgumentException e)
		{
			return Travel.FASTEST;
		}
	}

	public FarmingPreferences withTravel(Travel t)
	{
		FarmingPreferences p = copy();
		p.travel = t == null || t == Travel.FASTEST ? null : t.name();
		return p;
	}

	/** Only for types where a gardener takes payment; false otherwise. */
	public boolean isProtect(FarmingRunType type)
	{
		return type != null && type.offersProtection() && protect != null && Boolean.TRUE.equals(protect.get(type));
	}

	public FarmingPreferences withProtect(FarmingRunType type, boolean on)
	{
		FarmingPreferences p = copy();
		if (type != null && type.offersProtection())
		{
			p.protect.put(type, on);
		}
		return p;
	}

	public TreeClearing getTreeClearing()
	{
		return treeClearing == null ? TreeClearing.PAY : treeClearing;
	}

	public FarmingPreferences withTreeClearing(TreeClearing c)
	{
		FarmingPreferences p = copy();
		p.treeClearing = c == null ? TreeClearing.PAY : c;
		return p;
	}

	/**
	 * How this patch type's grown tree is cleared: the preference where both ways exist, payment where it is the only way
	 * (redwood), otherwise by hand.
	 */
	public TreeClearing clearingFor(FarmingPatchType type)
	{
		if (type == FarmingPatchType.REDWOOD)
		{
			return TreeClearing.PAY;
		}
		return paidRemovalOffered(type) ? getTreeClearing() : TreeClearing.CHOP;
	}

	public boolean isPickFruitFirst()
	{
		return pickFruitFirst == null || pickFruitFirst;
	}

	public FarmingPreferences withPickFruitFirst(boolean on)
	{
		FarmingPreferences p = copy();
		p.pickFruitFirst = on;
		return p;
	}

	public boolean isIncludeAllotments()
	{
		return includeAllotments == null || includeAllotments;
	}

	public boolean isIncludeFlowers()
	{
		return includeFlowers != null && includeFlowers;
	}

	public FarmingPreferences withIncludeAllotments(boolean on)
	{
		FarmingPreferences p = copy();
		p.includeAllotments = on;
		return p;
	}

	/** The bins included in runs (only catalogued ones). */
	public java.util.Set<String> getRunBins()
	{
		java.util.Set<String> out = new java.util.TreeSet<>();
		if (runBins != null)
		{
			for (String b : runBins)
			{
				if (CompostBin.def(b) != null)
				{
					out.add(b);
				}
			}
		}
		return java.util.Collections.unmodifiableSet(out);
	}

	public boolean isRunBin(String binId)
	{
		return getRunBins().contains(binId);
	}

	public FarmingPreferences withRunBin(String binId, boolean on)
	{
		FarmingPreferences p = copy();
		if (CompostBin.def(binId) != null)
		{
			if (on)
			{
				p.runBins.add(binId);
			}
			else
			{
				p.runBins.remove(binId);
			}
		}
		return p;
	}

	public CompostBin.Upgrade binUpgrade(String binId)
	{
		String u = binUpgrades == null ? null : binUpgrades.get(binId);
		try
		{
			return u == null ? CompostBin.Upgrade.NONE : CompostBin.Upgrade.valueOf(u);
		}
		catch (IllegalArgumentException e)
		{
			return CompostBin.Upgrade.NONE;
		}
	}

	public FarmingPreferences withBinUpgrade(String binId, CompostBin.Upgrade upgrade)
	{
		FarmingPreferences p = copy();
		if (CompostBin.def(binId) != null)
		{
			if (upgrade == null || upgrade == CompostBin.Upgrade.NONE)
			{
				p.binUpgrades.remove(binId);
			}
			else
			{
				p.binUpgrades.put(binId, upgrade.name());
			}
		}
		return p;
	}

	public FarmingPreferences withIncludeFlowers(boolean on)
	{
		FarmingPreferences p = copy();
		p.includeFlowers = on;
		return p;
	}

	/** Families where the player chooses between paying the gardener and clearing by hand (regular, fruit, hardwood). */
	public static boolean paidRemovalOffered(FarmingPatchType type)
	{
		return type == FarmingPatchType.TREE || type == FarmingPatchType.FRUIT_TREE || type == FarmingPatchType.HARDWOOD_TREE;
	}

	/** Patch types whose grown plant must be cleared (chopped and dug up, or paid for) before a new one goes in. */
	public static boolean needsClearing(FarmingPatchType type)
	{
		switch (type)
		{
			case TREE:
			case FRUIT_TREE:
			case HARDWOOD_TREE:
			case REDWOOD:
			case CALQUAT:
			case CELASTRUS:
			case SPIRIT_TREE:
			case CRYSTAL_TREE:
				return true;
			default:
				return false;
		}
	}

	/** Patch types that can be chopped for logs (an axe is needed when clearing by hand). */
	public static boolean choppable(FarmingPatchType type)
	{
		switch (type)
		{
			case TREE:
			case FRUIT_TREE:
			case HARDWOOD_TREE:
			case REDWOOD:
			case CELASTRUS:
			case CRYSTAL_TREE:
				return true;
			default:
				return false;
		}
	}

	/**
	 * How a grown plant of this family is cleared by hand (OSRS Wiki, checked 2026-10-21/22): trees are chopped and the stump
	 * dug up; a celastrus is cut down and cleared with a spade; a crystal tree is chopped with an axe; a calquat and a spirit
	 * tree are dug up with a spade (a spirit tree can't be cut down). Null for a redwood (only Alexandra's paid removal).
	 */
	public static String manualClearing(FarmingPatchType type)
	{
		switch (type)
		{
			case TREE:
			case FRUIT_TREE:
			case HARDWOOD_TREE:
				return "chop the tree, then dig up the stump";
			case CELASTRUS:
				return "cut the tree down, then clear it with a spade";
			case CRYSTAL_TREE:
				return "chop the tree down with an axe";
			case CALQUAT:
			case SPIRIT_TREE:
				return "dig up the tree with a spade";
			default:
				return null; // redwood: paid removal only
		}
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof FarmingPreferences))
		{
			return false;
		}
		FarmingPreferences p = (FarmingPreferences) o;
		for (FarmingRunType t : FarmingRunType.values())
		{
			if (isProtect(t) != p.isProtect(t))
			{
				return false;
			}
		}
		return getTreeClearing() == p.getTreeClearing() && isPickFruitFirst() == p.isPickFruitFirst()
			&& isIncludeAllotments() == p.isIncludeAllotments() && isIncludeFlowers() == p.isIncludeFlowers()
			&& getRunBins().equals(p.getRunBins()) && upgradesEqual(p) && getTravel() == p.getTravel() && compostEqual(p);
	}

	private boolean compostEqual(FarmingPreferences p)
	{
		if (isCompostChosen() != p.isCompostChosen())
		{
			return false;
		}
		for (FarmingPatchType t : FarmingPatchType.values())
		{
			if (compostFor(t) != p.compostFor(t))
			{
				return false;
			}
		}
		return true;
	}

	private boolean upgradesEqual(FarmingPreferences p)
	{
		for (CompostBin.Def d : CompostBin.ALL)
		{
			if (binUpgrade(d.getBinId()) != p.binUpgrade(d.getBinId()))
			{
				return false;
			}
		}
		return true;
	}

	@Override
	public int hashCode()
	{
		return getTreeClearing().hashCode() * 31 + (isPickFruitFirst() ? 1 : 0) + (isIncludeAllotments() ? 2 : 0) + (isIncludeFlowers() ? 4 : 0);
	}
}
