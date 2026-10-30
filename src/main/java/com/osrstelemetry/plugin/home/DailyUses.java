package com.osrstelemetry.plugin.home;

import java.util.ArrayList;
import java.util.List;

/**
 * Daily allowances of diary equipment: what is left today, never a chore (unused charges never make Home look unfinished).
 * Immutable; saved per RuneScape profile with the recurring activities.
 *
 * <h3>Supported (meaning and freshness verified)</h3>
 * <ul>
 *   <li>Explorer's ring free alchemy: {@code lumbridge_free_alchs} (varbit 4554) counts today's casts; the game's own alchemy
 *   interface shows "Charges: 30 − it" from it. 30 a day with any ring (Lumbridge &amp; Draynor easy diary or better).</li>
 *   <li>Explorer's ring run energy: {@code lumbridge_energy_restore} (varbit 4553, varp {@code handsand}, sent with the daily
 *   claims the Daily Task Indicator reads) counts today's restores; 2, 3 or 4 a day at 50% (rings 1–3), 3 at 100% (ring 4)
 *   (OSRS Wiki, each ring's page). The limit follows the highest Lumbridge diary done.</li>
 *   <li>Falador shield prayer: {@code falador_shield_recharge} (varbit 4544, varp {@code handsand}) counts today's
 *   recharges; once a day (shields 1–3), twice (shield 4, Falador elite) (OSRS Wiki).</li>
 *   <li>Ardougne cloak farm teleports and Explorer's ring cabbage teleports: the game's own message ("You have used N of your
 *   M … teleports for today.", Spyglass's charge observations): only when seen.</li>
 * </ul>
 * The rings' and shield's daily uses come back only after logging in again past the daily reset (OSRS Wiki, Explorer's
 * ring 4): a value read on an earlier UTC day is said as last read, never as refreshed.
 */
public final class DailyUses
{
	public static final long DAY_MILLIS = 86_400_000L;

	public enum Use
	{
		ALCHEMY("Explorer's ring alchemy", "Free alchemy casts"),
		RUN_ENERGY("Explorer's ring run energy", "Run energy restores"),
		PRAYER("Falador shield prayer", "Prayer recharges"),
		ARDOUGNE_FARM("Ardougne cloak farm teleports", "Teleports to the Ardougne farm patches"),
		CABBAGE("Explorer's ring cabbage teleports", "Teleports to the cabbage patch");

		private final String title;
		private final String what;

		Use(String title, String what)
		{
			this.title = title;
			this.what = what;
		}

		public String getTitle()
		{
			return title;
		}

		public String getWhat()
		{
			return what;
		}
	}

	/** One read at login and every few seconds (client thread). */
	public static final class Read
	{
		final int lumbridgeTier;
		final int alchsUsed;
		final int energyUsed;
		final int faladorTier;
		final int prayerUsed;

		/** {@code lumbridgeTier}, {@code faladorTier}: 0 none, 1 easy … 4 elite. */
		public Read(int lumbridgeTier, int alchsUsed, int energyUsed, int faladorTier, int prayerUsed)
		{
			this.lumbridgeTier = lumbridgeTier;
			this.alchsUsed = alchsUsed;
			this.energyUsed = energyUsed;
			this.faladorTier = faladorTier;
			this.prayerUsed = prayerUsed;
		}
	}

	private Integer lumbridgeTier;
	private Integer alchsUsed;
	private Integer energyUsed;
	private Integer faladorTier;
	private Integer prayerUsed;
	private Long readAt;

	public static DailyUses empty()
	{
		return new DailyUses();
	}

	public DailyUses with(Read r, long now)
	{
		boolean same = r != null && Integer.valueOf(r.lumbridgeTier).equals(lumbridgeTier) && Integer.valueOf(r.alchsUsed).equals(alchsUsed)
			&& Integer.valueOf(r.energyUsed).equals(energyUsed) && Integer.valueOf(r.faladorTier).equals(faladorTier)
			&& Integer.valueOf(r.prayerUsed).equals(prayerUsed) && readAt != null && Math.floorDiv(readAt, DAY_MILLIS) == Math.floorDiv(now, DAY_MILLIS);
		if (r == null || same)
		{
			return this;
		}
		DailyUses d = new DailyUses();
		d.lumbridgeTier = r.lumbridgeTier;
		d.alchsUsed = r.alchsUsed;
		d.energyUsed = r.energyUsed;
		d.faladorTier = r.faladorTier;
		d.prayerUsed = r.prayerUsed;
		d.readAt = now;
		return d;
	}

	/** An allowance now. */
	public static final class Status
	{
		private final Use use;
		/** Not available on this account (no diary). */
		private final boolean locked;
		/** Uses left as read (-1: not known); and the day's total. */
		private final int left;
		private final int limit;
		/** Read today (this login or earlier today); false: as last read, possibly refreshed since. */
		private final boolean fresh;
		private final String note;

		Status(Use use, boolean locked, int left, int limit, boolean fresh, String note)
		{
			this.use = use;
			this.locked = locked;
			this.left = left;
			this.limit = limit;
			this.fresh = fresh;
			this.note = note;
		}

		public Use getUse()
		{
			return use;
		}

		public boolean isLocked()
		{
			return locked;
		}

		public int getLeft()
		{
			return left;
		}

		public int getLimit()
		{
			return limit;
		}

		public boolean isFresh()
		{
			return fresh;
		}

		public String getNote()
		{
			return note;
		}

		/** Known at all (read once). */
		public boolean isKnown()
		{
			return left >= 0;
		}
	}

	/** Restores a day by Lumbridge diary tier (1 easy … 4 elite). */
	static int energyLimit(int tier)
	{
		return tier <= 0 ? 0 : tier == 1 ? 2 : tier == 2 ? 3 : tier == 3 ? 4 : 3;
	}

	/**
	 * @param charges the charge observations (Ardougne farm and cabbage teleports), or null
	 */
	public List<Status> statuses(com.osrstelemetry.plugin.account.ChargeKnowledge charges, long now)
	{
		List<Status> out = new ArrayList<>();
		boolean today = readAt != null && Math.floorDiv(readAt, DAY_MILLIS) == Math.floorDiv(now, DAY_MILLIS);
		String stale = "As last read; log in after the daily reset to read today's";
		if (lumbridgeTier == null)
		{
			out.add(new Status(Use.ALCHEMY, false, -1, 30, false, "Log in to read it"));
			out.add(new Status(Use.RUN_ENERGY, false, -1, 0, false, "Log in to read it"));
		}
		else
		{
			boolean locked = lumbridgeTier <= 0;
			out.add(new Status(Use.ALCHEMY, locked, locked ? -1 : Math.max(0, 30 - alchsUsed), 30, today,
				locked ? "Needs the Lumbridge & Draynor easy diary" : today ? (lumbridgeTier >= 4 ? "Low or high alchemy" : "Low alchemy") : stale));
			int limit = energyLimit(lumbridgeTier);
			out.add(new Status(Use.RUN_ENERGY, locked, locked ? -1 : Math.max(0, limit - energyUsed), limit, today,
				locked ? "Needs the Lumbridge & Draynor easy diary" : today ? (lumbridgeTier >= 4 ? "100% each" : "50% each") : stale));
		}
		if (faladorTier == null)
		{
			out.add(new Status(Use.PRAYER, false, -1, 0, false, "Log in to read it"));
		}
		else
		{
			boolean locked = faladorTier <= 0;
			int limit = faladorTier >= 4 ? 2 : 1;
			out.add(new Status(Use.PRAYER, locked, locked ? -1 : Math.max(0, limit - prayerUsed), limit, today,
				locked ? "Needs the Falador easy diary" : today ? null : stale));
		}
		out.add(charged(Use.ARDOUGNE_FARM, charges, com.osrstelemetry.plugin.account.ChargeKnowledge.ARDOUGNE_FARM, now));
		out.add(charged(Use.CABBAGE, charges, com.osrstelemetry.plugin.account.ChargeKnowledge.CABBAGE, now));
		return out;
	}

	private static Status charged(Use use, com.osrstelemetry.plugin.account.ChargeKnowledge charges, String pool, long now)
	{
		com.osrstelemetry.plugin.account.ChargeKnowledge.Entry e = charges == null ? null : charges.getEntries().get(pool);
		if (e == null)
		{
			return new Status(use, false, -1, 0, false, "Shown once the game reports it (teleport, or Check the item)");
		}
		boolean today = Math.floorDiv(e.at, DAY_MILLIS) == Math.floorDiv(now, DAY_MILLIS);
		return new Status(use, false, Math.max(0, e.limit - e.used), e.limit, today,
			today ? null : "As last reported; the daily reset has passed since");
	}

	public Long getReadAt()
	{
		return readAt;
	}
}
