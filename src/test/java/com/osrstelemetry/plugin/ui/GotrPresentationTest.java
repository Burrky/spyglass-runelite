package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.GotrStrategy;
import com.osrstelemetry.plugin.activity.GotrAltar;
import com.osrstelemetry.plugin.activity.GotrGameState;
import com.osrstelemetry.plugin.activity.GotrRewardWallet;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

/**
 * Guardians of the Rift presentation: CURRENT GAME (native HUD values only),
 * SESSION (games, reward points earned, Avg / Best energy), RUNES CRAFTED
 * (produced items, never "Loot"), and the matching History SESSION block --
 * at every sidebar width, with nothing guessed and nothing empty shown.
 */
public class GotrPresentationTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-09-20T10:50:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};
	private static final GotrGameState LIVE = GotrGameState.of(62, 280, 310);

	private static Session gotr(String state, String extra)
	{
		return GSON.fromJson(("{'sessionId':'s-gotr','activityIdentity':{'activityType':'STRUCTURED_ACTIVITY',"
			+ "'activityKey':'guardians_of_the_rift','displayName':'Guardians of the Rift'},'state':'" + state + "',"
			+ "'startedAt':'2026-09-20T10:00:00Z','lastActiveAt':'2026-09-20T10:42:00Z','accumulatedActiveDurationMillis':2520000,"
			+ (state.equals("FINALIZED") ? "'finalizedAt':'2026-09-20T11:00:00Z'," : "")
			+ "'aggregates':{'xpGainedBySkill':{'RUNECRAFT':42000,'MINING':3100,'CRAFTING':900},'lootDrops':[]" + extra + "}}")
			.replace('\'', '"'), Session.class);
	}

	private static final String FULL_STATS =
		",'activityCompletions':{'activityKey':'guardians_of_the_rift','sessionCompletions':4,'latestAuthoritativeValue':412}"
			+ ",'activityStats':{'activityKey':'guardians_of_the_rift','currencyGained':24,"
			+ "'currencyGainedByKey':{'elemental_points':11,'catalytic_points':13},"
			+ "'scoredCompletions':3,'scoreTotal':2700,'bestScore':1020}"
			+ ",'producedItems':[{'itemId':556,'itemName':'Air rune','quantity':12400},{'itemId':564,'itemName':'Cosmic rune','quantity':418},"
			+ "{'itemId':561,'itemName':'Nature rune','quantity':296},{'itemId':560,'itemName':'Death rune','quantity':210},"
			+ "{'itemId':555,'itemName':'Water rune','quantity':830},{'itemId':565,'itemName':'Blood rune','quantity':120}]";

	private static CurrentSessionSnapshot snap(Session s)
	{
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s), NOW);
	}

	private static CurrentSessionView view(GotrGameState game)
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		v.setGotrGameSupplier(() -> game);
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

	private static String text(Container root, String name)
	{
		Component c = named(root, name);
		return c == null ? null : UiText.visible(c);
	}

	private static int count(Container root, Class<?> type)
	{
		int n = 0;
		for (Component c : all(root))
		{
			if (type.isInstance(c))
			{
				n++;
			}
		}
		return n;
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

	@Test
	public void currentGame_showsGuardianPowerProminently_andTheEnergyRows()
	{
		CurrentSessionView v = view(LIVE);
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertEquals("62%", text(v, CurrentSessionView.GOTR_POWER_NAME));
		assertEquals("280", text(v, CurrentSessionView.GOTR_ENERGY_PREFIX + "Elemental"));
		assertEquals("310", text(v, CurrentSessionView.GOTR_ENERGY_PREFIX + "Catalytic"));
		assertEquals("590", text(v, CurrentSessionView.GOTR_ENERGY_PREFIX + "Total"));
	}

	@Test
	public void currentGame_unknownFieldsAreOmitted_noGameNoSection()
	{
		CurrentSessionView v = view(GotrGameState.of(null, 120, null));
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertNull(named(v, CurrentSessionView.GOTR_POWER_NAME));
		assertEquals("120", text(v, CurrentSessionView.GOTR_ENERGY_PREFIX + "Elemental"));
		assertNull(named(v, CurrentSessionView.GOTR_ENERGY_PREFIX + "Total"));

		CurrentSessionView none = view(null);
		none.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertNull(named(none, CurrentSessionView.GOTR_POWER_NAME));
		assertNull(named(none, CurrentSessionView.GOTR_ENERGY_PREFIX + "Elemental"));

		CurrentSessionView suspended = view(LIVE);
		suspended.render(snap(gotr("SUSPENDED", FULL_STATS)));
		assertNull("live game state only while the session is active", named(suspended, CurrentSessionView.GOTR_POWER_NAME));
	}

	@Test
	public void session_gamesRate_pointsEarnedPerCurrency_avgBestEnergy()
	{
		CurrentSessionView v = view(null);
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertNotNull("SESSION is a 3 x 2 grid", named(v, CurrentSessionView.GOTR_SESSION_GRID_NAME));
		assertEquals("4", cell(v, CurrentSessionView.GOTR_GAMES_CELL));
		CurrentSessionSnapshot s = snap(gotr("ACTIVE", FULL_STATS));
		assertEquals("active time only, the shared rate rules",
			String.format(java.util.Locale.ROOT, "%.1f", CompletionMetricText.perHour(4, s.getActiveDurationMillis())),
			cell(v, CurrentSessionView.GOTR_GAMES_PER_HOUR_CELL));
		assertEquals("+11", cell(v, CurrentSessionView.GOTR_ELEMENTAL_CELL));
		assertEquals("+13", cell(v, CurrentSessionView.GOTR_CATALYTIC_CELL));
		assertEquals("900", cell(v, CurrentSessionView.GOTR_AVG_CELL));
		assertEquals("1,020", cell(v, CurrentSessionView.GOTR_BEST_CELL));
		assertNull("no separate one-line SESSION metric any more", named(v, CurrentSessionView.COMPLETION_METRIC_NAME));
	}

	private static String cell(Container root, String caption)
	{
		Component c = named(root, SlayerStatGrid.VALUE_PREFIX + caption);
		return c == null ? null : ((JLabel) c).getText();
	}


	@Test
	public void session_withoutPointsOrTrustedGames_showsOnlyGames()
	{
		CurrentSessionView v = view(null);
		v.render(snap(gotr("ACTIVE", ",'activityCompletions':{'activityKey':'guardians_of_the_rift','sessionCompletions':0}")));
		assertEquals("0", cell(v, CurrentSessionView.GOTR_GAMES_CELL));
		assertEquals("unknown is a quiet dash, never a guessed 0", "-", cell(v, CurrentSessionView.GOTR_ELEMENTAL_CELL));
		assertEquals("-", cell(v, CurrentSessionView.GOTR_AVG_CELL));
		assertEquals("-", cell(v, CurrentSessionView.GOTR_GAMES_PER_HOUR_CELL));
		assertNull("no empty RUNES CRAFTED section", named(v, CurrentSessionView.RUNES_CRAFTED_NAME));
	}


	@Test
	public void runesCrafted_header_total_andOneGridCellPerRune_neverLabelledLoot()
	{
		CurrentSessionView v = view(LIVE);
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		JLabel header = (JLabel) named(v, CurrentSessionView.RUNES_CRAFTED_NAME);
		assertNotNull(header);
		assertEquals("RUNES CRAFTED", header.getText());
		assertEquals("14,274 total", text(v, CurrentSessionView.RUNES_CRAFTED_TOTAL_NAME));
		assertEquals("1.2M", ProducedItemGrid.compactTotal(1_234_567));
		assertEquals("999,999", ProducedItemGrid.compactTotal(999_999));
		Container grid = (Container) named(v, ProducedItemGrid.GRID_NAME);
		assertNotNull(grid);
		assertEquals(6, grid.getComponentCount());
		assertEquals(6, count(grid, LootGridCell.class));
		String tip = ((JComponent) grid.getComponent(0)).getToolTipText();
		assertTrue(tip, tip.contains("Air rune: 12,400"));
		for (Component c : all(v))
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null)
			{
				String t = ((JLabel) c).getText().toUpperCase();
				assertTrue("crafted runes are never shown as loot: " + t, !(t.contains("LOOT") && t.contains("RUNE")));
			}
		}
	}

	@Test
	public void runeTooltip_isExact_evenWhenTheOverlayIsCompact()
	{
		assertEquals("<html>Death rune: 1,234,567</html>",
			ProducedItemGrid.tooltip(new CurrentSessionSnapshot.ProducedItemView(560, "Death rune", 1_234_567)));
		assertEquals("<html>Item 999: 5</html>", ProducedItemGrid.tooltip(new CurrentSessionSnapshot.ProducedItemView(999, null, 5)));
	}

	@Test
	public void narrowWidths_noClipNoOverflow_includingTheRuneGrid()
	{
		CurrentSessionView v = view(LIVE);
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		Component block = named(v, CurrentSessionView.GOTR_BLOCK_NAME);
		assertNotNull(block);
		for (int width : WIDTHS)
		{
			v.setSize(width, 4000);
			for (int i = 0; i < 3; i++)
			{
				layout(v);
			}
			Component grid = named(v, ProducedItemGrid.GRID_NAME);
			for (Component c : all((Container) block))
			{
				// The rune grid is a GridLayout like the accepted Loot grid: its cells
				// shrink to the real sidebar width (RuneLite's host never scrolls
				// horizontally), so only its actual bounds must fit -- checked below.
				boolean inGrid = c == grid || SwingUtilities.isDescendingFrom(c, grid);
				if (!inGrid && c instanceof JComponent && !(c instanceof LootGridCell))
				{
					assertTrue(width + "px: " + c.getName() + " wants horizontal scroll (" + c.getPreferredSize().width + ")",
						c.getParent() == grid || c.getPreferredSize().width <= width);
				}
				Rectangle r = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), v);
				assertTrue(width + "px: " + c.getName() + " overflows (" + (r.x + r.width) + ")", r.x >= 0 && r.x + r.width <= width + 1);
				if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty())
				{
					assertTrue(width + "px: '" + ((JLabel) c).getText() + "' clipped", c.getWidth() >= c.getPreferredSize().width);
				}
			}
			assertTrue(width + "px: compact block height (" + block.getHeight() + ")", block.getHeight() < 420);
		}
	}

	// ---- HISTORY ---------------------------------------------------------

	@Test
	public void history_sessionBlock_pointsAvgBest_andRuneGrid()
	{
		HistoryDetailModel model = HistoryDetailModel.from(gotr("FINALIZED", FULL_STATS));
		assertTrue(model.hasSessionBlock());
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(model);
		assertNotNull(named(d, HistoryDetailView.SESSION_BLOCK_NAME));
		assertNull("no inline header metric", named(d, HistoryDetailView.COMPLETION_METRIC_NAME));
		assertNull(named(d, HistoryDetailView.ACTIVITY_STATS_NAME));
		assertEquals("Games 4", text(d, HistoryDetailView.SESSION_GAMES_NAME));
		assertEquals("5.7/hr", text(d, HistoryDetailView.SESSION_RATE_NAME));
		assertEquals("Elemental +11", text(d, HistoryDetailView.SESSION_CURRENCY_PREFIX + "elemental_points"));
		assertEquals("Catalytic +13", text(d, HistoryDetailView.SESSION_CURRENCY_PREFIX + "catalytic_points"));
		assertEquals("Avg 900", text(d, HistoryDetailView.SESSION_AVG_NAME));
		assertEquals("Best 1,020", text(d, HistoryDetailView.SESSION_BEST_NAME));
		assertEquals("RUNES CRAFTED", text(d, HistoryDetailView.PRODUCED_ITEMS_NAME));
		assertEquals("14,274 total", text(d, HistoryDetailView.PRODUCED_ITEMS_TOTAL_NAME));
		assertEquals(6, ((Container) named(d, ProducedItemGrid.GRID_NAME)).getComponentCount());
	}

	@Test
	public void history_oldGotrSession_loads_withGamesOnly_noRunesSection()
	{
		HistoryDetailModel model = HistoryDetailModel.from(gotr("FINALIZED",
			",'activityCompletions':{'activityKey':'guardians_of_the_rift','sessionCompletions':2}"));
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(model);
		assertEquals("Games 2", text(d, HistoryDetailView.SESSION_GAMES_NAME));
		assertNull(named(d, HistoryDetailView.SESSION_CURRENCY_PREFIX + "elemental_points"));
		assertNull(named(d, HistoryDetailView.SESSION_AVG_NAME));
		assertNull(named(d, HistoryDetailView.PRODUCED_ITEMS_NAME));
	}

	@Test
	public void history_barrowsKeepsItsAcceptedHeaderPresentation()
	{
		Session barrows = GSON.fromJson(("{'sessionId':'s-b','activityIdentity':{'activityType':'STRUCTURED_ACTIVITY',"
			+ "'activityKey':'barrows','displayName':'Barrows'},'state':'FINALIZED','startedAt':'2026-09-20T10:00:00Z',"
			+ "'finalizedAt':'2026-09-20T11:00:00Z','accumulatedActiveDurationMillis':2520000,"
			+ "'aggregates':{'xpGainedBySkill':{'MAGIC':9000},'lootDrops':[],"
			+ "'activityCompletions':{'activityKey':'barrows','sessionCompletions':7}}}").replace('\'', '"'), Session.class);
		HistoryDetailModel model = HistoryDetailModel.from(barrows);
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(model);
		assertNull(named(d, HistoryDetailView.SESSION_BLOCK_NAME));
		assertTrue(text(d, HistoryDetailView.COMPLETION_METRIC_NAME).startsWith("Chests: 7"));
	}

	@Test
	public void history_narrowWidths_sessionBlockAndGrid_fit()
	{
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(gotr("FINALIZED", FULL_STATS)));
		Component block = named(d, HistoryDetailView.SESSION_BLOCK_NAME);
		Component grid = named(d, ProducedItemGrid.GRID_NAME);
		for (int width : WIDTHS)
		{
			d.setSize(width, 6000);
			for (int i = 0; i < 3; i++)
			{
				layout(d);
			}
			for (Component c : new Component[]{block, grid})
			{
				Rectangle r = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), d);
				assertTrue(width + "px: " + c.getName() + " overflows (" + (r.x + r.width) + ")", r.x >= 0 && r.x + r.width <= width + 1);
			}
			assertTrue(width + "px: session block wants horizontal scroll (" + block.getPreferredSize().width + ")",
				block.getPreferredSize().width <= width);
			// Grid cells shrink to the real width (GridLayout, like the Loot grid): five columns that fit.
			Component fifth = ((Container) grid).getComponent(4);
			assertTrue(width + "px: grid cells fit", fifth.getX() + fifth.getWidth() <= grid.getWidth() + 1);
			for (Component c : all((Container) block))
			{
				if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty())
				{
					assertTrue(width + "px: '" + ((JLabel) c).getText() + "' clipped", c.getWidth() >= c.getPreferredSize().width);
				}
			}
			JLabel produced = (JLabel) named(d, HistoryDetailView.PRODUCED_ITEMS_NAME);
			assertTrue(width + "px: '" + produced.getText() + "' clipped (" + produced.getPreferredSize().width + ")",
				produced.getWidth() >= produced.getPreferredSize().width);
		}
	}

	// ---- RIFTS / STRATEGY / REWARDS (final GotR pass) ------------------------

	private static final GotrGameState RIFTS = GotrGameState.of(62, 280, 310,
		com.osrstelemetry.plugin.activity.GotrAltar.EARTH, com.osrstelemetry.plugin.activity.GotrAltar.DEATH, true, true);

	private static CurrentSessionView view(GotrGameState game, GotrRewardWallet wallet)
	{
		CurrentSessionView v = view(game);
		v.setGotrWalletSupplier(() -> wallet);
		return v;
	}

	private static String visible(Container root, String name)
	{
		Component c = named(root, name);
		return c == null ? null : UiText.visible(c);
	}

	@Test
	public void strategyGrid_pointsThenXp_marksAndReasons_sessionValuesUnchanged()
	{
		CurrentSessionView v = view(RIFTS, GotrRewardWallet.of(31, 27));
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertNotNull(named(v, CurrentSessionView.GOTR_STRATEGY_POINTS_NAME + ":selected"));
		assertNotNull(named(v, CurrentSessionView.GOTR_STRATEGY_GRID_NAME));
		assertNotNull("Death (overcharged cells) is the points pick", named(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "DEATH:best"));
		assertTrue(visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "DEATH:mark").endsWith("BEST"));
		assertEquals("Best cells", visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "DEATH:reason"));
		assertNotNull(named(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "EARTH"));
		assertEquals("Elemental", visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "EARTH:reason"));
		assertNull("open rifts need no access label", named(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "EARTH:access"));
		assertNull("no duplicated advice prose when a cell carries the mark", named(v, CurrentSessionView.GOTR_ADVICE_NAME));
		String games = cell(v, CurrentSessionView.GOTR_GAMES_CELL);

		v.selectGotrStrategy(GotrStrategy.XP);
		assertNotNull(named(v, CurrentSessionView.GOTR_STRATEGY_XP_NAME + ":selected"));
		assertNotNull(named(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "DEATH:best"));
		assertEquals("+3.5 / ess", visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "DEATH:reason"));
		assertEquals("6.5 / ess", visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "EARTH:reason"));
		assertEquals("interpretation only: session data untouched", games, cell(v, CurrentSessionView.GOTR_GAMES_CELL));
		assertEquals("+11", cell(v, CurrentSessionView.GOTR_ELEMENTAL_CELL));
	}

	@Test
	public void pointsBalance_focusesTheSide_notAnAltar()
	{
		CurrentSessionView v = view(GotrGameState.of(62, 200, 200, GotrAltar.AIR, GotrAltar.NATURE, true, true,
			java.util.Collections.singletonMap(GotrAltar.CHAOS, true)), GotrRewardWallet.of(31, 27));
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertNotNull(named(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "NATURE:focus"));
		assertNotNull("a talisman altar of the focused side is marked too", named(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "CHAOS:focus"));
		assertEquals("Talisman", visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "CHAOS:access"));
		assertEquals("4 behind", visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "NATURE:reason"));
		assertTrue(visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "NATURE:mark").endsWith("FOCUS"));
		assertNotNull(named(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "AIR"));
		assertEquals(3, ((SlayerStatGrid) named(v, CurrentSessionView.GOTR_STRATEGY_GRID_NAME)).getComponentCount());
	}

	@Test
	public void talisman_expandsTheCandidates_andCanWinOnlyOnMerit()
	{
		// XP: a held Blood talisman beats both open rifts.
		CurrentSessionView v = view(GotrGameState.of(62, 100, 100, GotrAltar.EARTH, GotrAltar.MIND, true, true,
			java.util.Collections.singletonMap(GotrAltar.BLOOD, true)), null);
		v.selectGotrStrategy(GotrStrategy.XP);
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertNotNull(named(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "BLOOD:best"));
		assertEquals("Talisman", visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "BLOOD:access"));
		assertTrue(((JLabel) named(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "BLOOD:best")).getToolTipText().contains("portal talisman"));

		// A talisman the player cannot use is shown locked, never recommended.
		CurrentSessionView locked = view(GotrGameState.of(62, 100, 100, GotrAltar.EARTH, GotrAltar.MIND, true, true,
			java.util.Collections.singletonMap(GotrAltar.BLOOD, false)), null);
		locked.selectGotrStrategy(GotrStrategy.XP);
		locked.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertNotNull(named(locked, CurrentSessionView.GOTR_ALTAR_PREFIX + "BLOOD:locked"));
		assertEquals("Locked", visible(locked, CurrentSessionView.GOTR_ALTAR_PREFIX + "BLOOD:reason"));
		assertNotNull(named(locked, CurrentSessionView.GOTR_ALTAR_PREFIX + "EARTH:best"));
	}

	@Test
	public void strategyChoice_isRemembered_throughItsWriter_andReadBack()
	{
		java.util.concurrent.atomic.AtomicReference<GotrStrategy> stored = new java.util.concurrent.atomic.AtomicReference<>();
		CurrentSessionView v = view(RIFTS, null);
		v.setGotrStrategyReader(stored::get);
		v.setGotrStrategyWriter(stored::set);
		assertEquals("default Points", GotrStrategy.POINTS, v.effectiveGotrStrategy());
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		v.selectGotrStrategy(GotrStrategy.XP);
		assertEquals(GotrStrategy.XP, stored.get());
		CurrentSessionView reopened = view(RIFTS, null);
		reopened.setGotrStrategyReader(stored::get);
		assertEquals(GotrStrategy.XP, reopened.effectiveGotrStrategy());
	}

	@Test
	public void unusableAltarIsMuted_noWinnerWithoutEvidence_noGridWithoutRifts()
	{
		CurrentSessionView v = view(GotrGameState.of(62, 280, 310, GotrAltar.EARTH, GotrAltar.BLOOD, true, false), null);
		v.selectGotrStrategy(GotrStrategy.XP);
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		JLabel blood = (JLabel) named(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "BLOOD:locked");
		assertNotNull(blood);
		assertTrue(blood.getToolTipText().contains("needs Runecraft 77 and Sins of the Father"));
		assertNotNull(named(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "EARTH:best"));

		CurrentSessionView unknown = view(GotrGameState.of(62, 280, 310, GotrAltar.EARTH, GotrAltar.BLOOD, null, null), null);
		unknown.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertNotNull("the altars are still shown", named(unknown, CurrentSessionView.GOTR_STRATEGY_GRID_NAME));
		assertNull("but no advice without evidence", named(unknown, CurrentSessionView.GOTR_ADVICE_NAME));
		for (Component c : all(unknown))
		{
			assertTrue(c.getName() == null || !(c.getName().endsWith(":best") || c.getName().endsWith(":focus")));
		}

		CurrentSessionView noRifts = view(LIVE, null);
		noRifts.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertNull("no open-rift data: no strategy grid, no selector", named(noRifts, CurrentSessionView.GOTR_STRATEGY_GRID_NAME));
		assertNull(named(noRifts, CurrentSessionView.GOTR_STRATEGY_POINTS_NAME + ":selected"));
	}

	@Test
	public void eitherAndCapped_oneQuietLine_noMarks()
	{
		CurrentSessionView either = view(GotrGameState.of(40, 200, 200, GotrAltar.WATER, GotrAltar.CHAOS, true, true), null);
		either.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertEquals("Either side" + FactLine.SEPARATOR + "Points even", visible(either, CurrentSessionView.GOTR_ADVICE_NAME));
		CurrentSessionView capped = view(GotrGameState.of(40, 700, 600, GotrAltar.WATER, GotrAltar.CHAOS, true, true), null);
		capped.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertEquals("No more points this game", visible(capped, CurrentSessionView.GOTR_ADVICE_NAME));
	}

	@Test
	public void sectionOrder_currentGame_strategy_session_runes_xp_rewards_loot()
	{
		String loot = ",'lootDrops':[{'sourceName':'Guardians of the Rift','observedAt':'2026-09-20T10:30:00Z','items':[{'itemId':555,'itemName':'Water rune','quantity':474}]}]";
		CurrentSessionView v = view(RIFTS, GotrRewardWallet.of(31, 27));
		v.render(snap(gotr("ACTIVE", FULL_STATS.replace(",'lootDrops':[]", "") + loot)));
		List<Component> order = all(v);
		int game = indexOfText(order, "CURRENT GAME");
		int strategy = order.indexOf(named(v, CurrentSessionView.GOTR_STRATEGY_GRID_NAME));
		int session = order.indexOf(named(v, CurrentSessionView.GOTR_SESSION_GRID_NAME));
		int rewards = order.indexOf(named(v, CurrentSessionView.GOTR_REWARDS_NAME));
		int lootAt = indexOfText(order, "LOOT");
		assertTrue(game >= 0 && game < strategy && strategy < session && session < rewards && rewards < lootAt);
	}

	private static int indexOfText(List<Component> order, String prefix)
	{
		for (int i = 0; i < order.size(); i++)
		{
			if (order.get(i) instanceof JLabel && ((JLabel) order.get(i)).getText() != null
				&& UiText.visible(order.get(i)).toUpperCase(java.util.Locale.ROOT).startsWith(prefix))
			{
				return i;
			}
		}
		return -1;
	}

	@Test
	public void rewards_2x2Grid_searchesBalanceWallet()
	{
		CurrentSessionView v = view(RIFTS, GotrRewardWallet.of(31, 27));
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		SlayerStatGrid grid = (SlayerStatGrid) named(v, CurrentSessionView.GOTR_REWARDS_GRID_NAME);
		assertNotNull(grid);
		assertEquals("27", cell(grid, CurrentSessionView.GOTR_SEARCHES_CELL));
		assertEquals("Catalytic -4", cell(grid, CurrentSessionView.GOTR_BALANCE_CELL));
		assertEquals("31", cell(grid, CurrentSessionView.GOTR_WALLET_ELEMENTAL_CELL));
		assertEquals("27", cell(grid, CurrentSessionView.GOTR_WALLET_CATALYTIC_CELL));
		JLabel balance = (JLabel) named(grid, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.GOTR_BALANCE_CELL);
		assertEquals(SpyglassTheme.ACCENT_AMBER, balance.getForeground());
		assertEquals(SpyglassTheme.ACCENT_GOLD, ((JLabel) named(grid, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.GOTR_SEARCHES_CELL)).getForeground());
		assertEquals("SESSION grid never shows wallet values", "+11", cell(v, CurrentSessionView.GOTR_ELEMENTAL_CELL));

		CurrentSessionView even = view(RIFTS, GotrRewardWallet.of(9, 9));
		even.render(snap(gotr("ACTIVE", FULL_STATS)));
		SlayerStatGrid evenGrid = (SlayerStatGrid) named(even, CurrentSessionView.GOTR_REWARDS_GRID_NAME);
		assertEquals("Balanced", cell(evenGrid, CurrentSessionView.GOTR_BALANCE_CELL));
		assertEquals(SpyglassTheme.ACCENT_SAGE,
			((JLabel) named(evenGrid, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.GOTR_BALANCE_CELL)).getForeground());
	}

	@Test
	public void rewards_unknownWallet_oneQuietLine_noZeroes_nothingWhenSuspended()
	{
		CurrentSessionView v = view(RIFTS, null);
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertEquals("Reward balance unknown", visible(v, CurrentSessionView.GOTR_WALLET_UNKNOWN_NAME));
		assertNull(named(v, CurrentSessionView.GOTR_REWARDS_GRID_NAME));
		CurrentSessionView suspended = view(RIFTS, null);
		suspended.render(snap(gotr("SUSPENDED", FULL_STATS)));
		assertNull("no nagging while away", named(suspended, CurrentSessionView.GOTR_REWARDS_NAME));
	}

	@Test
	public void rewardsGrid_2x2Normally_1x4AtTheNarrowest()
	{
		CurrentSessionView v = view(RIFTS, GotrRewardWallet.of(1234, 1000));
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		SlayerStatGrid grid = (SlayerStatGrid) named(v, CurrentSessionView.GOTR_REWARDS_GRID_NAME);
		for (int width : WIDTHS)
		{
			assertTrue(SidebarClippingRegressionTest.audit(v, width).isEmpty());
			assertTrue(width + "px: " + grid.columns(), grid.columns() == (width <= 150 ? grid.columns() : 2));
			assertTrue(grid.columns() >= 1 && grid.columns() <= 2);
		}
	}

	@Test
	public void wholeGotrView_everyWidth_noClipNoOverflow_withStrategyRewardsAndGrids()
	{
		String loot = ",'lootDrops':[{'sourceName':'Guardians of the Rift','observedAt':'2026-09-20T10:30:00Z','items':[{'itemId':555,'itemName':'Water rune','quantity':474}]}]";
		java.util.Map<GotrAltar, Boolean> talismans = new java.util.EnumMap<>(GotrAltar.class);
		talismans.put(GotrAltar.BLOOD, true);
		talismans.put(GotrAltar.COSMIC, false);
		talismans.put(GotrAltar.NATURE, true);
		talismans.put(GotrAltar.FIRE, true);
		GotrGameState[] games = {RIFTS, GotrGameState.of(62, 280, 310, GotrAltar.EARTH, GotrAltar.DEATH, true, true, talismans)};
		for (GotrGameState g : games)
		{
			for (GotrStrategy strategy : GotrStrategy.values())
			{
				CurrentSessionView v = view(g, GotrRewardWallet.of(1234, 27));
				v.selectGotrStrategy(strategy);
				v.render(snap(gotr("ACTIVE", FULL_STATS.replace(",'lootDrops':[]", "") + loot)));
				for (int width : WIDTHS)
				{
					List<String> problems = SidebarClippingRegressionTest.audit(v, width);
					assertTrue(strategy + " " + g.getCandidates().size() + " altars @" + width + ": " + problems, problems.isEmpty());
				}
			}
		}
	}

	@Test
	public void everyAdviceWording_fitsTheNarrowestSidebar()
	{
		GotrAltar e = GotrAltar.WATER;
		Object[][] cases = {
			{GotrStrategy.POINTS, GotrGameState.of(40, 400, 100, e, GotrAltar.CHAOS, true, true), null},
			{GotrStrategy.POINTS, GotrGameState.of(40, 200, 200, e, GotrAltar.CHAOS, true, true), null},
			{GotrStrategy.POINTS, GotrGameState.of(40, 700, 600, e, GotrAltar.CHAOS, true, true), null},
			{GotrStrategy.POINTS, GotrGameState.of(40, 100, 100, e, GotrAltar.CHAOS, true, true), GotrRewardWallet.of(1234, 1000)},
			{GotrStrategy.XP, GotrGameState.of(40, 1, 1, e, GotrAltar.BLOOD, true, false), null},
			{GotrStrategy.XP, GotrGameState.of(40, 1, 1, GotrAltar.FIRE, GotrAltar.BODY, true, true), null},
		};
		for (Object[] c : cases)
		{
			CurrentSessionView v = view((GotrGameState) c[1], (GotrRewardWallet) c[2]);
			v.selectGotrStrategy((GotrStrategy) c[0]);
			v.render(snap(gotr("ACTIVE", FULL_STATS)));
			for (int width : WIDTHS)
			{
				List<String> problems = SidebarClippingRegressionTest.audit(v, width);
				assertTrue(c[1] + " @" + width + ": " + problems, problems.isEmpty());
			}
		}
	}

	@Test
	public void strategyGrid_reflows_twoAltarsSideBySide_manyAltarsWrap()
	{
		CurrentSessionView v = view(RIFTS, null);
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		SlayerStatGrid grid = (SlayerStatGrid) named(v, CurrentSessionView.GOTR_STRATEGY_GRID_NAME);
		for (int width : WIDTHS)
		{
			assertTrue(SidebarClippingRegressionTest.audit(v, width).isEmpty());
			assertEquals(width + "px", 2, grid.columns());
		}
	}

	@Test
	public void sessionGrid_3x2AtNormalWidth_reflowsTo2x3AtTheNarrowest()
	{
		CurrentSessionView v = view(RIFTS, GotrRewardWallet.of(31, 27));
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		SlayerStatGrid grid = (SlayerStatGrid) named(v, CurrentSessionView.GOTR_SESSION_GRID_NAME);
		int[] expected = {2, 3, 3, 3};
		for (int i = 0; i < WIDTHS.length; i++)
		{
			assertTrue(SidebarClippingRegressionTest.audit(v, WIDTHS[i]).isEmpty());
			assertEquals(WIDTHS[i] + "px", expected[i], grid.columns());
		}
	}

	@Test
	public void xpStrategyCells_compactPerEssence_oneDecimal_neverReadsAsXpLess()
	{
		java.util.Map<GotrAltar, Boolean> talismans = new java.util.EnumMap<>(GotrAltar.class);
		talismans.put(GotrAltar.BLOOD, true);
		talismans.put(GotrAltar.LAW, true);
		CurrentSessionView v = view(GotrGameState.of(62, 100, 100, GotrAltar.AIR, GotrAltar.MIND, true, true, talismans), null);
		v.selectGotrStrategy(GotrStrategy.XP);
		v.render(snap(gotr("ACTIVE", FULL_STATS)));
		assertEquals("5.0 / ess", visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "AIR:reason"));
		assertEquals("5.5 / ess", visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "MIND:reason"));
		assertEquals("9.5 / ess", visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "LAW:reason"));
		assertEquals("+1.0 / ess", visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "BLOOD:reason"));
		assertTrue(visible(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "BLOOD:mark").endsWith("BEST"));
		assertTrue(((JLabel) named(v, CurrentSessionView.GOTR_ALTAR_PREFIX + "AIR:reason")).getToolTipText().contains("5.0 XP per essence"));
		for (Component c : all(v))
		{
			if (c.getName() != null && c.getName().startsWith(CurrentSessionView.GOTR_ALTAR_PREFIX) && c.getName().endsWith(":reason"))
			{
				String text = visible(v, c.getName());
				assertTrue(text, text.matches("\\+?\\d+\\.\\d / ess"));
				assertTrue(text, !text.contains("XP/") && !text.contains("/ess"));
			}
		}
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(v, width);
			assertTrue(width + ": " + problems, problems.isEmpty());
		}
	}
}
