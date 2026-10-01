package com.osrstelemetry.plugin.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * View-local expand/collapse state for Current Session's Slayer task
 * ledger cards, keyed by the assignment instance's stable per-session
 * ordinal -- never persisted, never part of session data.
 *
 * Defaults: the CURRENT (latest) assignment is expanded, every older one
 * collapsed. A player click stores an override that survives the panel's
 * ordinary 1-second re-renders. When a NEW assignment becomes current,
 * the previous current card drops its override (so it falls back to
 * collapsed) and the new one starts expanded; overrides on older cards
 * are kept. A different sessionId resets everything.
 */
final class SlayerLedgerCollapseState
{
	private String sessionId;
	private Integer currentOrdinal;
	private final Map<Integer, Boolean> overrides = new HashMap<>();

	/** Call once per render, before any isExpanded() query. */
	void sync(String sessionId, Integer currentOrdinal)
	{
		if (!Objects.equals(sessionId, this.sessionId))
		{
			this.sessionId = sessionId;
			this.currentOrdinal = currentOrdinal;
			overrides.clear();
			return;
		}
		if (!Objects.equals(currentOrdinal, this.currentOrdinal))
		{
			if (this.currentOrdinal != null)
			{
				overrides.remove(this.currentOrdinal);
			}
			if (currentOrdinal != null)
			{
				overrides.remove(currentOrdinal);
			}
			this.currentOrdinal = currentOrdinal;
		}
	}

	boolean isExpanded(int ordinal)
	{
		Boolean override = overrides.get(ordinal);
		return override != null ? override : Objects.equals(ordinal, currentOrdinal);
	}

	void toggle(int ordinal)
	{
		overrides.put(ordinal, !isExpanded(ordinal));
	}
}
