package com.osrstelemetry.plugin.model;

import lombok.Data;

/**
 * One document per activityId (a boss, raid, or repeatable encounter).
 * activityId is a free-form slug, not an enum — a new boss added to the
 * game needs zero code change here, just a new document.
 *
 * The authoritative/inferred distinction is load-bearing (see Step 3
 * audit): where Jagex prints an official count line in the chatbox or
 * reward interface, source=AUTHORITATIVE and killCount is set.
 * Otherwise source=INFERRED and killCountEstimate is set instead —
 * deliberately a different field name so nothing downstream can read
 * an estimate as a hard number by accident.
 */
@Data
public class ActivityState
{
	public enum Source
	{
		AUTHORITATIVE,
		INFERRED
	}

	private final String activityId;
	private String displayName;
	private Source source;

	// set only when source == AUTHORITATIVE
	private Integer killCount;

	// set only when source == INFERRED
	private Integer killCountEstimate;
	private String trackingStartedAt;
	private String note;

	private String lastUpdatedAt;
}
