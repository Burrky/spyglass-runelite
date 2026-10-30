package com.osrstelemetry.plugin.skills.fishing;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

/**
 * The first-pass Fishing METHODS: a TECHNIQUE at a kind of fishing spot
 * (the spot + the option clicked on it), never "whatever fish arrived
 * last". A multi-output technique is ONE method (fly fishing catches trout
 * AND salmon; the method stays "Fly fishing" and the fish stay separate
 * resources), so method identity never churns between species.
 *
 * Each method lists its catches (the resources it produces), the spot it
 * is fished at (RuneLite's native FishingSpot data, by NPC id -- game data,
 * not the Fishing plugin), the options that select it there, and its
 * CONSUMABLE, if any. Every catch belongs to exactly one method here, so the
 * catch itself also names the technique when no spot was seen.
 *
 * Deferred (see FishingCompanionDefinition): eels (sacred, infernal, lava,
 * cave, slimy), aerial fishing, drift net fishing, Camdozaal, lantern
 * harpooning, Fishing Trawler, Tempoross.
 */
public enum FishingMethod
{
	SMALL_NET("Small net fishing", "FISH CAUGHT", "fish", true, "SHRIMP", opts("Net", "Small Net"),
		items(ItemID.RAW_SHRIMP, ItemID.RAW_ANCHOVIES), items()),
	BAIT("Bait fishing", "FISH CAUGHT", "fish", true, "SHRIMP", opts("Bait"),
		items(ItemID.RAW_SARDINE, ItemID.RAW_HERRING), items(ItemID.FISHING_BAIT)),
	FLY("Fly fishing", "FISH CAUGHT", "fish", true, "SALMON", opts("Lure"),
		items(ItemID.RAW_TROUT, ItemID.RAW_SALMON), items(ItemID.FEATHER)),
	PIKE("Pike", "PIKE", "pike", true, "SALMON", opts("Bait"),
		items(ItemID.RAW_PIKE), items(ItemID.FISHING_BAIT)),
	LOBSTER("Lobster", "LOBSTERS", "lobsters", true, "LOBSTER", opts("Cage"),
		items(ItemID.RAW_LOBSTER), items()),
	HARPOON("Tuna & swordfish", "FISH CAUGHT", "fish", true, "LOBSTER", opts("Harpoon"),
		items(ItemID.RAW_TUNA, ItemID.RAW_SWORDFISH), items()),
	BIG_NET("Big net fishing", "FISH CAUGHT", "fish", true, "SHARK", opts("Big Net", "Net"),
		items(ItemID.RAW_MACKEREL, ItemID.RAW_COD, ItemID.RAW_BASS), items()),
	SHARK("Shark", "SHARKS", "sharks", true, "SHARK", opts("Harpoon"),
		items(ItemID.RAW_SHARK), items(ItemID.SHARK_LURE)),
	MONKFISH("Monkfish", "MONKFISH", "monkfish", true, "MONKFISH", opts("Net"),
		items(ItemID.RAW_MONKFISH), items()),
	KARAMBWAN("Karambwan", "KARAMBWAN", "karambwan", true, "KARAMBWAN", opts("Fish"),
		items(ItemID.TBWT_RAW_KARAMBWAN), items(ItemID.TBWT_RAW_KARAMBWANJI)),
	/** Stackable bait for Karambwan: inventory space is never the limit. */
	KARAMBWANJI("Karambwanji", "KARAMBWANJI", "karambwanji", false, "KARAMBWANJI", opts("Net", "Small Net"),
		items(ItemID.TBWT_RAW_KARAMBWANJI), items()),
	ANGLERFISH("Anglerfish", "ANGLERFISH", "anglerfish", true, "ANGLERFISH", opts("Bait", "Sandworms"),
		items(ItemID.RAW_ANGLERFISH), items(ItemID.PISCARILIUS_SANDWORMS)),
	DARK_CRAB("Dark crabs", "DARK CRABS", "crabs", true, "DARK_CRAB", opts("Cage"),
		items(ItemID.RAW_DARK_CRAB), items(ItemID.WILDERNESS_FISHING_BAIT)),
	/** Stackable, 10-14 per catch (Kylie Minnow's platform): no CURRENT TRIP. */
	MINNOWS("Minnows", "MINNOWS", "minnows", false, "MINNOW", opts("Small Net", "Net"),
		items(ItemID.MINNOW), items()),
	/** Leaping fish with a barbarian rod; Agility and Strength XP are incidental to it. */
	BARBARIAN("Barbarian fishing", "FISH CAUGHT", "fish", true, "BARB_FISH", opts("Use-rod"),
		items(ItemID.BRUT_SPAWNING_TROUT, ItemID.BRUT_SPAWNING_SALMON, ItemID.BRUT_STURGEON),
		items(ItemID.FEATHER, ItemID.FISHING_BAIT, ItemID.BRUT_FISH_CUTS));

	private final String label;
	private final String productionCaption;
	private final String rateNoun;
	private final boolean tripMatters;
	private final String spot;
	private final Set<String> options;
	private final int[] catches;
	private final Set<Integer> supplies;

	FishingMethod(String label, String productionCaption, String rateNoun, boolean tripMatters, String spot, Set<String> options,
		int[] catches, int[] supplies)
	{
		this.label = label;
		this.productionCaption = productionCaption;
		this.rateNoun = rateNoun;
		this.tripMatters = tripMatters;
		this.spot = spot;
		this.options = options;
		this.catches = catches;
		Set<Integer> s = new LinkedHashSet<>();
		for (int id : supplies)
		{
			s.add(id);
		}
		this.supplies = Collections.unmodifiableSet(s);
	}

	private static Set<String> opts(String... options)
	{
		Set<String> s = new LinkedHashSet<>();
		for (String o : options)
		{
			s.add(o.toLowerCase(Locale.ROOT));
		}
		return Collections.unmodifiableSet(s);
	}

	private static int[] items(int... ids)
	{
		return ids;
	}

	public String getLabel()
	{
		return label;
	}

	public String getProductionCaption()
	{
		return productionCaption;
	}

	public String getRateNoun()
	{
		return rateNoun;
	}

	public boolean isTripMatters()
	{
		return tripMatters;
	}

	public int[] getCatches()
	{
		return catches.clone();
	}

	/** The consumable(s) the method uses up; empty when none. */
	public Set<Integer> getSupplies()
	{
		return supplies;
	}

	public String getKey()
	{
		return name().toLowerCase(Locale.ROOT);
	}

	public static FishingMethod fromKey(String key)
	{
		if (key == null)
		{
			return null;
		}
		for (FishingMethod m : values())
		{
			if (m.getKey().equals(key))
			{
				return m;
			}
		}
		return null;
	}

	/** The method whose catch this item is (each catch belongs to exactly one), or null. */
	public static FishingMethod catching(int itemId)
	{
		for (FishingMethod m : values())
		{
			for (int id : m.catches)
			{
				if (id == itemId)
				{
					return m;
				}
			}
		}
		return null;
	}

	/**
	 * The technique a fishing spot (RuneLite FishingSpot constant name, e.g.
	 * "SALMON") and the clicked option ("Lure") name, or null when the pair is
	 * not a supported method (e.g. an eel spot) or the option is not known.
	 */
	public static FishingMethod forSpot(String spotName, String option)
	{
		if (spotName == null || option == null)
		{
			return null;
		}
		String o = option.trim().toLowerCase(Locale.ROOT);
		for (FishingMethod m : values())
		{
			if (m.spot.equals(spotName) && m.options.contains(o))
			{
				return m;
			}
		}
		return null;
	}

	/** Whether this spot kind has any supported method (so a spot of another kind is never mistaken for Fishing evidence). */
	public static boolean isSupportedSpot(String spotName)
	{
		return spotName != null && Arrays.stream(values()).anyMatch(m -> m.spot.equals(spotName));
	}
}
