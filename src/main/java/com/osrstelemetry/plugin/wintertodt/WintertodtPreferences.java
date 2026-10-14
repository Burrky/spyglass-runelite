package com.osrstelemetry.plugin.wintertodt;

import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Account-scoped Wintertodt PLANNING state, in this account's own
 * {@code wintertodt_planning.json} (the per-account LocalStateStore pattern of
 * LootTrackerPreferences / PestControlGoalPreferences): the playstyle (Method
 * + Focus) and the goal. Never telemetry: nothing here reaches events.jsonl.
 * <p>
 * FOCUS is remembered even while Method is Auto (the "last focus"), so leaving
 * Auto by hand restores it; a first-ever manual Method defaults Focus to XP.
 */
@Singleton
public class WintertodtPreferences
{
	private static final String CATEGORY = "wintertodt_planning";
	private static final long NO_ACCOUNT = -1L;

	/** Exactly what is persisted; enum names as strings so a renamed constant degrades to the default. */
	static final class Document
	{
		/** Pre-split single choice (AUTO, MASS_XP, ... WORLD_HOP_XP): read once for migration, then cleared. */
		String playstyle;
		String method;
		String focus;
		String family;
		int firemakingTarget = 99;
		String unique;
	}

	private final LocalStateStore store;
	private long accountHash = NO_ACCOUNT;
	private Document document = new Document();
	private volatile WintertodtGoal goal = WintertodtGoal.DEFAULT;
	private volatile WintertodtPlaystyle.Method method = WintertodtPlaystyle.Method.AUTO;
	private volatile WintertodtPlaystyle.Focus focus;

	@Inject
	public WintertodtPreferences(LocalStateStore store)
	{
		this.store = store;
	}

	/** Login / account switch (a same-account call is a no-op). */
	public synchronized void loadForAccount(long accountHash)
	{
		if (accountHash == this.accountHash)
		{
			return;
		}
		this.accountHash = accountHash;
		Document loaded = store.readIfExists(TelemetryPaths.stateFile(accountHash, CATEGORY), Document.class);
		document = loaded != null ? loaded : new Document();
		goal = new WintertodtGoal(parse(WintertodtGoal.Family.class, document.family),
			document.firemakingTarget <= 0 ? 99 : document.firemakingTarget, parse(WintertodtUnique.class, document.unique));
		WintertodtPlaystyle.Method m = parse(WintertodtPlaystyle.Method.class, document.method);
		WintertodtPlaystyle.Focus f = parse(WintertodtPlaystyle.Focus.class, document.focus);
		if (m == null && document.playstyle != null)
		{
			// Old single-choice file: migrate (never rewritten until the next change).
			Object[] migrated = WintertodtPlaystyle.migrate(document.playstyle);
			m = (WintertodtPlaystyle.Method) migrated[0];
			f = f != null ? f : (WintertodtPlaystyle.Focus) migrated[1];
		}
		method = m == null ? WintertodtPlaystyle.Method.AUTO : m;
		focus = f;
	}

	public WintertodtGoal getGoal()
	{
		return goal;
	}

	public WintertodtPlaystyle.Method getMethod()
	{
		return method;
	}

	/** The focus to SHOW (Method is Mass / Solo): the remembered one, XP when there has never been one. */
	public WintertodtPlaystyle.Focus getFocus()
	{
		return focus == null ? WintertodtPlaystyle.Focus.XP : focus;
	}

	/** The remembered focus, or null when there has never been one (diagnostics / tests). */
	WintertodtPlaystyle.Focus getRememberedFocus()
	{
		return focus;
	}

	public synchronized void setGoal(WintertodtGoal newGoal)
	{
		if (newGoal == null || newGoal.equals(goal))
		{
			return;
		}
		goal = newGoal;
		document.family = newGoal.getFamily().name();
		document.firemakingTarget = newGoal.getFiremakingTarget();
		document.unique = newGoal.getUnique().name();
		persist();
	}

	/** A manual Method. Leaving Auto keeps the remembered focus (XP the very first time); Mass <-> Solo keeps it too. */
	public synchronized void setMethod(WintertodtPlaystyle.Method newMethod)
	{
		if (newMethod == null || newMethod == method)
		{
			return;
		}
		method = newMethod;
		if (newMethod != WintertodtPlaystyle.Method.AUTO && focus == null)
		{
			focus = WintertodtPlaystyle.Focus.XP;
		}
		persist();
	}

	/** A manual Focus; the Method is unchanged. */
	public synchronized void setFocus(WintertodtPlaystyle.Focus newFocus)
	{
		if (newFocus == null || newFocus == focus)
		{
			return;
		}
		focus = newFocus;
		persist();
	}

	/**
	 * Auto resolved by inference: becomes the explicit pair -- only while the
	 * Method is still Auto (a manual choice is never overwritten, and a
	 * resolved pair never flips on later evidence).
	 */
	public synchronized boolean resolveAuto(WintertodtPlaystyle.Method inferredMethod, WintertodtPlaystyle.Focus inferredFocus)
	{
		if (method != WintertodtPlaystyle.Method.AUTO || inferredMethod == null || inferredMethod == WintertodtPlaystyle.Method.AUTO
			|| inferredFocus == null)
		{
			return false;
		}
		method = inferredMethod;
		focus = inferredFocus;
		persist();
		return true;
	}

	private void persist()
	{
		if (accountHash == NO_ACCOUNT)
		{
			return;
		}
		document.playstyle = null;
		document.method = method.name();
		document.focus = focus == null ? null : focus.name();
		store.write(TelemetryPaths.stateFile(accountHash, CATEGORY), document);
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String name)
	{
		if (name == null)
		{
			return null;
		}
		try
		{
			return Enum.valueOf(type, name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}
}
