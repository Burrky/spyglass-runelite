package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.model.StorageState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Pure,
 * RuneLite/Client-independent coverage of LoadoutArchive: query
 * semantics (strictly-before / at-or-after / at-or-before), pruning
 * by age and by count, defensive copying, and account-switch reset.
 * Plain JUnit, no Mockito, matching this project's established test
 * style (see LootTrackerIndexTest).
 */
public class LoadoutArchiveTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	private static List<StorageState.StorageItem> oneItem(int itemId)
	{
		return Collections.singletonList(new StorageState.StorageItem(0, itemId, "item-" + itemId, 1, null));
	}

	@Test
	public void record_thenLatestStrictlyBefore_findsEntryStrictlyEarlier()
	{
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(T0, oneItem(1), Collections.emptyList());
		archive.record(T0.plusSeconds(60), oneItem(2), Collections.emptyList());

		LoadoutArchive.Entry found = archive.latestStrictlyBefore(T0.plusSeconds(90));

		assertNotNull(found);
		assertEquals(T0.plusSeconds(60), found.getObservedAt());
		assertEquals(2, found.getInventory().get(0).getItemId());
	}

	@Test
	public void latestStrictlyBefore_excludesEntryExactlyAtInstant()
	{
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(T0, oneItem(1), Collections.emptyList());

		assertNull("an entry observed exactly AT the query instant must not count as 'strictly before' it",
			archive.latestStrictlyBefore(T0));
	}

	@Test
	public void earliestAtOrAfter_includesEntryExactlyAtInstant()
	{
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(T0, oneItem(1), Collections.emptyList());
		archive.record(T0.plusSeconds(60), oneItem(2), Collections.emptyList());

		LoadoutArchive.Entry found = archive.earliestAtOrAfter(T0);

		assertNotNull(found);
		assertEquals(T0, found.getObservedAt());
		assertEquals(1, found.getInventory().get(0).getItemId());
	}

	@Test
	public void earliestAtOrAfter_picksEarliestQualifyingEntry_notLatest()
	{
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(T0.plusSeconds(30), oneItem(1), Collections.emptyList());
		archive.record(T0.plusSeconds(60), oneItem(2), Collections.emptyList());
		archive.record(T0.plusSeconds(90), oneItem(3), Collections.emptyList());

		LoadoutArchive.Entry found = archive.earliestAtOrAfter(T0.plusSeconds(10));

		assertEquals(T0.plusSeconds(30), found.getObservedAt());
	}

	@Test
	public void latestAtOrBefore_includesEntryExactlyAtInstant_picksMostRecentQualifying()
	{
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(T0, oneItem(1), Collections.emptyList());
		archive.record(T0.plusSeconds(30), oneItem(2), Collections.emptyList());
		archive.record(T0.plusSeconds(90), oneItem(3), Collections.emptyList());

		LoadoutArchive.Entry found = archive.latestAtOrBefore(T0.plusSeconds(30));

		assertNotNull(found);
		assertEquals(T0.plusSeconds(30), found.getObservedAt());
	}

	@Test
	public void queries_onEmptyArchive_returnNull()
	{
		LoadoutArchive archive = new LoadoutArchive();

		assertNull(archive.latestStrictlyBefore(T0));
		assertNull(archive.earliestAtOrAfter(T0));
		assertNull(archive.latestAtOrBefore(T0));
	}

	@Test
	public void record_defensivelyCopiesLists_laterMutationNotReflected()
	{
		LoadoutArchive archive = new LoadoutArchive();
		List<StorageState.StorageItem> inventory = new ArrayList<>(oneItem(1));

		archive.record(T0, inventory, Collections.emptyList());
		inventory.clear();

		LoadoutArchive.Entry found = archive.latestAtOrBefore(T0);
		assertEquals(1, found.getInventory().size());
	}

	@Test
	public void entryLists_areUnmodifiable()
	{
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(T0, oneItem(1), Collections.emptyList());

		LoadoutArchive.Entry found = archive.latestAtOrBefore(T0);
		try
		{
			found.getInventory().add(new StorageState.StorageItem(1, 2, "x", 1, null));
			org.junit.Assert.fail("Entry's inventory list must be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// expected
		}
	}

	@Test
	public void prune_dropsEntriesOlderThanMaxAge()
	{
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(T0, oneItem(1), Collections.emptyList());

		// A later record() call prunes based on ITS OWN observedAt as "now".
		Instant wellPastMaxAge = T0.plus(LoadoutArchive.MAX_AGE).plusSeconds(1);
		archive.record(wellPastMaxAge, oneItem(2), Collections.emptyList());

		assertNull("the T0 entry must have aged out once MAX_AGE has elapsed -- a query that could ONLY match it must find nothing",
			archive.latestStrictlyBefore(T0.plusSeconds(1)));
		assertNotNull("the fresh entry must still be present",
			archive.latestAtOrBefore(wellPastMaxAge));
	}

	@Test
	public void prune_boundsEntryCountAtMaxEntries()
	{
		LoadoutArchive archive = new LoadoutArchive();
		for (int i = 0; i < LoadoutArchive.MAX_ENTRIES + 20; i++)
		{
			// One-second spacing keeps every entry well within MAX_AGE of
			// the last one recorded, so only the count bound is exercised.
			archive.record(T0.plusSeconds(i), oneItem(i), Collections.emptyList());
		}

		assertEquals(LoadoutArchive.MAX_ENTRIES, archive.size());
		// The oldest entries (0..19) must have been evicted in favor of the newest.
		assertNull(archive.latestAtOrBefore(T0.plusSeconds(5)));
	}

	@Test
	public void resetForAccountSwitch_clearsEverything()
	{
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(T0, oneItem(1), Collections.emptyList());
		assertTrue(archive.size() > 0);

		archive.resetForAccountSwitch();

		assertEquals(0, archive.size());
		assertNull(archive.latestAtOrBefore(T0.plusSeconds(1000)));
	}
}
