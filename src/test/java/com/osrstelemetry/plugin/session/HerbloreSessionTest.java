package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.history.HistoryEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Herblore through the REAL session pipeline: SKILLING/herblore from Herblore
 * XP; recipe production (exact items), consumed inputs and per-recipe
 * occurrences land on it and nowhere else; a recipe switch stays one session
 * with separate per-recipe time; another skill takes ownership normally;
 * processing without Herblore XP (unfinished potions) never starts a session;
 * History's list row is recipe-aware ("Super restore • 6 mixes • 18 doses").
 */
public class HerbloreSessionTest extends StructuredActivityTestSupport
{
	private static final Instant T0 = Instant.parse("2026-10-13T20:00:00Z");
	private static final int SR3 = 3026;
	private static final int SNAP_UNF = 3004;
	private static final int EGGS = 223;
	private static final int PP3 = 139;

	private static SessionSignal production(Instant at, String recipe, int output, long made, long units, int unf, int secondary)
	{
		List<SessionSignal.LootDrop> out = new ArrayList<>();
		out.add(new SessionSignal.LootDrop(output, "Potion(3)", made));
		List<SessionSignal.LootDrop> used = new ArrayList<>();
		used.add(new SessionSignal.LootDrop(unf, "Unfinished", made));
		used.add(new SessionSignal.LootDrop(secondary, "Secondary", made));
		Map<String, Long> millis = new LinkedHashMap<>();
		millis.put(recipe, 30_000L);
		Map<String, Long> occ = new LinkedHashMap<>();
		occ.put("mix:" + recipe, made);
		occ.put("units:" + recipe, units);
		return SessionSignal.skillProduction(at, new SkillProductionUpdate("HERBLORE", recipe, out, null, millis)
			.withExtras(null, occ).withConsumed(used));
	}

	private static boolean isHerblore(Session s)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.SKILLING
			&& "herblore".equals(s.getActivityIdentity().getActivityKey());
	}

	private Instant mixFor(Instant start, int windows)
	{
		Instant t = start;
		for (int i = 1; i <= windows; i++)
		{
			t = start.plusSeconds(30L * i);
			onTick(t, xpAt("HERBLORE", 2_800, t));
		}
		return t;
	}

	@Test
	public void herbloreXp_startsTheSession_productionInputsAndRecipeCountsLandOnIt()
	{
		Instant t = mixFor(T0, 2);
		assertTrue(isHerblore(current()));
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "super_restore", SR3, 3, 9, SNAP_UNF, EGGS));
		onTick(t.plusSeconds(2), production(t.plusSeconds(2), "super_restore", SR3, 3, 9, SNAP_UNF, EGGS));
		SessionAggregates.SkillSessionStats stats = current().getAggregates().getSkillStats();
		assertNotNull(stats);
		assertEquals(Long.valueOf(6), stats.getOccurrences().get("mix:super_restore"));
		assertEquals(Long.valueOf(18), stats.getOccurrences().get("units:super_restore"));
		long eggs = 0;
		for (SessionAggregates.ProducedItemAggregate c : stats.getConsumed())
		{
			eggs += c.getItemId() == EGGS ? c.getQuantity() : 0;
		}
		assertEquals(6L, eggs);
		Session s = current();
		s.setFinalizedAt(t.plusSeconds(60).toString());
		HistoryEntry entry = HistoryEntry.from(s);
		assertEquals(Arrays.asList("Super restore", "6 mixes", "18 doses"), entry.getSkillRowFacts());
	}

	@Test
	public void recipeSwitch_staysOneHerbloreSession_withSeparateRecipeTime()
	{
		Instant t = mixFor(T0, 2);
		String id = current().getSessionId();
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "prayer", PP3, 3, 9, 99, 231));
		t = mixFor(t.plusSeconds(1), 3);
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "super_restore", SR3, 3, 9, SNAP_UNF, EGGS));
		assertEquals(id, current().getSessionId());
		assertEquals(2, current().getAggregates().getSkillStats().getMethodMillis().size());
		Session s = current();
		s.setFinalizedAt(t.plusSeconds(60).toString());
		assertTrue(HistoryEntry.from(s).getSkillRowFacts().contains("+1 recipe"));
	}

	@Test
	public void anotherSkill_takesOwnershipNormally()
	{
		mixFor(T0, 3);
		Instant t = T0.plusSeconds(120);
		for (int i = 1; i <= 6; i++)
		{
			t = t.plusSeconds(30);
			onTick(t, xpAt("FISHING", 2_000, t));
		}
		assertEquals("fishing", current().getActivityIdentity().getActivityKey());
	}

	@Test
	public void processingWithoutHerbloreXp_neverStartsAHerbloreSession()
	{
		Instant t = T0;
		for (int i = 1; i <= 6; i++)
		{
			t = T0.plusSeconds(20L * i);
			// Unfinished potions: real item processing, but no Herblore XP -- no session of its own.
			onTick(t, production(t, "ranarr_unf", 99, 14, 14, 257, 227));
		}
		assertFalse(isHerblore(current()));
	}
}
