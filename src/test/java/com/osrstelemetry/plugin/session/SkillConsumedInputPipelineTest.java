package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.skills.ProductionLedger;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * The generic CONSUMED-INPUT concept (Skills Phase 5): session-owned inputs a
 * transformation used up are recorded apart from production -- never TOTAL,
 * never BANKED, never held -- through the ledger, the bounded payload (JSON
 * included), the mapper, the aggregates and the snapshot; older events read
 * exactly as before.
 */
public class SkillConsumedInputPipelineTest
{
	private static final Gson GSON = new Gson();
	private static final Instant AT = Instant.parse("2026-10-13T21:00:00Z");

	@Test
	public void ledger_consumed_isNeverProductionOrHeld()
	{
		ProductionLedger l = new ProductionLedger();
		l.consumed(7936, 27);
		l.consumed(7936, 0);
		assertTrue(l.hasPending());
		assertEquals(0L, l.heldInInventory(7936));
		ProductionLedger.Drain d = l.drain();
		assertTrue(d.getProduced().isEmpty());
		assertEquals(Long.valueOf(27L), d.getConsumed().get(7936));
		assertTrue(l.getPendingConsumed().isEmpty());
		l.consumed(1436, 5);
		l.discardPending();
		assertTrue(l.drain().getConsumed().isEmpty());
	}

	@Test
	public void payloadJson_mapper_aggregates_snapshot_roundTrip()
	{
		EventPayloads.SkillProduction p = new EventPayloads.SkillProduction("RUNECRAFT", "nature",
			Arrays.asList(new EventPayloads.ProducedItem(561, "Nature rune", 54)), Collections.<EventPayloads.ProducedItem>emptyList(),
			Collections.singletonMap("nature", 30_000L));
		p.setConsumed(Arrays.asList(new EventPayloads.ProducedItem(7936, "Pure essence", 27)));
		EventPayloads.SkillProduction back = GSON.fromJson(GSON.toJson(p), EventPayloads.SkillProduction.class);
		SkillProductionUpdate u = SessionEventMapper.map(EventType.SKILL_PRODUCTION, back, AT).getSkillProduction();
		assertEquals(27L, u.getConsumed().get(0).getQuantity());
		SessionAggregates agg = new SessionAggregates();
		SessionAggregateUpdater.apply(agg, MetricUpdate.skillProduction(u));
		SessionAggregateUpdater.apply(agg, MetricUpdate.skillProduction(u));
		assertEquals(54L, agg.getSkillStats().getConsumed().get(0).getQuantity());
		assertEquals("essence never joins production", 1, agg.getProducedItems().size());
		assertEquals(108L, agg.getProducedItems().get(0).getQuantity());
	}

	@Test
	public void consumedOnly_isNotEmpty_olderEventsUnchanged()
	{
		assertTrue(!new SkillProductionUpdate("RUNECRAFT", null, null, null, null)
			.withConsumed(Arrays.asList(new SessionSignal.LootDrop(7936, "Pure essence", 3))).isEmpty());
		EventPayloads.SkillProduction old = GSON.fromJson("{\"skill\":\"MINING\",\"method\":\"iron\",\"produced\":[],\"banked\":[],"
			+ "\"methodMillis\":{\"iron\":1000}}", EventPayloads.SkillProduction.class);
		assertNull(old.getConsumed());
		SkillProductionUpdate u = SessionEventMapper.map(EventType.SKILL_PRODUCTION, old, AT).getSkillProduction();
		assertTrue(u.getConsumed().isEmpty());
		assertTrue(!GSON.toJson(old).contains("consumed"));
	}
}
