package com.osrstelemetry.plugin.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Pure loot valuation/sorting for the Current Session loot section. Resolving an
 * actual unit price (GE or High Alch) is the CALLER's job
 * (CurrentSessionView, via RuneLite's own ItemManager/ItemComposition)
 * -- this class only does the arithmetic and ordering, so it stays
 * directly unit-testable with a plain lambda, no ItemManager mock
 * needed (this project has no Mockito dependency -- same
 * pure-core/RuneLite-boundary split this codebase already uses
 * elsewhere, e.g. NpcInteractionTargetCollector.decide()).
 *
 * SORT ORDER: by TOTAL stack value descending (unit price
 * times quantity), never unit price alone -- a cheap-per-unit stack
 * can still be worth more in total than one expensive item (the
 * task's own "rune platebody vs. coins" example). Ties are broken by
 * item name ascending for a stable, deterministic order.
 *
 * PUBLIC: this class itself, and the nested
 * {@link Priceable} interface, were widened from package-private to
 * public so that {@code ui.model.CurrentSessionSnapshot.LootEntry} AND
 * {@code ui.model.LootTrackerSnapshot.ItemEntry} (a different package)
 * can both implement {@link Priceable} and share this ONE valuation/
 * sort implementation -- the spec's explicit "reuse existing
 * LootPricing, no second pricing system" requirement for the new
 * persistent Loot Tracker. Every other member here ({@link ValuedLootRow},
 * {@link #valueAndSort}, {@link #buildTooltipHtml}, etc.) deliberately
 * stays package-private, exactly as before -- only callers within the
 * {@code ui} package (CurrentSessionView, LootTrackerView) can use the
 * actual valuation logic; {@code ui.model} classes only ever see the
 * {@link Priceable} shape they implement, never the pricing/sorting
 * code itself.
 */
public final class LootPricing
{
	private LootPricing()
	{
	}

	/**
	 * The minimal shape {@link #valueAndSort}
	 * needs from any lootable item entry -- see class javadoc for why
	 * this exists and stays public while everything else here does not.
	 */
	public interface Priceable
	{
		Integer getItemId();

		String getItemName();

		long getQuantity();
	}

	/** One loot row with its resolved unit/total value already applied. */
	static final class ValuedLootRow
	{
		private final Integer itemId;
		private final String itemName;
		private final long quantity;
		private final long unitValue;
		private final long totalValue;

		ValuedLootRow(Integer itemId, String itemName, long quantity, long unitValue, long totalValue)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
			this.unitValue = unitValue;
			this.totalValue = totalValue;
		}

		Integer getItemId()
		{
			return itemId;
		}

		String getItemName()
		{
			return itemName;
		}

		long getQuantity()
		{
			return quantity;
		}

		long getUnitValue()
		{
			return unitValue;
		}

		long getTotalValue()
		{
			return totalValue;
		}
	}

	/**
	 * Resolves each entry's unit value via {@code unitValueLookup}
	 * (called with the entry's itemId), computes each row's total value
	 * (unit value * quantity), and returns a NEW list sorted by total
	 * value descending, item name ascending as a tie-break. Never
	 * mutates {@code entries}. An entry with a null itemId, or whose
	 * lookup throws, is priced at zero (still included, just sorted
	 * last) rather than skipped or crashing the panel -- see
	 * safeLookup().
	 */
	static List<ValuedLootRow> valueAndSort(List<? extends Priceable> entries, ToLongFunction<Integer> unitValueLookup)
	{
		List<ValuedLootRow> rows = new ArrayList<>();
		for (Priceable entry : entries)
		{
			long unitValue = entry.getItemId() == null ? 0L : safeLookup(unitValueLookup, entry.getItemId());
			long totalValue = unitValue * entry.getQuantity();
			rows.add(new ValuedLootRow(entry.getItemId(), entry.getItemName(), entry.getQuantity(), unitValue, totalValue));
		}

		rows.sort((a, b) ->
		{
			int byValue = Long.compare(b.getTotalValue(), a.getTotalValue());
			if (byValue != 0)
			{
				return byValue;
			}
			String nameA = a.getItemName() == null ? "" : a.getItemName();
			String nameB = b.getItemName() == null ? "" : b.getItemName();
			return nameA.compareTo(nameB);
		});

		return rows;
	}

	/** Sum of every row's total value -- the compact "Loot   1.42m gp" header figure. */
	static long sumTotalValue(List<ValuedLootRow> rows)
	{
		long sum = 0L;
		for (ValuedLootRow row : rows)
		{
			sum += row.getTotalValue();
		}
		return sum;
	}

	private static long safeLookup(ToLongFunction<Integer> lookup, int itemId)
	{
		try
		{
			return Math.max(0L, lookup.applyAsLong(itemId));
		}
		catch (RuntimeException e)
		{
			// Defensive only -- an unresolvable/unpriced item (e.g. a
			// quest item with no GE listing) must never take the panel
			// down; it is priced at zero instead.
			return 0L;
		}
	}

	/**
	 * The one shared loot-item tooltip builder -- factored out here so
	 * the full Loot Tracker and Session History views can build the
	 * exact same tooltip shape without re-deriving it. {@code hiddenNote}
	 * is appended as an extra line only when non-null/non-empty -- pass
	 * null from any caller with no hidden-item concept (Current Session
	 * has none; the Loot Tracker does).
	 */
	static String buildTooltipHtml(String itemName, long quantity, String modeLabel, long unitValue, long totalValue, String hiddenNote)
	{
		String name = itemName == null ? "Unknown item" : itemName;
		StringBuilder html = new StringBuilder("<html><b>").append(escapeHtml(name)).append("</b><br>")
			.append("Qty: ").append(formatNumber(quantity)).append("<br>")
			.append(modeLabel).append(" (each): ").append(formatNumber(unitValue)).append(" gp<br>")
			.append("Total: ").append(formatNumber(totalValue)).append(" gp");
		if (hiddenNote != null && !hiddenNote.isEmpty())
		{
			html.append("<br>").append(escapeHtml(hiddenNote));
		}
		html.append("</html>");
		return html.toString();
	}

	private static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String formatNumber(long value)
	{
		return String.format("%,d", value);
	}
}
