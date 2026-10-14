package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * The GENERIC pieces Skills Phase 4 added to the shared chassis (no
 * Woodcutting words in them): AUTO-TRANSFORMED production (a subset of
 * produced, held nowhere, never banked) and contextual OCCURRENCES, from the
 * ledger through the bounded SKILL_PRODUCTION payload (JSON included), the
 * mapper, the aggregates and the immutable snapshot -- and older events /
 * sessions without them read exactly as before.
 */
public class SkillAutoTransformedPipelineTest
{
	private static final Gson GSON = new Gson();
	private static final Instant AT = Instant.parse("2026-10-13T18:00:00Z");

	@Test
	public void ledger_autoTransformed_isProduction_heldNowhere_neverBanked()
	{
		ProductionLedger l = new ProductionLedger();
		l.produced(1, 5, ProductionLedger.Destination.INVENTORY);
		l.autoTransformed(1, 2);
		l.autoTransformed(1, 0);
		assertEquals(5L, l.heldInInventory(1));
		assertEquals("a deposit can bank only what was held", 5L, l.bankedFromInventory(1, 7));
		ProductionLedger.Drain d = l.drain();
		assertEquals(Long.valueOf(7L), d.getProduced().get(1));
		assertEquals(Long.valueOf(2L), d.getAutoTransformed().get(1));
		assertEquals(Long.valueOf(5L), d.getBanked().get(1));
		assertTrue(l.getPendingAutoTransformed().isEmpty());
		l.autoTransformed(2, 1);
		l.discardPending();
		assertTrue(l.drain().getAutoTransformed().isEmpty());
	}

	@Test
	public void payloadJson_mapper_aggregates_snapshot_roundTrip()
	{
		EventPayloads.SkillProduction p = new EventPayloads.SkillProduction("WOODCUTTING", "redwood",
			Arrays.asList(new EventPayloads.ProducedItem(19669, "Redwood logs", 30)), Collections.<EventPayloads.ProducedItem>emptyList(),
			Collections.singletonMap("redwood", 30_000L));
		p.setAutoTransformed(Arrays.asList(new EventPayloads.ProducedItem(19669, "Redwood logs", 9)));
		Map<String, Long> occ = new LinkedHashMap<>();
		occ.put("forestry:rising_roots", 1L);
		occ.put("ignored", 0L);
		p.setOccurrences(occ);
		EventPayloads.SkillProduction back = GSON.fromJson(GSON.toJson(p), EventPayloads.SkillProduction.class);
		SessionSignal s = SessionEventMapper.map(EventType.SKILL_PRODUCTION, back, AT);
		SkillProductionUpdate u = s.getSkillProduction();
		assertEquals(1, u.getAutoTransformed().size());
		assertEquals(9L, u.getAutoTransformed().get(0).getQuantity());
		assertEquals(Collections.singletonMap("forestry:rising_roots", 1L), u.getOccurrences());

		SessionAggregates agg = new SessionAggregates();
		SessionAggregateUpdater.apply(agg, MetricUpdate.skillProduction(u));
		SessionAggregateUpdater.apply(agg, MetricUpdate.skillProduction(u));
		assertEquals(18L, agg.getSkillStats().getAutoTransformed().get(0).getQuantity());
		assertEquals(Long.valueOf(2L), agg.getSkillStats().getOccurrences().get("forestry:rising_roots"));
		assertEquals(60L, agg.getProducedItems().get(0).getQuantity());
	}

	@Test
	public void olderEventsWithoutExtras_readExactlyAsBefore()
	{
		String json = "{\"skill\":\"MINING\",\"method\":\"iron\",\"produced\":[{\"itemId\":440,\"itemName\":\"Iron ore\",\"quantity\":3}],"
			+ "\"banked\":[],\"methodMillis\":{\"iron\":1000}}";
		EventPayloads.SkillProduction p = GSON.fromJson(json, EventPayloads.SkillProduction.class);
		assertNull(p.getAutoTransformed());
		assertNull(p.getOccurrences());
		SkillProductionUpdate u = SessionEventMapper.map(EventType.SKILL_PRODUCTION, p, AT).getSkillProduction();
		assertTrue(u.getAutoTransformed().isEmpty());
		assertTrue(u.getOccurrences().isEmpty());
		SessionAggregates agg = new SessionAggregates();
		SessionAggregateUpdater.apply(agg, MetricUpdate.skillProduction(u));
		assertNull(agg.getSkillStats().getAutoTransformed());
		assertNull(agg.getSkillStats().getOccurrences());
		assertTrue("a Mining payload stays byte-for-byte free of the new fields", !GSON.toJson(p).contains("autoTransformed"));
	}

	@Test
	public void extrasOnly_isNotAnEmptyUpdate()
	{
		SkillProductionUpdate u = new SkillProductionUpdate("WOODCUTTING", null, null, null, null)
			.withExtras(null, Collections.singletonMap("forestry:beehive", 1L));
		assertTrue(!u.isEmpty());
		assertTrue(new SkillProductionUpdate("WOODCUTTING", null, null, null, null).withExtras(null, null).isEmpty());
	}

	@Test
	public void view_extrasSurviveEveryOtherWither()
	{
		SkillCompanionView.FocusView focus = new SkillCompanionView.FocusView("Yew tree", 3, 1_000L);
		SkillCompanionView v = SkillCompanionView.EMPTY.withExtras(Collections.singletonMap(1, 2L), null, null, null, focus, null)
			.withSupply(new SkillCompanionView.SupplyView("X", 1, null))
			.withCycles(null, null, null);
		assertEquals(focus, v.getFocus());
		assertEquals(Long.valueOf(2L), v.getFlushedAutoTransformed().get(1));
		assertTrue(v.getPendingOccurrences().isEmpty());
		assertNull(SkillCompanionView.EMPTY.getFocus());
		assertNull(SkillCompanionView.EMPTY.getEvent());
	}
}
