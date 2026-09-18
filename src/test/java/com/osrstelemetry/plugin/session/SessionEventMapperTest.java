package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import java.time.Instant;
import java.util.Collections;
import org.junit.Test;

/**
 * NOTE: written, not run -- same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project.
 *
 * Pure mapping tests -- no Client, no EventLedger, no coordinator.
 * Confirms SessionEventMapper copies payload fields verbatim ("no
 * duplicated collector truth logic") and never invents/strengthens a
 * claim a payload doesn't make.
 */
public class SessionEventMapperTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	@Test
	public void xpChange_mapsSkillAndDeltaVerbatim()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.XP_CHANGE, new EventPayloads.XpChange("SLAYER", 100, 150, 50, "a", "b"), T0);

		assertEquals(SignalKind.XP_CHANGE, signal.getKind());
		assertEquals("SLAYER", signal.getSkill());
		assertEquals(Long.valueOf(50), signal.getXpDelta());
	}

	@Test
	public void levelUp_hasNoSignalKind_mapsToNull()
	{
		assertNull(SessionEventMapper.map(
			EventType.LEVEL_UP, new EventPayloads.LevelUp("SLAYER", 98, 99, 12345), T0));
	}

	@Test
	public void npcDeath_mapsWithNoFabricatedKillClaim()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.NPC_DEATH,
			new EventPayloads.NpcDeath("Gargoyle", 1234, 100, "1,2,3", "Gargoyles", "Catacombs"), T0);

		assertEquals(SignalKind.NPC_DEATH, signal.getKind());
	}

	@Test
	public void npcLootAttributed_mapsButCarriesNoOwnershipClaim()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.NPC_LOOT_ATTRIBUTED,
			new EventPayloads.NpcLootAttributed("NPC", "Gargoyle", 1234, 561, "Nature rune", 40), T0);

		assertEquals(SignalKind.NPC_LOOT_ATTRIBUTED, signal.getKind());
	}

	@Test
	public void bossKill_mapsDisplayNameAndKillCountVerbatim()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.BOSS_KILL, new EventPayloads.BossKill("zulrah", "Zulrah", 812, "AUTHORITATIVE"), T0);

		assertEquals(SignalKind.BOSS_KILL, signal.getKind());
		assertEquals("Zulrah", signal.getBossOrActivitySource());
		assertEquals(Integer.valueOf(812), signal.getReliableCount());
	}

	@Test
	public void raidCompletion_mapsDisplayNameOnly()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.RAID_COMPLETION, new EventPayloads.RaidCompletion("tob", "Theatre of Blood", 40, "AUTHORITATIVE"), T0);

		assertEquals(SignalKind.RAID_COMPLETION, signal.getKind());
		assertEquals("Theatre of Blood", signal.getBossOrActivitySource());
	}

	@Test
	public void activityCompletion_mapsDisplayNameOnly()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.ACTIVITY_COMPLETION, new EventPayloads.ActivityCompletion("gauntlet", "The Gauntlet", 5, "AUTHORITATIVE"), T0);

		assertEquals(SignalKind.ACTIVITY_COMPLETION, signal.getKind());
		assertEquals("The Gauntlet", signal.getBossOrActivitySource());
	}

	@Test
	public void questCompleted_mapsQuestNameVerbatim()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.QUEST_COMPLETED, new EventPayloads.QuestCompleted(1, "COOKS_ASSISTANT", "Cook's Assistant"), T0);

		assertEquals(SignalKind.QUEST_COMPLETED, signal.getKind());
		assertEquals("Cook's Assistant", signal.getBossOrActivitySource());
	}

	@Test
	public void slayerTaskAssigned_mapsMonsterLocationAmount()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.SLAYER_TASK_ASSIGNED, new EventPayloads.SlayerTaskAssigned("Gargoyles", 124, "Catacombs", 7), T0);

		assertEquals(SignalKind.SLAYER_TASK_ASSIGNED, signal.getKind());
		assertEquals("Gargoyles", signal.getSlayerTaskName());
		assertEquals("Catacombs", signal.getSlayerTaskLocation());
		assertEquals(Integer.valueOf(124), signal.getSlayerInitialAmount());
	}

	@Test
	public void slayerTaskProgress_mapsTaskUnitsConsumed_notAbsoluteRemaining()
	{
		// Regression coverage: the mapper must carry through
		// taskUnitsConsumed (4 in this payload), never currentRemaining
		// (120), previousRemaining (124), or initialAmount (124) -- those
		// are absolute Slayer task state, not a per-event session delta.
		SessionSignal signal = SessionEventMapper.map(
			EventType.SLAYER_TASK_PROGRESS,
			new EventPayloads.SlayerTaskProgress("Gargoyles", "Catacombs", 124, 120, 4, 4, 124, 7), T0);

		assertEquals(SignalKind.SLAYER_TASK_PROGRESS, signal.getKind());
		assertEquals("Gargoyles", signal.getSlayerTaskName());
		assertEquals(Integer.valueOf(4), signal.getSlayerUnitsConsumed());
	}

	@Test
	public void slayerTaskCompleted_mapsMonsterWithNullLocation()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.SLAYER_TASK_COMPLETED, new EventPayloads.SlayerTaskCompleted("Gargoyles", 5, "5 tasks", 250), T0);

		assertEquals(SignalKind.SLAYER_TASK_COMPLETED, signal.getKind());
		assertEquals("Gargoyles", signal.getSlayerTaskName());
		assertNull("no location field exists on this payload -- must never be fabricated", signal.getSlayerTaskLocation());
	}

	@Test
	public void serverNpcLoot_preservesAtomicItemGrouping()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.SERVER_NPC_LOOT,
			new EventPayloads.ServerNpcLoot("Gargoyle", 1234, Collections.singletonList(
				new EventPayloads.ServerNpcLootItem(561, "Nature rune", 40))),
			T0);

		assertEquals(SignalKind.SERVER_NPC_LOOT, signal.getKind());
		assertEquals("Gargoyle", signal.getBossOrActivitySource());
		assertEquals(1, signal.getLootDrops().size());
		assertEquals("Nature rune", signal.getLootDrops().get(0).getItemName());
		assertEquals(40L, signal.getLootDrops().get(0).getQuantity());
	}

	@Test
	public void bankSnapshot_mapsToStorageSnapshotNotIgnoredAtTheMapperLevel()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.BANK_SNAPSHOT, new EventPayloads.BankSnapshot("bank", "snap-1", 40), T0);

		assertEquals(SignalKind.STORAGE_SNAPSHOT, signal.getKind());
	}

	@Test
	public void seedVaultSnapshot_mapsToStorageSnapshot()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.SEED_VAULT_SNAPSHOT, new EventPayloads.SeedVaultSnapshot("seedvault", 5), T0);

		assertEquals(SignalKind.STORAGE_SNAPSHOT, signal.getKind());
	}

	@Test
	public void combatAchievementCompleted_mapsTaskNameVerbatim()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.COMBAT_ACHIEVEMENT_COMPLETED, new EventPayloads.CombatAchievementCompleted("Grandmaster", "Kill Zulrah"), T0);

		assertEquals(SignalKind.COMBAT_ACHIEVEMENT_COMPLETED, signal.getKind());
		assertEquals("Kill Zulrah", signal.getBossOrActivitySource());
	}

	@Test
	public void collectionLogNewItem_mapsItemNameVerbatim()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.COLLECTION_LOG_NEW_ITEM, new EventPayloads.CollectionLogNewItem(null, "Tanzanite fang"), T0);

		assertEquals(SignalKind.COLLECTION_LOG_NEW_ITEM, signal.getKind());
		assertEquals("Tanzanite fang", signal.getBossOrActivitySource());
	}

	@Test
	public void bossActivityContext_mapsBossNameVerbatim_andCarriesNoReliableCount()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.BOSS_ACTIVITY_CONTEXT, new EventPayloads.BossActivityContext("Zulrah"), T0);

		assertEquals(SignalKind.BOSS_ACTIVITY_CONTEXT, signal.getKind());
		assertEquals("Zulrah", signal.getBossOrActivitySource());
		assertNull("BOSS_ACTIVITY_CONTEXT must never carry a reliableCount -- see EventType's javadoc",
			signal.getReliableCount());
	}
}
