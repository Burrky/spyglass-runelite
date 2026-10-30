package com.osrstelemetry.plugin.travel;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.osrstelemetry.plugin.account.RequirementSet;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * The Tier-0 transport registry (canonical §11): JSON resources, one file per
 * family, loaded once with the plugin's Gson. Generic: it knows nothing about
 * Farming. {@link #validate()} checks the structural rules at load; the
 * catalogue test additionally resolves every gameval name by reflection.
 */
public final class TransportCatalog
{
	public static final int SCHEMA = 1;
	static final String DIR = "/com/osrstelemetry/plugin/travel/transports/";
	public static final List<String> FILES = Collections.unmodifiableList(java.util.Arrays.asList(
		"spells.json", "tablets.json", "teleport-items.json", "diary-items.json", "jewellery.json", "fairy-rings.json", "spirit-trees.json", "poh.json"));

	static final class FileDoc
	{
		int schema;
		List<TransportDef> transports;
	}

	private final Map<String, TransportDef> byId;

	TransportCatalog(List<TransportDef> transports)
	{
		Map<String, TransportDef> m = new LinkedHashMap<>();
		for (TransportDef t : transports)
		{
			if (m.put(t.id, t) != null)
			{
				throw new IllegalArgumentException("Duplicate transport id " + t.id);
			}
		}
		this.byId = Collections.unmodifiableMap(m);
	}

	/** Loads every catalogue file; throws when a file is missing, unparsable or invalid (a build-time defect). */
	public static TransportCatalog load(Gson gson)
	{
		com.osrstelemetry.plugin.navigation.RouteTable.useGson(gson);
		List<TransportDef> all = loadBase(gson);
		all.addAll(networkJourneys(gson, all, loadNodes(gson, all), com.osrstelemetry.plugin.navigation.RouteTable.bundled()));
		TransportCatalog catalog = new TransportCatalog(all);
		List<String> problems = catalog.validate();
		if (!problems.isEmpty())
		{
			throw new IllegalStateException("Invalid transport catalogue: " + problems);
		}
		return catalog;
	}

	/** The catalogue files and the derived house routes, without world network journeys (route data generation). */
	public static List<TransportDef> loadBase(Gson gson)
	{
		List<TransportDef> all = new ArrayList<>();
		for (String file : FILES)
		{
			try (InputStream in = TransportCatalog.class.getResourceAsStream(DIR + file))
			{
				if (in == null)
				{
					throw new IllegalStateException("Missing transport file " + file);
				}
				try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
				{
					FileDoc doc = gson.fromJson(r, FileDoc.class);
					if (doc == null || doc.schema != SCHEMA || doc.transports == null)
					{
						throw new IllegalStateException("Transport file " + file + " has no schema " + SCHEMA + " transports");
					}
					all.addAll(doc.transports);
				}
			}
			catch (IOException | JsonParseException e)
			{
				throw new IllegalStateException("Unreadable transport file " + file, e);
			}
		}
		all.addAll(houseNetworkRoutes(gson, all));
		return all;
	}

	/** World network entrances (route data generation). */
	public static List<NetworkNode> nodes(Gson gson, List<TransportDef> base)
	{
		return loadNodes(gson, base);
	}

	/**
	 * A fairy ring or spirit tree is only a complete journey once the player can reach the network. The house is one entrance
	 * (world entrances are {@link #networkJourneys}): for every network destination it derives two house routes, by house tablet or
	 * construction cape ({@code poh.tab.<id>}) and by Teleport to House ({@code poh.spell.<id>}), each needing the house
	 * fairy ring / spirit tree seen in the confirmed own house ({@code poh.fairy_ring} / {@code poh.spirit_tree}) on top of
	 * the destination's own requirements (quests, staff, a player-grown tree). The house teleport's items, runes, level and
	 * spellbook are copied from the catalogued house routes, so they are checked the same way. Plain network entries stay
	 * in the catalogue for diagnostics but are never recommended on their own (canonical §28.9).
	 */
	static List<TransportDef> houseNetworkRoutes(Gson gson, List<TransportDef> all)
	{
		TransportDef tab = null;
		TransportDef spell = null;
		for (TransportDef t : all)
		{
			tab = "poh.tab.jewellery.farming_guild".equals(t.id) ? t : tab;
			spell = "poh.spell.jewellery.farming_guild".equals(t.id) ? t : spell;
		}
		List<TransportDef> out = new ArrayList<>();
		if (tab == null || spell == null)
		{
			return out;
		}
		for (TransportDef t : all)
		{
			if (t.network == null)
			{
				continue;
			}
			boolean ring = "FAIRY_RING".equals(t.network);
			String facility = ring ? "poh.fairy_ring" : "poh.spirit_tree";
			String what = ring ? "fairy ring" : "spirit tree";
			for (TransportDef house : new TransportDef[]{tab, spell})
			{
				TransportDef d = gson.fromJson(gson.toJson(t), TransportDef.class);
				boolean bySpell = house == spell;
				d.id = (bySpell ? "poh.spell." : "poh.tab.") + t.id;
				d.name = "House " + what + ": " + t.name.replaceFirst("^[^:]*:\\s*", "") + (bySpell ? " (Teleport to House)" : " (house tablet / construction cape)");
				d.type = house.type;
				d.network = null;
				d.items = house.items == null ? null : new ArrayList<>(house.items);
				d.runes = house.runes == null ? null : new ArrayList<>(house.runes);
				com.osrstelemetry.plugin.account.RequirementSet r = d.requirements == null ? new com.osrstelemetry.plugin.account.RequirementSet() : d.requirements;
				List<com.osrstelemetry.plugin.account.RequirementSet.EvidenceReq> ev = new ArrayList<>(com.osrstelemetry.plugin.account.RequirementSet.list(r.evidence));
				com.osrstelemetry.plugin.account.RequirementSet.EvidenceReq e = new com.osrstelemetry.plugin.account.RequirementSet.EvidenceReq();
				e.key = facility;
				e.name = "A " + what + " seen in your house";
				ev.add(e);
				r.evidence = ev;
				if (bySpell)
				{
					List<com.osrstelemetry.plugin.account.RequirementSet.SkillReq> sk = new ArrayList<>(com.osrstelemetry.plugin.account.RequirementSet.list(r.skills));
					sk.addAll(com.osrstelemetry.plugin.account.RequirementSet.list(house.requirements().skills));
					r.skills = sk;
					r.spellbook = house.requirements().spellbook;
				}
				d.requirements = r;
				List<String> notes = new ArrayList<>();
				notes.add("Teleport to your house, then use the " + what + ".");
				d.notes = notes;
				out.add(d);
			}
		}
		return out;
	}

	// -- complete journeys through world fairy rings and spirit trees (canonical §28.9) ---------------------------

	/** A fairy ring or spirit tree the player can walk up to and use as an entrance (network-nodes.json). */
	public static final class NetworkNode
	{
		public String id;
		public String network;
		public String code;
		public String name;
		public int x;
		public int y;
		public int plane;
		/** Requirements to use this entrance beyond the network's own (quests, proofs, evidence for a grown tree). */
		public RequirementSet requirements;
		/** A player-grown spirit tree: tile and requirements come from this catalogued transport. */
		public String fromTransport;
	}

	static final class NodesDoc
	{
		int schema;
		String source;
		List<NetworkNode> nodes;
	}

	private static List<NetworkNode> nodes = Collections.emptyList();

	/** The entrances loaded with the catalogue. */
	public static List<NetworkNode> nodes()
	{
		return nodes;
	}

	static List<NetworkNode> loadNodes(Gson gson, List<TransportDef> all)
	{
		try (InputStream in = TransportCatalog.class.getResourceAsStream("/com/osrstelemetry/plugin/travel/network-nodes.json"))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing network-nodes.json");
			}
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				NodesDoc doc = gson.fromJson(r, NodesDoc.class);
				List<NetworkNode> out = new ArrayList<>();
				for (NetworkNode n : doc.nodes)
				{
					if (n.fromTransport != null)
					{
						for (TransportDef t : all)
						{
							if (t.id.equals(n.fromTransport) && t.arrival != null)
							{
								n.x = t.arrival.x;
								n.y = t.arrival.y;
								n.plane = t.arrival.plane;
								RequirementSet own = new RequirementSet();
								own.evidence = t.requirements().evidence;
								own.skills = t.requirements().skills;
								n.requirements = own;
							}
						}
					}
					out.add(n);
				}
				nodes = Collections.unmodifiableList(out);
				return nodes;
			}
		}
		catch (IOException | JsonParseException e)
		{
			throw new IllegalStateException("Unreadable network-nodes.json", e);
		}
	}

	/**
	 * Every complete journey through a world fairy ring or spirit tree (canonical §28.11): a teleport (not itself a network
	 * hop or a house network route), the walk from where it lands to an entrance, and a network destination. The walk is
	 * resolved for each account when the journey is evaluated ({@link TransportDef#walkTo}, {@link TransportDef#walkFrom}
	 * through {@link com.osrstelemetry.plugin.navigation.AccountRouter}): no distance cut-off, and an entrance the teleport
	 * cannot walk to at all (proven by the exhaustive route data) is left out. An entrance is dropped for a teleport only when
	 * another entrance of the same network is better for every account: its own requirements are a subset and its walk with no
	 * gated step costs no more than this one's cheapest walk. One journey per teleport, entrance and destination.
	 */
	static List<TransportDef> networkJourneys(Gson gson, List<TransportDef> all, List<NetworkNode> entrances,
		com.osrstelemetry.plugin.navigation.RouteTable table)
	{
		List<TransportDef> out = new ArrayList<>();
		java.util.Set<String> hops = new java.util.HashSet<>();
		Map<String, List<TransportDef>> byNetwork = new LinkedHashMap<>();
		for (TransportDef t : all)
		{
			if (t.network != null)
			{
				hops.add(t.id);
				if (t.arrival != null)
				{
					byNetwork.computeIfAbsent(t.network, k -> new ArrayList<>()).add(t);
				}
			}
		}
		for (TransportDef entry : all)
		{
			if (entry.network != null || entry.via != null || entry.arrival == null || hops.contains(entry.id.replaceFirst("^poh\\.(tab|spell)\\.", "")))
			{
				continue;
			}
			for (Map.Entry<String, List<TransportDef>> net : byNetwork.entrySet())
			{
				List<Object[]> candidates = new ArrayList<>();
				for (NetworkNode n : entrances)
				{
					if (!net.getKey().equals(n.network))
					{
						continue;
					}
					List<com.osrstelemetry.plugin.navigation.RouteTable.Walk> walks = table.variants("node:" + n.id, "arr:" + entry.id);
					if (walks.isEmpty())
					{
						continue; // no walk at all (or not generated): not a journey
					}
					int cheapest = walks.get(0).getCost();
					int plain = Integer.MAX_VALUE;
					for (com.osrstelemetry.plugin.navigation.RouteTable.Walk w : walks)
					{
						if (w.getRequirement().isNone())
						{
							plain = Math.min(plain, w.getCost());
						}
					}
					candidates.add(new Object[]{n, walks.get(0), cheapest, plain, signature(n)});
				}
				for (Object[] c : candidates)
				{
					if (dominated(c, candidates))
					{
						continue;
					}
					NetworkNode n = (NetworkNode) c[0];
					for (TransportDef hop : net.getValue())
					{
						if (Math.max(Math.abs(n.x - hop.arrival.x), Math.abs(n.y - hop.arrival.y)) <= 10 && n.plane == hop.arrival.plane)
						{
							continue; // the destination itself
						}
						out.add(journey(gson, entry, n, hop, (com.osrstelemetry.plugin.navigation.RouteTable.Walk) c[1]));
					}
				}
			}
		}
		return out;
	}

	private static TransportDef journey(Gson gson, TransportDef entry, NetworkNode n, TransportDef hop,
		com.osrstelemetry.plugin.navigation.RouteTable.Walk walk)
	{
		TransportDef d = gson.fromJson(gson.toJson(entry), TransportDef.class);
		boolean ring = "FAIRY_RING".equals(hop.network);
		String code = ring ? hop.id.substring(hop.id.indexOf('.') + 1).toUpperCase(java.util.Locale.ROOT)
			: hop.name.replaceFirst("^[^:]*:\\s*", "");
		d.id = "via." + entry.id + "." + n.id + "." + hop.id;
		d.name = entry.name + ", then " + (ring ? "fairy ring " + n.code + " to " + code
			: Character.toLowerCase(n.name.charAt(0)) + n.name.substring(1) + " to " + code);
		d.arrival = hop.arrival;
		d.wield = hop.wield;
		d.preWalk = walk.getCost();
		d.walk = walk;
		d.walkTo = "node:" + n.id;
		d.walkFrom = "arr:" + entry.id;
		d.requirements = merge(merge(entry.requirements(), n.requirements == null ? new RequirementSet() : n.requirements), hop.requirements());
		TransportDef.Via v = new TransportDef.Via();
		v.entry = entry.id;
		v.entryAt = entry.arrival;
		v.node = n.id;
		v.nodeName = n.name;
		v.nodeAt = new TransportDef.Point();
		v.nodeAt.x = n.x;
		v.nodeAt.y = n.y;
		v.nodeAt.plane = n.plane;
		v.hop = hop.id;
		v.network = hop.network;
		v.code = code;
		d.via = v;
		List<String> notes = new ArrayList<>(TransportDef.list(entry.notes));
		notes.add("Walk to the " + n.name + ", then " + (ring ? "dial " + code : "travel to " + code) + ".");
		d.notes = notes;
		return d;
	}

	/** What an entrance itself needs, as comparable parts. */
	private static java.util.Set<String> signature(NetworkNode n)
	{
		java.util.Set<String> s = new java.util.TreeSet<>();
		RequirementSet r = n.requirements == null ? new RequirementSet() : n.requirements;
		RequirementSet.list(r.quests).forEach(q -> s.add("quest " + q.quest + " " + q.state));
		RequirementSet.list(r.proofs).forEach(p -> s.add("proof " + p.name));
		RequirementSet.list(r.evidence).forEach(e -> s.add("evidence " + e.key));
		RequirementSet.list(r.skills).forEach(k -> s.add("skill " + k.skill + " " + k.level));
		RequirementSet.list(r.diaries).forEach(dd -> s.add("diary " + dd));
		if (r.spellbook != null)
		{
			s.add("spellbook " + r.spellbook);
		}
		return s;
	}

	/**
	 * {@code c} is dominated when another entrance is better for every account: it needs no more of its own, and its walk with
	 * no gated step (usable whenever the entrance is) costs no more than {@code c}'s cheapest walk. Exact ties keep the first.
	 */
	@SuppressWarnings("unchecked")
	static boolean dominated(Object[] c, List<Object[]> all)
	{
		int cheapest = (Integer) c[2];
		java.util.Set<String> sig = (java.util.Set<String>) c[4];
		for (Object[] o : all)
		{
			if (o == c)
			{
				continue;
			}
			int plain = (Integer) o[3];
			java.util.Set<String> os = (java.util.Set<String>) o[4];
			if (plain == Integer.MAX_VALUE || plain > cheapest || !sig.containsAll(os))
			{
				continue;
			}
			boolean strictly = plain < cheapest || os.size() < sig.size();
			boolean tieFirst = all.indexOf(o) < all.indexOf(c);
			if (strictly || tieFirst)
			{
				return true;
			}
		}
		return false;
	}

	private static RequirementSet merge(RequirementSet a, RequirementSet b)
	{
		RequirementSet r = new RequirementSet();
		r.skills = concat(a.skills, b.skills);
		r.quests = concat(a.quests, b.quests);
		r.diaries = concat(a.diaries, b.diaries);
		r.evidence = concat(a.evidence, b.evidence);
		r.proofs = concat(a.proofs, b.proofs);
		r.spellbook = a.spellbook != null ? a.spellbook : b.spellbook;
		r.members = a.members == null || b.members == null ? null : a.members || b.members;
		return r;
	}

	private static <T> List<T> concat(List<T> a, List<T> b)
	{
		if ((a == null || a.isEmpty()) && (b == null || b.isEmpty()))
		{
			return null;
		}
		List<T> out = new ArrayList<>(RequirementSet.list(a));
		for (T t : RequirementSet.list(b))
		{
			if (!out.contains(t))
			{
				out.add(t);
			}
		}
		return out;
	}

	public List<TransportDef> all()
	{
		return new ArrayList<>(byId.values());
	}

	public TransportDef get(String id)
	{
		return byId.get(id);
	}

	/** The quests any requirement names: the account adapter reads only these. */
	public Set<Quest> requiredQuests()
	{
		Set<Quest> out = EnumSet.noneOf(Quest.class);
		for (TransportDef t : byId.values())
		{
			RequirementSet r = t.requirements();
			for (RequirementSet.QuestReq q : RequirementSet.list(r.quests))
			{
				out.add(Quest.valueOf(q.quest));
			}
			for (RequirementSet.ProofReq p : RequirementSet.list(r.proofs))
			{
				RequirementSet.list(p.anyQuestFinished).forEach(q -> out.add(Quest.valueOf(q)));
				RequirementSet.list(p.anyQuestStarted).forEach(q -> out.add(Quest.valueOf(q)));
			}
		}
		return out;
	}

	/** Structural rules (canonical §11.3). Gameval names are checked by the catalogue test. */
	public List<String> validate()
	{
		List<String> problems = new ArrayList<>();
		Set<String> skills = new HashSet<>();
		for (Skill s : Skill.values())
		{
			skills.add(s.name());
		}
		for (TransportDef t : byId.values())
		{
			String at = t.id == null ? "(no id)" : t.id;
			if (t.id == null || !t.id.matches("[a-z0-9_]+(\\.[a-z0-9_]+)+"))
			{
				problems.add(at + ": id must be dotted lower-case");
			}
			if (t.name == null || t.name.isEmpty() || t.type == null)
			{
				problems.add(at + ": name and type are required");
			}
			if (t.arrival == null || t.arrival.plane < 0 || t.arrival.plane > 3 || t.arrival.x < 1000 || t.arrival.x > 4200 || t.arrival.y < 2400
				|| t.arrival.y > 13000)
			{
				problems.add(at + ": arrival missing or outside the map / plane 0-3");
			}
			if (t.provenance == null || t.provenance.source == null || t.provenance.ref == null)
			{
				problems.add(at + ": provenance source and ref are required");
			}
			boolean needsItems = t.type == TransportDef.Type.TABLET || t.type == TransportDef.Type.ITEM_TELEPORT;
			if (needsItems == TransportDef.list(t.items).isEmpty())
			{
				problems.add(at + ": items are required exactly for tablets and item teleports");
			}
			if ((t.type == TransportDef.Type.SPELL) == TransportDef.list(t.runes).isEmpty())
			{
				problems.add(at + ": runes are required exactly for spells");
			}
			if (t.type == TransportDef.Type.SPELL && t.requirements().spellbook == null)
			{
				problems.add(at + ": a spell names its spellbook");
			}
			if ((t.type == TransportDef.Type.FAIRY_RING || t.type == TransportDef.Type.SPIRIT_TREE) != (t.network != null))
			{
				problems.add(at + ": network is set exactly for fairy rings and spirit trees");
			}
			for (TransportDef.ItemVariant v : TransportDef.list(t.items))
			{
				if (v.id <= 0 || v.gameval == null || v.charges == null)
				{
					problems.add(at + ": item variant needs id, gameval and charges");
				}
				else if (v.charges == TransportDef.Charges.FIXED && (v.count == null || v.count <= 0))
				{
					problems.add(at + ": FIXED variant " + v.gameval + " needs a count");
				}
				else if (v.charges == TransportDef.Charges.DAILY_LIMIT && (v.dailyLimit == null || v.dailyLimit == 0))
				{
					problems.add(at + ": DAILY_LIMIT variant " + v.gameval + " needs a dailyLimit");
				}
			}
			for (TransportDef.RuneCost r : TransportDef.list(t.runes))
			{
				if (r.id <= 0 || r.gameval == null || r.qty <= 0 || !RuneSources.isBaseRune(r.id))
				{
					problems.add(at + ": rune cost must be a positive quantity of a base rune");
				}
			}
			problems.addAll(validate(at, t.requirements(), skills));
			if (t.wield != null && (t.wield.name == null || TransportDef.list(t.wield.items).isEmpty()))
			{
				problems.add(at + ": wield requirement needs a name and items");
			}
		}
		return problems;
	}

	/** Structural rules for a requirement set (shared with domain-built sets). */
	public static List<String> validate(String at, RequirementSet req, Set<String> skills)
	{
		List<String> problems = new ArrayList<>();
		for (RequirementSet.SkillReq s : RequirementSet.list(req.skills))
		{
			if (s.skill == null || !skills.contains(s.skill) || s.level < 1 || s.level > 99)
			{
				problems.add(at + ": bad skill requirement " + s.skill);
			}
		}
		for (RequirementSet.QuestReq q : RequirementSet.list(req.quests))
		{
			quest(at, q.quest, problems);
			if (!"FINISHED".equals(q.state) && !"STARTED".equals(q.state) && !"PARTIAL".equals(q.state))
			{
				problems.add(at + ": quest state must be FINISHED, STARTED or PARTIAL");
			}
		}
		if (req.spellbook != null && !req.spellbook.matches("STANDARD|ANCIENT|LUNAR|ARCEUUS"))
		{
			problems.add(at + ": unknown spellbook " + req.spellbook);
		}
		for (RequirementSet.EvidenceReq e : RequirementSet.list(req.evidence))
		{
			if (e.key == null || e.name == null)
			{
				problems.add(at + ": evidence requirement needs key and name");
			}
		}
		for (RequirementSet.ProofReq p : RequirementSet.list(req.proofs))
		{
			if (p.name == null || p.source == null
				|| RequirementSet.list(p.anyQuestFinished).isEmpty() && RequirementSet.list(p.anyQuestStarted).isEmpty() && RequirementSet.list(p.anyDiary).isEmpty())
			{
				problems.add(at + ": a proof requirement needs a name, a source and at least one proof");
			}
			RequirementSet.list(p.anyQuestFinished).forEach(q -> quest(at, q, problems));
			RequirementSet.list(p.anyQuestStarted).forEach(q -> quest(at, q, problems));
		}
		return problems;
	}

	private static void quest(String at, String name, List<String> problems)
	{
		try
		{
			Quest.valueOf(name);
		}
		catch (RuntimeException e)
		{
			problems.add(at + ": unknown quest " + name);
		}
	}
}
