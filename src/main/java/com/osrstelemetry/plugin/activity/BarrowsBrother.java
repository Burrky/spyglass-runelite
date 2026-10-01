package com.osrstelemetry.plugin.activity;

import net.runelite.api.gameval.VarbitID;

/**
 * The six Barrows brothers and the game's own per-brother "killed this
 * run" varbits (the same ones RuneLite's core Barrows plugin reads for its
 * brothers overlay). The varbit is 1 once that brother has been defeated in
 * the current run and returns to 0 when the game starts a new run -- Spyglass
 * only mirrors it; it never decides by itself that a brother is cleared.
 *
 * {@link #getSchematicRow()}/{@link #getSchematicColumn()} place each mound on
 * a 3x3 grid by its real map position (north up), purely so the progress
 * block reads like the mound layout. It is not a map and not a route.
 */
public enum BarrowsBrother
{
	// Mound centres (RuneLite's BarrowsBrothers): Verac 3557,3298  Dharok 3575,3298
	// Torag 3553,3283  Ahrim 3566,3289  Guthan 3577,3283  Karil 3566,3275
	AHRIM("Ahrim", VarbitID.BARROWS_KILLED_AHRIM, 1, 1),
	DHAROK("Dharok", VarbitID.BARROWS_KILLED_DHAROK, 0, 2),
	GUTHAN("Guthan", VarbitID.BARROWS_KILLED_GUTHAN, 1, 2),
	KARIL("Karil", VarbitID.BARROWS_KILLED_KARIL, 2, 1),
	TORAG("Torag", VarbitID.BARROWS_KILLED_TORAG, 1, 0),
	VERAC("Verac", VarbitID.BARROWS_KILLED_VERAC, 0, 0);

	private final String displayName;
	private final int killedVarbit;
	private final int schematicRow;
	private final int schematicColumn;

	BarrowsBrother(String displayName, int killedVarbit, int schematicRow, int schematicColumn)
	{
		this.displayName = displayName;
		this.killedVarbit = killedVarbit;
		this.schematicRow = schematicRow;
		this.schematicColumn = schematicColumn;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public int getKilledVarbit()
	{
		return killedVarbit;
	}

	public int getSchematicRow()
	{
		return schematicRow;
	}

	public int getSchematicColumn()
	{
		return schematicColumn;
	}

	/** The brother whose "killed" varbit this is, or null. */
	public static BarrowsBrother forKilledVarbit(int varbitId)
	{
		for (BarrowsBrother brother : values())
		{
			if (brother.killedVarbit == varbitId)
			{
				return brother;
			}
		}
		return null;
	}
}
