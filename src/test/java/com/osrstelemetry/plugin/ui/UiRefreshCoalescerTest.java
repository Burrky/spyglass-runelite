package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** One coalesced EDT pass per burst of dirty marks, from any thread. */
public class UiRefreshCoalescerTest
{
	/** A manual stand-in for SwingUtilities::invokeLater. */
	private final List<Runnable> queued = new ArrayList<>();
	private final List<Set<UiSurface>> refreshed = new ArrayList<>();
	private final UiRefreshCoalescer coalescer = new UiRefreshCoalescer(queued::add, refreshed::add);

	private void runQueued()
	{
		List<Runnable> now = new ArrayList<>(queued);
		queued.clear();
		now.forEach(Runnable::run);
	}

	@Test
	public void oneMutation_queuesOneRefresh()
	{
		coalescer.markDirty(UiSurface.CURRENT_SESSION);
		assertEquals(1, queued.size());
		runQueued();
		assertEquals(1, refreshed.size());
		assertEquals(EnumSet.of(UiSurface.CURRENT_SESSION), refreshed.get(0));
	}

	@Test
	public void manyMutationsBeforeDrain_stillOneRefresh_withTheUnionOfSurfaces()
	{
		// e.g. Ranged + Hitpoints + Slayer XP, task progress, loot, 30 prices...
		for (int i = 0; i < 30; i++)
		{
			coalescer.markDirty(UiSurface.CURRENT_SESSION);
		}
		coalescer.markDirty(UiSurface.LOOT_TRACKER);
		coalescer.markDirty(UiSurface.CURRENT_SESSION);
		assertEquals("one queued EDT task", 1, queued.size());
		runQueued();
		assertEquals(1, refreshed.size());
		assertEquals("Current Session and Loot both dirty in one burst",
			EnumSet.of(UiSurface.CURRENT_SESSION, UiSurface.LOOT_TRACKER), refreshed.get(0));
	}

	@Test
	public void mutationAfterDrain_queuesANewRefresh()
	{
		coalescer.markDirty(UiSurface.LOOT_TRACKER);
		runQueued();
		coalescer.markDirty(UiSurface.HISTORY);
		assertEquals(1, queued.size());
		runQueued();
		assertEquals(2, refreshed.size());
		assertEquals(EnumSet.of(UiSurface.HISTORY), refreshed.get(1));
	}

	@Test
	public void shutdown_ignoresLaterMarks_andMakesAQueuedPassANoOp()
	{
		coalescer.markDirty(UiSurface.CURRENT_SESSION);
		coalescer.shutdown();
		runQueued();
		coalescer.markDirty(UiSurface.CURRENT_SESSION);
		runQueued();
		assertTrue(refreshed.isEmpty());
		assertTrue(queued.isEmpty());
	}

	@Test
	public void concurrentMarksFromManyThreads_neverQueueMoreThanOnePassAtATime() throws Exception
	{
		List<Runnable> tasks = java.util.Collections.synchronizedList(new ArrayList<>());
		List<Set<UiSurface>> seen = java.util.Collections.synchronizedList(new ArrayList<>());
		UiRefreshCoalescer c = new UiRefreshCoalescer(tasks::add, seen::add);
		int threads = 8;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		for (int t = 0; t < threads; t++)
		{
			UiSurface surface = UiSurface.values()[t % UiSurface.values().length];
			new Thread(() ->
			{
				try
				{
					start.await();
					for (int i = 0; i < 1000; i++)
					{
						c.markDirty(surface);
					}
				}
				catch (InterruptedException ignored)
				{
				}
				done.countDown();
			}).start();
		}
		start.countDown();
		assertTrue(done.await(10, TimeUnit.SECONDS));
		assertEquals("8000 marks, nothing drained: exactly one queued pass", 1, tasks.size());
		tasks.get(0).run();
		assertEquals(EnumSet.allOf(UiSurface.class), seen.get(0));
	}

	@Test
	public void aRefreshThatThrows_doesNotWedgeTheCoalescer()
	{
		List<Runnable> tasks = new ArrayList<>();
		int[] calls = {0};
		UiRefreshCoalescer c = new UiRefreshCoalescer(tasks::add, dirty ->
		{
			calls[0]++;
			throw new IllegalStateException("render failed");
		});
		c.markDirty(UiSurface.CURRENT_SESSION);
		try
		{
			tasks.remove(0).run();
		}
		catch (IllegalStateException expected)
		{
		}
		c.markDirty(UiSurface.CURRENT_SESSION);
		assertEquals("a later mark still queues", 1, tasks.size());
		try
		{
			tasks.remove(0).run();
		}
		catch (IllegalStateException expected)
		{
		}
		assertEquals(2, calls[0]);
	}
}
