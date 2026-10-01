package com.osrstelemetry.plugin.ui;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.activity.BarrowsBrother;
import com.osrstelemetry.plugin.activity.BarrowsRunProgress;
import com.osrstelemetry.plugin.activity.GotrGameState;
import com.osrstelemetry.plugin.activity.TemporossGameState;
import com.osrstelemetry.plugin.model.SlayerLiveStatus;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

/**
 * RELEASE CANDIDATE: the sidebar never clips. Every major surface -- Current
 * Session for each activity type, History detail and list, Loot Grouped and
 * Individual -- rendered with realistic LONG names at 150 / 180 / 213 / 260 px:
 * nothing extends past the sidebar edge (so no horizontal scrollbar is ever
 * needed), no HTML label is cut off (HTML never ellipsizes, it just clips),
 * and any text that truncates carries its full value in a tooltip (on the
 * label, or on the row that owns it).
 */
public class SidebarClippingRegressionTest
{
	static final Gson GSON = new Gson();
	static final Instant NOW = Instant.parse("2026-09-20T10:50:00Z");
	static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig() {};

	static Session session(String type, String key, String name, String state, String xp, String extra)
	{
		return GSON.fromJson(("{'sessionId':'s-" + key + "','activityIdentity':{'activityType':'" + type + "','activityKey':'" + key
			+ "','displayName':'" + name + "'},'state':'" + state + "','startedAt':'2026-09-20T10:00:00Z',"
			+ "'lastActiveAt':'2026-09-20T10:42:00Z','accumulatedActiveDurationMillis':12520000,"
			+ "'aggregates':{'xpGainedBySkill':" + xp + extra + "}}").replace('\'', '"'), Session.class);
	}

	static final String LOOT = ",'lootDrops':[{'sourceName':'Thermonuclear smoke devil','observedAt':'2026-09-20T10:30:00Z','items':["
		+ "{'itemId':12002,'itemName':'Occult necklace','quantity':1},{'itemId':11998,'itemName':'Smoke battlestaff','quantity':1},"
		+ "{'itemId':995,'itemName':'Coins','quantity':1234567},{'itemId':561,'itemName':'Nature rune','quantity':4520},"
		+ "{'itemId':1,'itemName':'Dragon chainbody','quantity':1}]}]";

	static Map<String, Object[]> fixtures()
	{
		Map<String, Object[]> m = new LinkedHashMap<>();
		m.put("skilling", new Object[]{session("SKILLING", "woodcutting:yew", "Yew trees", "ACTIVE", "{'WOODCUTTING':1234567,'FIREMAKING':98765}", ",'lootDrops':[]"), null});
		m.put("combat-long", new Object[]{session("COMBAT", "combat:x", "Undead Druid of the Forgotten Forest", "SUSPENDED", "{'HITPOINTS':120000,'RANGED':360000,'DEFENCE':5000}",
			LOOT + ",'reliableCount':{'kind':'KILLS','sessionOccurrences':1234}"), null});
		m.put("boss-long", new Object[]{session("BOSSING", "boss:tsd", "Thermonuclear smoke devil", "ACTIVE", "{'MAGIC':1200000,'HITPOINTS':400000}",
			LOOT + ",'reliableCount':{'kind':'KILLS','sessionOccurrences':1234,'authoritativeCurrentValue':12345}"), null});
		m.put("slayer-konar", new Object[]{session("SLAYER", "fossil island wyverns", "Fossil Island wyverns", "ACTIVE", "{'SLAYER':91000,'RANGED':300000,'HITPOINTS':100000}",
			LOOT + ",'latestSlayerCurrentRemaining':117,'slayerAssignments':[{'ordinal':1,'taskName':'Fossil Island wyverns','assignmentKey':'fossil island wyverns',"
			+ "'assignedAt':'2026-09-20T10:00:00Z','initialAmount':236,'latestRemaining':117,'unitsCompleted':119,'slaughterProcs':12,'expeditiousProcs':14,"
			+ "'masterId':8,'xpGainedBySkill':{'SLAYER':91000},'activeDurationStartMillis':0,'items':[{'itemId':1,'itemName':'Granite longsword','quantity':1}]}]"),
			new SlayerLiveStatus(8, 12840, 999, "NORMAL", "Fossil Island wyverns", "Mos Le'Harmless Cave", 0, 0, false)});
		m.put("barrows", new Object[]{session("STRUCTURED_ACTIVITY", "barrows", "Barrows", "ACTIVE", "{'MAGIC':42000,'HITPOINTS':9000}",
			",'lootDrops':[],'activityCompletions':{'activityKey':'barrows','sessionCompletions':17,'latestAuthoritativeValue':1312}"), "barrows"});
		m.put("tempoross", new Object[]{session("STRUCTURED_ACTIVITY", "tempoross", "Tempoross", "ACTIVE", "{'FISHING':420000,'COOKING':3100}",
			",'lootDrops':[],'activityCompletions':{'activityKey':'tempoross','sessionCompletions':16,'latestAuthoritativeValue':1410},"
			+ "'activityStats':{'activityKey':'tempoross','currencyGained':1234,'scoredCompletions':16,'scoreTotal':52000,'bestScore':4512}"), "tempoross"});
		m.put("gotr", new Object[]{session("STRUCTURED_ACTIVITY", "guardians_of_the_rift", "Guardians of the Rift", "ACTIVE", "{'RUNECRAFT':420000,'MINING':3100,'CRAFTING':900}",
			",'lootDrops':[],'activityCompletions':{'activityKey':'guardians_of_the_rift','sessionCompletions':14,'latestAuthoritativeValue':1412}"
			+ ",'activityStats':{'activityKey':'guardians_of_the_rift','currencyGained':240,'currencyGainedByKey':{'elemental_points':110,'catalytic_points':130},"
			+ "'scoredCompletions':13,'scoreTotal':12700,'bestScore':1020}"
			+ ",'producedItems':[{'itemId':556,'itemName':'Air rune','quantity':124000},{'itemId':564,'itemName':'Cosmic rune','quantity':4180},"
			+ "{'itemId':561,'itemName':'Nature rune','quantity':2960},{'itemId':565,'itemName':'Blood rune','quantity':1200}]"), "gotr"});
		return m;
	}

	static List<Component> all(Container c)
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

	static void layout(Container c)
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

	static boolean showing(Component c, Container root)
	{
		for (Component p = c; p != null && p != root; p = p.getParent())
		{
			if (!p.isVisible())
			{
				return false;
			}
		}
		return true;
	}

	static boolean hasTooltip(Component c, Container root)
	{
		for (Component p = c; p != null && p != root.getParent(); p = p.getParent())
		{
			if (p instanceof javax.swing.JComponent && ((javax.swing.JComponent) p).getToolTipText() != null)
			{
				return true;
			}
		}
		return false;
	}

	static List<String> audit(Container root, int width)
	{
		root.setSize(width, 6000);
		for (int i = 0; i < 5; i++)
		{
			layout(root);
		}
		List<String> problems = new ArrayList<>();
		for (Component c : all(root))
		{
			if (!showing(c, root) || c.getWidth() == 0 && c.getHeight() == 0)
			{
				continue;
			}
			Rectangle r = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), root);
			String id = c.getClass().getSimpleName() + (c.getName() == null ? "" : "[" + c.getName() + "]");
			if (c instanceof JLabel)
			{
				JLabel l = (JLabel) c;
				String t = l.getText();
				if (t == null || t.isEmpty())
				{
					continue;
				}
				id += " '" + t.replaceAll("<[^>]*>", "") + "'";
				boolean html = t.startsWith("<html>");
				int pref = l.getPreferredSize().width;
				if (l.getWidth() < pref && !(l instanceof WrappingHtmlLabel))
				{
					if (html)
					{
						problems.add("CLIPPED-HTML " + id + " w=" + l.getWidth() + " pref=" + pref);
					}
					else if (!hasTooltip(l, root))
					{
						problems.add("TRUNCATED-WITHOUT-TOOLTIP " + id + " w=" + l.getWidth() + " pref=" + pref);
					}
				}
			}
			if (r.x + r.width > width + 1 || r.x < 0)
			{
				problems.add("OVERFLOW " + id + " x=" + r.x + " w=" + r.width);
			}
		}
		return problems;
	}

	static List<String> prefix(String where, List<String> problems)
	{
		List<String> out = new ArrayList<>();
		for (String p : problems)
		{
			out.add(where + ": " + p);
		}
		return out;
	}

	@Test
	public void currentSession_everyActivityType_neverClips_atAnySidebarWidth()
	{
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, Object[]> e : fixtures().entrySet())
		{
			for (int width : new int[]{150, 180, 213, 260})
			{
				CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
				Object extra = e.getValue()[1];
				if (extra instanceof SlayerLiveStatus)
				{
					v.setSlayerStatusSupplier(() -> (SlayerLiveStatus) extra);
				}
				else if ("barrows".equals(extra))
				{
					v.setBarrowsProgressSupplier(() -> BarrowsRunProgress.read(id -> id == BarrowsBrother.values()[0].getKilledVarbit() ? 1 : 0));
				}
				else if ("tempoross".equals(extra))
				{
					v.setTemporossGameSupplier(() -> null);
				}
				else if ("gotr".equals(extra))
				{
					v.setGotrGameSupplier(() -> GotrGameState.of(62, 280, 310));
				}
				v.render(CurrentSessionSnapshot.from(SessionSnapshot.capture((Session) e.getValue()[0]), NOW));
				problems.addAll(prefix("current-session " + e.getKey() + " @" + width, audit(v, width)));
			}
		}
		org.junit.Assert.assertTrue(String.join("\n", problems), problems.isEmpty());
	}

	@Test
	public void history_detailAndList_neverClip_atAnySidebarWidth()
	{
		List<String> problems = new ArrayList<>();
		java.util.List<com.osrstelemetry.plugin.history.HistoryEntry> entries = new java.util.ArrayList<>();
		for (Map.Entry<String, Object[]> e : fixtures().entrySet())
		{
			Session s = (Session) e.getValue()[0];
			String json = GSON.toJson(s).replace("\"state\":\"ACTIVE\"", "\"state\":\"FINALIZED\",\"finalizedAt\":\"2026-09-20T11:00:00Z\"")
				.replace("\"state\":\"SUSPENDED\"", "\"state\":\"FINALIZED\",\"finalizedAt\":\"2026-09-20T11:00:00Z\"");
			Session fin = GSON.fromJson(json, Session.class);
			entries.add(com.osrstelemetry.plugin.history.HistoryEntry.from(fin));
			for (int width : new int[]{150, 180, 213, 260})
			{
				HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
				d.render(HistoryDetailModel.from(fin));
				problems.addAll(prefix("history-detail " + e.getKey() + " @" + width, audit(d, width)));
			}
		}
		for (int width : new int[]{150, 180, 213, 260})
		{
			HistoryView hv = new HistoryView(null, null, null, CONFIG, id -> { });
			hv.render(entries);
			problems.addAll(prefix("history-list @" + width, audit(hv, width)));
		}
		org.junit.Assert.assertTrue(String.join("\n", problems), problems.isEmpty());
	}

	@Test
	public void loot_groupedAndIndividual_neverClip_atAnySidebarWidth() throws Exception
	{
		List<String> problems = new ArrayList<>();
		java.nio.file.Path root = java.nio.file.Files.createTempDirectory("clip-loot");
		com.osrstelemetry.plugin.storage.TelemetryPaths.init(com.osrstelemetry.plugin.storage.TestFilepaths.rooted(root));
		com.osrstelemetry.plugin.loottracker.LootTrackerPreferences prefs =
			new com.osrstelemetry.plugin.loottracker.LootTrackerPreferences(new com.osrstelemetry.plugin.storage.LocalStateStore(GSON));
		prefs.loadForAccount(777L);
		com.osrstelemetry.plugin.loottracker.LootTrackerIndex index = new com.osrstelemetry.plugin.loottracker.LootTrackerIndex();
		String[] names = {"Undead Druid of the Forgotten Forest", "Thermonuclear smoke devil", "Guardians of the Rift reward", "Goblin", "Kraken"};
		for (int i = 0; i < 40; i++)
		{
			String n = names[i % names.length];
			index.apply(new com.osrstelemetry.plugin.loottracker.LootTrackerRecord("e" + i, n, i % names.length, NOW.plusSeconds(i * 60L),
				java.util.Arrays.asList(new com.osrstelemetry.plugin.loottracker.LootTrackerItem(995, "Coins", 1234567L * (i + 1)),
					new com.osrstelemetry.plugin.loottracker.LootTrackerItem(12002, "Occult necklace", 1),
					new com.osrstelemetry.plugin.loottracker.LootTrackerItem(561, "Nature rune", 4520),
					new com.osrstelemetry.plugin.loottracker.LootTrackerItem(1, "Dragon chainbody", 1),
					new com.osrstelemetry.plugin.loottracker.LootTrackerItem(2, "Abyssal whip", 1),
					new com.osrstelemetry.plugin.loottracker.LootTrackerItem(3, "Granite longsword", 1))));
		}
		for (String mode : new String[]{"Grouped", "Individual"})
		{
			for (int width : new int[]{150, 180, 213, 260})
			{
				LootTrackerView v = new LootTrackerView(null, null, CONFIG, prefs);
				v.setSize(width, 6000);
				v.render(com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot.from(index, prefs));
				if (mode.equals("Individual"))
				{
					for (Component c : all(v))
					{
						if (c instanceof javax.swing.JButton && "Grouped".equals(((javax.swing.JButton) c).getText()))
						{
							for (java.awt.event.ActionListener l : ((javax.swing.JButton) c).getActionListeners())
							{
								l.actionPerformed(null);
							}
							break;
						}
					}
				}
				problems.addAll(prefix("loot-" + mode + " @" + width, audit(v, width)));
			}
		}
		org.junit.Assert.assertTrue(String.join("\n", problems), problems.isEmpty());
	}
}
