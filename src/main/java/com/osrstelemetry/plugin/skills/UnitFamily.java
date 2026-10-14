package com.osrstelemetry.plugin.skills;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * ADDED (Skills Phase 6). A resource FAMILY whose items each hold a different
 * number of one UNIT (generic: e.g. variants that hold 1 to N of the same
 * thing). Ownership, production and targets of such a family are counted in
 * UNITS -- never in items -- so changing how the units are spread over the
 * variants (merging two part-filled items into one full one) never looks like
 * new production or lost stock.
 * <p>
 * Ordinary item counts (every other resource) are untouched: a family exists
 * only where a skill's definition declares one. Raw item identity is kept
 * everywhere else (telemetry, History grids); only planning and the headline
 * numbers read units.
 */
public final class UnitFamily
{
	private final String key;
	private final String name;
	private final Map<Integer, Integer> unitsPerItem;
	private final int fullUnits;
	private final int canonicalItemId;
	private final String unitSingular;
	private final String unitPlural;
	private final String fullLabel;

	/**
	 * @param unitsPerItem item id -> units one item holds (each &gt; 0)
	 * @param fullUnits units in one FULL item (the equivalence the player plans in)
	 * @param canonicalItemId the family's representative item (the full one)
	 * @param fullLabel how one full item reads in a quantity ("full": "271 × full")
	 */
	public UnitFamily(String key, String name, Map<Integer, Integer> unitsPerItem, int fullUnits, int canonicalItemId,
		String unitSingular, String unitPlural, String fullLabel)
	{
		this.key = Objects.requireNonNull(key, "key");
		this.name = Objects.requireNonNull(name, "name");
		Map<Integer, Integer> copy = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> e : unitsPerItem.entrySet())
		{
			if (e.getKey() != null && e.getValue() != null && e.getValue() > 0)
			{
				copy.put(e.getKey(), e.getValue());
			}
		}
		this.unitsPerItem = Collections.unmodifiableMap(copy);
		this.fullUnits = Math.max(1, fullUnits);
		this.canonicalItemId = canonicalItemId;
		this.unitSingular = Objects.requireNonNull(unitSingular, "unitSingular");
		this.unitPlural = Objects.requireNonNull(unitPlural, "unitPlural");
		this.fullLabel = Objects.requireNonNull(fullLabel, "fullLabel");
	}

	public String getKey()
	{
		return key;
	}

	public String getName()
	{
		return name;
	}

	/** Every member item and the units it holds, read-only. */
	public Map<Integer, Integer> getUnitsPerItem()
	{
		return unitsPerItem;
	}

	public int getFullUnits()
	{
		return fullUnits;
	}

	public int getCanonicalItemId()
	{
		return canonicalItemId;
	}

	public String getFullLabel()
	{
		return fullLabel;
	}

	public boolean contains(int itemId)
	{
		return unitsPerItem.containsKey(itemId);
	}

	/** Units one item of {@code itemId} holds; 0 when it is not a member. */
	public int unitsOf(int itemId)
	{
		Integer u = unitsPerItem.get(itemId);
		return u == null ? 0 : u;
	}

	/** Total units in an item -> quantity map (non-members ignored). */
	public long units(Map<Integer, Long> items)
	{
		long total = 0L;
		if (items == null)
		{
			return 0L;
		}
		for (Map.Entry<Integer, Long> e : items.entrySet())
		{
			if (e.getKey() != null && e.getValue() != null && e.getValue() > 0)
			{
				total += e.getValue() * unitsOf(e.getKey());
			}
		}
		return total;
	}

	/** Whole full items {@code units} make (rounded down). */
	public long fullItems(long units)
	{
		return Math.max(0L, units) / fullUnits;
	}

	/** "1,086 units" / "1 unit" (in the family's own unit words). */
	public String unitsText(long units)
	{
		return fmt(units) + " " + (units == 1 ? unitSingular : unitPlural);
	}

	/**
	 * The exact equivalence in full items: "271 × full + 2 units", "684 ×
	 * full" when it divides evenly, "2 units" below one full item. Never a
	 * fractional item.
	 */
	public String equivalentText(long units)
	{
		long u = Math.max(0L, units);
		long full = u / fullUnits;
		long rest = u % fullUnits;
		if (full == 0)
		{
			return unitsText(rest);
		}
		String head = fmt(full) + " × " + fullLabel;
		return rest == 0 ? head : head + " + " + unitsText(rest);
	}

	/** A whole-item quantity in this family's terms: "1,000 × full". */
	public String fullText(long fullItems)
	{
		return fmt(fullItems) + " × " + fullLabel;
	}

	public String unitPlural()
	{
		return unitPlural;
	}

	public String unitSingular()
	{
		return unitSingular;
	}

	private static String fmt(long v)
	{
		return String.format(Locale.ROOT, "%,d", v);
	}

	@Override
	public String toString()
	{
		return name;
	}
}
