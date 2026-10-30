package com.osrstelemetry.plugin.skills.smithing;

import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.session.SessionRuntimeCoordinator;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillCompanionCollector;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;

/**
 * The Smithing companion's native wiring (Skills Phase 7): the SAME shared
 * {@link SkillCompanionCollector} (binding to SKILLING/smithing only, bounded
 * telemetry -- exact outputs, exact consumed inputs, per-recipe output units
 * and observed failures --, live view, bank view) with Smithing's tracker,
 * plus its own native evidence, all event-driven:
 * <ul>
 * <li>worn equipment (ring of forging, ice gloves), read once per tick;</li>
 * <li>the BLAST FURNACE's own varbits (furnace ore / coal, dispenser bars,
 * hot bars) through VarbitChanged, with one baseline read per login;</li>
 * <li>coal bag Fill / Empty clicks (its contents have no native state).</li>
 * </ul>
 * Magic XP (Superheat) arrives through the shared other-skill hook.
 * Independent of RuneLite's own plugins; no networking.
 */
@Slf4j
@Singleton
public class SmithingCompanionCollector extends SkillCompanionCollector
{
	/** Every Blast Furnace varbit Smithing reads (furnace contents, dispenser bars, hot bars). */
	static final Set<Integer> FURNACE_VARBITS;

	static
	{
		Set<Integer> v = new HashSet<>();
		for (SmithingRecipes.FurnaceVar f : SmithingRecipes.furnaceOres())
		{
			v.add(f.getVarbit());
		}
		for (SmithingRecipes.Bar b : SmithingRecipes.bars())
		{
			if (b.getBfBarVarbit() > 0)
			{
				v.add(b.getBfBarVarbit());
			}
		}
		v.add(VarbitID.BLAST_FURNACE_BARS_HOT);
		FURNACE_VARBITS = java.util.Collections.unmodifiableSet(v);
	}

	private boolean furnaceSeeded;

	@Inject
	public SmithingCompanionCollector(Client client, EventLedger eventLedger, SessionRuntimeCoordinator coordinator, LocalStateStore store)
	{
		this(client, eventLedger, coordinator::getCurrentSessionSnapshot, store);
	}

	SmithingCompanionCollector(Client client, EventLedger eventLedger, Supplier<SessionSnapshot> currentSession, LocalStateStore store)
	{
		super(client, eventLedger, currentSession, store, SmithingCompanionDefinition.INSTANCE, new SmithingCompanionTracker());
	}

	@Override
	protected void setClock(LongSupplier clock)
	{
		super.setClock(clock);
	}

	SmithingCompanionTracker smithing()
	{
		return (SmithingCompanionTracker) tracker;
	}

	@Override
	public void startUp()
	{
		furnaceSeeded = false;
		super.startUp();
	}

	@Override
	public void resetForAccountSwitch()
	{
		furnaceSeeded = false;
		super.resetForAccountSwitch();
	}

	/**
	 * The shared tick, with worn items and (once per login) the Blast Furnace
	 * baseline read FIRST, so this tick's settlement already knows them.
	 * Deliberately NOT {@code @Subscribe}: the inherited subscription already
	 * delivers every GameTick here, and RuneLite would run a re-annotated
	 * override twice.
	 */
	@Override
	public void onGameTick(GameTick event)
	{
		readSmithingState();
		super.onGameTick(event);
	}

	private void readSmithingState()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		ItemContainer wornContainer = client.getItemContainer(InventoryID.WORN);
		Set<Integer> worn = new HashSet<>();
		if (wornContainer != null)
		{
			for (Item item : wornContainer.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					worn.add(item.getId());
				}
			}
		}
		smithing().setWorn(worn);
		if (!furnaceSeeded)
		{
			furnaceSeeded = true;
			long now = clock.getAsLong();
			for (Integer varbit : FURNACE_VARBITS)
			{
				try
				{
					smithing().onFurnaceVar(client.getTickCount(), now, varbit, client.getVarbitValue(varbit));
				}
				catch (RuntimeException e)
				{
					log.debug("Smithing companion could not read varbit {}", varbit, e);
				}
			}
		}
	}

	/** The shared lifecycle, then a fresh Blast Furnace baseline at the next login. Inherited subscription: never re-annotated. */
	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		super.onGameStateChanged(event);
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			furnaceSeeded = false;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int varbit = event.getVarbitId();
		if (varbit > 0 && FURNACE_VARBITS.contains(varbit))
		{
			smithing().onFurnaceVar(client.getTickCount(), clock.getAsLong(), varbit, event.getValue());
		}
	}

	/** A coal bag's own Fill / Empty: the coal change that follows is storage movement, never consumption. */
	@Override
	protected void onMenuOption(String option, String target, MenuOptionClicked event)
	{
		if (target != null && target.toLowerCase(Locale.ROOT).contains("coal bag"))
		{
			smithing().onCoalBagOption(client.getTickCount(), option);
		}
	}

	/** Exact names of every Blast Furnace varbit Smithing reads (tests / diagnostics). */
	static List<Integer> furnaceVarbits()
	{
		return new ArrayList<>(FURNACE_VARBITS);
	}
}
