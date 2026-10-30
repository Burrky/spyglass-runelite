package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * Smithing through the REAL session pipeline: SKILLING/smithing from Smithing
 * XP; recipe production, consumed inputs and per-recipe occurrences land on it
 * and nowhere else; a recipe / process switch stays one session; Superheat's
 * Magic XP rides along as a metric (Magic is never lifecycle evidence, so it
 * neither steals nor creates ownership); Giants' Foundry stays its own
 * structured session and never receives Smithing companion data; leaving it,
 * ordinary Smithing resumes as SKILLING/smithing.
 */
public class SmithingSessionTest extends StructuredActivityTestSupport
{
	private static final Instant T0 = Instant.parse("2026-10-13T20:00:00Z");
	private static final int STEEL_BAR = 2353;
	private static final int IRON_ORE = 440;
	private static final int COAL = 453;
	private static final int MITH_BAR = 2359;
	private static final int MITH_PLATEBODY = 1121;
	private static final int FOUNDRY = 13491;

	private static SessionSignal production(Instant at, String recipe, int output, long made, int[] inputs, long[] used)
	{
		List<SessionSignal.LootDrop> out = new ArrayList<>();
		out.add(new SessionSignal.LootDrop(output, "Output", made));
		List<SessionSignal.LootDrop> in = new ArrayList<>();
		Map<String, Long> occ = new LinkedHashMap<>();
		occ.put("made:" + recipe, made);
		for (int i = 0; i < inputs.length; i++)
		{
			in.add(new SessionSignal.LootDrop(inputs[i], "Input", used[i]));
			occ.put("in:" + recipe + ":" + inputs[i], used[i]);
		}
		Map<String, Long> millis = new LinkedHashMap<>();
		millis.put(recipe, 30_000L);
		return SessionSignal.skillProduction(at, new SkillProductionUpdate("SMITHING", recipe, out, null, millis)
			.withExtras(null, occ).withConsumed(in));
	}

	private static boolean isSmithing(Session s)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.SKILLING
			&& "smithing".equals(s.getActivityIdentity().getActivityKey());
	}

	private Instant smithFor(Instant start, int windows, String... alsoSkills)
	{
		Instant t = start;
		for (int i = 1; i <= windows; i++)
		{
			t = start.plusSeconds(30L * i);
			List<SessionSignal> s = new ArrayList<>();
			s.add(xpAt("SMITHING", 1_500, t));
			for (String other : alsoSkills)
			{
				s.add(xpAt(other, 900, t));
			}
			onTick(t, s.toArray(new SessionSignal[0]));
		}
		return t;
	}

	@Test
	public void smithingXp_startsTheSession_productionAndInputsLandOnIt_historyRowIsRecipeAware()
	{
		Instant t = smithFor(T0, 2);
		assertTrue(isSmithing(current()));
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "smelt_steel", STEEL_BAR, 14, new int[]{IRON_ORE, COAL}, new long[]{14, 28}));
		onTick(t.plusSeconds(2), production(t.plusSeconds(2), "smelt_steel", STEEL_BAR, 13, new int[]{IRON_ORE, COAL}, new long[]{13, 26}));
		SessionAggregates.SkillSessionStats stats = current().getAggregates().getSkillStats();
		assertNotNull(stats);
		assertEquals(Long.valueOf(27), stats.getOccurrences().get("made:smelt_steel"));
		long coal = 0;
		for (SessionAggregates.ProducedItemAggregate c : stats.getConsumed())
		{
			coal += c.getItemId() == COAL ? c.getQuantity() : 0;
		}
		assertEquals(54L, coal);
		Session s = current();
		s.setFinalizedAt(t.plusSeconds(60).toString());
		assertEquals(Arrays.asList("Steel bars", "27 smelted"), HistoryEntry.from(s).getSkillRowFacts());
	}

	@Test
	public void recipeAndProcessSwitch_staysOneSmithingSession()
	{
		Instant t = smithFor(T0, 2);
		String id = current().getSessionId();
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "smelt_steel", STEEL_BAR, 5, new int[]{IRON_ORE}, new long[]{5}));
		t = smithFor(t.plusSeconds(1), 3);
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "mithril_platebody", MITH_PLATEBODY, 2, new int[]{MITH_BAR}, new long[]{10}));
		t = smithFor(t.plusSeconds(1), 3);
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "bf_steel", STEEL_BAR, 27, new int[]{IRON_ORE}, new long[]{27}));
		assertEquals(id, current().getSessionId());
		assertEquals(3, current().getAggregates().getSkillStats().getMethodMillis().size());
		Session s = current();
		s.setFinalizedAt(t.plusSeconds(60).toString());
		List<String> facts = HistoryEntry.from(s).getSkillRowFacts();
		assertTrue(facts.toString(), facts.contains("+2 recipes"));
	}

	@Test
	public void superheat_magicXpRidesAlong_theSessionStaysSmithing()
	{
		Instant t = smithFor(T0, 6, "MAGIC");
		assertTrue(isSmithing(current()));
		String id = current().getSessionId();
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "superheat_steel", STEEL_BAR, 6, new int[]{IRON_ORE}, new long[]{6}));
		t = smithFor(t.plusSeconds(1), 4, "MAGIC");
		assertEquals(id, current().getSessionId());
		assertTrue(isSmithing(current()));
		Long magic = current().getAggregates().getXpGainedBySkill().get("MAGIC");
		assertTrue("Magic XP is the session's metric: " + magic, magic != null && magic > 0);
	}

	@Test
	public void magicXpAlone_neverCreatesOrStealsASmithingSession()
	{
		Instant t = T0;
		for (int i = 1; i <= 6; i++)
		{
			t = T0.plusSeconds(30L * i);
			onTick(t, xpAt("MAGIC", 900, t));
		}
		assertFalse(isSmithing(current()));
		t = smithFor(t, 3);
		assertTrue(isSmithing(current()));
		String id = current().getSessionId();
		for (int i = 1; i <= 4; i++)
		{
			t = t.plusSeconds(30);
			onTick(t, xpAt("MAGIC", 900, t));
		}
		assertEquals("a few casts of unrelated Magic never replace the session", id, current().getSessionId());
	}

	@Test
	public void anotherSkill_takesOwnershipNormally()
	{
		smithFor(T0, 3);
		Instant t = T0.plusSeconds(120);
		for (int i = 1; i <= 6; i++)
		{
			t = t.plusSeconds(30);
			onTick(t, xpAt("FISHING", 2_000, t));
		}
		assertEquals("fishing", current().getActivityIdentity().getActivityKey());
	}

	@Test
	public void giantsFoundry_isItsOwnSession_smithingCompanionDataNeverLandsOnIt_leavingResumesSmithing()
	{
		Instant t = T0;
		onTick(t, enter("giants_foundry", FOUNDRY, t));
		for (int i = 1; i <= 4; i++)
		{
			t = T0.plusSeconds(15L * i);
			onTick(t, xpAt("SMITHING", 2_000, t));
		}
		Session foundry = current();
		assertEquals(ActivityType.STRUCTURED_ACTIVITY, foundry.getActivityIdentity().getActivityType());
		assertEquals("giants_foundry", foundry.getActivityIdentity().getActivityKey());
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "mithril_platebody", MITH_PLATEBODY, 1, new int[]{MITH_BAR}, new long[]{5}));
		assertNull("Smithing companion data never lands on the Foundry session", current().getAggregates().getSkillStats());
		assertTrue(current().getAggregates().getProducedItems() == null || current().getAggregates().getProducedItems().isEmpty());
		t = t.plusSeconds(30);
		onTick(t, leave(t));
		t = smithFor(t, 6);
		assertTrue("ordinary Smithing resumes as its own session", isSmithing(current()));
	}
}
