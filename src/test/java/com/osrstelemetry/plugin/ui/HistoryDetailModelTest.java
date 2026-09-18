package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.osrstelemetry.plugin.collectors.LoadoutArchive;
import com.osrstelemetry.plugin.model.StorageState;
import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.LoadoutProvenance;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.session.SessionLifecycleEngine;
import com.osrstelemetry.plugin.session.SessionPersistence;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.File;
import java.time.Instant;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers
 * {@link HistoryDetailModel#from} -- the pure, Swing-free transform
 * {@link HistoryDetailView} renders -- without constructing any Swing
 * component, same convention {@code LootTrackerViewTest}/
 * {@code HistoryEntryTest} already establish for this codebase (this
 * project has no Mockito dependency, so RuneLite-injected types like
 * ItemManager/ClientThread are never faked; only pure, Swing-free logic
 * is unit tested directly).
 */
public class HistoryDetailModelTest
{
	private static final long TEST_ACCOUNT_HASH = 424_242_888L;
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final ActivityIdentity GARGOYLES =
		new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");

	@Before
	public void setUp()
	{
		deleteAccountDir();
	}

	@After
	public void tearDown()
	{
		deleteAccountDir();
	}

	private static void deleteAccountDir()
	{
		deleteRecursively(TelemetryPaths.accountDir(TEST_ACCOUNT_HASH));
	}

	private static void deleteRecursively(File dir)
	{
		File[] files = dir.listFiles();
		if (files != null)
		{
			for (File f : files)
			{
				if (f.isDirectory())
				{
					deleteRecursively(f);
				}
				else
				{
					f.delete();
				}
			}
		}
	}

	private static Session buildFinalizedSession()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);
		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);
		return engine.advanceTime(expiry).getFinalized();
	}

	@Test
	public void from_nullSession_returnsNull()
	{
		assertNull(HistoryDetailModel.from(null));
	}

	@Test
	public void from_validSession_carriesIdentityAndTiming()
	{
		Session session = buildFinalizedSession();

		HistoryDetailModel model = HistoryDetailModel.from(session);

		assertNotNull(model);
		assertEquals(session.getSessionId(), model.getSessionId());
		assertEquals(GARGOYLES, model.getActivityIdentity());
		assertEquals(session.getFinalizedAt(), model.getFinalizedAt());
		assertEquals(session.getAccumulatedActiveDurationMillis(), model.getAccumulatedActiveDurationMillis());
	}

	@Test
	public void from_xpRows_sortedDescendingByXpGained_zeroEntriesExcluded()
	{
		Session session = buildFinalizedSession();
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 500L);
		session.getAggregates().getXpGainedBySkill().put("STRENGTH", 1200L);
		session.getAggregates().getXpGainedBySkill().put("HITPOINTS", 0L);

		HistoryDetailModel model = HistoryDetailModel.from(session);

		assertEquals(2, model.getXpRows().size());
		assertEquals("STRENGTH", model.getXpRows().get(0).getSkill());
		assertEquals("SLAYER", model.getXpRows().get(1).getSkill());
		assertEquals(1700L, model.getTotalXpGained());
	}

	@Test
	public void from_lootDrops_summedAcrossGroupsByItemId()
	{
		Session session = buildFinalizedSession();
		SessionAggregates.LootDropGroup group1 = new SessionAggregates.LootDropGroup();
		group1.getItems().add(lootItem(526, "Bones", 1));
		SessionAggregates.LootDropGroup group2 = new SessionAggregates.LootDropGroup();
		group2.getItems().add(lootItem(526, "Bones", 2));
		group2.getItems().add(lootItem(995, "Coins", 40));
		session.getAggregates().getLootDrops().add(group1);
		session.getAggregates().getLootDrops().add(group2);

		HistoryDetailModel model = HistoryDetailModel.from(session);

		assertEquals("two drop GROUPS were observed, even though only two distinct item ids resulted", 2, model.getLootDropCount());
		assertEquals(2, model.getLootRows().size());
		long bonesQuantity = model.getLootRows().stream()
			.filter(row -> row.getItemId() == 526)
			.mapToLong(HistoryDetailModel.LootRow::getQuantity)
			.sum();
		assertEquals("bones from both groups must be summed into one row", 3L, bonesQuantity);
	}

	@Test
	public void from_noLoadoutAttached_bothLoadoutsUnavailable_neverNull()
	{
		Session session = buildFinalizedSession();

		HistoryDetailModel model = HistoryDetailModel.from(session);

		assertNotNull(model.getStartingLoadout());
		assertNotNull(model.getEndingLoadout());
		assertEquals(LoadoutProvenance.UNAVAILABLE, model.getStartingLoadout().getProvenance());
		assertEquals(LoadoutProvenance.UNAVAILABLE, model.getEndingLoadout().getProvenance());
	}

	@Test
	public void from_loadoutPresent_passedThroughFromRealFinalizedSession() throws Exception
	{
		// Uses the real production persistence path (matches
		// SessionPersistenceLoadoutResolutionTest's own established
		// pattern) rather than Session's package-private setter, which
		// this ui-package test has no access to -- see Session's own
		// javadoc on why mutation is deliberately package-private.
		LocalStateStore store = new LocalStateStore();
		store.start();
		try
		{
			// Recorded at -45s so it lands at-or-before the resolver's
			// target instant (T0 - 30s -- see
			// LoadoutResolver.STARTING_LOADOUT_TARGET_OFFSET), not merely
			// before T0 itself.
			LoadoutArchive archive = new LoadoutArchive();
			archive.record(T0.minusSeconds(45),
				Collections.singletonList(new StorageState.StorageItem(0, 995, "Coins", 500, null)),
				Collections.emptyList());
			SessionPersistence persistence = new SessionPersistence(store, archive);

			SessionLifecycleEngine engine = new SessionLifecycleEngine();
			engine.onQualifyingActivity(GARGOYLES, T0);
			Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
			engine.advanceTime(suspendedAt);
			Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);
			Session finalized = engine.advanceTime(expiry).getFinalized();

			persistence.persistFinalized(TEST_ACCOUNT_HASH, finalized, null);
			Thread.sleep(300);

			Session reloaded = persistence.loadFinalized(TEST_ACCOUNT_HASH, finalized.getSessionId());
			HistoryDetailModel model = HistoryDetailModel.from(reloaded);

			assertNotNull(model.getStartingLoadout());
			assertEquals(LoadoutProvenance.PRE_START, model.getStartingLoadout().getProvenance());
			assertEquals(995, model.getStartingLoadout().getInventory().get(0).getItemId());
		}
		finally
		{
			store.shutdown();
		}
	}

	private static SessionAggregates.LootItemAggregate lootItem(int itemId, String name, long quantity)
	{
		SessionAggregates.LootItemAggregate item = new SessionAggregates.LootItemAggregate();
		item.setItemId(itemId);
		item.setItemName(name);
		item.setQuantity(quantity);
		return item;
	}
}
