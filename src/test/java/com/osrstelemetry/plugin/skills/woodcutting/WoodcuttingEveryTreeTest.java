package com.osrstelemetry.plugin.skills.woodcutting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillCompanions;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.SkillTracker;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Every supported tree, end to end through the tracker (its cut line, its
 * product, its method, its CURRENT TREE when it has one), plus Woodcutting's
 * vocabulary on the shared chassis: identity stays SKILLING/woodcutting, the
 * tree is sub-context; primary and secondary never mix.
 */
public class WoodcuttingEveryTreeTest
{
	private static final WoodcuttingCompanionDefinition DEF = WoodcuttingCompanionDefinition.INSTANCE;

	@Test
	public void everyTree_cutByItsOwnLine_producesItsOwnProduct_andNamesItself()
	{
		for (WoodcuttingMethod m : WoodcuttingMethod.values())
		{
			int product = m.getMainProduct();
			WoodcuttingSim s = new WoodcuttingSim();
			s.login();
			String name = m == WoodcuttingMethod.NORMAL ? "Tree" : m == WoodcuttingMethod.HOLLOW ? "Hollow tree" : m.getLabel();
			s.click(name, 1000 + m.ordinal(), m.ordinal());
			assertEquals(m.name(), Integer.valueOf(product), WoodcuttingCompanionDefinition.cutProduct(WoodcuttingSim.line(product)));
			for (int i = 0; i < 3; i++)
			{
				s.cut(product);
				s.steps(3);
			}
			assertEquals(m.name(), 3L, s.p(product));
			assertEquals(m.name(), m.getKey(), s.t.getMethodKey());
			assertSame(m, WoodcuttingMethod.producing(product));
			assertEquals(m.name(), m.hasFocus(), s.t.focus(s.now) != null);
			assertTrue(DEF.methodProduces(m.getKey(), product));
			assertEquals(m.getLabel(), DEF.methodLabel(m.getKey()));
			assertTrue(DEF.tripMatters(m.getKey()));
			assertFalse(DEF.stockpileResources(m.getKey()).isEmpty());
		}
	}

	@Test
	public void clickNames_hintTheRightTree()
	{
		assertSame(WoodcuttingMethod.NORMAL, WoodcuttingMethod.forTreeName("Tree"));
		assertSame(WoodcuttingMethod.NORMAL, WoodcuttingMethod.forTreeName("Dead tree"));
		assertSame(WoodcuttingMethod.NORMAL, WoodcuttingMethod.forTreeName("Evergreen tree"));
		assertSame(WoodcuttingMethod.OAK, WoodcuttingMethod.forTreeName("Oak tree"));
		assertSame(WoodcuttingMethod.WILLOW, WoodcuttingMethod.forTreeName("Willow"));
		assertSame(WoodcuttingMethod.YEW, WoodcuttingMethod.forTreeName("Yew tree"));
		assertSame(WoodcuttingMethod.MAGIC, WoodcuttingMethod.forTreeName("Magic tree"));
		assertSame(WoodcuttingMethod.REDWOOD, WoodcuttingMethod.forTreeName("Redwood tree"));
		assertSame(WoodcuttingMethod.TEAK, WoodcuttingMethod.forTreeName("Teak"));
		assertSame(WoodcuttingMethod.MAHOGANY, WoodcuttingMethod.forTreeName("Mahogany"));
		assertSame(WoodcuttingMethod.ARCTIC_PINE, WoodcuttingMethod.forTreeName("Arctic Pine"));
		assertSame(WoodcuttingMethod.ACHEY, WoodcuttingMethod.forTreeName("Achey Tree"));
		assertSame(WoodcuttingMethod.BLISTERWOOD, WoodcuttingMethod.forTreeName("Blisterwood Tree"));
		assertSame(WoodcuttingMethod.SULLIUSCEP, WoodcuttingMethod.forTreeName("Sulliuscep"));
		assertSame(WoodcuttingMethod.JATOBA, WoodcuttingMethod.forTreeName("Jatoba tree"));
		assertSame(WoodcuttingMethod.CAMPHOR, WoodcuttingMethod.forTreeName("Camphor tree"));
		assertSame(WoodcuttingMethod.IRONWOOD, WoodcuttingMethod.forTreeName("Ironwood tree"));
		assertSame(WoodcuttingMethod.ROSEWOOD, WoodcuttingMethod.forTreeName("Rosewood tree"));
		assertSame(WoodcuttingMethod.HOLLOW, WoodcuttingMethod.forTreeName("Hollow tree"));
		assertSame("a farming yew is still a yew", WoodcuttingMethod.YEW, WoodcuttingMethod.forTreeName("Yew tree (grown)"));
		assertNull("not a tree", WoodcuttingMethod.forTreeName("Bank booth"));
		assertNull(WoodcuttingMethod.forTreeName("Oakenshield"));
		assertNull(WoodcuttingMethod.forTreeName(null));
	}

	@Test
	public void infernalBurns_onlyTreesWhoseProductIsAFiremakingLog()
	{
		assertFalse(WoodcuttingMethod.SULLIUSCEP.isInfernalBurns());
		assertFalse(WoodcuttingMethod.BLISTERWOOD.isInfernalBurns());
		assertFalse(WoodcuttingMethod.HOLLOW.isInfernalBurns());
		assertTrue(WoodcuttingMethod.REDWOOD.isInfernalBurns());
		assertTrue(WoodcuttingMethod.ROSEWOOD.isInfernalBurns());
	}

	@Test
	public void everyProductBelongsToExactlyOneTree()
	{
		Set<Integer> seen = new HashSet<>();
		for (WoodcuttingMethod m : WoodcuttingMethod.values())
		{
			for (int p : m.getProducts())
			{
				assertTrue("duplicate product " + p, seen.add(p));
				SkillResource r = DEF.resource(p);
				assertNotNull(m.name(), r);
				assertEquals(SkillResource.Role.PRIMARY, r.getRole());
			}
		}
	}

	@Test
	public void secondaryResources_areNeverPrimary_andHaveTheirOwnSections()
	{
		for (SkillResource r : WoodcuttingCompanionDefinition.resources().values())
		{
			if (r.getRole() == SkillResource.Role.SECONDARY)
			{
				assertNotNull(r.getName(), r.getGroup());
				assertNull(r.getName(), WoodcuttingMethod.producing(r.getItemId()));
				assertFalse("never in a log basket", WoodcuttingCompanionDefinition.basketAccepts(r.getItemId()));
			}
		}
		List<SkillCompanionDefinition.Secondary> sections = DEF.secondaries();
		assertEquals("nest", sections.get(0).getGroup());
		assertEquals("bark", sections.get(1).getGroup());
		assertTrue(sections.get(2).isCompactLine());
		assertEquals("Leaves", DEF.groupTitle("leaf"));
		assertEquals("Fossils", DEF.groupTitle("fossil"));
		assertTrue(DEF.resource(ItemID.FORESTRY_CURRENCY).isStackable());
	}

	@Test
	public void basket_takesLogsOnly()
	{
		assertTrue(WoodcuttingCompanionDefinition.basketAccepts(ItemID.MAGIC_LOGS));
		assertTrue(WoodcuttingCompanionDefinition.basketAccepts(ItemID.ROSEWOOD_LOGS));
		assertFalse(WoodcuttingCompanionDefinition.basketAccepts(ItemID.BITTERCAP_MUSHROOM));
		assertFalse(WoodcuttingCompanionDefinition.basketAccepts(ItemID.HOLLOW_BARK));
		assertFalse(WoodcuttingCompanionDefinition.basketAccepts(ItemID.BIRD_NEST_SEEDS));
	}

	@Test
	public void nativeLines()
	{
		assertEquals(Integer.valueOf(ItemID.YEW_LOGS), WoodcuttingCompanionDefinition.cutProduct("You get some yew logs."));
		assertEquals(Integer.valueOf(ItemID.LOGS), WoodcuttingCompanionDefinition.cutProduct("You get some logs."));
		assertEquals(Integer.valueOf(ItemID.BITTERCAP_MUSHROOM), WoodcuttingCompanionDefinition.cutProduct("You get some mushrooms."));
		assertEquals(Integer.valueOf(ItemID.ARCTIC_PINE_LOG), WoodcuttingCompanionDefinition.cutProduct("You get an arctic pine log."));
		assertNull(WoodcuttingCompanionDefinition.cutProduct("You get some coins."));
		assertNull(WoodcuttingCompanionDefinition.cutProduct("You swing your axe at the tree."));
		assertEquals(Long.valueOf(12L), WoodcuttingCompanionDefinition.barkAwarded("You've been awarded 12 Anima-infused bark."));
		assertEquals(Long.valueOf(1250L), WoodcuttingCompanionDefinition.barkAwarded("You've been awarded 1,250 Anima-infused bark."));
		assertNull(WoodcuttingCompanionDefinition.barkAwarded("Anima-infused bark is a currency."));
		assertTrue(WoodcuttingCompanionDefinition.isNestFalling("A bird's nest falls out of the tree."));
		assertFalse(WoodcuttingCompanionDefinition.isNestFalling("You search the nest."));
	}

	@Test
	public void woodcutting_isAnAdapterOnTheSharedChassis()
	{
		assertTrue(SkillTracker.class.isAssignableFrom(WoodcuttingCompanionTracker.class));
		assertSame(DEF, SkillCompanions.forSkillKey("woodcutting"));
		assertEquals(Skill.WOODCUTTING, DEF.getSkill());
		assertEquals("Tree", DEF.methodTitle());
		assertEquals("auto-burned", DEF.autoTransformedVerb());
		assertEquals("Current tree", DEF.focusTitle());
		assertTrue(DEF.historyCountsPrimaryOnly());
		assertEquals("Rising Roots", DEF.occurrenceLabel("forestry:rising_roots"));
		assertNull(DEF.occurrenceLabel("forestry:nope"));
		assertEquals("LOGS CUT", DEF.productionCaption(null, java.util.Arrays.asList(DEF.resource(ItemID.YEW_LOGS),
			DEF.resource(ItemID.MAGIC_LOGS))));
		assertEquals("YEW LOGS", DEF.productionCaption("yew", java.util.Collections.singletonList(DEF.resource(ItemID.YEW_LOGS))));
		assertEquals("logs", DEF.rateNoun("redwood"));
		assertEquals("mushrooms", DEF.rateNoun("sulliuscep"));
		List<SkillResource> stock = DEF.stockpileResources("redwood");
		assertEquals(ItemID.REDWOOD_LOGS, stock.get(0).getItemId());
		assertEquals(ItemID.FORESTRY_CURRENCY, stock.get(stock.size() - 1).getItemId());
		assertTrue(WoodcuttingCompanionDefinition.BURNING_AXES.contains(ItemID.INFERNAL_AXE));
		assertFalse("an uncharged infernal axe burns nothing", WoodcuttingCompanionDefinition.BURNING_AXES.contains(ItemID.INFERNAL_AXE_EMPTY));
	}
}
