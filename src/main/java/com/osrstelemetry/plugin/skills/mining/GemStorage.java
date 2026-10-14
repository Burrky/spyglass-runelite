package com.osrstelemetry.plugin.skills.mining;

import com.osrstelemetry.plugin.skills.SkillStorage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;

/**
 * The gem bag family as Mining's SPECIALIZED STORAGE (the generic
 * {@link SkillStorage} configured for gems): which container the player
 * carries and whether it is open (an open one catches mined gems before they
 * reach the inventory). Contents are not exposed natively; see SkillStorage
 * for when they become known.
 *
 * Capacities: gem bag 60 of each of 5 gems (300), gem sack 60 of each of 8
 * (480) -- OSRS Wiki. Pouch / satchel / tote are not verified: no "/ cap" is
 * shown for them.
 */
public final class GemStorage
{
	public static final List<SkillStorage.Kind> KINDS = Collections.unmodifiableList(Arrays.asList(
		new SkillStorage.Kind("Gem bag", ItemID.GEM_BAG, ItemID.GEM_BAG_OPEN, 300, null),
		new SkillStorage.Kind("Gem sack", ItemID.GEM_SACK, ItemID.GEM_SACK_OPEN, 480, null),
		new SkillStorage.Kind("Gem pouch", ItemID.GEM_POUCH, ItemID.GEM_POUCH_OPEN, null, null),
		new SkillStorage.Kind("Gem satchel", ItemID.GEM_SATCHEL, ItemID.GEM_SATCHEL_OPEN, null, null),
		new SkillStorage.Kind("Gem tote", ItemID.GEM_TOTE, ItemID.GEM_TOTE_OPEN, null, null)));

	private GemStorage()
	{
	}

	public static SkillStorage create()
	{
		return new SkillStorage(KINDS);
	}
}
