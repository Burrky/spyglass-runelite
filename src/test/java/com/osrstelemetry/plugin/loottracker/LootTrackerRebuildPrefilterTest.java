package com.osrstelemetry.plugin.loottracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.events.TelemetryEvent;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Loot Tracker rebuild PRE-FILTER: lines that cannot be SERVER_NPC_LOOT /
 * STRUCTURED_REWARD are skipped before JSON parsing, and the rebuilt index
 * is identical to the unfiltered (pre-change) path in every case --
 * retention, event-ID dedup, malformed lines, order and quantities.
 */
public class LootTrackerRebuildPrefilterTest
{
	private static final long ACCOUNT = 777_000_111L;
	private static final Gson GSON = new Gson();
	private static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");

	private Path root;

	@Before
	public void setUp() throws Exception
	{
		root = Files.createTempDirectory("loot-prefilter-test");
		TelemetryPaths.init(TestFilepaths.rooted(root));
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(root).deleteRecursively();
	}

	// ---- helpers -----------------------------------------------------------

	private static String line(String eventId, EventType type, Object payload, Instant at)
	{
		return GSON.toJson(new TelemetryEvent(eventId, Long.toString(ACCOUNT), type, at.toString(), "s-1", payload));
	}

	private static String npcLoot(String id, String npc, int npcId, int itemId, String item, int qty, Instant at)
	{
		return line(id, EventType.SERVER_NPC_LOOT, new EventPayloads.ServerNpcLoot(npc, npcId,
			Collections.singletonList(new EventPayloads.ServerNpcLootItem(itemId, item, qty))), at);
	}

	private static String reward(String id, String activity, String source, int itemId, String item, int qty, Instant at)
	{
		return line(id, EventType.STRUCTURED_REWARD, new EventPayloads.StructuredReward(activity, source, source,
			Collections.singletonList(new EventPayloads.StructuredRewardItem(itemId, item, qty))), at);
	}

	private static String rawCombat(String id, Instant at)
	{
		return line(id, EventType.RAW_COMBAT_XP_OBSERVED, new EventPayloads.RawCombatXpObserved("ATTACK"), at);
	}

	private static String xp(String id, Instant at)
	{
		return line(id, EventType.XP_CHANGE, new EventPayloads.XpChange("SLAYER", 1000, 1100, 100, at.toString(), at.toString()), at);
	}

	private static String death(String id, String npc, Instant at)
	{
		return line(id, EventType.NPC_DEATH, new EventPayloads.NpcDeath(npc, 1, 100, "0,0,0", "Kraken", null), at);
	}

	private void write(List<String> lines) throws Exception
	{
		Path file = TestFilepaths.file(TelemetryPaths.eventsFile(ACCOUNT)).toPath();
		Files.createDirectories(file.getParent());
		Files.write(file, String.join(System.lineSeparator(), lines).concat(System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
	}

	/** Every observable of the rebuilt index, in order: sources, records, event ids, times, items, quantities. */
	private static String fingerprint(LootTrackerIndex index)
	{
		StringBuilder sb = new StringBuilder();
		for (LootTrackerSource s : index.snapshotSources())
		{
			sb.append(s.getSourceKey()).append('|').append(s.getSourceName()).append('|').append(s.getSourceId())
				.append('|').append(s.getKillCount()).append('\n');
			for (LootTrackerRecord r : s.getRecords())
			{
				sb.append("  ").append(r.getEventId()).append('@').append(r.getObservedAt());
				for (LootTrackerItem i : r.getItems())
				{
					sb.append(' ').append(i.getItemId()).append(':').append(i.getItemName()).append('x').append(i.getQuantity());
				}
				sb.append('\n');
			}
			for (LootTrackerItem i : s.getItemTotals())
			{
				sb.append("  total ").append(i.getItemId()).append('x').append(i.getQuantity()).append('\n');
			}
		}
		return sb.toString();
	}

	private static final class Run
	{
		final int applied;
		final String fingerprint;
		final LootTrackerPersistence.RebuildStats stats;

		Run(int applied, String fingerprint, LootTrackerPersistence.RebuildStats stats)
		{
			this.applied = applied;
			this.fingerprint = fingerprint;
			this.stats = stats;
		}
	}

	private static Run rebuild(boolean prefilter, Instant cutoff)
	{
		LootTrackerPersistence p = new LootTrackerPersistence(GSON, prefilter);
		LootTrackerIndex index = new LootTrackerIndex();
		int applied = p.rebuild(ACCOUNT, index, cutoff);
		return new Run(applied, fingerprint(index), p.getLastRebuildStats());
	}

	private static void assertSameAsUnfiltered(Instant cutoff)
	{
		Run old = rebuild(false, cutoff);
		Run now = rebuild(true, cutoff);
		assertEquals(old.applied, now.applied);
		assertEquals(old.fingerprint, now.fingerprint);
	}

	// ---- A: irrelevant lines are never parsed -------------------------------

	@Test
	public void irrelevantLines_areRejectedBeforeJsonParsing()
	{
		assertFalse(LootTrackerPersistence.isLootCandidate(rawCombat("e1", BASE)));
		assertFalse(LootTrackerPersistence.isLootCandidate(xp("e2", BASE)));
		assertFalse(LootTrackerPersistence.isLootCandidate(death("e3", "Kraken", BASE)));
		assertTrue(LootTrackerPersistence.isLootCandidate(npcLoot("e4", "Kraken", 494, 995, "Coins", 100, BASE)));
		assertTrue(LootTrackerPersistence.isLootCandidate(reward("e5", "barrows", "barrows", 560, "Death rune", 50, BASE)));
	}

	@Test
	public void parserIsInvokedOnlyForCandidateLines() throws Exception
	{
		write(Arrays.asList(rawCombat("e1", BASE), xp("e2", BASE), death("e3", "Kraken", BASE),
			npcLoot("e4", "Kraken", 494, 995, "Coins", 100, BASE), rawCombat("e5", BASE),
			reward("e6", "barrows", "barrows", 560, "Death rune", 50, BASE)));
		Run now = rebuild(true, null);
		assertEquals(6, now.stats.lines);
		assertEquals(2, now.stats.candidates);
		assertEquals("JSON parsing ran only for the two loot lines", 2, now.stats.parsed);
		assertEquals(2, now.applied);
		Run old = rebuild(false, null);
		assertEquals("the unfiltered path parses every line", 6, old.stats.parsed);
		assertEquals(old.fingerprint, now.fingerprint);
	}

	// ---- B / C: both loot kinds still rebuild exactly -----------------------

	@Test
	public void serverNpcLoot_andStructuredReward_rebuildExactly_inOrder() throws Exception
	{
		write(Arrays.asList(
			npcLoot("a", "Kraken", 494, 995, "Coins", 100, BASE),
			rawCombat("x1", BASE.plusSeconds(1)),
			npcLoot("b", "Kraken", 494, 995, "Coins", 250, BASE.plusSeconds(2)),
			reward("c", "barrows", "barrows", 560, "Death rune", 50, BASE.plusSeconds(3)),
			reward("d", "barrows", "barrows", 4708, "Ahrim's hood", 1, BASE.plusSeconds(4))));
		Run now = rebuild(true, null);
		assertEquals(4, now.applied);
		assertTrue(now.fingerprint.contains("  total 995x350"));
		assertTrue(now.fingerprint, now.fingerprint.contains("4708:Ahrim's hoodx1"));
		assertTrue(now.fingerprint.indexOf("  a@") < now.fingerprint.indexOf("  b@"));
		assertSameAsUnfiltered(null);
	}

	@Test
	public void escapedEventType_stillACandidate_neverDropped() throws Exception
	{
		// The lenient parser accepts a unicode-escaped type name; the filter keeps such lines.
		String escaped = npcLoot("esc", "Kraken", 494, 995, "Coins", 7, BASE).replace("\"SERVER_NPC_LOOT\"", "\"\\u0053ERVER_NPC_LOOT\"");
		assertFalse(escaped.contains("SERVER_NPC_LOOT"));
		assertTrue(LootTrackerPersistence.isLootCandidate(escaped));
		write(Collections.singletonList(escaped));
		assertEquals(1, rebuild(true, null).applied);
		assertSameAsUnfiltered(null);
	}

	// ---- D / E: malformed lines --------------------------------------------

	@Test
	public void malformedIrrelevantLines_areSkippedWithoutParsing_malformedLootLinesBehaveAsBefore() throws Exception
	{
		write(Arrays.asList(
			"{ this is not json RAW_COMBAT",
			"garbage",
			"{\"eventType\":\"SERVER_NPC_LOOT\",\"payload\":{broken",          // malformed candidate
			"{\"eventType\":\"STRUCTURED_REWARD\",\"payload\":\"not-an-object\"}", // malformed payload
			npcLoot("ok", "Kraken", 494, 995, "Coins", 10, BASE),
			"{\"eventId\":\"q\",\"eventType\":\"SERVER_NPC_LOOT\"}"));         // no payload
		Run now = rebuild(true, null);
		assertEquals(1, now.applied);
		assertEquals(6, now.stats.lines);
		assertEquals("only the four candidate lines were parsed", 4, now.stats.parsed);
		assertSameAsUnfiltered(null);
	}

	// ---- F: retention -------------------------------------------------------

	@Test
	public void lootOutsideTheRetentionWindow_staysExcluded() throws Exception
	{
		Instant cutoff = BASE.plusSeconds(100);
		write(Arrays.asList(
			npcLoot("old", "Kraken", 494, 995, "Coins", 10, BASE),
			reward("oldr", "barrows", "barrows", 560, "Death rune", 5, BASE.plusSeconds(99)),
			npcLoot("new", "Kraken", 494, 995, "Coins", 20, BASE.plusSeconds(100)),
			rawCombat("x", BASE.plusSeconds(150))));
		Run now = rebuild(true, cutoff);
		assertEquals(1, now.applied);
		assertTrue(now.fingerprint.contains("  new@"));
		assertFalse(now.fingerprint.contains("  old@"));
		assertSameAsUnfiltered(cutoff);
	}

	// ---- G: event-ID dedup ---------------------------------------------------

	@Test
	public void eventIdDedup_unchanged() throws Exception
	{
		String a = npcLoot("dup", "Kraken", 494, 995, "Coins", 10, BASE);
		write(Arrays.asList(a, rawCombat("x", BASE), a, reward("r", "barrows", "barrows", 560, "Death rune", 5, BASE)));
		LootTrackerPersistence p = new LootTrackerPersistence(GSON);
		LootTrackerIndex index = new LootTrackerIndex();
		assertEquals("the repeated line is applied once", 2, p.rebuild(ACCOUNT, index, null));
		assertEquals("a second rebuild into the same index adds nothing", 0, p.rebuild(ACCOUNT, index, null));
		assertSameAsUnfiltered(null);
	}

	// ---- H: large mixed input -- identical output, far fewer parses ----------

	/** About 93% irrelevant lines, like a real log; returns the number of loot lines written. */
	private int writeLargeMixedLog(int total, long seed) throws Exception
	{
		Random random = new Random(seed);
		List<String> lines = new ArrayList<>(total);
		String[] npcs = {"Kraken", "Abyssal demon", "Gargoyle", "Ahrim the Blighted", "Zulrah"};
		int loot = 0;
		for (int i = 0; i < total; i++)
		{
			Instant at = BASE.plusSeconds(i);
			int roll = random.nextInt(100);
			String id = "e" + i;
			if (roll < 5)
			{
				lines.add(npcLoot(id, npcs[random.nextInt(npcs.length)], 400 + random.nextInt(5), 995 + random.nextInt(3), "Item", 1 + random.nextInt(500), at));
				loot++;
			}
			else if (roll < 7)
			{
				lines.add(reward(id, "barrows", "barrows", 560 + random.nextInt(3), "Rune", 1 + random.nextInt(90), at));
				loot++;
			}
			else if (roll < 50)
			{
				lines.add(rawCombat(id, at));
			}
			else if (roll < 70)
			{
				lines.add(xp(id, at));
			}
			else if (roll < 99)
			{
				lines.add(death(id, npcs[random.nextInt(npcs.length)], at));
			}
			else
			{
				lines.add("{ malformed " + i);
			}
		}
		write(lines);
		return loot;
	}

	@Test
	public void largeMixedLog_identicalIndex_onlyCandidatesParsed() throws Exception
	{
		int loot = writeLargeMixedLog(20_000, 42L);
		Instant cutoff = BASE.plusSeconds(5_000);
		Run old = rebuild(false, cutoff);
		Run now = rebuild(true, cutoff);
		assertEquals(old.applied, now.applied);
		assertEquals(old.fingerprint, now.fingerprint);
		assertEquals(20_000, old.stats.parsed);
		assertEquals(loot, now.stats.parsed);
		assertTrue("about 7% of lines are candidates: " + now.stats.parsed, now.stats.parsed < 20_000 / 10);
	}

	/**
	 * Rough local measurement (not a JMH benchmark): prints line counts and
	 * best-of-3 timings for the unfiltered and pre-filtered paths. Kept fast
	 * enough for the normal suite; asserts only the equivalence.
	 */
	@Test
	public void measurement_unfilteredVsPrefiltered() throws Exception
	{
		int total = 100_000;
		int loot = writeLargeMixedLog(total, 7L);
		long oldBest = Long.MAX_VALUE;
		long newBest = Long.MAX_VALUE;
		Run old = null;
		Run now = null;
		for (int i = 0; i < 3; i++)
		{
			long t0 = System.nanoTime();
			old = rebuild(false, null);
			long t1 = System.nanoTime();
			now = rebuild(true, null);
			long t2 = System.nanoTime();
			oldBest = Math.min(oldBest, t1 - t0);
			newBest = Math.min(newBest, t2 - t1);
		}
		assertEquals(old.fingerprint, now.fingerprint);
		System.out.println("PREFILTER-MEASUREMENT lines=" + now.stats.lines + " candidates=" + now.stats.candidates
			+ " parsedOld=" + old.stats.parsed + " parsedNew=" + now.stats.parsed + " lootLines=" + loot
			+ " oldMs=" + oldBest / 1_000_000 + " newMs=" + newBest / 1_000_000);
	}
}
