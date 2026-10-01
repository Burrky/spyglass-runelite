package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Guardians of the Rift session metrics through the real coordinator: games,
 * per-currency reward points earned, Avg / Best total energy of fully
 * observed won games, and RUNES CRAFTED (produced items -- never loot).
 */
public class GotrSessionMetricsTest extends StructuredActivityTestSupport
{
	private static final Instant B = Instant.parse("2026-05-02T12:00:00Z");
	private static final int AIR = 556;
	private static final int NATURE = 561;

	private static SessionSignal produced(Instant at, SessionSignal.LootDrop... items)
	{
		return SessionSignal.structuredActivityProduction(at, GOTR, Arrays.asList(items));
	}

	private static SessionSignal.LootDrop rune(int id, String name, long qty)
	{
		return new SessionSignal.LootDrop(id, name, qty);
	}

	private void wonGame(int rifts, Integer energy, boolean fromStart, Instant at)
	{
		onTick(at, gotrGame(rifts, at), SessionSignal.structuredActivityScore(at, GOTR, rifts, energy, fromStart));
	}

	private SessionAggregates aggregates()
	{
		return current().getAggregates();
	}

	@Test
	public void runesCrafted_accumulatePerRune_exactQuantities_firstProducedOrder()
	{
		lockedGotr(B);
		onTick(B.plusSeconds(60), produced(B.plusSeconds(60), rune(AIR, "Air rune", 300)));
		onTick(B.plusSeconds(90), produced(B.plusSeconds(90), rune(NATURE, "Nature rune", 46)));
		onTick(B.plusSeconds(120), produced(B.plusSeconds(120), rune(AIR, "Air rune", 360), rune(NATURE, "Nature rune", 9)));
		List<SessionAggregates.ProducedItemAggregate> p = aggregates().getProducedItems();
		assertEquals(2, p.size());
		assertEquals(Integer.valueOf(AIR), p.get(0).getItemId());
		assertEquals(660L, p.get(0).getQuantity());
		assertEquals(55L, p.get(1).getQuantity());
		CurrentSessionSnapshot ui = ui(B.plusSeconds(130));
		assertEquals(2, ui.getProducedItems().size());
		assertEquals(715L, ui.getProducedItemTotal());
	}

	@Test
	public void runesCrafted_areNeverLoot_andNeverLifecycleEvidence()
	{
		lockedGotr(B);
		String id = current().getSessionId();
		onTick(B.plusSeconds(60), produced(B.plusSeconds(60), rune(AIR, "Air rune", 300)));
		assertTrue("no loot group for crafted runes", aggregates().getLootDrops() == null || aggregates().getLootDrops().isEmpty());
		assertEquals(id, current().getSessionId());
	}

	@Test
	public void runesCrafted_onlyEverLandOnTheGotrSession()
	{
		onTick(B, xpAt("WOODCUTTING", 200, B));
		assertFalse(isGotr(current()));
		onTick(B.plusSeconds(10), produced(B.plusSeconds(10), rune(AIR, "Air rune", 300)));
		assertNull(current().getAggregates().getProducedItems());
	}

	@Test
	public void runesCrafted_surviveRoundsAndRestart_oldSessionsLoadWithNone() throws Exception
	{
		Instant base = Instant.now().minusSeconds(600);
		lockedGotr(base);
		onTick(base.plusSeconds(60), produced(base.plusSeconds(60), rune(AIR, "Air rune", 300)));
		wonGame(10, 900, true, base.plusSeconds(200));
		onTick(base.plusSeconds(260), produced(base.plusSeconds(260), rune(AIR, "Air rune", 200)));
		assertEquals("rounds never reset production", 500L, aggregates().getProducedItems().get(0).getQuantity());
		String id = current().getSessionId();
		coordinator.finalizeForClientShutdownBlocking();
		restartPlugin();
		assertEquals(id, current().getSessionId());
		assertEquals(500L, aggregates().getProducedItems().get(0).getQuantity());

		SessionAggregates legacy = GSON.fromJson("{'xpGainedBySkill':{'RUNECRAFT':100}}", SessionAggregates.class);
		assertNull(legacy.getProducedItems());
		assertTrue(CurrentSessionSnapshot.buildProducedItemViews(legacy.getProducedItems()).isEmpty());
	}

	@Test
	public void rewardPoints_trackedPerCurrency_totalIsTheirSum()
	{
		lockedGotr(B);
		onTick(B.plusSeconds(200), SessionSignal.structuredActivityCurrency(B.plusSeconds(200), GOTR, "elemental_points", 7));
		onTick(B.plusSeconds(201), SessionSignal.structuredActivityCurrency(B.plusSeconds(201), GOTR, "catalytic_points", 8));
		onTick(B.plusSeconds(600), SessionSignal.structuredActivityCurrency(B.plusSeconds(600), GOTR, "elemental_points", 4));
		SessionAggregates.ActivitySessionStats s = aggregates().getActivityStats();
		assertEquals(Long.valueOf(11), s.getCurrencyGainedByKey().get("elemental_points"));
		assertEquals(Long.valueOf(8), s.getCurrencyGainedByKey().get("catalytic_points"));
		assertEquals(19L, s.getCurrencyGained());
		CurrentSessionSnapshot ui = ui(B.plusSeconds(610));
		assertEquals(11L, ui.getActivityCurrencyGained("elemental_points"));
		assertEquals(8L, ui.getActivityCurrencyGained("catalytic_points"));
	}

	@Test
	public void avgBest_onlyFullyObservedWins_partialStillCountsAsAGame_duplicateIgnored()
	{
		lockedGotr(B);
		wonGame(10, 900, true, B.plusSeconds(300));
		wonGame(11, 1_100, false, B.plusSeconds(700)); // re-enabled mid-game
		wonGame(12, 700, true, B.plusSeconds(1_100));
		onTick(B.plusSeconds(1_101), SessionSignal.structuredActivityScore(B.plusSeconds(1_101), GOTR, 12, 5_000, true));
		SessionAggregates.ActivitySessionStats s = aggregates().getActivityStats();
		assertEquals(2, s.getScoredCompletions());
		assertEquals(Long.valueOf(800), s.averageScore());
		assertEquals(Integer.valueOf(900), s.getBestScore());
		assertEquals(3, aggregates().getActivityCompletions().getSessionCompletions());
	}

	@Test
	public void failedGame_sendsNothing_soGamesAndAvgBestAreUntouched()
	{
		lockedGotr(B);
		wonGame(10, 900, true, B.plusSeconds(300));
		// A loss: the game prints no rifts-closed message, so the collector sends no completion and no score.
		onTick(B.plusSeconds(700), xpAt("RUNECRAFT", 200, B.plusSeconds(700)));
		assertEquals(1, aggregates().getActivityCompletions().getSessionCompletions());
		assertEquals(1, aggregates().getActivityStats().getScoredCompletions());
	}

	@Test
	public void mapper_carriesCurrencyKeyAndProducedItems()
	{
		SessionSignal c = SessionEventMapper.map(EventType.STRUCTURED_ACTIVITY_CURRENCY,
			new EventPayloads.StructuredActivityCurrency(GOTR, "catalytic_points", 8, 46), B);
		assertEquals("catalytic_points", c.getActivityCurrencyKey());
		assertEquals(Long.valueOf(8), c.getActivityAmount());

		SessionSignal p = SessionEventMapper.map(EventType.STRUCTURED_ACTIVITY_PRODUCTION,
			new EventPayloads.StructuredActivityProduction(GOTR, Arrays.asList(
				new EventPayloads.ProducedItem(AIR, "Air rune", 300), new EventPayloads.ProducedItem(NATURE, null, 0))), B);
		assertEquals(SignalKind.STRUCTURED_ACTIVITY_PRODUCTION, p.getKind());
		assertEquals(1, p.getProducedItems().size());
		assertNull("produced items are never exposed as a currency", p.getActivityCurrencyKey());
		assertNull(SessionEventMapper.map(EventType.STRUCTURED_ACTIVITY_PRODUCTION,
			new EventPayloads.StructuredActivityProduction(GOTR, Collections.<EventPayloads.ProducedItem>emptyList()), B));
	}

	@Test
	public void tempoross_singleCurrency_keepsWorkingWithoutAKey()
	{
		SessionSignal c = SessionSignal.structuredActivityCurrency(B, "tempoross", 5);
		assertNull(c.getActivityCurrencyKey());
		assertNull(c.getProducedItems());
	}

	/**
	 * LIVE REGRESSION (2026-10-01): exactly the events the collector now emits
	 * for the won live game -- score, completion (rifts 87), the game's own
	 * point award -- give Games 1 and Avg / Best, with no reward claim; the
	 * reward pulls that followed are loot and never another game.
	 */
	@Test
	public void liveWonGame_gamesBecomes1_avgBestUpdate_rewardPullsNeverCountAsGames()
	{
		lockedGotr(B);
		Instant end = B.plusSeconds(500);
		onTick(end,
			SessionSignal.structuredActivityScore(end, GOTR, 87, 490, true),
			SessionSignal.structuredActivityCompletion(end, GOTR, 87),
			SessionSignal.structuredActivityCurrency(end, GOTR, "elemental_points", 2),
			SessionSignal.structuredActivityCurrency(end, GOTR, "catalytic_points", 3));
		CurrentSessionSnapshot ui = ui(end.plusSeconds(5));
		assertEquals("Games: 1", 1, ui.getCompletionCount());
		assertEquals(Long.valueOf(490), ui.getAverageScore());
		assertEquals(Integer.valueOf(490), ui.getBestScore());
		assertEquals(2L, ui.getActivityCurrencyGained("elemental_points"));
		assertEquals(3L, ui.getActivityCurrencyGained("catalytic_points"));

		Instant pull = end.plusSeconds(10);
		onTick(pull, SessionSignal.structuredReward(pull, GOTR, GOTR,
			Arrays.asList(new SessionSignal.LootDrop(555, "Water rune", 474))));
		onTick(pull.plusSeconds(2), SessionSignal.structuredReward(pull.plusSeconds(2), GOTR, GOTR,
			Arrays.asList(new SessionSignal.LootDrop(26792, "Abyssal pearls", 16))));
		CurrentSessionSnapshot after = ui(pull.plusSeconds(5));
		assertEquals("reward pulls are loot, never games", 1, after.getCompletionCount());
		assertNull("reward runes are never RUNES CRAFTED", aggregates().getProducedItems());
	}
}
