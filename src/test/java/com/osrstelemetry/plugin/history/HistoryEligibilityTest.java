package com.osrstelemetry.plugin.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.session.Session;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Pass 28: History's shared report-eligibility policy (visibility only). */
public class HistoryEligibilityTest
{
	private static final Gson GSON = new Gson();
	private static int ids;

	/** A finalized session: type/key, active seconds, and a raw aggregates JSON body (null = none, like the oldest files). */
	public static HistoryEntry entry(String type, String key, long seconds, String aggregates)
	{
		Instant at = Instant.parse("2026-10-27T12:00:00Z");
		String json = "{'sessionId':'s" + (ids++) + "','activityIdentity':{'activityType':'" + type + "','activityKey':'" + key
			+ "','displayName':'" + key + "'},'state':'FINALIZED','startedAt':'" + at.minusSeconds(seconds + 30)
			+ "','finalizedAt':'" + at + "','accumulatedActiveDurationMillis':" + (seconds * 1000)
			+ (aggregates == null ? "" : ",'aggregates':{" + aggregates + "}") + "}";
		return HistoryEntry.from(GSON.fromJson(json.replace('\'', '"'), Session.class));
	}

	public static String drops(int n)
	{
		StringBuilder b = new StringBuilder("'lootDrops':[");
		for (int i = 0; i < n; i++)
		{
			b.append(i == 0 ? "" : ",").append("{'sourceName':'Bloodveld','items':[{'itemId':995,'itemName':'Coins','quantity':5000}]}");
		}
		return b.append(']').toString();
	}

	@Test
	public void emptyFragmentsAndLoneEvents_areShortRecords()
	{
		assertNull(HistoryEligibility.reason(entry("COMBAT", "goblin", 8, null)));
		assertNull(HistoryEligibility.reason(entry("COMBAT", "goblin", 8, "")));
		// One incidental XP drop.
		assertNull(HistoryEligibility.reason(entry("COMBAT", "goblin", 12, "'xpGainedBySkill':{'HITPOINTS':40}")));
		// One valuable drop, or two, with nothing else.
		assertNull(HistoryEligibility.reason(entry("SLAYER", "bloodveld", 20, drops(1))));
		assertNull(HistoryEligibility.reason(entry("SLAYER", "bloodveld", 20, drops(2) + ",'slayerProgressDelta':2")));
		// Long but empty: duration alone is never enough.
		assertNull(HistoryEligibility.reason(entry("COMBAT", "goblin", 3600, "")));
		assertNull(HistoryEligibility.reason(null));
	}

	@Test
	public void shortCompletedActivities_areReports()
	{
		assertEquals(HistoryEligibility.Reason.BOSS, HistoryEligibility.reason(entry("BOSSING", "zulrah", 45,
			"'reliableCount':{'kind':'KILLS','sessionOccurrences':1}")));
		assertEquals(HistoryEligibility.Reason.BOSS, HistoryEligibility.reason(entry("BOSSING", "kraken", 30, drops(1))));
		assertEquals(HistoryEligibility.Reason.COMPLETION, HistoryEligibility.reason(entry("STRUCTURED_ACTIVITY", "barrows", 40,
			"'activityCompletions':{'sessionCompletions':1}")));
		assertEquals(HistoryEligibility.Reason.FARMING, HistoryEligibility.reason(entry("SKILLING", "farming", 50,
			"'xpGainedBySkill':{'FARMING':120}")));
		// A boss session with neither kill nor drop, or a minigame with no completion, stays short.
		assertNull(HistoryEligibility.reason(entry("BOSSING", "zulrah", 45, "'xpGainedBySkill':{'RANGED':300}")));
		assertNull(HistoryEligibility.reason(entry("STRUCTURED_ACTIVITY", "barrows", 40, "'activityCompletions':{'sessionCompletions':0}")));
	}

	@Test
	public void repeatedSustainedAndSkillingWork_areReports()
	{
		assertEquals(HistoryEligibility.Reason.REPEATED, HistoryEligibility.reason(entry("SLAYER", "bloodveld", 20, "'slayerProgressDelta':3")));
		assertEquals(HistoryEligibility.Reason.REPEATED, HistoryEligibility.reason(entry("SLAYER", "bloodveld", 20, drops(3))));
		assertEquals(HistoryEligibility.Reason.REPEATED, HistoryEligibility.reason(entry("COMBAT", "cow", 20,
			"'reliableCount':{'kind':'KILLS','sessionOccurrences':3}")));
		assertEquals(HistoryEligibility.Reason.REPEATED, HistoryEligibility.reason(entry("SKILLING", "fletching", 20,
			"'producedItems':[{'itemId':52,'quantity':15}]")));
		assertEquals(HistoryEligibility.Reason.REPEATED, HistoryEligibility.reason(entry("SKILLING", "agility", 40,
			"'skillStats':{'skillKey':'agility','cycles':{'gnome':{'count':1}}}")));
		assertEquals(HistoryEligibility.Reason.SUSTAINED, HistoryEligibility.reason(entry("COMBAT", "goblin", 300,
			"'xpGainedBySkill':{'ATTACK':40}")));
		assertNull(HistoryEligibility.reason(entry("COMBAT", "goblin", 299, "'xpGainedBySkill':{'ATTACK':40}")));
		assertEquals(HistoryEligibility.Reason.SKILLING, HistoryEligibility.reason(entry("SKILLING", "cooking", 120,
			"'xpGainedBySkill':{'COOKING':30}")));
		assertEquals(HistoryEligibility.Reason.SKILLING, HistoryEligibility.reason(entry("SKILLING", "cooking", 30,
			"'xpGainedBySkill':{'COOKING':1000}")));
		assertNull(HistoryEligibility.reason(entry("SKILLING", "cooking", 30, "'xpGainedBySkill':{'COOKING':999}")));
		assertNull(HistoryEligibility.reason(entry("SKILLING", "cooking", 29, "'xpGainedBySkill':{'COOKING':5000}")));
	}

	@Test
	public void olderRecordsWithoutTheNewFields_areJudgedByWhatTheyKept()
	{
		HistoryEntry old = entry("SLAYER", "bloodveld", 400, "'xpGainedBySkill':{'SLAYER':200}");
		assertEquals(0, old.getSlayerProgress());
		assertEquals(0, old.getProducedQuantity());
		assertEquals(HistoryEligibility.Reason.SUSTAINED, HistoryEligibility.reason(old));
		HistoryEntry bare = entry("SLAYER", "bloodveld", 400, null);
		assertFalse(HistoryEligibility.isReport(bare));
	}

	@Test
	public void split_keepsOrderAndNeverDropsARecord()
	{
		HistoryEntry a = entry("BOSSING", "zulrah", 45, "'reliableCount':{'kind':'KILLS','sessionOccurrences':1}");
		HistoryEntry b = entry("COMBAT", "goblin", 8, null);
		HistoryEntry c = entry("SLAYER", "bloodveld", 20, "'slayerProgressDelta':5");
		HistoryEntry d = entry("COMBAT", "goblin", 9, "'xpGainedBySkill':{'HITPOINTS':4}");
		HistoryEligibility.Split split = HistoryEligibility.split(Arrays.asList(a, b, c, null, d));
		assertEquals(Arrays.asList(a, c), split.getReports());
		assertEquals(Arrays.asList(b, d), split.getHidden());
		assertTrue(HistoryEligibility.split(null).getReports().isEmpty());
		List<HistoryEntry> none = HistoryEligibility.split(java.util.Collections.emptyList()).getHidden();
		assertTrue(none.isEmpty());
	}
}
