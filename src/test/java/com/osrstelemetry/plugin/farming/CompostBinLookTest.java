package com.osrstelemetry.plugin.farming;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pass 31: each bin state's look from the game's own bin models; the picture shows the compost, the step the lid. */
public class CompostBinLookTest
{
	static CompostBin.Def bin(String id)
	{
		return CompostBin.ALL.stream().filter(d -> d.getBinId().equals(id)).findFirst().get();
	}

	@Test
	public void emptyOpen_fillingWithContents_rottingClosed_readyOpenAndFull()
	{
		CompostBin.Def fal = bin("bin.falador");
		assertArrayEquals(new int[]{7838, 7845, 7839}, CompostBinLook.of(fal, CompostBin.decode(0, false)).getModels());
		assertArrayEquals(new int[]{7838, 7845, 7842}, CompostBinLook.of(fal, CompostBin.decode(7, false)).getModels());
		assertArrayEquals(new int[]{7838, 7844}, CompostBinLook.of(fal, CompostBin.decode(31, false)).getModels());
		assertArrayEquals(new int[]{7838, 7845, 7840}, CompostBinLook.of(fal, CompostBin.decode(20, false)).getModels());
		// Rotted (still closed): the picture is the compost, ready; the step still opens the bin first.
		CompostBin.State rotted = CompostBin.decode(85, false);
		assertArrayEquals(new int[]{7838, 7845, 7840}, CompostBinLook.of(fal, rotted).getModels());
		CompostBin.Task t = CompostBin.task(fal, new CompostBin.Seen(85, 0L, 0L), CompostBin.Upgrade.NONE, 1000L);
		assertTrue(t.label, t.label.toLowerCase(java.util.Locale.ROOT).contains("open"));
		// Never seen: no look at all (a neutral mark, never an empty bin).
		assertNull(CompostBinLook.of(fal, null));
	}

	@Test
	public void contentsColour_followsWhatIsInIt_andPortPhasmatysIsDark()
	{
		CompostBin.Def fal = bin("bin.falador");
		CompostBinLook compost = CompostBinLook.of(fal, CompostBin.decode(20, false));
		assertArrayEquals(new short[]{8724}, compost.getRecolourFrom());
		assertArrayEquals(new short[]{7581}, compost.getRecolourTo());
		assertEquals("supercompost keeps the model's own colour", 0, CompostBinLook.of(fal, CompostBin.decode(55, false)).getRecolourFrom().length);
		assertEquals(3, CompostBinLook.of(bin("bin.morytania"), CompostBin.decode(0, false)).getRecolourFrom().length);
		// The Farming Guild's big bin: the same parts.
		assertArrayEquals(new int[]{7838, 7845, 7839}, CompostBinLook.of(bin("bin.farming_guild"), CompostBin.decode(0, true)).getModels());
	}
}
