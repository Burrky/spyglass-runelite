package com.osrstelemetry.plugin.farming;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The first-use Farming tutorial (canonical §28.18): one instruction at a time, on the real page, each step done by what Spyglass
 * actually sees (or the player's "Got it" for a step that is only advice), never by a click standing in for evidence. Setup
 * comes first, because preparation needs it: the bank seen once, the tool leprechaun's store read, the seed vault seen once
 * when the account can use the Farming Guild's, and the house seen once when runs go through it. Anything already known is
 * credited without asking. It starts by itself only for a first-time Farming setup (no run ever started on the profile); a
 * player who has run before has it marked complete, replayable from settings. The introduction is required; a storage step
 * the player can't do (no access, no house) can be skipped, and the card says what skipping means. Progress and completion
 * are saved per RuneScape profile (field names are the JSON); nothing but Replay starts it again. Immutable.
 */
public final class FarmingTutorial
{
	public enum Step
	{
		INTRO(true, "Farming runs, step by step", "Pick patches, Spyglass lists what to bring and shows the way; it ticks each patch when the"
			+ " game shows the work done. You play every step yourself.", null),
		BANK(true, "Open your bank once", "Spyglass sees your seeds, compost and tools there, so preparation knows what you have.",
			"Skip if you can't reach a bank now: preparation then can't say what you hold there."),
		TOOLS(true, "Log in", "Spyglass reads the tool leprechaun's store from the game: nothing to deposit or show it.",
			"Skip to continue logged out: the leprechaun's store counts once you log in."),
		SEED_VAULT(true, "Open the seed vault once", "In the Farming Guild. Seeds kept there count in preparation.",
			"Skip if you don't use the vault: seeds kept there won't count until you open it."),
		HOUSE(true, "Visit your house once", "Runs prefer house travel: walk past the portals and other facilities you have (open the nexus's"
			+ " Teleport Menu if you have one) so Spyglass knows them.", "Skip if you can't visit now: runs use other ways until Spyglass has seen your house."),
		PICK(false, "Pick a run type", "Choose a type above (or Compost bins), then tick what this run includes.", null),
		START(false, "Press Start Run", "Preparation lists what to bring for this run.", null),
		BANK_FILTER(false, "Use the bank filter", "With your bank open, press \"Show run items in bank\" on this page: the bank shows only what this run"
			+ " needs.", null),
		GO(false, "Begin the run", "Press \"Begin run\" (or \"Begin anyway\"): guidance then shows the way to each stop, one step at a time.", null),
		PATCH(false, "Work your first patch", "Do what its card shows. It ticks when the game shows it done; compost and protection are steps of"
			+ " their own.", null),
		SKIP(false, "Skip what you can't do", "A patch, a compost bin or a payment you can't do now: skip it on its card and the run moves on.", null),
		FINISH(false, "Finish the run", "It completes when everything in it is done or skipped. History keeps what happened.", null);

		private final boolean setup;
		private final String title;
		private final String detail;
		private final String skipHint;

		Step(boolean setup, String title, String detail, String skipHint)
		{
			this.setup = setup;
			this.title = title;
			this.detail = detail;
			this.skipHint = skipHint;
		}

		/** What skipping this step means (setup storage steps), or null. */
		public String getSkipHint()
		{
			return skipHint;
		}

		/** The introduction is required: Got it, never Skip. */
		public boolean isSkippable()
		{
			return this != INTRO && !isAdvice();
		}

		/** The step as this run shows it: a bins-only run works bins, not patches. */
		public String titleFor(Facts f)
		{
			return this == PATCH && f.binsOnly ? "Work your first bin" : title;
		}

		public String detailFor(Facts f)
		{
			return this == PATCH && f.binsOnly ? "Do what its card shows; it ticks when the game shows the bin changed." : detail;
		}

		public boolean isSetup()
		{
			return setup;
		}

		public String getTitle()
		{
			return title;
		}

		public String getDetail()
		{
			return detail;
		}

		/** Advice only: done by the player's "Got it". */
		public boolean isAdvice()
		{
			return this == SKIP || this == INTRO;
		}
	}

	/** What Spyglass sees now (built each render). */
	public static final class Facts
	{
		public boolean accountRead;
		public boolean bankSeen;
		public Integer farmingLevel;
		public boolean vaultSeen;
		public boolean houseChosen;
		public boolean houseSeen;
		public boolean typeSelected;
		public boolean runOpen;
		public boolean preparing;
		public boolean bankFilterOn;
		public int patchesDone;
		public boolean runFinished;
		/** The open run is compost bins only. */
		public boolean binsOnly;
		/** Bins of the open run worked so far. */
		public int binsWorked;
	}

	/** The Farming Guild (and its seed vault) needs 45 Farming (OSRS Wiki, Farming Guild). */
	public static final int GUILD_LEVEL = 45;

	/** Field names are the saved JSON. */
	private Set<String> done = new TreeSet<>();
	private Set<String> skipped = new TreeSet<>();
	private boolean finished;
	/** When it first started by itself (epoch ms); null: not yet decided for this profile. */
	private Long startedAt;
	/** Marked complete without being shown: the profile had run Farming before. */
	private boolean creditedExperience;

	public static FarmingTutorial fresh()
	{
		return new FarmingTutorial();
	}

	private FarmingTutorial copy()
	{
		FarmingTutorial t = new FarmingTutorial();
		t.done = new TreeSet<>(done());
		t.skipped = new TreeSet<>(skipped());
		t.finished = finished;
		t.startedAt = startedAt;
		t.creditedExperience = creditedExperience;
		return t;
	}

	/** Nothing decided yet for this profile: the first Farming visit decides whether it starts. */
	public boolean isUndecided()
	{
		return startedAt == null && !finished && done().isEmpty() && skipped().isEmpty();
	}

	/** First Farming visit: start it for a first-time setup, or mark it complete for a profile that has run Farming before. */
	public FarmingTutorial decide(boolean ranBefore, long now)
	{
		FarmingTutorial t = copy();
		t.startedAt = now;
		if (ranBefore)
		{
			t.finished = true;
			t.creditedExperience = true;
		}
		return t;
	}

	public boolean isCreditedExperience()
	{
		return creditedExperience;
	}

	private Set<String> done()
	{
		return done == null ? Collections.emptySet() : done;
	}

	private Set<String> skipped()
	{
		return skipped == null ? Collections.emptySet() : skipped;
	}

	public boolean isFinished()
	{
		return finished;
	}

	/** Whether a step applies to this account and its choices (the seed vault only with Guild access, the house only for house travel). */
	public static boolean applies(Step s, Facts f)
	{
		switch (s)
		{
			case SEED_VAULT:
				return f.farmingLevel != null && f.farmingLevel >= GUILD_LEVEL;
			case HOUSE:
				return f.houseChosen;
			default:
				return true;
		}
	}

	/** What the game shows satisfies the step (already known counts: it is credited, never asked again). */
	public static boolean satisfied(Step s, Facts f)
	{
		switch (s)
		{
			case INTRO:
				return false;
			case BANK:
				return f.bankSeen;
			case TOOLS:
				return f.accountRead;
			case SEED_VAULT:
				return f.vaultSeen;
			case HOUSE:
				return f.houseSeen;
			case PICK:
				return f.typeSelected || f.runOpen;
			case START:
				return f.runOpen;
			case BANK_FILTER:
				return f.bankFilterOn;
			case GO:
				return f.runOpen && !f.preparing;
			case PATCH:
				return f.patchesDone > 0 || f.binsWorked > 0;
			case FINISH:
				return f.runFinished;
			default:
				return false;
		}
	}

	private boolean settled(Step s, Facts f)
	{
		return done().contains(s.name()) || skipped().contains(s.name()) || !applies(s, f);
	}

	/** This state with every step the game now shows credited (the same instance when nothing changed). */
	public FarmingTutorial withEvidence(Facts f)
	{
		FarmingTutorial t = null;
		for (Step s : Step.values())
		{
			if (!settled(s, f) && satisfied(s, f))
			{
				t = t == null ? copy() : t;
				t.done.add(s.name());
			}
		}
		if (t != null && t.current(f) == null)
		{
			t.finished = true;
		}
		return t == null ? this : t;
	}

	/** The one step to show now, or null when the tutorial is finished. */
	public Step current(Facts f)
	{
		if (finished)
		{
			return null;
		}
		for (Step s : Step.values())
		{
			if (!settled(s, f))
			{
				return s;
			}
		}
		return null;
	}

	/** Every setup step done, skipped or not applicable: preparation can start. */
	public boolean isSetupComplete(Facts f)
	{
		if (finished)
		{
			return true;
		}
		for (Step s : Step.values())
		{
			if (s.isSetup() && !settled(s, f))
			{
				return false;
			}
		}
		return true;
	}

	public FarmingTutorial withSkipped(Step s, Facts f)
	{
		FarmingTutorial t = copy();
		t.skipped.add(s.name());
		t.finished = t.current(f) == null;
		return t;
	}

	/** "Got it" on an advice step. */
	public FarmingTutorial withAcknowledged(Step s, Facts f)
	{
		FarmingTutorial t = copy();
		t.done.add(s.name());
		t.finished = t.current(f) == null;
		return t;
	}

	/** Hide the run tips (setup kept as it is): the player knows the flow. */
	public FarmingTutorial withFinished()
	{
		FarmingTutorial t = copy();
		t.finished = true;
		return t;
	}

	/** Replay from settings: the flow steps again, setup credited from what is known. */
	public FarmingTutorial replay()
	{
		FarmingTutorial t = new FarmingTutorial();
		t.startedAt = startedAt == null ? Long.valueOf(0L) : startedAt;
		for (String s : done())
		{
			Step step = parse(s);
			if (step != null && step.isSetup() && step != Step.INTRO)
			{
				t.done.add(s);
			}
		}
		return t;
	}

	/** Step number among the steps that apply ("2 of 9"). */
	public static int[] position(Step s, Facts f)
	{
		int n = 0;
		int at = 0;
		for (Step x : Step.values())
		{
			if (applies(x, f))
			{
				n++;
				if (x == s)
				{
					at = n;
				}
			}
		}
		return new int[]{at, n};
	}

	private static Step parse(String name)
	{
		try
		{
			return Step.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}
}
