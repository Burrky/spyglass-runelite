package com.osrstelemetry.plugin.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Catalog integrity and the indexed lookups the collector/classifier rely on. */
public class StructuredActivityRegistryTest
{
	private final StructuredActivityRegistry registry = StructuredActivityRegistry.defaults();

	@Test
	public void catalogKeysAndRegions_matchAuditedRuneLiteData()
	{
		Set<String> keys = new HashSet<>();
		for (StructuredActivityDefinition d : registry.all())
		{
			keys.add(d.getKey());
		}
		assertEquals(new HashSet<>(Arrays.asList("guardians_of_the_rift", "tempoross", "pest_control",
			"giants_foundry", "barrows", "wintertodt")), keys);

		assertEquals("guardians_of_the_rift", registry.forRegion(14484).getKey());
		assertEquals("tempoross", registry.forRegion(12076).getKey());
		assertEquals("tempoross", registry.forRegion(12588).getKey());
		assertEquals("pest_control", registry.forRegion(10536).getKey());
		assertEquals("giants_foundry", registry.forRegion(13491).getKey());
		assertEquals("barrows", registry.forRegion(14131).getKey());
		assertEquals("barrows", registry.forRegion(14231).getKey());
		assertEquals("wintertodt", registry.forRegion(6462).getKey());
		assertEquals("wintertodt", registry.forRegion(6461).getKey());
		assertNull("ordinary regions are not structured activities (e.g. Lumbridge)", registry.forRegion(12850));
	}

	@Test
	public void policiesPerRegion()
	{
		assertEquals(LocationPolicy.EXCLUSIVE, registry.byKey("tempoross").policyFor(12076));
		assertEquals(LocationPolicy.NON_EXCLUSIVE, registry.byKey("tempoross").policyFor(12588));
		assertEquals(LocationPolicy.STRONG_CONTEXT, registry.byKey("barrows").policyFor(14131));
		assertEquals(LocationPolicy.EXCLUSIVE, registry.byKey("barrows").policyFor(14231));
		assertEquals(LocationPolicy.NON_EXCLUSIVE, registry.byKey("wintertodt").policyFor(6461));
		assertNull(registry.byKey("barrows").policyFor(12076));
	}

	@Test
	public void rewardSources_canonicalKeys_displayNames_aliases_andOneProducerEach()
	{
		StructuredRewardSource barrows = registry.rewardSource("barrows");
		assertEquals("Barrows", barrows.getDisplayName());
		assertEquals("barrows", barrows.getActivityKey());
		assertEquals(StructuredRewardSource.Producer.REWARD_INTERFACE, barrows.getProducer());
		assertEquals("gameval InterfaceID.BARROWS_REWARD", 155, barrows.getInterfaceId());
		assertEquals("gameval InventoryID.TRAIL_REWARDINV", 141, barrows.getContainerId());
		assertSame(barrows, registry.rewardForInterface(155));

		StructuredRewardSource gotr = registry.rewardSource("guardians_of_the_rift");
		assertEquals("Guardians of the Rift", gotr.getDisplayName());
		assertSame("the same causal source under another name canonicalizes to one key", gotr, registry.rewardSource("Rewards Guardian"));
		assertSame(gotr, registry.rewardSource("Guardians of the Rift"));

		StructuredRewardSource pool = registry.rewardSource("tempoross_reward_pool");
		assertEquals("Tempoross Reward Pool", pool.getDisplayName());
		assertEquals("tempoross", pool.getActivityKey());
		assertSame("RuneLite's old event name is an alias of the same source", pool, registry.rewardSource("Reward pool (Tempoross)"));

		StructuredRewardSource cart = registry.rewardSource("wintertodt_reward_cart");
		assertEquals("Wintertodt Reward Cart", cart.getDisplayName());
		assertSame(cart, registry.rewardSource("Reward cart (Wintertodt)"));

		for (StructuredRewardSource claim : new StructuredRewardSource[] {gotr, pool, cart})
		{
			assertEquals(StructuredRewardSource.Producer.CLAIM_MESSAGE, claim.getProducer());
		}
		assertTrue(registry.byKey("pest_control").getRewardSources().isEmpty());
		assertTrue(registry.byKey("giants_foundry").getRewardSources().isEmpty());
	}

	@Test
	public void unrelatedChests_caskets_andSupplyCrates_areNeverRewardSources()
	{
		assertNull("Brimstone chest must never be an activity reward", registry.rewardSource("Brimstone Chest"));
		assertNull(registry.rewardSource("Crystal Chest"));
		assertNull("a casket opened anywhere is not a Tempoross reward", registry.rewardSource("Casket (Tempoross)"));
		assertNull("a supply crate opened anywhere is not a Wintertodt reward", registry.rewardSource("Supply crate (Wintertodt)"));
		assertNull(registry.rewardSource("Lunar Chest"));
		assertNull("no substring matching", registry.rewardSource("Barrows chest"));
		assertNull(registry.rewardSource(null));
		assertNull("clue caskets share container 141 but not the Barrows interface", registry.rewardForInterface(73));
		assertNull("Perilous Moons reward interface is not implemented", registry.rewardForInterface(868));
	}

	@Test
	public void claimMessages_areRegionBound()
	{
		String msg = "You found some loot: 20 x Nature rune";
		assertEquals("guardians_of_the_rift", registry.rewardForClaimMessage(msg, 14484).getKey());
		assertEquals("tempoross_reward_pool", registry.rewardForClaimMessage(msg, 12588).getKey());
		assertNull("the Tempoross GAME region is not the reward area", registry.rewardForClaimMessage(msg, 12076));
		assertEquals("wintertodt_reward_cart", registry.rewardForClaimMessage("<col=ef1020>" + msg, 6461).getKey());
		assertNull("the same message elsewhere is no reward", registry.rewardForClaimMessage(msg, 12850));
		assertNull(registry.rewardForClaimMessage("You find some treasure in the chest!", 14484));
	}

	@Test
	public void chatCompletions_areIsolatedPerActivity()
	{
		StructuredActivityRegistry.ChatCompletion gotr = registry.matchCompletionChat("Amount of rifts you have closed: 1,234.");
		assertNotNull(gotr);
		assertEquals("guardians_of_the_rift", gotr.getDefinition().getKey());
		assertEquals(Integer.valueOf(1234), gotr.getCount());

		StructuredActivityRegistry.ChatCompletion pc = registry.matchCompletionChat(
			"Congratulations! You managed to destroy all the portals! We've awarded you 4 Void Knight Commendation points.");
		assertNotNull(pc);
		assertEquals("pest_control", pc.getDefinition().getKey());
		assertNull("points are not a game count", pc.getCount());

		assertNull(registry.matchCompletionChat("Your Barrows chest count is: 5."));
		assertNull(registry.matchCompletionChat("You have closed the rift."));
		assertNull(registry.matchCompletionChat(null));
	}

	@Test
	public void expectedSkillsSubordinatesAndCompletionSources()
	{
		StructuredActivityDefinition gotr = registry.byKey("guardians_of_the_rift");
		assertTrue(gotr.isExpectedSkill("MINING"));
		assertTrue(gotr.isExpectedSkill("Crafting"));
		assertTrue(gotr.isExpectedSkill("runecraft"));
		assertFalse(gotr.isExpectedSkill("AGILITY"));
		assertFalse(gotr.isExpectedSkill("ATTACK"));

		StructuredActivityDefinition barrows = registry.byKey("barrows");
		assertTrue(barrows.isExpectedSkill("MAGIC"));
		assertTrue(barrows.isExpectedSkill("HITPOINTS"));
		assertFalse(barrows.isExpectedSkill("FISHING"));
		assertTrue(barrows.isSubordinate("Dharok the Wretched"));
		assertTrue(barrows.isCompletionCount("Barrows chest"));
		assertFalse(barrows.isSubordinate("Zulrah"));

		StructuredActivityDefinition tempoross = registry.byKey("tempoross");
		assertTrue(tempoross.isCompletionBoss("Tempoross"));
		assertTrue("the completion boss is also subordinate", tempoross.isSubordinate("Tempoross"));

		assertTrue(registry.byKey("wintertodt").isCompletionCount("subdued Wintertodt"));
		assertFalse("Giants' Foundry has no confirmed completion signal", registry.byKey("giants_foundry").hasCompletionSource());
		assertNull(registry.completionLabel("giants_foundry"));
		assertEquals("Games", registry.completionLabel("guardians_of_the_rift"));
		assertEquals("Chests", registry.completionLabel("barrows"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void aRegionCannotBelongToTwoActivities()
	{
		new StructuredActivityRegistry(Arrays.asList(
			StructuredActivityDefinition.builder("a", "A").region(LocationPolicy.EXCLUSIVE, 1).build(),
			StructuredActivityDefinition.builder("b", "B").region(LocationPolicy.EXCLUSIVE, 1).build()));
	}

	@Test
	public void defaultsIsShared()
	{
		assertSame(StructuredActivityRegistry.defaults(), StructuredActivityRegistry.defaults());
	}
}
