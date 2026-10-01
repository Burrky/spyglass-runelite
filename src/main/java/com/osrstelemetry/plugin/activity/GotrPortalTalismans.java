package com.osrstelemetry.plugin.activity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

/**
 * Guardians of the Rift portal talismans, by native item id (resolved
 * RuneLite 1.13.1 gameval ItemID.GOTR_PORTAL_TALISMAN_*), one per altar.
 *
 * MECHANICS (OSRS Wiki, portal talisman pages; live log 2026-10-01): found
 * while crafting at an altar ("You discover a strange talisman during the
 * runecrafting process!"); stackable; used on that altar's guardian to enter
 * it even while its rift is closed ("The portal talisman shatters and you
 * find yourself at the altar it originated from") -- consumed unless the
 * guardian is active; cannot be kept after the game ends. Spyglass reads
 * which are HELD from the inventory container it already observes.
 */
public final class GotrPortalTalismans
{
	private GotrPortalTalismans()
	{
	}

	private static final Map<Integer, GotrAltar> BY_ITEM;

	static
	{
		Map<Integer, GotrAltar> m = new HashMap<>();
		m.put(ItemID.GOTR_PORTAL_TALISMAN_AIR, GotrAltar.AIR);
		m.put(ItemID.GOTR_PORTAL_TALISMAN_WATER, GotrAltar.WATER);
		m.put(ItemID.GOTR_PORTAL_TALISMAN_EARTH, GotrAltar.EARTH);
		m.put(ItemID.GOTR_PORTAL_TALISMAN_FIRE, GotrAltar.FIRE);
		m.put(ItemID.GOTR_PORTAL_TALISMAN_MIND, GotrAltar.MIND);
		m.put(ItemID.GOTR_PORTAL_TALISMAN_BODY, GotrAltar.BODY);
		m.put(ItemID.GOTR_PORTAL_TALISMAN_COSMIC, GotrAltar.COSMIC);
		m.put(ItemID.GOTR_PORTAL_TALISMAN_CHAOS, GotrAltar.CHAOS);
		m.put(ItemID.GOTR_PORTAL_TALISMAN_NATURE, GotrAltar.NATURE);
		m.put(ItemID.GOTR_PORTAL_TALISMAN_LAW, GotrAltar.LAW);
		m.put(ItemID.GOTR_PORTAL_TALISMAN_DEATH, GotrAltar.DEATH);
		m.put(ItemID.GOTR_PORTAL_TALISMAN_BLOOD, GotrAltar.BLOOD);
		BY_ITEM = Collections.unmodifiableMap(m);
	}

	/** The altar a portal talisman item leads to, or null for any other item. */
	public static GotrAltar altarOf(int itemId)
	{
		return BY_ITEM.get(itemId);
	}

	/** Altars with at least one portal talisman in these items (item id -> quantity). */
	public static Set<GotrAltar> heldIn(Map<Integer, Long> items)
	{
		Set<GotrAltar> held = EnumSet.noneOf(GotrAltar.class);
		if (items != null)
		{
			for (Map.Entry<Integer, Long> e : items.entrySet())
			{
				GotrAltar altar = e.getKey() == null ? null : BY_ITEM.get(e.getKey());
				if (altar != null && e.getValue() != null && e.getValue() > 0)
				{
					held.add(altar);
				}
			}
		}
		return held;
	}

	static int count()
	{
		return BY_ITEM.size();
	}
}
