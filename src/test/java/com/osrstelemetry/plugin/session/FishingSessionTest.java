package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.history.HistoryEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Fishing through the REAL session pipeline -- the same metric-only
 * SKILL_PRODUCTION path Mining uses, keyed by skill: the SKILLING/fishing
 * session is created by Fishing XP alone; Fishing production lands on it
 * and on nothing else (never Mining, never Tempoross); Mining production
 * never lands on it.
 */
public class FishingSessionTest extends StructuredActivityTestSupport
{
	private static final Instant T0 = Instant.parse("2026-10-12T16:00:00Z");
	private static final int LOBSTER = 377;
	private static final int TROUT = 335;
	private static final int SALMON = 331;
	private static final int KARAMBWAN = 3142;
	private static final int LEAPING_TROUT = 11328;
	private static final int IRON = 440;
	private static final String TEMPOROSS = "tempoross";
	private static final int TEMPOROSS_ARENA = 12076;

	private static SessionSignal production(Instant at, String skill, String method, Object... itemQty)
	{
		List<SessionSignal.LootDrop> items = new ArrayList<>();
		for (int i = 0; i < itemQty.length; i += 2)
		{
			items.add(new SessionSignal.LootDrop((Integer) itemQty[i], "Item " + itemQty[i], ((Number) itemQty[i + 1]).longValue()));
		}
		Map<String, Long> millis = new LinkedHashMap<>();
		if (method != null)
		{
			millis.put(method, 30_000L);
		}
		return SessionSignal.skillProduction(at, new SkillProductionUpdate(skill, method, items, null, millis));
	}

	private static SessionSignal fishing(Instant at, String method, Object... itemQty)
	{
		return production(at, "FISHING", method, itemQty);
	}

	private static boolean isSkill(Session s, String key)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.SKILLING
			&& key.equals(s.getActivityIdentity().getActivityKey());
	}

	private Instant fishFor(Instant start, int windows)
	{
		Instant t = start;
		for (int i = 1; i <= windows; i++)
		{
			t = start.plusSeconds(30L * i);
			onTick(t, xpAt("FISHING", 1_200, t));
		}
		return t;
	}

	private static long produced(Session s, int item)
	{
		if (s.getAggregates().getProducedItems() == null)
		{
			return 0L;
		}
		for (SessionAggregates.ProducedItemAggregate p : s.getAggregates().getProducedItems())
		{
			if (p.getItemId() != null && p.getItemId() == item)
			{
				return p.getQuantity();
			}
		}
		return 0L;
	}

	@Test
	public void fishingXp_startsTheSession_productionLandsOnIt()
	{
		Instant t = fishFor(T0, 2);
		assertTrue(isSkill(current(), "fishing"));
		onTick(t.plusSeconds(1), fishing(t.plusSeconds(1), "lobster", LOBSTER, 26));
		SessionAggregates.SkillSessionStats stats = current().getAggregates().getSkillStats();
		assertEquals(26L, produced(current(), LOBSTER));
		assertEquals("fishing", stats.getSkillKey());
		assertEquals("lobster", stats.getLastMethod());
	}

	@Test
	public void flyFishing_multiOutput_staysOneMethodInTheSession()
	{
		Instant t = fishFor(T0, 2);
		onTick(t.plusSeconds(1), fishing(t.plusSeconds(1), "fly", TROUT, 18, SALMON, 9));
		onTick(t.plusSeconds(2), fishing(t.plusSeconds(2), "fly", TROUT, 4));
		assertEquals(22L, produced(current(), TROUT));
		assertEquals(9L, produced(current(), SALMON));
		assertEquals(1, current().getAggregates().getSkillStats().getMethodMillis().size());
	}

	@Test
	public void fishingProduction_neverLandsOnAMiningSession_andViceVersa()
	{
		Instant t = T0;
		for (int i = 1; i <= 4; i++)
		{
			t = T0.plusSeconds(30L * i);
			onTick(t, xpAt("MINING", 2_000, t));
		}
		assertTrue(isSkill(current(), "mining"));
		onTick(t.plusSeconds(1), fishing(t.plusSeconds(1), "lobster", LOBSTER, 5));
		assertEquals(0L, produced(current(), LOBSTER));
		assertNull(current().getAggregates().getSkillStats());

		Instant f = fishFor(t.plusSeconds(10), 10);
		assertTrue(isSkill(current(), "fishing"));
		onTick(f.plusSeconds(1), production(f.plusSeconds(1), "MINING", "iron", IRON, 7));
		assertEquals(0L, produced(current(), IRON));
	}

	@Test
	public void productionAlone_neverStartsAFishingSession()
	{
		onTick(T0, fishing(T0, "lobster", LOBSTER, 5));
		assertNull(current());
	}

	@Test
	public void tempoross_isNeverSwallowedOrDuplicated_byFishing()
	{
		onTick(T0, enter(TEMPOROSS, TEMPOROSS_ARENA, T0));
		Instant t = T0;
		for (int i = 1; i <= 6; i++)
		{
			t = T0.plusSeconds(20L * i);
			onTick(t, xpAt("FISHING", 400, t));
		}
		Session s = current();
		assertTrue(s != null && s.getActivityIdentity().getActivityType() == ActivityType.STRUCTURED_ACTIVITY
			&& TEMPOROSS.equals(s.getActivityIdentity().getActivityKey()));
		// Even if a Fishing update arrived during the game, it never lands on Tempoross.
		onTick(t.plusSeconds(1), fishing(t.plusSeconds(1), null, LOBSTER, 3));
		assertEquals(s.getSessionId(), current().getSessionId());
		assertEquals(0L, produced(current(), LOBSTER));
		assertNull(current().getAggregates().getSkillStats());
	}

	@Test
	public void barbarianFishing_fishingStaysPrimary_despiteAgilityAndStrength()
	{
		// An ordinary Fishing session first (e.g. the first catches' Fishing-only XP window), then the
		// barbarian triad every window: the session stays Fishing, production and method land on it.
		Instant t = fishFor(T0, 3);
		String id = current().getSessionId();
		for (int i = 1; i <= 8; i++)
		{
			t = t.plusSeconds(30);
			// Leaping trout: 50 Fishing, 5 Agility, 5 Strength per catch, ~20 catches a window.
			onTick(t, xpAt("FISHING", 1_000, t), xpAt("AGILITY", 100, t), xpAt("STRENGTH", 100, t));
		}
		assertTrue(isSkill(current(), "fishing"));
		assertEquals(id, current().getSessionId());
		onTick(t.plusSeconds(1), fishing(t.plusSeconds(1), "barbarian", LEAPING_TROUT, 20));
		assertEquals(20L, produced(current(), LEAPING_TROUT));
		assertEquals("barbarian", current().getAggregates().getSkillStats().getLastMethod());
	}

	@Test
	public void barbarianFishing_fromColdStart_withoutBarbarianEvidence_staysAmbiguous()
	{
		// The generic rule (SessionSignalBatchResolverTest): Fishing + Agility (+ Strength) XP on one tick with
		// NO current session and NO method-specific evidence chooses nothing.
		Instant t = T0;
		for (int i = 1; i <= 4; i++)
		{
			t = T0.plusSeconds(30L * i);
			onTick(t, xpAt("FISHING", 1_000, t), xpAt("AGILITY", 100, t), xpAt("STRENGTH", 100, t));
		}
		assertNull(current());
		// ...and a Fishing update arriving meanwhile is never parked on anything else.
		onTick(t.plusSeconds(1), fishing(t.plusSeconds(1), "barbarian", LEAPING_TROUT, 20));
		assertNull(current());
	}

	/** One barbarian window: the Fishing companion saw a leaping-fish catch ~10 s before the XP window closed. */
	private void barbarianWindow(Instant t, boolean evidence)
	{
		if (evidence)
		{
			coordinator.noteSkillMethodEvidence("fishing", t.minusSeconds(10));
		}
		onTick(t, xpAt("FISHING", 1_000, t), xpAt("AGILITY", 100, t), xpAt("STRENGTH", 100, t));
	}

	@Test
	public void barbarianFishing_coldStart_withFreshBarbarianEvidence_startsFishing_andKeepsIt()
	{
		Instant t = T0.plusSeconds(30);
		barbarianWindow(t, true);
		assertTrue("the barbarian catch resolves the tie: Fishing starts", isSkill(current(), "fishing"));
		String id = current().getSessionId();
		for (int i = 1; i <= 6; i++)
		{
			t = t.plusSeconds(30);
			barbarianWindow(t, i % 2 == 0); // stickiness carries the windows in between
		}
		assertEquals("every later barbarian catch keeps the same Fishing session", id, current().getSessionId());
		onTick(t.plusSeconds(1), fishing(t.plusSeconds(1), "barbarian", LEAPING_TROUT, 20));
		assertEquals("production lands exactly once", 20L, produced(current(), LEAPING_TROUT));
		assertEquals("barbarian", current().getAggregates().getSkillStats().getLastMethod());
		assertTrue("incidental XP never moved ownership", isSkill(current(), "fishing"));
	}

	@Test
	public void barbarianFishing_staleEvidence_neverOverridesTheGenericRule()
	{
		coordinator.noteSkillMethodEvidence("fishing", T0);
		Instant t = T0.plusSeconds(300);
		for (int i = 0; i < 3; i++)
		{
			t = t.plusSeconds(30);
			barbarianWindow(t, false);
		}
		assertNull("a barbarian click 5 minutes ago proves nothing now", current());
	}

	@Test
	public void historyEntry_summarisesFishing()
	{
		Instant t = fishFor(T0, 2);
		onTick(t.plusSeconds(1), fishing(t.plusSeconds(1), "karambwan", KARAMBWAN, 1_842));
		Session s = current();
		s.setFinalizedAt(t.plusSeconds(60).toString());
		HistoryEntry entry = HistoryEntry.from(s);
		assertEquals(1_842L, entry.getSkillProducedTotal());
		assertEquals("karambwan", entry.getSkillPrimaryMethod());
	}
}
