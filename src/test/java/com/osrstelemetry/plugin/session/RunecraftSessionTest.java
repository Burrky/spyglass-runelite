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
 * Runecraft through the REAL session pipeline: SKILLING/runecraft from XP;
 * produced runes and CONSUMED essence land on it (and nowhere else); Guardians
 * of the Rift stays its own structured activity and never receives generic
 * Runecraft production; leaving GotR for normal crafting starts Runecraft.
 */
public class RunecraftSessionTest extends StructuredActivityTestSupport
{
	private static final Instant T0 = Instant.parse("2026-10-13T19:00:00Z");
	private static final int NATURE = 561;
	private static final int PURE = 7936;

	private static SessionSignal production(Instant at, String method, long runes, long essence)
	{
		List<SessionSignal.LootDrop> made = new ArrayList<>();
		made.add(new SessionSignal.LootDrop(NATURE, "Nature rune", runes));
		List<SessionSignal.LootDrop> used = new ArrayList<>();
		used.add(new SessionSignal.LootDrop(PURE, "Pure essence", essence));
		Map<String, Long> millis = new LinkedHashMap<>();
		millis.put(method, 30_000L);
		return SessionSignal.skillProduction(at, new SkillProductionUpdate("RUNECRAFT", method, made, null, millis).withConsumed(used));
	}

	private static boolean isRunecraft(Session s)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.SKILLING
			&& "runecraft".equals(s.getActivityIdentity().getActivityKey());
	}

	private static long produced(Session s, int item)
	{
		if (s.getAggregates().getProducedItems() == null)
		{
			return 0L;
		}
		long q = 0L;
		for (SessionAggregates.ProducedItemAggregate p : s.getAggregates().getProducedItems())
		{
			if (p.getItemId() != null && p.getItemId() == item)
			{
				q += p.getQuantity();
			}
		}
		return q;
	}

	private Instant craftFor(Instant start, int windows)
	{
		Instant t = start;
		for (int i = 1; i <= windows; i++)
		{
			t = start.plusSeconds(30L * i);
			onTick(t, xpAt("RUNECRAFT", 2_400, t));
		}
		return t;
	}

	@Test
	public void runecraftXp_startsTheSession_runesAndEssenceLandOnIt()
	{
		Instant t = craftFor(T0, 2);
		assertTrue(isRunecraft(current()));
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "nature", 54, 27));
		onTick(t.plusSeconds(2), production(t.plusSeconds(2), "nature", 54, 27));
		SessionAggregates.SkillSessionStats stats = current().getAggregates().getSkillStats();
		assertEquals(108L, produced(current(), NATURE));
		assertEquals(54L, stats.getConsumed().get(0).getQuantity());
		assertEquals("essence is never production", 0L, produced(current(), PURE));
		Session s = current();
		s.setFinalizedAt(t.plusSeconds(60).toString());
		HistoryEntry entry = HistoryEntry.from(s);
		assertEquals(108L, entry.getSkillProducedTotal());
		assertEquals("nature", entry.getSkillPrimaryMethod());
	}

	@Test
	public void gotr_isNeverSwallowed_andNeverReceivesGenericProduction()
	{
		Session gotr = lockedGotr(T0);
		Instant t = T0.plusSeconds(40);
		for (int i = 1; i <= 4; i++)
		{
			t = t.plusSeconds(20);
			onTick(t, xpAt("RUNECRAFT", 600, t));
		}
		assertTrue(isGotr(current()));
		assertEquals(gotr.getSessionId(), current().getSessionId());
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "nature", 54, 27));
		assertEquals(0L, produced(current(), NATURE));
		assertNull(current().getAggregates().getSkillStats());
	}

	@Test
	public void leavingGotr_forNormalCrafting_startsRunecraft()
	{
		lockedGotr(T0);
		Instant out = T0.plusSeconds(120);
		onTick(out, leave(out));
		Instant t = craftFor(out.plusSeconds(400), 6);
		assertTrue(isRunecraft(current()));
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "nature", 54, 27));
		assertEquals(54L, produced(current(), NATURE));
	}

	@Test
	public void arceuusLoop_denseMiningAndChiselling_stayInTheRunecraftSession()
	{
		Instant t = craftFor(T0, 3);
		String id = current().getSessionId();
		for (int i = 1; i <= 6; i++)
		{
			t = t.plusSeconds(30);
			// The companion saw dense blocks arrive with the Mining XP (and fragments with the Crafting XP).
			coordinator.noteSkillContextEvidence("runecraft", "MINING", t.minusSeconds(2));
			coordinator.noteSkillContextEvidence("runecraft", "CRAFTING", t.minusSeconds(2));
			onTick(t, xpAt("MINING", 360, t), xpAt("CRAFTING", 240, t));
		}
		assertEquals(id, current().getSessionId());
		assertTrue(isRunecraft(current()));
	}

	@Test
	public void miningWithoutArceuusEvidence_takesOwnershipNormally()
	{
		craftFor(T0, 3);
		Instant t = T0.plusSeconds(120);
		for (int i = 1; i <= 6; i++)
		{
			t = t.plusSeconds(30);
			onTick(t, xpAt("MINING", 2_000, t));
		}
		assertTrue(current().getActivityIdentity().getActivityKey().equals("mining"));
	}

	@Test
	public void methodChange_staysOneRunecraftSession()
	{
		Instant t = craftFor(T0, 2);
		String id = current().getSessionId();
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "nature", 54, 27));
		t = craftFor(t.plusSeconds(1), 3);
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "law", 27, 27));
		assertEquals(id, current().getSessionId());
		assertEquals(2, current().getAggregates().getSkillStats().getMethodMillis().size());
	}
}
