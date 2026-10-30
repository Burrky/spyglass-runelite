package com.osrstelemetry.plugin;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountCapabilityAdapter;
import com.osrstelemetry.plugin.account.AccountOwner;
import com.osrstelemetry.plugin.farming.FarmingCaptureAdapter;
import com.osrstelemetry.plugin.farming.FarmingOwner;
import com.osrstelemetry.plugin.farming.FarmingSnapshot;
import com.osrstelemetry.plugin.farming.FarmingTravel;
import com.osrstelemetry.plugin.travel.TransportAvailability;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportDiagnostics;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import com.osrstelemetry.plugin.account.EvidenceSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.eventbus.Subscribe;

/**
 * Plugin glue for Phase F: loads the generic transport catalogue, tells the
 * account adapter which quests to read, connects Farming's evidence to the
 * generic evaluator (only when Farming's owner is the same account and profile),
 * and serves the developer diagnostic {@code ::spyglass-travel}.
 *
 * Developer mode only; nothing here reaches a player screen. No guidance,
 * preparation, routing or travel execution (later phases).
 */
@Slf4j
@Singleton
public class TravelCapabilityWiring
{
	public static final String COMMAND = "spyglass-travel";

	private final Client client;
	private final Gson gson;
	private final AccountCapabilityAdapter account;
	private final FarmingCaptureAdapter farming;
	private final boolean developerMode;
	LongSupplier clock = System::currentTimeMillis;

	private volatile TransportCatalog catalog;

	@Inject
	public TravelCapabilityWiring(Client client, Gson gson, AccountCapabilityAdapter account, FarmingCaptureAdapter farming,
		@Named("developerMode") boolean developerMode)
	{
		this.client = client;
		this.gson = gson;
		// The bundled route table is read with the client's Gson (Plugin Hub rule), installed before anything routes.
		com.osrstelemetry.plugin.navigation.RouteTable.useGson(gson);
		this.account = account;
		this.farming = farming;
		this.developerMode = developerMode;
	}

	/** Loads the catalogue once per enable. A broken catalogue disables Phase F only, never the plugin. */
	public void start()
	{
		try
		{
			TransportCatalog loaded = TransportCatalog.load(gson);
			catalog = loaded;
			java.util.Set<net.runelite.api.Quest> quests = java.util.EnumSet.noneOf(net.runelite.api.Quest.class);
			quests.addAll(loaded.requiredQuests());
			for (com.osrstelemetry.plugin.account.RequirementSet r : com.osrstelemetry.plugin.farming.FarmingAccess.all().values())
			{
				for (com.osrstelemetry.plugin.account.RequirementSet.QuestReq q : com.osrstelemetry.plugin.account.RequirementSet.list(r.quests))
				{
					quests.add(net.runelite.api.Quest.valueOf(q.quest));
				}
			}
			// Navigation (canonical §28.10): the quests, varbits and varps doors, shortcuts and boats check.
			com.osrstelemetry.plugin.navigation.TransitionCatalog nav = com.osrstelemetry.plugin.navigation.TransitionCatalog.bundled();
			quests.addAll(nav.quests());
			account.setQuestsOfInterest(quests);
			// Plus the account variables catalogue entries name as evidence ("varp:4182&16384": a built quetzal landing).
			java.util.Set<Integer> varps = new java.util.LinkedHashSet<>(nav.varbits(true));
			java.util.Set<Integer> catalogVarbits = new java.util.LinkedHashSet<>();
			for (com.osrstelemetry.plugin.travel.TransportDef t : loaded.all())
			{
				for (com.osrstelemetry.plugin.account.RequirementSet.EvidenceReq e
					: com.osrstelemetry.plugin.account.RequirementSet.list(t.requirements().evidence))
				{
					java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(varp|varbit):(\\d+)").matcher(e.key);
					if (m.find())
					{
						(m.group(1).equals("varp") ? varps : catalogVarbits).add(Integer.parseInt(m.group(2)));
					}
				}
			}
			account.setVarpsOfInterest(varps);
			// Farming's unlock varbits used by preparation (dibber / rake), read only once account data is loaded.
			// Plus the Farming Guild contract and the tool leprechaun's storage (FarmingAccountFacts).
			java.util.Set<Integer> varbits = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
				net.runelite.api.gameval.VarbitID.BRUT_FARMING_PLANTING, net.runelite.api.gameval.VarbitID.FARMING_BLOCKWEEDS,
				// Fertile Soil's ultracompost upgrade (the ash covered tome; the spellbook tooltip script reads it, §28.16).
				net.runelite.api.gameval.VarbitID.FOSSIL_MINE_UNLOCKED_ULTRAFERTILESOIL));
			for (int v : com.osrstelemetry.plugin.farming.FarmingAccountFacts.varbits())
			{
				varbits.add(v);
			}
			varbits.addAll(nav.varbits(false));
			varbits.addAll(catalogVarbits);
			account.setVarbitsOfInterest(varbits);
		}
		catch (RuntimeException e)
		{
			catalog = null;
			log.error("Spyglass travel: transport catalogue failed to load; account travel availability is off", e);
		}
	}

	public TransportCatalog catalog()
	{
		return catalog;
	}

	/** Every transport's availability for the current account (developer use; pure evaluation over snapshots). */
	public List<TransportAvailability> evaluate()
	{
		TransportCatalog c = catalog;
		if (c == null)
		{
			return new ArrayList<>();
		}
		AccountCapability cap = account.capability();
		return TransportEvaluator.evaluateAll(c, cap, evidence(cap.getOwner(), farming.uiSnapshot(), clock.getAsLong()), clock.getAsLong());
	}

	/** Farming's account facts (patch access, contract, tool leprechaun) for the UI, owned by the visible account. */
	public com.osrstelemetry.plugin.farming.FarmingAccountFacts farmingFacts()
	{
		AccountCapability cap = account.capability();
		return com.osrstelemetry.plugin.farming.FarmingAccountFacts.of(cap,
			evidence(cap == null ? null : cap.getOwner(), farming.uiSnapshot(), clock.getAsLong()));
	}

	private volatile java.util.function.Supplier<com.osrstelemetry.plugin.account.poh.PohKnowledge> house;

	/** The player's observed house (phase G): its facilities answer the POH routes' evidence. */
	public void attachHouse(java.util.function.Supplier<com.osrstelemetry.plugin.account.poh.PohKnowledge> house)
	{
		this.house = house;
	}

	/** The observed house's knowledge object (a new object after each visit), or null: part of a plan's inputs. */
	public Object houseKnowledge()
	{
		try
		{
			return house == null ? null : house.get();
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** Farming's evidence for this owner plus the observed house's (profile-scoped by its store). */
	public EvidenceSource evidence(AccountOwner owner, FarmingSnapshot snapshot, long now)
	{
		EvidenceSource f = farmingEvidence(owner, snapshot, now);
		com.osrstelemetry.plugin.account.poh.PohKnowledge k = null;
		try
		{
			k = owner == null || house == null ? null : house.get();
		}
		catch (RuntimeException e)
		{
			// unreadable: no house evidence
		}
		return k == null ? f : com.osrstelemetry.plugin.account.poh.PohKnowledge.either(f, k.evidence());
	}

	/** Farming's evidence only when its knowledge belongs to exactly this account and profile. */
	static EvidenceSource farmingEvidence(AccountOwner owner, FarmingSnapshot snapshot, long now)
	{
		if (owner == null || snapshot == null || snapshot.getState() != FarmingSnapshot.State.AVAILABLE)
		{
			return EvidenceSource.NONE;
		}
		FarmingOwner fo = snapshot.getOwner();
		if (fo == null || fo.getAccountHash() != owner.getAccountHash() || !fo.getProfileKey().equals(owner.getProfileKey()))
		{
			return EvidenceSource.NONE;
		}
		return FarmingTravel.evidence(snapshot.getKnowledge(), now);
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!developerMode || !COMMAND.equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		List<String> lines = diagnosticLines();
		for (String line : lines)
		{
			log.info("[{}] {}", COMMAND, line);
		}
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Spyglass travel: " + lines.get(lines.size() - 1) + " (full dump in client.log)", null);
	}

	List<String> diagnosticLines()
	{
		long now = clock.getAsLong();
		List<String> out = new ArrayList<>(TransportDiagnostics.capabilityLines(account.capability(), now));
		if (catalog == null)
		{
			out.add("transport catalogue not loaded");
			return out;
		}
		List<TransportAvailability> results = evaluate();
		out.addAll(TransportDiagnostics.availabilityLines(results));
		for (Map.Entry<String, List<String>> e : FarmingTravel.destinations().entrySet())
		{
			List<String> parts = new ArrayList<>();
			for (String id : e.getValue())
			{
				for (TransportAvailability a : results)
				{
					if (a.getTransport().id.equals(id))
					{
						parts.add(id + "=" + a.getState());
					}
				}
			}
			out.add("farming " + e.getKey() + ": " + String.join(", ", parts));
		}
		out.add(TransportDiagnostics.summary(results));
		return out;
	}
}
