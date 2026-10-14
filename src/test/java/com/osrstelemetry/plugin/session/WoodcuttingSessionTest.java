package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.history.HistoryEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Woodcutting through the REAL session pipeline: one SKILLING/woodcutting
 * session whatever the tree; the same metric-only SKILL_PRODUCTION path as
 * Mining / Fishing / Agility, now also carrying the auto-burned part of
 * production and Forestry occurrences; and OWNERSHIP -- an infernal axe's
 * Firemaking XP and a Forestry event's other-skill XP never steal the
 * session while the Woodcutting companion proves they are Woodcutting's,
 * while manual Firemaking (or Hunter away from any event) keeps its normal
 * ownership, and a cold-start tie without evidence stays ambiguous.
 */
public class WoodcuttingSessionTest extends StructuredActivityTestSupport
{
	private static final Instant T0 = Instant.parse("2026-10-13T16:00:00Z");
	private static final int REDWOOD = 19669;
	private static final int YEW = 1515;
	private static final int BARK = 28134;
	private static final int NEST = 5073;

	private static SessionSignal production(Instant at, String method, List<SessionSignal.LootDrop> items, List<SessionSignal.LootDrop> burned,
		Map<String, Long> occurrences)
	{
		Map<String, Long> millis = new LinkedHashMap<>();
		if (method != null)
		{
			millis.put(method, 30_000L);
		}
		return SessionSignal.skillProduction(at, new SkillProductionUpdate("WOODCUTTING", method, items, null, millis)
			.withExtras(burned, occurrences));
	}

	private static List<SessionSignal.LootDrop> items(Object... itemQty)
	{
		List<SessionSignal.LootDrop> items = new ArrayList<>();
		for (int i = 0; i < itemQty.length; i += 2)
		{
			items.add(new SessionSignal.LootDrop((Integer) itemQty[i], "Item " + itemQty[i], ((Number) itemQty[i + 1]).longValue()));
		}
		return items;
	}

	private static boolean isSkill(Session s, String key)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.SKILLING
			&& key.equals(s.getActivityIdentity().getActivityKey());
	}

	private Instant chopFor(Instant start, int windows)
	{
		Instant t = start;
		for (int i = 1; i <= windows; i++)
		{
			t = start.plusSeconds(30L * i);
			onTick(t, xpAt("WOODCUTTING", 2_000, t));
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

	private static long xp(Session s, String skill)
	{
		Long v = s.getAggregates().getXpGainedBySkill().get(skill);
		return v == null ? 0L : v;
	}

	/** One infernal window: Woodcutting and Firemaking XP on the same tick; evidence = the companion saw it. */
	private void infernalWindow(Instant t, boolean evidence)
	{
		if (evidence)
		{
			coordinator.noteSkillContextEvidence("woodcutting", "FIREMAKING", t.minusSeconds(2));
		}
		onTick(t, xpAt("WOODCUTTING", 2_000, t), xpAt("FIREMAKING", 600, t));
	}

	@Test
	public void woodcuttingXp_startsTheSession_productionAndExtrasLandOnIt()
	{
		Instant t = chopFor(T0, 2);
		assertTrue(isSkill(current(), "woodcutting"));
		Map<String, Long> occ = new LinkedHashMap<>();
		occ.put("forestry:rising_roots", 1L);
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "redwood", items(REDWOOD, 120, BARK, 14, NEST, 1), items(REDWOOD, 9), occ));
		onTick(t.plusSeconds(2), production(t.plusSeconds(2), "redwood", items(REDWOOD, 30), items(REDWOOD, 3), occ));
		SessionAggregates.SkillSessionStats stats = current().getAggregates().getSkillStats();
		assertEquals(150L, produced(current(), REDWOOD));
		assertEquals(14L, produced(current(), BARK));
		assertEquals(1, stats.getAutoTransformed().size());
		assertEquals(12L, stats.getAutoTransformed().get(0).getQuantity());
		assertEquals(Long.valueOf(2L), stats.getOccurrences().get("forestry:rising_roots"));
		assertEquals("redwood", stats.getLastMethod());
	}

	@Test
	public void treeChange_staysOneSession_methodSegmentsKept()
	{
		Instant t = chopFor(T0, 2);
		String id = current().getSessionId();
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "yew", items(YEW, 50), null, null));
		t = chopFor(t.plusSeconds(1), 3);
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "redwood", items(REDWOOD, 40), null, null));
		assertEquals(id, current().getSessionId());
		Map<String, Long> millis = current().getAggregates().getSkillStats().getMethodMillis();
		assertTrue(millis.containsKey("yew") && millis.containsKey("redwood"));
	}

	@Test
	public void infernalFiremakingXp_neverStealsTheSession()
	{
		Instant t = chopFor(T0, 3);
		String id = current().getSessionId();
		for (int i = 1; i <= 8; i++)
		{
			t = t.plusSeconds(30);
			infernalWindow(t, true);
		}
		assertTrue(isSkill(current(), "woodcutting"));
		assertEquals(id, current().getSessionId());
		assertTrue("the Firemaking XP is kept as a metric of the Woodcutting session", xp(current(), "FIREMAKING") >= 8 * 600L);
	}

	@Test
	public void infernalFiremakingXp_aloneOnAWindow_withEvidence_staysWoodcutting()
	{
		Instant t = chopFor(T0, 3);
		String id = current().getSessionId();
		for (int i = 1; i <= 6; i++)
		{
			t = t.plusSeconds(30);
			coordinator.noteSkillContextEvidence("woodcutting", "FIREMAKING", t.minusSeconds(2));
			// A window in which only the Firemaking XP closed (the Woodcutting window closes on the next tick).
			onTick(t, xpAt("FIREMAKING", 600, t));
			onTick(t.plusSeconds(1), xpAt("WOODCUTTING", 2_000, t.plusSeconds(1)));
		}
		assertEquals(id, current().getSessionId());
	}

	@Test
	public void firemakingXpAloneOnWindows_withoutEvidence_switchesAsBefore()
	{
		// The same shape as above minus the companion's proof: the pre-existing rules move ownership,
		// which is exactly what the evidence prevents for infernal chopping.
		Instant t = chopFor(T0, 3);
		String id = current().getSessionId();
		for (int i = 1; i <= 6; i++)
		{
			t = t.plusSeconds(30);
			onTick(t, xpAt("FIREMAKING", 600, t));
		}
		assertNotEquals(id, current().getSessionId());
	}

	@Test
	public void infernalColdStart_withEvidence_startsWoodcutting()
	{
		Instant t = T0.plusSeconds(30);
		infernalWindow(t, true);
		assertTrue("the infernal proof resolves the tie: Woodcutting starts", isSkill(current(), "woodcutting"));
	}

	@Test
	public void woodcuttingAndFiremaking_coldStart_withoutEvidence_staysAmbiguous()
	{
		Instant t = T0;
		for (int i = 1; i <= 4; i++)
		{
			t = T0.plusSeconds(30L * i);
			onTick(t, xpAt("WOODCUTTING", 2_000, t), xpAt("FIREMAKING", 600, t));
		}
		assertNull("the generic rule is unchanged without the companion's proof", current());
	}

	@Test
	public void manualFiremakingAfterChopping_takesOwnershipNormally()
	{
		Instant t = chopFor(T0, 3);
		infernalWindow(t.plusSeconds(30), true);
		String wc = current().getSessionId();
		// The axe is put away; the player lights logs by hand from here: no fresh evidence inside these windows.
		Instant f = t.plusSeconds(60);
		for (int i = 1; i <= 6; i++)
		{
			f = f.plusSeconds(30);
			onTick(f, xpAt("FIREMAKING", 1_500, f));
		}
		assertTrue("manual Firemaking is its own session", isSkill(current(), "firemaking"));
		assertNotEquals(wc, current().getSessionId());
	}

	@Test
	public void manualFletchingAfterChopping_takesOwnershipNormally()
	{
		Instant t = chopFor(T0, 3);
		String wc = current().getSessionId();
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "yew", items(YEW, 27), null, null));
		Instant f = t.plusSeconds(60);
		for (int i = 1; i <= 6; i++)
		{
			f = f.plusSeconds(30);
			onTick(f, xpAt("FLETCHING", 1_200, f));
		}
		assertTrue("deliberate Fletching is its own session", isSkill(current(), "fletching"));
		assertNotEquals(wc, current().getSessionId());
	}

	@Test
	public void oneStrayFiremakingWindow_neverChurnsTheIdentity()
	{
		Instant t = chopFor(T0, 3);
		String id = current().getSessionId();
		t = t.plusSeconds(30);
		onTick(t, xpAt("FIREMAKING", 300, t));
		t = chopFor(t, 2);
		assertEquals(id, current().getSessionId());
		assertTrue(isSkill(current(), "woodcutting"));
	}

	@Test
	public void unrelatedSkill_takesOwnershipNormally()
	{
		chopFor(T0, 3);
		Instant t = T0.plusSeconds(120);
		for (int i = 1; i <= 6; i++)
		{
			t = t.plusSeconds(30);
			onTick(t, xpAt("MINING", 2_000, t));
		}
		assertTrue(isSkill(current(), "mining"));
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "yew", items(YEW, 5), null, null));
		assertEquals("Woodcutting production never lands on Mining", 0L, produced(current(), YEW));
	}

	@Test
	public void forestryHunterXp_duringPoachers_staysInTheWoodcuttingSession()
	{
		Instant t = chopFor(T0, 3);
		String id = current().getSessionId();
		for (int i = 1; i <= 6; i++)
		{
			t = t.plusSeconds(30);
			coordinator.noteSkillContextEvidence("woodcutting", "HUNTER", t.minusSeconds(2));
			onTick(t, xpAt("HUNTER", 300, t));
		}
		assertEquals(id, current().getSessionId());
		assertTrue(xp(current(), "HUNTER") > 0);
	}

	@Test
	public void hunterXpWithoutAnEvent_keepsItsNormalOwnership()
	{
		chopFor(T0, 3);
		Instant t = T0.plusSeconds(120);
		for (int i = 1; i <= 6; i++)
		{
			t = t.plusSeconds(30);
			onTick(t, xpAt("HUNTER", 300, t));
		}
		assertTrue(isSkill(current(), "hunter"));
	}

	@Test
	public void contextEvidenceForAnotherOwner_neverAppliesToWoodcutting()
	{
		chopFor(T0, 3);
		Instant t = T0.plusSeconds(120);
		for (int i = 1; i <= 6; i++)
		{
			t = t.plusSeconds(30);
			coordinator.noteSkillContextEvidence("mining", "HUNTER", t.minusSeconds(2));
			onTick(t, xpAt("HUNTER", 300, t));
		}
		assertTrue("evidence names its owner: a Mining proof never keeps Hunter in a Woodcutting session", isSkill(current(), "hunter"));
	}

	@Test
	public void productionAlone_neverStartsAWoodcuttingSession()
	{
		onTick(T0, production(T0, "yew", items(YEW, 5), null, Collections.<String, Long>emptyMap()));
		assertNull(current());
	}

	@Test
	public void historyEntry_countsLogsOnly()
	{
		Instant t = chopFor(T0, 2);
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "redwood", items(REDWOOD, 300, BARK, 50, NEST, 2), items(REDWOOD, 20), null));
		Session s = current();
		s.setFinalizedAt(t.plusSeconds(60).toString());
		HistoryEntry entry = HistoryEntry.from(s);
		assertEquals(300L, entry.getSkillProducedTotal());
		assertEquals("redwood", entry.getSkillPrimaryMethod());
	}
}
