package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.home.Dailies;
import com.osrstelemetry.plugin.home.DailyTask;
import com.osrstelemetry.plugin.home.DailyUses;
import com.osrstelemetry.plugin.home.Recurring;
import com.osrstelemetry.plugin.home.TearsOfGuthix;
import com.osrstelemetry.plugin.hunter.BirdhouseRun;
import com.osrstelemetry.plugin.hunter.BirdhouseSpace;
import com.osrstelemetry.plugin.hunter.BirdhouseTraps;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.junit.Test;

/**
 * Pass 30: Home's Dailies &amp; Weeklies card counts what is ready across daily claims and weekly activities (daily uses never
 * count; bird houses moved to their own card, a pass-29 choice of them kept but never counted), and the Bird houses card reads
 * the spaces directly, with no choice needed anywhere.
 */
public class RecurringHomeTest
{
	static final long DAY = Dailies.DAY_MILLIS;
	static final long NOW = 20_754L * DAY + 10 * 3_600_000L;

	static BirdhouseTraps traps(long builtAt, long seededAt)
	{
		Map<BirdhouseSpace, Integer> built = new EnumMap<>(BirdhouseSpace.class);
		Map<BirdhouseSpace, Integer> seeded = new EnumMap<>(BirdhouseSpace.class);
		for (BirdhouseSpace s : BirdhouseSpace.values())
		{
			built.put(s, 25);
			seeded.put(s, 27);
		}
		return BirdhouseTraps.empty().withReading(built, builtAt).withReading(seeded, seededAt);
	}

	static BirdhouseTraps full()
	{
		return traps(NOW - 3 * 3_600_000L, NOW - 2 * 3_600_000L);
	}

	@Test
	public void emptyChoice_asksToChoose()
	{
		assertEquals("Choose what to track", HomeText.recurringCard(Dailies.empty(), Recurring.empty(), true, NOW).headline);
	}

	@Test
	public void readyAcrossGroups_dailyUsesNeverCount()
	{
		Recurring r = Recurring.empty().withShown(Recurring.Entry.TEARS, true).withShown(Recurring.Entry.ALCHEMY, true)
			.withTears(TearsOfGuthix.empty().with(new TearsOfGuthix.Read(true, 50, 0, 0, false, 1000), true, NOW))
			.withUses(DailyUses.empty().with(new DailyUses.Read(4, 0, 0, 0, 0), NOW));
		HomeText.Card c = HomeText.recurringCard(Dailies.empty(), r, true, NOW);
		assertEquals("1 ready", c.headline);
		assertEquals("Tears of Guthix", c.facts.get(0));
		Recurring uses = Recurring.empty().withShown(Recurring.Entry.ALCHEMY, true);
		assertEquals("Nothing to claim", HomeText.recurringCard(Dailies.empty(), uses, true, NOW).headline);
	}

	@Test
	public void birdHousesChosenInPass29_areKeptInTheSave_butNeverChosenOrCountedHere()
	{
		Recurring saved = new Gson().fromJson("{\"shown\":[\"BIRDHOUSES\",\"TEARS\"]}", Recurring.class);
		assertTrue(saved.isShown(Recurring.Entry.TEARS));
		for (Recurring.Entry e : Recurring.Entry.values())
		{
			assertFalse("no bird house entry is offered any more", e.name().contains("BIRD"));
		}
		assertTrue("a later change keeps the old name untouched", new Gson().toJson(saved.withShown(Recurring.Entry.ALCHEMY, true))
			.contains("BIRDHOUSES"));
		HomeText.Card c = HomeText.recurringCard(Dailies.empty(), saved, true, NOW);
		assertEquals("Tears not read: nothing about bird houses here", "Not read yet", c.headline);
	}

	@Test
	public void trackedDailyAvailable_countsWithTheOthers()
	{
		Dailies.Read read = new Dailies.Read();
		for (DailyTask t : DailyTask.values())
		{
			read.claim(t, 0);
			if (t.getGateVarbit() >= 0)
			{
				read.gate(t, t == DailyTask.SAND ? 160 : 1);
			}
		}
		Dailies d = Dailies.empty().withTracked(DailyTask.BATTLESTAVES, true).withTracked(DailyTask.RUNES, true)
			.with(read.morytania(0, 0).account(0, 0), true, NOW);
		HomeText.Card c = HomeText.recurringCard(d, Recurring.empty(), true, NOW);
		assertEquals("2 ready", c.headline);
		assertEquals("Battlestaves and 1 more", c.facts.get(0));
	}

	@Test
	public void birdHouseCard_unseen_ready_filling_andRuns()
	{
		HomeText.Card unseen = HomeText.birdhouseCard(BirdhouseTraps.empty().summary(NOW), null, null, NOW);
		assertEquals("Not seen yet", unseen.headline);
		assertEquals("Read on Fossil Island", unseen.facts.get(0));
		assertNull("nothing read: no urgency", unseen.rail);

		HomeText.Card ready = HomeText.birdhouseCard(full().summary(NOW), null, null, NOW);
		assertEquals("4 ready", ready.headline);
		assertSame(SpyglassTheme.ACCENT_GOLD, ready.rail);

		BirdhouseTraps filling = traps(NOW - 600_000L, NOW - 300_000L);
		HomeText.Card f = HomeText.birdhouseCard(filling.summary(NOW), null, null, NOW);
		assertEquals("Full in ~45m", f.headline);
		assertNull(f.rail);

		BirdhouseRun run = BirdhouseRun.start(EnumSet.allOf(BirdhouseSpace.class), null, filling, NOW);
		assertEquals("Run started", HomeText.birdhouseCard(filling.summary(NOW), run, run.counts(filling, NOW), NOW).headline);
		BirdhouseRun going = run.begun();
		HomeText.Card active = HomeText.birdhouseCard(filling.summary(NOW), going, going.counts(filling, NOW), NOW);
		assertEquals("Run in progress", active.headline);
		assertEquals("seen being seeded before the run and still filling: set (pass 31)", "4 of 4 set", active.facts.get(0));
	}
}
