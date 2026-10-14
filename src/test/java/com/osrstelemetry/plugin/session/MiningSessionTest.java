package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.history.HistoryEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Mining through the REAL session pipeline: the SKILLING/mining session is
 * still created and kept by Mining XP alone (the generic skill rules are
 * unchanged); the companion's SKILL_PRODUCTION updates are metric-only and
 * land on that session ONLY -- never on another skill, a structured activity,
 * or nothing.
 */
public class MiningSessionTest extends StructuredActivityTestSupport
{
	private static final Instant T0 = Instant.parse("2026-10-12T16:00:00Z");
	private static final int IRON = 440;
	private static final int COAL = 453;
	private static final int AMETHYST = 21347;

	private static SessionSignal production(Instant at, String method, Object... itemQty)
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
		return SessionSignal.skillProduction(at, new SkillProductionUpdate("MINING", method, items, null, millis));
	}

	private static SessionSignal banked(Instant at, int item, long qty)
	{
		return SessionSignal.skillProduction(at, new SkillProductionUpdate("MINING", "iron", null,
			Collections.singletonList(new SessionSignal.LootDrop(item, "Item " + item, qty)), null));
	}

	private boolean isMining(Session s)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.SKILLING
			&& "mining".equals(s.getActivityIdentity().getActivityKey());
	}

	private Instant mineFor(Instant start, int windows)
	{
		Instant t = start;
		for (int i = 1; i <= windows; i++)
		{
			t = start.plusSeconds(30L * i);
			onTick(t, xpAt("MINING", 1_000, t));
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
	public void miningXp_startsTheSession_productionAndBankingLandOnIt()
	{
		Instant t = mineFor(T0, 2);
		assertTrue(isMining(current()));
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "iron", IRON, 28));
		onTick(t.plusSeconds(2), banked(t.plusSeconds(2), IRON, 28));
		onTick(t.plusSeconds(3), production(t.plusSeconds(3), "iron", IRON, 28));
		assertEquals(56L, produced(current(), IRON));
		SessionAggregates.SkillSessionStats stats = current().getAggregates().getSkillStats();
		assertEquals("mining", stats.getSkillKey());
		assertEquals(28L, stats.getBanked().get(0).getQuantity());
		assertEquals(Long.valueOf(60_000L), stats.getMethodMillis().get("iron"));
		assertEquals("iron", stats.getLastMethod());
	}

	@Test
	public void methodChange_neverFragmentsTheSession()
	{
		Instant t = mineFor(T0, 2);
		String id = current().getSessionId();
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "iron", IRON, 20));
		t = mineFor(t.plusSeconds(2), 2);
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "coal", COAL, 12));
		assertEquals(id, current().getSessionId());
		assertEquals(20L, produced(current(), IRON));
		assertEquals(12L, produced(current(), COAL));
		Map<String, Long> millis = current().getAggregates().getSkillStats().getMethodMillis();
		assertEquals(Arrays.asList("iron", "coal"), new ArrayList<>(millis.keySet()));
	}

	@Test
	public void productionAlone_neverStartsASession()
	{
		onTick(T0, production(T0, "iron", IRON, 5));
		assertNull("production is never lifecycle evidence", current());
	}

	@Test
	public void miningProduction_neverLandsOnAnotherSkillsSession()
	{
		Instant t = T0;
		for (int i = 1; i <= 4; i++)
		{
			t = T0.plusSeconds(30L * i);
			onTick(t, xpAt("WOODCUTTING", 2_000, t));
		}
		assertEquals("woodcutting", current().getActivityIdentity().getActivityKey());
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "iron", IRON, 5));
		assertEquals(0L, produced(current(), IRON));
		assertNull(current().getAggregates().getSkillStats());
	}

	@Test
	public void miningProduction_neverLandsOnAStructuredActivity()
	{
		Session gotr = lockedGotr(T0);
		Instant t = T0.plusSeconds(40);
		onTick(t, production(t, null, AMETHYST, 3));
		assertEquals(gotr.getSessionId(), current().getSessionId());
		assertNull(current().getAggregates().getSkillStats());
	}

	@Test
	public void incidentalXp_doesNotStealMining()
	{
		Instant t = mineFor(T0, 4);
		String id = current().getSessionId();
		Instant a = t.plusSeconds(5);
		onTick(a, xpAt("AGILITY", 10, a));
		t = mineFor(a, 2);
		assertEquals("one incidental tick never takes ownership", id, current().getSessionId());
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "iron", IRON, 4));
		assertEquals(4L, produced(current(), IRON));
	}

	@Test
	public void sustainedOtherSkill_takesOwnershipNormally()
	{
		Instant t = mineFor(T0, 3);
		String id = current().getSessionId();
		for (int i = 1; i <= 8; i++)
		{
			t = t.plusSeconds(30);
			onTick(t, xpAt("WOODCUTTING", 2_000, t));
		}
		assertNotEquals(id, current().getSessionId());
		assertEquals("woodcutting", current().getActivityIdentity().getActivityKey());
	}

	// ---- persistence / History -------------------------------------------------------------------

	@Test
	public void skillStats_roundTripThroughJson_andOldSessionsStillLoad()
	{
		Instant t = mineFor(T0, 2);
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "amethyst", AMETHYST, 100));
		onTick(t.plusSeconds(2), banked(t.plusSeconds(2), AMETHYST, 72));
		Gson gson = new Gson();
		Session copy = gson.fromJson(gson.toJson(current()), Session.class);
		assertEquals(100L, produced(copy, AMETHYST));
		assertEquals(72L, copy.getAggregates().getSkillStats().getBanked().get(0).getQuantity());

		Session old = gson.fromJson(("{'sessionId':'old','activityIdentity':{'activityType':'SKILLING','activityKey':'mining',"
			+ "'displayName':'Mining'},'state':'FINALIZED','startedAt':'2026-01-01T10:00:00Z','finalizedAt':'2026-01-01T11:00:00Z',"
			+ "'accumulatedActiveDurationMillis':3600000,'aggregates':{'xpGainedBySkill':{'MINING':50000},'lootDrops':[]}}")
			.replace('\'', '"'), Session.class);
		assertNull("written before the companion existed", old.getAggregates().getSkillStats());
		HistoryEntry entry = HistoryEntry.from(old);
		assertNotNull(entry);
		assertEquals(0L, entry.getSkillProducedTotal());
		assertNull(entry.getSkillPrimaryMethod());
		assertNull(SessionSnapshot.capture(old).getSkillStats());
	}

	@Test
	public void historyEntry_summarisesTotalAndTheLongestMethod()
	{
		Instant t = mineFor(T0, 2);
		onTick(t.plusSeconds(1), production(t.plusSeconds(1), "iron", IRON, 10));
		SessionAggregates.SkillSessionStats stats = current().getAggregates().getSkillStats();
		stats.getMethodMillis().put("amethyst", 600_000L);
		assertEquals("amethyst", HistoryEntry.primaryMethod(stats));
		Session s = current();
		s.setFinalizedAt(t.plusSeconds(60).toString());
		HistoryEntry entry = HistoryEntry.from(s);
		assertEquals(10L, entry.getSkillProducedTotal());
		assertEquals("amethyst", entry.getSkillPrimaryMethod());
	}

	@Test
	public void snapshot_carriesAPrivateCopyOfTheSkillStats()
	{
		Instant t = mineFor(T0, 2);
		onTick(t.plusSeconds(1), banked(t.plusSeconds(1), IRON, 5));
		SessionSnapshot snap = SessionSnapshot.capture(current());
		assertEquals(5L, snap.getSkillStats().getBanked().get(0).getQuantity());
		current().getAggregates().getSkillStats().getBanked().get(0).setQuantity(99L);
		assertEquals("never aliases the live aggregates", 5L, snap.getSkillStats().getBanked().get(0).getQuantity());
	}

	// ---- event mapping -----------------------------------------------------------------------------

	@Test
	public void eventMapper_mapsTheBoundedUpdate_unnamedGemKeepsNoId()
	{
		Map<String, Long> millis = new LinkedHashMap<>();
		millis.put("gem_rocks", 30_000L);
		EventPayloads.SkillProduction payload = new EventPayloads.SkillProduction("MINING", "gem_rocks",
			Arrays.asList(new EventPayloads.ProducedItem(1623, "Uncut sapphire", 2), new EventPayloads.ProducedItem(-1, "Uncut gem", 1)),
			Collections.singletonList(new EventPayloads.ProducedItem(1623, "Uncut sapphire", 2)), millis);
		SessionSignal signal = SessionEventMapper.map(EventType.SKILL_PRODUCTION, payload, T0);
		assertEquals(SignalKind.SKILL_PRODUCTION, signal.getKind());
		SkillProductionUpdate u = signal.getSkillProduction();
		assertEquals("mining", u.getSkillKey());
		assertEquals(2, u.getProduced().size());
		assertNull(u.getProduced().get(1).getItemId());
		assertEquals("Uncut gem", u.getProduced().get(1).getItemName());
		assertEquals(Long.valueOf(30_000L), u.getMethodMillis().get("gem_rocks"));
		assertEquals("tick tagging keeps the update", u, signal.withGameTick(5).getSkillProduction());
		assertNull("an empty update is never a signal", SessionEventMapper.map(EventType.SKILL_PRODUCTION,
			new EventPayloads.SkillProduction("MINING", null, null, null, null), T0));
	}

	@Test
	public void unnamedGem_isTotalledByName_inTheSession()
	{
		Instant t = mineFor(T0, 2);
		SkillProductionUpdate u = new SkillProductionUpdate("MINING", "gem_rocks",
			Collections.singletonList(new SessionSignal.LootDrop(null, "Uncut gem", 1)), null, null);
		onTick(t.plusSeconds(1), SessionSignal.skillProduction(t.plusSeconds(1), u));
		onTick(t.plusSeconds(2), SessionSignal.skillProduction(t.plusSeconds(2), u));
		SessionAggregates.ProducedItemAggregate p = current().getAggregates().getProducedItems().get(0);
		assertNull(p.getItemId());
		assertEquals(2L, p.getQuantity());
	}
}
