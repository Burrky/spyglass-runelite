package com.osrstelemetry.plugin.pestcontrol;

import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Skill;

/**
 * Account-scoped Pest Control PLANNING state, in this account's own
 * {@code pest_control_goal.json} (same per-account LocalStateStore pattern
 * as LootTrackerPreferences): the selected goal and the last commendation
 * balance the game stated. Never telemetry: nothing here is written to
 * events.jsonl or a session, and changing the goal never touches History.
 */
@Singleton
public class PestControlGoalPreferences
{
	private static final String CATEGORY = "pest_control_goal";
	private static final long NO_ACCOUNT = -1L;

	/** Exactly what is persisted; enum names as strings so a renamed constant degrades to the default. */
	static final class Document
	{
		String family;
		String voidGoal;
		String voidPiece;
		String voidStyle;
		String xpSkill;
		int xpTargetLevel = 99;
		String pack;
		int packTarget = 20;
		Integer lastBalance;
		String lastBalanceAt;
	}

	private final LocalStateStore store;
	private long accountHash = NO_ACCOUNT;
	private Document document = new Document();
	private volatile PestControlGoal goal = PestControlGoal.DEFAULT;
	private volatile Integer balance;

	@Inject
	public PestControlGoalPreferences(LocalStateStore store)
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
		goal = toGoal(document);
		balance = document.lastBalance != null && document.lastBalance >= 0 ? document.lastBalance : null;
	}

	public PestControlGoal getGoal()
	{
		return goal;
	}

	/** Planning change from the panel: kept in memory at once, persisted for this account only. */
	public synchronized void setGoal(PestControlGoal newGoal)
	{
		if (newGoal == null || newGoal.equals(goal))
		{
			return;
		}
		goal = newGoal;
		document.family = newGoal.getFamily().name();
		document.voidGoal = newGoal.getVoidGoal().name();
		document.voidPiece = newGoal.getVoidPiece().name();
		document.voidStyle = newGoal.getVoidStyle().name();
		document.xpSkill = newGoal.getXpSkill().name();
		document.xpTargetLevel = newGoal.getXpTargetLevel();
		document.pack = newGoal.getPack().name();
		document.packTarget = newGoal.getPackTarget();
		persist();
	}

	/** The last commendation balance the game stated for this account, or null when never seen. */
	public Integer getBalance()
	{
		return balance;
	}

	/** The game stated the balance (message / lander overlay): remembered across sessions. */
	public synchronized void recordBalance(int points)
	{
		if (points < 0 || Integer.valueOf(points).equals(balance))
		{
			return;
		}
		balance = points;
		document.lastBalance = points;
		document.lastBalanceAt = Instant.now().toString();
		persist();
	}

	/** The collector's balance sink: a stated balance is remembered, null (no longer known) forgets it. */
	public void onBalanceStated(Integer balance)
	{
		if (balance == null)
		{
			forgetBalance();
		}
		else
		{
			recordBalance(balance);
		}
	}

	/** The balance may have changed without the game restating it (reward shop): unknown until it does. */
	public synchronized void forgetBalance()
	{
		if (balance == null)
		{
			return;
		}
		balance = null;
		document.lastBalance = null;
		document.lastBalanceAt = Instant.now().toString();
		persist();
	}

	private void persist()
	{
		if (accountHash == NO_ACCOUNT)
		{
			return;
		}
		store.write(TelemetryPaths.stateFile(accountHash, CATEGORY), document);
	}

	static PestControlGoal toGoal(Document d)
	{
		return new PestControlGoal(
			parse(PestControlGoal.Family.class, d.family),
			parse(PestControlGoal.VoidGoal.class, d.voidGoal),
			parse(VoidPiece.class, d.voidPiece),
			parse(VoidStyle.class, d.voidStyle),
			parse(Skill.class, d.xpSkill),
			d.xpTargetLevel <= 0 ? 99 : d.xpTargetLevel,
			parse(SupplyPack.class, d.pack),
			d.packTarget <= 0 ? 20 : d.packTarget);
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
