package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillCompanionPanelSource;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillGoal;
import com.osrstelemetry.plugin.skills.SkillGoalPlanner;
import com.osrstelemetry.plugin.skills.mining.MiningCompanionDefinition;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import net.runelite.api.Experience;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The Mining companion as the player sees it -- modular (only sections that
 * earn their space), method-aware wording, TOTAL vs BANKED, by-products on
 * their own line, LEVEL and STOCKPILE targets -- at 150 / 180 / 213 / 260 px
 * with real layout passes (no clipping, no horizontal scroll), plus History.
 */
public class MiningPresentationTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-10-12T17:00:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};

	private static final int IRON = ItemID.IRON_ORE;
	private static final int COAL = ItemID.COAL;
	private static final int AMETHYST = ItemID.AMETHYST;

	// ---- fixtures ---------------------------------------------------------------------------------

	/** A Mining session, 60 active minutes: produced items, banked, method time, Mining XP. */
	private static Session mining(String state, String produced, String banked, String methodMillis, String lastMethod)
	{
		return mining(state, produced, banked, methodMillis, lastMethod, 3_600_000L);
	}

	private static Session mining(String state, String produced, String banked, String methodMillis, String lastMethod, long activeMillis)
	{
		String stats = "'skillStats':{'skillKey':'mining'" + (banked == null ? "" : ",'banked':[" + banked + "]")
			+ (methodMillis == null ? "" : ",'methodMillis':{" + methodMillis + "}") + (lastMethod == null ? "" : ",'lastMethod':'" + lastMethod + "'")
			+ "}";
		return GSON.fromJson(("{'sessionId':'s-mine','activityIdentity':{'activityType':'SKILLING','activityKey':'mining',"
			+ "'displayName':'Mining'},'state':'" + state + "','startedAt':'2026-10-12T15:58:00Z','lastActiveAt':'2026-10-12T17:00:00Z',"
			+ "'accumulatedActiveDurationMillis':" + activeMillis + "," + (state.equals("FINALIZED") ? "'finalizedAt':'2026-10-12T17:05:00Z'," : "")
			+ "'aggregates':{'xpGainedBySkill':{'MINING':38600},'lootDrops':[]"
			+ (produced == null ? "" : ",'producedItems':[" + produced + "]") + "," + stats + "}}").replace('\'', '"'), Session.class);
	}

	private static String item(int id, String name, long qty)
	{
		return "{'itemId':" + id + ",'itemName':'" + name + "','quantity':" + qty + "}";
	}

	private static CurrentSessionSnapshot snap(Session s)
	{
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s), NOW);
	}

	static final class Source implements SkillCompanionPanelSource
	{
		SkillCompanionView view = SkillCompanionView.EMPTY;
		SkillGoal goal = SkillGoal.DEFAULT;
		Integer xp = Experience.getXpForLevel(80) + 10_000;
		Map<Integer, Long> bank;
		int writes;

		@Override
		public SkillCompanionDefinition definition()
		{
			return MiningCompanionDefinition.INSTANCE;
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
			writes++;
		}

		@Override
		public Integer skillXp()
		{
			return xp;
		}

		@Override
		public Long bankQuantity(int itemId)
		{
			return bank == null ? null : bank.getOrDefault(itemId, 0L);
		}
	}

	private static SkillCompanionView view(String method, SkillCompanionView.TripView trip, List<SkillCompanionView.ContextLine> context,
		Map<Integer, Long> pending)
	{
		return new SkillCompanionView("mining", method, null, Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), pending == null ? Collections.<Integer, Long>emptyMap() : pending,
			Collections.<Integer, Long>emptyMap(), Collections.<String, Long>emptyMap(), trip,
			context == null ? Collections.<SkillCompanionView.ContextLine>emptyList() : context,
			Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
	}

	private static SkillCompanionView.TripView trip(int space, Long estMillis)
	{
		return new SkillCompanionView.TripView(space, estMillis, null, null, null, 0, false);
	}

	private static CurrentSessionView render(Source s, Session session)
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		v.setSkillCompanionSource(s);
		v.render(snap(session));
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

	private static String cell(Container root, String grid, String caption)
	{
		Component g = named(root, grid);
		Component c = g == null ? null : named((Container) g, SlayerStatGrid.VALUE_PREFIX + caption);
		return c == null ? null : ((JLabel) c).getText();
	}

	private static String text(Container root, String name)
	{
		Component c = named(root, name);
		return c == null ? null : ((JLabel) c).getText().replaceAll("<[^>]*>", "").trim();
	}

	private static void fitsEveryWidth(Container v, String what)
	{
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(v, width);
			assertTrue(what + " @" + width + ": " + problems, problems.isEmpty());
		}
	}

	private static final String IRON_SESSION = item(IRON, "Iron ore", 1_103);

	// ---- ordinary ore ------------------------------------------------------------------------------

	@Test
	public void genericOre_methodTripProductionTarget()
	{
		Source s = new Source();
		s.view = view("iron", trip(6, 21_000L), null, null);
		Session session = mining("ACTIVE", IRON_SESSION, item(IRON, "Iron ore", 742), "'iron':3600000", "iron");
		CurrentSessionView v = render(s, session);
		assertEquals("Iron", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("6", cell(v, CurrentSessionView.SC_TRIP_GRID_NAME, CurrentSessionView.SC_SPACE_LEFT_CELL));
		assertEquals("~0:21", cell(v, CurrentSessionView.SC_TRIP_GRID_NAME, CurrentSessionView.SC_EST_FULL_CELL));
		assertEquals("1,103", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "IRON MINED"));
		assertEquals("742", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, CurrentSessionView.SC_BANKED_CELL));
		assertEquals("1,103 ore/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		assertNull("one resource: no breakdown line", named(v, CurrentSessionView.SC_BREAKDOWN_NAME));
		assertNotNull(named(v, CurrentSessionView.SC_TARGET_GRID_NAME));
		fitsEveryWidth(v, "generic ore");
	}

	@Test
	public void powermining_dropsNeverLowerTotal_bankedStaysZero()
	{
		Source s = new Source();
		s.view = view("iron", trip(12, null), null, null);
		CurrentSessionView v = render(s, mining("ACTIVE", item(IRON, "Iron ore", 1_000), null, "'iron':3600000", "iron"));
		assertEquals("1,000", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "IRON MINED"));
		assertEquals("0", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, CurrentSessionView.SC_BANKED_CELL));
		assertEquals("no rate is ever assumed", "-", cell(v, CurrentSessionView.SC_TRIP_GRID_NAME, CurrentSessionView.SC_EST_FULL_CELL));
		assertNull("no Held / Dropped clutter", named(v, "skillHeld"));
		fitsEveryWidth(v, "powermining");
	}

	@Test
	public void livePending_isAddedToTheSessionTotal_neverDoubleCounted()
	{
		Source s = new Source();
		Map<Integer, Long> pending = new HashMap<>();
		pending.put(IRON, 7L);
		s.view = view("iron", trip(21, null), null, pending);
		CurrentSessionView v = render(s, mining("ACTIVE", item(IRON, "Iron ore", 100), null, "'iron':3600000", "iron"));
		assertEquals("107", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "IRON MINED"));
	}

	@Test
	public void mixedResources_totalCaption_breakdownLine_gemsOnTheirOwnLine()
	{
		Source s = new Source();
		s.view = view("coal", trip(20, null), null, null);
		String produced = IRON_SESSION + "," + item(COAL, "Coal", 94) + "," + item(ItemID.UNCUT_SAPPHIRE, "Uncut sapphire", 41) + ","
			+ item(ItemID.UNCUT_EMERALD, "Uncut emerald", 26) + "," + item(ItemID.UNCUT_RUBY, "Uncut ruby", 14) + ","
			+ item(ItemID.UNCUT_DIAMOND, "Uncut diamond", 6);
		CurrentSessionView v = render(s, mining("ACTIVE", produced, null, "'iron':2400000,'coal':1200000", "coal"));
		assertEquals("never 1,284 read as iron", "1,197", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "TOTAL"));
		assertEquals("Iron 1,103 • Coal 94", text(v, CurrentSessionView.SC_BREAKDOWN_NAME));
		assertEquals("Gems: Sapphire 41 • Emerald 26 • Ruby 14 • Diamond 6", text(v, CurrentSessionView.SC_BYPRODUCT_PREFIX + 0));
		fitsEveryWidth(v, "mixed");
	}

	@Test
	public void unknownMethod_showsGenericMining_noMethodRow_noTrip()
	{
		Source s = new Source();
		s.view = view(null, trip(20, null), null, null);
		CurrentSessionView v = render(s, mining("ACTIVE", null, null, null, null));
		assertNull(named(v, CurrentSessionView.SC_METHOD_NAME));
		assertNull(named(v, CurrentSessionView.SC_TRIP_NAME));
		assertNull("nothing produced: no empty PRODUCTION block", named(v, CurrentSessionView.SC_PRODUCTION_NAME));
		assertNotNull("the target still earns its place", named(v, CurrentSessionView.SC_TARGET_NAME));
		fitsEveryWidth(v, "unknown");
	}

	@Test
	public void otherSkills_keepThePlainSession()
	{
		Source s = new Source();
		Session wc = GSON.fromJson(("{'sessionId':'w','activityIdentity':{'activityType':'SKILLING','activityKey':'woodcutting',"
			+ "'displayName':'Woodcutting'},'state':'ACTIVE','startedAt':'2026-10-12T16:00:00Z','lastActiveAt':'2026-10-12T17:00:00Z',"
			+ "'accumulatedActiveDurationMillis':600000,'aggregates':{'xpGainedBySkill':{'WOODCUTTING':5000},'lootDrops':[]}}")
			.replace('\'', '"'), Session.class);
		CurrentSessionView v = render(s, wc);
		assertNull(named(v, CurrentSessionView.SC_BLOCK_NAME));
	}

	// ---- specialized methods ------------------------------------------------------------------------

	@Test
	public void calcified_noTrip_shardsMain_depositsByproduct_waterLine()
	{
		Source s = new Source();
		List<SkillCompanionView.ContextLine> ctx = Collections.singletonList(new SkillCompanionView.ContextLine(
			"Water flowing: +15% success", SkillCompanionView.ContextLine.Tone.ALERT, "tip"));
		s.view = view("calcified", trip(26, null), ctx, null);
		String produced = item(ItemID.BLESSED_BONE_SHARD, "Blessed bone shards", 2_340) + "," + item(ItemID.CALCIFIED_DEPOSIT, "Calcified deposit", 12);
		CurrentSessionView v = render(s, mining("ACTIVE", produced, null, "'calcified':3600000", "calcified"));
		assertEquals("Calcified rocks", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertNull("stackable shards: inventory space is not a decision", named(v, CurrentSessionView.SC_TRIP_NAME));
		assertEquals("2,340", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "BONE SHARDS"));
		assertEquals("Deposits 12", text(v, CurrentSessionView.SC_BYPRODUCT_PREFIX + 0));
		assertEquals("2,340 shards/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		assertEquals("Water flowing: +15% success", text(v, CurrentSessionView.SC_CONTEXT_PREFIX + 0));
		fitsEveryWidth(v, "calcified");
	}

	@Test
	public void gemRocks_directStorage_gemsMined_exactBreakdown_storageLine()
	{
		Source s = new Source();
		s.view = view("gem_rocks", new SkillCompanionView.TripView(18, null, "Gem bag", null, 300, 12, true), null, null);
		String produced = item(ItemID.UNCUT_OPAL, "Uncut opal", 31) + "," + item(ItemID.UNCUT_JADE, "Uncut jade", 14) + ","
			+ item(ItemID.UNCUT_RED_TOPAZ, "Uncut red topaz", 8) + "," + item(ItemID.UNCUT_SAPPHIRE, "Uncut sapphire", 4) + ","
			+ "{'itemName':'Uncut gem','quantity':2}";
		CurrentSessionView v = render(s, mining("ACTIVE", produced, null, "'gem_rocks':3600000", "gem_rocks"));
		assertEquals("Gem rocks", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("59", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "GEMS MINED"));
		assertEquals("Opal 31 • Jade 14 • Red topaz 8 • Sapphire 4 • +1 more", text(v, CurrentSessionView.SC_BREAKDOWN_NAME));
		assertEquals("Gem bag +12 this trip", text(v, CurrentSessionView.SC_STORAGE_NAME));
		assertEquals("59 gems/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		fitsEveryWidth(v, "gem rocks");

		s.view = view("gem_rocks", new SkillCompanionView.TripView(18, null, "Gem bag", 42, 300, 12, true), null, null);
		v = render(s, mining("ACTIVE", produced, null, "'gem_rocks':3600000", "gem_rocks"));
		assertEquals("Gem bag 42 / 300", text(v, CurrentSessionView.SC_STORAGE_NAME));
	}

	// ---- targets ----------------------------------------------------------------------------------------

	@Test
	public void levelTarget_withEta_atTheObservedXpRate()
	{
		Source s = new Source();
		s.view = view("iron", trip(6, null), null, null);
		s.goal = SkillGoal.DEFAULT.withTargetLevel(92);
		CurrentSessionView v = render(s, mining("ACTIVE", IRON_SESSION, null, "'iron':3600000", "iron"));
		long left = Experience.getXpForLevel(92) - s.xp;
		assertEquals(String.format("%,d", left), cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_XP_LEFT_CELL));
		String eta = cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_ETA_CELL);
		assertTrue(eta, eta.startsWith("~"));
		assertEquals("Lvl 92", ((JLabel) named(v, CurrentSessionView.SC_TARGET_LEVEL_NAME)).getText());
		fitsEveryWidth(v, "level target");
	}

	@Test
	public void levelTarget_withoutEta_beforeFiveMinutes()
	{
		Source s = new Source();
		Session early = mining("ACTIVE", item(IRON, "Iron ore", 4), null, "'iron':60000", "iron", 60_000L);
		s.view = view("iron", trip(24, null), null, null);
		CurrentSessionView v = render(s, early);
		assertEquals("-", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_ETA_CELL));
		assertNull("no rate before 5 minutes", named(v, CurrentSessionView.SC_RATE_NAME));
	}

	@Test
	public void levelTarget_reached()
	{
		Source s = new Source();
		s.goal = SkillGoal.DEFAULT.withTargetLevel(70);
		CurrentSessionView v = render(s, mining("ACTIVE", IRON_SESSION, null, "'iron':3600000", "iron"));
		assertEquals("Reached", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_ETA_CELL));
	}

	@Test
	public void stockpileTarget_accountAware_bankedRateEta()
	{
		Source s = new Source();
		s.bank = new HashMap<>();
		s.bank.put(AMETHYST, 2_242L);
		Map<Integer, SkillGoalPlanner.BankingSample> banking = new HashMap<>();
		banking.put(AMETHYST, new SkillGoalPlanner.BankingSample(400, 2, 3_000_000L));
		s.view = new SkillCompanionView("mining", "amethyst", "s-mine", Collections.<Integer, Long>emptyMap(),
			Collections.<Integer, Long>emptyMap(), Collections.<String, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<Integer, Long>emptyMap(), Collections.<String, Long>emptyMap(), trip(7, 134_000L),
			Collections.<SkillCompanionView.ContextLine>emptyList(), banking);
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(AMETHYST).withTargetQuantity(5_000);
		CurrentSessionView v = render(s, mining("ACTIVE", item(AMETHYST, "Amethyst", 428), item(AMETHYST, "Amethyst", 400),
			"'amethyst':3600000", "amethyst"));
		assertEquals("428", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "AMETHYST"));
		assertEquals("400", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, CurrentSessionView.SC_BANKED_CELL));
		assertEquals("the account's bank, not today's session", "2,242",
			cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_IN_BANK_CELL));
		assertEquals("2,758", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_REMAINING_CELL));
		// 400 banked over 50 active minutes = 480/hr -> 2,758 left ~ 5h 45m.
		assertEquals("ETA " + SkillGoalPlanner.etaText((long) Math.ceil(2_758 / 480d * 3_600_000d)), text(v, CurrentSessionView.SC_ETA_LINE_NAME));
		@SuppressWarnings("unchecked")
		JComboBox<Object> combo = (JComboBox<Object>) named(v, CurrentSessionView.SC_TARGET_RESOURCE_NAME);
		assertEquals("method-aware: amethyst only", 1, combo.getItemCount());
		assertEquals("5,000", ((JLabel) named(v, CurrentSessionView.SC_TARGET_QUANTITY_NAME)).getText());
		fitsEveryWidth(v, "stockpile");
	}

	@Test
	public void stockpileTarget_noBankYet_noBankedRate_isHonest()
	{
		Source s = new Source();
		s.view = view("amethyst", trip(7, null), null, null);
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE);
		CurrentSessionView v = render(s, mining("ACTIVE", item(AMETHYST, "Amethyst", 428), null, "'amethyst':3600000", "amethyst"));
		assertEquals("-", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_IN_BANK_CELL));
		assertEquals("-", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_REMAINING_CELL));
		assertEquals("production/hr is never passed off as banked/hr", "ETA -", text(v, CurrentSessionView.SC_ETA_LINE_NAME));
	}

	@Test
	public void choosingAFamily_writesThePlanningPreference_andRerenders()
	{
		Source s = new Source();
		s.view = view("amethyst", trip(7, null), null, null);
		CurrentSessionView v = render(s, mining("ACTIVE", item(AMETHYST, "Amethyst", 10), null, "'amethyst':600000", "amethyst"));
		v.selectSkillGoal(s.goal.withFamily(SkillGoal.Family.STOCKPILE));
		assertEquals(1, s.writes);
		assertNotNull(named(v, CurrentSessionView.SC_TARGET_RESOURCE_NAME));
		assertNull(named(v, CurrentSessionView.SC_TARGET_LEVEL_NAME));
	}

	@Test
	public void parseSkillNumber()
	{
		assertEquals(Integer.valueOf(5_000), CurrentSessionView.parseSkillNumber("5,000", 1, 10_000_000));
		assertEquals(Integer.valueOf(92), CurrentSessionView.parseSkillNumber(" 92 ", 2, 99));
		assertNull(CurrentSessionView.parseSkillNumber("100", 2, 99));
		assertNull(CurrentSessionView.parseSkillNumber("abc", 1, 10));
	}

	// ---- History ------------------------------------------------------------------------------------

	@Test
	public void history_compactRow_andDetail()
	{
		String produced = item(AMETHYST, "Amethyst", 3_000) + "," + item(IRON, "Iron ore", 1_821) + ","
			+ item(ItemID.UNCUT_SAPPHIRE, "Uncut sapphire", 3);
		Session done = mining("FINALIZED", produced, item(AMETHYST, "Amethyst", 2_800) + "," + item(IRON, "Iron ore", 1_112),
			"'amethyst':6120000,'iron':1860000", "iron");
		HistoryEntry entry = HistoryEntry.from(done);
		assertEquals(4_824L, entry.getSkillProducedTotal());
		assertEquals("amethyst", entry.getSkillPrimaryMethod());

		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(done));
		assertEquals("Mined 4,824", text(d, HistoryDetailView.SKILL_PRODUCED_NAME));
		assertEquals("4,824/hr", text(d, HistoryDetailView.SKILL_PRODUCED_RATE_NAME));
		assertEquals("Banked 3,912 (81%)", text(d, HistoryDetailView.SKILL_BANKED_NAME));
		assertEquals("Mining XP 38,600", text(d, HistoryDetailView.SKILL_XP_NAME));
		assertEquals("Amethyst", text(d, HistoryDetailView.SKILL_METHOD_PREFIX + "amethyst"));
		assertEquals("MINED", text(d, HistoryDetailView.PRODUCED_ITEMS_NAME));
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(d, width);
			assertTrue(width + ": " + problems, problems.isEmpty());
		}
	}

	@Test
	public void history_olderMiningSession_withoutCompanionData_rendersAsBefore()
	{
		Session old = GSON.fromJson(("{'sessionId':'old','activityIdentity':{'activityType':'SKILLING','activityKey':'mining',"
			+ "'displayName':'Mining'},'state':'FINALIZED','startedAt':'2026-01-01T10:00:00Z','finalizedAt':'2026-01-01T11:00:00Z',"
			+ "'accumulatedActiveDurationMillis':3600000,'aggregates':{'xpGainedBySkill':{'MINING':50000},'lootDrops':[]}}")
			.replace('\'', '"'), Session.class);
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(old));
		assertNull(named(d, HistoryDetailView.SKILL_BLOCK_NAME));
		assertNull(named(d, HistoryDetailView.PRODUCED_ITEMS_NAME));
		assertFalse(text(d, "historyXpRow:MINING") != null && text(d, "historyXpRow:MINING").isEmpty());
	}

	@Test
	public void model_mergesPendingOnlyOnce_whenBoundAndFlushed()
	{
		Map<Integer, Long> flushed = new LinkedHashMap<>();
		flushed.put(IRON, 110L);
		Map<Integer, Long> pending = new LinkedHashMap<>();
		pending.put(IRON, 5L);
		SkillCompanionView bound = new SkillCompanionView("mining", "iron", "s-mine", flushed, Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), pending, Collections.<Integer, Long>emptyMap(), Collections.<String, Long>emptyMap(),
			null, Collections.<SkillCompanionView.ContextLine>emptyList(), Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		SkillCompanionModel m = SkillCompanionModel.build(snap(mining("ACTIVE", item(IRON, "Iron ore", 100), null, null, "iron")),
			MiningCompanionDefinition.INSTANCE, bound, SkillGoal.DEFAULT, null, id -> null);
		assertEquals("max(session 100, sent 110) + pending 5", 115L, m.production.total);
	}
}
