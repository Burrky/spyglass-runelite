package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.T0;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** The anima plant: type and stage from the decoded patch, withering at stage 8, time shown only when known exactly. */
public class FarmingAnimaTest
{
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();
	private static final long MIN = 60_000L;
	private static final long STAGE = 640 * MIN;

	private static int raw(FarmingProduce p, int stage)
	{
		return FarmingKnowledgeFixtures.raw(FarmingPatchType.ANIMA, p, PatchCondition.GROWING, stage);
	}

	@Test
	public void neverSeen_saysNothing()
	{
		FarmingAnima a = FarmingAnima.of(new HashMap<>(), CATALOG, T0);
		assertEquals(FarmingAnima.State.UNKNOWN, a.getState());
		assertNull(a.text(T0));
	}

	@Test
	public void seenGrowing_withoutAStageChange_showsTheStateOnly()
	{
		Map<String, PatchKnowledge> known = new HashMap<>();
		FarmingKnowledgeFixtures.see(known, FarmingAnima.PATCH_ID, raw(FarmingProduce.KRONOS, 2), T0);
		FarmingAnima a = FarmingAnima.of(known, CATALOG, T0);
		assertEquals(FarmingAnima.State.ACTIVE, a.getState());
		assertTrue(a.isKronosActive());
		assertNull("no exact time", a.getWithersAt());
		assertEquals("Kronos · growing", a.text(T0));
		assertEquals("crops may be ready sooner", a.effect());
		assertEquals("past the latest it can still live: unknown, nothing shown", FarmingAnima.State.UNKNOWN,
			FarmingAnima.of(known, CATALOG, T0 + 7 * STAGE).getState());
	}

	@Test
	public void anObservedStageChange_givesTheExactWitheringTime()
	{
		Map<String, PatchKnowledge> known = new HashMap<>();
		FarmingKnowledgeFixtures.see(known, FarmingAnima.PATCH_ID, raw(FarmingProduce.IASOR, 2), T0);
		FarmingKnowledgeFixtures.change(known, FarmingAnima.PATCH_ID, raw(FarmingProduce.IASOR, 2), raw(FarmingProduce.IASOR, 3), T0 + 10 * MIN);
		FarmingAnima a = FarmingAnima.of(known, CATALOG, T0 + 10 * MIN);
		assertEquals(T0 + 10 * MIN + 5 * STAGE, (long) a.getWithersAt());
		assertTrue(a.text(T0 + 10 * MIN), a.text(T0 + 10 * MIN).startsWith("Iasor · withers in ~"));
		assertEquals(FarmingAnima.State.WITHERING, FarmingAnima.of(known, CATALOG, T0 + 10 * MIN + 5 * STAGE).getState());
	}

	@Test
	public void stageEight_isWithering_stillInEffect_andAnEmptyPatchHasNone()
	{
		Map<String, PatchKnowledge> known = new HashMap<>();
		FarmingKnowledgeFixtures.see(known, FarmingAnima.PATCH_ID, raw(FarmingProduce.KRONOS, 8), T0);
		FarmingAnima a = FarmingAnima.of(known, CATALOG, T0);
		assertEquals(FarmingAnima.State.WITHERING, a.getState());
		assertEquals("Kronos · withering", a.text(T0));
		assertTrue("one stage from death: still in the patch", a.isKronosActive());
		Map<String, PatchKnowledge> empty = new HashMap<>();
		FarmingKnowledgeFixtures.see(empty, FarmingAnima.PATCH_ID, 3, T0);
		FarmingAnima none = FarmingAnima.of(empty, CATALOG, T0);
		assertEquals("No anima plant", none.text(T0));
		assertFalse(none.isKronosActive());
	}
}
