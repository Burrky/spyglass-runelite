package com.osrstelemetry.plugin.farming;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a patch's current (or next) planting is composted, and with what (canonical §28.14), from the game's own words
 * only. One record per patch, tied to the crop cycle it belongs to; immutable and saved with the patch knowledge
 * ({@code compost.json}, per account and profile).
 *
 * <p>Evidence (message texts verified in Josh's client log 2026-10-22; patterns as RuneLite's CompostTracker, master
 * @ e98399f931c6, BSD-2):
 * <ul>
 *   <li>"You treat the herb patch with supercompost." (a bucket, or a bottomless bucket's fill): applied.</li>
 *   <li>"The herb patch has been treated with supercompost." (Fertile Soil): applied.</li>
 *   <li>"This flower patch has already been treated with ultracompost.": applied (seen again).</li>
 *   <li>"This is a herb patch. The soil has been treated with ultracompost. ...": applied (Inspect).</li>
 *   <li>"This is an allotment. The soil has not been treated. ...": confirmed not composted (Inspect).</li>
 * </ul>
 * The message names the kind of patch, not which one: the patch is the one the player chose with that item, spell or
 * Inspect within 30 seconds, and stands beside (selecting the item or clicking alone is never evidence).
 *
 * <p>Life: applied to a bare patch, it belongs to the next planting (kept when the crop goes in); applied to a crop, to
 * that crop. A cycle closing (harvested out, cleared) ends it. A later, stronger sighting (Inspect, "already treated")
 * replaces an older one.
 */
public final class PatchCompost
{
	public enum Kind
	{
		COMPOST("Compost"),
		SUPERCOMPOST("Supercompost"),
		ULTRACOMPOST("Ultracompost"),
		/** Inspect said the soil has not been treated. */
		NONE("Not composted");

		private final String label;

		Kind(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}

		/** The run card's step name, short enough for the step line at sidebar width ("Super", "Ultra"). */
		public String getStepLabel()
		{
			return this == SUPERCOMPOST ? "Super" : this == ULTRACOMPOST ? "Ultra" : label;
		}
	}

	/** How the game showed it. */
	public enum Source
	{
		APPLIED,
		FERTILE_SOIL,
		ALREADY_TREATED,
		INSPECTED
	}

	static final Pattern APPLIED = Pattern.compile("^You treat the (.+?) with (ultra|super|)compost\\.$");
	static final Pattern FERTILE_SOIL = Pattern.compile("^The (.+?) has been treated with (ultra|super|)compost");
	static final Pattern ALREADY = Pattern.compile("^This (.+?) has already been (?:treated|fertilised) with (ultra|super|)compost");
	static final Pattern INSPECT_TREATED = Pattern.compile("^This is an? (.+?)\\. The soil has been treated with (ultra|super|)compost\\.");
	static final Pattern INSPECT_UNTREATED = Pattern.compile("^This is an? (.+?)\\. The soil has not been treated\\.");

	/** Field names are the saved JSON. */
	private Kind kind;
	private Source source;
	private long at;
	/** The crop cycle it belongs to (its first sighting), or null when applied to a bare patch (the next planting's). */
	private Long cycleFirstSeenAt;

	PatchCompost()
	{
	}

	PatchCompost(Kind kind, Source source, long at, Long cycleFirstSeenAt)
	{
		this.kind = kind;
		this.source = source;
		this.at = at;
		this.cycleFirstSeenAt = cycleFirstSeenAt;
	}

	public Kind getKind()
	{
		return kind;
	}

	public Source getSource()
	{
		return source;
	}

	public long getAt()
	{
		return at;
	}

	public Long getCycleFirstSeenAt()
	{
		return cycleFirstSeenAt;
	}

	/** A compost message read: the kind, the source and the patch noun it names ("herb patch", "allotment"); null for others. */
	public static final class Message
	{
		public final Kind kind;
		public final Source source;
		public final String patchNoun;

		Message(Kind kind, Source source, String patchNoun)
		{
			this.kind = kind;
			this.source = source;
			this.patchNoun = patchNoun;
		}
	}

	public static Message parse(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String m = raw.replaceAll("<[^>]*>", "").trim();
		Matcher x;
		if ((x = APPLIED.matcher(m)).find())
		{
			return new Message(kind(x.group(2)), Source.APPLIED, x.group(1));
		}
		if ((x = FERTILE_SOIL.matcher(m)).find())
		{
			return new Message(kind(x.group(2)), Source.FERTILE_SOIL, x.group(1));
		}
		if ((x = ALREADY.matcher(m)).find())
		{
			return new Message(kind(x.group(2)), Source.ALREADY_TREATED, x.group(1));
		}
		if ((x = INSPECT_TREATED.matcher(m)).find())
		{
			return new Message(kind(x.group(2)), Source.INSPECTED, x.group(1));
		}
		if ((x = INSPECT_UNTREATED.matcher(m)).find())
		{
			return new Message(Kind.NONE, Source.INSPECTED, x.group(1));
		}
		return null;
	}

	private static Kind kind(String prefix)
	{
		switch (prefix.toLowerCase(Locale.ROOT))
		{
			case "ultra":
				return Kind.ULTRACOMPOST;
			case "super":
				return Kind.SUPERCOMPOST;
			default:
				return Kind.COMPOST;
		}
	}

	/** The record for a message read now at a patch with this knowledge (bare: the next planting's). */
	static PatchCompost of(Message m, long at, PatchKnowledge k, FarmingPatchDef def)
	{
		Long cycle = null;
		if (k != null && def != null && k.getCycle() != null && !k.isDecodeStale()
			&& FarmingDecoder.decode(def.getType(), k.getLastRaw()).getCondition().holdsCrop())
		{
			cycle = k.getCycle().getFirstSeenAt();
		}
		return new PatchCompost(m.kind, m.source, at, cycle);
	}

	/**
	 * This record for the patch as it is now, or null when it no longer applies: its crop cycle ended (or was replaced),
	 * or, applied to a bare patch, a cycle closed after it (that planting has come and gone).
	 */
	public PatchCompost current(PatchKnowledge k)
	{
		if (k == null)
		{
			return cycleFirstSeenAt == null ? this : null;
		}
		ClosedCropCycle closed = k.getLastClosedCycle();
		if (cycleFirstSeenAt != null)
		{
			return k.getCycle() != null && k.getCycle().getFirstSeenAt() == cycleFirstSeenAt ? this : null;
		}
		if (closed != null && closed.getClosedAt() > at)
		{
			return null; // the planting it was for has been harvested or cleared since
		}
		return this; // still bare, or planted since: the planting it was put down for
	}

	/** Whether this one should replace {@code old} (a newer sighting of the same planting, or a newer planting). */
	boolean supersedes(PatchCompost old)
	{
		return old == null || at >= old.at;
	}
}
