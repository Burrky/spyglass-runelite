package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Integrity audit continuation (UI invalidation): a surface dirtied again
 * WHILE it is being rendered is rendered again afterwards -- the second
 * mutation is never swallowed by the first render's completion.
 */
public class IntegrityAuditContinuationUiTest
{
	private final List<Runnable> edtQueue = new ArrayList<>();
	private final List<Set<UiSurface>> renders = new ArrayList<>();
	private UiRefreshCoalescer coalescer;

	private void runEdt()
	{
		while (!edtQueue.isEmpty())
		{
			edtQueue.remove(0).run();
		}
	}

	@Test
	public void markDuringRender_isRenderedAgainAfterwards()
	{
		coalescer = new UiRefreshCoalescer(edtQueue::add, dirty ->
		{
			renders.add(dirty);
			if (renders.size() == 1)
			{
				// Another runtime mutation lands mid-render.
				coalescer.markDirty(UiSurface.CURRENT_SESSION);
			}
		});
		coalescer.markDirty(UiSurface.CURRENT_SESSION);
		runEdt();
		assertEquals("the mid-render mark produced a second render", 2, renders.size());
		assertEquals(EnumSet.of(UiSurface.CURRENT_SESSION), renders.get(1));
		assertTrue("and nothing is left queued", edtQueue.isEmpty());
	}

	@Test
	public void markForAnotherSurfaceDuringRender_isNotLostEither()
	{
		coalescer = new UiRefreshCoalescer(edtQueue::add, dirty ->
		{
			renders.add(dirty);
			if (renders.size() == 1)
			{
				coalescer.markDirty(UiSurface.LOOT_TRACKER);
				coalescer.markDirty(UiSurface.CURRENT_SESSION);
			}
		});
		coalescer.markDirty(UiSurface.CURRENT_SESSION);
		runEdt();
		assertEquals(2, renders.size());
		assertEquals(EnumSet.of(UiSurface.CURRENT_SESSION, UiSurface.LOOT_TRACKER), renders.get(1));
	}

	@Test
	public void shutdownDuringRender_dropsTheFollowUp()
	{
		coalescer = new UiRefreshCoalescer(edtQueue::add, dirty ->
		{
			renders.add(dirty);
			coalescer.markDirty(UiSurface.HISTORY);
			coalescer.shutdown();
		});
		coalescer.markDirty(UiSurface.HISTORY);
		runEdt();
		assertEquals(1, renders.size());
	}
}
