package com.osrstelemetry.plugin.skills.agility;

import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.session.SessionRuntimeCoordinator;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillCompanionCollector;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * The Agility companion's native wiring: the SAME shared
 * {@link SkillCompanionCollector} Mining and Fishing use (session binding,
 * bounded telemetry -- now with completed laps --, live view, bank view) with
 * Agility's tracker, plus Agility's own evidence, all event-driven:
 * <ul>
 * <li>clicks on game objects (MenuOptionClicked identifier = object id): the
 * obstacle the player is attempting, a shortcut portal, a reward trigger;</li>
 * <li>the local player's tile (read once per tick and on Agility XP -- never a
 * scene scan): course end tiles, rooftop planes;</li>
 * <li>hitsplats on the local player (fall evidence, rooftops only);</li>
 * <li>the Colossal Wyrm course progress varbits.</li>
 * </ul>
 * Independent of RuneLite's own Agility plugin (its course data was studied,
 * never used at runtime).
 */
@Singleton
public class AgilityCompanionCollector extends SkillCompanionCollector
{
	@Inject
	public AgilityCompanionCollector(Client client, EventLedger eventLedger, SessionRuntimeCoordinator coordinator, LocalStateStore store)
	{
		this(client, eventLedger, coordinator::getCurrentSessionSnapshot, store);
	}

	AgilityCompanionCollector(Client client, EventLedger eventLedger, Supplier<SessionSnapshot> currentSession, LocalStateStore store)
	{
		super(client, eventLedger, currentSession, store, AgilityCompanionDefinition.INSTANCE, new AgilityCompanionTracker());
	}

	@Override
	protected void setClock(LongSupplier clock)
	{
		super.setClock(clock);
	}

	AgilityCompanionTracker agility()
	{
		return (AgilityCompanionTracker) tracker;
	}

	@Override
	protected void onMenuOption(String option, String target, MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();
		if (action == MenuAction.GAME_OBJECT_FIRST_OPTION || action == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| action == MenuAction.GAME_OBJECT_THIRD_OPTION || action == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| action == MenuAction.GAME_OBJECT_FIFTH_OPTION)
		{
			agility().onObjectClick(client.getTickCount(), clock.getAsLong(), event.getId());
		}
	}

	@Override
	protected void onPlayerPosition(int tick, long nowMillis, WorldPoint position)
	{
		if (position != null)
		{
			agility().onPosition(tick, nowMillis, position.getX(), position.getY(), position.getPlane(), position.getRegionID());
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor actor = event.getActor();
		if (actor != null && actor == client.getLocalPlayer() && event.getHitsplat() != null && event.getHitsplat().getAmount() > 0)
		{
			agility().onLocalDamage(client.getTickCount());
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() > 0 && AgilityCourses.completingOnVarbit(event.getVarbitId()) != null)
		{
			agility().onVarbit(event.getVarbitId(), event.getValue(), clock.getAsLong());
		}
	}
}
