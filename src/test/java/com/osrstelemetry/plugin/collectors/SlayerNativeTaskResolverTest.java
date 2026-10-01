package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Every resolution branch of RuneLite 1.13.1's SlayerPlugin.updateTask(),
 * reproduced by Spyglass's own pure resolver. The cache is a map-backed
 * fake (no Mockito in this project).
 */
public class SlayerNativeTaskResolverTest
{
	static final int GARGOYLES_TARGET = 46;
	static final int GARGOYLES_ROW = 9046;
	static final int BOSS_ID_KRAKEN = 3;
	static final int KRAKEN_ROW = 9300;
	static final int AREA_SLAYER_TOWER = 12;

	static final class FakeLookup implements SlayerTaskLookup
	{
		final Map<Integer, Integer> taskRows = new HashMap<>();
		final Map<Integer, Integer> bossRows = new HashMap<>();
		final Map<Integer, String> names = new HashMap<>();
		final Map<Integer, String> areas = new HashMap<>();

		@Override
		public Integer taskRowForTaskId(int taskId)
		{
			return taskRows.get(taskId);
		}

		@Override
		public Integer taskRowForBossId(int bossId)
		{
			return bossRows.get(bossId);
		}

		@Override
		public String taskName(int taskRow)
		{
			return names.get(taskRow);
		}

		@Override
		public String areaName(int areaId)
		{
			return areas.get(areaId);
		}
	}

	static FakeLookup standardCache()
	{
		FakeLookup l = new FakeLookup();
		l.taskRows.put(GARGOYLES_TARGET, GARGOYLES_ROW);
		l.names.put(GARGOYLES_ROW, "Gargoyles");
		l.bossRows.put(BOSS_ID_KRAKEN, KRAKEN_ROW);
		l.names.put(KRAKEN_ROW, "Kraken");
		l.areas.put(AREA_SLAYER_TOWER, "Slayer Tower");
		return l;
	}

	static SlayerNativeTaskResolver.RawVars raw(int count, int target, int boss, int area, int original)
	{
		return new SlayerNativeTaskResolver.RawVars(count, target, boss, area, original, 0, 0, 0);
	}

	@Test
	public void activeTask_resolvesNameRemainingInitial_noLocationWhenAreaZero()
	{
		SlayerNativeTask t = SlayerNativeTaskResolver.resolve(raw(137, GARGOYLES_TARGET, 0, 0, 180), standardCache());
		assertTrue(t.isActive());
		assertEquals("Gargoyles", t.getTaskName());
		assertNull(t.getTaskLocation());
		assertEquals(137, t.getRemaining());
		assertEquals(180, t.getInitialAmount());
	}

	@Test
	public void areaTask_resolvesLocationFromSlayerAreaTable()
	{
		SlayerNativeTask t = SlayerNativeTaskResolver.resolve(raw(50, GARGOYLES_TARGET, 0, AREA_SLAYER_TOWER, 60), standardCache());
		assertEquals("Slayer Tower", t.getTaskLocation());
	}

	@Test
	public void bossTask_target98_resolvesThroughBossSublist()
	{
		SlayerNativeTask t = SlayerNativeTaskResolver.resolve(
			raw(10, SlayerNativeTaskResolver.BOSS_TASK_TARGET, BOSS_ID_KRAKEN, 0, 10), standardCache());
		assertTrue(t.isActive());
		assertEquals("Kraken", t.getTaskName());
	}

	@Test
	public void zeroOrNegativeCount_isNoTask_evenWithStaleTargetAndArea()
	{
		assertEquals(SlayerNativeTask.Status.NO_TASK,
			SlayerNativeTaskResolver.resolve(raw(0, GARGOYLES_TARGET, 0, AREA_SLAYER_TOWER, 180), standardCache()).getStatus());
		assertEquals(SlayerNativeTask.Status.NO_TASK,
			SlayerNativeTaskResolver.resolve(raw(-1, GARGOYLES_TARGET, 0, 0, 180), standardCache()).getStatus());
		SlayerNativeTask none = SlayerNativeTaskResolver.resolve(raw(0, 0, 0, 0, 0), standardCache());
		assertNull(none.getTaskName());
		assertEquals(0, none.getRemaining());
		assertEquals(0, none.getInitialAmount());
	}

	@Test
	public void missingCacheRows_areUnresolved_neverNoTask()
	{
		FakeLookup cache = standardCache();
		assertTrue("unknown task id", SlayerNativeTaskResolver.resolve(raw(5, 999, 0, 0, 5), cache).isUnresolved());
		assertTrue("unknown boss id", SlayerNativeTaskResolver.resolve(
			raw(5, SlayerNativeTaskResolver.BOSS_TASK_TARGET, 77, 0, 5), cache).isUnresolved());
		assertTrue("unknown area", SlayerNativeTaskResolver.resolve(raw(5, GARGOYLES_TARGET, 0, 404, 5), cache).isUnresolved());
		cache.names.remove(GARGOYLES_ROW);
		assertTrue("row without a name", SlayerNativeTaskResolver.resolve(raw(5, GARGOYLES_TARGET, 0, 0, 5), cache).isUnresolved());
	}

	@Test
	public void quantityModifier_adjustsInitialAmount_onlyForModifierId2()
	{
		SlayerNativeTaskResolver.RawVars plus = new SlayerNativeTaskResolver.RawVars(100, GARGOYLES_TARGET, 0, 0, 150, 2, 25, 0);
		SlayerNativeTaskResolver.RawVars minus = new SlayerNativeTaskResolver.RawVars(100, GARGOYLES_TARGET, 0, 0, 150, 2, 25, 1);
		SlayerNativeTaskResolver.RawVars otherModifier = new SlayerNativeTaskResolver.RawVars(100, GARGOYLES_TARGET, 0, 0, 150, 1, 25, 0);
		assertEquals(175, SlayerNativeTaskResolver.resolve(plus, standardCache()).getInitialAmount());
		assertEquals(125, SlayerNativeTaskResolver.resolve(minus, standardCache()).getInitialAmount());
		assertEquals(150, SlayerNativeTaskResolver.resolve(otherModifier, standardCache()).getInitialAmount());
	}

	@Test
	public void initialAmount_mayBeZeroWhileOriginalVarpStillSyncing_neverGuessed()
	{
		SlayerNativeTask t = SlayerNativeTaskResolver.resolve(raw(116, GARGOYLES_TARGET, 0, 0, 0), standardCache());
		assertTrue(t.isActive());
		assertEquals(0, t.getInitialAmount());
	}

	@Test
	public void resolver_isStateless_sameInputSameResult()
	{
		FakeLookup cache = standardCache();
		SlayerNativeTaskResolver.RawVars r = raw(20, GARGOYLES_TARGET, 0, AREA_SLAYER_TOWER, 40);
		assertEquals(SlayerNativeTaskResolver.resolve(r, cache), SlayerNativeTaskResolver.resolve(r, cache));
		assertFalse(SlayerNativeTaskResolver.resolve(r, cache).equals(
			SlayerNativeTaskResolver.resolve(raw(19, GARGOYLES_TARGET, 0, AREA_SLAYER_TOWER, 40), cache)));
	}

	@Test
	public void rawCountOriginal_isRetainedForDiagnostics()
	{
		assertEquals(180, SlayerNativeTaskResolver.resolve(raw(137, GARGOYLES_TARGET, 0, 0, 180), standardCache())
			.getRaw().getCountOriginal());
	}
}
