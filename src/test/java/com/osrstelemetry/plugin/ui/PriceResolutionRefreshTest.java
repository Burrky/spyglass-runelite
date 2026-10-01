package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.loottracker.LootTrackerIndex;
import com.osrstelemetry.plugin.loottracker.LootTrackerItem;
import com.osrstelemetry.plugin.loottracker.LootTrackerRecord;
import com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Prices never gate visibility: items/quantities render before any value
 * is known, and a burst of resolved prices becomes ONE coalesced refresh.
 */
public class PriceResolutionRefreshTest
{
	@Test
	public void burstOfResolvedPrices_coalescesIntoOneRefresh()
	{
		List<Runnable> clientThread = new ArrayList<>();
		ItemValuationCache cache = new ItemValuationCache(clientThread::add, id -> id * 10L, id -> id);
		List<Runnable> edt = new ArrayList<>();
		List<Set<UiSurface>> refreshes = new ArrayList<>();
		UiRefreshCoalescer coalescer = new UiRefreshCoalescer(edt::add, refreshes::add);
		cache.setOnResolved(() -> coalescer.markDirty(UiSurface.LOOT_TRACKER));

		for (int id = 1; id <= 30; id++)
		{
			assertEquals("unresolved: 0 now, never blocks", 0L, cache.getGePrice(id));
			cache.getGePrice(id); // a second ask never queues a second lookup
		}
		assertEquals(30, clientThread.size());
		long versionBefore = cache.getVersion();
		clientThread.forEach(Runnable::run);
		assertEquals(versionBefore + 30, cache.getVersion());
		assertEquals("30 resolutions, one queued EDT pass", 1, edt.size());
		edt.get(0).run();
		assertEquals(1, refreshes.size());
		assertEquals(70L, cache.getGePrice(7));
	}

	@Test
	public void itemsAndQuantities_renderBeforeAnyPriceIsKnown()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(new LootTrackerRecord("e1", "Goblin", 3029, Instant.now(),
			Arrays.asList(new LootTrackerItem(995, "Coins", 12), new LootTrackerItem(526, "Bones", 1))));
		LootTrackerView view = new LootTrackerView(null, null, new OsrsTelemetryConfig()
		{
		}, null);
		view.render(LootTrackerSnapshot.from(index, null));
		List<LootGridCell> cells = new ArrayList<>();
		collect(view, cells);
		assertEquals("both items visible with no resolved price", 2, cells.size());
		assertTrue(cells.get(0).getToolTipText().contains("Coins") || cells.get(1).getToolTipText().contains("Coins"));
	}

	private static void collect(Container c, List<LootGridCell> out)
	{
		for (Component k : c.getComponents())
		{
			if (k instanceof LootGridCell)
			{
				out.add((LootGridCell) k);
			}
			else if (k instanceof Container)
			{
				collect((Container) k, out);
			}
		}
	}
}
