package com.osrstelemetry.plugin.skills.smithing;

/**
 * How a Smithing recipe turns its inputs into output, and what proves it
 * (see SmithingCompanionTracker). The process decides the evidence, never the
 * XP amount.
 */
public enum SmithingProcess
{
	/** Ore (+ coal) -> bar at a furnace (any furnace, ship furnaces included): bar gained + ore consumed + Smithing XP, no Magic XP. */
	SMELTING("smelted"),
	/** The Superheat Item spell: like smelting, with Magic XP on the same tick. */
	SUPERHEAT("smelted"),
	/** Ore on the Blast Furnace belt; production is only the bars actually COLLECTED from the dispenser. */
	BLAST_FURNACE("smelted"),
	/** Bars (or parts) -> a smithed item at an anvil, one or many per action. */
	ANVIL("smithed"),
	/** A bar poured into an ammo mould at a furnace; the cannonballs arrive a few ticks after the bar is used. */
	MOULD("made"),
	/** Smithed items combined into another (chainshot / incendiary cannonballs). */
	COMBINE("made"),
	/** The dragon forge (dragon metal sheets). */
	FORGE("smithed");

	private final String verb;

	SmithingProcess(String verb)
	{
		this.verb = verb;
	}

	/** Past-tense verb for History ("412 smelted", "28 smithed", "2,144 made"). */
	public String getVerb()
	{
		return verb;
	}

	/** Bars are the output (smelting by any process). */
	public boolean makesBars()
	{
		return this == SMELTING || this == SUPERHEAT || this == BLAST_FURNACE;
	}
}
