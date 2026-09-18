package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;

import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.session.SessionLifecycleEngine;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * NOTE: written, not run -- same no-JDK-in-this-cloud-sandbox caveat
 * documented across this project's other Java test files (see
 * SessionLifecycleEngineTest). Josh's own `.\gradlew.bat clean test`
 * run is what actually executes these.
 *
 * Pure tests for
 * {@link LootPricing}'s own arithmetic/ordering. Resolving an actual
 * unit price is CurrentSessionView's job (RuneLite's ItemManager,
 * exercised only by the real client) -- these tests supply a plain
 * lambda price lookup instead, exactly the seam LootPricing was split
 * out for (this project has no Mockito dependency). LootEntry
 * instances are obtained the same way CurrentSessionSnapshotTest
 * builds its own fixtures (a real SessionLifecycleEngine +
 * SessionSnapshot.capture()), since LootEntry's own constructor is
 * package-private to ui.model.
 */
public class LootPricingTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final ActivityIdentity GARGOYLES =
		new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");

	// ------------------------------------------------------------------
	// Sorting is by TOTAL stack value, never unit price alone
	// -- the task's own "rune platebody (38k x 2 = 76k) outranks coins
	// (50k total)" example, under GE-sourced prices.
	// ------------------------------------------------------------------
	@Test
	public void valueAndSort_ordersByTotalStackValueDescending_underGeMode()
	{
		List<CurrentSessionSnapshot.LootEntry> entries = lootEntries(
			item(995, "Coins", 50_000L),
			item(1127, "Rune platebody", 2L));

		Map<Integer, Long> gePrices = new HashMap<>();
		gePrices.put(995, 1L);
		gePrices.put(1127, 38_000L);

		List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(entries, id -> gePrices.getOrDefault(id, 0L));

		assertEquals(2, rows.size());
		assertEquals("Rune platebody", rows.get(0).getItemName());
		assertEquals(76_000L, rows.get(0).getTotalValue());
		assertEquals("Coins", rows.get(1).getItemName());
		assertEquals(50_000L, rows.get(1).getTotalValue());
	}

	// Same "total stack value, not unit price" rule, exercised again
	// under a completely different (High Alch) price source, so GE and
	// HA are each covered by their own test.
	@Test
	public void valueAndSort_ordersByTotalStackValueDescending_underHighAlchMode()
	{
		List<CurrentSessionSnapshot.LootEntry> entries = lootEntries(
			item(561, "Nature rune", 40L),
			item(1201, "Amulet of glory", 1L));

		Map<Integer, Long> haPrices = new HashMap<>();
		haPrices.put(561, 90L);
		haPrices.put(1201, 3_000L);

		List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(entries, id -> haPrices.getOrDefault(id, 0L));

		// Nature rune: 40 x 90 = 3,600 -- outranks the single Amulet of
		// glory at 3,000.
		assertEquals(2, rows.size());
		assertEquals("Nature rune", rows.get(0).getItemName());
		assertEquals(3_600L, rows.get(0).getTotalValue());
		assertEquals("Amulet of glory", rows.get(1).getItemName());
		assertEquals(3_000L, rows.get(1).getTotalValue());
	}

	@Test
	public void valueAndSort_tiesBrokenByItemNameAscending()
	{
		List<CurrentSessionSnapshot.LootEntry> entries = lootEntries(
			item(2, "Zulrah's scales", 100L),
			item(1, "Ancient shard", 100L));

		List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(entries, id -> 10L);

		assertEquals("Ancient shard", rows.get(0).getItemName());
		assertEquals("Zulrah's scales", rows.get(1).getItemName());
	}

	// ------------------------------------------------------------------
	// The compact total-session-loot-value figure.
	// ------------------------------------------------------------------
	@Test
	public void sumTotalValue_isTheSumOfEveryRow()
	{
		List<CurrentSessionSnapshot.LootEntry> entries = lootEntries(
			item(995, "Coins", 1_000L),
			item(1127, "Rune platebody", 2L));

		Map<Integer, Long> gePrices = new HashMap<>();
		gePrices.put(995, 1L);
		gePrices.put(1127, 38_000L);

		List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(entries, id -> gePrices.getOrDefault(id, 0L));

		assertEquals(77_000L, LootPricing.sumTotalValue(rows));
	}

	// An unresolvable price (e.g. no GE listing) must never crash the
	// panel -- see LootPricing.safeLookup()'s own javadoc.
	@Test
	public void valueAndSort_unresolvableItemIsPricedAtZero_notSkipped()
	{
		List<CurrentSessionSnapshot.LootEntry> entries = lootEntries(item(9999, "Mystery item", 5L));

		List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(entries, id ->
		{
			throw new RuntimeException("no price available");
		});

		assertEquals(1, rows.size());
		assertEquals(0L, rows.get(0).getTotalValue());
	}

	private static List<CurrentSessionSnapshot.LootEntry> lootEntries(SessionAggregates.LootItemAggregate... items)
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Session session = engine.getCurrentSession();

		SessionAggregates.LootDropGroup group = new SessionAggregates.LootDropGroup();
		group.setSourceName("Gargoyle");
		group.setObservedAt(T0.toString());
		group.setItems(Arrays.asList(items));
		session.getAggregates().getLootDrops().add(group);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(SessionSnapshot.capture(session), T0);
		return snapshot.getLootEntries();
	}

	private static SessionAggregates.LootItemAggregate item(int itemId, String itemName, long quantity)
	{
		SessionAggregates.LootItemAggregate aggregate = new SessionAggregates.LootItemAggregate();
		aggregate.setItemId(itemId);
		aggregate.setItemName(itemName);
		aggregate.setQuantity(quantity);
		return aggregate;
	}
}
