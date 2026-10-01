package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * END-TO-END over the switched collector: SlayerCollector reading the
 * Spyglass-native reader (no SlayerPluginService at all), driven by
 * RuneLite's real VarbitChanged/GameTick events against a fake game whose
 * varps change exactly as the server would change them. Asserts the
 * SLAYER_TASK_* event stream Smart Slayer consumes is the same stream as
 * before the migration, and that NPC_DEATH annotation comes from the
 * committed Spyglass state.
 */
public class SlayerNativeCollectorFlowTest
{
	private static final int GARGOYLES = 46;
	private static final int GARGOYLES_ROW = 9046;
	private static final int ABERRANT = 41;
	private static final int ABERRANT_ROW = 9041;
	private static final int KRAKEN_BOSS = 3;
	private static final int KRAKEN_SUBLIST_ROW = 9500;
	private static final int KRAKEN_ROW = 9300;
	private static final int TOWER = 12;
	private static final int TOWER_ROW = 9612;

	static final class Captured
	{
		final EventType type;
		final Object payload;

		Captured(EventType type, Object payload)
		{
			this.type = type;
			this.payload = payload;
		}
	}

	private Path root;
	private LocalStateStore store;
	private SlayerNativeParityTest.FakeGame game;
	private SlayerCommittedTask committed;
	private SlayerCollector collector;
	private final List<Captured> events = new ArrayList<>();

	@Before
	public void setUp() throws Exception
	{
		root = Files.createTempDirectory("slayer-native-flow");
		TelemetryPaths.init(TestFilepaths.rooted(root));
		store = new LocalStateStore(new Gson());
		store.start();
		game = new SlayerNativeParityTest.FakeGame();
		game.row(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, GARGOYLES, GARGOYLES_ROW);
		game.field(GARGOYLES_ROW, DBTableID.SlayerTask.COL_NAME_UPPERCASE, "Gargoyles");
		game.row(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, ABERRANT, ABERRANT_ROW);
		game.field(ABERRANT_ROW, DBTableID.SlayerTask.COL_NAME_UPPERCASE, "Aberrant spectres");
		game.row(DBTableID.SlayerTaskSublist.ID, DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, KRAKEN_BOSS, KRAKEN_SUBLIST_ROW);
		game.field(KRAKEN_SUBLIST_ROW, DBTableID.SlayerTaskSublist.COL_TASK, KRAKEN_ROW);
		game.field(KRAKEN_ROW, DBTableID.SlayerTask.COL_NAME_UPPERCASE, "Kraken");
		game.row(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, TOWER, TOWER_ROW);
		game.field(TOWER_ROW, DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, "Slayer Tower");
		game.tick = 100;
		game.varbits.put(VarbitID.SLAYER_POINTS, 500);
		game.varbits.put(VarbitID.SLAYER_TASKS_COMPLETED, 20);

		EventLedger ledger = new EventLedger(new Gson())
		{
			@Override
			public void append(long accountHash, EventType type, Object payload)
			{
				events.add(new Captured(type, payload));
			}
		};
		committed = new SlayerCommittedTask();
		collector = new SlayerCollector(game.client(), null, new SlayerNativeTaskReader(game.client()), committed, store, ledger);
	}

	@After
	public void tearDown() throws Exception
	{
		store.shutdown();
		TestFilepaths.rooted(root).deleteRecursively();
	}

	// ---- server-like varp changes ------------------------------------

	private void varp(int varpId, int value)
	{
		game.varps.put(varpId, value);
		VarbitChanged e = new VarbitChanged();
		e.setVarpId(varpId);
		e.setValue(value);
		collector.onVarbitChanged(e);
	}

	private void varbit(int varbitId, int value)
	{
		game.varbits.put(varbitId, value);
		VarbitChanged e = new VarbitChanged();
		e.setVarbitId(varbitId);
		e.setValue(value);
		collector.onVarbitChanged(e);
	}

	private void tick()
	{
		game.tick++;
		collector.onGameTick(new GameTick());
	}

	/** A full assignment arriving in one server tick (several varps changing together). */
	private void assignInOneTick(int count, int target, int bossId, int area)
	{
		varbit(VarbitID.SLAYER_TARGET_BOSSID, bossId);
		varp(VarPlayerID.SLAYER_TARGET, target);
		varp(VarPlayerID.SLAYER_AREA, area);
		varp(VarPlayerID.SLAYER_COUNT_ORIGINAL, count);
		varp(VarPlayerID.SLAYER_COUNT, count);
		tick();
	}

	private void loginOn(int count, int target, int area, int original)
	{
		game.setTask(count, target, 0, area, original);
		collector.captureInitialStateWithoutBaseline();
	}

	private List<Captured> of(EventType type)
	{
		List<Captured> out = new ArrayList<>();
		for (Captured c : events)
		{
			if (c.type == type)
			{
				out.add(c);
			}
		}
		return out;
	}

	// ---- tests ---------------------------------------------------------

	@Test
	public void loginAlreadyOnTask_hydratesSilently_andCommitsForNpcDeath()
	{
		loginOn(137, GARGOYLES, TOWER, 180);
		tick();
		tick();
		assertTrue("login knowledge alone never emits", events.isEmpty());
		assertEquals("Gargoyles", committed.getTaskName());
		assertEquals("Slayer Tower", committed.getTaskLocation());
		assertEquals(137, committed.getRemaining());
	}

	@Test
	public void ordinaryAndMultiUnitDecrements_areProgress_resolvedOnTheNextTick()
	{
		loginOn(10, GARGOYLES, TOWER, 180);
		varp(VarPlayerID.SLAYER_COUNT, 9);
		assertTrue("same tick: not resolved yet (coalescing)", events.isEmpty());
		tick();
		varp(VarPlayerID.SLAYER_COUNT, 6);
		tick();
		List<Captured> p = of(EventType.SLAYER_TASK_PROGRESS);
		assertEquals(2, p.size());
		EventPayloads.SlayerTaskProgress first = (EventPayloads.SlayerTaskProgress) p.get(0).payload;
		EventPayloads.SlayerTaskProgress second = (EventPayloads.SlayerTaskProgress) p.get(1).payload;
		assertEquals(1, first.getTaskUnitsConsumed());
		assertEquals(9, first.getCurrentRemaining());
		assertEquals(3, second.getTaskUnitsConsumed());
		assertEquals(6, second.getCurrentRemaining());
		assertEquals("native initial amount on progress", 180, second.getInitialAmount());
		assertEquals("Slayer Tower", second.getTaskLocation());
	}

	@Test
	public void duplicateAndSameTickRepeats_produceOneProgress()
	{
		loginOn(10, GARGOYLES, 0, 10);
		varp(VarPlayerID.SLAYER_COUNT, 9);
		varp(VarPlayerID.SLAYER_COUNT, 9);
		varp(VarPlayerID.SLAYER_COUNT, 9);
		tick();
		tick();
		assertEquals(1, of(EventType.SLAYER_TASK_PROGRESS).size());
	}

	@Test
	public void finalKill_isCompletion_andItsNpcDeathKeepsTheTaskAnnotation()
	{
		loginOn(1, GARGOYLES, TOWER, 180);
		// Final kill: SLAYER_COUNT reaches 0 and the NPC dies in the same tick.
		varp(VarPlayerID.SLAYER_COUNT, 0);
		EventPayloads.NpcDeath death = NpcDeathCollector.buildPayload("Gargoyle", 412, 111, "1,2,0",
			committed.getRemaining(), committed.getTaskName(), committed.getTaskLocation());
		assertEquals("final kill still annotated with its task", "Gargoyles", death.getActiveSlayerTaskName());
		assertEquals("Slayer Tower", death.getActiveSlayerTaskLocation());
		tick();
		List<Captured> done = of(EventType.SLAYER_TASK_COMPLETED);
		assertEquals(1, done.size());
		EventPayloads.SlayerTaskCompleted c = (EventPayloads.SlayerTaskCompleted) done.get(0).payload;
		assertEquals("Gargoyles", c.getMonster());
		assertNull("an ordinary completion is not a cancellation", c.getCancelled());
		assertTrue(of(EventType.SLAYER_TASK_ASSIGNED).isEmpty());
		assertNull("committed state cleared once completion is committed", committed.getTaskName());
		EventPayloads.NpcDeath later = NpcDeathCollector.buildPayload("Gargoyle", 412, 111, "1,2,0",
			committed.getRemaining(), committed.getTaskName(), committed.getTaskLocation());
		assertNull(later.getActiveSlayerTaskName());
	}

	@Test
	public void cancellation_pointsDropStreakUnchanged_isFlaggedCancelled_notCompleted()
	{
		loginOn(40, GARGOYLES, TOWER, 180);
		varbit(VarbitID.SLAYER_POINTS, 470);
		varp(VarPlayerID.SLAYER_COUNT, 0);
		tick();
		EventPayloads.SlayerTaskCompleted c = (EventPayloads.SlayerTaskCompleted) of(EventType.SLAYER_TASK_COMPLETED).get(0).payload;
		assertEquals(Boolean.TRUE, c.getCancelled());
	}

	@Test
	public void rapidNextAssignment_multiVarpTick_isExactlyOneAssigned_noIntermediateIdentity()
	{
		loginOn(1, GARGOYLES, TOWER, 180);
		varp(VarPlayerID.SLAYER_COUNT, 0);
		tick();
		assignInOneTick(150, ABERRANT, 0, 0);
		List<Captured> assigned = of(EventType.SLAYER_TASK_ASSIGNED);
		assertEquals(1, assigned.size());
		EventPayloads.SlayerTaskAssigned a = (EventPayloads.SlayerTaskAssigned) assigned.get(0).payload;
		assertEquals("Aberrant spectres", a.getMonster());
		assertNull(a.getLocation());
		assertEquals(150, a.getAmount());
		assertTrue(of(EventType.SLAYER_TASK_PROGRESS).isEmpty());
	}

	@Test
	public void directReplacementAtoB_withoutPassingZero_isAssigned()
	{
		loginOn(40, GARGOYLES, TOWER, 180);
		assignInOneTick(150, ABERRANT, 0, 0);
		assertEquals(1, of(EventType.SLAYER_TASK_ASSIGNED).size());
		assertTrue(of(EventType.SLAYER_TASK_COMPLETED).isEmpty());
		assertEquals("Aberrant spectres", committed.getTaskName());
	}

	@Test
	public void sameNameReassignedAfterCompletion_isCompletedThenAssigned_twoInstances()
	{
		loginOn(1, GARGOYLES, TOWER, 180);
		varp(VarPlayerID.SLAYER_COUNT, 0);
		tick();
		assignInOneTick(170, GARGOYLES, 0, TOWER);
		assertEquals(1, of(EventType.SLAYER_TASK_COMPLETED).size());
		assertEquals(1, of(EventType.SLAYER_TASK_ASSIGNED).size());
	}

	@Test
	public void sameIdentityCountIncrease_staysProgress_semanticsUnchanged()
	{
		loginOn(40, GARGOYLES, TOWER, 180);
		varp(VarPlayerID.SLAYER_COUNT_ORIGINAL, 170);
		varp(VarPlayerID.SLAYER_COUNT, 170);
		tick();
		assertTrue("not reinterpreted as a new assignment in this pass", of(EventType.SLAYER_TASK_ASSIGNED).isEmpty());
		assertEquals(1, of(EventType.SLAYER_TASK_PROGRESS).size());
	}

	@Test
	public void bossTask_assignedAndKilled_resolvesBossName()
	{
		loginOn(0, 0, 0, 0);
		tick();
		tick(); // NONE confirmed
		assignInOneTick(8, SlayerNativeTaskResolver.BOSS_TASK_TARGET, KRAKEN_BOSS, 0);
		varp(VarPlayerID.SLAYER_COUNT, 7);
		tick();
		assertEquals("Kraken", ((EventPayloads.SlayerTaskAssigned) of(EventType.SLAYER_TASK_ASSIGNED).get(0).payload).getMonster());
		assertEquals("Kraken", ((EventPayloads.SlayerTaskProgress) of(EventType.SLAYER_TASK_PROGRESS).get(0).payload).getTaskName());
	}

	@Test
	public void loginAwaitingTask_thenFreshAssignment_isOneAssigned()
	{
		loginOn(0, 0, 0, 0);
		tick();
		tick();
		assertTrue(events.isEmpty());
		assignInOneTick(120, GARGOYLES, 0, TOWER);
		assertEquals(1, of(EventType.SLAYER_TASK_ASSIGNED).size());
	}

	@Test
	public void hopOrRelog_sameTask_resetsAndRehydrates_withoutFabricatedEvents()
	{
		loginOn(50, GARGOYLES, TOWER, 180);
		varp(VarPlayerID.SLAYER_COUNT, 49);
		tick();
		events.clear();
		// Hop: varps blank transiently, then resync.
		collector.resetForAccountSwitch();
		assertNull("reset clears committed state", committed.getTaskName());
		game.setTask(0, 0, 0, 0, 0);
		collector.captureInitialStateWithoutBaseline();
		tick();
		game.setTask(49, GARGOYLES, 0, TOWER, 180);
		varp(VarPlayerID.SLAYER_COUNT, 49);
		tick();
		tick();
		assertTrue("no COMPLETED/ASSIGNED for continuity across a hop", events.isEmpty());
		assertEquals("Gargoyles", committed.getTaskName());
	}

	/**
	 * Pre-existing ordering defect found while testing hops: a resync arriving
	 * on the tick just before "no task" would have been confirmed must
	 * hydrate silently, never be diffed against a just-confirmed NONE.
	 */
	@Test
	public void resyncArrivingOnTheLastStabilizationTick_hydratesSilently()
	{
		game.setTask(0, 0, 0, 0, 0);
		collector.captureInitialStateWithoutBaseline(); // NONE candidate, countdown 2
		tick();                                          // undisturbed: 1 left
		game.setTask(49, GARGOYLES, 0, TOWER, 180);
		varp(VarPlayerID.SLAYER_COUNT, 49);              // resync lands now
		tick();
		tick();
		assertTrue(events.isEmpty());
		assertEquals("Gargoyles", committed.getTaskName());
	}

	@Test
	public void genuinelyNoTask_stillConfirmsAfterTwoQuietTicks_thenRealAssignmentIsAssigned()
	{
		game.setTask(0, 0, 0, 0, 0);
		collector.captureInitialStateWithoutBaseline();
		tick();
		tick();
		assignInOneTick(80, GARGOYLES, 0, 0);
		assertEquals(1, of(EventType.SLAYER_TASK_ASSIGNED).size());
	}

	@Test
	public void accountSwitch_newAccountWithDifferentTask_neverDiffsAcrossAccounts()
	{
		loginOn(50, GARGOYLES, TOWER, 180);
		collector.resetForAccountSwitch();
		game.accountHash = 999L;
		loginOn(120, ABERRANT, 0, 120);
		tick();
		assertTrue(events.isEmpty());
		assertEquals("Aberrant spectres", committed.getTaskName());
	}

	@Test
	public void unresolvedCacheRow_keepsBaseline_noEvents_thenResolves()
	{
		loginOn(30, GARGOYLES, TOWER, 180);
		varp(VarPlayerID.SLAYER_TARGET, 999);
		tick();
		assertTrue("unknown is not no-task: nothing emitted", events.isEmpty());
		assertEquals("Gargoyles", committed.getTaskName());
		varp(VarPlayerID.SLAYER_TARGET, GARGOYLES);
		varp(VarPlayerID.SLAYER_COUNT, 29);
		tick();
		assertEquals(1, of(EventType.SLAYER_TASK_PROGRESS).size());
	}

	@Test
	public void quantityModifierVarbits_armARecheck_andAdjustInitialWithoutEvents()
	{
		assertTrue(SlayerCollector.isRelevantSlayerVarbitChange(-1, VarbitID.SLAYER_MODIFIER_ID));
		assertTrue(SlayerCollector.isRelevantSlayerVarbitChange(-1, VarbitID.SLAYER_MODIFIER_VALUE));
		assertTrue(SlayerCollector.isRelevantSlayerVarbitChange(-1, VarbitID.SLAYER_MODIFIER_NEGATIVE));
		loginOn(100, GARGOYLES, 0, 150);
		varbit(VarbitID.SLAYER_MODIFIER_VALUE, 25);
		varbit(VarbitID.SLAYER_MODIFIER_ID, 2);
		tick();
		assertTrue("metadata-only change emits nothing", events.isEmpty());
	}

	@Test
	public void lateOriginalCount_atLogin_isPickedUpNatively_neverGuessed()
	{
		loginOn(116, GARGOYLES, 0, 0);
		varp(VarPlayerID.SLAYER_COUNT_ORIGINAL, 137);
		tick();
		varp(VarPlayerID.SLAYER_COUNT, 115);
		tick();
		EventPayloads.SlayerTaskProgress p = (EventPayloads.SlayerTaskProgress) of(EventType.SLAYER_TASK_PROGRESS).get(0).payload;
		assertEquals(137, p.getInitialAmount());
		assertFalse(of(EventType.SLAYER_TASK_PROGRESS).isEmpty());
	}

	// ---- Mortimer streak / live status / bracelets (Smart Slayer presentation pass) ----

	@Test
	public void mortimerTask_readsMortimersOwnStreak_normalAndWildernessUnchanged()
	{
		game.varbits.put(VarbitID.SLAYER_TASKS_COMPLETED, 20);
		game.varbits.put(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED, 7);
		game.varps.put(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED, 33);

		game.varbits.put(VarbitID.SLAYER_MASTER, 10);
		loginOn(40, GARGOYLES, TOWER, 180);
		assertEquals(33, committed.getStatus().getStreak());
		assertEquals("MORTIMER", committed.getStatus().getStreakLabel());
		assertEquals(10, committed.getStatus().getMasterId());

		collector.resetForAccountSwitch();
		game.varbits.put(VarbitID.SLAYER_MASTER, 7);
		loginOn(40, GARGOYLES, TOWER, 180);
		assertEquals(7, committed.getStatus().getStreak());
		assertEquals("WILDERNESS", committed.getStatus().getStreakLabel());

		collector.resetForAccountSwitch();
		game.varbits.put(VarbitID.SLAYER_MASTER, 5);
		loginOn(40, GARGOYLES, TOWER, 180);
		assertEquals(20, committed.getStatus().getStreak());
		assertEquals("NORMAL", committed.getStatus().getStreakLabel());
	}

	@Test
	public void mortimerStreakVarp_isARelevantChange()
	{
		assertTrue(SlayerCollector.isRelevantSlayerVarbitChange(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED, -1));
	}

	@Test
	public void mortimerCompletion_streakAdvancesOnHisCounter_notCancelled()
	{
		game.varbits.put(VarbitID.SLAYER_MASTER, 10);
		game.varps.put(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED, 33);
		loginOn(1, GARGOYLES, TOWER, 180);
		game.varps.put(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED, 34);
		varp(VarPlayerID.SLAYER_COUNT, 0);
		tick();
		EventPayloads.SlayerTaskCompleted c = (EventPayloads.SlayerTaskCompleted) of(EventType.SLAYER_TASK_COMPLETED).get(0).payload;
		assertEquals(34, c.getStreak());
		assertEquals("MORTIMER", c.getStreakLabel());
		assertNull("a real completion is never flagged cancelled", c.getCancelled());
	}

	@Test
	public void mortimerSkip_pointsSpentStreakFlat_isCancelled()
	{
		game.varbits.put(VarbitID.SLAYER_MASTER, 10);
		game.varps.put(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED, 33);
		game.varbits.put(VarbitID.SLAYER_POINTS, 500);
		loginOn(40, GARGOYLES, TOWER, 180);
		game.varbits.put(VarbitID.SLAYER_POINTS, 400);
		varp(VarPlayerID.SLAYER_COUNT, 0);
		tick();
		EventPayloads.SlayerTaskCompleted c = (EventPayloads.SlayerTaskCompleted) of(EventType.SLAYER_TASK_COMPLETED).get(0).payload;
		assertEquals(Boolean.TRUE, c.getCancelled());
	}

	@Test
	public void liveStatus_survivesTaskCompletion_pointsAndStreakStillVisibleWhileAwaiting()
	{
		game.varbits.put(VarbitID.SLAYER_MASTER, 5);
		loginOn(1, GARGOYLES, TOWER, 180);
		game.varbits.put(VarbitID.SLAYER_POINTS, 515);
		game.varbits.put(VarbitID.SLAYER_TASKS_COMPLETED, 21);
		varp(VarPlayerID.SLAYER_COUNT, 0);
		tick();
		assertNull("no task committed", committed.getTaskName());
		assertEquals(515, committed.getStatus().getPoints());
		assertEquals(21, committed.getStatus().getStreak());
		assertNull(committed.getStatus().getTaskName());
		collector.resetForAccountSwitch();
		assertNull("account switch clears the status", committed.getStatus());
	}

	@Test
	public void modifierVarbits_areCapturedRaw()
	{
		game.varbits.put(VarbitID.SLAYER_MASTER, 10);
		game.varbits.put(VarbitID.SLAYER_MODIFIER_ID, 2);
		game.varbits.put(VarbitID.SLAYER_MODIFIER_VALUE, 20);
		game.varbits.put(VarbitID.SLAYER_MODIFIER_NEGATIVE, 1);
		loginOn(40, GARGOYLES, TOWER, 180);
		assertEquals(2, committed.getStatus().getModifierId());
		assertEquals(20, committed.getStatus().getModifierValue());
		assertTrue(committed.getStatus().isModifierNegative());
		assertEquals("Slayer Tower", committed.getStatus().getTaskLocation());
	}

	private void chat(net.runelite.api.ChatMessageType type, String message)
	{
		net.runelite.api.events.ChatMessage m = new net.runelite.api.events.ChatMessage();
		m.setType(type);
		m.setMessage(message);
		collector.onChatMessage(m);
	}

	@Test
	public void braceletMessages_emitExplicitEffects_only()
	{
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE,
			"<col=ef1020>Your bracelet of slaughter prevents your slayer count from decreasing. It has 29 charges left.</col>");
		chat(net.runelite.api.ChatMessageType.SPAM,
			"Your expeditious bracelet helps you progress your slayer task faster. It then crumbles to dust.");
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE,
			"Your expeditious bracelet helps you progress your slayer faster. It has 1 charge left.");
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "Your bracelet of slaughter has 29 charges left.");
		chat(net.runelite.api.ChatMessageType.PUBLICCHAT,
			"Your bracelet of slaughter prevents your slayer count from decreasing. It has 29 charges left.");
		List<Captured> effects = of(EventType.SLAYER_BRACELET_EFFECT);
		assertEquals("a charge check or a player typing it is never a proc", 3, effects.size());
		assertEquals("SLAUGHTER", ((EventPayloads.SlayerBraceletEffect) effects.get(0).payload).getEffect());
		assertEquals("EXPEDITIOUS", ((EventPayloads.SlayerBraceletEffect) effects.get(1).payload).getEffect());
		assertEquals("EXPEDITIOUS", ((EventPayloads.SlayerBraceletEffect) effects.get(2).payload).getEffect());
	}

	@Test
	public void parseBraceletEffect_coversEveryChargeOutcome()
	{
		for (String tail : new String[] {"It has 5 charges left.", "It has 1 charge left.", "It then crumbles to dust.",
			"It then regenerates itself to full charge!"})
		{
			assertEquals("SLAUGHTER", SlayerCollector.parseBraceletEffect(
				"Your bracelet of slaughter prevents your slayer count from decreasing. " + tail));
			assertEquals("EXPEDITIOUS", SlayerCollector.parseBraceletEffect(
				"Your expeditious bracelet helps you progress your slayer task faster. " + tail));
		}
		assertNull(SlayerCollector.parseBraceletEffect("Your Bracelet of Slaughter has crumbled to dust."));
		assertNull(SlayerCollector.parseBraceletEffect("You have completed your task! You killed 136 Kalphites."));
		assertNull(SlayerCollector.parseBraceletEffect(null));
	}

	@Test
	public void remainingDroppingByTwo_isNeverTakenAsAnExpeditiousProc()
	{
		loginOn(40, GARGOYLES, TOWER, 180);
		varp(VarPlayerID.SLAYER_COUNT, 38);
		tick();
		assertEquals(1, of(EventType.SLAYER_TASK_PROGRESS).size());
		assertTrue(of(EventType.SLAYER_BRACELET_EFFECT).isEmpty());
	}
}
