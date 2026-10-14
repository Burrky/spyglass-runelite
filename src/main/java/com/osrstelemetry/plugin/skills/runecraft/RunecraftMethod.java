package com.osrstelemetry.plugin.skills.runecraft;

import java.util.Locale;
import net.runelite.api.gameval.ItemID;

/**
 * Runecraft METHODS: the production loop, identified from what a craft
 * actually produced (and, for the Arceuus loop, what it consumed) -- never
 * from XP amounts and never from routing (the Abyss is how a Nature run gets
 * to its altar, not a method).
 *
 * <ul>
 * <li>One rune per standard altar (the rune names the altar).</li>
 * <li>Combination runes (mist, dust, mud, smoke, steam, lava): one craft
 * turns essence + an elemental rune into the combination rune; the elemental
 * rune used is a catalyst, never "essence used".</li>
 * <li>OURANIA (ZMI): one craft yields several rune types -- the mix itself is
 * the evidence.</li>
 * <li>Blood / Soul from Arceuus dark essence fragments: the same rune as the
 * true altar, told apart by the input consumed.</li>
 * <li>Sunfire (Shrine of Ralos) and aether (Cosmic Altar) runes: transformations
 * with extra catalyst inputs, identified by their own output.</li>
 * </ul>
 *
 * Scar extracts are not a method: they are an optional INPUT of whichever
 * altar the player crafts at (one extract amplifies one essence), recorded
 * as consumed and seen in the actual output.
 *
 * Never this companion: Guardians of the Rift (a structured activity of its
 * own).
 */
public enum RunecraftMethod
{
	AIR("Air runes", "AIR RUNES", ItemID.AIRRUNE),
	MIND("Mind runes", "MIND RUNES", ItemID.MINDRUNE),
	WATER("Water runes", "WATER RUNES", ItemID.WATERRUNE),
	EARTH("Earth runes", "EARTH RUNES", ItemID.EARTHRUNE),
	FIRE("Fire runes", "FIRE RUNES", ItemID.FIRERUNE),
	BODY("Body runes", "BODY RUNES", ItemID.BODYRUNE),
	COSMIC("Cosmic runes", "COSMIC RUNES", ItemID.COSMICRUNE),
	CHAOS("Chaos runes", "CHAOS RUNES", ItemID.CHAOSRUNE),
	ASTRAL("Astral runes", "ASTRAL RUNES", ItemID.ASTRALRUNE),
	NATURE("Nature runes", "NATURE RUNES", ItemID.NATURERUNE),
	LAW("Law runes", "LAW RUNES", ItemID.LAWRUNE),
	DEATH("Death runes", "DEATH RUNES", ItemID.DEATHRUNE),
	/** The True Blood Altar (pure / daeyalt essence) -- told apart from the Arceuus fragment loop. */
	BLOOD("Blood runes \u2014 True Altar", "BLOOD RUNES", ItemID.BLOODRUNE),
	SOUL("Soul runes", "SOUL RUNES", ItemID.SOULRUNE),
	WRATH("Wrath runes", "WRATH RUNES", ItemID.WRATHRUNE),
	MIST("Mist runes", "MIST RUNES", ItemID.MISTRUNE),
	DUST("Dust runes", "DUST RUNES", ItemID.DUSTRUNE),
	MUD("Mud runes", "MUD RUNES", ItemID.MUDRUNE),
	SMOKE("Smoke runes", "SMOKE RUNES", ItemID.SMOKERUNE),
	STEAM("Steam runes", "STEAM RUNES", ItemID.STEAMRUNE),
	LAVA("Lava runes", "LAVA RUNES", ItemID.LAVARUNE),
	/** Varlamore: Shrine of Ralos -- essence + fire rune + sunfire splinter (twisted extracts optional). */
	SUNFIRE("Sunfire runes", "SUNFIRE RUNES", ItemID.SUNFIRERUNE),
	/** May 2025: Cosmic Altar -- essence + soul rune + aether catalyst, a bind that can fail without a binding necklace. */
	AETHER("Aether runes", "AETHER RUNES", ItemID.AETHERRUNE),
	/** Mixed output from one craft. */
	OURANIA("Ourania Altar", "RUNES", -1),
	/** Blood runes from Arceuus dark essence fragments. */
	BLOOD_ARCEUUS("Blood runes — Arceuus", "BLOOD RUNES", ItemID.BLOODRUNE),
	/** Soul runes from Arceuus dark essence fragments. */
	SOUL_ARCEUUS("Soul runes — Arceuus", "SOUL RUNES", ItemID.SOULRUNE);

	private final String label;
	private final String productionCaption;
	private final int rune;

	RunecraftMethod(String label, String productionCaption, int rune)
	{
		this.label = label;
		this.productionCaption = productionCaption;
		this.rune = rune;
	}

	public String getKey()
	{
		return name().toLowerCase(Locale.ROOT);
	}

	public String getLabel()
	{
		return label;
	}

	public String getProductionCaption()
	{
		return productionCaption;
	}

	/** The rune this method makes; -1 for a mixed-output method (Ourania). */
	public int getRune()
	{
		return rune;
	}

	public boolean isMixed()
	{
		return rune < 0;
	}

	/** A bind that can fail (combination runes, aether): attempts vs bound is observable from input vs output. */
	public boolean isBinding()
	{
		switch (this)
		{
			case MIST:
			case DUST:
			case MUD:
			case SMOKE:
			case STEAM:
			case LAVA:
			case AETHER:
				return true;
			default:
				return false;
		}
	}

	/** The Arceuus fragment loop (no bank trips; mining / venerating / chiselling phases). */
	public boolean isArceuus()
	{
		return this == BLOOD_ARCEUUS || this == SOUL_ARCEUUS;
	}

	public static RunecraftMethod fromKey(String key)
	{
		if (key == null)
		{
			return null;
		}
		for (RunecraftMethod m : values())
		{
			if (m.getKey().equals(key))
			{
				return m;
			}
		}
		return null;
	}

	/** The standard altar (or combination) method making {@code rune}; null for a non-rune. */
	public static RunecraftMethod standardFor(int rune)
	{
		for (RunecraftMethod m : values())
		{
			if (m.rune == rune && m != BLOOD_ARCEUUS && m != SOUL_ARCEUUS)
			{
				return m;
			}
		}
		return null;
	}
}
