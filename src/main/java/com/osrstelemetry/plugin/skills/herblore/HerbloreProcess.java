package com.osrstelemetry.plugin.skills.herblore;

/**
 * How a Herblore recipe processes its inputs (internal only: never shown to
 * the player, whose words are the recipe's own name). The process decides
 * the companion's layout and its arithmetic -- not the XP, which never
 * identifies anything.
 */
public enum HerbloreProcess
{
	/** Grimy herb -> clean herb (Herblore XP). No batch, no doses. */
	CLEANING,
	/** Clean herb (or coral) + vial of water -> unfinished potion. The game gives NO Herblore XP for this. */
	UNFINISHED_POTION,
	/** Unfinished potion + secondary -> a 3-dose potion (or a fixed-dose one). */
	FINISHED_POTION,
	/** A finished potion + an ingredient per dose -> the upgraded potion of the SAME doses (stamina, anti-venom...). */
	POTION_UPGRADE,
	/** An extended potion: base potion + an ingredient per dose, doses preserved. */
	EXTENSION,
	/** A divine potion: super potion + crystal dust per dose, doses preserved. */
	DIVINE,
	/** Weapon poisons (not drinkable, not dose-normalized). */
	POISON,
	/** Barbarian mixes: a 2-dose potion + roe / caviar -> a 2-dose mix. */
	MIX,
	/** Anything else with its own shape (tars, Guthix rest, haemostatic poultices). */
	SPECIAL;

	/** Whether this process's output keeps the base potion's doses (variable output). */
	public boolean preservesDoses()
	{
		return this == POTION_UPGRADE || this == EXTENSION || this == DIVINE;
	}
}
