package com.osrstelemetry.plugin.skills.fishing;

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
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.game.FishingSpot;

/**
 * The Fishing companion's native wiring: the SAME shared
 * {@link SkillCompanionCollector} Mining uses (binding, bounded telemetry,
 * live view, bank view) with Fishing's tracker, plus Fishing's own click
 * evidence:
 * <ul>
 * <li>a fishing option on a fishing-spot NPC -- the spot kind comes from
 * RuneLite's native FishingSpot game data (by NPC id), never from RuneLite's
 * Fishing plugin, which does not need to be enabled -- a method HINT only;</li>
 * <li>"Fill" / "Empty" on a fish barrel, so moving fish in or out of it is
 * never mistaken for a catch.</li>
 * </ul>
 */
@Singleton
public class FishingCompanionCollector extends SkillCompanionCollector
{
	@Inject
	public FishingCompanionCollector(Client client, EventLedger eventLedger, SessionRuntimeCoordinator coordinator, LocalStateStore store)
	{
		this(client, eventLedger, coordinator::getCurrentSessionSnapshot, store);
		// Barbarian fishing's catches also grant Agility and Strength XP: its own
		// native evidence tells the session engine the activity is Fishing.
		setMethodEvidenceSink(coordinator::noteSkillMethodEvidence);
	}

	FishingCompanionCollector(Client client, EventLedger eventLedger, Supplier<SessionSnapshot> currentSession, LocalStateStore store)
	{
		super(client, eventLedger, currentSession, store, FishingCompanionDefinition.INSTANCE, new FishingCompanionTracker());
	}

	@Override
	protected void setClock(LongSupplier clock)
	{
		super.setClock(clock);
	}

	@Override
	protected void setMethodEvidenceSink(java.util.function.BiConsumer<String, java.time.Instant> sink)
	{
		super.setMethodEvidenceSink(sink);
	}

	FishingCompanionTracker fishing()
	{
		return (FishingCompanionTracker) tracker;
	}

	@Override
	protected void onMenuOption(String option, String target, MenuOptionClicked event)
	{
		String t = target == null ? "" : target.toLowerCase(java.util.Locale.ROOT);
		if (t.contains("barrel"))
		{
			if (option.equalsIgnoreCase("Fill"))
			{
				fishing().onBarrelFill(client.getTickCount());
			}
			else if (option.equalsIgnoreCase("Empty"))
			{
				fishing().onBarrelEmpty(client.getTickCount());
			}
			return;
		}
		String spot = spotName(event);
		if (spot != null)
		{
			fishing().onSpotClick(clock.getAsLong(), spot, option);
		}
	}

	/** The clicked NPC's fishing-spot kind (RuneLite FishingSpot name, aliases folded), or null. */
	static String spotName(MenuOptionClicked event)
	{
		MenuEntry entry = event == null ? null : event.getMenuEntry();
		NPC npc = entry == null ? null : entry.getNpc();
		if (npc == null)
		{
			return null;
		}
		return spotKind(FishingSpot.findSpot(npc.getId()));
	}

	/** Tutorial / quest copies of a spot behave like the ordinary spot of that kind. */
	static String spotKind(FishingSpot spot)
	{
		if (spot == null)
		{
			return null;
		}
		switch (spot.name())
		{
			case "TUTORIAL_SHRIMP":
				return "SHRIMP";
			case "ETCETERIA_LOBSTER":
				return "LOBSTER";
			default:
				return spot.name();
		}
	}
}
