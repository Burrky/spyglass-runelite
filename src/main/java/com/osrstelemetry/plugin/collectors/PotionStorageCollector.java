package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.model.PotionStorageState;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Read-only. Never interacts with the potion storage interface —
 * only reads widget state RuneLite already builds for its own display
 * purposes, the same way the built-in Bank plugin does for its value
 * overlay. See PotionStorageState javadoc for the verification trail
 * behind every constant used here.
 *
 * EnumComposition/EnumID confirmed via direct fetches of their current
 * source files — both declared `package net.runelite.api;` (not
 * `.gameval`), matching BankPlugin.java's own import lines.
 * EnumID.POTIONSTORE_POTIONS (= 4826) and
 * EnumID.POTIONSTORE_UNFINISHED_POTIONS (= 4829), the two constants
 * this collector actually uses, are both confirmed present verbatim.
 * Client.getEnum(int) (`EnumComposition getEnum(int id);`) and
 * Client.getWidget(int) (`Widget getWidget(@Component int componentId);`
 * — single packed-component-id overload, matching this collector's
 * `client.getWidget(InterfaceID.Bankmain.POTIONSTORE_ITEMS)` call, not
 * the two-arg groupId/childId overload) are both confirmed via a
 * direct fetch of the current Client.java source. No longer an
 * inference from call sites — this is a direct read of each symbol's
 * defining source, the same evidence tier as this project's other
 * confirmed constants.
 */
public class PotionStorageCollector
{
	private final Client client;
	private final ItemManager itemManager;
	private final LocalStateStore store;
	private final OsrsTelemetryConfig config;

	@Inject
	public PotionStorageCollector(Client client, ItemManager itemManager, LocalStateStore store, OsrsTelemetryConfig config)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.store = store;
		this.config = config;
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (!config.collectPotionStorage())
		{
			return;
		}

		int scriptId = event.getScriptId();
		if (scriptId == ScriptID.POTIONSTORE_BUILD || scriptId == ScriptID.POTIONSTORE_DOSE_CHANGE)
		{
			captureNow();
		}
	}

	/**
	 * Mirrors BankPlugin#getPotionStoragePrice()'s data-reading half
	 * exactly (the price-calculation half is irrelevant to telemetry
	 * and not reproduced) — same enum-driven potionMap construction,
	 * same "groups of five" widget layout, same text parsing, same
	 * withdrawDoses fallback-to-4 quirk.
	 */
	private void captureNow()
	{
		Map<Integer, EnumComposition> potionMap = new HashMap<>();

		EnumComposition potionStorePotions = client.getEnum(EnumID.POTIONSTORE_POTIONS);
		for (int potionEnumId : potionStorePotions.getIntVals())
		{
			EnumComposition potionEnum = client.getEnum(potionEnumId);
			for (int doses = 1; doses <= 4; doses++)
			{
				int itemId = potionEnum.getIntValue(doses);
				if (itemId > -1)
				{
					potionMap.put(itemId, potionEnum);
				}
			}
		}

		EnumComposition unfinishedPotions = client.getEnum(EnumID.POTIONSTORE_UNFINISHED_POTIONS);
		for (int potionEnumId : unfinishedPotions.getIntVals())
		{
			EnumComposition potionEnum = client.getEnum(potionEnumId);
			int itemId = potionEnum.getIntValue(1);
			potionMap.put(itemId, potionEnum);
		}

		Widget widget = client.getWidget(InterfaceID.Bankmain.POTIONSTORE_ITEMS);
		if (widget == null)
		{
			return;
		}
		Widget[] children = widget.getDynamicChildren();
		if (children == null)
		{
			return;
		}

		List<PotionStorageState.PotionStorageItem> items = new ArrayList<>();
		for (int i = 0; i + 4 < children.length; i += 5)
		{
			Widget itemWidget = children[i + 1];
			Widget dosesWidget = children[i + 3];

			if (itemWidget.getItemId() == -1 || dosesWidget.getText() == null || dosesWidget.getText().isEmpty())
			{
				continue;
			}

			int itemId = itemWidget.getItemId();
			String[] parts = dosesWidget.getText().split(": ");
			if (parts.length < 2)
			{
				continue;
			}
			int totalDoses = Integer.parseInt(parts[1].replace(",", ""));

			EnumComposition potionEnum = potionMap.get(itemId);
			if (potionEnum == null)
			{
				continue;
			}

			int withdrawalDoseCount;
			for (withdrawalDoseCount = 1; withdrawalDoseCount < 4; withdrawalDoseCount++)
			{
				if (potionEnum.getIntValue(withdrawalDoseCount) == itemId)
				{
					break;
				}
			}
			// withdrawalDoseCount falls through to 4 if the loop above
			// never matched 1-3 — same fallback as the real plugin's
			// own withdrawDoses calculation, kept faithfully.

			String itemName = itemManager.getItemComposition(itemId).getName();
			items.add(new PotionStorageState.PotionStorageItem(itemId, itemName, totalDoses, withdrawalDoseCount));
		}

		PotionStorageState state = new PotionStorageState();
		state.setItems(items);
		state.setLastObservedAt(Instant.now().toString());

		store.write(TelemetryPaths.stateFile(client.getAccountHash(), "potion_storage"), state);
	}
}
