package com.osrstelemetry.plugin.farming;

import java.util.Arrays;

/**
 * How a compost bin looks in the game for a state (pass 31): the bin object's own models and recolours, read from the game
 * cache's location definitions (osrs-dumps {@code dump.loc}: the {@code compost_bin_*} / {@code compost_bigbin_*} multiloc
 * children of each bin; every bin shares the same three parts). The models are drawn by the client into a small picture
 * ({@code ui.ModelIcon}); nothing here is an image.
 *
 * <ul>
 *   <li>Base (the box): model 7838.</li>
 *   <li>Lid: open 7845, closed 7844 (rotting, and rotted until the player opens it).</li>
 *   <li>Contents (open bins only): empty 7839, some 7842, full 7840; tomatoes 7843 / 7841.</li>
 *   <li>Contents colour (recolour of the contents' 8724): compostable 11922, supercompostable 12827, compost 7581;
 *   supercompost and ultracompost keep the model's own colour; rotten tomatoes also 2979 → 3742 and 22430 → 10670.</li>
 *   <li>Port Phasmatys' bin is the dark variant: 7580 → 12815, 7700 → 12170, 7461 → 11793.</li>
 * </ul>
 *
 * <p>The colours are the cache's own packed HSL. The dump writes recolours as 15-bit RGB of the HSL (brightness 0.95):
 * its 8418, 7425, 9571, 10532 and the dark bin's 10500 / 8386 / 12614 → 5313 / 4256 / 6370 are those, matched back to the
 * HSL values the models use (verified on the scimitar recolours, whose dump value 16846 is the blade's HSL 61, and on all
 * five bin sources against models 7838–7845 from cache 2752 on openrs2, 2026-10-28).
 *
 * <p>The picture shows the compost's state, not the lid: a rotted bin (closed until opened) shows the compost it holds, ready
 * (open and full); the next step still says to open it first (see {@link CompostBin#task}). A bin never seen has no look:
 * the card shows a neutral "not seen" mark, never an empty bin.
 */
public final class CompostBinLook
{
	public static final int BASE = 7838;
	public static final int LID_OPEN = 7845;
	public static final int LID_CLOSED = 7844;
	public static final int CONTENTS_EMPTY = 7839;
	public static final int CONTENTS_SOME = 7842;
	public static final int CONTENTS_FULL = 7840;
	public static final int TOMATOES_SOME = 7843;
	public static final int TOMATOES_FULL = 7841;

	static final short CONTENTS_COLOUR = 8724;
	static final short COMPOSTABLE = 11922;
	static final short SUPERCOMPOSTABLE = 12827;
	static final short COMPOST = 7581;
	/** The location definitions' {@code ambient=25} on every compost bin child. */
	public static final int AMBIENT = 25;

	/** Port Phasmatys' bin (object 7838, {@code farming_compost_bin_3}): the dark wood. */
	static final int DARK_BIN_OBJECT = 7838;
	private static final short[][] DARK = {{7580, 12815}, {7700, 12170}, {7461, 11793}};

	private final int[] models;
	private final short[] from;
	private final short[] to;

	private CompostBinLook(int[] models, short[][] recolours)
	{
		this.models = models;
		this.from = new short[recolours.length];
		this.to = new short[recolours.length];
		for (int i = 0; i < recolours.length; i++)
		{
			from[i] = recolours[i][0];
			to[i] = recolours[i][1];
		}
	}

	public int[] getModels()
	{
		return models.clone();
	}

	public short[] getRecolourFrom()
	{
		return from.clone();
	}

	public short[] getRecolourTo()
	{
		return to.clone();
	}

	/** A key for the drawn picture (equal looks draw once). */
	public String key()
	{
		return Arrays.toString(models) + Arrays.toString(from) + Arrays.toString(to);
	}

	@Override
	public String toString()
	{
		return key();
	}

	/** The look of this bin in this state; null when it was never seen or the value is not recognised. */
	public static CompostBinLook of(CompostBin.Def d, CompostBin.State st)
	{
		if (d == null || st == null)
		{
			return null;
		}
		java.util.List<short[]> recol = new java.util.ArrayList<>();
		int[] models;
		switch (st.phase)
		{
			case EMPTY:
				models = new int[]{BASE, LID_OPEN, CONTENTS_EMPTY};
				break;
			case FILLING:
				if (st.contents == CompostBin.Contents.ROTTEN_TOMATOES)
				{
					models = new int[]{BASE, LID_OPEN, TOMATOES_SOME};
					recol.add(new short[]{CONTENTS_COLOUR, COMPOSTABLE});
				}
				else
				{
					// Being filled: open, with contents (some), whatever the count; full and open reads as "ready".
					models = new int[]{BASE, LID_OPEN, CONTENTS_SOME};
					recol.add(new short[]{CONTENTS_COLOUR, st.contents == CompostBin.Contents.SUPERCOMPOST ? SUPERCOMPOSTABLE : COMPOSTABLE});
				}
				break;
			case ROTTING:
				models = new int[]{BASE, LID_CLOSED};
				break;
			case ROTTED:
				// The compost is done: the picture shows it ready (open and full), the step says to open the bin first.
				models = product(st.contents, true, recol);
				break;
			case READY:
				models = product(st.contents, true, recol); // compost to take: open and full, however much is left
				break;
			default:
				return null;
		}
		if (d.getObjectId() == DARK_BIN_OBJECT)
		{
			recol.addAll(Arrays.asList(DARK));
		}
		return new CompostBinLook(models, recol.toArray(new short[0][]));
	}

	private static int[] product(CompostBin.Contents c, boolean full, java.util.List<short[]> recol)
	{
		if (c == CompostBin.Contents.ROTTEN_TOMATOES)
		{
			recol.add(new short[]{CONTENTS_COLOUR, COMPOSTABLE});
			recol.add(new short[]{2979, 3742});
			recol.add(new short[]{22430, 10670});
			return new int[]{BASE, LID_OPEN, full ? TOMATOES_FULL : TOMATOES_SOME};
		}
		if (c == CompostBin.Contents.COMPOST)
		{
			recol.add(new short[]{CONTENTS_COLOUR, COMPOST});
		}
		return new int[]{BASE, LID_OPEN, full ? CONTENTS_FULL : CONTENTS_SOME};
	}
}
