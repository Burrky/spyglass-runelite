package com.osrstelemetry.plugin.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Home's Dailies &amp; Weeklies beyond the daily claims ({@link Dailies} keeps those, unchanged): which weekly activities and
 * daily allowances the player chose to show, and what the game last showed for them (Tears of Guthix, the daily uses).
 * Bird houses have their own Home card and page (pass 30): they are no longer chosen here, and a saved choice from pass 29
 * ({@code "BIRDHOUSES"} in {@code shown}) is kept as it was, simply unused. Saved per RuneScape profile (field names are the
 * JSON); immutable.
 */
public final class Recurring
{
	public enum Group
	{
		WEEKLY,
		DAILY_USES
	}

	public enum Entry
	{
		TEARS(Group.WEEKLY, "Tears of Guthix", "Juna, Lumbridge Swamp Caves", net.runelite.api.gameval.ItemID.TOG_BOWL),
		ALCHEMY(Group.DAILY_USES, "Ring alchemy", "Explorer's ring", net.runelite.api.gameval.ItemID.LUMBRIDGE_RING_ELITE),
		RUN_ENERGY(Group.DAILY_USES, "Ring run energy", "Explorer's ring", net.runelite.api.gameval.ItemID.LUMBRIDGE_RING_EASY),
		PRAYER(Group.DAILY_USES, "Shield prayer", "Falador shield", net.runelite.api.gameval.ItemID.FALADOR_SHIELD_ELITE),
		ARDOUGNE_FARM(Group.DAILY_USES, "Cloak farm teleports", "Ardougne cloak", net.runelite.api.gameval.ItemID.ARDY_CAPE_ELITE),
		CABBAGE(Group.DAILY_USES, "Cabbage teleports", "Explorer's ring", net.runelite.api.gameval.ItemID.CABBAGE);

		private final Group group;
		private final String title;
		private final String where;
		/** The item the card shows (pass 31). */
		private final int icon;

		Entry(Group group, String title, String where, int icon)
		{
			this.icon = icon;
			this.group = group;
			this.title = title;
			this.where = where;
		}

		public Group getGroup()
		{
			return group;
		}

		public String getTitle()
		{
			return title;
		}

		public String getWhere()
		{
			return where;
		}

		public int getIconItem()
		{
			return icon;
		}

		/** The daily use this entry shows (null for the others). */
		public DailyUses.Use use()
		{
			switch (this)
			{
				case ALCHEMY:
					return DailyUses.Use.ALCHEMY;
				case RUN_ENERGY:
					return DailyUses.Use.RUN_ENERGY;
				case PRAYER:
					return DailyUses.Use.PRAYER;
				case ARDOUGNE_FARM:
					return DailyUses.Use.ARDOUGNE_FARM;
				case CABBAGE:
					return DailyUses.Use.CABBAGE;
				default:
					return null;
			}
		}

		public static List<Entry> of(Group g)
		{
			List<Entry> out = new ArrayList<>();
			for (Entry e : values())
			{
				if (e.group == g)
				{
					out.add(e);
				}
			}
			return out;
		}
	}

	/** Entry names shown (null: never chosen). Names no longer offered are kept untouched. */
	private Set<String> shown;
	private TearsOfGuthix tears;
	private DailyUses uses;

	public static Recurring empty()
	{
		return new Recurring();
	}

	private Recurring copy()
	{
		Recurring r = new Recurring();
		r.shown = shown == null ? null : new TreeSet<>(shown);
		r.tears = tears;
		r.uses = uses;
		return r;
	}

	public boolean isChosen()
	{
		return shown != null;
	}

	public boolean isShown(Entry e)
	{
		return shown != null && shown.contains(e.name());
	}

	public Recurring withShown(Entry e, boolean on)
	{
		Recurring r = copy();
		if (r.shown == null)
		{
			r.shown = new TreeSet<>();
		}
		if (on)
		{
			r.shown.add(e.name());
		}
		else
		{
			r.shown.remove(e.name());
		}
		return r;
	}

	public List<Entry> shownIn(Group g)
	{
		List<Entry> out = new ArrayList<>();
		for (Entry e : Entry.of(g))
		{
			if (isShown(e))
			{
				out.add(e);
			}
		}
		return out;
	}

	public TearsOfGuthix tears()
	{
		return tears == null ? TearsOfGuthix.empty() : tears;
	}

	public DailyUses uses()
	{
		return uses == null ? DailyUses.empty() : uses;
	}

	public Recurring withTears(TearsOfGuthix t)
	{
		if (t == tears)
		{
			return this;
		}
		Recurring r = copy();
		r.tears = t;
		return r;
	}

	public Recurring withUses(DailyUses u)
	{
		if (u == uses)
		{
			return this;
		}
		Recurring r = copy();
		r.uses = u;
		return r;
	}

	Set<String> shownNames()
	{
		return shown == null ? Collections.emptySet() : shown;
	}
}
