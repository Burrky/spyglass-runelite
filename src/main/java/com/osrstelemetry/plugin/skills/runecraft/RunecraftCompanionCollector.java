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
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
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

	@Subscribe
	public void onRunecraftTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			pouchStateRead = false;
			return;
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
