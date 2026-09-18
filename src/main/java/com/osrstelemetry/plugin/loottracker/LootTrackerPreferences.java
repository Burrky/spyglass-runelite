package com.osrstelemetry.plugin.loottracker;

import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Every piece of Loot Tracker UI PREFERENCE state that must survive a
 * restart, kept entirely SEPARATE from {@link LootTrackerIndex} (pure
 * telemetry-derived truth) -- the same "durable telemetry vs. durable
 * UI preference" split TelemetryPaths already draws between
 * {@code events.jsonl} and every {@code stateFile()} document. Nothing
 * in this class is telemetry: losing this file loses favorites/hidden
 * items/collapsed cards/reset points, never any actual loot history
 * (that always survives, unconditionally, in events.jsonl).
 *
 * FAVORITES ARE SOURCES, NOT ITEMS (spec requirement): {@link #favoriteSourceKeys}
 * holds {@link LootTrackerIndex#sourceKey} values.
 *
 * HIDDEN ITEMS ARE SOURCE-SPECIFIC (spec requirement): "hide" removes
 * one item from one source's PRESENTATION only -- see
 * {@link #isItemHidden}/{@link #hideItem}. Hidden loot still counts
 * toward every total (this class has no opinion on totals at all; it
 * only answers "hidden or not" -- the UI/valuation layer is what
 * decides whether to still include a hidden item's value, and the spec
 * requires that it does).
 *
 * RESET IS A LOGICAL WATERMARK, NEVER A DELETE (spec requirement):
 * {@link #getResetWatermark}/{@link #resetSource} store a per-source
 * Instant, "forget everything AT OR BEFORE this point" for DISPLAY
 * purposes only. {@link LootTrackerIndex} itself is never told about
 * this and never has anything removed from it -- the watermark is
 * applied at the UI-facing snapshot layer (see the Loot Tracker view
 * model), which is what makes a reset survive a full durable rebuild
 * (a rebuild re-applies every historical record exactly as before; the
 * watermark alone is what keeps them display-suppressed) without this
 * class or {@link LootTrackerIndex} needing to coordinate rebuild
 * ordering with each other at all.
 *
 * PERSISTENCE: one small JSON document, {@code loot_tracker_preferences.json},
 * written via the existing {@link LocalStateStore} (async, atomic
 * rename -- same durability guarantees every other state document in
 * this project already gets). An in-memory copy is kept authoritative
 * between writes (read-your-own-write consistency for the UI, which
 * polls this class far more often than any write actually happens) --
 * mirrors LiveXpTracker/lastKnownAbsoluteXpBySkill's own "authoritative
 * in-memory, asynchronously durable" split elsewhere in this project.
 */
@Singleton
public final class LootTrackerPreferences
{
	private static final String CATEGORY = "loot_tracker_preferences";

	/** The exact document persisted to disk -- a plain data holder, deliberately with no behavior of its own. */
	static final class Document
	{
		Set<String> favoriteSourceKeys = new HashSet<>();
		Map<String, Set<Integer>> hiddenItemIdsBySourceKey = new HashMap<>();
		Set<String> collapsedSourceKeys = new HashSet<>();
		/** sourceKey -> ISO-8601 Instant string (this project's established timestamp convention). */
		Map<String, String> resetWatermarkBySourceKey = new HashMap<>();
		/** Spec: "Show hidden" toggle, default OFF. */
		boolean showHidden = false;
	}

	private static final long NO_ACCOUNT = -1L;

	private final LocalStateStore store;
	private long accountHash = NO_ACCOUNT;
	private Document document = new Document();

	@Inject
	public LootTrackerPreferences(LocalStateStore store)
	{
		this.store = store;
	}

	/**
	 * Loads this account's persisted preferences (or starts a fresh,
	 * all-default document if none exist yet -- a brand-new account, or
	 * a pre-Phase-2 install that never had this file). Safe to call on
	 * EVERY {@code OsrsTelemetryPlugin.handleLogin()} transition
	 * unconditionally (mirrors {@code SessionRuntimeCoordinator.
	 * ensureAccountLoaded()}'s own same-account-is-a-no-op contract): a
	 * same-account call (a world hop/relog, or a duplicate fresh-start
	 * call) is a deliberate no-op, so an in-flight but not-yet-durably-
	 * written preference mutation from just before the hop is never
	 * discarded by re-reading a possibly-stale copy off disk. A best-
	 * effort synchronous read (see LocalStateStore.readIfExists()'s own
	 * fail-open javadoc), acceptable here for the exact same reason it's
	 * already acceptable there: a single small JSON document, read at
	 * most once per genuine account change, not on any hot path.
	 */
	public synchronized void loadForAccount(long accountHash)
	{
		if (accountHash == this.accountHash)
		{
			return;
		}
		this.accountHash = accountHash;
		Document loaded = store.readIfExists(TelemetryPaths.stateFile(accountHash, CATEGORY), Document.class);
		this.document = loaded != null ? loaded : new Document();
		if (document.favoriteSourceKeys == null)
		{
			document.favoriteSourceKeys = new HashSet<>();
		}
		if (document.hiddenItemIdsBySourceKey == null)
		{
			document.hiddenItemIdsBySourceKey = new HashMap<>();
		}
		if (document.collapsedSourceKeys == null)
		{
			document.collapsedSourceKeys = new HashSet<>();
		}
		if (document.resetWatermarkBySourceKey == null)
		{
			document.resetWatermarkBySourceKey = new HashMap<>();
		}
	}

	private synchronized void persist()
	{
		store.write(TelemetryPaths.stateFile(accountHash, CATEGORY), document);
	}

	public synchronized boolean isFavorite(String sourceKey)
	{
		return document.favoriteSourceKeys.contains(sourceKey);
	}

	public synchronized void setFavorite(String sourceKey, boolean favorite)
	{
		if (favorite)
		{
			document.favoriteSourceKeys.add(sourceKey);
		}
		else
		{
			document.favoriteSourceKeys.remove(sourceKey);
		}
		persist();
	}

	public synchronized boolean isItemHidden(String sourceKey, int itemId)
	{
		Set<Integer> hidden = document.hiddenItemIdsBySourceKey.get(sourceKey);
		return hidden != null && hidden.contains(itemId);
	}

	public synchronized void setItemHidden(String sourceKey, int itemId, boolean hidden)
	{
		if (hidden)
		{
			document.hiddenItemIdsBySourceKey.computeIfAbsent(sourceKey, k -> new HashSet<>()).add(itemId);
		}
		else
		{
			Set<Integer> set = document.hiddenItemIdsBySourceKey.get(sourceKey);
			if (set != null)
			{
				set.remove(itemId);
				if (set.isEmpty())
				{
					document.hiddenItemIdsBySourceKey.remove(sourceKey);
				}
			}
		}
		persist();
	}

	public synchronized boolean isShowHidden()
	{
		return document.showHidden;
	}

	public synchronized void setShowHidden(boolean showHidden)
	{
		document.showHidden = showHidden;
		persist();
	}

	public synchronized boolean isCollapsed(String sourceKey)
	{
		return document.collapsedSourceKeys.contains(sourceKey);
	}

	public synchronized void setCollapsed(String sourceKey, boolean collapsed)
	{
		if (collapsed)
		{
			document.collapsedSourceKeys.add(sourceKey);
		}
		else
		{
			document.collapsedSourceKeys.remove(sourceKey);
		}
		persist();
	}

	/** Null if this source has never been reset. */
	public synchronized Instant getResetWatermark(String sourceKey)
	{
		String iso = document.resetWatermarkBySourceKey.get(sourceKey);
		if (iso == null)
		{
			return null;
		}
		try
		{
			return Instant.parse(iso);
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}

	/**
	 * Requires the CALLER to have already obtained user confirmation
	 * (spec requirement -- "requires confirmation"); this method itself
	 * performs the reset unconditionally once called. {@code at} is
	 * normally {@code Instant.now()} -- passed in rather than computed
	 * here so this stays unit-testable with a fixed clock, matching this
	 * project's existing style (see CurrentSessionSnapshotTest's own
	 * fixed-Instant convention).
	 */
	public synchronized void resetSource(String sourceKey, Instant at)
	{
		document.resetWatermarkBySourceKey.put(sourceKey, at.toString());
		persist();
	}
}
