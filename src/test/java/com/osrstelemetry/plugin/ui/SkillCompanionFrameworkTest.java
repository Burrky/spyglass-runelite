package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillCompanionPanelSource;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillCompanions;
import com.osrstelemetry.plugin.skills.SkillGoal;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.fishing.FishingMethod;
import com.osrstelemetry.plugin.skills.mining.MiningCompanionDefinition;
import com.osrstelemetry.plugin.skills.mining.MiningMethod;
import com.osrstelemetry.plugin.skills.runecraft.RunecraftMethod;
import com.osrstelemetry.plugin.skills.woodcutting.WoodcuttingCompanionDefinition;
import com.osrstelemetry.plugin.skills.woodcutting.WoodcuttingMethod;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.JLabel;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * FRAMEWORK CONSOLIDATION: the six skill companions through the one shared
 * chassis, side by side.
 *
 * <ul>
 * <li>One definition per SKILLING key, round-tripping through the registry.</li>
 * <li>Every word the chassis shows comes from the skill's own definition:
 * neutral defaults, and no skill's vocabulary in another skill's text.</li>
 * <li>Every skill's live block (LEVEL and STOCKPILE) at 150 / 180 / 213 / 260 px.</li>
 * <li>Every OLDER session shape (no stats; stats with only a key; first-phase
 * stats with a retired method) rendered live and in History without a
 * crash, a clip or a false zero.</li>
 * </ul>
 */
public class SkillCompanionFrameworkTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-10-13T21:00:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};

	/** One real method and one of its real products per skill (key, method key, item id, item name). */
	private static final Object[][] SKILLS = {
		{"mining", MiningMethod.IRON.getKey(), ItemID.IRON_ORE, "Iron ore"},
		{"fishing", FishingMethod.SMALL_NET.getKey(), ItemID.RAW_SHRIMP, "Raw shrimps"},
		{"agility", "gnome", ItemID.GRACE, "Mark of grace"},
		{"woodcutting", WoodcuttingMethod.NORMAL.getKey(), ItemID.LOGS, "Logs"},
		{"runecraft", RunecraftMethod.NATURE.getKey(), ItemID.NATURERUNE, "Nature rune"},
		{"herblore", "super_restore", ItemID._3DOSE2RESTORE, "Super restore(3)"},
		{"smithing", "smelt_steel", ItemID.STEEL_BAR, "Steel bar"},
	};

	/** Each skill's own signature words: they may appear in that skill's text only. */
	private static final Map<String, Pattern> VOCABULARY = new LinkedHashMap<>();

	static
	{
		VOCABULARY.put("mining", words("ore", "ores", "pickaxe", "rock", "rocks", "gem", "gems"));
		VOCABULARY.put("fishing", words("fish", "catch", "catches", "barrel", "bait", "harpoon"));
		VOCABULARY.put("agility", words("course", "courses", "lap", "laps", "obstacle", "obstacles", "grace"));
		VOCABULARY.put("woodcutting", words("tree", "trees", "log", "logs", "axe", "forestry"));
		VOCABULARY.put("runecraft", words("essence", "rune", "runes", "altar", "pouch", "pouches", "crafting"));
		VOCABULARY.put("herblore", words("potion", "potions", "dose", "doses", "herb", "herbs", "mixology", "vial"));
		VOCABULARY.put("smithing", words("anvil", "furnace", "smelt", "smelted", "bar", "bars", "cannonball", "cannonballs", "foundry"));
	}

	private static Pattern words(String... w)
	{
		return Pattern.compile("\\b(" + String.join("|", w) + ")\\b", Pattern.CASE_INSENSITIVE);
	}

	// ---- registry ---------------------------------------------------------------------------------

	@Test
	public void registry_oneDefinitionPerSkillingKey_roundTrips()
	{
		Set<String> keys = new HashSet<>();
		Set<Skill> skills = new HashSet<>();
		for (SkillCompanionDefinition def : SkillCompanions.all())
		{
			assertTrue("unique key " + def.getSkillKey(), keys.add(def.getSkillKey()));
			assertTrue("unique skill " + def.getSkill(), skills.add(def.getSkill()));
			assertSame(def, SkillCompanions.forSkillKey(def.getSkillKey()));
			assertEquals("the key is the skill's own lower-case name", def.getSkill().getName().toLowerCase(Locale.ROOT), def.getSkillKey());
		}
		assertEquals(SKILLS.length, SkillCompanions.all().size());
		assertNull("a skill without a companion keeps the plain session", SkillCompanions.forSkillKey("cooking"));
		assertNull(SkillCompanions.forSkillKey(null));
	}

	// ---- vocabulary -------------------------------------------------------------------------------

	private static List<String> chassisText(SkillCompanionDefinition d)
	{
		return Arrays.asList(d.methodTitle(), d.storageTooltip(), d.autoTransformedTooltip(), d.eventTitle(), d.focusCountTooltip(),
			d.focusTimeCaption(), d.focusTimeTooltip(), d.bankedTooltip(), d.stockpileEmptyHint(), d.rewardTooltip(), d.rewardSourcesNoun(),
			d.failureTooltip(), d.consumedTooltip(), d.otherInputsTooltip());
	}

	@Test
	public void vocabulary_defaultsAreSkillNeutral_andNoSkillSpeaksAnothersWords()
	{
		for (String text : chassisText(new Stub()))
		{
			for (Map.Entry<String, Pattern> v : VOCABULARY.entrySet())
			{
				assertFalse("default \"" + text + "\" uses " + v.getKey() + " vocabulary", v.getValue().matcher(text).find());
			}
		}
		for (SkillCompanionDefinition def : SkillCompanions.all())
		{
			for (String text : chassisText(def))
			{
				for (Map.Entry<String, Pattern> v : VOCABULARY.entrySet())
				{
					if (!v.getKey().equals(def.getSkillKey()))
					{
						assertFalse(def.getSkillKey() + ": \"" + text + "\" uses " + v.getKey() + " vocabulary", v.getValue().matcher(text).find());
					}
				}
			}
		}
	}

	@Test
	public void vocabulary_skillsKeepTheirExactWording_theChassisNoLongerHardCodesIt()
	{
		assertEquals("Since this tree's first log", WoodcuttingCompanionDefinition.INSTANCE.focusTimeTooltip());
		assertEquals("courses", SkillCompanions.forSkillKey("agility").rewardSourcesNoun());
		assertTrue(SkillCompanions.forSkillKey("agility").failureTooltip().contains("dropped off the course"));
		assertTrue(SkillCompanions.forSkillKey("runecraft").otherInputsTooltip().contains("never counted as essence"));
		// Rendered: a focus from a skill that words nothing gets the neutral default; Woodcutting gets its own.
		Session session = session("ACTIVE", "mining", MiningMethod.IRON.getKey(), item(ItemID.IRON_ORE, "Iron ore", 12), null);
		SkillCompanionView focus = SkillCompanionView.EMPTY.withExtras(null, null, null, null,
			new SkillCompanionView.FocusView("Iron rocks", 12L, 90_000L), null);
		CurrentSessionView v = render(session, new Source(new Stub(), focus));
		assertEquals("Since its first success", tooltip(v, CurrentSessionView.SC_FOCUS_GRID_NAME, "TIME"));
		Session wc = session("ACTIVE", "woodcutting", WoodcuttingMethod.NORMAL.getKey(), item(ItemID.LOGS, "Logs", 12), null);
		CurrentSessionView w = render(wc, new Source(WoodcuttingCompanionDefinition.INSTANCE, SkillCompanionView.EMPTY.withExtras(null, null,
			null, null, new SkillCompanionView.FocusView("Tree", 12L, 90_000L), null)));
		assertEquals("Since this tree's first log", tooltip(w, CurrentSessionView.SC_FOCUS_GRID_NAME,
			WoodcuttingCompanionDefinition.INSTANCE.focusTimeCaption()));
		// The method row never reads "sees you herblore".
		Session herb = session("ACTIVE", "herblore", "super_restore", item(ItemID._3DOSE2RESTORE, "Super restore(3)", 30), null);
		CurrentSessionView h = render(herb, new Source(SkillCompanions.forSkillKey("herblore"), SkillCompanionView.EMPTY));
		Component method = named(h, CurrentSessionView.SC_METHOD_NAME);
		assertNotNull(method);
		assertEquals("The recipe Spyglass recognises from what you actually produce",
			((javax.swing.JComponent) method.getParent()).getToolTipText());
	}

	@Test
	public void cycleSkillWithoutRouteProgress_aCurrentCycleNeverCrashes_sessionCyclesStay()
	{
		// Runecraft names a cycle ("trip") but no route progress: a CURRENT cycle reaching the chassis used to
		// dereference the null progress caption. Now: no CURRENT section; SESSION trips unaffected.
		String nature = RunecraftMethod.NATURE.getKey();
		SkillCompanionView view = SkillCompanionView.EMPTY.withCycles(new SkillCompanionView.CycleView(nature, 2, null, 30_000L, true),
			null, null);
		Session session = rawSession("ACTIVE", "runecraft", "'skillStats':{'skillKey':'runecraft','lastMethod':'" + nature + "',"
				+ "'methodMillis':{'" + nature + "':3600000},'cycles':{'" + nature + "':{'count':12,'totalMillis':1800000,'bestMillis':140000}}}",
			item(ItemID.NATURERUNE, "Nature rune", 600));
		CurrentSessionView v = render(session, new Source(SkillCompanions.forSkillKey("runecraft"), view));
		assertNull(named(v, CurrentSessionView.SC_CYCLE_NAME));
		assertNotNull(named(v, CurrentSessionView.SC_CYCLE_SESSION_NAME));
		fitsEveryWidth(v, "runecraft cycle");
	}

	// ---- every skill, live, every width -----------------------------------------------------------

	@Test
	public void everySkill_liveBlock_levelAndStockpile_fitEveryWidth()
	{
		for (Object[] s : SKILLS)
		{
			String key = (String) s[0];
			SkillCompanionDefinition def = SkillCompanions.forSkillKey(key);
			int itemId = (Integer) s[2];
			SkillResource resource = def.resource(itemId);
			assertNotNull(key + ": the fixture item is this skill's production", resource);
			Session session = session("ACTIVE", key, (String) s[1], item(itemId, (String) s[3], 120), "'" + s[1] + "':3600000");
			// The inventory read too (a reward target counts what you carry).
			Source source = new Source(def, SkillCompanionView.EMPTY.withCarried(new HashMap<Integer, Long>()));
			source.bank = new HashMap<>();
			source.bank.put(itemId, 500L);

			source.goal = SkillGoal.DEFAULT.withTargetLevel(90);
			CurrentSessionView level = render(session, source);
			assertNotNull(key, named(level, CurrentSessionView.SC_BLOCK_NAME));
			assertEquals(key, def.methodLabel((String) s[1]), text(level, CurrentSessionView.SC_METHOD_NAME));
			assertNotNull(key + " LEVEL target", cellValue(level, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_XP_LEFT_CELL));
			fitsEveryWidth(level, key + " level");

			source.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(itemId).withTargetQuantity(2_000);
			CurrentSessionView stock = render(session, source);
			Component grid = named(stock, CurrentSessionView.SC_TARGET_GRID_NAME);
			assertNotNull(key + " STOCKPILE target", grid);
			assertNotNull(key + ": bank known -> a number, never '-'", firstValue((Container) grid));
			fitsEveryWidth(stock, key + " stockpile");
		}
	}

	@Test
	public void everySkill_resourceTargetIsNamedByItsResource_neverStockpile()
	{
		for (Object[] s : SKILLS)
		{
			SkillCompanionDefinition def = SkillCompanions.forSkillKey((String) s[0]);
			String label = CurrentSessionView.resourceTargetLabel(def, (String) s[1]);
			assertFalse(s[0] + ": " + label, label.contains("Stockpile"));
		}
		assertEquals("Iron ore", CurrentSessionView.resourceTargetLabel(SkillCompanions.forSkillKey("mining"), MiningMethod.IRON.getKey()));
		assertEquals("Marks of grace", CurrentSessionView.resourceTargetLabel(SkillCompanions.forSkillKey("agility"), "ardougne"));
		assertEquals("Crystal shards", CurrentSessionView.resourceTargetLabel(SkillCompanions.forSkillKey("agility"), "prifddinas"));
		assertEquals("Termites", CurrentSessionView.resourceTargetLabel(SkillCompanions.forSkillKey("agility"), "colossal_wyrm_basic"));
	}

	// ---- backward compatibility matrix ------------------------------------------------------------

	@Test
	public void backwardCompat_everySkill_olderSessionShapes_liveAndHistory_noCrashNoClipNoFalseZero()
	{
		for (Object[] s : SKILLS)
		{
			String key = (String) s[0];
			String[][] shapes = {
				// before the skill had a companion: no stats, no production
				{null, null, null},
				// a companion session that never produced anything: the key alone
				{"'skillStats':{'skillKey':'" + key + "'}", null, "key only"},
				// first-phase stats (banked / time / last method) with a method key this build no longer knows
				{"'skillStats':{'skillKey':'" + key + "','banked':[" + item((Integer) s[2], (String) s[3], 40) + "],"
					+ "'methodMillis':{'retired_method':600000},'lastMethod':'retired_method'}", item((Integer) s[2], (String) s[3], 90), "phase 1"},
			};
			for (String[] shape : shapes)
			{
				String what = key + " / " + (shape[2] == null ? "no stats" : shape[2]);
				Session live = rawSession("ACTIVE", key, shape[0], shape[1]);
				CurrentSessionView v = render(live, new Source(SkillCompanions.forSkillKey(key), SkillCompanionView.EMPTY));
				assertNotNull(what, named(v, CurrentSessionView.SC_BLOCK_NAME));
				if (shape[1] == null)
				{
					assertNull(what + ": nothing produced -> no PRODUCTION section (never a 0 total)",
						named(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME));
				}
				fitsEveryWidth(v, what);

				Session done = rawSession("FINALIZED", key, shape[0], shape[1]);
				HistoryEntry entry = HistoryEntry.from(done);
				assertNotNull(what, entry);
				HistoryView list = new HistoryView(null, null, null, CONFIG, id -> { });
				list.render(Collections.singletonList(entry));
				FactLine stats = statsLine(list);
				if (stats != null)
				{
					for (String fact : stats.text().split(Pattern.quote(FactLine.SEPARATOR)))
					{
						assertFalse(what + ": no false zero fact in \"" + stats.text() + "\"", fact.trim().matches("0( .*)?"));
					}
				}
				HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
				d.render(HistoryDetailModel.from(done));
				for (int width : WIDTHS)
				{
					assertTrue(what + " history @" + width, SidebarClippingRegressionTest.audit(d, width).isEmpty());
					assertTrue(what + " list @" + width, SidebarClippingRegressionTest.audit(list, width).isEmpty());
				}
			}
		}
	}

	// ---- fixtures ---------------------------------------------------------------------------------

	/** A skill-neutral definition: Mining's data, every wording hook left at its default, plus a focus. */
	private static final class Stub implements SkillCompanionDefinition
	{
		private final SkillCompanionDefinition m = MiningCompanionDefinition.INSTANCE;

		@Override
		public String getSkillKey()
		{
			return m.getSkillKey();
		}

		@Override
		public Skill getSkill()
		{
			return m.getSkill();
		}

		@Override
		public String methodLabel(String methodKey)
		{
			return m.methodLabel(methodKey);
		}

		@Override
		public SkillResource resource(Integer itemId)
		{
			return m.resource(itemId);
		}

		@Override
		public boolean tripMatters(String methodKey)
		{
			return false;
		}

		@Override
		public boolean groupIsPrimary(String methodKey, String group)
		{
			return false;
		}

		@Override
		public String productionCaption(String methodKey, List<SkillResource> mainResources)
		{
			return "PRODUCED";
		}

		@Override
		public String rateNoun(String methodKey)
		{
			return "items";
		}

		@Override
		public String producedVerb()
		{
			return "produced";
		}

		@Override
		public String producedTitle()
		{
			return "Produced";
		}

		@Override
		public List<SkillResource> stockpileResources(String methodKey)
		{
			return Collections.emptyList();
		}

		@Override
		public boolean storageRelevant(String methodKey, Set<Integer> producedItems)
		{
			return false;
		}

		@Override
		public boolean storageCatchesMain(String methodKey)
		{
			return false;
		}

		@Override
		public String focusTitle()
		{
			return "Current focus";
		}
	}

	private static final class Source implements SkillCompanionPanelSource
	{
		private final SkillCompanionDefinition def;
		private final SkillCompanionView view;
		SkillGoal goal = SkillGoal.DEFAULT;
		Map<Integer, Long> bank;

		Source(SkillCompanionDefinition def, SkillCompanionView view)
		{
			this.def = def;
			this.view = view;
		}

		@Override
		public SkillCompanionDefinition definition()
		{
			return def;
		}

		@Override
		public SkillCompanionView view()
		{
			return view;
		}

		@Override
		public SkillGoal goal()
		{
			return goal;
		}

		@Override
		public void setGoal(SkillGoal g)
		{
			goal = g;
		}

		@Override
		public Integer skillXp()
		{
			return Experience.getXpForLevel(70) + 1_000;
		}

		@Override
		public Long bankQuantity(int itemId)
		{
			return bank == null ? null : bank.getOrDefault(itemId, 0L);
		}
	}

	private static String item(int id, String name, long qty)
	{
		return "{'itemId':" + id + ",'itemName':'" + name.replace("'", "\\u0027") + "','quantity':" + qty + "}";
	}

	private static Session session(String state, String key, String method, String produced, String methodMillis)
	{
		String stats = "'skillStats':{'skillKey':'" + key + "','lastMethod':'" + method + "'"
			+ (methodMillis == null ? "" : ",'methodMillis':{" + methodMillis + "}") + "}";
		return rawSession(state, key, stats, produced);
	}

	private static Session rawSession(String state, String key, String stats, String produced)
	{
		String skill = SkillCompanions.forSkillKey(key).getSkill().name();
		String display = SkillCompanions.forSkillKey(key).getSkill().getName();
		return GSON.fromJson(("{'sessionId':'s-" + key + "','activityIdentity':{'activityType':'SKILLING','activityKey':'" + key
			+ "','displayName':'" + display + "'},'state':'" + state + "','startedAt':'2026-10-13T19:58:00Z',"
			+ "'lastActiveAt':'2026-10-13T21:00:00Z','accumulatedActiveDurationMillis':3600000,"
			+ (state.equals("FINALIZED") ? "'finalizedAt':'2026-10-13T21:05:00Z'," : "")
			+ "'aggregates':{'xpGainedBySkill':{'" + skill + "':25000},'lootDrops':[]"
			+ (produced == null ? "" : ",'producedItems':[" + produced + "]") + (stats == null ? "" : "," + stats) + "}}")
			.replace('\'', '"'), Session.class);
	}

	private static CurrentSessionView render(Session session, Source s)
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		v.setSkillCompanionSource(s);
		v.render(CurrentSessionSnapshot.from(SessionSnapshot.capture(session), NOW));
		return v;
	}

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

	private static Component named(Container root, String name)
	{
		for (Component c : all(root))
		{
			if (name.equals(c.getName()))
			{
				return c;
			}
		}
		return null;
	}

	private static JLabel valueLabel(Container root, String grid, String caption)
	{
		Component g = named(root, grid);
		Component c = g == null ? null : named((Container) g, SlayerStatGrid.VALUE_PREFIX + caption);
		return (JLabel) c;
	}

	private static String cellValue(Container root, String grid, String caption)
	{
		JLabel l = valueLabel(root, grid, caption);
		return l == null ? null : l.getText();
	}

	private static String tooltip(Container root, String grid, String caption)
	{
		JLabel l = valueLabel(root, grid, caption);
		return l == null ? null : l.getToolTipText();
	}

	/** The first value cell of a grid that shows a real number (null when every value is a placeholder). */
	private static String firstValue(Container grid)
	{
		for (Component c : all(grid))
		{
			if (c.getName() != null && c.getName().startsWith(SlayerStatGrid.VALUE_PREFIX) && c instanceof JLabel)
			{
				String t = ((JLabel) c).getText();
				if (t != null && t.matches(".*[0-9].*"))
				{
					return t;
				}
			}
		}
		return null;
	}

	private static String text(Container root, String name)
	{
		Component c = named(root, name);
		if (c == null)
		{
			return null;
		}
		if (c instanceof JLabel)
		{
			return ((JLabel) c).getText() == null ? null : ((JLabel) c).getText().replaceAll("<[^>]*>", "").trim();
		}
		List<String> parts = new ArrayList<>();
		for (Component k : all((Container) c))
		{
			if (k instanceof JLabel && ((JLabel) k).getText() != null && !((JLabel) k).getText().trim().isEmpty())
			{
				parts.add(((JLabel) k).getText().replaceAll("<[^>]*>", "").trim());
			}
		}
		// statRow: caption first, value last.
		return parts.isEmpty() ? null : parts.get(parts.size() - 1);
	}

	private static FactLine statsLine(HistoryView list)
	{
		for (Component c : all(list))
		{
			if (HistoryView.ROW_STATS_NAME.equals(c.getName()))
			{
				return (FactLine) c;
			}
		}
		return null;
	}

	private static void fitsEveryWidth(Container v, String what)
	{
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(v, width);
			assertTrue(what + " @" + width + ": " + problems, problems.isEmpty());
		}
	}
}
