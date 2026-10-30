package com.osrstelemetry.plugin.history;

import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.SessionAggregates;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which finished sessions History lists as reports (pass 28). One shared,
 * pure policy over the list-row summary ({@link HistoryEntry}), applied
 * BEFORE the list is counted or drawn, so hidden fragments never leave
 * empty space or inflate a total. Visibility only: every record stays on
 * disk exactly as captured and can still be listed ("Show short records")
 * and opened.
 *
 * A session is a report when ANY rule holds (all from captured, structured
 * evidence; nothing is inferred from names):
 * <ol>
 * <li>COMPLETION: a structured activity (minigame) recorded at least one
 * completion.</li>
 * <li>BOSS: a boss session recorded a kill or a drop (a boss drop is that
 * kill's own reward).</li>
 * <li>FARMING: a Farming session gained Farming XP (any patch or bin work).</li>
 * <li>REPEATED: repeated activity -- at least {@value #REPEATED} kills,
 * Slayer task progress, loot drops or produced items, or one completed
 * cycle (an Agility lap).</li>
 * <li>SUSTAINED: at least {@value #SUSTAINED_SECONDS} s of active play with
 * any XP, kill, drop or task progress.</li>
 * <li>SKILLING: a skilling session with XP and either
 * {@value #SKILLING_SECONDS} s of active play, or at least
 * {@value #SKILLING_BURST_XP} XP in {@value #SKILLING_BURST_SECONDS} s.</li>
 * </ol>
 * Anything else -- an empty fragment, one stray XP drop, one or two drops
 * with nothing else -- is a short record. Older records with missing fields
 * read those fields as zero, so they are judged only by what they kept.
 *
 * Tradeoff: a genuine fight that ended after one or two kills with no
 * longer play is hidden too; it is one click away under the control, and
 * the default list is no longer buried under hundreds of fragments.
 */
public final class HistoryEligibility
{
	static final int REPEATED = 3;
	static final long SUSTAINED_SECONDS = 300;
	static final long SKILLING_SECONDS = 120;
	static final long SKILLING_BURST_SECONDS = 30;
	static final long SKILLING_BURST_XP = 1_000;

	/** Why a session is a report; null from {@link #reason} = a short record. */
	public enum Reason
	{
		COMPLETION,
		BOSS,
		FARMING,
		REPEATED,
		SUSTAINED,
		SKILLING
	}

	private HistoryEligibility()
	{
	}

	/** The first rule that makes this a report, or null for a short record (and for null). */
	public static Reason reason(HistoryEntry e)
	{
		if (e == null || e.getActivityIdentity() == null)
		{
			return null;
		}
		ActivityType type = e.getActivityIdentity().getActivityType();
		long seconds = Math.max(0L, e.getAccumulatedActiveDurationMillis()) / 1000L;
		long xp = Math.max(0L, e.getTotalXpGained());
		int kills = kills(e);
		int drops = Math.max(0, e.getLootDropCount());
		int progress = Math.max(0, e.getSlayerProgress());

		if (type == ActivityType.STRUCTURED_ACTIVITY && completions(e) >= 1)
		{
			return Reason.COMPLETION;
		}
		if (type == ActivityType.BOSSING && (kills >= 1 || drops >= 1))
		{
			return Reason.BOSS;
		}
		if (type == ActivityType.SKILLING && "farming".equals(e.getActivityIdentity().getActivityKey()) && xp > 0)
		{
			return Reason.FARMING;
		}
		if (kills >= REPEATED || progress >= REPEATED || drops >= REPEATED
			|| e.getProducedQuantity() >= REPEATED || e.getSkillCycleTotal() >= 1)
		{
			return Reason.REPEATED;
		}
		if (seconds >= SUSTAINED_SECONDS && (xp > 0 || kills > 0 || drops > 0 || progress > 0))
		{
			return Reason.SUSTAINED;
		}
		if (type == ActivityType.SKILLING && xp > 0
			&& (seconds >= SKILLING_SECONDS || (seconds >= SKILLING_BURST_SECONDS && xp >= SKILLING_BURST_XP)))
		{
			return Reason.SKILLING;
		}
		return null;
	}

	public static boolean isReport(HistoryEntry e)
	{
		return reason(e) != null;
	}

	private static int kills(HistoryEntry e)
	{
		SessionAggregates.ReliableCount c = e.getReliableCount();
		return c != null && c.getKind() == SessionAggregates.ReliableCountKind.KILLS ? Math.max(0, c.getSessionOccurrences()) : 0;
	}

	private static int completions(HistoryEntry e)
	{
		int n = e.getActivityCompletionCount() == null ? 0 : e.getActivityCompletionCount();
		SessionAggregates.ReliableCount c = e.getReliableCount();
		if (n <= 0 && c != null && c.getKind() == SessionAggregates.ReliableCountKind.COMPLETIONS)
		{
			n = c.getSessionOccurrences();
		}
		return n;
	}

	/** The list split once, in order: reports, then short records. Never null. */
	public static Split split(List<HistoryEntry> entries)
	{
		List<HistoryEntry> reports = new ArrayList<>();
		List<HistoryEntry> hidden = new ArrayList<>();
		if (entries != null)
		{
			for (HistoryEntry e : entries)
			{
				if (e == null)
				{
					continue;
				}
				(isReport(e) ? reports : hidden).add(e);
			}
		}
		return new Split(Collections.unmodifiableList(reports), Collections.unmodifiableList(hidden));
	}

	/** {@link #split}'s result. */
	public static final class Split
	{
		private final List<HistoryEntry> reports;
		private final List<HistoryEntry> hidden;

		Split(List<HistoryEntry> reports, List<HistoryEntry> hidden)
		{
			this.reports = reports;
			this.hidden = hidden;
		}

		public List<HistoryEntry> getReports()
		{
			return reports;
		}

		public List<HistoryEntry> getHidden()
		{
			return hidden;
		}
	}
}
