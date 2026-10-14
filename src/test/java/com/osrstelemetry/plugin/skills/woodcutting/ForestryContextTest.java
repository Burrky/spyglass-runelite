package com.osrstelemetry.plugin.skills.woodcutting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.SkillCompanionView;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import org.junit.Test;

/**
 * FORESTRY as context of the Woodcutting session: EVERY event is recognised
 * from its own native NPCs / objects, shown only while it runs, counted once
 * when it ends -- only if the player took part (bark awarded, or the event's
 * own other-skill XP) -- and never becomes a session of its own.
 */
public class ForestryContextTest
{
	private final ForestryContext f = new ForestryContext();
	private long now = 1_000_000L;

	private void advance(long millis)
	{
		now += millis;
		f.step(now);
	}

	/** One representative spawn per event (an NPC where the event has one, else an object). */
	private void spawn(ForestryEvent e)
	{
		switch (e)
		{
			case RISING_ROOTS:
				f.objectSpawned(ObjectID.GATHERING_EVENT_RISING_ROOTS, now);
				break;
			case STRUGGLING_SAPLING:
				f.objectSpawned(ObjectID.GATHERING_EVENT_SAPLING_2X2, now);
				break;
			case FLOWERING_BUSH:
				f.npcSpawned(NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_COL04, now);
				break;
			case LEPRECHAUN:
				f.npcSpawned(NpcID.GATHERING_EVENT_WOODCUTTING_LEPRECHAUN, now);
				break;
			case BEEHIVE:
				f.npcSpawned(NpcID.GATHERING_EVENT_BEES_BEEBOX_2, now);
				break;
			case FRIENDLY_ENT:
				f.npcSpawned(NpcID.GATHERING_EVENT_ENTLINGS_NPC_01, now);
				break;
			case POACHERS:
				f.npcSpawned(NpcID.GATHERING_EVENT_POACHERS_FOX_OUTDOORS, now);
				break;
			case PHEASANT_CONTROL:
				f.npcSpawned(NpcID.GATHERING_EVENT_PHEASANT_FORESTER, now);
				break;
			case ENCHANTMENT_RITUAL:
				f.npcSpawned(NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_DRYAD, now);
				break;
			default:
				throw new AssertionError(e);
		}
	}

	private void despawn(ForestryEvent e)
	{
		switch (e)
		{
			case RISING_ROOTS:
				f.objectDespawned(ObjectID.GATHERING_EVENT_RISING_ROOTS, now);
				break;
			case STRUGGLING_SAPLING:
				f.objectDespawned(ObjectID.GATHERING_EVENT_SAPLING_2X2, now);
				break;
			case FLOWERING_BUSH:
				f.npcDespawned(NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_COL04, now);
				break;
			case LEPRECHAUN:
				f.npcDespawned(NpcID.GATHERING_EVENT_WOODCUTTING_LEPRECHAUN, now);
				break;
			case BEEHIVE:
				f.npcDespawned(NpcID.GATHERING_EVENT_BEES_BEEBOX_2, now);
				break;
			case FRIENDLY_ENT:
				f.npcDespawned(NpcID.GATHERING_EVENT_ENTLINGS_NPC_01, now);
				break;
			case POACHERS:
				f.npcDespawned(NpcID.GATHERING_EVENT_POACHERS_FOX_OUTDOORS, now);
				break;
			case PHEASANT_CONTROL:
				f.npcDespawned(NpcID.GATHERING_EVENT_PHEASANT_FORESTER, now);
				break;
			case ENCHANTMENT_RITUAL:
				f.npcDespawned(NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_DRYAD, now);
				break;
			default:
				throw new AssertionError(e);
		}
	}

	@Test
	public void everyEvent_runsWhileItsEntitiesExist_andCountsOnceWhenTakenPart()
	{
		for (ForestryEvent e : ForestryEvent.values())
		{
			spawn(e);
			advance(5_000);
			SkillCompanionView.EventView v = f.view(now);
			assertNotNull(e.name(), v);
			assertEquals(e.getLabel(), v.getName());
			assertEquals(5_000L, v.getElapsedMillis());
			f.barkAwarded(14, now);
			advance(10_000);
			assertEquals("14", f.view(now).getFields().get(0).getValue());
			despawn(e);
			advance(1_000);
			assertNotNull("still in its end grace", f.view(now));
			advance(ForestryContext.END_GRACE_MILLIS);
			assertNull(e.name() + " ended", f.view(now));
			Map<String, Long> occ = f.drainOccurrences();
			assertEquals(e.name(), Long.valueOf(1L), occ.get(e.occurrenceKey()));
			assertEquals(1, occ.size());
		}
	}

	@Test
	public void standingNextToAnEvent_neverCounts()
	{
		for (ForestryEvent e : ForestryEvent.values())
		{
			spawn(e);
			advance(20_000);
			despawn(e);
			advance(ForestryContext.END_GRACE_MILLIS + 1);
		}
		assertTrue(f.drainOccurrences().isEmpty());
	}

	@Test
	public void eventSkillXp_isParticipation_andCountsWhereItIsOneForOne()
	{
		spawn(ForestryEvent.POACHERS);
		advance(600);
		for (int i = 0; i < 6; i++)
		{
			f.otherSkillXp("HUNTER", now);
		}
		f.otherSkillXp("THIEVING", now);
		SkillCompanionView.EventView v = f.view(now);
		assertEquals("TRAPS", v.getFields().get(1).getCaption());
		assertEquals("6", v.getFields().get(1).getValue());
		f.onChat("Unfortunately you weren't able to save the fox from the poachers...", now);
		assertEquals("Fox lost to the poachers", f.view(now).getLines().get(0).getText());
		despawn(ForestryEvent.POACHERS);
		advance(ForestryContext.END_GRACE_MILLIS + 1);
		assertEquals(Long.valueOf(1L), f.drainOccurrences().get("forestry:poachers"));
	}

	@Test
	public void pheasantEggs_fromThievingXp()
	{
		spawn(ForestryEvent.PHEASANT_CONTROL);
		f.objectSpawned(ObjectID.GATHERING_EVENT_PHEASANT_NEST01, now);
		advance(600);
		f.otherSkillXp("THIEVING", now);
		f.otherSkillXp("THIEVING", now);
		assertEquals("EGGS", f.view(now).getFields().get(1).getCaption());
		assertEquals("2", f.view(now).getFields().get(1).getValue());
	}

	@Test
	public void eventsWithoutAOneForOneAction_neverInventACount()
	{
		for (ForestryEvent e : new ForestryEvent[]{ForestryEvent.STRUGGLING_SAPLING, ForestryEvent.BEEHIVE, ForestryEvent.FRIENDLY_ENT,
			ForestryEvent.RISING_ROOTS, ForestryEvent.ENCHANTMENT_RITUAL, ForestryEvent.FLOWERING_BUSH, ForestryEvent.LEPRECHAUN})
		{
			assertNull(e.name(), e.getCounterCaption());
		}
	}

	@Test
	public void contextSkills_onlyWhileTheirEventRuns()
	{
		assertTrue(f.contextSkills(now).isEmpty());
		spawn(ForestryEvent.STRUGGLING_SAPLING);
		spawn(ForestryEvent.BEEHIVE);
		Set<String> skills = f.contextSkills(now);
		assertEquals(new HashSet<>(java.util.Arrays.asList("FARMING", "CONSTRUCTION")), skills);
		despawn(ForestryEvent.STRUGGLING_SAPLING);
		despawn(ForestryEvent.BEEHIVE);
		advance(ForestryContext.END_GRACE_MILLIS + 1);
		assertTrue(f.contextSkills(now).isEmpty());
		spawn(ForestryEvent.RISING_ROOTS);
		assertTrue("Rising Roots hands out only Woodcutting XP", f.contextSkills(now).isEmpty());
	}

	@Test
	public void saplingMulchOrder_saved_andWithering_fromNativeEvidence()
	{
		spawn(ForestryEvent.STRUGGLING_SAPLING);
		f.onChat("The sapling seems to love splintered bark as the second ingredient", now);
		f.onChat("The sapling seems to love Wild mushrooms as the first ingredient.", now);
		assertEquals("Mulch: 1 Wild mushrooms • 2 Splintered bark • 3 ?", f.view(now).getLines().get(0).getText());
		f.objectSpawned(ObjectID.GATHERING_EVENT_SAPLING_WITHERING_2X2, now);
		assertEquals("Sapling withering", f.view(now).getLines().get(1).getText());
		f.objectSpawned(ObjectID.GATHERING_EVENT_SAPLING_SAVED_2X2, now);
		assertEquals("Sapling saved", f.view(now).getLines().get(1).getText());
		assertTrue(f.leafRewardWindow(now));
	}

	@Test
	public void risingRoots_animaRootLine_whileItIsUp()
	{
		spawn(ForestryEvent.RISING_ROOTS);
		assertTrue(f.view(now).getLines().isEmpty());
		f.objectSpawned(ObjectID.GATHERING_EVENT_RISING_ROOTS_SPECIAL, now);
		assertEquals("Anima-infused root is up", f.view(now).getLines().get(0).getText());
		f.objectDespawned(ObjectID.GATHERING_EVENT_RISING_ROOTS_SPECIAL, now);
		assertTrue(f.view(now).getLines().isEmpty());
		assertNotNull("the ordinary roots are still there", f.view(now));
	}

	@Test
	public void notEligible_line()
	{
		spawn(ForestryEvent.ENCHANTMENT_RITUAL);
		f.onChat("As you weren't chopping near the event when it started, you aren't eligible for full rewards.", now);
		assertEquals("Not eligible for full rewards", f.view(now).getLines().get(0).getText());
	}

	@Test
	public void npcChangingForm_orSceneReload_neverSplitsOneEvent()
	{
		spawn(ForestryEvent.FRIENDLY_ENT);
		f.barkAwarded(5, now);
		// Entling pruned: the NPC changes form (despawn old id, spawn new id on the same tick).
		f.npcDespawned(NpcID.GATHERING_EVENT_ENTLINGS_NPC_01, now);
		f.npcSpawned(NpcID.GATHERING_EVENT_ENTLINGS_NPC_01_PRUNED, now);
		advance(600);
		f.sceneReloading(now);
		advance(600);
		f.npcSpawned(NpcID.GATHERING_EVENT_ENTLINGS_NPC_01_PRUNED, now);
		advance(10_000);
		assertNotNull(f.view(now));
		assertTrue(f.drainOccurrences().isEmpty());
		f.npcDespawned(NpcID.GATHERING_EVENT_ENTLINGS_NPC_01_PRUNED, now);
		advance(ForestryContext.END_GRACE_MILLIS + 1);
		assertEquals(Long.valueOf(1L), f.drainOccurrences().get("forestry:friendly_ent"));
		assertTrue("a participated ent may still drop its nest", f.entNestWindow(now));
	}

	@Test
	public void staleEvent_withoutAnySign_endsByItself()
	{
		spawn(ForestryEvent.LEPRECHAUN);
		advance(ForestryContext.MAX_EVENT_MILLIS + 60_000);
		advance(ForestryContext.MAX_EVENT_MILLIS / 2 + 1);
		assertNull(f.view(now));
	}

	@Test
	public void npcAndObjectIdSpaces_neverMix()
	{
		for (ForestryEvent e : ForestryEvent.values())
		{
			for (int id : e.npcIds())
			{
				assertEquals(e, ForestryEvent.forNpc(id));
			}
			for (int id : e.objectIds())
			{
				assertEquals(e, ForestryEvent.forObject(id));
			}
			assertFalse(e.npcIds().isEmpty() && e.objectIds().isEmpty());
			assertEquals(e, ForestryEvent.fromOccurrenceKey(e.occurrenceKey()));
		}
		assertNull(ForestryEvent.forNpc(1));
		assertNull(ForestryEvent.forObject(1));
	}
}
