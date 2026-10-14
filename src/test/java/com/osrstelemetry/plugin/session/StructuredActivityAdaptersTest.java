package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.activity.RecognitionState;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.Test;

/**
 * Per-adapter behaviour for every implemented structured activity, driven
 * table-style through the real coordinator: location, no session on entry,
 * activation, lock, expected-XP ownership, completion, repeated rounds,
 * reward ownership, leave/switch, restart and History.
 */
public class StructuredActivityAdaptersTest extends StructuredActivityTestSupport
{
	static final class Spec
	{
		final String key;
		final String displayName;
		final int region;
		final String[] expectedSkills;
		/** (time, running count) -> completion signal; null = no completion source. */
		final BiFunction<Instant, Integer, SessionSignal> completion;
		/** Canonical reward-source key (null = no reward source). */
		final String rewardName;
		final String rewardDisplay;
		final int rewardRegion;
		final String label;

		Spec(String key, String displayName, int region, String[] expectedSkills,
			BiFunction<Instant, Integer, SessionSignal> completion, String rewardName, String rewardDisplay, int rewardRegion, String label)
		{
			this.key = key;
			this.displayName = displayName;
			this.region = region;
			this.expectedSkills = expectedSkills;
			this.completion = completion;
			this.rewardName = rewardName;
			this.rewardDisplay = rewardDisplay;
			this.rewardRegion = rewardRegion;
			this.label = label;
		}

		@Override
		public String toString()
		{
			return key;
		}
	}

	static final List<Spec> SPECS = Arrays.asList(
		new Spec("guardians_of_the_rift", "Guardians of the Rift", 14484, new String[] {"RUNECRAFT", "MINING", "CRAFTING"},
			(t, n) -> SessionSignal.structuredActivityCompletion(t, "guardians_of_the_rift", n), "guardians_of_the_rift", "Guardians of the Rift", 14484, "Games"),
		new Spec("tempoross", "Tempoross", 12076, new String[] {"FISHING", "CONSTRUCTION", "COOKING"},
			(t, n) -> SessionSignal.bossKill(t, "Tempoross", n), "tempoross_reward_pool", "Tempoross Reward Pool", 12588, "Games"),
		new Spec("pest_control", "Pest Control", 10536, new String[] {"ATTACK", "HITPOINTS", "MAGIC"},
			(t, n) -> SessionSignal.structuredActivityCompletion(t, "pest_control", null), null, null, 0, "Games won"),
		new Spec("giants_foundry", "Giants' Foundry", 13491, new String[] {"SMITHING"}, null, null, null, 0, null),
		new Spec("barrows", "Barrows", 14231, new String[] {"RANGED", "HITPOINTS", "DEFENCE"},
			(t, n) -> SessionSignal.activityCompletion(t, "Barrows chest", n), "barrows", "Barrows", 14231, "Chests"),
		new Spec("wintertodt", "Wintertodt", 6462, new String[] {"FIREMAKING", "WOODCUTTING", "FLETCHING"},
			(t, n) -> SessionSignal.activityCompletion(t, "subdued Wintertodt", n), "wintertodt_reward_cart", "Wintertodt Reward Cart", 6461, "Games"));

	void fresh() throws Exception
	{
		tearDownCoordinator();
		setUpCoordinator();
	}

	static boolean startsOnEntry(Spec spec)
	{
		return com.osrstelemetry.plugin.activity.StructuredActivityRegistry.defaults().byKey(spec.key).startsOnEntry(spec.region);
	}

	boolean is(Spec spec, Session s)
	{
		return s != null && s.getActivityIdentity().getActivityType() == ActivityType.STRUCTURED_ACTIVITY
			&& spec.key.equals(s.getActivityIdentity().getActivityKey());
	}

	/** Enter, then expected XP on three distinct ticks. */
	Session play(Spec spec, Instant base)
	{
		onTick(base, enter(spec.key, spec.region, base));
		for (int i = 0; i < 3; i++)
		{
			Instant t = base.plusSeconds(10L * (i + 1));
			onTick(t, xpAt(spec.expectedSkills[i % spec.expectedSkills.length], 25, t));
		}
		return current();
	}

	static SessionSignal reward(Spec spec, Instant t, long coins)
	{
		return SessionSignal.structuredReward(t, spec.key, spec.rewardName,
			Collections.singletonList(new SessionSignal.LootDrop(995, "Coins", coins)));
	}

	static long lootFrom(Session s, String source)
	{
		long total = 0;
		for (SessionAggregates.LootDropGroup g : s.getAggregates().getLootDrops())
		{
			if (source.equals(g.getSourceName()))
			{
				for (SessionAggregates.LootItemAggregate i : g.getItems())
				{
					total += i.getQuantity();
				}
			}
		}
		return total;
	}

	@Test
	public void a_location_noSessionOnEntry_activation_lock_expectedXpOwnership() throws Exception
	{
		for (Spec spec : SPECS)
		{
			fresh();
			onTick(T0, enter(spec.key, spec.region, T0));
			if (startsOnEntry(spec))
			{
				// Pest Control island: the game's own play area -- entry IS the start.
				assertTrue(spec + ": entry starts the activity", is(spec, current()));
				assertEquals(spec + ": active from entry", RecognitionState.ACTIVE, recognition());
			}
			else
			{
				assertNull(spec + ": entry alone is no session", current());
				assertEquals(spec + ": armed", RecognitionState.ARMED, recognition());
			}

			Session s = play(spec, T0);
			assertTrue(spec + ": activated", is(spec, s));
			assertEquals(spec.displayName, s.getActivityIdentity().getDisplayName());
			assertEquals(spec + ": locked", RecognitionState.LOCKED, recognition());
			for (String skill : spec.expectedSkills)
			{
				Instant t = T0.plusSeconds(60 + 10L * Arrays.asList(spec.expectedSkills).indexOf(skill));
				onTick(t, xpAt(skill, 10, t));
				assertEquals(spec + ": expected " + skill + " never fragments", s.getSessionId(), current().getSessionId());
				assertTrue(spec + ": credited " + skill, current().getAggregates().getXpGainedBySkill().containsKey(skill));
			}
		}
	}

	@Test
	public void b_completion_repeatedRounds_oneSession() throws Exception
	{
		for (Spec spec : SPECS)
		{
			fresh();
			String id = play(spec, T0).getSessionId();
			Instant t = T0.plusSeconds(40);
			for (int round = 1; round <= 3; round++)
			{
				for (int i = 0; i < 4; i++)
				{
					t = t.plusSeconds(15);
					onTick(t, xpAt(spec.expectedSkills[i % spec.expectedSkills.length], 30, t));
				}
				if (spec.completion != null)
				{
					t = t.plusSeconds(2);
					onTick(t, spec.completion.apply(t, 50 + round));
				}
				t = t.plusSeconds(60);
			}
			assertEquals(spec + ": all rounds are one session", id, current().getSessionId());
			assertTrue(spec + ": never a boss/other session", is(spec, current()));
			SessionAggregates.ActivityCompletionCount c = current().getAggregates().getActivityCompletions();
			if (spec.completion == null)
			{
				assertNull(spec + ": no completion source, no fabricated metric", c);
			}
			else
			{
				assertEquals(spec + ": completions", 3, c.getSessionCompletions());
				assertEquals(spec.key, c.getActivityKey());
			}
		}
	}

	@Test
	public void c_rewardOwnership_rewardsLandOnlyOnTheActivity() throws Exception
	{
		for (Spec spec : SPECS)
		{
			if (spec.rewardName == null)
			{
				continue;
			}
			fresh();
			// A Slayer session exists first and is parked by the activity.
			onTick(T0, SessionSignal.slayerTaskAssigned(T0, NECH, NECH_LOC, 100));
			onTick(T0.plusSeconds(5), SessionSignal.slayerTaskProgress(T0.plusSeconds(5), NECH, NECH_LOC, 1, 99));
			String slayerId = current().getSessionId();
			Session activity = play(spec, T0.plusSeconds(20));
			assertTrue(is(spec, activity));
			assertEquals(slayerId, coordinator.testInterruptedCandidate().getSessionId());

			Instant t = T0.plusSeconds(90);
			if (spec.rewardRegion != spec.region)
			{
				onTick(t, enter(spec.key, spec.rewardRegion, t));
			}
			onTick(t.plusSeconds(5), reward(spec, t.plusSeconds(5), 777));
			assertEquals(spec + ": reward on the activity", 777L, lootFrom(current(), spec.rewardDisplay));
			assertEquals(spec + ": still the activity", activity.getSessionId(), current().getSessionId());
			assertTrue(spec + ": parked Slayer session untouched",
				coordinator.testInterruptedCandidate().getAggregates().getLootDrops().isEmpty());
		}
	}

	@Test
	public void c2_rewardWithoutTheActivity_isDropped_neverIntoSlayer() throws Exception
	{
		for (Spec spec : SPECS)
		{
			if (spec.rewardName == null)
			{
				continue;
			}
			fresh();
			onTick(T0, SessionSignal.slayerTaskProgress(T0, NECH, NECH_LOC, 1, 99));
			String slayerId = current().getSessionId();
			onTick(T0.plusSeconds(5), reward(spec, T0.plusSeconds(5), 50));
			assertEquals(slayerId, current().getSessionId());
			assertTrue(spec + ": never into the Slayer session", current().getAggregates().getLootDrops().isEmpty());
		}
	}

	@Test
	public void d_leaveThenSwitch() throws Exception
	{
		for (Spec spec : SPECS)
		{
			fresh();
			String id = play(spec, T0).getSessionId();
			Instant out = T0.plusSeconds(60);
			onTick(out, leave(out));
			for (int i = 1; i <= 12; i++)
			{
				Instant t = out.plusSeconds(10L * i);
				onTick(t, xpAt("THIEVING", 20, t));
			}
			assertNotEquals(spec + ": unrelated activity takes over after leaving", id, current().getSessionId());
			assertEquals("thieving", current().getActivityIdentity().getActivityKey().toLowerCase());
		}
	}

	@Test
	public void e_restartReArmsAndResumes() throws Exception
	{
		for (Spec spec : SPECS)
		{
			fresh();
			Instant base = Instant.now().minusSeconds(200);
			String id = play(spec, base).getSessionId();
			restartPlugin();
			Instant after = base.plusSeconds(80);
			onTick(after, enter(spec.key, spec.region, after));
			assertEquals(spec + ": re-armed", startsOnEntry(spec) ? RecognitionState.ACTIVE : RecognitionState.ARMED, recognition());
			onTick(after.plusSeconds(5), xpAt(spec.expectedSkills[0], 10, after.plusSeconds(5)));
			assertEquals(spec + ": same session after restart", id, current().getSessionId());
		}
	}

	@Test
	public void f_historyPreservesIdentityAndMetric() throws Exception
	{
		for (Spec spec : SPECS)
		{
			fresh();
			Instant base = Instant.now().minusSeconds(3 * 3600);
			Session live = play(spec, base);
			Instant last = base.plusSeconds(40);
			if (spec.completion != null)
			{
				onTick(last, spec.completion.apply(last, 9));
			}
			coordinator.testProcessTick(++tick, last.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1));
			coordinator.testProcessTick(++tick, last.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT)
				.plus(SessionLifecycleEngine.RESUME_WINDOW).plusSeconds(5));
			assertNull(current());
			store.shutdown();
			store = new LocalStateStore(GSON);
			store.start();
			Session loaded = new SessionPersistence(store).loadFinalized(ACCOUNT, live.getSessionId());
			assertNotNull(spec + ": finalized file", loaded);
			HistoryEntry entry = HistoryEntry.from(loaded);
			assertEquals(spec.displayName, entry.getActivityIdentity().getDisplayName());
			assertEquals(spec + ": completion count in History",
				Integer.valueOf(spec.completion == null ? 0 : 1), entry.getActivityCompletionCount());
		}
	}

	// ------------------------------------------------------------------ activity-specific

	@Test
	public void tempoross_bossKillIsACompletion_notABossSession_andSubordinateContextNeverSwitches()
	{
		Spec tempoross = SPECS.get(1);
		String id = play(tempoross, T0).getSessionId();
		onTick(T0.plusSeconds(50), SessionSignal.bossActivityContext(T0.plusSeconds(50), "Tempoross"));
		onTick(T0.plusSeconds(60), SessionSignal.bossKill(T0.plusSeconds(60), "Tempoross", 12));
		assertEquals(id, current().getSessionId());
		assertEquals(ActivityType.STRUCTURED_ACTIVITY, current().getActivityIdentity().getActivityType());
		assertNull("no boss kill-count metric on the activity", current().getAggregates().getReliableCount());
		assertEquals(1, current().getAggregates().getActivityCompletions().getSessionCompletions());
		assertEquals(Integer.valueOf(12), current().getAggregates().getActivityCompletions().getLatestAuthoritativeValue());
	}

	@Test
	public void tempoross_rewardPoolInCoveWithoutAGame_createsNothing()
	{
		onTick(T0, enter("tempoross", 12588, T0));
		onTick(T0.plusSeconds(5), reward(SPECS.get(1), T0.plusSeconds(5), 100));
		onTick(T0.plusSeconds(10), xpAt("FISHING", 50, T0.plusSeconds(10)));
		assertTrue("NON_EXCLUSIVE cove: fishing there is ordinary skilling",
			current() != null && current().getActivityIdentity().getActivityType() == ActivityType.SKILLING);
		assertTrue(current().getAggregates().getLootDrops().isEmpty());
	}

	@Test
	public void wintertodt_campIsNonExclusive_woodcuttingThereIsOrdinary()
	{
		onTick(T0, enter("wintertodt", 6461, T0));
		onTick(T0.plusSeconds(5), xpAt("WOODCUTTING", 50, T0.plusSeconds(5)));
		assertEquals(ActivityType.SKILLING, current().getActivityIdentity().getActivityType());
	}

	@Test
	public void slayerTaskProgressInsideAnActivity_neverStealsIt()
	{
		Spec barrows = SPECS.get(4);
		String id = play(barrows, T0).getSessionId();
		onTick(T0.plusSeconds(45), SessionSignal.slayerTaskProgress(T0.plusSeconds(45), "Skeletons", null, 1, 20));
		onTick(T0.plusSeconds(50), SessionSignal.slayerTaskProgress(T0.plusSeconds(50), "Skeletons", null, 1, 19));
		assertEquals(id, current().getSessionId());
		assertNull(coordinator.testInterruptedCandidate());
	}

	@Test
	public void npcLootInsideAnActivity_staysWithTheActivity()
	{
		Spec pc = SPECS.get(2);
		String id = play(pc, T0).getSessionId();
		onTick(T0.plusSeconds(45), SessionSignal.serverNpcLoot(T0.plusSeconds(45), "Brawler",
			Collections.singletonList(new SessionSignal.LootDrop(995, "Coins", 3))));
		assertEquals(id, current().getSessionId());
		assertEquals(3L, lootFrom(current(), "Brawler"));
	}
}
