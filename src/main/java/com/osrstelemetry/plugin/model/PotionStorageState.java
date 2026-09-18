package com.osrstelemetry.plugin.model;

import java.util.List;
import lombok.Data;

/**
 * Extraction relies on the same signals RuneLite's own BankPlugin
 * uses: ScriptID.POTIONSTORE_BUILD/DOSE_CHANGE,
 * InterfaceID.Bankmain.POTIONSTORE_ITEMS, EnumID.POTIONSTORE_POTIONS/
 * UNFINISHED_POTIONS, the "groups of five, item at i+1, dose text at
 * i+3" widget layout, and the "Doses: N" / "Quantity: N" text format.
 *
 * itemId/itemName describe the CURRENTLY DISPLAYED withdrawal-dose
 * variant for that potion type (e.g. "Super strength(4)"), not a
 * dose-independent potion identity — the real game doesn't have one.
 * totalDoses is the raw dose count, which is the actual "how much do
 * I own" signal independent of which variant happens to be displayed.
 * withdrawalDoseCount records which dose-variant (1-4) itemId
 * corresponds to, mirroring the real plugin's own withdrawDoses
 * calculation (including its fallback-to-4-if-unmatched quirk, kept
 * faithfully rather than "fixed" since it's their behavior being
 * mirrored, not this project's own design).
 */
@Data
public class PotionStorageState
{
	@Data
	public static class PotionStorageItem
	{
		private final int itemId;
		private final String itemName;
		private final int totalDoses;
		private final int withdrawalDoseCount;
	}

	private List<PotionStorageItem> items;
	private String lastObservedAt;
	private final boolean continuouslyObservable = false;
}
