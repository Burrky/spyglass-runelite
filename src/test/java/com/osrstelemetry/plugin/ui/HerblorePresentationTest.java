package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
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
import com.osrstelemetry.plugin.skills.SkillExternalStock;
import com.osrstelemetry.plugin.skills.SkillGoal;
import com.osrstelemetry.plugin.skills.SkillGoalPlanner;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.herblore.HerbloreCompanionDefinition;
import com.osrstelemetry.plugin.skills.herblore.HerbloreCompanionTracker;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import net.runelite.api.Experience;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The Herblore companion as the player sees it, rendered by the shared
 * Current Session / History code at 150 / 180 / 213 / 260 px with real layout
 * passes: RECIPE, CURRENT BATCH (limiting ingredient), SESSION (mixes /
 * cleaned), PRODUCTION in doses with its exact 4-dose equivalence, INPUTS
 * USED with actual saved secondaries, bonus doses, LEVEL and dose-normalized
 * STOCKPILE targets over bank + Potion Storage (known / unknown), herb
 * cleaning / unfinished / upgrade layouts, recipe switches, Mastering
 * Mixology, and History. No clipping, no horizontal scroll, no dead sections.
 */
public class HerblorePresentationTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-10-13T21:00:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};
	private static final int SR4 = ItemID._4DOSE2RESTORE;
	private static final int SR3 = ItemID._3DOSE2RESTORE;
	private static final int SR1 = ItemID._1DOSE2RESTORE;
	private static final int SNAP_UNF = ItemID.SNAPDRAGONVIAL;
	private static final int EGGS = ItemID.RED_SPIDERS_EGGS;

	// ---- fixtures ---------------------------------------------------------------------------------

	private static Session herblore(String state, String produced, String banked, String methodMillis, String consumed, String occurrences,
		String lastMethod, String xp, long activeMillis)
	{
		String stats = "'skillStats':{'skillKey':'herblore'" + (banked == null ? "" : ",'banked':[" + banked + "]")
			+ (methodMillis == null ? "" : ",'methodMillis':{" + methodMillis + "}")
			+ (consumed == null ? "" : ",'consumed':[" + consumed + "]")
			+ (occurrences == null ? "" : ",'occurrences':{" + occurrences + "}")
			+ (lastMethod == null ? "" : ",'lastMethod':'" + lastMethod + "'") + "}";
		return GSON.fromJson(("{'sessionId':'s-h','activityIdentity':{'activityType':'SKILLING','activityKey':'herblore',"
			+ "'displayName':'Herblore'},'state':'" + state + "','startedAt':'2026-10-13T19:58:00Z','lastActiveAt':'2026-10-13T21:00:00Z',"
			+ "'accumulatedActiveDurationMillis':" + activeMillis + "," + (state.equals("FINALIZED") ? "'finalizedAt':'2026-10-13T21:05:00Z'," : "")
			+ "'aggregates':{'xpGainedBySkill':{" + xp + "},'lootDrops':[]"
			+ (produced == null ? "" : ",'producedItems':[" + produced + "]") + "," + stats + "}}").replace('\'', '"'), Session.class);
	}

	private static Session active(String produced, String methodMillis, String consumed, String occurrences, String method)
	{
		return herblore("ACTIVE", produced, null, methodMillis, consumed, occurrences, method, "'HERBLORE':84300", 3_600_000L);
	}

	private static String item(int id, String name, long qty)
	{
		return "{'itemId':" + id + ",'itemName':'" + name.replace("'", "\\u0027") + "','quantity':" + qty + "}";
	}

	static final class Source implements SkillCompanionPanelSource
	{
		SkillCompanionView view = SkillCompanionView.EMPTY;
		SkillGoal goal = SkillGoal.DEFAULT;
		Integer xp = Experience.getXpForLevel(85) + 20_000;
		Map<Integer, Long> bank;
		SkillExternalStock storage = SkillExternalStock.NOT_APPLICABLE;

		@Override
		public SkillCompanionDefinition definition()
		{
			return HerbloreCompanionDefinition.INSTANCE;
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
			return xp;
		}

		@Override
		public Long bankQuantity(int itemId)
		{
			return bank == null ? null : bank.getOrDefault(itemId, 0L);
		}

		@Override
		public SkillExternalStock externalStock(SkillResource resource)
		{
			return storage;
		}
	}

	private static SkillCompanionView view(String method, SkillCompanionView.EventView event, List<SkillCompanionView.ContextLine> context,
		Map<Integer, SkillGoalPlanner.BankingSample> banking)
	{
		return new SkillCompanionView("herblore", method, "s-h", Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), null, context, banking)
			.withExtras(null, null, null, null, null, event);
	}

	private static SkillCompanionView view(String method, SkillCompanionView.EventView event)
	{
		return view(method, event, Collections.<SkillCompanionView.ContextLine>emptyList(),
			Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
	}

	/** CURRENT BATCH exactly as the real tracker builds it, after one observed mix of {@code deltas} from {@code carried}. */
	private static SkillCompanionView.EventView batch(Map<Integer, Long> carried, Object... deltas)
	{
		HerbloreCompanionTracker t = new HerbloreCompanionTracker();
		int tick = 10;
		long now = 1_000_000L;
		Map<Integer, Long> inv = new HashMap<>(carried);
		t.onInventory(tick, now, new HashMap<>(inv), inv.size());
		for (int i = 0; i < 2; i++)
		{
			t.onSkillXp(tick, 100, now);
			for (int d = 0; d < deltas.length; d += 2)
			{
				inv.merge((Integer) deltas[d], ((Number) deltas[d + 1]).longValue(), Long::sum);
			}
			inv.values().removeIf(v -> v <= 0);
			t.onInventory(tick, now, new HashMap<>(inv), inv.size());
			for (int k = 0; k < 4; k++)
			{
				tick++;
				now += 600;
				t.onTick(tick, now, 0);
			}
		}
		return t.event(now);
	}

	private static Map<Integer, Long> carried(Object... pairs)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			m.put((Integer) pairs[i], ((Number) pairs[i + 1]).longValue());
		}
		return m;
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

	private static String cell(Container root, String grid, String caption)
	{
		Component g = named(root, grid);
		Component c = g == null ? null : named((Container) g, SlayerStatGrid.VALUE_PREFIX + caption);
		return c == null ? null : ((JLabel) c).getText();
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
		for (Component k : all((Container) c))
		{
			if (k instanceof JLabel && ((JLabel) k).getText() != null && !((JLabel) k).getText().isEmpty())
			{
				return ((JLabel) k).getText().replaceAll("<[^>]*>", "").trim();
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

	private static final String RESTORES = item(SR3, "Super restore(3)", 340) + "," + item(SR4, "Super restore(4)", 18);
	private static final String RESTORE_INPUTS = item(SNAP_UNF, "Snapdragon potion (unf)", 358) + "," + item(EGGS, "Red spiders' eggs", 324);
	private static final String RESTORE_OCC = "'mix:super_restore':358,'units:super_restore':1092,'in:super_restore:" + SNAP_UNF + "':358,"
		+ "'in:super_restore:" + EGGS + "':324,'saved:super_restore':34,'bonus:super_restore':18";

	// ---- Current Session ---------------------------------------------------------------------------

	@Test
	public void standardPotion_batchSessionDosesInputsSavedBonus_levelTarget()
	{
		Source s = new Source();
		s.view = view("super_restore", batch(carried(SNAP_UNF, 144, EGGS, 190), SNAP_UNF, -1, EGGS, -1, SR3, 1));
		s.goal = SkillGoal.DEFAULT.withTargetLevel(90);
		CurrentSessionView v = render(active(RESTORES, "'super_restore':3600000", RESTORE_INPUTS, RESTORE_OCC, "super_restore"), s);
		assertEquals("Super restore", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("142", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "UNFINISHED"));
		assertEquals("188", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "SECONDARY"));
		assertEquals("142", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "CAN MAKE"));
		assertNull("no batch name / timer line", named(v, CurrentSessionView.SC_EVENT_TITLE_NAME));
		assertEquals("358", cell(v, CurrentSessionView.SC_ACTIONS_GRID_NAME, "MIXES"));
		assertEquals("358", cell(v, CurrentSessionView.SC_ACTIONS_GRID_NAME, "MIXES / HR"));
		assertEquals("1,092 doses", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "SUPER RESTORE"));
		assertEquals("(273 × 4-dose)", text(v, CurrentSessionView.SC_UNITS_NAME));
		assertEquals("1,092 doses/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		assertEquals("+18 bonus doses", text(v, CurrentSessionView.SC_OCCURRENCE_LINE_PREFIX + 0));
		assertEquals("358", cell(v, CurrentSessionView.SC_INPUTS_GRID_NAME, "UNFINISHED"));
		assertEquals("actual, never the theoretical 358", "324", cell(v, CurrentSessionView.SC_INPUTS_GRID_NAME, "SECONDARY"));
		assertEquals("34 secondaries saved", text(v, CurrentSessionView.SC_INPUTS_LINE_PREFIX + 0));
		assertNull("no generic consumed line", named(v, CurrentSessionView.SC_CONSUMED_NAME));
		assertNotNull(named(v, CurrentSessionView.SC_TARGET_GRID_NAME));
		assertEquals(String.format("%,d", Experience.getXpForLevel(90) - s.xp),
			cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_XP_LEFT_CELL));
		fitsEveryWidth(v, "standard");
	}

	@Test
	public void lowIngredients_canMakeFollowsTheLimit()
	{
		Source s = new Source();
		s.view = view("super_restore", batch(carried(SNAP_UNF, 30, EGGS, 5), SNAP_UNF, -1, EGGS, -1, SR3, 1));
		CurrentSessionView v = render(active(item(SR3, "Super restore(3)", 1), "'super_restore':60000", null,
			"'mix:super_restore':1,'units:super_restore':3", "super_restore"), s);
		assertEquals("3", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "CAN MAKE"));
		assertEquals("3 doses", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "SUPER RESTORE"));
		assertNull("below one 4-dose: no equivalence line", named(v, CurrentSessionView.SC_UNITS_NAME));
		assertEquals("rate only after 5 minutes", "-", cell(v, CurrentSessionView.SC_ACTIONS_GRID_NAME, "MIXES / HR"));
		fitsEveryWidth(v, "low");
	}

	@Test
	public void stockpile_dosesOverBankVariantsPlusPotionStorage_etaFromTheStoredRate()
	{
		Source s = new Source();
		Map<Integer, SkillGoalPlanner.BankingSample> banking = new HashMap<>();
		banking.put(SR3, new SkillGoalPlanner.BankingSample(300, 3, 3_000_000L));
		s.view = view("super_restore", null, Collections.<SkillCompanionView.ContextLine>emptyList(), banking);
		s.bank = new HashMap<>();
		s.bank.put(SR4, 600L);
		s.bank.put(SR3, 20L);
		s.bank.put(SR1, 4L);
		s.storage = SkillExternalStock.known("Potion Storage", 280L, null);
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(SR4).withTargetQuantity(1_000);
		CurrentSessionView v = render(active(RESTORES, "'super_restore':3600000", RESTORE_INPUTS, RESTORE_OCC, "super_restore"), s);
		// Bank 600x4 + 20x3 + 4x1 = 2,464 doses + 280 stored = 2,744 doses = 686 x 4-dose.
		assertEquals("686 × 4-dose", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, "OWNED"));
		// 4,000 - 2,744 = 1,256 doses left = 314 x 4-dose.
		assertEquals("314 × 4-dose", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_REMAINING_CELL));
		assertEquals("1,000 × 4-dose", text(v, CurrentSessionView.SC_TARGET_QUANTITY_NAME));
		assertEquals("Bank + Potion Storage", text(v, CurrentSessionView.SC_OWNERSHIP_NAME));
		// 300 (3) stored in 50 min = 1,080 doses/hr -> 1,256 left in ~1h 10m (never the 1,092 doses/hr produced).
		assertEquals("ETA ~1h 10m", text(v, CurrentSessionView.SC_ETA_LINE_NAME));
		@SuppressWarnings("unchecked")
		JComboBox<Object> combo = (JComboBox<Object>) named(v, CurrentSessionView.SC_TARGET_RESOURCE_NAME);
		assertEquals("this recipe's family only, never every potion", 1, combo.getItemCount());
		fitsEveryWidth(v, "stockpile");
	}

	@Test
	public void stockpile_unknownPotionStorage_isABound_neverZero_noEta()
	{
		Source s = new Source();
		Map<Integer, SkillGoalPlanner.BankingSample> banking = new HashMap<>();
		banking.put(SR3, new SkillGoalPlanner.BankingSample(300, 3, 3_000_000L));
		s.view = view("super_restore", null, Collections.<SkillCompanionView.ContextLine>emptyList(), banking);
		s.bank = new HashMap<>();
		s.bank.put(SR4, 616L);
		s.storage = SkillExternalStock.unknown("Potion Storage");
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(SR4).withTargetQuantity(1_000);
		CurrentSessionView v = render(active(RESTORES, "'super_restore':3600000", RESTORE_INPUTS, RESTORE_OCC, "super_restore"), s);
		assertEquals("≥ 616 × 4-dose", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, "OWNED"));
		assertEquals("≤ 384 × 4-dose", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_REMAINING_CELL));
		assertEquals("Potion Storage not seen yet: open your bank", text(v, CurrentSessionView.SC_OWNERSHIP_NAME));
		assertEquals("ETA -", text(v, CurrentSessionView.SC_ETA_LINE_NAME));
		fitsEveryWidth(v, "unknown storage");
	}

	@Test
	public void stockpile_bankNeverSeen_isUnknown()
	{
		Source s = new Source();
		s.view = view("super_restore", null);
		s.storage = SkillExternalStock.known("Potion Storage", 280L, "2026-10-12T10:00:00Z");
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(SR4).withTargetQuantity(1_000);
		CurrentSessionView v = render(active(RESTORES, "'super_restore':3600000", RESTORE_INPUTS, RESTORE_OCC, "super_restore"), s);
		assertEquals("-", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, "OWNED"));
		fitsEveryWidth(v, "bank unknown");
	}

	@Test
	public void herbCleaning_cleanedAndRate_noBatchNoDosesNoInputs()
	{
		Source s = new Source();
		s.view = view("clean_ranarr", null);
		CurrentSessionView v = render(active(item(ItemID.RANARR_WEED, "Ranarr weed", 482), "'clean_ranarr':900000",
			item(ItemID.UNIDENTIFIED_RANARR, "Grimy ranarr weed", 482), "'mix:clean_ranarr':482,'units:clean_ranarr':482,"
				+ "'in:clean_ranarr:" + ItemID.UNIDENTIFIED_RANARR + "':482", "clean_ranarr"), s);
		assertEquals("Clean ranarr", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("482", cell(v, CurrentSessionView.SC_ACTIONS_GRID_NAME, "CLEANED"));
		assertEquals("1,928", cell(v, CurrentSessionView.SC_ACTIONS_GRID_NAME, "CLEANED / HR"));
		assertEquals("482", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "RANARR WEED"));
		assertNull(named(v, CurrentSessionView.SC_EVENT_NAME));
		assertNull(named(v, CurrentSessionView.SC_INPUTS_NAME));
		assertNull(named(v, CurrentSessionView.SC_UNITS_NAME));
		fitsEveryWidth(v, "cleaning");
	}

	@Test
	public void unfinishedPotion_batchProductionInputs_noSessionCells()
	{
		Source s = new Source();
		s.view = view("ranarr_unf", batch(carried(ItemID.RANARR_WEED, 144, ItemID.VIAL_WATER, 190), ItemID.RANARR_WEED, -1, ItemID.VIAL_WATER, -1,
			ItemID.RANARRVIAL, 1));
		CurrentSessionView v = render(active(item(ItemID.RANARRVIAL, "Ranarr potion (unf)", 142), "'ranarr_unf':600000",
			item(ItemID.RANARR_WEED, "Ranarr weed", 142) + "," + item(ItemID.VIAL_WATER, "Vial of water", 142),
			"'mix:ranarr_unf':142,'units:ranarr_unf':142,'in:ranarr_unf:" + ItemID.RANARR_WEED + "':142,'in:ranarr_unf:" + ItemID.VIAL_WATER
				+ "':142", "ranarr_unf"), s);
		assertEquals("Ranarr potion (unf)", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("142", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "RANARR"));
		assertEquals("188", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "VIALS"));
		assertEquals("142", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "CAN MAKE"));
		assertEquals("142", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "RANARR POTION (UNF)"));
		assertEquals("142", cell(v, CurrentSessionView.SC_INPUTS_GRID_NAME, "VIALS"));
		assertNull("the count IS the production", named(v, CurrentSessionView.SC_ACTIONS_NAME));
		fitsEveryWidth(v, "unfinished");
	}

	@Test
	public void potionUpgrade_inputDosesToOutputDoses()
	{
		Source s = new Source();
		s.view = view("extended_antifire", batch(carried(ItemID._4DOSE1ANTIDRAGON, 40, ItemID.LAVA_SHARD, 300), ItemID._4DOSE1ANTIDRAGON, -1,
			ItemID.LAVA_SHARD, -4, ItemID._4DOSE2ANTIDRAGON, 1));
		CurrentSessionView v = render(active(item(ItemID._4DOSE2ANTIDRAGON, "Extended antifire(4)", 100), "'extended_antifire':1800000",
			item(ItemID._4DOSE1ANTIDRAGON, "Antifire potion(4)", 100) + "," + item(ItemID.LAVA_SHARD, "Lava scale shard", 400),
			"'mix:extended_antifire':100,'units:extended_antifire':400,'in:extended_antifire:" + ItemID._4DOSE1ANTIDRAGON + "':100,"
				+ "'in:extended_antifire:" + ItemID.LAVA_SHARD + "':400", "extended_antifire"), s);
		assertEquals("Extended antifire", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("152", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "ANTIFIRE DOSES"));
		assertEquals("152 doses", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "CAN MAKE"));
		assertEquals("400 doses", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "EXTENDED ANTIFIRE"));
		assertEquals("400", cell(v, CurrentSessionView.SC_INPUTS_GRID_NAME, "ANTIFIRE DOSES"));
		assertEquals("400", cell(v, CurrentSessionView.SC_INPUTS_GRID_NAME, "SECONDARY"));
		fitsEveryWidth(v, "upgrade");
	}

	@Test
	public void recipeSwitch_earlierRecipeOnItsOwnLine_neverBlended()
	{
		Source s = new Source();
		s.view = view("super_restore", null);
		CurrentSessionView v = render(active(RESTORES + "," + item(ItemID._3DOSEPRAYERRESTORE, "Prayer potion(3)", 120),
			"'prayer':1200000,'super_restore':2400000", RESTORE_INPUTS, RESTORE_OCC + ",'mix:prayer':120,'units:prayer':360", "super_restore"), s);
		assertEquals("1,092 doses", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "SUPER RESTORE"));
		assertEquals("Also: Prayer potion 360 doses", text(v, CurrentSessionView.SC_BYPRODUCT_PREFIX + 0));
		fitsEveryWidth(v, "switch");
	}

	@Test
	public void longRecipeName_hugeNumbers_fitEveryWidth()
	{
		Source s = new Source();
		s.view = view("divine_super_combat", batch(carried(ItemID._4DOSE2COMBAT, 9_999, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED, 99_999),
			ItemID._4DOSE2COMBAT, -1, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED, -4, ItemID._4DOSEDIVINECOMBAT, 1));
		s.bank = new HashMap<>();
		s.storage = SkillExternalStock.unknown("Potion Storage");
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(ItemID._4DOSEDIVINECOMBAT).withTargetQuantity(9_999_999);
		CurrentSessionView v = render(active(item(ItemID._4DOSEDIVINECOMBAT, "Divine super combat potion(4)", 1_234_567),
			"'divine_super_combat':36000000", item(ItemID._4DOSE2COMBAT, "Super combat potion(4)", 1_234_567),
			"'mix:divine_super_combat':1234567,'units:divine_super_combat':4938268,'in:divine_super_combat:" + ItemID._4DOSE2COMBAT
				+ "':1234567", "divine_super_combat"), s);
		assertEquals("Divine super combat potion", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("4,938,268 doses", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "DIVINE SUPER COMBAT"));
		assertEquals("\u2264 9,999,999", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_REMAINING_CELL));
		fitsEveryWidth(v, "long names");
	}

	@Test
	public void noRecipeYet_onlyTheTarget()
	{
		Source s = new Source();
		s.view = view(null, null);
		CurrentSessionView v = render(active(null, null, null, null, null), s);
		assertNull(named(v, CurrentSessionView.SC_METHOD_NAME));
		assertNull(named(v, CurrentSessionView.SC_PRODUCTION_NAME));
		assertNull(named(v, CurrentSessionView.SC_EVENT_NAME));
		assertNull(named(v, CurrentSessionView.SC_ACTIONS_NAME));
		assertNull(named(v, CurrentSessionView.SC_INPUTS_NAME));
		assertNotNull(named(v, CurrentSessionView.SC_TARGET_GRID_NAME));
		fitsEveryWidth(v, "no recipe");
	}

	@Test
	public void masteringMixology_contextLine_noBatch()
	{
		Source s = new Source();
		s.view = view(null, null, Collections.singletonList(new SkillCompanionView.ContextLine("Mastering Mixology: its own activity",
			SkillCompanionView.ContextLine.Tone.QUIET, null)), Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView v = render(active(null, null, null, null, null), s);
		assertEquals("Mastering Mixology: its own activity", text(v, CurrentSessionView.SC_CONTEXT_PREFIX + 0));
		assertNull(named(v, CurrentSessionView.SC_EVENT_NAME));
		assertNull(named(v, CurrentSessionView.SC_PRODUCTION_NAME));
		fitsEveryWidth(v, "mixology");
	}

	// ---- History -----------------------------------------------------------------------------------

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

	@Test
	public void history_recipeAwareRow_andPerRecipeDetail()
	{
		Session done = herblore("FINALIZED", RESTORES, item(SR3, "Super restore(3)", 300), "'super_restore':3600000", RESTORE_INPUTS,
			RESTORE_OCC, "super_restore", "'HERBLORE':84300", 3_600_000L);
		HistoryEntry entry = HistoryEntry.from(done);
		HistoryView list = new HistoryView(null, null, null, CONFIG, id -> { });
		list.render(Collections.singletonList(entry));
		FactLine stats = statsLine(list);
		assertNotNull(stats);
		assertEquals("Super restore" + FactLine.SEPARATOR + "358 mixes" + FactLine.SEPARATOR + "1,092 doses" + FactLine.SEPARATOR + "84,300 XP",
			stats.text());
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(done));
		assertEquals("Super restore", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 0));
		assertEquals("Made", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 1));
		assertEquals("Banked / stored", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 2));
		assertEquals("Snapdragon potion (unf)", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 3));
		assertEquals("Red spiders' eggs", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 4));
		assertEquals("Secondaries saved", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 5));
		assertEquals("Bonus doses", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 6));
		assertNull("never the generic blended row", named(d, HistoryDetailView.SKILL_PRODUCED_NAME));
		assertEquals("Herblore XP 84,300", text(d, HistoryDetailView.SKILL_XP_NAME));
		assertNotNull(named(d, HistoryDetailView.SKILL_METHOD_PREFIX + "super_restore"));
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(d, width);
			assertTrue(width + ": " + problems, problems.isEmpty());
			problems = SidebarClippingRegressionTest.audit(list, width);
			assertTrue("list " + width + ": " + problems, problems.isEmpty());
		}
	}

	@Test
	public void history_cleaningRow_andMixedRecipes_neverBlended()
	{
		Session cleaned = herblore("FINALIZED", item(ItemID.RANARR_WEED, "Ranarr weed", 482), null, "'clean_ranarr':900000", null,
			"'mix:clean_ranarr':482,'units:clean_ranarr':482", "clean_ranarr", "'HERBLORE':12050", 900_000L);
		HistoryView list = new HistoryView(null, null, null, CONFIG, id -> { });
		list.render(Collections.singletonList(HistoryEntry.from(cleaned)));
		assertEquals("Ranarr weed" + FactLine.SEPARATOR + "482 cleaned" + FactLine.SEPARATOR + "12,050 XP", statsLine(list).text());

		Session mixed = herblore("FINALIZED", RESTORES + "," + item(ItemID._3DOSEPRAYERRESTORE, "Prayer potion(3)", 120), null,
			"'prayer':1200000,'super_restore':2400000", RESTORE_INPUTS, RESTORE_OCC + ",'mix:prayer':120,'units:prayer':360", "super_restore",
			"'HERBLORE':100000", 3_600_000L);
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(mixed));
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < 20; i++)
		{
			String t = text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + i);
			if (t != null)
			{
				labels.add(t);
			}
		}
		assertTrue(labels.toString(), labels.contains("Super restore") && labels.contains("Prayer potion"));
		assertTrue("longest recipe first", labels.indexOf("Super restore") < labels.indexOf("Prayer potion"));
		HistoryView mixedList = new HistoryView(null, null, null, CONFIG, id -> { });
		mixedList.render(Collections.singletonList(HistoryEntry.from(mixed)));
		assertEquals("Super restore" + FactLine.SEPARATOR + "358 mixes" + FactLine.SEPARATOR + "1,092 doses" + FactLine.SEPARATOR + "+1 recipe"
			+ FactLine.SEPARATOR + "100,000 XP", statsLine(mixedList).text());
	}

	@Test
	public void history_oldHerbloreSession_rendersAsBefore()
	{
		Session old = GSON.fromJson(("{'sessionId':'old','activityIdentity':{'activityType':'SKILLING','activityKey':'herblore',"
			+ "'displayName':'Herblore'},'state':'FINALIZED','startedAt':'2026-01-01T10:00:00Z','finalizedAt':'2026-01-01T11:00:00Z',"
			+ "'accumulatedActiveDurationMillis':3600000,'aggregates':{'xpGainedBySkill':{'HERBLORE':50000},'lootDrops':[]}}")
			.replace('\'', '"'), Session.class);
		HistoryEntry entry = HistoryEntry.from(old);
		assertNull(entry.getSkillRowFacts());
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(old));
		assertNull(named(d, HistoryDetailView.SKILL_BLOCK_NAME));
	}
}
