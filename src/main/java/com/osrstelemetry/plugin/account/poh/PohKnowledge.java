package com.osrstelemetry.plugin.account.poh;

import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.account.Known;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ObjectID;

/**
 * What Spyglass has observed of the player's own house (canonical §13,
 * phase G). Facilities are read from the objects in the house scene; a visit
 * counts only when it is confirmed to be the player's own house (building
 * mode seen on during the visit, which is only possible at home), because a
 * friend's house has the same objects. Construction level, owning a house or
 * carrying a house teleport proves nothing.
 *
 * <ul>
 *   <li>Never confirmed: every facility UNKNOWN; POH routes are left out of
 *   planning (not "unavailable").</li>
 *   <li>Confirmed: the facilities seen are YES; anything not seen is NO (the
 *   whole house is in the loaded scene); a later confirmed visit replaces
 *   the set, so something removed disappears.</li>
 *   <li>Portal-nexus destinations are not readable from the object; they are
 *   read from the nexus's own menus (Teleport Menu, or the configuration's
 *   slots) when the player opens one during the visit. Until then a house
 *   destination the nexus might hold is unknown, never assumed.</li>
 * </ul>
 * Evidence keys: {@code poh.jewellery_box.basic|fancy|ornate},
 * {@code poh.mounted.xerics}, {@code poh.mounted.digsite},
 * {@code poh.portal.<destination>}, {@code poh.nexus}, {@code poh.fairy_ring},
 * {@code poh.spirit_tree}. Object ids: gameval ObjectID (RuneLite 1.13.1).
 */
public final class PohKnowledge
{
	static final Map<Integer, String> OBJECTS = new LinkedHashMap<>();

	static
	{
		OBJECTS.put(ObjectID.POH_JEWELLERY_BOX_1, "poh.jewellery_box.basic");
		OBJECTS.put(ObjectID.POH_JEWELLERY_BOX_2, "poh.jewellery_box.fancy");
		OBJECTS.put(ObjectID.POH_JEWELLERY_BOX_3, "poh.jewellery_box.ornate");
		for (int id : new int[]{ObjectID.POH_AMULET_XERIC, ObjectID.POH_AMULET_XERIC_LOOKOUT, ObjectID.POH_AMULET_XERIC_GLADE,
			ObjectID.POH_AMULET_XERIC_INFERNO, ObjectID.POH_AMULET_XERIC_HEART, ObjectID.POH_AMULET_XERIC_HONOUR})
		{
			OBJECTS.put(id, "poh.mounted.xerics");
		}
		for (int id : new int[]{ObjectID.POH_AMULET_DIGSITE, ObjectID.POH_AMULET_DIG_DIGSITE, ObjectID.POH_AMULET_DIG_FOSSIL,
			ObjectID.POH_AMULET_DIG_LITHKREN})
		{
			OBJECTS.put(id, "poh.mounted.digsite");
		}
		// Portal chamber portals, by destination (gameval ObjectID / ObjectID1, RuneLite 1.13.1: teak, mahogany, marble). The
		// Varrock, Camelot and Watchtower portals have a second form per left-click destination (GE, Seers', Yanille).
		// The plain forms (no diary) go to one place. After the diary each has two forms, named by the left-click destination
		// ("..._VARROCK1ST", "..._GE1ST"); the other place is the right-click option (OSRS Wiki "Portal chamber": medium Varrock,
		// hard Kandarin, hard Ardougne diaries). Recorded as poh.portal.<left> and poh.portal_alt.<right>.
		portal("varrock", ObjectID.POH_PORTAL_TEAK_VARROCK, ObjectID.POH_PORTAL_MAG_VARROCK, ObjectID.POH_PORTAL_MARBLE_VARROCK);
		portalPair("varrock", "grand_exchange", ObjectID.POH_PORTAL_TEAK_VARROCK_VARROCK1ST, ObjectID.POH_PORTAL_MAHOGANY_VARROCK_VARROCK1ST,
			ObjectID.POH_PORTAL_MARBLE_VARROCK_VARROCK1ST);
		portalPair("grand_exchange", "varrock", ObjectID.POH_PORTAL_TEAK_VARROCK_GE1ST, ObjectID.POH_PORTAL_MAHOGANY_VARROCK_GE1ST,
			ObjectID.POH_PORTAL_MARBLE_VARROCK_GE1ST);
		portal("lumbridge", ObjectID.POH_PORTAL_TEAK_LUMBRIDGE, ObjectID.POH_PORTAL_MAG_LUMBRIDGE, ObjectID.POH_PORTAL_MARBLE_LUMBRIDGE);
		portal("falador", ObjectID.POH_PORTAL_TEAK_FALADOR, ObjectID.POH_PORTAL_MAG_FALADOR, ObjectID.POH_PORTAL_MARBLE_FALADOR);
		portal("camelot", ObjectID.POH_PORTAL_TEAK_CAMELOT, ObjectID.POH_PORTAL_MAG_CAMELOT, ObjectID.POH_PORTAL_MARBLE_CAMELOT);
		portalPair("camelot", "seers", ObjectID.POH_PORTAL_TEAK_CAMELOT_CAMELOT1ST, ObjectID.POH_PORTAL_MAHOGANY_CAMELOT_CAMELOT1ST,
			ObjectID.POH_PORTAL_MARBLE_CAMELOT_CAMELOT1ST);
		portalPair("seers", "camelot", ObjectID.POH_PORTAL_TEAK_CAMELOT_SEERS1ST, ObjectID.POH_PORTAL_MAHOGANY_CAMELOT_SEERS1ST,
			ObjectID.POH_PORTAL_MARBLE_CAMELOT_SEERS1ST);
		portal("ardougne", ObjectID.POH_PORTAL_TEAK_ARDOUGNE, ObjectID.POH_PORTAL_MAG_ARDOUGNE, ObjectID.POH_PORTAL_MARBLE_ARDOUGNE);
		portal("watchtower", ObjectID.POH_PORTAL_TEAK_YANILLE, ObjectID.POH_PORTAL_MAG_YANILLE, ObjectID.POH_PORTAL_MARBLE_YANILLE);
		portalPair("watchtower", "yanille", ObjectID.POH_PORTAL_TEAK_YANILLE_WATCHTOWER1ST, ObjectID.POH_PORTAL_MAHOGANY_YANILLE_WATCHTOWER1ST,
			ObjectID.POH_PORTAL_MARBLE_YANILLE_WATCHTOWER1ST);
		portalPair("yanille", "watchtower", ObjectID.POH_PORTAL_TEAK_YANILLE_YANILLE1ST, ObjectID.POH_PORTAL_MAHOGANY_YANILLE_YANILLE1ST,
			ObjectID.POH_PORTAL_MARBLE_YANILLE_YANILLE1ST);
		portal("kourend", ObjectID.POH_PORTAL_TEAK_KOUREND, ObjectID.POH_PORTAL_MAG_KOUREND, ObjectID.POH_PORTAL_MARBLE_KOUREND);
		portal("catherby", ObjectID.POH_PORTAL_TEAK_CATHERBY, ObjectID.POH_PORTAL_MAG_CATHERBY, ObjectID.POH_PORTAL_MARBLE_CATHERBY);
		portal("troll_stronghold", ObjectID.POH_PORTAL_TEAK_STRONGHOLD, ObjectID.POH_PORTAL_MAG_STRONGHOLD, ObjectID.POH_PORTAL_MARBLE_STRONGHOLD);
		portal("weiss", ObjectID.POH_PORTAL_TEAK_WEISS, ObjectID.POH_PORTAL_MAG_WEISS, ObjectID.POH_PORTAL_MARBLE_WEISS);
		portal("draynor_manor", ObjectID.POH_PORTAL_TEAK_DRAYNOR_MANOR, ObjectID.POH_PORTAL_MAG_DRAYNOR_MANOR, ObjectID.POH_PORTAL_MARBLE_DRAYNOR_MANOR);
		portal("battlefront", ObjectID.POH_PORTAL_TEAK_BATTLEFRONT, ObjectID.POH_PORTAL_MAG_BATTLEFRONT, ObjectID.POH_PORTAL_MARBLE_BATTLEFRONT);
		portal("mind_altar", ObjectID.POH_PORTAL_TEAK_MIND_ALTAR, ObjectID.POH_PORTAL_MAG_MIND_ALTAR, ObjectID.POH_PORTAL_MARBLE_MIND_ALTAR);
		portal("salve_graveyard", ObjectID.POH_PORTAL_TEAK_SALVE_GRAVEYARD, ObjectID.POH_PORTAL_MAG_SALVE_GRAVEYARD,
			ObjectID.POH_PORTAL_MARBLE_SALVE_GRAVEYARD);
		portal("fenkenstrain", ObjectID.POH_PORTAL_TEAK_FENKENSTRAIN, ObjectID.POH_PORTAL_MAG_FENKENSTRAIN, ObjectID.POH_PORTAL_MARBLE_FENKENSTRAIN);
		portal("west_ardougne", ObjectID.POH_PORTAL_TEAK_WEST_ARDOUGNE, ObjectID.POH_PORTAL_MAG_WEST_ARDOUGNE, ObjectID.POH_PORTAL_MARBLE_WEST_ARDOUGNE);
		portal("harmony_island", ObjectID.POH_PORTAL_TEAK_HARMONY_ISLAND, ObjectID.POH_PORTAL_MAG_HARMONY_ISLAND,
			ObjectID.POH_PORTAL_MARBLE_HARMONY_ISLAND);
		portal("barrows", ObjectID.POH_PORTAL_TEAK_BARROWS, ObjectID.POH_PORTAL_MAG_BARROWS, ObjectID.POH_PORTAL_MARBLE_BARROWS);
		portal("ape_atoll_dungeon", ObjectID.POH_PORTAL_TEAK_APE_ATOLL, ObjectID.POH_PORTAL_MAG_APE_ATOLL, ObjectID.POH_PORTAL_MARBLE_APE_ATOLL);
		portal("arceuus_library", ObjectID.POH_PORTAL_TEAK_ARCEUUS_LIBRARY, ObjectID.POH_PORTAL_MAG_ARCEUUS_LIBRARY,
			ObjectID.POH_PORTAL_MARBLE_ARCEUUS_LIBRARY);
		portal("civitas", ObjectID.POH_PORTAL_TEAK_FORTIS, ObjectID.POH_PORTAL_MAG_FORTIS, ObjectID.POH_PORTAL_MARBLE_FORTIS);
		portal("kharyrll", ObjectID.POH_PORTAL_TEAK_KHARYRLL, ObjectID.POH_PORTAL_MAG_KHARYRLL, ObjectID.POH_PORTAL_MARBLE_KHARYRLL);
		portal("lunar_isle", ObjectID.POH_PORTAL_TEAK_LUNARISLE, ObjectID.POH_PORTAL_MAG_LUNARISLE, ObjectID.POH_PORTAL_MARBLE_LUNARISLE);
		portal("senntisten", ObjectID.POH_PORTAL_TEAK_SENNTISTEN, ObjectID.POH_PORTAL_MAG_SENNTISTEN, ObjectID.POH_PORTAL_MARBLE_SENNTISTEN);
		portal("waterbirth", ObjectID.POH_PORTAL_TEAK_WATERBIRTH, ObjectID.POH_PORTAL_MAG_WATERBIRTH, ObjectID.POH_PORTAL_MARBLE_WATERBIRTH);
		portal("fishing_guild", ObjectID.POH_PORTAL_TEAK_FISHINGGUILD, ObjectID.POH_PORTAL_MAG_FISHINGGUILD, ObjectID.POH_PORTAL_MARBLE_FISHINGGUILD);
		portal("marim", ObjectID.POH_PORTAL_TEAK_MARIM, ObjectID.POH_PORTAL_MAG_MARIM, ObjectID.POH_PORTAL_MARBLE_MARIM);
		// Not recorded: Wilderness destinations (Annakarl, Carrallangar, Ghorrock, Forgotten Cemetery): never routed.
		for (int id : new int[]{ObjectID.POH_TELENEXUS_1, ObjectID.POH_TELENEXUS_2_MIDDLE, ObjectID.POH_TELENEXUS_2_SIDE, ObjectID.POH_TELENEXUS_2_CORNER,
			ObjectID.POH_TELENEXUS_3, ObjectID.POH_NEXUS_4_AMULET, ObjectID.POH_NEXUS_5_AMULET})
		{
			OBJECTS.put(id, "poh.nexus");
		}
		OBJECTS.put(ObjectID.POH_FAIRY_RING, "poh.fairy_ring");
		OBJECTS.put(ObjectID.POH_SPIRIT_TREE, "poh.spirit_tree");
		OBJECTS.put(ObjectID.POH_SPIRIT_RING, "poh.fairy_ring+poh.spirit_tree");
	}

	/**
	 * Portal nexus destinations by the name the nexus menus show (OSRS Wiki "Portal nexus" / "Portal chamber" destination
	 * names; matched case-insensitively within a menu row). Keys are the same destinations as the portals'.
	 */
	static final Map<String, String> NEXUS_NAMES = new LinkedHashMap<>();

	static
	{
		nexus("varrock", "Varrock");
		nexus("grand_exchange", "Grand Exchange");
		nexus("lumbridge", "Lumbridge");
		nexus("falador", "Falador");
		nexus("camelot", "Camelot");
		nexus("seers", "Seers' Village", "Seers Village");
		nexus("ardougne", "East Ardougne", "Ardougne");
		nexus("watchtower", "Watchtower");
		nexus("yanille", "Yanille");
		nexus("kourend", "Kourend Castle", "Kourend");
		nexus("catherby", "Catherby");
		nexus("troll_stronghold", "Troll Stronghold");
		nexus("weiss", "Weiss");
		nexus("trollheim", "Trollheim");
		nexus("draynor_manor", "Draynor Manor");
		nexus("battlefront", "Battlefront");
		nexus("mind_altar", "Mind Altar");
		nexus("salve_graveyard", "Salve Graveyard");
		nexus("fenkenstrain", "Fenkenstrain's Castle", "Fenkenstrains Castle");
		nexus("west_ardougne", "West Ardougne");
		nexus("harmony_island", "Harmony Island");
		nexus("barrows", "Barrows");
		nexus("ape_atoll_dungeon", "Ape Atoll Dungeon");
		nexus("arceuus_library", "Arceuus Library");
		nexus("civitas", "Civitas illa Fortis");
		nexus("kharyrll", "Kharyrll");
		nexus("lunar_isle", "Lunar Isle");
		nexus("senntisten", "Senntisten");
		nexus("waterbirth", "Waterbirth Island", "Waterbirth");
		nexus("fishing_guild", "Fishing Guild");
		nexus("marim", "Marim", "Ape Atoll");
		nexus("lassar", "Lassar", "Ice Mountain");
	}

	private static void nexus(String dest, String... names)
	{
		for (String n : names)
		{
			NEXUS_NAMES.put(normal(n), dest);
		}
	}

	static String normal(String s)
	{
		return s.toLowerCase(java.util.Locale.ROOT).replaceAll("<[^>]*>", " ").replaceAll("[^a-z' ]", " ").replace("'", "").replaceAll("\\s+", " ").trim();
	}

	/**
	 * The nexus destination a menu row names, or null. Rows read like "<key>: <destination>"; the longest destination name
	 * contained in the row wins (so "West Ardougne" is never read as "Ardougne", nor "Ape Atoll Dungeon" as "Marim").
	 */
	public static String nexusDestination(String rowText)
	{
		if (rowText == null)
		{
			return null;
		}
		String row = " " + normal(rowText) + " ";
		String best = null;
		int bestLen = 0;
		for (Map.Entry<String, String> e : NEXUS_NAMES.entrySet())
		{
			if (e.getKey().length() > bestLen && row.contains(" " + e.getKey() + " "))
			{
				best = e.getValue();
				bestLen = e.getKey().length();
			}
		}
		return best;
	}

	/** A house destination (portal or nexus): {@code poh.dest.<destination>}. */
	public static final String DEST = "poh.dest.";

	/**
	 * Recorded when the portal nexus's Teleport Menu (the list it teleports with, in slot order) was read during the visit.
	 * Version 2 (2026-10-22): only that menu counts. The configuration screen is not read: a destination dragged into its
	 * Slots list is not installed until the change is confirmed and paid for.
	 */
	public static final String NEXUS_READ = "poh.nexus.menu";

	/** The first collector's marker: it also read the configuration screen, so its nexus destinations are not trusted. */
	static final String NEXUS_READ_V1 = "poh.nexus.read";

	private static void portal(String dest, int... ids)
	{
		for (int id : ids)
		{
			OBJECTS.put(id, "poh.portal." + dest);
		}
	}

	/** A diary form of a portal: {@code left} on left-click, {@code right} as its right-click option. */
	private static void portalPair(String left, String right, int... ids)
	{
		for (int id : ids)
		{
			OBJECTS.put(id, "poh.portal." + left + "+poh.portal_alt." + right);
		}
	}

	/** A portal-chamber, mount or ring fact (not a nexus slot): what identifies the house's layout. */
	static boolean structural(String key)
	{
		return !key.startsWith("poh.nexus.");
	}

	/** Saved form (RuneLite profile config); field names are the JSON. */
	private Set<String> facilities = new LinkedHashSet<>();
	private long confirmedAt;
	private Set<String> unconfirmed = new LinkedHashSet<>();
	private long unconfirmedAt;

	public static PohKnowledge empty()
	{
		return new PohKnowledge();
	}

	/** Every facility key the observer can record. */
	public static Set<String> knownKeys()
	{
		Set<String> out = new LinkedHashSet<>();
		for (String v : OBJECTS.values())
		{
			Collections.addAll(out, v.split("\\+"));
		}
		for (String d : NEXUS_NAMES.values())
		{
			out.add("poh.nexus." + d);
			out.add(DEST + d);
		}
		for (String v : new java.util.ArrayList<>(out))
		{
			if (v.startsWith("poh.portal."))
			{
				out.add(DEST + v.substring("poh.portal.".length()));
			}
			else if (v.startsWith("poh.portal_alt."))
			{
				out.add(DEST + v.substring("poh.portal_alt.".length()));
			}
		}
		out.add(NEXUS_READ);
		return out;
	}

	/** The object ids that are this facility ("poh.portal.weiss", "poh.portal_alt.grand_exchange", "poh.nexus"), for highlighting. */
	public static Set<Integer> objectIds(String facility)
	{
		Set<Integer> out = new LinkedHashSet<>();
		for (Map.Entry<Integer, String> e : OBJECTS.entrySet())
		{
			for (String f : e.getValue().split("\\+"))
			{
				if (f.equals(facility))
				{
					out.add(e.getKey());
				}
			}
		}
		return out;
	}

	/** The facility keys an object id stands for (empty for anything else). */
	public static Set<String> facilitiesOf(int objectId)
	{
		String v = OBJECTS.get(objectId);
		if (v == null)
		{
			return Collections.emptySet();
		}
		Set<String> out = new LinkedHashSet<>();
		Collections.addAll(out, v.split("\\+"));
		return out;
	}

	/**
	 * Facilities from an object's name when its id is not in the table (newer portal frames, other frame tiers): a portal
	 * chamber portal is named "<Destination> Portal". Never the house's exit portal or the portal nexus.
	 */
	public static Set<String> facilitiesOfName(String name)
	{
		if (name == null)
		{
			return Collections.emptySet();
		}
		String n = normal(name);
		if (!n.endsWith(" portal") || n.contains("nexus"))
		{
			return Collections.emptySet();
		}
		String dest = nexusDestination(n.substring(0, n.length() - " portal".length()));
		return dest == null ? Collections.emptySet() : Collections.singleton("poh.portal." + dest);
	}

	/**
	 * A finished house visit. A visit confirmed as the player's own (building mode seen) replaces what the house has; the nexus
	 * slots seen in its Teleport Menu replace the old ones only when the menu was read on this visit, otherwise the last read
	 * slots are kept while the nexus is still there. A visit not confirmed is the player's own house only when its layout is
	 * exactly the confirmed one (every portal, mount, box, ring and tree the same): then a nexus menu read on it updates the
	 * slots. Anything else stays apart and unused (a friend's house).
	 */
	public PohKnowledge withVisit(Set<String> seen, boolean ownHouseConfirmed, long at)
	{
		PohKnowledge k = copy();
		Set<String> v = new LinkedHashSet<>(seen);
		v.remove(NEXUS_READ_V1);
		boolean sameLayout = !ownHouseConfirmed && isConfirmed() && !v.isEmpty() && layout(v).equals(layout(k.facilities));
		if (ownHouseConfirmed || sameLayout)
		{
			Set<String> next = new LinkedHashSet<>(sameLayout ? layout(k.facilities) : layout(v));
			boolean nexus = next.contains("poh.nexus");
			if (nexus && v.contains(NEXUS_READ))
			{
				for (String f : v)
				{
					if (f.startsWith("poh.nexus.") || f.equals(NEXUS_READ))
					{
						next.add(f);
					}
				}
			}
			else if (nexus)
			{
				for (String f : k.facilities)
				{
					if (f.startsWith("poh.nexus.") || f.equals(NEXUS_READ))
					{
						next.add(f); // not read this time: the last read slots stand
					}
				}
			}
			k.facilities = next;
			if (ownHouseConfirmed)
			{
				k.confirmedAt = at;
			}
			k.unconfirmed = new LinkedHashSet<>();
			k.unconfirmedAt = 0;
		}
		else if (!v.isEmpty())
		{
			k.unconfirmed = v;
			k.unconfirmedAt = at;
		}
		return k;
	}

	private static Set<String> layout(Set<String> f)
	{
		Set<String> out = new LinkedHashSet<>();
		for (String x : f == null ? Collections.<String>emptySet() : f)
		{
			if (structural(x))
			{
				out.add(x);
			}
		}
		return out;
	}

	/**
	 * Saved knowledge from before 2026-10-22's correction: nexus slots recorded by the first collector (which also read the
	 * configuration screen) are dropped and read again from the Teleport Menu; portals, mounts, boxes, rings and trees stay.
	 */
	PohKnowledge migrated()
	{
		if (!facilities.contains(NEXUS_READ_V1) && !unconfirmed.contains(NEXUS_READ_V1))
		{
			return this;
		}
		PohKnowledge k = copy();
		k.facilities.removeIf(f -> f.equals(NEXUS_READ_V1) || f.startsWith("poh.nexus.") && !f.equals("poh.nexus"));
		k.unconfirmed.removeIf(f -> f.equals(NEXUS_READ_V1) || f.startsWith("poh.nexus.") && !f.equals("poh.nexus"));
		return k;
	}

	public PohKnowledge copy()
	{
		PohKnowledge k = new PohKnowledge();
		k.facilities = new LinkedHashSet<>(facilities == null ? Collections.emptySet() : facilities);
		k.confirmedAt = confirmedAt;
		k.unconfirmed = new LinkedHashSet<>(unconfirmed == null ? Collections.emptySet() : unconfirmed);
		k.unconfirmedAt = unconfirmedAt;
		return k;
	}

	/** A loaded copy with any outdated records migrated (see {@link #migrated()}). */
	public PohKnowledge loaded()
	{
		return copy().migrated();
	}

	public boolean isConfirmed()
	{
		return confirmedAt > 0;
	}

	public long getConfirmedAt()
	{
		return confirmedAt;
	}

	public Set<String> getFacilities()
	{
		return Collections.unmodifiableSet(facilities == null ? Collections.emptySet() : facilities);
	}

	/** Facilities seen in a house not confirmed to be the player's own (shown as unresolved, never planned with). */
	public Set<String> getUnconfirmed()
	{
		return Collections.unmodifiableSet(unconfirmed == null ? Collections.emptySet() : unconfirmed);
	}

	/** YES when seen in the confirmed own house; NO when a confirmed visit did not see it; otherwise UNKNOWN. */
	public Known answer(String key)
	{
		if (key == null || !key.startsWith("poh."))
		{
			return Known.UNKNOWN;
		}
		if (!isConfirmed())
		{
			return Known.UNKNOWN;
		}
		Set<String> f = getFacilities();
		boolean menuUnread = f.contains("poh.nexus") && !f.contains(NEXUS_READ);
		if (key.startsWith(DEST))
		{
			// Any facility that sends the player there (a portal, a portal's right-click, a nexus slot).
			String d = key.substring(DEST.length());
			if (f.contains("poh.portal." + d) || f.contains("poh.portal_alt." + d) || f.contains("poh.nexus." + d))
			{
				return Known.YES;
			}
			return menuUnread ? Known.UNKNOWN : Known.NO;
		}
		if (key.startsWith("poh.nexus.") && !key.equals(NEXUS_READ))
		{
			// A nexus slot: only its Teleport Menu says (the nexus being there says nothing about its slots).
			if (f.contains(key) && f.contains(NEXUS_READ))
			{
				return Known.YES;
			}
			return menuUnread ? Known.UNKNOWN : Known.NO;
		}
		switch (key)
		{
			case "poh.jewellery_box.basic":
				return Known.of(f.contains("poh.jewellery_box.basic") || f.contains("poh.jewellery_box.fancy") || f.contains("poh.jewellery_box.ornate"));
			case "poh.jewellery_box.fancy":
				return Known.of(f.contains("poh.jewellery_box.fancy") || f.contains("poh.jewellery_box.ornate"));
			default:
				return Known.of(f.contains(key));
		}
	}

	public EvidenceSource evidence()
	{
		PohKnowledge k = copy();
		return new EvidenceSource()
		{
			@Override
			public Known get(String key)
			{
				return k.answer(key);
			}

			@Override
			public String explain(String key)
			{
				Known a = k.answer(key);
				return a == Known.UNKNOWN ? "your house hasn't been confirmed yet (visit it and turn on building mode once)"
					: a == Known.YES ? "seen in your house" : "not seen in your house";
			}
		};
	}

	/** Two sources, the first one that knows answers. */
	public static EvidenceSource either(EvidenceSource a, EvidenceSource b)
	{
		return new EvidenceSource()
		{
			@Override
			public Known get(String key)
			{
				Known x = a == null ? Known.UNKNOWN : a.get(key);
				return x != Known.UNKNOWN || b == null ? x : b.get(key);
			}

			@Override
			public String explain(String key)
			{
				Known x = a == null ? Known.UNKNOWN : a.get(key);
				return x != Known.UNKNOWN || b == null ? (a == null ? "no evidence" : a.explain(key)) : b.explain(key);
			}
		};
	}
}
