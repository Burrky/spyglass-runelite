package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.home.DailyTask;
import com.osrstelemetry.plugin.home.Dailies;
import com.osrstelemetry.plugin.home.Kingdom;

/** What Home's Kingdom and Dailies read (the observer in production; a fake in tests and renders). Any thread. */
interface HomeSource
{
	Dailies dailies();

	Kingdom kingdom();

	/** Standing in the kingdom now: the coffer and approval are this moment's. */
	boolean onIslands();

	boolean loggedIn();

	void setTracked(DailyTask task, boolean on);

	/** Weekly activities, daily uses and what is shown (pass 29). */
	default com.osrstelemetry.plugin.home.Recurring recurring()
	{
		return com.osrstelemetry.plugin.home.Recurring.empty();
	}

	default void setShown(com.osrstelemetry.plugin.home.Recurring.Entry entry, boolean on)
	{
	}

	/** The charge observations (Ardougne farm and cabbage teleports' daily uses). */
	default com.osrstelemetry.plugin.account.ChargeKnowledge charges()
	{
		return com.osrstelemetry.plugin.account.ChargeKnowledge.EMPTY;
	}

	HomeSource NONE = new HomeSource()
	{
		@Override
		public Dailies dailies()
		{
			return Dailies.empty();
		}

		@Override
		public Kingdom kingdom()
		{
			return Kingdom.empty();
		}

		@Override
		public boolean onIslands()
		{
			return false;
		}

		@Override
		public boolean loggedIn()
		{
			return false;
		}

		@Override
		public void setTracked(DailyTask task, boolean on)
		{
		}
	};
}
