package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.activity.PestControlGameState;
import com.osrstelemetry.plugin.pestcontrol.PestControlGoal;
import com.osrstelemetry.plugin.pestcontrol.PestControlPanelSource;
import com.osrstelemetry.plugin.pestcontrol.SupplyPack;
import com.osrstelemetry.plugin.pestcontrol.VoidOwnership;
import com.osrstelemetry.plugin.pestcontrol.VoidStyle;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The Pest Control dashboard: CURRENT GAME (island only), GOAL (planner with
 * only the relevant selectors), SESSION (no Games/hr, no Best, no balance
 * cell) and the History SESSION block -- at every sidebar width.
 */
public class PestControlPresentationTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-10-02T04:20:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};

	/** 40 min active; 30 min of it in rounds (10 rounds), 3 observed wins of 2:00 / 2:10 / 2:20, 48 points. */
	private static final String STATS =
		",'activityCompletions':{'activityKey':'pest_control','sessionCompletions':4}"
			+ ",'activityStats':{'activityKey':'pest_control','currencyGained':48,'currencyGainedByKey':{'commendation_points':48},"
			+ "'scoredCompletions':3,'scoreTotal':390,'bestScore':140,'roundMillis':1800000,'roundsTimed':10}";

	private static Session pc(String state, String extra)
	{
		return GSON.fromJson(("{'sessionId':'s-pc','activityIdentity':{'activityType':'STRUCTURED_ACTIVITY',"
			+ "'activityKey':'pest_control','displayName':'Pest Control'},'state':'" + state + "',"
			+ "'startedAt':'2026-10-02T03:40:00Z','lastActiveAt':'2026-10-02T04:19:00Z','accumulatedActiveDurationMillis':2400000,"
			+ (state.equals("FINALIZED") ? "'finalizedAt':'2026-10-02T04:30:00Z'," : "")
			+ "'aggregates':{'xpGainedBySkill':{'ATTACK':42000,'HITPOINTS':14000},'lootDrops':[]" + extra + "}}")
			.replace('\'', '"'), Session.class);
	}

	private static CurrentSessionSnapshot snap(Session s)
	{
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s), NOW);
	}

	/** A mutable fake of the plugin's source. */
	static final class Source implements PestControlPanelSource
	{
		PestControlGameState game;
		Integer balance;
		VoidOwnership ownership = new VoidOwnership();
		Map<Skill, Integer> xp = new HashMap<>();
		PestControlGoal goal = PestControlGoal.DEFAULT;
		int writes;

		@Override
		public PestControlGameState game()
		{
			return game;
		}

		@Override
		public Integer balance()
		{
			return balance;
		}

		@Override
		public VoidOwnership.Snapshot ownership()
		{
			return ownership.getSnapshot();
		}

		@Override
		public Integer skillXp(Skill skill)
		{
			return xp.get(skill);
		}

		@Override
		public PestControlGoal goal()
		{
			return goal;
		}

		@Override
		public void setGoal(PestControlGoal g)
		{
			goal = g;
			writes++;
		}
	}

	private static CurrentSessionView view(Source source)
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		v.setPestControlSource(source);
		v.setNowSupplier(() -> NOW);
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

	private static String cell(Container root, String caption)
	{
		Component c = named(root, SlayerStatGrid.VALUE_PREFIX + caption);
		return c == null ? null : ((JLabel) c).getText();
	}

	private static Component gridOf(Container root, String gridName, String caption)
	{
		Component grid = named(root, gridName);
		return grid == null ? null : named((Container) grid, SlayerStatGrid.VALUE_PREFIX + caption);
	}

	private static Source liveSource()
	{
		Source s = new Source();
		s.game = new PestControlGameState(55, 90, 2, NOW.minusSeconds(92), NOW.minusSeconds(92));
		return s;
	}

	// ---- CURRENT GAME ---------------------------------------------------------------

	@Test
	public void currentGame_grid_activityKnightPortalsTime()
	{
		CurrentSessionView v = view(liveSource());
		v.render(snap(pc("ACTIVE", STATS)));
		assertNotNull(named(v, CurrentSessionView.PEST_GAME_GRID_NAME));
		assertEquals("55%", cell(v, CurrentSessionView.PEST_ACTIVITY_CELL));
		assertEquals("90%", cell(v, CurrentSessionView.PEST_KNIGHT_CELL));
		assertEquals("2/4", cell(v, CurrentSessionView.PEST_PORTALS_CELL));
		assertEquals("1:32", cell(v, CurrentSessionView.PEST_TIME_CELL));
	}

	@Test
	public void currentGame_unreadValues_areDashes_andJoinedPartWay_hasNoTime()
	{
		Source s = new Source();
		s.game = new PestControlGameState(null, null, null, null, NOW.minusSeconds(30));
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		for (String c : new String[]{CurrentSessionView.PEST_ACTIVITY_CELL, CurrentSessionView.PEST_KNIGHT_CELL,
			CurrentSessionView.PEST_PORTALS_CELL, CurrentSessionView.PEST_TIME_CELL})
		{
			assertEquals(c, "-", cell(v, c));
		}
	}

	@Test
	public void noCurrentGame_offTheIsland_orWhenSuspended()
	{
		Source s = new Source();
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		assertNull("outpost: no current game", named(v, CurrentSessionView.PEST_GAME_GRID_NAME));
		Source live = liveSource();
		CurrentSessionView w = view(live);
		w.render(snap(pc("SUSPENDED", STATS)));
		assertNull("a suspended session shows no live game", named(w, CurrentSessionView.PEST_GAME_GRID_NAME));
		assertNotNull("the goal and session stay", named(w, CurrentSessionView.PEST_SESSION_GRID_NAME));
	}

	// ---- SESSION --------------------------------------------------------------------

	@Test
	public void sessionGrid_gamesCommendationsAvgRate_only()
	{
		CurrentSessionView v = view(new Source());
		v.render(snap(pc("ACTIVE", STATS)));
		Container grid = (Container) named(v, CurrentSessionView.PEST_SESSION_GRID_NAME);
		assertEquals("4", cell(grid, CurrentSessionView.PEST_GAMES_CELL));
		assertEquals("+48", cell(grid, CurrentSessionView.PEST_COMMENDATIONS_CELL));
		assertEquals("2:10", cell(grid, CurrentSessionView.PEST_AVG_CELL));
		assertEquals("48 points / 30 min of rounds, never the 40 active", "96.0", cell(grid, CurrentSessionView.PEST_RATE_CELL));
		assertEquals("exactly four cells", 4, ((Container) grid).getComponentCount());
		assertNull("no Games/hr", named(v, SlayerStatGrid.VALUE_PREFIX + "GAMES/HR"));
		assertNull("no Best", named(v, SlayerStatGrid.VALUE_PREFIX + "BEST"));
		assertNull("no balance cell", named(v, SlayerStatGrid.VALUE_PREFIX + "BALANCE"));
		assertNull("no duplicate header line", named(v, CurrentSessionView.COMPLETION_METRIC_NAME));
		assertFalse("no '+' on the rate", cell(grid, CurrentSessionView.PEST_RATE_CELL).startsWith("+"));
	}

	@Test
	public void rate_includesTheRoundInProgress()
	{
		Source s = new Source();
		s.game = new PestControlGameState(50, 100, 0, NOW.minusSeconds(600), NOW.minusSeconds(600));
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		// 48 / (30 + 10 min) = 72.0
		assertEquals("72.0", cell((Container) named(v, CurrentSessionView.PEST_SESSION_GRID_NAME), CurrentSessionView.PEST_RATE_CELL));
	}

	// ---- GOAL -----------------------------------------------------------------------

	private static boolean has(Container v, String name)
	{
		return named(v, name) != null;
	}

	@Test
	public void goal_void_nextMissing_showsGoalAndStyle_only()
	{
		Source s = new Source();
		s.balance = 100;
		s.ownership.update(VoidOwnership.Container.BANK, new HashMap<>());
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		assertTrue(has(v, CurrentSessionView.PEST_FAMILY_NAME));
		assertTrue(has(v, CurrentSessionView.PEST_VOID_GOAL_NAME));
		assertTrue(has(v, CurrentSessionView.PEST_STYLE_NAME));
		assertFalse(has(v, CurrentSessionView.PEST_PIECE_NAME));
		assertFalse(has(v, CurrentSessionView.PEST_SKILL_NAME));
		assertFalse(has(v, CurrentSessionView.PEST_PACK_NAME));
		Container grid = (Container) named(v, CurrentSessionView.PEST_GOAL_GRID_NAME);
		assertEquals("250 - 100", "150", cell(grid, CurrentSessionView.PEST_POINTS_LEFT_CELL));
		// 150 points at 96/hr = 1h 34m (rounded up)
		assertEquals("~1h 34m", cell(grid, CurrentSessionView.PEST_ETA_CELL));
		assertEquals("Need: Top", ((JLabel) named(v, CurrentSessionView.PEST_GOAL_DETAIL_NAME)).getText().replaceAll("<[^>]*>", "").trim());
	}

	@Test
	public void goal_specificPiece_showsThePieceSelector_notTheStyle()
	{
		Source s = new Source();
		s.goal = PestControlGoal.DEFAULT.withVoidGoal(PestControlGoal.VoidGoal.PIECE);
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		assertTrue(has(v, CurrentSessionView.PEST_PIECE_NAME));
		assertFalse(has(v, CurrentSessionView.PEST_STYLE_NAME));
	}

	@Test
	public void goal_unknownBalance_showsCost_neverAssumesZero()
	{
		Source s = new Source();
		s.goal = PestControlGoal.DEFAULT.withVoidGoal(PestControlGoal.VoidGoal.FULL_SET).withVoidStyle(VoidStyle.RANGED);
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		Container grid = (Container) named(v, CurrentSessionView.PEST_GOAL_GRID_NAME);
		assertNull(cell(grid, CurrentSessionView.PEST_POINTS_LEFT_CELL));
		assertEquals("850", cell(grid, CurrentSessionView.PEST_COST_CELL));
		assertTrue(((JLabel) named(v, CurrentSessionView.PEST_GOAL_DETAIL_NAME)).getText().contains("bank not seen yet"));
	}

	@Test
	public void goal_xp_showsSkillAndLevel_andTheExactCost()
	{
		Source s = new Source();
		s.goal = PestControlGoal.DEFAULT.withFamily(PestControlGoal.Family.XP).withXpSkill(Skill.DEFENCE).withXpTargetLevel(99);
		s.xp.put(Skill.DEFENCE, Experience.getXpForLevel(98));
		s.balance = 0;
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		assertTrue(has(v, CurrentSessionView.PEST_SKILL_NAME));
		assertEquals("Lvl 99", ((JLabel) named(v, CurrentSessionView.PEST_LEVEL_NAME)).getText());
		assertFalse(has(v, CurrentSessionView.PEST_VOID_GOAL_NAME));
		assertEquals("2,000", cell((Container) named(v, CurrentSessionView.PEST_GOAL_GRID_NAME), CurrentSessionView.PEST_POINTS_LEFT_CELL));
	}

	@Test
	public void goal_xp_unknownXp_isAProblem_notANumber()
	{
		Source s = new Source();
		s.goal = PestControlGoal.DEFAULT.withFamily(PestControlGoal.Family.XP);
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		assertEquals("-", ((JLabel) named(v, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.PEST_COST_CELL)).getText());
		assertEquals("-", cell(v, CurrentSessionView.PEST_ETA_CELL));
		assertTrue(((JLabel) named(v, CurrentSessionView.PEST_GOAL_DETAIL_NAME)).getText().contains("Defence XP not known yet"));
	}

	@Test
	public void goal_supplies_showsPackAndCount()
	{
		Source s = new Source();
		s.goal = PestControlGoal.DEFAULT.withFamily(PestControlGoal.Family.SUPPLIES).withPack(SupplyPack.MINERAL).withPackTarget(20);
		s.balance = 30;
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		assertTrue(has(v, CurrentSessionView.PEST_PACK_NAME));
		assertEquals("20 packs", ((JLabel) named(v, CurrentSessionView.PEST_PACK_COUNT_NAME)).getText());
		assertEquals("270", cell((Container) named(v, CurrentSessionView.PEST_GOAL_GRID_NAME), CurrentSessionView.PEST_POINTS_LEFT_CELL));
		assertTrue(((JLabel) named(v, CurrentSessionView.PEST_GOAL_DETAIL_NAME)).getText().contains("500 coal"));
	}

	@Test
	public void eta_withoutARate_isADash_neverADefaultRate()
	{
		Source s = new Source();
		s.balance = 0;
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", "")));
		assertEquals("-", cell((Container) named(v, CurrentSessionView.PEST_GOAL_GRID_NAME), CurrentSessionView.PEST_ETA_CELL));
	}

	@Test
	public void selectingAGoal_isPlanningState_andReRenders()
	{
		Source s = new Source();
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		v.selectPestGoal(s.goal.withFamily(PestControlGoal.Family.SUPPLIES));
		assertEquals(1, s.writes);
		assertTrue(has(v, CurrentSessionView.PEST_PACK_NAME));
		assertFalse(has(v, CurrentSessionView.PEST_VOID_GOAL_NAME));
	}

	@Test
	public void openDropdown_isNotRebuiltUnderTheCursor()
	{
		Source s = new Source();
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		JComboBox<?> family = (JComboBox<?>) named(v, CurrentSessionView.PEST_FAMILY_NAME);
		family.firePopupMenuWillBecomeVisible();
		v.render(snap(pc("ACTIVE", STATS)));
		assertSame("the 1 s refresh keeps the open dropdown", family, named(v, CurrentSessionView.PEST_FAMILY_NAME));
		family.firePopupMenuCanceled();
		family.firePopupMenuWillBecomeInvisible();
		v.render(snap(pc("ACTIVE", STATS)));
		assertTrue("closed: rendering resumes", family != named(v, CurrentSessionView.PEST_FAMILY_NAME));
	}

	/** Lets the dropdown's deferred (invokeLater) selection run, as the real EDT would. */
	private static void flushEdt() throws Exception
	{
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
		});
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
		});
	}

	@SuppressWarnings("unchecked")
	private static void pick(Container v, String comboName, String label)
	{
		JComboBox<Object> combo = (JComboBox<Object>) named(v, comboName);
		for (int i = 0; i < combo.getItemCount(); i++)
		{
			if (label.equals(combo.getItemAt(i).toString()))
			{
				combo.setSelectedIndex(i);
				return;
			}
		}
		throw new AssertionError("no '" + label + "' in " + comboName);
	}

	@Test
	public void realDropdownSelection_changesTheGoal_andShowsOnlyItsSelectors() throws Exception
	{
		Source s = new Source();
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		pick(v, CurrentSessionView.PEST_FAMILY_NAME, "XP target");
		flushEdt();
		assertEquals(PestControlGoal.Family.XP, s.goal.getFamily());
		assertEquals(1, s.writes);
		assertTrue(has(v, CurrentSessionView.PEST_SKILL_NAME));
		assertFalse(has(v, CurrentSessionView.PEST_VOID_GOAL_NAME));

		pick(v, CurrentSessionView.PEST_SKILL_NAME, "Prayer");
		flushEdt();
		assertEquals(Skill.PRAYER, s.goal.getXpSkill());
		assertEquals("Prayer", ((JComboBox<?>) named(v, CurrentSessionView.PEST_SKILL_NAME)).getSelectedItem().toString());

		pick(v, CurrentSessionView.PEST_FAMILY_NAME, "Void equipment");
		flushEdt();
		pick(v, CurrentSessionView.PEST_VOID_GOAL_NAME, "Specific piece");
		flushEdt();
		assertEquals(PestControlGoal.VoidGoal.PIECE, s.goal.getVoidGoal());
		assertTrue(has(v, CurrentSessionView.PEST_PIECE_NAME));
		assertFalse(has(v, CurrentSessionView.PEST_STYLE_NAME));
		assertEquals("the XP choice is kept while browsing families", Skill.PRAYER, s.goal.getXpSkill());
	}

	@Test
	public void reselectingTheCurrentChoice_writesNothing() throws Exception
	{
		Source s = new Source();
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		pick(v, CurrentSessionView.PEST_FAMILY_NAME, "Void equipment");
		flushEdt();
		assertEquals(0, s.writes);
	}

	@Test
	public void selectionFromAnOpenPopup_rendersOnceItCloses() throws Exception
	{
		Source s = new Source();
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		JComboBox<?> family = (JComboBox<?>) named(v, CurrentSessionView.PEST_FAMILY_NAME);
		family.firePopupMenuWillBecomeVisible();
		pick(v, CurrentSessionView.PEST_FAMILY_NAME, "Supplies");
		family.firePopupMenuWillBecomeInvisible();
		flushEdt();
		assertEquals(PestControlGoal.Family.SUPPLIES, s.goal.getFamily());
		assertTrue("the panel shows the new goal", has(v, CurrentSessionView.PEST_PACK_NAME));
		v.render(snap(pc("ACTIVE", STATS)));
		assertTrue("normal refreshes resume", family != named(v, CurrentSessionView.PEST_FAMILY_NAME));
	}

	@Test
	public void goalStatus_isCenteredUnderTheResultGrid_forEveryKindOfStatus()
	{
		Map<Integer, Long> mace = new HashMap<>();
		mace.put(ItemID.PEST_VOID_KNIGHT_MACE, 1L);
		PestControlGoal[] goals = {
			PestControlGoal.DEFAULT.withVoidGoal(PestControlGoal.VoidGoal.PIECE).withVoidPiece(com.osrstelemetry.plugin.pestcontrol.VoidPiece.MACE),
			PestControlGoal.DEFAULT,
			PestControlGoal.DEFAULT.withFamily(PestControlGoal.Family.XP),
			PestControlGoal.DEFAULT.withFamily(PestControlGoal.Family.SUPPLIES)};
		String[] expected = {"Owned", "Need: Top", "Defence XP not known yet", "500 coal"};
		for (int i = 0; i < goals.length; i++)
		{
			Source s = new Source();
			s.goal = goals[i];
			s.balance = 0;
			s.ownership.update(VoidOwnership.Container.BANK, mace);
			CurrentSessionView v = view(s);
			v.render(snap(pc("ACTIVE", STATS)));
			JLabel status = (JLabel) named(v, CurrentSessionView.PEST_GOAL_DETAIL_NAME);
			assertTrue(status.getText(), status.getText().replaceAll("<[^>]*>", "").startsWith(expected[i]));
			assertTrue("centered: " + status.getText(), status.getText().contains("text-align:center"));
			Container goal = (Container) named(v, CurrentSessionView.PEST_GOAL_NAME);
			List<Component> order = java.util.Arrays.asList(goal.getComponents());
			assertTrue("directly below the result grid",
				order.indexOf(status.getParent()) == order.indexOf(named(v, CurrentSessionView.PEST_GOAL_GRID_NAME)) + 2);
			for (int width : WIDTHS)
			{
				// Current Session rebuilds every second, so each width is a fresh render.
				CurrentSessionView w = view(s);
				w.render(snap(pc("ACTIVE", STATS)));
				List<String> problems = SidebarClippingRegressionTest.audit(w, width);
				assertTrue(width + ": " + problems, problems.isEmpty());
				Component line = named(w, CurrentSessionView.PEST_GOAL_DETAIL_NAME);
				Component box = named(w, CurrentSessionView.PEST_GOAL_NAME);
				assertTrue(width + "px: spans the goal width (" + line.getWidth() + " of " + box.getWidth() + ")",
					line.getWidth() >= box.getWidth() - 2);
			}
		}
	}

	/**
	 * Where the status text is actually DRAWN: paint the status line exactly as
	 * Swing does (its real UI delegate, HTML view and alignment) and take the
	 * left / right of the painted glyph pixels, in the GOAL section's
	 * coordinates. Property checks (alignment flags, widths) passed while the
	 * live client drew "Owned" left of center; painted pixels cannot.
	 */
	private static double paintedTextCenterX(JLabel status, Container goal)
	{
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(Math.max(1, status.getWidth()),
			Math.max(1, status.getHeight()), java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = img.createGraphics();
		status.paint(g);
		g.dispose();
		int min = Integer.MAX_VALUE;
		int max = -1;
		for (int x = 0; x < img.getWidth(); x++)
		{
			for (int y = 0; y < img.getHeight(); y++)
			{
				if ((img.getRGB(x, y) >>> 24) != 0)
				{
					min = Math.min(min, x);
					max = Math.max(max, x);
				}
			}
		}
		assertTrue("the status text was painted", max >= 0);
		java.awt.Point origin = javax.swing.SwingUtilities.convertPoint(status, 0, 0, goal);
		return origin.x + (min + max + 1) / 2.0;
	}

	/** Size + layout passes, as validate() does in the real panel (no preferred-size re-queries afterwards). */
	private static void liveLayout(Container root, int width)
	{
		root.setSize(width, 6000);
		for (int i = 0; i < 5; i++)
		{
			layoutTree(root);
		}
	}

	private static void layoutTree(Container c)
	{
		c.doLayout();
		for (Component k : c.getComponents())
		{
			if (k instanceof Container)
			{
				layoutTree((Container) k);
			}
		}
	}

	private static double centerX(Component c, Container goal)
	{
		java.awt.Point origin = javax.swing.SwingUtilities.convertPoint(c, 0, 0, goal);
		return origin.x + c.getWidth() / 2.0;
	}

	@Test
	public void liveCase_ownedRangerHelm_statusIsPaintedCenteredUnderTheResultGrid_atEveryWidth()
	{
		// Live 2026-10-02: Void equipment / Specific piece / Void ranger helm, owned; POINTS LEFT 0, ETA Ready.
		Map<Integer, Long> bank = new HashMap<>();
		bank.put(ItemID.LEAGUE_3_VOID_RANGE_HELM, 1L);
		String[] statuses = {"Owned", "Need: Top", "Defence XP not known yet", "500 coal"};
		PestControlGoal[] goals = {
			PestControlGoal.DEFAULT.withVoidGoal(PestControlGoal.VoidGoal.PIECE)
				.withVoidPiece(com.osrstelemetry.plugin.pestcontrol.VoidPiece.RANGER_HELM),
			PestControlGoal.DEFAULT.withVoidStyle(VoidStyle.RANGED),
			PestControlGoal.DEFAULT.withFamily(PestControlGoal.Family.XP),
			PestControlGoal.DEFAULT.withFamily(PestControlGoal.Family.SUPPLIES)};
		for (int i = 0; i < goals.length; i++)
		{
			for (int width : WIDTHS)
			{
				Source s = new Source();
				s.goal = goals[i];
				s.balance = 0;
				s.ownership.update(VoidOwnership.Container.BANK, bank);
				CurrentSessionView v = view(s);
				v.render(snap(pc("ACTIVE", STATS)));
				// Exactly what the live panel does each second: a fresh render, then
				// layout passes only -- nothing that would re-query preferred sizes
				// afterwards (the clipping audit does, so it runs only after measuring).
				liveLayout(v, width);
				Container goal = (Container) named(v, CurrentSessionView.PEST_GOAL_NAME);
				Component grid = named(v, CurrentSessionView.PEST_GOAL_GRID_NAME);
				JLabel status = (JLabel) named(v, CurrentSessionView.PEST_GOAL_DETAIL_NAME);
				assertTrue(statuses[i], status.getText().replaceAll("<[^>]*>", "").startsWith(statuses[i]));
				if (i == 0)
				{
					assertEquals("0", cell((Container) grid, CurrentSessionView.PEST_POINTS_LEFT_CELL));
					assertEquals("Ready", cell((Container) grid, CurrentSessionView.PEST_ETA_CELL));
				}
				double gridCenter = centerX(grid, goal);
				assertEquals(width + "px: the status row spans the result grid's width", grid.getWidth(), status.getParent().getWidth(), 2);
				assertEquals(width + "px: '" + statuses[i] + "' is drawn centered under the result grid",
					gridCenter, paintedTextCenterX(status, goal), 2.0);
				List<String> problems = SidebarClippingRegressionTest.audit(v, width);
				assertTrue(width + ": " + problems, problems.isEmpty());
			}
		}
	}

	@Test
	public void numberPrompt_acceptsOnlyInRangeWholeNumbers()
	{
		assertEquals(Integer.valueOf(70), CurrentSessionView.parsePestNumber(" 70 ", 2, 99));
		assertEquals(Integer.valueOf(1500), CurrentSessionView.parsePestNumber("1,500", 1, 10_000));
		assertNull(CurrentSessionView.parsePestNumber("100", 2, 99));
		assertNull(CurrentSessionView.parsePestNumber("1", 2, 99));
		assertNull(CurrentSessionView.parsePestNumber("abc", 2, 99));
		assertNull(CurrentSessionView.parsePestNumber("-5", 1, 10));
		assertNull(CurrentSessionView.parsePestNumber(null, 1, 10));
	}

	@Test
	public void ownedPieces_areInTheDetailTooltip()
	{
		Source s = new Source();
		s.goal = PestControlGoal.DEFAULT.withVoidGoal(PestControlGoal.VoidGoal.FULL_SET);
		Map<Integer, Long> inv = new HashMap<>();
		inv.put(ItemID.PEST_VOID_KNIGHT_GLOVES, 1L);
		s.ownership.update(VoidOwnership.Container.INVENTORY, inv);
		CurrentSessionView v = view(s);
		v.render(snap(pc("ACTIVE", STATS)));
		assertEquals("Already owned: Gloves", ((JLabel) named(v, CurrentSessionView.PEST_GOAL_DETAIL_NAME)).getToolTipText());
	}

	// ---- widths -----------------------------------------------------------------------

	@Test
	public void everyFamily_fitsEverySidebarWidth()
	{
		PestControlGoal[] goals = {
			PestControlGoal.DEFAULT.withVoidGoal(PestControlGoal.VoidGoal.NEXT_MISSING),
			PestControlGoal.DEFAULT.withVoidGoal(PestControlGoal.VoidGoal.PIECE),
			PestControlGoal.DEFAULT.withFamily(PestControlGoal.Family.XP).withXpSkill(Skill.HITPOINTS),
			PestControlGoal.DEFAULT.withFamily(PestControlGoal.Family.SUPPLIES).withPack(SupplyPack.HERB).withPackTarget(10_000)};
		for (PestControlGoal goal : goals)
		{
			for (int width : WIDTHS)
			{
				Source s = liveSource();
				s.goal = goal;
				s.balance = 1234;
				s.xp.put(Skill.HITPOINTS, Experience.getXpForLevel(60));
				CurrentSessionView v = view(s);
				v.render(snap(pc("ACTIVE", STATS)));
				List<String> problems = SidebarClippingRegressionTest.audit(v, width);
				assertTrue(goal.getFamily() + " @" + width + ": " + problems, problems.isEmpty());
				Component block = named(v, CurrentSessionView.PEST_BLOCK_NAME);
				// The next revalidate (every render) settles wrap-to-parent labels to the real width.
				for (Component c : all(v))
				{
					c.invalidate();
				}
				SidebarClippingRegressionTest.audit(v, width);
				StringBuilder why = new StringBuilder();
				for (Component c : all((Container) block))
				{
					if (c.getPreferredSize().width > width)
					{
						why.append(c.getClass().getSimpleName()).append('[').append(c.getName()).append("]=").append(c.getPreferredSize().width).append(" w=").append(c.getWidth()).append(' ');
						for (Component k : ((Container) c).getComponents())
						{
							why.append(" {").append(k.getClass().getSimpleName()).append(' ').append(k.getName()).append(" p=").append(k.getPreferredSize().width).append(" w=").append(k.getWidth()).append('}');
						}
					}
				}
				assertTrue(width + "px: block wants horizontal scroll (" + block.getPreferredSize().width + ") " + why,
					block.getPreferredSize().width <= width);
				for (Component c : all((Container) block))
				{
					if (c instanceof JComboBox)
					{
						assertTrue(width + "px: dropdown " + c.getName() + " too wide", c.getWidth() <= width && c.getWidth() > 0);
					}
				}
			}
		}
	}

	// ---- HISTORY ----------------------------------------------------------------------

	@Test
	public void history_sessionBlock_gamesAvgCommendationsRate_noLiveStateNoGoal()
	{
		HistoryDetailModel model = HistoryDetailModel.from(pc("FINALIZED", STATS));
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(model);
		assertEquals("Games won 4", ((JLabel) named(d, HistoryDetailView.SESSION_GAMES_NAME)).getText());
		assertEquals("Avg 2:10", ((JLabel) named(d, HistoryDetailView.SESSION_AVG_NAME)).getText());
		assertEquals("Commendations +48", ((JLabel) named(d, HistoryDetailView.SESSION_CURRENCY_PREFIX + "single")).getText());
		assertEquals("96.0/hr", ((JLabel) named(d, HistoryDetailView.SESSION_RATE_NAME)).getText());
		assertNull("no Best in History either", named(d, HistoryDetailView.SESSION_BEST_NAME));
		assertNull("no goal in History", named(d, CurrentSessionView.PEST_GOAL_NAME));
		assertNull("no live game in History", named(d, CurrentSessionView.PEST_GAME_GRID_NAME));
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(d, width);
			assertTrue(width + ": " + problems, problems.isEmpty());
		}
	}

	@Test
	public void otherActivities_neverShowThePestControlBlock()
	{
		Session gotr = GSON.fromJson(("{'sessionId':'s-g','activityIdentity':{'activityType':'STRUCTURED_ACTIVITY',"
			+ "'activityKey':'guardians_of_the_rift','displayName':'Guardians of the Rift'},'state':'ACTIVE',"
			+ "'startedAt':'2026-10-02T03:40:00Z','lastActiveAt':'2026-10-02T04:19:00Z','accumulatedActiveDurationMillis':2400000,"
			+ "'aggregates':{'xpGainedBySkill':{'RUNECRAFT':42000},'lootDrops':[]}}").replace('\'', '"'), Session.class);
		CurrentSessionView v = view(liveSource());
		v.render(snap(gotr));
		assertNull(named(v, CurrentSessionView.PEST_BLOCK_NAME));
		assertNull(named(v, CurrentSessionView.PEST_GOAL_NAME));
	}
}
