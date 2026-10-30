package com.osrstelemetry.plugin.farming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A run's optional work beyond its crop patches (canonical §28.16), pure: the protection payments due and the compost bins
 * with a task now. One rule for every surface: the run page lists them, guidance leads to them, and a run completes only when
 * none is left (each can be skipped, so none can hold the run). Older runs (no extras recorded at Start) have none.
 */
public final class RunExtras
{
	private RunExtras()
	{
	}

	/** A bin of this run with its task now (null task: nothing to do there now). */
	public static final class BinWork
	{
		public final CompostBin.Def def;
		public final CompostBin.Task task;
		public final boolean skipped;
		/** The bin as last seen (null: never seen). */
		public final CompostBin.Seen seen;

		BinWork(CompostBin.Def def, CompostBin.Task task, boolean skipped, CompostBin.Seen seen)
		{
			this.def = def;
			this.task = task;
			this.skipped = skipped;
			this.seen = seen;
		}

		/** A task to do that the player has not skipped. */
		public boolean isDue()
		{
			return task != null && !skipped;
		}
	}

	/** The run's bins, in catalogue order, each with its task from what was last seen. */
	public static List<BinWork> bins(FarmingRunIntent intent, Map<String, CompostBin.Seen> seen, long now)
	{
		if (intent == null || intent.getBins().isEmpty())
		{
			return Collections.emptyList();
		}
		List<BinWork> out = new ArrayList<>();
		for (CompostBin.Def d : CompostBin.ALL)
		{
			if (!intent.getBins().contains(d.getBinId()))
			{
				continue;
			}
			CompostBin.Seen s = seen == null ? null : seen.get(d.getBinId());
			out.add(new BinWork(d, CompostBin.task(d, s, intent.binUpgrade(d.getBinId()), now), intent.isBinSkipped(d.getBinId()), s));
		}
		// Bins with something to do first (catalogue order within), then skipped ones, then those with nothing to do now.
		out.sort(java.util.Comparator.comparingInt(b -> b.isDue() ? 0 : b.task != null ? 1 : 2));
		return out;
	}

	/** Patches whose protection payment is due in this run (the run's own choice from Start; skipped ones are not). */
	public static List<String> protectionDue(FarmingRunIntent intent, FarmingRunEvaluator.Progress progress)
	{
		if (intent == null || progress == null || intent.getProtectTypes() == null)
		{
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		for (FarmingRunEvaluator.PatchProgress p : progress.getPatches().values())
		{
			if (FarmingWording.protectionPending(p, intent, null))
			{
				out.add(p.getPatchId());
			}
		}
		return out;
	}

	/** The run's compost method for this patch ("SUPERCOMPOST", "FERTILE_SOIL" ...), or null when none was chosen. */
	public static String compostMethod(FarmingRunIntent intent, FarmingPatchDef def)
	{
		if (intent == null || def == null || intent.getOptions() == null)
		{
			return null;
		}
		String c = intent.getOptions().compostFor(def.getType());
		return c == null || "NONE".equals(c) ? null : c;
	}

	/**
	 * The compost the game showed for the planting this run makes (canonical §28.17), or null: a record made on the bare patch
	 * (it carries into the planting; a harvest or clear after it ends it), or one made on the crop planted during this run.
	 * The previous crop's compost never counts for the new planting, even while that crop is still being harvested.
	 */
	public static PatchCompost compostForRun(FarmingRunIntent intent, String patchId, Map<String, PatchKnowledge> knowledge,
		Map<String, PatchCompost> compost)
	{
		PatchCompost rec = compost == null ? null : compost.get(patchId);
		PatchKnowledge k = knowledge == null ? null : knowledge.get(patchId);
		PatchCompost cur = rec == null ? null : rec.current(k);
		if (cur == null || intent == null)
		{
			return null;
		}
		if (cur.getCycleFirstSeenAt() == null)
		{
			return cur;
		}
		return k != null && k.getCycle() != null && k.getCycle().getFirstSeenAt() >= intent.getStartedAt() ? cur : null;
	}

	/**
	 * Compost still to apply for this run: the run plants this patch with a compost method chosen, the player has not skipped
	 * it, and the game has not shown compost on this planting (Inspect's "not treated" is still to do). Planting does not
	 * settle it: compost may follow the seed (OSRS Wiki, Farming: "before (or immediately after) planting").
	 */
	public static boolean compostDue(FarmingRunIntent intent, FarmingRunEvaluator.PatchProgress p, PatchCompost forRun)
	{
		if (intent == null || p == null || !p.isPlantPlanned() || p.getOutcome() == FarmingRunEvaluator.Outcome.SKIPPED
			|| p.getOutcome() == FarmingRunEvaluator.Outcome.NOT_APPLICABLE || intent.isCompostSkipped(p.getPatchId())
			|| compostMethod(intent, p.getPatch()) == null)
		{
			return false;
		}
		return forRun == null || forRun.getKind() == PatchCompost.Kind.NONE;
	}

	/** Planted this run, compost still due: a task after planting (runs with optional work recorded at Start only). */
	public static List<String> compostDueAfterPlanting(FarmingRunIntent intent, FarmingRunEvaluator.Progress progress,
		Map<String, PatchKnowledge> knowledge, Map<String, PatchCompost> compost)
	{
		if (intent == null || progress == null || intent.getProtectTypes() == null)
		{
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		for (FarmingRunEvaluator.PatchProgress p : progress.getPatches().values())
		{
			boolean planted = p.getDetail() == FarmingRunEvaluator.Detail.REPLANTED || p.getDetail() == FarmingRunEvaluator.Detail.PLANTED_UNSEEN;
			if (planted && compostDue(intent, p, compostForRun(intent, p.getPatchId(), knowledge, compost)))
			{
				out.add(p.getPatchId());
			}
		}
		return out;
	}

	/** Every crop patch settled, no protection due and no included bin with a task now: the run is complete. */
	public static boolean complete(FarmingRunIntent intent, FarmingRunEvaluator.Progress progress, Map<String, CompostBin.Seen> seen, long now)
	{
		return complete(intent, progress, seen, Collections.emptyMap(), Collections.emptyMap(), now);
	}

	/** As above, and no compost still due on a patch planted this run (§28.17). */
	public static boolean complete(FarmingRunIntent intent, FarmingRunEvaluator.Progress progress, Map<String, CompostBin.Seen> seen,
		Map<String, PatchKnowledge> knowledge, Map<String, PatchCompost> compost, long now)
	{
		// A compost-bin run has no patches: its bins decide (§28.17).
		boolean cropsSettled = progress != null && (progress.getPatches().isEmpty() ? intent != null && !intent.getBins().isEmpty() : progress.isAllSettled());
		if (progress == null || !cropsSettled || !protectionDue(intent, progress).isEmpty()
			|| !compostDueAfterPlanting(intent, progress, knowledge, compost).isEmpty())
		{
			return false;
		}
		for (BinWork b : bins(intent, seen, now))
		{
			if (b.isDue())
			{
				return false;
			}
		}
		return true;
	}

	/** The bins of this run that are due, keyed by bin id (for the plan's stops). */
	public static Map<String, BinWork> due(FarmingRunIntent intent, Map<String, CompostBin.Seen> seen, long now)
	{
		Map<String, BinWork> out = new LinkedHashMap<>();
		for (BinWork b : bins(intent, seen, now))
		{
			if (b.isDue())
			{
				out.put(b.def.getBinId(), b);
			}
		}
		return out;
	}

	/**
	 * Bins of the run worked during it: what the game shows now differs from the first read in the run in a way rotting alone
	 * never makes (rotting to rotted, or one rotting value to the next, happens by itself). Unread bins count for nothing.
	 */
	public static int binsWorked(FarmingRunIntent intent, Map<String, CompostBin.Seen> now)
	{
		if (intent == null || intent.getBinStart() == null || now == null)
		{
			return 0;
		}
		int n = 0;
		for (Map.Entry<String, Integer> e : intent.getBinStart().entrySet())
		{
			CompostBin.Def d = CompostBin.def(e.getKey());
			CompostBin.Seen s = now.get(e.getKey());
			if (d == null || s == null || s.getRaw() == e.getValue())
			{
				continue;
			}
			CompostBin.State from = CompostBin.decode(e.getValue(), d.isBig());
			CompostBin.State to = s.state(d);
			boolean rottingOnItsOwn = from != null && to != null && from.phase == CompostBin.Phase.ROTTING
				&& (to.phase == CompostBin.Phase.ROTTING || to.phase == CompostBin.Phase.ROTTED) && from.contents == to.contents;
			n += rottingOnItsOwn ? 0 : 1;
		}
		return n;
	}

	public static int binsSkipped(FarmingRunIntent intent)
	{
		int n = 0;
		for (String b : intent.getBins())
		{
			n += intent.isBinSkipped(b) ? 1 : 0;
		}
		return n;
	}
}
