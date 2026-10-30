package com.osrstelemetry.plugin.skills.runecraft;

import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.session.SessionRuntimeCoordinator;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillCompanionCollector;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;

/**
 * The Runecraft companion's native wiring: the SAME shared
 * {@link SkillCompanionCollector} (session binding to SKILLING/runecraft only,
 * bounded telemetry -- produced runes, consumed inputs, trips, per-trip
 * yields, binds --, live view, bank view) with Runecraft's tracker, plus its
 * own native evidence, all event-driven:
 * <ul>
 * <li>pouch contents and degradation from the game's own varbits / varps
 * (VarbitChanged, plus one read when enabled mid-game);</li>
 * <li>the real Runecraft level (colossal pouch capacity) and whether a binding
 * necklace is worn, read once per tick;</li>
 * <li>Mining / Crafting XP of the Arceuus loop reported to the session engine
 * as Runecraft context (only from the items those steps change).</li>
 * </ul>
 * Independent of RuneLite's own Runecraft plugin. Guardians of the Rift stays
 * a structured activity: nothing inside its region is produced here, and
 * production no Runecraft session claims is dropped by the shared binding rule.
 */
@Singleton
public class RunecraftCompanionCollector extends SkillCompanionCollector
{
	private boolean pouchStateRead;
	/** The rune pouch must be read again (a slot varbit changed, or not read since login). */
	private boolean runePouchDirty = true;

	/** The rune pouch's slots: type varbit, quantity varbit (the divine rune pouch has the fourth; slots 5-6 for newer pouches). */
	static final int[][] RUNE_POUCH_SLOTS = {
		{VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_QUANTITY_1},
		{VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_QUANTITY_2},
		{VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_QUANTITY_3},
		{VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_QUANTITY_4},
		{VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_QUANTITY_5},
		{VarbitID.RUNE_POUCH_TYPE_6, VarbitID.RUNE_POUCH_QUANTITY_6},
	};

	static boolean isRunePouchVarbit(int varbit)
	{
		for (int[] slot : RUNE_POUCH_SLOTS)
		{
			if (slot[0] == varbit || slot[1] == varbit)
			{
				return true;
			}
		}
		return false;
	}

	@Inject
	public RunecraftCompanionCollector(Client client, EventLedger eventLedger, SessionRuntimeCoordinator coordinator, LocalStateStore store)
	{
		this(client, eventLedger, coordinator::getCurrentSessionSnapshot, store);
		// The Arceuus loop's dense-essence mining and chiselling XP belong to the Runecraft session.
		setContextEvidenceSink(coordinator::noteSkillContextEvidence);
	}

	RunecraftCompanionCollector(Client client, EventLedger eventLedger, Supplier<SessionSnapshot> currentSession, LocalStateStore store)
	{
		super(client, eventLedger, currentSession, store, RunecraftCompanionDefinition.INSTANCE, new RunecraftCompanionTracker());
	}

	@Override
	protected void setClock(LongSupplier clock)
	{
		super.setClock(clock);
	}

	@Override
	protected void setContextEvidenceSink(ContextEvidenceSink sink)
	{
		super.setContextEvidenceSink(sink);
	}

	RunecraftCompanionTracker runecraft()
	{
		return (RunecraftCompanionTracker) tracker;
	}

	/**
	 * The shared tick, with pouch / level / necklace state read FIRST, so this
	 * tick's settlement already knows it. Deliberately NOT {@code @Subscribe}:
	 * the inherited subscription already delivers every GameTick here, and
	 * RuneLite would run a re-annotated override twice.
	 */
	@Override
	public void onGameTick(GameTick event)
	{
		readRunecraftState();
		super.onGameTick(event);
	}

	private void readRunecraftState()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			pouchStateRead = false;
			runePouchDirty = true;
			return;
		}
		if (runePouchDirty)
		{
			runePouchDirty = false;
			readRunePouch();
		}
		if (!pouchStateRead)
		{
			pouchStateRead = true;
			for (RunecraftPouch p : RunecraftPouch.values())
			{
				runecraft().setPouchAmount(p, client.getVarbitValue(p.getAmountVarbit()));
				if (p.getDegradationVar() > 0)
				{
					runecraft().setPouchDegradation(p, p.isDegradationVarbit() ? client.getVarbitValue(p.getDegradationVar())
						: client.getVarpValue(p.getDegradationVar()));
				}
			}
		}
		runecraft().setRunecraftLevel(client.getRealSkillLevel(Skill.RUNECRAFT));
		runecraft().setBindingNecklace(wears(client.getItemContainer(InventoryID.WORN), ItemID.MAGIC_EMERALD_NECKLACE));
	}

	/** The rune pouch's contents through its native slot varbits and the game's own rune enum (as RuneLite's rune pouch overlay). */
	private void readRunePouch()
	{
		try
		{
			EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
			if (runes == null)
			{
				// Not yet loadable: read again on the next pouch change rather than seeing an empty pouch.
				return;
			}
			Map<Integer, Long> contents = new HashMap<>();
			for (int[] slot : RUNE_POUCH_SLOTS)
			{
				int type = client.getVarbitValue(slot[0]);
				int qty = client.getVarbitValue(slot[1]);
				int item = type <= 0 ? -1 : runes.getIntValue(type);
				if (item > 0 && qty > 0)
				{
					contents.merge(item, (long) qty, Long::sum);
				}
			}
			runecraft().onRunePouch(client.getTickCount(), clock.getAsLong(), contents);
		}
		catch (RuntimeException e)
		{
			// Never break the companion over the pouch: runes it receives simply stay unseen.
			runePouchDirty = false;
		}
	}

	static boolean wears(ItemContainer worn, int itemId)
	{
		if (worn == null)
		{
			return false;
		}
		for (Item item : worn.getItems())
		{
			if (item != null && item.getId() == itemId)
			{
				return true;
			}
		}
		return false;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int varbit = event.getVarbitId();
		if (varbit > 0 && isRunePouchVarbit(varbit))
		{
			// Read now, on the tick it changed, so a craft into the pouch lands with its XP and essence.
			// A slot's type and quantity may arrive as two events; the per-tick net still telescopes exactly.
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				readRunePouch();
			}
			else
			{
				runePouchDirty = true;
			}
			return;
		}
		if (varbit > 0)
		{
			RunecraftPouch amount = RunecraftPouch.forAmountVarbit(varbit);
			if (amount != null)
			{
				runecraft().setPouchAmount(amount, event.getValue());
				return;
			}
			RunecraftPouch degradation = RunecraftPouch.forDegradationVarbit(varbit);
			if (degradation != null)
			{
				runecraft().setPouchDegradation(degradation, event.getValue());
			}
			return;
		}
		RunecraftPouch degradation = RunecraftPouch.forDegradationVarp(event.getVarpId());
		if (degradation != null)
		{
			runecraft().setPouchDegradation(degradation, event.getValue());
		}
	}
}
