package com.osrstelemetry.plugin.account;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Observed charges and daily uses of teleport items (canonical §28.13), from the game's own messages only. Saved per
 * RuneScape profile; immutable.
 *
 * <p>Pools ({@code TransportDef.ItemVariant.pool}):
 * <ul>
 *   <li>{@code daily.ardougne_farm}, {@code daily.cabbage}: a day's uses. Message (verified in Josh's client log,
 *   2026-10-22): "You have used N of your M Ardougne Farm teleports for today." / "... M Cabbage teleports for today."
 *   (seen on teleporting and on Check). The count restarts at the daily reset (00:00 UTC); an older observation then
 *   means none used today.</li>
 *   <li>{@code stored.xerics_talisman}: "The talisman has N charges." (Check; verified in the same log).</li>
 *   <li>{@code stored.quetzal_whistle}: "Your quetzal whistle has N charges remaining." (after each use and on Check;
 *   verified in the same log). Recorded only when one whistle is carried (the message names no tier).</li>
 * </ul>
 * Nothing here is ever assumed: an item never observed stays unknown. A stored count is the last one the game reported;
 * a use the game did not report (the talisman reports none) is not subtracted, so the count is "as last checked".
 */
public final class ChargeKnowledge
{
	public static final ChargeKnowledge EMPTY = new ChargeKnowledge(Collections.emptyMap());

	public static final String ARDOUGNE_FARM = "daily.ardougne_farm";
	public static final String CABBAGE = "daily.cabbage";
	public static final String XERICS = "stored.xerics_talisman";
	public static final String QUETZAL_WHISTLE = "stored.quetzal_whistle";

	static final Pattern DAILY = Pattern.compile("You have used (\\d+) of your (\\d+) (Ardougne Farm|Cabbage) teleports for today\\.");
	static final Pattern TALISMAN = Pattern.compile("The talisman has ([\\d,]+) charges?\\.");
	static final Pattern WHISTLE = Pattern.compile("Your quetzal whistle has ([\\d,]+) charges? remaining\\.");
	static final long DAY_MILLIS = 86_400_000L;

	/** One pool's last observation: {@code used} of {@code limit} today (daily), or {@code count} left (stored). */
	public static final class Entry
	{
		public final int used;
		public final int limit;
		public final int count;
		public final long at;

		Entry(int used, int limit, int count, long at)
		{
			this.used = used;
			this.limit = limit;
			this.count = count;
			this.at = at;
		}

		@Override
		public boolean equals(Object o)
		{
			return o instanceof Entry && ((Entry) o).used == used && ((Entry) o).limit == limit && ((Entry) o).count == count && ((Entry) o).at == at;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(used, limit, count, at);
		}
	}

	private final Map<String, Entry> entries;

	private ChargeKnowledge(Map<String, Entry> entries)
	{
		this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
	}

	public Map<String, Entry> getEntries()
	{
		return entries;
	}

	/** This knowledge with a game message applied (unchanged when the message is about nothing tracked). */
	public ChargeKnowledge withMessage(String message, boolean oneWhistleCarried, long now)
	{
		if (message == null)
		{
			return this;
		}
		String m = message.replaceAll("<[^>]*>", "").trim();
		Matcher d = DAILY.matcher(m);
		if (d.find())
		{
			return with("Ardougne Farm".equals(d.group(3)) ? ARDOUGNE_FARM : CABBAGE,
				new Entry(Integer.parseInt(d.group(1)), Integer.parseInt(d.group(2)), -1, now));
		}
		Matcher t = TALISMAN.matcher(m);
		if (t.find())
		{
			return with(XERICS, new Entry(-1, -1, number(t.group(1)), now));
		}
		Matcher w = WHISTLE.matcher(m);
		if (w.find() && oneWhistleCarried)
		{
			return with(QUETZAL_WHISTLE, new Entry(-1, -1, number(w.group(1)), now));
		}
		return this;
	}

	private ChargeKnowledge with(String pool, Entry e)
	{
		Map<String, Entry> next = new LinkedHashMap<>(entries);
		next.put(pool, e);
		return new ChargeKnowledge(next);
	}

	private static int number(String s)
	{
		return Integer.parseInt(s.replace(",", ""));
	}

	/** Uses left in a pool now, or null when never observed. Daily pools restart at 00:00 UTC. */
	public Integer remaining(String pool, long now)
	{
		Entry e = pool == null ? null : entries.get(pool);
		if (e == null)
		{
			return null;
		}
		if (pool.startsWith("daily."))
		{
			return Math.floorDiv(e.at, DAY_MILLIS) < Math.floorDiv(now, DAY_MILLIS) ? e.limit : Math.max(0, e.limit - e.used);
		}
		return Math.max(0, e.count);
	}

	/** Words for a pool's state, or null when never observed: "2 of 5 used today", "247 charges (checked 3h ago)". */
	public String describe(String pool, long now)
	{
		Entry e = pool == null ? null : entries.get(pool);
		if (e == null)
		{
			return null;
		}
		if (pool.startsWith("daily."))
		{
			boolean reset = Math.floorDiv(e.at, DAY_MILLIS) < Math.floorDiv(now, DAY_MILLIS);
			return reset ? "0 of " + e.limit + " used today (reset since last seen)" : e.used + " of " + e.limit + " used today";
		}
		return e.count + (e.count == 1 ? " charge" : " charges") + " when last reported";
	}

	@Override
	public boolean equals(Object o)
	{
		return o instanceof ChargeKnowledge && ((ChargeKnowledge) o).entries.equals(entries);
	}

	@Override
	public int hashCode()
	{
		return entries.hashCode();
	}

	// -- persistence (RuneLite profile config, JSON) ---------------------------------------------------

	/** Saved form: pool -> [used, limit, count, at]. */
	public Map<String, long[]> toSaved()
	{
		Map<String, long[]> out = new LinkedHashMap<>();
		entries.forEach((k, e) -> out.put(k, new long[]{e.used, e.limit, e.count, e.at}));
		return out;
	}

	public static ChargeKnowledge fromSaved(Map<String, long[]> saved)
	{
		if (saved == null)
		{
			return EMPTY;
		}
		Map<String, Entry> m = new LinkedHashMap<>();
		saved.forEach((k, v) -> {
			if (k != null && v != null && v.length == 4)
			{
				m.put(k, new Entry((int) v[0], (int) v[1], (int) v[2], v[3]));
			}
		});
		return new ChargeKnowledge(m);
	}
}
