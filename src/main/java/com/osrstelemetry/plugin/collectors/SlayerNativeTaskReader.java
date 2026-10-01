package com.osrstelemetry.plugin.collectors;

import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Reads the current Slayer assignment straight from native client state:
 * Slayer varps/varbits plus the game cache's SlayerTask, SlayerTaskSublist
 * and SlayerArea DB tables, all through runelite-api's {@link Client}. No
 * other RuneLite plugin is involved, and nothing is cached here -- every
 * read reflects the logged-in account's current varps, so the reader is
 * account-safe across login, logout, hop, account switch and plugin
 * disable/re-enable by construction.
 *
 * MUST be called on the client thread (varp and DB-table access). Cost: a
 * handful of varp reads and one to three indexed cache lookups, only when a
 * caller asks -- no polling, timers, NPC scans or reflection.
 */
public class SlayerNativeTaskReader
{
	private final Client client;
	private final SlayerTaskLookup lookup;

	@Inject
	public SlayerNativeTaskReader(Client client)
	{
		this.client = client;
		this.lookup = new ClientSlayerTaskLookup(client);
	}

	public SlayerNativeTask read()
	{
		return SlayerNativeTaskResolver.resolve(readRaw(client), lookup);
	}

	static SlayerNativeTaskResolver.RawVars readRaw(Client client)
	{
		return new SlayerNativeTaskResolver.RawVars(
			client.getVarpValue(VarPlayerID.SLAYER_COUNT),
			client.getVarpValue(VarPlayerID.SLAYER_TARGET),
			client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID),
			client.getVarpValue(VarPlayerID.SLAYER_AREA),
			client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL),
			client.getVarbitValue(VarbitID.SLAYER_MODIFIER_ID),
			client.getVarbitValue(VarbitID.SLAYER_MODIFIER_VALUE),
			client.getVarbitValue(VarbitID.SLAYER_MODIFIER_NEGATIVE));
	}

	/** Production {@link SlayerTaskLookup} over the game cache's DB tables. */
	static final class ClientSlayerTaskLookup implements SlayerTaskLookup
	{
		private final Client client;

		ClientSlayerTaskLookup(Client client)
		{
			this.client = client;
		}

		@Override
		public Integer taskRowForTaskId(int taskId)
		{
			return firstRow(client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId));
		}

		@Override
		public Integer taskRowForBossId(int bossId)
		{
			Integer sublistRow = firstRow(client.getDBRowsByValue(
				DBTableID.SlayerTaskSublist.ID, DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, bossId));
			if (sublistRow == null)
			{
				return null;
			}
			Object value = field(sublistRow, DBTableID.SlayerTaskSublist.COL_TASK);
			return value instanceof Integer ? (Integer) value : null;
		}

		@Override
		public String taskName(int taskRow)
		{
			Object value = field(taskRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE);
			return value instanceof String ? (String) value : null;
		}

		@Override
		public String areaName(int areaId)
		{
			Integer areaRow = firstRow(client.getDBRowsByValue(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0, areaId));
			if (areaRow == null)
			{
				return null;
			}
			Object value = field(areaRow, DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER);
			return value instanceof String ? (String) value : null;
		}

		private Object field(int row, int column)
		{
			Object[] values = client.getDBTableField(row, column, 0);
			return values == null || values.length == 0 ? null : values[0];
		}

		private static Integer firstRow(List<Integer> rows)
		{
			return rows == null || rows.isEmpty() ? null : rows.get(0);
		}
	}
}
