package com.osrstelemetry.plugin.navigation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every transition walking may use (canonical §28.10), from the Shortest Path plugin's transport tables (Skretzo and
 * contributors, BSD 2-Clause; see THIRD_PARTY_NOTICES.md), bundled under {@code navigation/}: {@code transports.tsv} (doors,
 * gates, stairs, ladders, caves), {@code agility_shortcuts.tsv}, {@code boats.tsv}, {@code ships.tsv},
 * {@code quetzals.tsv}, {@code magic_mushtrees.tsv} and {@code hot_air_balloons.tsv}. Teleports, fairy rings and spirit trees are not here: they are the
 * travel catalogue's ({@code travel/TransportCatalog}), which evaluates their items, charges and requirements.
 *
 * <p>Columns are read by their header names. In the network tables (quetzals, mushtrees) a row with no destination is a
 * departure point and a row with no origin an arrival point; every departure reaches every other arrival.
 *
 * <p>Immutable after loading; thread-safe.
 */
public final class TransitionCatalog
{
	static final String DIR = "/com/osrstelemetry/plugin/navigation/";
	private static final String[][] FILES = {
		{"transports.tsv", "PASSAGE"},
		{"agility_shortcuts.tsv", "AGILITY_SHORTCUT"},
		{"boats.tsv", "BOAT"},
		{"ships.tsv", "SHIP"},
		{"quetzals.tsv", "QUETZAL"},
		{"magic_mushtrees.tsv", "MAGIC_MUSHTREE"},
		{"hot_air_balloons.tsv", "BALLOON"},
		{"spyglass_transitions.tsv", "BOAT"},
	};

	private final List<Transition> all;
	private final Map<Integer, List<Transition>> byFrom;
	private final Map<Integer, List<Transition>> byTo;

	private TransitionCatalog(List<Transition> all)
	{
		this.all = Collections.unmodifiableList(all);
		Map<Integer, List<Transition>> f = new HashMap<>();
		Map<Integer, List<Transition>> t = new HashMap<>();
		for (Transition tr : all)
		{
			f.computeIfAbsent(tr.from, k -> new ArrayList<>(2)).add(tr);
			t.computeIfAbsent(tr.to, k -> new ArrayList<>(2)).add(tr);
		}
		this.byFrom = f;
		this.byTo = t;
	}

	private static volatile TransitionCatalog shared;

	public static TransitionCatalog bundled()
	{
		TransitionCatalog c = shared;
		if (c == null)
		{
			synchronized (TransitionCatalog.class)
			{
				c = shared;
				if (c == null)
				{
					c = load();
					shared = c;
				}
			}
		}
		return c;
	}

	static TransitionCatalog load()
	{
		Map<String, int[]> itemNames = itemNames();
		List<Transition> out = new ArrayList<>();
		for (String[] f : FILES)
		{
			read(f[0], Transition.Family.valueOf(f[1]), itemNames, out);
		}
		return new TransitionCatalog(out);
	}

	/** A catalogue of given transitions (tests). */
	static TransitionCatalog of(List<Transition> transitions)
	{
		return new TransitionCatalog(new ArrayList<>(transitions));
	}

	public List<Transition> all()
	{
		return all;
	}

	public List<Transition> from(int packed)
	{
		List<Transition> l = byFrom.get(packed);
		return l == null ? Collections.emptyList() : l;
	}

	public List<Transition> to(int packed)
	{
		List<Transition> l = byTo.get(packed);
		return l == null ? Collections.emptyList() : l;
	}

	/** Every varbit and varp any transition checks (read once account data is loaded). */
	public Set<Integer> varbits(boolean varps)
	{
		Set<Integer> out = new LinkedHashSet<>();
		for (Transition t : all)
		{
			for (NavRequirement.VarCheck v : t.requirement.getVars())
			{
				if (v.varp == varps)
				{
					out.add(v.id);
				}
			}
		}
		return out;
	}

	/** Every quest any transition needs. */
	public Set<net.runelite.api.Quest> quests()
	{
		Set<net.runelite.api.Quest> out = new LinkedHashSet<>();
		for (Transition t : all)
		{
			out.addAll(t.requirement.getQuests());
		}
		return out;
	}

	private static Map<String, int[]> itemNames()
	{
		Map<String, int[]> out = new HashMap<>();
		List<String[]> rows = rows("item-variations.tsv");
		for (String[] row : rows.subList(1, rows.size()))
		{
			if (row.length >= 2 && !row[1].isEmpty())
			{
				String[] ids = row[1].split(",");
				int[] v = new int[ids.length];
				for (int i = 0; i < ids.length; i++)
				{
					v[i] = Integer.parseInt(ids[i].trim());
				}
				out.put(row[0].trim().toUpperCase(java.util.Locale.ROOT), v);
			}
		}
		out.putIfAbsent("COINS", new int[]{995});
		return out;
	}

	private static List<String[]> rows(String file)
	{
		List<String[]> out = new ArrayList<>();
		try (InputStream in = TransitionCatalog.class.getResourceAsStream(DIR + file))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing navigation/" + file);
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			boolean first = true;
			while ((line = r.readLine()) != null)
			{
				if (first)
				{
					out.add(line.replaceFirst("^#\\s*", "").split("\t", -1));
					first = false;
					continue;
				}
				if (line.startsWith("#") || line.trim().isEmpty())
				{
					continue;
				}
				out.add(line.split("\t", -1));
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return out;
	}

	private static void read(String file, Transition.Family family, Map<String, int[]> itemNames, List<Transition> out)
	{
		List<String[]> rows = rows(file);
		String[] header = rows.get(0);
		Map<String, Integer> col = new HashMap<>();
		for (int i = 0; i < header.length; i++)
		{
			col.put(header[i].trim(), i);
		}
		List<Object[]> departures = new ArrayList<>();
		List<Object[]> arrivals = new ArrayList<>();
		for (int i = 1; i < rows.size(); i++)
		{
			String[] row = rows.get(i);
			Integer from = point(cell(row, col, "Origin"));
			Integer to = point(cell(row, col, "Destination"));
			if (from == null && to == null)
			{
				continue;
			}
			String[] action = action(cell(row, col, "menuOption menuTarget objectID"));
			NavRequirement req = NavRequirement.parse(cell(row, col, "Skills"), cell(row, col, "Quests"), cell(row, col, "Items"),
				cell(row, col, "Varbits"), cell(row, col, "VarPlayers"), itemNames);
			int duration = 1;
			try
			{
				String d = cell(row, col, "Duration");
				duration = d == null || d.isEmpty() ? 1 : Integer.parseInt(d.trim());
			}
			catch (NumberFormatException e)
			{
				duration = 1;
			}
			String info = cell(row, col, "Display info");
			info = info == null || info.trim().isEmpty() ? null : info.trim();
			if (from != null && to != null)
			{
				if (family == Transition.Family.SHIP && action[1] != null && action[1].equals("Monk of Entrana") && NavPoint.x(to) < 2900)
				{
					req = req.withRestriction(NavRequirement.ENTRANA); // to Entrana only: the monks search you
				}
				out.add(new Transition(from, to, family, action[0], action[1], parseInt(action[2]), req, duration, info,
					area(cell(row, col, "Arrival area"))));
			}
			else if (to == null)
			{
				departures.add(new Object[]{from, action, req, duration});
			}
			else
			{
				if (family == Transition.Family.BALLOON)
				{
					// OSRS Wiki "Balloon transport system" (2026-10-22): each landing needs a Firemaking level (boostable; the base
					// level is checked, so a boost is never assumed). Shortest Path's table has no Skills column for these rows.
					Integer fm = info == null ? null : BALLOON_FIREMAKING.get(info);
					if (fm != null)
					{
						req = req.and(NavRequirement.parse(fm + " Firemaking", null, null, null, null, itemNames));
					}
				}
				arrivals.add(new Object[]{to, req, duration, info});
			}
		}
		for (Object[] d : departures)
		{
			for (Object[] a : arrivals)
			{
				int from = (Integer) d[0];
				int to = (Integer) a[0];
				if (NavPoint.distance(from, to) <= 5)
				{
					continue; // the landing it departs from (Shortest Path's permutation radius: quetzals and mushtrees 5)
				}
				String[] action = (String[]) d[1];
				NavRequirement req = ((NavRequirement) d[2]).and((NavRequirement) a[1]);
				if (family == Transition.Family.BALLOON)
				{
					req = req.withRestriction(NavRequirement.BALLOON_WEIGHT);
				}
				out.add(new Transition(from, to, family, action[0], action[1], parseInt(action[2]), req, Math.max((Integer) d[3], (Integer) a[2]),
					(String) a[3]));
			}
		}
	}

	/** Firemaking level per balloon landing (OSRS Wiki "Balloon transport system", 2026-10-22). */
	static final Map<String, Integer> BALLOON_FIREMAKING = new HashMap<>();

	static
	{
		BALLOON_FIREMAKING.put("Entrana", 20);
		BALLOON_FIREMAKING.put("Taverley", 20);
		BALLOON_FIREMAKING.put("Crafting Guild", 30);
		BALLOON_FIREMAKING.put("Varrock", 40);
		BALLOON_FIREMAKING.put("Castle Wars", 50);
		BALLOON_FIREMAKING.put("Grand Tree", 60);
	}

	private static String cell(String[] row, Map<String, Integer> col, String name)
	{
		Integer i = col.get(name);
		return i == null || i >= row.length ? null : row[i];
	}

	/** "x1 y1 x2 y2 plane" → an arrival area, or null. */
	static int[] area(String cell)
	{
		if (cell == null || cell.trim().isEmpty())
		{
			return null;
		}
		String[] p = cell.trim().split("\\s+");
		if (p.length != 5)
		{
			throw new IllegalStateException("Arrival area must be 'x1 y1 x2 y2 plane': " + cell);
		}
		int[] a = new int[5];
		for (int i = 0; i < 5; i++)
		{
			a[i] = Integer.parseInt(p[i]);
		}
		return a;
	}

	static Integer point(String cell)
	{
		if (cell == null || cell.trim().isEmpty())
		{
			return null;
		}
		String[] p = cell.trim().split("\\s+");
		if (p.length != 3)
		{
			return null;
		}
		return NavPoint.pack(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
	}

	/** "Climb-up Ladder 17026" → {"Climb-up", "Ladder", "17026"}. */
	static String[] action(String cell)
	{
		if (cell == null || cell.trim().isEmpty())
		{
			return new String[]{null, null, null};
		}
		String[] p = cell.trim().split("\\s+");
		if (p.length == 1)
		{
			return new String[]{p[0], null, null};
		}
		String last = p[p.length - 1];
		boolean id = last.matches("-?\\d+");
		int end = id ? p.length - 1 : p.length;
		String target = String.join(" ", java.util.Arrays.copyOfRange(p, 1, end));
		return new String[]{p[0], target.isEmpty() ? null : target, id ? last : null};
	}

	private static int parseInt(String s)
	{
		try
		{
			return s == null ? -1 : Integer.parseInt(s);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}
}
