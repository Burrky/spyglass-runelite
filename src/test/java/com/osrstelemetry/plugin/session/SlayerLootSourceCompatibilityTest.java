package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;

import com.osrstelemetry.plugin.session.SlayerTaskFamilyRegistry.LootSourceCompatibility;
import org.junit.Test;

/** SERVER_NPC_LOOT source vs current Slayer assignment: MATCH / KNOWN_MISMATCH / UNKNOWN. */
public class SlayerLootSourceCompatibilityTest
{
	private static LootSourceCompatibility c(String task, String source)
	{
		return SlayerTaskFamilyRegistry.lootSourceCompatibility(task, source);
	}

	@Test
	public void match_familyMembersVariantsAndRuneLiteContainsSemantics()
	{
		assertEquals(LootSourceCompatibility.MATCH, c("Turoth", "Turoth"));
		assertEquals(LootSourceCompatibility.MATCH, c("Turoth", "Spiked turoth"));
		assertEquals("Zombies' 'undead' target", LootSourceCompatibility.MATCH, c("Zombies", "Undead chicken"));
		assertEquals(LootSourceCompatibility.MATCH, c("Kalphites", "Kalphite Worker"));
		assertEquals(LootSourceCompatibility.MATCH, c("Cave Slimes", "Cave slime"));
		assertEquals(LootSourceCompatibility.MATCH, c("Abyssal Demons", "Greater abyssal demon"));
		assertEquals(LootSourceCompatibility.MATCH, c("Nechryael", "Nechryarch"));
		assertEquals("case-insensitive task names", LootSourceCompatibility.MATCH, c("Black Demons", "Black demon"));
	}

	@Test
	public void knownMismatch_sourceIsAnotherTasksTarget()
	{
		assertEquals(LootSourceCompatibility.KNOWN_MISMATCH, c("Turoth", "Undead chicken"));
		assertEquals(LootSourceCompatibility.KNOWN_MISMATCH, c("Turoth", "Goblin"));
		assertEquals(LootSourceCompatibility.KNOWN_MISMATCH, c("Bloodveld", "Cow"));
	}

	@Test
	public void unknown_cannotProveOffTask_existingOwnershipKept()
	{
		assertEquals("summoned/subordinate NPC is no task's target", LootSourceCompatibility.UNKNOWN, c("Nechryael", "Death Spawn"));
		assertEquals(LootSourceCompatibility.UNKNOWN, c("Turoth", null));
		assertEquals(LootSourceCompatibility.UNKNOWN, c("Turoth", "  "));
		assertEquals(LootSourceCompatibility.UNKNOWN, c(null, "Goblin"));
		assertEquals("an uncatalogued task + an NPC no task targets", LootSourceCompatibility.UNKNOWN, c("Foobars", "Mysterious stranger"));
	}
}
