package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.LootValuationMode;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.farming.FarmingHistoryFixtures;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.session.Session;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.Collections;
import javax.swing.JLabel;
import org.junit.Test;

/** A Farming session's History detail lists its finished run (canonical §28.8) and never a harvest total. */
public class HistoryFarmRunsTest
{
	private static void collect(Container c, StringBuilder sb)
	{
		for (Component k : c.getComponents())
		{
			if (k instanceof JLabel)
			{
				sb.append(((JLabel) k).getText()).append('\n');
			}
			if (k instanceof Container)
			{
				collect((Container) k, sb);
			}
		}
	}

	@Test
	public void theFarmRunSection_showsTheRun_withoutAHarvestTotal() throws Exception
	{
		Instant at = Instant.now().minusSeconds(60);
		Session s = new Gson().fromJson(("{'sessionId':'f','activityIdentity':{'activityType':'SKILLING','activityKey':'farming','displayName':'Farming'},"
			+ "'state':'FINALIZED','startedAt':'" + at.minusSeconds(1200) + "','lastActiveAt':'" + at.minusSeconds(60) + "','finalizedAt':'" + at
			+ "','accumulatedActiveDurationMillis':60000,'aggregates':{'xpGainedBySkill':{'FARMING':500}}}").replace('\'', '"'), Session.class);
		HistoryView view = new HistoryView(null, null, null, new OsrsTelemetryConfig()
		{
			@Override
			public LootValuationMode lootValuationMode()
			{
				return LootValuationMode.GRAND_EXCHANGE;
			}
		}, id -> { });
		view.render(Collections.singletonList(HistoryEntry.from(s)));
		java.lang.reflect.Field open = HistoryView.class.getDeclaredField("openDetailSessionId");
		open.setAccessible(true);
		open.set(view, "f");
		long end = at.toEpochMilli() - 120_000;
		view.showDetail(s, Collections.singletonList(FarmingHistoryFixtures.herbRun(1L, end - 600_000, end)));
		StringBuilder sb = new StringBuilder();
		collect(view, sb);
		String all = sb.toString();
		assertTrue(all, all.contains("FARM RUN"));
		assertTrue(all, all.contains("Herb run"));
		assertTrue(all, all.contains("2 of 2 patches"));
		assertTrue(all, all.contains("Planted: Ranarr"));
		assertFalse("no harvest total or its caveat", all.contains("Harvests seen") || all.contains("herb sack"));

		// The session that didn't finish the run: a reference to the same run, never its counts again.
		view.showDetail(s, Collections.singletonList(FarmingHistoryFixtures.referenceFromEarlierSession(1L, end - 600_000, end)));
		StringBuilder ref = new StringBuilder();
		collect(view, ref);
		String refText = ref.toString();
		assertTrue(refText, refText.contains("Herb run"));
		assertTrue(refText, refText.contains("Finished and recorded in a later session"));
		assertFalse("no duplicate totals: " + refText, refText.contains("2 of 2 patches") || refText.contains("Planted:"));
	}
}
