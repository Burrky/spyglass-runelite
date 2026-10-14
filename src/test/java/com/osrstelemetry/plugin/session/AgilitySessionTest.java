package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
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
 * Agility through the REAL session pipeline: SKILLING/agility is still
 * created by Agility XP alone; completed laps ride the companion's bounded
 * update as a separate CYCLES field (never items), aggregate per course,
 * survive persistence, and old sessions without them still load.
 */
public class AgilitySessionTest extends StructuredActivityTestSupport
{
	private static final Instant T0 = Instant.parse("2026-10-12T16:00:00Z");
	private static final int GRACE = 11849;

	private static SkillProductionUpdate laps(String course, long count, long total, long best, long falls, Object... rewardQty)
	{
		List<SessionSignal.LootDrop> items = new ArrayList<>();
		for (int i = 0; i < rewardQty.length; i += 2)
		{
			items.add(new SessionSignal.LootDrop((Integer) rewardQty[i], "Item " + rewardQty[i], ((Number) rewardQty[i + 1]).longValue()));
		}
		Map<String, Long> millis = new LinkedHashMap<>();
		millis.put(course, total + 60_000L);
		SessionAggregates.CycleAggregate c = new SessionAggregates.CycleAggregate();
		c.setCount(count);
		c.setTotalMillis(total);
		c.setBestMillis(best);
		c.setFailures(falls);
		Map<String, SessionAggregates.CycleAggregate> cycles = new LinkedHashMap<>();
		cycles.put(course, c);
		return new SkillProductionUpdate("AGILITY", course, items, null, millis).withCycles(cycles);
	}

	private Instant runFor(Instant start, int windows)
	{
		Instant t = start;
		for (int i = 1; i <= windows; i++)
		{
			t = start.plusSeconds(30L * i);
			onTick(t, xpAt("AGILITY", 800, t));
		}
		return t;
	}

	private static boolean isAgility(Session s)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.SKILLING
			&& "agility".equals(s.getActivityIdentity().getActivityKey());
	}

	@Test
	public void agilityXp_startsTheSession_lapsAggregatePerCourse()
	{
		Instant t = runFor(T0, 2);
		assertTrue(isAgility(current()));
		onTick(t.plusSeconds(1), SessionSignal.skillProduction(t.plusSeconds(1), laps("ardougne", 10, 660_000, 58_000, 1, GRACE, 2)));
		onTick(t.plusSeconds(2), SessionSignal.skillProduction(t.plusSeconds(2), laps("ardougne", 5, 300_000, 55_000, 0)));
		SessionAggregates.SkillSessionStats stats = current().getAggregates().getSkillStats();
		SessionAggregates.CycleAggregate c = stats.getCycles().get("ardougne");
		assertEquals(15L, c.getCount());
		assertEquals(960_000L, c.getTotalMillis());
		assertEquals("best of all", 55_000L, c.getBestMillis());
		assertEquals(1L, c.getFailures());
		assertEquals("rewards are the only produced items", 1, current().getAggregates().getProducedItems().size());
		assertEquals(2L, current().getAggregates().getProducedItems().get(0).getQuantity());
	}

	@Test
	public void changingCourse_keepsOneSession_andEachCoursesOwnLaps()
	{
		Instant t = runFor(T0, 2);
		String id = current().getSessionId();
		onTick(t.plusSeconds(1), SessionSignal.skillProduction(t.plusSeconds(1), laps("seers", 18, 18 * 45_000L, 43_000, 0)));
		t = runFor(t.plusSeconds(2), 3);
		onTick(t.plusSeconds(1), SessionSignal.skillProduction(t.plusSeconds(1), laps("ardougne", 32, 32 * 66_000L, 61_000, 0)));
		assertEquals(id, current().getSessionId());
		Map<String, SessionAggregates.CycleAggregate> cycles = current().getAggregates().getSkillStats().getCycles();
		assertEquals(18L, cycles.get("seers").getCount());
		assertEquals(32L, cycles.get("ardougne").getCount());
		assertEquals(43_000L, cycles.get("seers").getBestMillis());
		assertEquals(61_000L, cycles.get("ardougne").getBestMillis());
		Session s = current();
		s.setFinalizedAt(t.plusSeconds(60).toString());
		HistoryEntry entry = HistoryEntry.from(s);
		assertEquals(50L, entry.getSkillCycleTotal());
		assertEquals("ardougne", entry.getSkillPrimaryMethod());
	}

	@Test
	public void lapsNeverLandOnAnotherSkill_orAStructuredActivity()
	{
		Instant t = T0;
		for (int i = 1; i <= 4; i++)
		{
			t = T0.plusSeconds(30L * i);
			onTick(t, xpAt("MINING", 2_000, t));
		}
		onTick(t.plusSeconds(1), SessionSignal.skillProduction(t.plusSeconds(1), laps("ardougne", 3, 180_000, 58_000, 0)));
		assertNull(current().getAggregates().getSkillStats());
		Session gotr = lockedGotr(t.plusSeconds(60));
		Instant g = t.plusSeconds(120);
		onTick(g, SessionSignal.skillProduction(g, laps("ardougne", 3, 180_000, 58_000, 0)));
		assertEquals(gotr.getSessionId(), current().getSessionId());
		assertNull(current().getAggregates().getSkillStats());
	}

	@Test
	public void lapsAlone_neverStartASession()
	{
		onTick(T0, SessionSignal.skillProduction(T0, laps("ardougne", 3, 180_000, 58_000, 0)));
		assertNull(current());
	}

	@Test
	public void eventMapper_carriesCycles_andAnOldUpdateHasNone()
	{
		Map<String, Long> millis = Collections.singletonMap("prifddinas", 120_000L);
		EventPayloads.SkillProduction p = new EventPayloads.SkillProduction("AGILITY", "prifddinas", null, null, millis);
		p.setCycles(Arrays.asList(new EventPayloads.SkillCycle("prifddinas", 2, 140_000, 68_000, 0),
			new EventPayloads.SkillCycle("empty", 0, 0, 0, 0)));
		SkillProductionUpdate u = SessionEventMapper.map(EventType.SKILL_PRODUCTION, p, T0).getSkillProduction();
		assertEquals(1, u.getCycles().size());
		assertEquals(2L, u.getCycles().get("prifddinas").getCount());
		Gson gson = new Gson();
		EventPayloads.SkillProduction old = gson.fromJson("{\"skill\":\"MINING\",\"method\":\"iron\",\"methodMillis\":{\"iron\":30000}}",
			EventPayloads.SkillProduction.class);
		assertNull(old.getCycles());
		assertTrue(SessionEventMapper.map(EventType.SKILL_PRODUCTION, old, T0).getSkillProduction().getCycles().isEmpty());
		EventPayloads.SkillProduction onlyLaps = new EventPayloads.SkillProduction("AGILITY", "gnome", null, null, null);
		onlyLaps.setCycles(Collections.singletonList(new EventPayloads.SkillCycle("gnome", 1, 40_000, 40_000, 0)));
		assertNotNull("an update with only laps is still an update", SessionEventMapper.map(EventType.SKILL_PRODUCTION, onlyLaps, T0));
	}

	@Test
	public void persistence_roundTrips_andOldAgilitySessionsStillLoad()
	{
		Instant t = runFor(T0, 2);
		onTick(t.plusSeconds(1), SessionSignal.skillProduction(t.plusSeconds(1), laps("ardougne", 4, 240_000, 57_000, 1)));
		Gson gson = new Gson();
		Session copy = gson.fromJson(gson.toJson(current()), Session.class);
		assertEquals(4L, copy.getAggregates().getSkillStats().getCycles().get("ardougne").getCount());
		assertEquals(4L, SessionSnapshot.capture(copy).getSkillStats().getCycles().get("ardougne").getCount());

		Session old = gson.fromJson(("{'sessionId':'old','activityIdentity':{'activityType':'SKILLING','activityKey':'agility',"
			+ "'displayName':'Agility'},'state':'FINALIZED','startedAt':'2026-01-01T10:00:00Z','finalizedAt':'2026-01-01T11:00:00Z',"
			+ "'accumulatedActiveDurationMillis':3600000,'aggregates':{'xpGainedBySkill':{'AGILITY':50000},'lootDrops':[]}}")
			.replace('\'', '"'), Session.class);
		assertNull(old.getAggregates().getSkillStats());
		HistoryEntry entry = HistoryEntry.from(old);
		assertEquals(0L, entry.getSkillCycleTotal());
		assertNull(SessionSnapshot.capture(old).getSkillStats());
	}

	@Test
	public void snapshot_neverAliasesTheLiveCycles()
	{
		Instant t = runFor(T0, 2);
		onTick(t.plusSeconds(1), SessionSignal.skillProduction(t.plusSeconds(1), laps("gnome", 3, 120_000, 39_000, 0)));
		SessionSnapshot snap = SessionSnapshot.capture(current());
		current().getAggregates().getSkillStats().getCycles().get("gnome").setCount(99);
		assertEquals(3L, snap.getSkillStats().getCycles().get("gnome").getCount());
	}
}
