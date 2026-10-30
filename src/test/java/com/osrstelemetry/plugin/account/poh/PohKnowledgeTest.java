package com.osrstelemetry.plugin.account.poh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.account.Known;
import java.util.Arrays;
import java.util.HashSet;
import net.runelite.api.gameval.ObjectID;
import org.junit.Test;

/** House facilities: only a confirmed own-house visit answers; a friend's house or no visit stays unknown. */
public class PohKnowledgeTest
{
	@Test
	public void neverConfirmed_everythingUnknown_evenAfterSeeingAFriendsHouse()
	{
		PohKnowledge k = PohKnowledge.empty().withVisit(new HashSet<>(Arrays.asList("poh.jewellery_box.ornate", "poh.portal.falador")), false, 1000);
		assertEquals(Known.UNKNOWN, k.answer("poh.jewellery_box.ornate"));
		assertEquals(Known.UNKNOWN, k.answer("poh.portal.varrock"));
		assertTrue("kept apart, unused", k.getUnconfirmed().contains("poh.portal.falador"));
	}

	@Test
	public void confirmed_seenIsYes_notSeenIsNo_andJewelleryTiersAreCumulative()
	{
		PohKnowledge k = PohKnowledge.empty().withVisit(new HashSet<>(Arrays.asList("poh.jewellery_box.fancy", "poh.portal.falador")), true, 1000);
		assertEquals(Known.YES, k.answer("poh.portal.falador"));
		assertEquals(Known.NO, k.answer("poh.portal.varrock"));
		assertEquals(Known.YES, k.answer("poh.jewellery_box.basic"));
		assertEquals(Known.YES, k.answer("poh.jewellery_box.fancy"));
		assertEquals(Known.NO, k.answer("poh.jewellery_box.ornate"));
		assertEquals("not a house key", Known.UNKNOWN, k.answer("farming.spirit_tree.x"));
		PohKnowledge later = k.withVisit(new HashSet<>(Arrays.asList("poh.portal.varrock")), true, 2000);
		assertEquals("a removed portal disappears", Known.NO, later.answer("poh.portal.falador"));
		PohKnowledge round = new Gson().fromJson(new Gson().toJson(later), PohKnowledge.class).copy();
		assertEquals(Known.YES, round.answer("poh.portal.varrock"));
	}

	@Test
	public void objectsMapToFacilities_includingTheCombinedSpiritRing()
	{
		assertTrue(PohKnowledge.facilitiesOf(ObjectID.POH_PORTAL_MARBLE_CATHERBY).contains("poh.portal.catherby"));
		assertEquals(new HashSet<>(Arrays.asList("poh.fairy_ring", "poh.spirit_tree")), PohKnowledge.facilitiesOf(ObjectID.POH_SPIRIT_RING));
		assertTrue(PohKnowledge.facilitiesOf(ObjectID.POH_EXIT_PORTAL).isEmpty());
	}
}
