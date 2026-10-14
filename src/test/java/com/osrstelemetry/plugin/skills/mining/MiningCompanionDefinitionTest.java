package com.osrstelemetry.plugin.skills.mining;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.SkillCompanions;
import com.osrstelemetry.plugin.skills.SkillResource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** Mining's vocabulary: native lines, method identity from products, roles, captions and stockpile choices. */
public class MiningCompanionDefinitionTest
{
	private static final MiningCompanionDefinition DEF = MiningCompanionDefinition.INSTANCE;

	@Test
	public void gemLines_keepExactIdentity()
	{
		assertEquals(Integer.valueOf(ItemID.UNCUT_SAPPHIRE), MiningCompanionDefinition.gemFound("You just found a Sapphire!"));
		assertEquals(Integer.valueOf(ItemID.UNCUT_EMERALD), MiningCompanionDefinition.gemFound("You just found an Emerald!"));
		assertEquals(Integer.valueOf(ItemID.UNCUT_RUBY), MiningCompanionDefinition.gemFound("You just found a Ruby!"));
		assertEquals(Integer.valueOf(ItemID.UNCUT_DIAMOND), MiningCompanionDefinition.gemFound("You just found a Diamond!"));
		assertEquals(Integer.valueOf(ItemID.UNCUT_RED_TOPAZ), MiningCompanionDefinition.gemFound("You just mined a Red topaz!"));
		assertTrue(MiningCompanionDefinition.isGemMinedLine("You just mined an Opal!"));
		assertFalse(MiningCompanionDefinition.isGemMinedLine("You just found an Opal!"));
		assertNull(MiningCompanionDefinition.gemFound("You just found a Clue geode!"));
		assertNull(MiningCompanionDefinition.gemFound("You swing your pick at the rock."));
	}

	@Test
	public void successLines_nameTheMethod()
	{
		assertEquals(MiningMethod.IRON, MiningCompanionDefinition.successMethod("You manage to mine some iron."));
		assertEquals(MiningMethod.CLAY, MiningCompanionDefinition.successMethod("You manage to mine some soft clay."));
		assertEquals(MiningMethod.AMETHYST, MiningCompanionDefinition.successMethod("You manage to mine some amethyst."));
		assertEquals(MiningMethod.GRANITE, MiningCompanionDefinition.successMethod("You manage to quarry some granite."));
		assertNull(MiningCompanionDefinition.successMethod("You manage to mine some stardust."));
	}

	@Test
	public void storageAndCalcifiedLines()
	{
		assertTrue(MiningCompanionDefinition.isStorageRouted("You put it straight into your open gem bag."));
		assertTrue(MiningCompanionDefinition.isStorageRouted("You put it straight into your open gem sack."));
		assertTrue(MiningCompanionDefinition.isStorageFilled("You add the gems to your bag."));
		assertTrue(MiningCompanionDefinition.isWaterStart("Some cracks around the cave begin to ooze water."));
		assertTrue(MiningCompanionDefinition.isSlipping("Your pickaxe keeps slipping on the rocks as it's still wet from the last deposit."));
	}

	@Test
	public void methods_fromProductsAndRockNames()
	{
		assertEquals(MiningMethod.IRON, MiningMethod.producing(ItemID.IRON_ORE));
		assertEquals(MiningMethod.SANDSTONE, MiningMethod.producing(ItemID.ENAKH_SANDSTONE_LARGE));
		assertEquals(MiningMethod.CALCIFIED, MiningMethod.producing(ItemID.BLESSED_BONE_SHARD));
		assertNull("a gem names no single method", MiningMethod.producing(ItemID.UNCUT_RUBY));
		assertEquals(MiningMethod.GEM_ROCKS, MiningMethod.forRockName("Gem rocks"));
		assertEquals(MiningMethod.AMETHYST, MiningMethod.forRockName("Amethyst crystals"));
		assertNull(MiningMethod.forRockName("Rocks"));
		for (MiningMethod m : MiningMethod.values())
		{
			assertEquals(m, MiningMethod.fromKey(m.getKey()));
		}
	}

	@Test
	public void roles_primaryOres_secondaryGemsAndDeposits()
	{
		assertEquals(SkillResource.Role.PRIMARY, DEF.resource(ItemID.AMETHYST).getRole());
		assertEquals(SkillResource.Role.SECONDARY, DEF.resource(ItemID.UNCUT_SAPPHIRE).getRole());
		assertEquals("gem", DEF.resource(ItemID.UNCUT_SAPPHIRE).getGroup());
		assertEquals(SkillResource.Role.SECONDARY, DEF.resource(ItemID.CALCIFIED_DEPOSIT).getRole());
		assertTrue(DEF.resource(ItemID.BLESSED_BONE_SHARD).isStackable());
		assertNull("not Mining production", DEF.resource(ItemID.PAYDIRT));
		assertTrue(DEF.groupIsPrimary("gem_rocks", "gem"));
		assertFalse(DEF.groupIsPrimary("iron", "gem"));
	}

	@Test
	public void captions_areMethodAware_totalWhenMixed()
	{
		assertEquals("IRON MINED", DEF.productionCaption("iron", Collections.singletonList(DEF.resource(ItemID.IRON_ORE))));
		assertEquals("AMETHYST", DEF.productionCaption("amethyst", Collections.singletonList(DEF.resource(ItemID.AMETHYST))));
		assertEquals("GEMS MINED", DEF.productionCaption("gem_rocks", Arrays.asList(DEF.resource(ItemID.UNCUT_OPAL),
			DEF.resource(ItemID.UNCUT_JADE))));
		assertEquals("GRANITE", DEF.productionCaption("granite", Arrays.asList(DEF.resource(ItemID.ENAKH_GRANITE_TINY),
			DEF.resource(ItemID.ENAKH_GRANITE_MEDIUM))));
		assertEquals("TOTAL", DEF.productionCaption("coal", Arrays.asList(DEF.resource(ItemID.IRON_ORE), DEF.resource(ItemID.COAL))));
	}

	@Test
	public void stockpileChoices_areMethodAware()
	{
		List<SkillResource> amethyst = DEF.stockpileResources("amethyst");
		assertEquals(1, amethyst.size());
		assertEquals(ItemID.AMETHYST, amethyst.get(0).getItemId());
		assertEquals(8, DEF.stockpileResources("gem_rocks").size());
		assertEquals(2, DEF.stockpileResources("calcified").size());
		assertTrue(DEF.stockpileResources(null).isEmpty());
	}

	@Test
	public void tripRelevance_andRegistry()
	{
		assertTrue(DEF.tripMatters("iron"));
		assertTrue(DEF.tripMatters("gem_rocks"));
		assertFalse("stackable shards", DEF.tripMatters("calcified"));
		assertFalse(DEF.tripMatters(null));
		assertEquals(Skill.MINING, DEF.getSkill());
		assertEquals(DEF, SkillCompanions.forSkillKey("mining"));
		assertNull("every other skill keeps the plain session", SkillCompanions.forSkillKey("firemaking"));
		assertEquals("Shooting stars", DEF.methodLabel("shooting_stars"));
	}
}
