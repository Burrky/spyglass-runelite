package com.osrstelemetry.plugin.skills.fishing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.SkillCompanionCollector;
import com.osrstelemetry.plugin.skills.SkillCompanions;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.SkillTracker;
import com.osrstelemetry.plugin.skills.mining.MiningCompanionDefinition;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.FishingSpot;
import org.junit.Test;

/**
 * Fishing's vocabulary and the ARCHITECTURE: Fishing is an adapter on the
 * shared skill chassis (no forked collector / ledger / goal system), its
 * methods are techniques (stable for multi-output spots), every catch maps
 * to exactly one method, and it never claims Mining's (or Tempoross's) items.
 */
public class FishingCompanionDefinitionTest
{
	private static final FishingCompanionDefinition DEF = FishingCompanionDefinition.INSTANCE;

	// ---- architecture reuse ------------------------------------------------------------------

	@Test
	public void fishing_isAnAdapterOnTheSharedChassis()
	{
		assertTrue(SkillCompanionCollector.class.isAssignableFrom(FishingCompanionCollector.class));
		assertTrue(SkillTracker.class.isAssignableFrom(FishingCompanionTracker.class));
		assertTrue(SkillCompanionCollector.class.isAssignableFrom(com.osrstelemetry.plugin.skills.mining.MiningCompanionCollector.class));
		assertTrue(SkillTracker.class.isAssignableFrom(com.osrstelemetry.plugin.skills.mining.MiningCompanionTracker.class));
		assertSame(DEF, SkillCompanions.forSkillKey("fishing"));
		assertSame(MiningCompanionDefinition.INSTANCE, SkillCompanions.forSkillKey("mining"));
		assertNull("every other skill keeps the plain session", SkillCompanions.forSkillKey("firemaking"));
		assertEquals(Skill.FISHING, DEF.getSkill());
	}

	@Test
	public void fishingAndMining_neverShareAResource()
	{
		for (Integer id : DEF.resources().keySet())
		{
			assertNull("fish " + id + " is not Mining's", MiningCompanionDefinition.INSTANCE.resource(id));
		}
		for (Integer id : MiningCompanionDefinition.resources().keySet())
		{
			assertNull("ore " + id + " is not Fishing's", DEF.resource(id));
		}
	}

	@Test
	public void structuredActivityFish_areNeverResources()
	{
		assertNull(DEF.resource(ItemID.TEMPOROSS_RAW_HARPOONFISH));
		assertNull(DEF.resource(ItemID.TEMPOROSS_HARPOONFISH));
		assertNull(FishingCompanionDefinition.caughtFish("You catch a harpoonfish."));
	}

	// ---- methods -----------------------------------------------------------------------------------

	@Test
	public void everyCatch_belongsToExactlyOneMethod()
	{
		for (Integer id : DEF.resources().keySet())
		{
			int owners = 0;
			for (FishingMethod m : FishingMethod.values())
			{
				for (int c : m.getCatches())
				{
					if (c == id)
					{
						owners++;
					}
				}
			}
			assertEquals("item " + id, 1, owners);
			assertNotNull(FishingMethod.catching(id));
		}
	}

	@Test
	public void spotAndOption_nameTheTechnique()
	{
		assertEquals(FishingMethod.FLY, FishingMethod.forSpot("SALMON", "Lure"));
		assertEquals(FishingMethod.PIKE, FishingMethod.forSpot("SALMON", "Bait"));
		assertEquals(FishingMethod.SMALL_NET, FishingMethod.forSpot("SHRIMP", "Small Net"));
		assertEquals(FishingMethod.BAIT, FishingMethod.forSpot("SHRIMP", "Bait"));
		assertEquals(FishingMethod.LOBSTER, FishingMethod.forSpot("LOBSTER", "Cage"));
		assertEquals(FishingMethod.HARPOON, FishingMethod.forSpot("LOBSTER", "Harpoon"));
		assertEquals(FishingMethod.SHARK, FishingMethod.forSpot("SHARK", "Harpoon"));
		assertEquals(FishingMethod.BIG_NET, FishingMethod.forSpot("SHARK", "Big Net"));
		assertEquals(FishingMethod.KARAMBWAN, FishingMethod.forSpot("KARAMBWAN", "Fish"));
		assertEquals(FishingMethod.BARBARIAN, FishingMethod.forSpot("BARB_FISH", "Use-rod"));
		assertNull("eels are deferred", FishingMethod.forSpot("INFERNAL_EEL", "Bait"));
		assertNull(FishingMethod.forSpot("SALMON", "Examine"));
		assertTrue(FishingMethod.isSupportedSpot("MINNOW"));
		assertFalse(FishingMethod.isSupportedSpot("SACRED_EEL"));
	}

	@Test
	public void everySpotNameUsed_isARealRuneLiteFishingSpot()
	{
		Set<String> native_ = new HashSet<>();
		for (FishingSpot s : FishingSpot.values())
		{
			native_.add(s.name());
		}
		for (String spot : Arrays.asList("SHRIMP", "SALMON", "LOBSTER", "SHARK", "MONKFISH", "KARAMBWAN", "KARAMBWANJI", "ANGLERFISH",
			"DARK_CRAB", "MINNOW", "BARB_FISH"))
		{
			assertTrue(spot, native_.contains(spot));
			assertTrue(spot, FishingMethod.isSupportedSpot(spot));
		}
		assertEquals("SHRIMP", FishingCompanionCollector.spotKind(FishingSpot.TUTORIAL_SHRIMP));
		assertEquals("LOBSTER", FishingCompanionCollector.spotKind(FishingSpot.ETCETERIA_LOBSTER));
		assertNull(FishingCompanionCollector.spotKind(null));
	}

	@Test
	public void stackableMethods_haveNoTrip_othersDo()
	{
		assertFalse(DEF.tripMatters("minnows"));
		assertFalse(DEF.tripMatters("karambwanji"));
		assertTrue(DEF.tripMatters("lobster"));
		assertTrue(DEF.tripMatters("karambwan"));
		assertTrue(DEF.tripMatters("barbarian"));
		assertFalse(DEF.tripMatters(null));
	}

	@Test
	public void labels_andUnknownKeys()
	{
		assertEquals("Fly fishing", DEF.methodLabel("fly"));
		assertEquals("Tuna & swordfish", DEF.methodLabel("harpoon"));
		assertEquals("Lava eels", DEF.methodLabel("lava_eels"));
		assertNull(DEF.methodLabel(null));
	}

	// ---- native lines ------------------------------------------------------------------------------

	@Test
	public void catchLines()
	{
		assertEquals(Integer.valueOf(ItemID.RAW_LOBSTER), FishingCompanionDefinition.caughtFish("You catch a lobster."));
		assertEquals(Integer.valueOf(ItemID.RAW_SHRIMP), FishingCompanionDefinition.caughtFish("You catch some shrimps."));
		assertEquals(Integer.valueOf(ItemID.RAW_ANCHOVIES), FishingCompanionDefinition.caughtFish("You catch some anchovies."));
		assertEquals(Integer.valueOf(ItemID.RAW_SHARK), FishingCompanionDefinition.caughtFish("You catch a shark!"));
		assertEquals(Integer.valueOf(ItemID.RAW_ANGLERFISH), FishingCompanionDefinition.caughtFish("You catch an Anglerfish."));
		assertEquals(Integer.valueOf(ItemID.TBWT_RAW_KARAMBWAN), FishingCompanionDefinition.caughtFish("You catch a karambwan."));
		assertEquals("karambwanji is never karambwan", Integer.valueOf(ItemID.TBWT_RAW_KARAMBWANJI),
			FishingCompanionDefinition.caughtFish("You catch 7 Karambwanji."));
		assertEquals("leaping trout is never trout", Integer.valueOf(ItemID.BRUT_SPAWNING_TROUT),
			FishingCompanionDefinition.caughtFish("You catch a leaping trout."));
		assertEquals(Integer.valueOf(ItemID.BRUT_STURGEON), FishingCompanionDefinition.caughtFish("You catch a leaping sturgeon."));
		assertEquals(Integer.valueOf(ItemID.RAW_DARK_CRAB), FishingCompanionDefinition.caughtFish("You catch a dark crab."));
		assertEquals(Integer.valueOf(ItemID.MINNOW), FishingCompanionDefinition.caughtFish("You catch some minnows!"));
		assertNull(FishingCompanionDefinition.caughtFish("You attempt to catch a lobster."));
		assertNull(FishingCompanionDefinition.caughtFish("You catch a slimy eel."));
		assertNull(FishingCompanionDefinition.caughtFish(null));
	}

	@Test
	public void barrelAndFlyingFishLines()
	{
		assertTrue(FishingCompanionDefinition.isBarrelFull("Your fish barrel is full."));
		assertFalse(FishingCompanionDefinition.isBarrelFull("You catch a lobster."));
		assertTrue(FishingCompanionDefinition.isFlyingFish("A flying fish jumps up and eats some of your minnows!"));
		assertFalse(FishingCompanionDefinition.isFlyingFish("You catch some minnows!"));
	}

	// ---- wording / targets ------------------------------------------------------------------------

	@Test
	public void captions_areNatural()
	{
		assertEquals("LOBSTERS", DEF.productionCaption("lobster", res(ItemID.RAW_LOBSTER)));
		assertEquals("KARAMBWAN", DEF.productionCaption("karambwan", res(ItemID.TBWT_RAW_KARAMBWAN)));
		assertEquals("FISH CAUGHT", DEF.productionCaption("fly", res(ItemID.RAW_TROUT, ItemID.RAW_SALMON)));
		assertEquals("one fly-fishing species is still FISH CAUGHT", "FISH CAUGHT", DEF.productionCaption("fly", res(ItemID.RAW_TROUT)));
		assertEquals("mixed methods", "FISH CAUGHT", DEF.productionCaption("lobster", res(ItemID.RAW_LOBSTER, ItemID.RAW_SHARK)));
		assertEquals("caught", DEF.producedVerb());
		assertEquals("Caught", DEF.producedTitle());
		assertEquals("lobsters", DEF.rateNoun("lobster"));
		assertTrue(DEF.methodProduces("fly", ItemID.RAW_SALMON));
		assertFalse(DEF.methodProduces("fly", ItemID.RAW_LOBSTER));
		assertFalse("Mining keeps its whole-session mixed rate", MiningCompanionDefinition.INSTANCE.methodProduces("iron", ItemID.IRON_ORE));
	}

	@Test
	public void stockpile_isTheMethodsCatch_neverItsBait()
	{
		List<SkillResource> fly = DEF.stockpileResources("fly");
		assertEquals(2, fly.size());
		List<SkillResource> kbwan = DEF.stockpileResources("karambwan");
		assertEquals(1, kbwan.size());
		assertEquals(ItemID.TBWT_RAW_KARAMBWAN, kbwan.get(0).getItemId());
		assertTrue(DEF.stockpileResources(null).isEmpty());
	}

	@Test
	public void barrelLine_onlyForBarrelMethods()
	{
		assertTrue(DEF.storageRelevant("lobster", Collections.<Integer>emptySet()));
		assertFalse(DEF.storageRelevant("minnows", Collections.<Integer>emptySet()));
		assertTrue(DEF.storageCatchesMain("shark"));
		assertFalse(DEF.storageCatchesMain("karambwanji"));
		assertTrue(DEF.storageTooltip().contains("barrel"));
		assertTrue(MiningCompanionDefinition.INSTANCE.storageTooltip().startsWith("Gems"));
		assertEquals("Mine something first to pick what to stockpile", MiningCompanionDefinition.INSTANCE.stockpileEmptyHint());
	}

	private static List<SkillResource> res(int... ids)
	{
		SkillResource[] out = new SkillResource[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			out[i] = DEF.resource(ids[i]);
		}
		return Arrays.asList(out);
	}
}
