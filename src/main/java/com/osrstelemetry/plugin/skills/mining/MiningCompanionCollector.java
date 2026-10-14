package com.osrstelemetry.plugin.skills.mining;

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
import net.runelite.api.events.MenuOptionClicked;

/**
 * The Mining companion's native wiring: the shared
 * {@link SkillCompanionCollector} (binding, bounded telemetry, live view,
 * bank view) with Mining's tracker, plus Mining's own click evidence -- a
 * "Mine" / "Quarry" on a named rock, a method hint only. Independent of
 * RuneLite's own Mining plugin.
 */
@Singleton
public class MiningCompanionCollector extends SkillCompanionCollector
{
	@Inject
	public MiningCompanionCollector(Client client, EventLedger eventLedger, SessionRuntimeCoordinator coordinator, LocalStateStore store)
	{
		this(client, eventLedger, coordinator::getCurrentSessionSnapshot, store);
	}

	MiningCompanionCollector(Client client, EventLedger eventLedger, Supplier<SessionSnapshot> currentSession, LocalStateStore store)
	{
		super(client, eventLedger, currentSession, store, MiningCompanionDefinition.INSTANCE, new MiningCompanionTracker());
	}

	@Override
	protected void setClock(LongSupplier clock)
	{
		super.setClock(clock);
	}

	@Override
	protected void onMenuOption(String option, String target, MenuOptionClicked event)
	{
		if (option.equalsIgnoreCase("Mine") || option.equalsIgnoreCase("Quarry"))
		{
			((MiningCompanionTracker) tracker).onMineClick(clock.getAsLong(), target);
		}
	}
}
