package com.osrstelemetry.plugin.ui.model;

import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.session.SlayerAssignmentEndReason;
import java.util.List;
import java.util.Locale;

/**
 * SMART SLAYER DASHBOARD: pure presentation rules for the Slayer Current
 * Session (and History) -- master names, task progress, streak milestones,
 * the Turael-boosting hint, master-specific context rows and per-task rates.
 * No Swing, no client access; every rule here is unit-tested directly.
 */
public final class SlayerDashboard
{
	private SlayerDashboard()
	{
	}

	/** Zero-point masters whose tasks still extend the streak (Turael/Aya, Spria). */
	private static final int TURAEL = 1;
	private static final int SPRIA = 9;
	public static final int KRYSTILIA = 7;
	public static final int KONAR = 8;
	public static final int MORTIMER = 10;

	/** Consecutive completed zero-point-master tasks this session that count as boosting. */
	static final int BOOSTING_MIN_TASKS = 3;

	/**
	 * Streak bonus cadence (OSRS Wiki, Slayer reward point): bonus points on
	 * EVERY 10th task, with larger multipliers on every 50th / 100th / 250th
	 * / 1,000th (the highest applicable tier wins). So the next bonus task is
	 * always the next multiple of 10; its number itself tells the tier.
	 */
	static final int MILESTONE_STEP = 10;

	/**
	 * Player-facing name for the native SLAYER_MASTER varbit. One varbit value
	 * covers both masters of a quest-replaced pair (the game itself does not
	 * distinguish them), so the pair is named rather than guessed. Null for
	 * 0 (no task) and unknown values -- never an internal id.
	 */
	public static String masterName(Integer masterId)
	{
		if (masterId == null)
		{
			return null;
		}
		switch (masterId)
		{
			case 1:
				return "Turael / Aya";
			case 2:
				return "Mazchna / Achtryn";
			case 3:
				return "Vannaka";
			case 4:
				return "Chaeldar";
			case 5:
				return "Duradel / Kuradal";
			case 6:
				return "Nieve / Steve";
			case 7:
				return "Krystilia";
			case 8:
				return "Konar quo Maten";
			case 9:
				return "Spria";
			case 10:
				return "Mortimer";
			default:
				return null;
		}
	}

	/**
	 * Whole-percent task progress from the assignment's own size and the
	 * native remaining count (task units, so bracelet effects are already
	 * reflected). Floor, so 100% appears only at 0 remaining. Null when the
	 * size is unknown -- the size itself is never displayed.
	 */
	public static Integer progressPercent(Integer remaining, Integer size)
	{
		if (remaining == null || size == null || size <= 0 || remaining < 0 || remaining > size)
		{
			return null;
		}
		return (int) Math.floor((size - remaining) * 100.0 / size);
	}

	/** The assignment's size: the observed ASSIGNED amount, else the native original size (joined mid-task). */
	public static Integer assignmentSize(SessionAggregates.SlayerAssignmentRecord record)
	{
		if (record == null)
		{
			return null;
		}
		if (!record.isBootstrapped() && record.getInitialAmount() != null && record.getInitialAmount() > 0)
		{
			return record.getInitialAmount();
		}
		return record.getAssignedInitialAmount();
	}

	/**
	 * The NEXT BONUS cell: "10th in 8", "50th next", "250th in 2" -- the next
	 * streak task that earns bonus points (every multiple of 10). Null for
	 * Mortimer -- his points come from his task modifier and are not raised
	 * by milestones -- and when the streak source is unknown.
	 */
	public static String milestoneText(Integer streak, String streakLabel)
	{
		Integer away = tasksToNextMilestone(streak, streakLabel);
		if (away == null)
		{
			return null;
		}
		String ordinal = ordinal(streak + away);
		return away == 1 ? ordinal + " next" : ordinal + " in " + away;
	}

	/** Tasks until the next bonus (multiple-of-10) streak task (1 = the next task), or null when not applicable. */
	public static Integer tasksToNextMilestone(Integer streak, String streakLabel)
	{
		if (streak == null || streak < 0 || streakLabel == null || "MORTIMER".equals(streakLabel))
		{
			return null;
		}
		int target = (streak / MILESTONE_STEP + 1) * MILESTONE_STEP;
		return target - streak;
	}

	static String ordinal(int n)
	{
		String number = String.format(Locale.ROOT, "%,d", n);
		int mod100 = n % 100;
		if (mod100 >= 11 && mod100 <= 13)
		{
			return number + "th";
		}
		switch (n % 10)
		{
			case 1:
				return number + "st";
			case 2:
				return number + "nd";
			case 3:
				return number + "rd";
			default:
				return number + "th";
		}
	}

	/**
	 * TURAEL BOOSTING, recognized conservatively: the most recent
	 * {@link #BOOSTING_MIN_TASKS} finished assignments of THIS session were
	 * all COMPLETED (never skipped) tasks from a zero-point master
	 * (Turael/Aya or Spria) with a known master. One Turael task, a skip, an
	 * unknown master or any other master breaks the run. The unfinished
	 * current assignment is ignored either way.
	 */
	public static boolean looksLikeTuraelBoosting(List<SessionAggregates.SlayerAssignmentRecord> ledger)
	{
		if (ledger == null)
		{
			return false;
		}
		int run = 0;
		for (int i = ledger.size() - 1; i >= 0; i--)
		{
			SessionAggregates.SlayerAssignmentRecord r = ledger.get(i);
			if (r == null)
			{
				return false;
			}
			if (r.getEndReason() == null && r.getEndedAt() == null)
			{
				// The live, unfinished assignment.
				continue;
			}
			if (r.getEndReason() != SlayerAssignmentEndReason.COMPLETED || r.getMasterId() == null
				|| (r.getMasterId() != TURAEL && r.getMasterId() != SPRIA))
			{
				break;
			}
			run++;
			if (run >= BOOSTING_MIN_TASKS)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The boosting hint: only when boosting is recognized AND the very next
	 * normal-streak task is a bonus task (any multiple of 10 -- the 10th,
	 * 20th, 50th, 100th ...).
	 */
	public static boolean showPointsMasterHint(boolean boosting, Integer streak, String streakLabel)
	{
		Integer away = tasksToNextMilestone(streak, streakLabel);
		return boosting && "NORMAL".equals(streakLabel) && away != null && away == 1;
	}

	/**
	 * Player-facing text for the native task modifier, only for the ids whose
	 * meaning is confirmed: 2 = task quantity (RuneLite 1.13.1 applies it to
	 * the task size itself, signed by SLAYER_MODIFIER_NEGATIVE) and 1 = Slayer
	 * points on completion. Other ids (clue / XP / superior) are not named --
	 * their ordering differs between sources -- so nothing is shown rather
	 * than a guess. Null when there is no modifier.
	 */
	public static String modifierText(int modifierId, int modifierValue, boolean negative)
	{
		if (modifierValue <= 0)
		{
			return null;
		}
		switch (modifierId)
		{
			case 1:
				return "+" + String.format(Locale.ROOT, "%,d", modifierValue) + " points";
			case 2:
				return "Quantity " + (negative ? "-" : "+") + String.format(Locale.ROOT, "%,d", modifierValue);
			default:
				return null;
		}
	}

	/** Kills per hour over the task's own active time; null until the shared rate floor is reached or with no kills. */
	public static Long perHour(Integer count, long activeMillis)
	{
		if (count == null || count <= 0 || activeMillis < CurrentSessionSnapshot.MIN_DURATION_FOR_RATE_MILLIS)
		{
			return null;
		}
		return Math.round(count * 3_600_000.0 / activeMillis);
	}

	/** Observed loot value per observed kill; null with no kills. */
	public static Long perKill(long value, Integer kills)
	{
		if (kills == null || kills <= 0)
		{
			return null;
		}
		return Math.round(value / (double) kills);
	}
}
