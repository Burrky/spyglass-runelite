package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.BirdhouseService;
import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.hunter.BirdhouseChoice;
import com.osrstelemetry.plugin.hunter.BirdhousePlan;
import com.osrstelemetry.plugin.hunter.BirdhouseRun;
import com.osrstelemetry.plugin.hunter.BirdhouseSpace;
import com.osrstelemetry.plugin.hunter.BirdhouseTier;
import com.osrstelemetry.plugin.hunter.BirdhouseTraps;
import com.osrstelemetry.plugin.prep.PreparationState;
import com.osrstelemetry.plugin.prep.Readiness;
import java.util.Set;

/** What the bird house page reads and does (the service in production; a fake in tests and renders). Any thread. */
interface BirdhouseSource
{
	BirdhouseTraps traps();

	BirdhouseRun run();

	BirdhousePlan plan();

	PreparationState preparation();

	Readiness readiness();

	AccountCapability capability();

	boolean farmingRunActive();

	boolean onIsland();

	boolean start(Set<BirdhouseSpace> spaces, BirdhouseTier tier);

	void setTier(BirdhouseTier tier);

	void skip(BirdhouseSpace space, boolean skip);

	void begin();

	void end();

	void dismiss();

	void find(int[] itemIds);

	/** The spaces and tier chosen for the next run (saved per profile; pass 30). */
	default BirdhouseChoice choice()
	{
		return BirdhouseChoice.empty();
	}

	default void choose(BirdhouseChoice choice)
	{
	}

	default boolean bankOpen()
	{
		return false;
	}

	BirdhouseSource NONE = new BirdhouseSource()
	{
		@Override
		public BirdhouseTraps traps()
		{
			return BirdhouseTraps.empty();
		}

		@Override
		public BirdhouseRun run()
		{
			return null;
		}

		@Override
		public BirdhousePlan plan()
		{
			return null;
		}

		@Override
		public PreparationState preparation()
		{
			return null;
		}

		@Override
		public Readiness readiness()
		{
			return null;
		}

		@Override
		public AccountCapability capability()
		{
			return null;
		}

		@Override
		public boolean farmingRunActive()
		{
			return false;
		}

		@Override
		public boolean onIsland()
		{
			return false;
		}

		@Override
		public boolean start(Set<BirdhouseSpace> spaces, BirdhouseTier tier)
		{
			return false;
		}

		@Override
		public void setTier(BirdhouseTier tier)
		{
		}

		@Override
		public void skip(BirdhouseSpace space, boolean skip)
		{
		}

		@Override
		public void begin()
		{
		}

		@Override
		public void end()
		{
		}

		@Override
		public void dismiss()
		{
		}

		@Override
		public void find(int[] itemIds)
		{
		}
	};

	static BirdhouseSource of(BirdhouseService s)
	{
		return new BirdhouseSource()
		{
			@Override
			public BirdhouseTraps traps()
			{
				return s.traps();
			}

			@Override
			public BirdhouseRun run()
			{
				return s.run();
			}

			@Override
			public BirdhousePlan plan()
			{
				return s.plan();
			}

			@Override
			public PreparationState preparation()
			{
				return s.preparation();
			}

			@Override
			public Readiness readiness()
			{
				return s.readiness();
			}

			@Override
			public AccountCapability capability()
			{
				return s.capability();
			}

			@Override
			public boolean farmingRunActive()
			{
				return s.isFarmingRunActive();
			}

			@Override
			public boolean onIsland()
			{
				return s.isOnIsland();
			}

			@Override
			public boolean start(Set<BirdhouseSpace> spaces, BirdhouseTier tier)
			{
				return s.start(spaces, tier);
			}

			@Override
			public void setTier(BirdhouseTier tier)
			{
				s.setTier(tier);
			}

			@Override
			public void skip(BirdhouseSpace space, boolean skip)
			{
				s.skip(space, skip);
			}

			@Override
			public void begin()
			{
				s.begin();
			}

			@Override
			public void end()
			{
				s.end();
			}

			@Override
			public void dismiss()
			{
				s.dismiss();
			}

			@Override
			public void find(int[] itemIds)
			{
				s.focus(itemIds);
			}

			@Override
			public BirdhouseChoice choice()
			{
				return s.choice();
			}

			@Override
			public void choose(BirdhouseChoice choice)
			{
				s.choose(choice);
			}

			@Override
			public boolean bankOpen()
			{
				return s.isBankOpen();
			}
		};
	}
}
