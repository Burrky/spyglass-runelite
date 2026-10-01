package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.loottracker.LootTrackerIndex;
import com.osrstelemetry.plugin.loottracker.LootTrackerItem;
import com.osrstelemetry.plugin.loottracker.LootTrackerPreferences;
import com.osrstelemetry.plugin.loottracker.LootTrackerRecord;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionListener;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JTextField;
import org.junit.Test;

/**
 * RELEASE CANDIDATE abuse sweep: large retained data and rapid repeated
 * interactions never accumulate Swing components, never exceed the Individual
 * bound, and leave every surface in the same state as a single interaction.
 * (Structural assertions only -- no wall-clock thresholds, so never flaky.)
 */
public class AbuseSweepTest
{
	private static final Gson GSON = new Gson();
	private static final Instant NOW = Instant.parse("2026-09-20T10:50:00Z");
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};

	private static List<Component> all(Container c)
	{
		List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(all((Container) k));
			}
		}
		return out;
	}

	private static void layout(Container c)
	{
		c.doLayout();
		for (Component k : c.getComponents())
		{
			if (k instanceof Container)
			{
				layout((Container) k);
			}
		}
	}

	private static boolean press(Container root, String text)
	{
		for (Component c : all(root))
		{
			if (c instanceof JButton && text.equals(((JButton) c).getText()))
			{
				for (ActionListener l : ((JButton) c).getActionListeners())
				{
					l.actionPerformed(null);
				}
				return true;
			}
		}
		return false;
	}

	/** A long Slayer session: 60 assignments x 25 item types in Task History. */
	private static Session bigSlayerSession()
	{
		StringBuilder tasks = new StringBuilder();
		for (int t = 1; t <= 60; t++)
		{
			StringBuilder items = new StringBuilder();
			for (int i = 0; i < 25; i++)
			{
				items.append(i == 0 ? "" : ",").append("{'itemId':").append(1000 + i).append(",'itemName':'Item ").append(i).append("','quantity':").append(i + 1).append('}');
			}
			tasks.append(t == 1 ? "" : ",").append("{'ordinal':").append(t).append(",'taskName':'Task ").append(t).append("','assignmentKey':'task ").append(t)
				.append("','assignedAt':'2026-09-20T10:00:00Z','initialAmount':150,'latestRemaining':").append(t == 60 ? 40 : 0)
				.append(",'unitsCompleted':").append(t == 60 ? 110 : 150).append(",'masterId':5,'xpGainedBySkill':{'SLAYER':12000},'activeDurationStartMillis':0")
				.append(t == 60 ? "" : ",'activeDurationEndMillis':0,'endReason':'COMPLETED','completedAt':'2026-09-20T10:00:00Z','endedAt':'2026-09-20T10:00:00Z'")
				.append(",'items':[").append(items).append("]}");
		}
		return GSON.fromJson(("{'sessionId':'s','activityIdentity':{'activityType':'SLAYER','activityKey':'task 60','displayName':'Task 60'},"
			+ "'state':'ACTIVE','startedAt':'2026-09-20T10:00:00Z','lastActiveAt':'2026-09-20T10:42:00Z','accumulatedActiveDurationMillis':36000000,"
			+ "'aggregates':{'xpGainedBySkill':{'SLAYER':900000,'RANGED':3000000,'HITPOINTS':1000000},'lootDrops':[],'latestSlayerCurrentRemaining':40,"
			+ "'slayerAssignments':[" + tasks + "]}}").replace('\'', '"'), Session.class);
	}

	private static Session small(String type, String key, String name, String state)
	{
		return GSON.fromJson(("{'sessionId':'s-" + key + "','activityIdentity':{'activityType':'" + type + "','activityKey':'" + key
			+ "','displayName':'" + name + "'},'state':'" + state + "','startedAt':'2026-09-20T10:00:00Z','lastActiveAt':'2026-09-20T10:42:00Z',"
			+ "'accumulatedActiveDurationMillis':600000," + (state.equals("FINALIZED") ? "'finalizedAt':'2026-09-20T11:00:00Z'," : "")
			+ "'aggregates':{'xpGainedBySkill':{'MINING':1000},'lootDrops':[]}}").replace('\'', '"'), Session.class);
	}

	@Test
	public void currentSession_rapidSessionSwitching_resizesAndExpandAll_neverAccumulateComponents()
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		CurrentSessionSnapshot slayer = CurrentSessionSnapshot.from(SessionSnapshot.capture(bigSlayerSession()), NOW);
		CurrentSessionSnapshot mining = CurrentSessionSnapshot.from(SessionSnapshot.capture(small("SKILLING", "mining", "Mining", "SUSPENDED")), NOW);
		v.setSize(225, 8000);
		v.render(slayer);
		int slayerCount = all(v).size();
		v.render(mining);
		int miningCount = all(v).size();
		int[] widths = {150, 180, 213, 260};
		for (int i = 0; i < 120; i++)
		{
			v.render(i % 3 == 0 ? mining : slayer);
			v.setSize(widths[i % widths.length], 8000);
			layout(v);
		}
		v.render(slayer);
		assertEquals("no accumulated Swing components after 120 renders/resizes", slayerCount, all(v).size());
		v.render(mining);
		assertEquals(miningCount, all(v).size());
		v.render(CurrentSessionSnapshot.empty());
		v.render(slayer);
		assertEquals(slayerCount, all(v).size());
	}

	@Test
	public void history_repeatedAndAlternatingLists_stayBounded()
	{
		HistoryView h = new HistoryView(null, null, null, CONFIG, id -> { });
		List<HistoryEntry> many = new ArrayList<>();
		for (int i = 0; i < 300; i++)
		{
			Session s = small("SKILLING", "mining-" + i, "Mining", "FINALIZED");
			s = GSON.fromJson(GSON.toJson(s).replace("\"s-mining-" + i + "\"", "\"id-" + i + "\""), Session.class);
			many.add(HistoryEntry.from(s));
		}
		h.render(many);
		int full = all(h).size();
		for (int i = 0; i < 50; i++)
		{
			h.render(i % 2 == 0 ? many.subList(0, 10) : many);
		}
		h.render(many);
		assertEquals(full, all(h).size());
	}

	@Test
	public void loot_twentyThousandRecords_rapidToggleSearchAndLoadMore_boundedAndStable() throws Exception
	{
		TelemetryPaths.init(TestFilepaths.rooted(Files.createTempDirectory("abuse-loot")));
		LootTrackerPreferences prefs = new LootTrackerPreferences(new LocalStateStore(GSON));
		prefs.loadForAccount(77L);
		LootTrackerIndex index = new LootTrackerIndex();
		int sources = 150;
		for (int s = 0; s < sources; s++)
		{
			for (int r = 0; r < 80; r++)
			{
				List<LootTrackerItem> items = new ArrayList<>();
				for (int i = 0; i < 6; i++)
				{
					items.add(new LootTrackerItem(100 + (s * 7 + i) % 400, "Item " + ((s * 7 + i) % 400), 1 + r));
				}
				index.apply(new LootTrackerRecord("s" + s + "r" + r, "Source " + s, s, NOW.minusSeconds((long) r * sources + s), items));
			}
		}
		LootTrackerView v = new LootTrackerView(null, null, CONFIG, prefs);
		v.setSize(225, 8000);
		v.render(LootTrackerSnapshot.from(index, prefs));
		int grouped = all(v).size();

		press(v, "Grouped");
		int clicks = 0;
		while (press(v, "Load more"))
		{
			clicks++;
			assertTrue("Load more always terminates at the cap", clicks < 20);
		}
		long records = all(v).stream().filter(c -> LootTrackerView.RECORD_CARD_NAME.equals(c.getName())).count();
		assertEquals("the global Individual budget holds under abuse", LootTrackerView.MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED, records);
		int individual = all(v).size();

		JTextField field = null;
		for (Component c : all(v))
		{
			if (c instanceof JTextField)
			{
				field = (JTextField) c;
			}
		}
		for (int round = 0; round < 2; round++)
		{
			for (String q : new String[]{"i", "it", "ite", "item", "item 1", "item 12", "item 1", "item", ""})
			{
				field.setText(q);
			}
			press(v, "Individual");
			assertEquals("Grouped identical after round " + round, grouped, all(v).size());
			press(v, "Grouped");
			assertEquals("Individual identical after round " + round, individual, all(v).size());
		}
	}
}
