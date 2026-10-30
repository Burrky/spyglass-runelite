package com.osrstelemetry.plugin.farming;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountReaders;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.account.Known;
import com.osrstelemetry.plugin.account.RequirementCheck;
import com.osrstelemetry.plugin.account.RequirementEvaluator;
import com.osrstelemetry.plugin.account.RequirementSet;
import com.osrstelemetry.plugin.prep.ItemNeed;
import com.osrstelemetry.plugin.prep.PreparationRequest;
import com.osrstelemetry.plugin.travel.Availability;
import com.osrstelemetry.plugin.travel.TransportAvailability;
import com.osrstelemetry.plugin.travel.TransportDef;
import com.osrstelemetry.plugin.travel.TravelPlanner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * Farming Planner's preparation step (canonical §6.1, Phase H): from an open
 * run intent and evidenced availability, what to bring and how each site will
 * probably be reached. Pure: the generic pieces (requirement evaluation,
 * transport availability, the cost-only Travel Planner, need evaluation) do
 * the judging; this class only supplies Farming's data.
 *
 * <h3>What is derived</h3>
 * <ul>
 *   <li>Seeds for planned crops with sourced seed data ({@link FarmingSeeds});
 *   other planned crops are listed as notes, never guessed.</li>
 *   <li>One bucket of the chosen compost per patch being planted (compost goes
 *   on an un-composted patch; OSRS Wiki), or a filled bottomless bucket.</li>
 *   <li>Tools: a seed dibber unless Barbarian planting is unlocked
 *   ({@code BRUT_FARMING_PLANTING == 3}); a rake unless weeds are blocked
 *   ({@code FARMING_BLOCKWEEDS == 2}); a spade. Conditions as Quest Helper's
 *   herb run applies them (HerbRun, master @ 5f79193, BSD-2). An unread
 *   condition keeps the tool on the list.</li>
 *   <li>Travel: per site, the Travel Planner's choice among Farming's
 *   destinations ({@link FarmingTravel}); the chosen items, tablets, runes and
 *   fairy-ring staff become needs.</li>
 *   <li>Patch access ({@link FarmingAccess}): evidenced unmet → a warning;
 *   missing evidence → explained, never assumed.</li>
 * </ul>
 * Protection payments are not derived in Phase H (said so when selected).
 */
public final class FarmingPreparation
{
	public static final String CONSUMER = "farming";

	/** One site's planned arrival. */
	public static final class SitePlan
	{
		private final FarmingSites.Site site;
		private final TransportAvailability transport;
		private final int walkTiles;
		private final String note;
		private final List<TransportAvailability> alternatives;
		private final List<TransportAvailability> rejected;
		private final List<String> needKeys;
		private final List<String> accessUnmet;
		private final List<String> accessMissing;

		private final List<String> closedWays;
		/** Set by the plan: whether every variant that might be taken has observed charges or today's uses (§28.13). */
		Boolean chargesSeen;
		private List<FarmingTravel.Leg> legs = Collections.emptyList();
		/**
		 * Pass 32, Use current setup: journeys left out because they need another spellbook ("Harmony Island Teleport needs the
		 * Arceuus spellbook (you are on Standard)"); never planned, never offered as "bring this".
		 */
		List<String> setupLeftOut = Collections.emptyList();
		/** Pass 32: the plan was made with Use current setup. */
		boolean currentSetup;

		public List<String> getSetupLeftOut()
		{
			return setupLeftOut;
		}

		public boolean isCurrentSetup()
		{
			return currentSetup;
		}

		SitePlan(FarmingSites.Site site, TransportAvailability transport, int walkTiles, String note, List<TransportAvailability> alternatives,
			List<TransportAvailability> rejected, List<String> needKeys, List<String> accessUnmet, List<String> accessMissing)
		{
			this(site, transport, walkTiles, note, alternatives, rejected, needKeys, accessUnmet, accessMissing, Collections.emptyList());
		}

		SitePlan(FarmingSites.Site site, TransportAvailability transport, int walkTiles, String note, List<TransportAvailability> alternatives,
			List<TransportAvailability> rejected, List<String> needKeys, List<String> accessUnmet, List<String> accessMissing, List<String> closedWays)
		{
			this.closedWays = Collections.unmodifiableList(new ArrayList<>(closedWays));
			this.site = site;
			this.transport = transport;
			this.walkTiles = walkTiles;
			this.note = note;
			this.alternatives = Collections.unmodifiableList(alternatives);
			this.rejected = Collections.unmodifiableList(rejected);
			this.needKeys = Collections.unmodifiableList(needKeys);
			this.accessUnmet = Collections.unmodifiableList(accessUnmet);
			this.accessMissing = Collections.unmodifiableList(accessMissing);
		}

		public FarmingSites.Site getSite()
		{
			return site;
		}

		/** The chosen transport, or null when none is usable after preparation. */
		public TransportAvailability getTransport()
		{
			return transport;
		}

		public int getWalkTiles()
		{
			return walkTiles;
		}

		/** Why there is no transport, or what still has to be reached (network); may be null. */
		public String getNote()
		{
			return note;
		}

		/** Other usable-after-preparation transports for this site, best first. */
		public List<TransportAvailability> getAlternatives()
		{
			return alternatives;
		}

		/** Catalogued transports for this site that are not usable on current evidence (unknown, locked, depleted). */
		/** The chosen journey's steps after the teleport, as this account walks them (empty: none chosen, or a plain walk). */
		public List<FarmingTravel.Leg> getLegs()
		{
			return legs;
		}

		/** Teleports that would be quicker but whose way to the patch is closed for this account, with what it needs. */
		public List<String> getClosedWays()
		{
			return closedWays;
		}

		public List<TransportAvailability> getRejected()
		{
			return rejected;
		}

		/** The preparation needs the chosen transport relies on (its items, runes, fairy-ring staff), shared with other sites. */
		public List<String> getNeedKeys()
		{
			return needKeys;
		}

		/** Patch access known not to be met here ("Farming Guild herb: Farming 65"). */
		public List<String> getAccessUnmet()
		{
			return accessUnmet;
		}

		/** Patch access Spyglass could not confirm here. */
		public List<String> getAccessMissing()
		{
			return accessMissing;
		}

		/** The chosen transport is a network (fairy ring, spirit tree): the player first has to reach the network. */
		public boolean isNetworkHop()
		{
			return transport != null && transport.getTransport().network != null;
		}

		/**
		 * True when the chosen transport's remaining charges or today's uses
		 * cannot be observed for any variant that might be taken (Xeric's
		 * talisman, Ardougne cloak 2-3): carrying it does not confirm it works.
		 */
		public boolean isChargeUncertain()
		{
			if (transport == null)
			{
				return false;
			}
			if (chargesSeen != null)
			{
				return !chargesSeen;
			}
			if (transport.getState() == Availability.CHARGES_UNKNOWN)
			{
				return true;
			}
			if (transport.getState() == Availability.READY)
			{
				return false;
			}
			for (TransportDef.ItemVariant v : TransportDef.list(transport.getTransport().items))
			{
				if (v.charges != TransportDef.Charges.DEPLETED && !chargesObservable(v))
				{
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Whether every variant of the chosen transport that might be taken has its charges or today's uses observed (the
	 * game's own messages, ChargeKnowledge); a variant that never reports stays unknown.
	 */
	public static boolean chargesSeen(TransportAvailability chosen, AccountCapability cap)
	{
		if (chosen.getState() == Availability.READY)
		{
			return true;
		}
		long now = cap == null ? 0 : cap.getCapturedAt();
		for (TransportDef.ItemVariant v : TransportDef.list(chosen.getTransport().items))
		{
			if (v.charges == TransportDef.Charges.DEPLETED || chargesObservable(v))
			{
				continue;
			}
			if (v.pool == null || cap == null || cap.getCharges().remaining(v.pool, now) == null)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The whole run against shared daily uses and stored charges (canonical §28.13): when the planned stops would use one
	 * pool more often than the game last reported as left, say so. Only pools the chosen item can only draw on are counted
	 * (an unlimited variant carried, the elite cloak say, needs none).
	 */
	static List<String> poolShortfalls(List<SitePlan> planned, AccountCapability cap)
	{
		List<String> out = new ArrayList<>();
		if (cap == null)
		{
			return out;
		}
		long now = cap.getCapturedAt();
		Map<String, Integer> uses = new java.util.LinkedHashMap<>();
		Map<String, String> names = new java.util.HashMap<>();
		for (SitePlan sp : planned)
		{
			TransportAvailability t = sp.getTransport();
			if (t == null)
			{
				continue;
			}
			String pool = null;
			boolean unlimited = false;
			for (TransportDef.ItemVariant v : TransportDef.list(t.getTransport().items))
			{
				if (!carriedOrBanked(cap, v.id))
				{
					continue;
				}
				if (v.pool == null)
				{
					unlimited |= v.charges == TransportDef.Charges.UNLIMITED;
				}
				else
				{
					pool = v.pool;
				}
			}
			if (pool != null && !unlimited)
			{
				uses.merge(pool, 1, Integer::sum);
				names.putIfAbsent(pool, itemOrName(t.getTransport()));
			}
		}
		for (Map.Entry<String, Integer> e : uses.entrySet())
		{
			Integer left = cap.getCharges().remaining(e.getKey(), now);
			if (left != null && left < e.getValue())
			{
				out.add(names.get(e.getKey()) + ": " + left + (e.getKey().startsWith("daily.") ? (left == 1 ? " use" : " uses") + " left today"
					: left == 1 ? " charge left" : " charges left") + ", this run plans " + e.getValue());
			}
		}
		return out;
	}

	private static boolean carriedOrBanked(AccountCapability cap, int itemId)
	{
		return has(cap.getInventory(), itemId) || has(cap.getWorn(), itemId) || cap.getBank() != null && has(cap.getBank().getItems(), itemId);
	}

	private static boolean has(Map<Integer, Integer> items, int itemId)
	{
		return items != null && items.getOrDefault(itemId, 0) > 0;
	}

	public static final class Plan
	{
		private final PreparationRequest request;
		private final List<SitePlan> sites;
		private final List<String> accessUnmet;
		private final List<String> accessUnknown;
		private final List<String> notes;

		Plan(PreparationRequest request, List<SitePlan> sites, List<String> accessUnmet, List<String> accessUnknown, List<String> notes)
		{
			this.request = request;
			this.sites = Collections.unmodifiableList(sites);
			this.accessUnmet = Collections.unmodifiableList(accessUnmet);
			this.accessUnknown = Collections.unmodifiableList(accessUnknown);
			this.notes = Collections.unmodifiableList(notes);
		}

		public PreparationRequest getRequest()
		{
			return request;
		}

		/** Sites in PLANNED order (an estimate; never persisted, never a cursor). */
		public List<SitePlan> getSites()
		{
			return sites;
		}

		public List<String> getAccessUnmet()
		{
			return accessUnmet;
		}

		public List<String> getAccessUnknown()
		{
			return accessUnknown;
		}

		public List<String> getNotes()
		{
			return notes;
		}

		/** Patch ids in the suggested order (site by site), the order both pages show. */
		public List<String> patchOrder()
		{
			List<String> out = new ArrayList<>();
			for (SitePlan s : sites)
			{
				for (String id : s.getSite().getPatchIds())
				{
					if (!out.contains(id))
					{
						out.add(id);
					}
				}
			}
			return out;
		}
	}

	private FarmingPreparation()
	{
	}

	public static Plan plan(FarmingRunIntent intent, FarmingPatchCatalog catalog, AccountCapability cap, List<TransportAvailability> availability,
		EvidenceSource evidence)
	{
		return plan(intent, catalog, cap, availability, evidence, null, Collections.emptyMap());
	}

	/**
	 * @param prefs     the player's Farming preferences (null: protection from the run's options, trees cleared by hand)
	 * @param knowledge this owner's patch knowledge (what is standing in each patch now); empty when unknown
	 */
	public static Plan plan(FarmingRunIntent intent, FarmingPatchCatalog catalog, AccountCapability cap, List<TransportAvailability> availability,
		EvidenceSource evidence, FarmingPreferences prefs, Map<String, PatchKnowledge> knowledge)
	{
		return plan(intent, catalog, cap, availability, evidence, prefs, knowledge, Collections.emptyMap(), 0L);
	}

	/**
	 * As above; {@code bins}: the compost bins as last seen at {@code now}. The run's bins with a task (§28.16) join the plan:
	 * at a stop the run already visits, or as a stop of their own (the bin's allotment site); their determinable supplies are
	 * optional rows (buckets for the compost, the chosen upgrade's potion dose and ash).
	 */
	public static Plan plan(FarmingRunIntent intent, FarmingPatchCatalog catalog, AccountCapability cap, List<TransportAvailability> availability,
		EvidenceSource evidence, FarmingPreferences prefs, Map<String, PatchKnowledge> knowledge, Map<String, CompostBin.Seen> bins, long now)
	{
		List<String> patches = new ArrayList<>();
		for (String id : intent.getIncludedPatchIds())
		{
			if (!intent.isSkipped(id))
			{
				patches.add(id);
			}
		}
		List<ItemNeed> needs = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		supplies(intent, catalog, patches, cap, needs, notes, prefs, knowledge == null ? Collections.emptyMap() : knowledge);

		Map<String, TransportAvailability> byId = new LinkedHashMap<>();
		Map<String, List<TransportAvailability>> byHop = new LinkedHashMap<>();
		for (TransportAvailability a : availability)
		{
			byId.put(a.getTransport().id, a);
			if (a.getTransport().via != null)
			{
				byHop.computeIfAbsent(a.getTransport().via.hop, k -> new ArrayList<>()).add(a);
			}
		}
		Map<String, RunExtras.BinWork> dueBins = RunExtras.due(intent, bins, now);
		List<String> stopPatches = new ArrayList<>(patches);
		java.util.Set<String> stops = new java.util.HashSet<>();
		for (String id : patches)
		{
			stops.add(FarmingSites.stopOf(catalog, id));
		}
		for (RunExtras.BinWork b : dueBins.values())
		{
			String anchor = b.def.getAnchorPatchId();
			if (catalog.patch(anchor) != null && stops.add(FarmingSites.stopOf(catalog, anchor)))
			{
				stopPatches.add(anchor); // a stop of its own: travel to the bin's site (its anchor patch is not run work)
			}
		}
		List<FarmingSites.Site> sites = FarmingSites.sitesOf(catalog, stopPatches);
		// Pass 32: Use current setup plans only journeys on the current spellbook (any spell leg included).
		boolean currentSetup = prefs != null && prefs.getTravel() == FarmingPreferences.Travel.CURRENT;
		String book = cap == null || cap.spellbook() == null ? null : cap.spellbook().name();
		List<List<String>> leftOuts = new ArrayList<>();
		List<TravelPlanner.Choice> choices = new ArrayList<>();
		List<List<TransportAvailability>> rejected = new ArrayList<>();
		List<Integer> arrivalWalks = new ArrayList<>();
		List<List<String>> wayNotes = new ArrayList<>();
		for (FarmingSites.Site site : sites)
		{
			java.util.Set<TransportAvailability> candidateSet = new java.util.LinkedHashSet<>();
			for (String patchId : site.getPatchIds())
			{
				for (String tid : FarmingTravel.forStop(catalog, patchId))
				{
					// A fairy ring or spirit tree is a destination: the candidates are the complete journeys to it
					// (a teleport, the walk to an entrance, the hop), never the bare hop.
					List<TransportAvailability> options = FarmingTravel.isNetwork(tid) ? byHop.getOrDefault(tid, Collections.emptyList())
						: byId.containsKey(tid) ? Collections.singletonList(byId.get(tid)) : Collections.emptyList();
					for (TransportAvailability a : options)
					{
						candidateSet.add(a);
					}
				}
			}
			List<TransportAvailability> candidates = new ArrayList<>(candidateSet);
			List<String> leftOut = new ArrayList<>();
			if (currentSetup)
			{
				List<TransportAvailability> other = new ArrayList<>();
				candidates = TravelPlanner.withoutSpellbookChange(candidates, book, other);
				for (TransportAvailability a : other)
				{
					String why = spellbookText(a.getTransport(), book);
					if (leftOut.size() < 2 && !leftOut.contains(why))
					{
						leftOut.add(why);
					}
				}
			}
			leftOuts.add(leftOut);
			String first = site.getPatchIds().get(0);
			com.osrstelemetry.plugin.navigation.AccountRouter.Key key = com.osrstelemetry.plugin.navigation.AccountRouter.bundled().planning(cap);
			// Whole journeys, each leg the account's own cheapest usable walk (canonical §28.11).
			TravelPlanner.Choice choice = TravelPlanner.chooseByJourney(d -> FarmingTravel.afterTeleport(catalog, first, d, key), candidates,
				prefs != null && prefs.getTravel() == FarmingPreferences.Travel.HOUSE);
			choices.add(choice);
			if (choice.getChosen() != null)
			{
				leftOut.clear(); // named only to say why a stop has no journey
			}
			com.osrstelemetry.plugin.navigation.RouteTable.Walk arrive = choice.getChosen() == null ? null
				: FarmingTravel.walk(catalog, first, choice.getChosen().getTransport(), key);
			arrivalWalks.add(arrive == null ? -1 : arrive.getCost());
			// Not usable now: only routes that would beat the chosen one (or every route when none is usable), cheapest first,
			// at most three; house routes and network journeys are summarised by their destination. A teleport that is fine
			// but whose way to the patch is closed for this account says what the way needs.
			int chosenCost = choice.getChosen() == null ? Integer.MAX_VALUE : Math.max(0, choice.getWalkTiles());
			List<Object[]> worse = new ArrayList<>();
			java.util.Set<String> seen = new java.util.HashSet<>();
			List<String> closedWays = new ArrayList<>();
			for (String tid : FarmingTravel.forStop(catalog, first))
			{
				TransportAvailability a = byId.get(tid);
				if (a == null || tid.startsWith("poh.") || !seen.add(tid))
				{
					continue;
				}
				if (currentSetup && TravelPlanner.needsSpellbookChange(a.getTransport(), book))
				{
					continue; // never offered as something to bring: it needs another spellbook (said once, above)
				}
				List<com.osrstelemetry.plugin.navigation.RouteTable.Walk> v = FarmingTravel.routes().variants(FarmingTravel.stopKey(catalog, first),
					FarmingTravel.arrivalKey(a.getTransport()));
				int cost = v.isEmpty() ? Integer.MAX_VALUE : v.get(0).getCost();
				if (cost >= chosenCost)
				{
					continue;
				}
				if (FarmingTravel.isNetwork(tid))
				{
					if (byHop.getOrDefault(tid, Collections.emptyList()).stream().noneMatch(j -> TravelPlanner.PREPARATION_ELIGIBLE.contains(j.getState())))
					{
						worse.add(new Object[]{cost, a});
					}
					continue;
				}
				if (!TravelPlanner.PREPARATION_ELIGIBLE.contains(a.getState()))
				{
					worse.add(new Object[]{cost, a});
				}
				else if (FarmingTravel.walk(catalog, first, a.getTransport(), key) == null && !v.isEmpty() && closedWays.size() < 2)
				{
					String lacks = com.osrstelemetry.plugin.navigation.VarNames.blocking(v.get(0).getRequirement(), cap);
					if (!lacks.isEmpty())
					{
						closedWays.add(FarmingPreparation.itemOrName(a.getTransport()) + ": the way from there needs " + lacks);
					}
				}
			}
			worse.sort(java.util.Comparator.comparingInt(o -> (Integer) o[0]));
			List<TransportAvailability> notUsable = new ArrayList<>();
			for (int k = 0; k < Math.min(3, worse.size()); k++)
			{
				notUsable.add((TransportAvailability) worse.get(k)[1]);
			}
			wayNotes.add(closedWays);
			rejected.add(notUsable);
		}
		List<TransportAvailability> chosen = new ArrayList<>();
		for (TravelPlanner.Choice c : choices)
		{
			chosen.add(c.getChosen());
		}
		Map<String, List<String>> needKeysByTransport = travelNeeds(chosen, cap, needs);
		fertileSoil(intent, catalog, patches, cap, needs, notes);
		binSupplies(dueBins.values(), bins, intent, now, needs);
		notes.addAll(slotConflicts(needs));

		int[] order = order(sites, choices);
		List<SitePlan> planned = new ArrayList<>();
		for (int i : order)
		{
			TravelPlanner.Choice c = choices.get(i);
			String note = null;
			if (c.getChosen() == null)
			{
				// Teleports that work but whose way on is closed for this account: other ways there (sailing a boat, say) are not
				// guided, so this is a gap in guidance, never "locked".
				note = wayNotes.get(i).isEmpty() ? noTransportNote(catalog, sites.get(i), byId) : "no guided route to this patch yet";
			}
			else if (c.isNetwork())
			{
				note = "from any " + (c.getChosen().getTransport().type == TransportDef.Type.FAIRY_RING ? "fairy ring" : "spirit tree");
			}
			List<String> siteUnmet = new ArrayList<>();
			List<String> siteMissing = new ArrayList<>();
			for (String patchId : sites.get(i).getPatchIds())
			{
				RequirementSet req = FarmingAccess.forPatch(patchId);
				if (req == null)
				{
					continue;
				}
				RequirementCheck check = RequirementEvaluator.check(req, cap, evidence);
				if (check.overall() == Known.NO)
				{
					siteUnmet.addAll(check.getUnmet());
				}
				else if (check.overall() == Known.UNKNOWN)
				{
					siteMissing.addAll(check.getMissing());
				}
			}
			List<String> keys = c.getChosen() == null ? Collections.emptyList()
				: needKeysByTransport.getOrDefault(c.getChosen().getTransport().id, Collections.emptyList());
			SitePlan sp = new SitePlan(sites.get(i), c.getChosen(), arrivalWalks.get(i), note, c.getAlternatives(), rejected.get(i), keys, siteUnmet,
				siteMissing, wayNotes.get(i));
			sp.currentSetup = currentSetup;
			sp.setupLeftOut = Collections.unmodifiableList(leftOuts.get(i));
			if (c.getChosen() != null)
			{
				sp.chargesSeen = chargesSeen(c.getChosen(), cap);
				sp.legs = Collections.unmodifiableList(FarmingTravel.journey(catalog, sites.get(i).getPatchIds().get(0), c.getChosen().getTransport(),
					sites.get(i).getTile(), com.osrstelemetry.plugin.navigation.AccountRouter.bundled().planning(cap)));
			}
			planned.add(sp);
		}

		List<String> unmet = new ArrayList<>();
		List<String> unknown = new ArrayList<>();
		for (String patchId : patches)
		{
			RequirementSet req = FarmingAccess.forPatch(patchId);
			if (req == null)
			{
				continue;
			}
			RequirementCheck check = RequirementEvaluator.check(req, cap, evidence);
			String where = catalog.patch(patchId) == null ? patchId : catalog.patch(patchId).getAreaName();
			if (check.overall() == Known.NO)
			{
				unmet.add(where + ": needs " + String.join("; ", check.getUnmet()));
			}
			else if (check.overall() == Known.UNKNOWN)
			{
				unknown.add(where + ": access not confirmed (" + String.join("; ", check.getMissing()) + ")");
			}
		}
		String title = intent.getTitle() + " run";
		balloonFuel(planned, needs);
		notes.addAll(poolShortfalls(planned, cap));
		return new Plan(new PreparationRequest(CONSUMER, title, needs), planned, unmet, unknown, notes);
	}

	// -- supplies --------------------------------------------------------------------------

	/** Inventory slots a player has; the estimate never claims more fit. */
	public static final int INVENTORY_SLOTS = 28;

	/** The amulet of bounty saves seeds only when planting allotments (OSRS Wiki, Amulet of bounty, checked 2026-10-16). */
	public static boolean bountyApplies(FarmingRunType type)
	{
		return type == FarmingRunType.ALLOTMENT;
	}

	/**
	 * Fuel for the balloon flights on the planned journeys (OSRS Wiki "Balloon transport system": one log of the landing's
	 * kind a flight), summed over the run. Logs left in a balloon's log storage are not readable, so the carried log is what
	 * is prepared; the flight's own requirement names it.
	 */
	static void balloonFuel(List<SitePlan> planned, List<ItemNeed> needs)
	{
		Map<Integer, Integer> logs = new java.util.LinkedHashMap<>();
		Map<Integer, List<String>> where = new java.util.LinkedHashMap<>();
		for (SitePlan sp : planned)
		{
			for (FarmingTravel.Leg leg : sp.getLegs())
			{
				com.osrstelemetry.plugin.navigation.Transition t = leg.transition;
				if (t == null || t.family != com.osrstelemetry.plugin.navigation.Transition.Family.BALLOON)
				{
					continue;
				}
				for (com.osrstelemetry.plugin.navigation.NavRequirement.ItemNeed n : t.requirement.getItems())
				{
					if (n.optionIds.size() == 1 && n.optionIds.get(0).length == 1)
					{
						int id = n.optionIds.get(0)[0];
						logs.merge(id, n.optionQuantities.get(0), Integer::sum);
						where.computeIfAbsent(id, k -> new ArrayList<>()).add(t.info == null ? "a landing" : t.info);
					}
				}
			}
		}
		for (Map.Entry<Integer, Integer> e : logs.entrySet())
		{
			String one = com.osrstelemetry.plugin.navigation.Transition.LOGS.getOrDefault(e.getKey(), "log");
			String label = Character.toUpperCase(one.charAt(0)) + one.substring(1) + "s";
			needs.add(new ItemNeed("travel.balloon." + e.getKey(), label, "Travel", new int[]{e.getKey()}, e.getValue(), ItemNeed.Necessity.REQUIRED,
				ItemNeed.SatisfiedBy.INVENTORY_ONLY, "balloon fuel: to " + String.join(", ", new java.util.LinkedHashSet<>(where.get(e.getKey()))))
				.withSearchText(one));
		}
	}

	/** Compost potion, any dose count (one dose upgrades a bin of compost). */
	static final int[] COMPOST_POTIONS = {ItemID.SUPERCOMPOST_POTION_1, ItemID.SUPERCOMPOST_POTION_2, ItemID.SUPERCOMPOST_POTION_3,
		ItemID.SUPERCOMPOST_POTION_4};

	/**
	 * Supplies for the run's bin work that can be worked out (§28.16): a bucket per bucket of compost seen in a bin to take,
	 * the chosen upgrade's compost potion dose and volcanic ash. All optional (bin work is optional), never the compostables
	 * to fill a bin with (the player's choice), and the compost in a bin is never counted as carried or available.
	 */
	static void binSupplies(java.util.Collection<RunExtras.BinWork> due, Map<String, CompostBin.Seen> seen, FarmingRunIntent intent, long now,
		List<ItemNeed> needs)
	{
		int buckets = 0;
		int ash = 0;
		int potion = 0;
		List<String> bucketWhere = new ArrayList<>();
		List<String> upgradeWhere = new ArrayList<>();
		for (RunExtras.BinWork b : due)
		{
			int[] sup = CompostBin.supplies(b.def, b.seen, intent.binUpgrade(b.def.getBinId()), now);
			buckets += sup[0];
			ash += sup[1];
			potion += sup[2];
			if (sup[0] > 0)
			{
				bucketWhere.add(b.def.getPlace());
			}
			if (sup[1] > 0 || sup[2] > 0)
			{
				upgradeWhere.add(b.def.getPlace());
			}
		}
		if (buckets > 0)
		{
			needs.add(new ItemNeed("supply.bin_buckets", "Bucket", "Optional", new int[]{ItemID.BUCKET_EMPTY}, buckets, ItemNeed.Necessity.RECOMMENDED,
				ItemNeed.SatisfiedBy.INVENTORY_ONLY, "for the compost in the bin at " + String.join(", ", bucketWhere)).withSearchText("bucket"));
		}
		if (potion > 0)
		{
			needs.add(new ItemNeed("supply.compost_potion", "Compost potion", "Optional", COMPOST_POTIONS, potion, ItemNeed.Necessity.RECOMMENDED,
				ItemNeed.SatisfiedBy.INVENTORY_ONLY, "a dose for each bin of compost to upgrade: " + String.join(", ", upgradeWhere))
				.unstackable().withSearchText("compost potion"));
		}
		if (ash > 0)
		{
			addAsh(needs, ash, "ultracompost in the bin at " + String.join(", ", upgradeWhere));
		}
	}

	/** Volcanic ash, one row for every use in the run (Fertile Soil's upgrade and bins), so carried ash is counted once. */
	static void addAsh(List<ItemNeed> needs, int qty, String reason)
	{
		for (int i = 0; i < needs.size(); i++)
		{
			ItemNeed n = needs.get(i);
			if (n.getKey().equals("supply.volcanic_ash"))
			{
				needs.set(i, new ItemNeed(n.getKey(), n.getLabel(), n.getSection(), new int[]{ItemID.FOSSIL_VOLCANIC_ASH}, n.getQuantity() + qty,
					n.getNecessity(), ItemNeed.SatisfiedBy.INVENTORY_ONLY, n.getReason() + "; " + reason).withSearchText("volcanic ash"));
				return;
			}
		}
		needs.add(new ItemNeed("supply.volcanic_ash", "Volcanic ash", "Optional", new int[]{ItemID.FOSSIL_VOLCANIC_ASH}, qty, ItemNeed.Necessity.RECOMMENDED,
			ItemNeed.SatisfiedBy.INVENTORY_ONLY, reason).withSearchText("volcanic ash"));
	}

	/** Fertile Soil casts: one per patch planted this run with it chosen (OSRS Wiki "Fertile Soil", 2026-10-22). */
	static final int[][] FERTILE_SOIL_RUNES = {{ItemID.EARTHRUNE, 15}, {ItemID.ASTRALRUNE, 3}, {ItemID.NATURERUNE, 2}};

	/**
	 * Fertile Soil chosen as the compost: its runes join the travel spells' (one shared total per rune, from the inventory
	 * or the rune pouch), its requirements are named when this account does not meet them (never silently swapped for
	 * buckets), and volcanic ash is an optional row (2 a cast, for ultracompost once the ash covered tome is read).
	 */
	static void fertileSoil(FarmingRunIntent intent, FarmingPatchCatalog catalog, List<String> patches, AccountCapability cap, List<ItemNeed> needs,
		List<String> notes)
	{
		int casts = 0;
		for (String id : patches)
		{
			FarmingPatchDef def = catalog.patch(id);
			if (def != null && intent.plansToPlant(id) && intent.getOptions() != null
				&& "FERTILE_SOIL".equals(intent.getOptions().compostFor(def.getType())))
			{
				casts++;
			}
		}
		if (casts == 0)
		{
			return;
		}
		for (int[] r : FERTILE_SOIL_RUNES)
		{
			addRunes(needs, r[0], r[1] * casts, runeLabel(r[0] == ItemID.EARTHRUNE ? "EARTHRUNE" : r[0] == ItemID.ASTRALRUNE ? "ASTRALRUNE" : "NATURERUNE"),
				"Fertile Soil");
		}
		// The upgrade: varbit 5960 (FOSSIL_MINE_UNLOCKED_ULTRAFERTILESOIL) is 1 once the ash covered tome is unlocked; the game's
		// spellbook tooltip then reads "Fertilise a farming patch with super or ultra compost, consuming 2 volcanic ash to do so."
		// (osrs-dumps @6e5033af, magic_spellbook_settooltip). Ash is offered only then, as optional: without it the spell gives
		// supercompost. What it gave is only ever what the game's message says.
		Integer upgrade = cap == null ? null : cap.varbit(VarbitID.FOSSIL_MINE_UNLOCKED_ULTRAFERTILESOIL);
		if (upgrade != null && upgrade == 1)
		{
			addAsh(needs, 2 * casts, "2 a cast: Fertile Soil then gives ultracompost instead of supercompost");
		}
		else if (upgrade == null)
		{
			notes.add("Fertile Soil gives supercompost; whether this account has its ultracompost upgrade is not read yet.");
		}
		List<String> missing = new ArrayList<>();
		AccountReaders.Spellbook book = cap == null ? null : cap.spellbook();
		if (book == null)
		{
			missing.add("the Lunar spellbook (spellbook not read yet)");
		}
		else if (book != AccountReaders.Spellbook.LUNAR)
		{
			missing.add("the Lunar spellbook");
		}
		Integer magic = cap == null ? null : cap.level(net.runelite.api.Skill.MAGIC);
		if (magic == null || magic < 83)
		{
			missing.add("83 Magic" + (magic == null ? " (not read yet)" : ""));
		}
		if (cap == null || cap.quest(net.runelite.api.Quest.LUNAR_DIPLOMACY) != net.runelite.api.QuestState.FINISHED)
		{
			missing.add("Lunar Diplomacy");
		}
		if (!missing.isEmpty())
		{
			notes.add("Fertile Soil (" + casts + (casts == 1 ? " cast" : " casts") + ") needs " + String.join(", ", missing) + ".");
		}
	}

	/** "Harmony Island Teleport needs the Arceuus spellbook (you are on Standard)" (pass 32). */
	public static String spellbookText(TransportDef t, String currentBook)
	{
		String need = TravelPlanner.spellbookOf(t);
		return t.name + " needs the " + bookTitle(need) + " spellbook" + (currentBook == null ? " (your spellbook is not read yet)"
			: " (you are on " + bookTitle(currentBook) + ")");
	}

	/** "ARCEUUS" → "Arceuus". */
	public static String bookTitle(String book)
	{
		return book == null || book.isEmpty() ? "" : book.charAt(0) + book.substring(1).toLowerCase(java.util.Locale.ROOT);
	}

	/** Adds runes to the shared total for that rune (one row per rune for every spell of the run). */
	static void addRunes(List<ItemNeed> needs, int runeId, int qty, String label, String reason)
	{
		for (int i = 0; i < needs.size(); i++)
		{
			ItemNeed n = needs.get(i);
			if (n.getKey().equals("rune." + runeId))
			{
				needs.set(i, new ItemNeed(n.getKey(), n.getLabel(), n.getSection(), new int[]{runeId}, n.getQuantity() + qty, ItemNeed.Necessity.REQUIRED,
					ItemNeed.SatisfiedBy.INVENTORY_OR_RUNE_POUCH, n.getReason() + ", " + reason).withSearchText(n.getLabel()));
				return;
			}
		}
		needs.add(new ItemNeed("rune." + runeId, label, "Travel", new int[]{runeId}, qty, ItemNeed.Necessity.REQUIRED,
			ItemNeed.SatisfiedBy.INVENTORY_OR_RUNE_POUCH, reason).withSearchText(label));
	}

	private static void supplies(FarmingRunIntent intent, FarmingPatchCatalog catalog, List<String> patches, AccountCapability cap, List<ItemNeed> needs,
		List<String> notes, FarmingPreferences prefs, Map<String, PatchKnowledge> knowledge)
	{
		FarmingAccountFacts.ToolStore store = cap != null && cap.isSettled() ? FarmingAccountFacts.toolStore(cap) : null;
		Integer farming = cap == null ? null : cap.level(net.runelite.api.Skill.FARMING);
		FarmingPreferences pref = prefs;
		// Planting patches per patch type (generic categories: the player chooses the crop in game).
		Map<FarmingPatchType, List<String>> planting = new LinkedHashMap<>();
		Map<String, Integer> compostCounts = new LinkedHashMap<>();
		Set<FarmingRunType> typesPresent = new java.util.LinkedHashSet<>();
		boolean seeds = false;
		boolean allotmentPlanting = false;
		int removalCoins = 0;
		Map<Integer, Integer> removalWhat = new java.util.TreeMap<>();
		boolean axe = false;
		List<String> removalUnknown = new ArrayList<>();
		for (String id : patches)
		{
			FarmingPatchDef def = catalog.patch(id);
			if (def == null)
			{
				continue;
			}
			FarmingRunType type = FarmingRunType.of(def.getType());
			typesPresent.add(type);
			if (!intent.plansToPlant(id))
			{
				continue;
			}
			planting.computeIfAbsent(def.getType(), k -> new ArrayList<>()).add(id);
			allotmentPlanting |= def.getType() == FarmingPatchType.ALLOTMENT;
			seeds |= !FarmingSeeds.saplings(def.getType());
			String compost = intent.getOptions() == null ? null : intent.getOptions().compostFor(def.getType());
			if (compost != null && !"NONE".equals(compost))
			{
				compostCounts.merge(compost, 1, Integer::sum);
			}
			// A grown tree must come out before the new one goes in: by the gardener (coins) or by hand (axe + spade).
			if (FarmingPreferences.needsClearing(def.getType()))
			{
				PatchCondition c = lastCondition(knowledge, catalog, id);
				boolean standing = c == null || c == PatchCondition.GROWN || c == PatchCondition.CHECK_HEALTH || c == PatchCondition.HARVESTABLE
					|| c == PatchCondition.GROWING || c == PatchCondition.DISEASED;
				if (standing)
				{
					if (c == null)
					{
						removalUnknown.add(def.getAreaName());
					}
					FarmingPreferences.TreeClearing how = pref != null ? pref.clearingFor(def.getType())
						: FarmingPreferences.defaults().withTreeClearing(FarmingPreferences.TreeClearing.CHOP).clearingFor(def.getType());
					if (how == FarmingPreferences.TreeClearing.PAY)
					{
						removalCoins += FarmingPreferences.removalCoins(def.getType());
						removalWhat.merge(FarmingPreferences.removalCoins(def.getType()), 1, Integer::sum);
					}
					else
					{
						axe |= FarmingPreferences.choppable(def.getType());
					}
				}
			}
		}
		for (Map.Entry<FarmingPatchType, List<String>> e : planting.entrySet())
		{
			FarmingPatchType type = e.getKey();
			int n = e.getValue().size();
			List<FarmingProduce> fit = FarmingSeeds.plantable(type, farming);
			if (fit.isEmpty())
			{
				notes.add(FarmingSeeds.category(type) + ": none Spyglass knows of " + (farming == null ? "" : "at Farming " + farming + " ")
					+ "for " + n + " " + FarmingModel.noun(type, n) + "; bring your own.");
				continue;
			}
			int[] ids = new int[fit.size()];
			for (int i = 0; i < ids.length; i++)
			{
				ids[i] = FarmingSeeds.forCrop(fit.get(i)).getItemId();
			}
			int max = FarmingSeeds.maxPerPatch(type);
			int min = FarmingSeeds.minPerPatch(type);
			String why = (max == min ? max + " per patch" : max + " per patch (" + min + " for some crops)")
				+ (farming == null ? "; Farming level not read yet" : "; any you can plant at Farming " + farming);
			String key = "seed." + type.name().toLowerCase(java.util.Locale.ROOT) + ".any";
			ItemNeed need = new ItemNeed(key, FarmingSeeds.category(type), "Supplies", ids, n * max, ItemNeed.Necessity.REQUIRED,
				ItemNeed.SatisfiedBy.INVENTORY_ONLY, why).withSearchText(FarmingSeeds.category(type).toLowerCase(java.util.Locale.ROOT));
			needs.add(FarmingSeeds.saplings(type) ? need.unstackable() : need);
			if (type == FarmingPatchType.SPIRIT_TREE)
			{
				int allowed = farming == null ? -1 : farming >= 99 ? Integer.MAX_VALUE : farming >= 96 ? 4 : farming >= 93 ? 3 : farming >= 88 ? 2 : 1;
				if (allowed >= 0 && n > allowed)
				{
					notes.add("At Farming " + farming + " you can have " + allowed + " spirit tree" + (allowed == 1 ? "" : "s") + " growing at once (OSRS Wiki).");
				}
			}
		}
		for (Map.Entry<String, Integer> e : compostCounts.entrySet())
		{
			compost(e.getKey(), e.getValue(), store, needs);
		}
		protection(intent, pref, planting, cap, needs, notes);
		if (removalCoins > 0)
		{
			needs.add(new ItemNeed("payment.removal_coins", "Coins (tree removal)", "Protection", new int[]{ItemID.COINS}, removalCoins,
				ItemNeed.Necessity.RECOMMENDED, ItemNeed.SatisfiedBy.INVENTORY_ONLY, removalText(removalWhat)
				+ (removalUnknown.isEmpty() ? "" : "; includes patches not seen yet")).withSearchText("Coins"));
		}
		if (axe)
		{
			needs.add(new ItemNeed("tool.axe", "Axe", "Tools", AXES, 0, ItemNeed.Necessity.REQUIRED, ItemNeed.SatisfiedBy.INVENTORY_OR_WORN,
				"to chop the old tree (you clear by hand)").unstackable().withSearchText("axe"));
		}
		if (seeds)
		{
			Integer barbarian = cap == null ? null : cap.varbit(VarbitID.BRUT_FARMING_PLANTING);
			if (barbarian == null || barbarian != 3)
			{
				needs.add(tool(new ItemNeed("tool.dibber", "Seed dibber", "Tools", new int[]{ItemID.DIBBER}, 0, ItemNeed.Necessity.REQUIRED,
					ItemNeed.SatisfiedBy.INVENTORY_ONLY, barbarian == null ? "Barbarian planting not read" : "to plant seeds"), store, ItemID.DIBBER));
			}
		}
		Integer weeds = cap == null ? null : cap.varbit(VarbitID.FARMING_BLOCKWEEDS);
		if (weeds == null || weeds != 2)
		{
			needs.add(tool(new ItemNeed("tool.rake", "Rake", "Tools", new int[]{ItemID.RAKE}, 0, ItemNeed.Necessity.RECOMMENDED,
				ItemNeed.SatisfiedBy.INVENTORY_ONLY, "for weeds"), store, ItemID.RAKE));
		}
		needs.add(tool(new ItemNeed("tool.spade", "Spade", "Tools", new int[]{ItemID.SPADE}, 0, ItemNeed.Necessity.RECOMMENDED,
			ItemNeed.SatisfiedBy.INVENTORY_ONLY, "to clear dead plants"), store, ItemID.SPADE));
		boolean bonus = false;
		for (FarmingRunType t : typesPresent)
		{
			bonus |= t == FarmingRunType.HERB || t == FarmingRunType.ALLOTMENT || t == FarmingRunType.HOPS || t == FarmingRunType.BUSH
				|| t == FarmingRunType.CELASTRUS;
		}
		if (bonus)
		{
			// Magic secateurs: +10% yield for herbs, allotments, hops, bushes, celastrus; held or carried (OSRS Wiki, 2026-10-16).
			ItemNeed magic = new ItemNeed("tool.magic_secateurs", "Magic secateurs", "Optional", new int[]{ItemID.FAIRY_ENCHANTED_SECATEURS}, 0,
				ItemNeed.Necessity.RECOMMENDED, ItemNeed.SatisfiedBy.INVENTORY_OR_WORN, "+10% yield; worn or carried");
			if (store != null && store.isMagicSecateurs() && store.count(ItemID.FAIRY_ENCHANTED_SECATEURS) > 0)
			{
				magic.withReachableStore("tool leprechaun", store.count(ItemID.FAIRY_ENCHANTED_SECATEURS));
			}
			needs.add(magic);
		}
		if (typesPresent.contains(FarmingRunType.HERB))
		{
			needs.add(new ItemNeed("tool.herb_sack", "Herb sack", "Optional",
				new int[]{ItemID.SLAYER_HERB_SACK, ItemID.SLAYER_HERB_SACK_OPEN, ItemID.SLAYER_HERB_SACK_SILK, ItemID.SLAYER_HERB_SACK_SILK_OPEN}, 0,
				ItemNeed.Necessity.RECOMMENDED, ItemNeed.SatisfiedBy.INVENTORY_ONLY, "holds grimy herbs"));
		}
		if (intent.getOptions() != null && intent.getOptions().isUseBounty() && allotmentPlanting)
		{
			// Worn only; 25% chance to use one seed instead of three when planting allotments; 10 charges per player, not readable.
			needs.add(new ItemNeed("tool.amulet_of_bounty", "Amulet of bounty", "Equipment", new int[]{ItemID.AMULET_OF_BOUNTY}, 0,
				ItemNeed.Necessity.RECOMMENDED, ItemNeed.SatisfiedBy.WORN_ONLY, "wear it; allotments only; charges not readable").wornIn("neck"));
		}
	}

	/**
	 * Worn items that compete for one equipment slot: the amulet of bounty and a
	 * neck teleport (skills necklace, glory, digsite pendant...) can't be worn at
	 * once. Said plainly; Spyglass never swaps anything.
	 */
	static List<String> slotConflicts(List<ItemNeed> needs)
	{
		List<String> out = new ArrayList<>();
		boolean bounty = false;
		List<String> neck = new ArrayList<>();
		for (ItemNeed n : needs)
		{
			if ("neck".equals(n.getWornSlot()))
			{
				bounty = true;
			}
			else if ("Travel".equals(n.getSection()))
			{
				String l = n.getLabel().toLowerCase(java.util.Locale.ROOT);
				if (l.contains("necklace") || l.contains("amulet") || l.contains("pendant") || l.contains("glory"))
				{
					neck.add(n.getLabel());
				}
			}
		}
		if (bounty && !neck.isEmpty())
		{
			out.add("The amulet of bounty and " + String.join(", ", neck) + " share the neck slot: carry the teleport and swap when you use it.");
		}
		return out;
	}

	/** The noted form of an item, by its gameval name (RuneLite's ItemID.Cert), or -1 when it has none. */
	static int notedId(String gameval)
	{
		try
		{
			return net.runelite.api.gameval.ItemID.Cert.class.getField(gameval).getInt(null);
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			return -1;
		}
	}

	/** Every axe that chops a farming tree (inventory or wielded), gameval 1.13.1. */
	static final int[] AXES = {ItemID.BRONZE_AXE, ItemID.IRON_AXE, ItemID.STEEL_AXE, ItemID.BLACK_AXE, ItemID.MITHRIL_AXE, ItemID.ADAMANT_AXE,
		ItemID.RUNE_AXE, ItemID.DRAGON_AXE, ItemID.INFERNAL_AXE, ItemID.INFERNAL_AXE_EMPTY, ItemID._3A_AXE, ItemID.CRYSTAL_AXE, ItemID.BRONZE_AXE_2H,
		ItemID.IRON_AXE_2H, ItemID.STEEL_AXE_2H, ItemID.BLACK_AXE_2H, ItemID.MITHRIL_AXE_2H, ItemID.ADAMANT_AXE_2H, ItemID.RUNE_AXE_2H,
		ItemID.DRAGON_AXE_2H, ItemID.CRYSTAL_AXE_2H, ItemID._3A_AXE_2H};

	/** "2 trees × 200; 1 redwood × 2,000 (Alexandra)": what the removal coins pay for. */
	static String removalText(Map<Integer, Integer> byFee)
	{
		List<String> parts = new ArrayList<>();
		byFee.forEach((fee, n) -> parts.add(n + (n == 1 ? " grown tree" : " grown trees") + " × " + String.format(java.util.Locale.ROOT, "%,d", fee)
			+ (fee == FarmingPreferences.REDWOOD_REMOVAL_COINS ? " (redwood, paid to Alexandra)" : "")));
		return String.join("; ", parts) + " to the gardener";
	}

	/** The patch's condition when last seen (decoded now), or null when never seen or not decodable. */
	static PatchCondition lastCondition(Map<String, PatchKnowledge> knowledge, FarmingPatchCatalog catalog, String patchId)
	{
		PatchKnowledge k = knowledge == null ? null : knowledge.get(patchId);
		FarmingPatchDef def = catalog.patch(patchId);
		if (k == null || def == null || k.getLastSeenAt() <= 0 || k.isDecodeStale())
		{
			return null;
		}
		PatchReading r = FarmingDecoder.decode(def.getType(), k.getLastRaw());
		return r == null ? null : r.getCondition();
	}

	/**
	 * Protection (optional, per patch type, from the player's preferences):
	 * payments follow the planting material actually carried. One crop carried
	 * for a type: its payment for every planted patch of that type. Several
	 * crops with different payments: which seed goes where is the player's
	 * choice, so no total is invented; the per-patch payments are listed
	 * instead. Nothing carried yet: said so. A plan, never proof of payment.
	 */
	static void protection(FarmingRunIntent intent, FarmingPreferences prefs, Map<FarmingPatchType, List<String>> planting, AccountCapability cap,
		List<ItemNeed> needs, List<String> notes)
	{
		Map<Integer, Integer> payQty = new LinkedHashMap<>();
		Map<Integer, FarmingSeeds.Payment> payItem = new LinkedHashMap<>();
		Map<Integer, String> payFor = new LinkedHashMap<>();
		for (Map.Entry<FarmingPatchType, List<String>> e : planting.entrySet())
		{
			FarmingRunType type = FarmingRunType.of(e.getKey());
			boolean on = prefs != null ? prefs.isProtect(type) : intent.getOptions() != null && intent.getOptions().protectionFor(type);
			if (!on || !type.offersProtection() || !protectable(e.getKey()))
			{
				continue;
			}
			List<String> ids = new ArrayList<>(e.getValue());
			if (ids.remove("allotment.harmony"))
			{
				notes.add("The Harmony Island allotment can't be protected (no gardener).");
			}
			int n = ids.size();
			if (n == 0)
			{
				continue;
			}
			// Planting material carried for this type, in patches' worth (seeds per patch; saplings one each).
			Map<FarmingProduce, Integer> units = new LinkedHashMap<>();
			Map<Integer, Integer> inv = cap == null ? null : cap.getInventory();
			if (inv != null)
			{
				for (Map.Entry<Integer, Integer> item : inv.entrySet())
				{
					FarmingProduce crop = FarmingSeeds.cropOf(item.getKey());
					FarmingSeeds.Seed seed = crop == null ? null : FarmingSeeds.forCrop(crop);
					if (crop != null && seed != null && crop.getPatchType() == e.getKey() && item.getValue() > 0)
					{
						units.merge(crop, item.getValue() / Math.max(1, seed.getPerPatch()), Integer::sum);
					}
				}
			}
			units.values().removeIf(u -> u <= 0);
			String what = n + " " + FarmingModel.noun(e.getKey(), n);
			if (units.isEmpty())
			{
				notes.add("Protection for " + what + ": the payment is listed once your " + FarmingSeeds.category(e.getKey()).toLowerCase(java.util.Locale.ROOT)
					+ " are in your inventory.");
				continue;
			}
			int total = 0;
			for (int u : units.values())
			{
				total += u;
			}
			// Every carried crop is planted when there is room for all of it: its payments are exact. With more carried than
			// patches, which crop goes in is the player's choice: only what must be planted whatever the choice is listed, the
			// rest is said in words (never a guessed total).
			List<String> open = new ArrayList<>();
			for (Map.Entry<FarmingProduce, Integer> c : units.entrySet())
			{
				FarmingSeeds.Payment pay = FarmingSeeds.paymentFor(c.getKey());
				if (pay == null)
				{
					open.add(c.getKey().getDisplayName() + ": no payment known");
					continue;
				}
				int sure = total <= n ? c.getValue() : Math.max(0, n - (total - c.getValue()));
				int most = Math.min(c.getValue(), n);
				List<FarmingSeeds.Payment> parts = new ArrayList<>();
				parts.add(pay);
				parts.addAll(pay.getAlso());
				for (FarmingSeeds.Payment part : parts)
				{
					if (sure > 0)
					{
						payQty.merge(part.getItemId(), part.getQuantity() * sure, Integer::sum);
						payItem.putIfAbsent(part.getItemId(), part);
						payFor.merge(part.getItemId(), sure + " " + c.getKey().getDisplayName().toLowerCase(java.util.Locale.ROOT),
							(x, y) -> x + ", " + y);
					}
				}
				if (most > sure)
				{
					open.add(c.getKey().getDisplayName() + ": " + (most - sure) + " more " + (most - sure == 1 ? "patch" : "patches") + " × "
						+ pay.getQuantity() + " " + pay.getLabel().toLowerCase(java.util.Locale.ROOT) + " if planted");
				}
			}
			if (!open.isEmpty())
			{
				notes.add("Protection for " + what + " also depends on which you plant where (" + String.join("; ", open) + ").");
			}
			if (total < n)
			{
				notes.add("Protection for " + (n - total) + " more " + FarmingModel.noun(e.getKey(), n - total)
					+ ": listed once their planting material is in your inventory.");
			}
		}
		for (Map.Entry<Integer, Integer> e : payQty.entrySet())
		{
			FarmingSeeds.Payment pay = payItem.get(e.getKey());
			// Paid in the same item the run composts with (potatoes cost buckets of compost): one need for the total.
			int compostToo = 0;
			for (ItemNeed n : needs)
			{
				if (n.getKey().equals("supply.compost." + pay.getItemId()))
				{
					compostToo = n.getQuantity();
				}
			}
			int noted = notedId(pay.getGameval());
			int[] ids = noted > 0 ? new int[]{pay.getItemId(), noted} : new int[]{pay.getItemId()};
			needs.add(new ItemNeed("payment." + pay.getItemId(), pay.getLabel(), "Protection", ids, e.getValue() + compostToo,
				ItemNeed.Necessity.RECOMMENDED, ItemNeed.SatisfiedBy.INVENTORY_ONLY,
				"protects " + payFor.get(e.getKey()) + "; noted is fine" + (compostToo > 0 ? "; includes the " + compostToo + " you compost with" : "")));
		}
	}

	/** Herb, flower, seaweed and other gardener-less or immune crops are never protected. */
	static boolean protectable(FarmingPatchType type)
	{
		switch (type)
		{
			case ALLOTMENT:
			case HOPS:
			case BUSH:
			case TREE:
			case FRUIT_TREE:
			case HARDWOOD_TREE:
			case CALQUAT:
			case CACTUS:
			case SPIRIT_TREE:
			case CELASTRUS:
			case REDWOOD:
				return true;
			default:
				return false;
		}
	}

	private static boolean saplingType(FarmingPatchType type)
	{
		switch (type)
		{
			case TREE:
			case FRUIT_TREE:
			case HARDWOOD_TREE:
			case REDWOOD:
			case SPIRIT_TREE:
			case CALQUAT:
			case CELASTRUS:
			case CRYSTAL_TREE:
				return true;
			default:
				return false;
		}
	}

	private static ItemNeed tool(ItemNeed need, FarmingAccountFacts.ToolStore store, int itemId)
	{
		if (store != null && store.count(itemId) > 0)
		{
			need.withReachableStore("tool leprechaun", store.count(itemId));
		}
		return need.unstackable();
	}

	private static void compost(String option, int planting, FarmingAccountFacts.ToolStore store, List<ItemNeed> needs)
	{
		switch (option)
		{
			case "COMPOST":
				needs.add(compostNeed("Compost", ItemID.BUCKET_COMPOST, planting, store));
				break;
			case "SUPERCOMPOST":
				needs.add(compostNeed("Supercompost", ItemID.BUCKET_SUPERCOMPOST, planting, store));
				break;
			case "ULTRACOMPOST":
				needs.add(compostNeed("Ultracompost", ItemID.BUCKET_ULTRACOMPOST, planting, store));
				break;
			case "BOTTOMLESS":
				// A filled bottomless bucket covers every patch; its charges are not readable. The leprechaun can hold it.
				ItemNeed b = new ItemNeed("supply.bottomless", "Bottomless compost bucket", "Supplies", new int[]{ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED}, 0,
					ItemNeed.Necessity.REQUIRED, ItemNeed.SatisfiedBy.INVENTORY_ONLY, "filled; charges not readable").withSearchText("Bottomless compost bucket")
					.unstackable();
				if (store != null && store.getBottomless() > 1)
				{
					b.withReachableStore("tool leprechaun (filled)", 1);
				}
				needs.add(b);
				break;
			default:
				break;
		}
	}

	private static ItemNeed compostNeed(String label, int itemId, int count, FarmingAccountFacts.ToolStore store)
	{
		ItemNeed n = new ItemNeed("supply.compost." + itemId, label, "Supplies", new int[]{itemId}, count, ItemNeed.Necessity.REQUIRED,
			ItemNeed.SatisfiedBy.INVENTORY_ONLY, count + " patches").unstackable();
		if (store != null && store.count(itemId) > 0)
		{
			n.withReachableStore("tool leprechaun", store.count(itemId));
		}
		return n;
	}

	/**
	 * Inventory slots the still-to-carry supplies take (stackables one slot,
	 * buckets / saplings / tools one each; worn and stored items none), or -1
	 * when a quantity is unknown. An estimate of supplies only: the harvest
	 * needs room too.
	 */
	public static int slotsNeeded(com.osrstelemetry.plugin.prep.PreparationState state)
	{
		int slots = 0;
		for (com.osrstelemetry.plugin.prep.NeedResult r : state.getResults())
		{
			ItemNeed n = r.getNeed();
			if (n.getSatisfiedBy() == ItemNeed.SatisfiedBy.WORN_ONLY || r.getStatus() == com.osrstelemetry.plugin.prep.NeedStatus.REACHABLE
				|| r.getVia() == com.osrstelemetry.plugin.prep.NeedResult.Via.WORN || r.getVia() == com.osrstelemetry.plugin.prep.NeedResult.Via.STAFF
				|| r.getVia() == com.osrstelemetry.plugin.prep.NeedResult.Via.RUNE_POUCH)
			{
				continue;
			}
			if ("Optional".equals(n.getSection()) && !r.isSatisfied())
			{
				continue; // optional and not carried: not part of the load
			}
			slots += n.isStackable() ? 1 : n.required();
		}
		return slots;
	}

	// -- travel ------------------------------------------------------------------------------

	/**
	 * Travel needs for the whole suggested run, with resources shared: every
	 * chosen transport that uses the same item (any variant in common) draws
	 * on ONE need whose quantity covers all its uses (tablets: one per use;
	 * jewellery: one charge per use, counted across the variants carried);
	 * runes are summed across spells; one fairy-ring staff serves every ring.
	 * A transport is never judged as if it had the item to itself.
	 *
	 * @return transport id -> the need keys it relies on
	 */
	public static Map<String, List<String>> travelNeeds(List<TransportAvailability> chosenPerSite, AccountCapability cap, List<ItemNeed> needs)
	{
		Map<String, Integer> uses = new LinkedHashMap<>();
		Map<String, TransportDef> defs = new LinkedHashMap<>();
		Map<Integer, Integer> runes = new LinkedHashMap<>();
		Map<Integer, String> runeNames = new LinkedHashMap<>();
		Map<String, List<String>> keys = new LinkedHashMap<>();
		boolean staff = false;
		for (TransportAvailability chosen : chosenPerSite)
		{
			if (chosen == null)
			{
				continue;
			}
			TransportDef t = chosen.getTransport();
			defs.put(t.id, t);
			uses.merge(t.id, 1, Integer::sum);
			List<String> k = keys.computeIfAbsent(t.id, x -> new ArrayList<>());
			for (TransportDef.RuneCost r : TransportDef.list(t.runes))
			{
				runes.merge(r.id, r.qty, Integer::sum);
				runeNames.put(r.id, runeLabel(r.gameval));
				addOnce(k, "rune." + r.id);
			}
			if (t.wield != null && cap.diary(t.wield.unlessDiary == null ? "" : t.wield.unlessDiary) != Known.YES)
			{
				staff = true;
				addOnce(k, STAFF_KEY);
			}
		}
		// Pool transports that share an item.
		List<List<TransportDef>> pools = new ArrayList<>();
		for (TransportDef t : defs.values())
		{
			if (usableIds(t).isEmpty())
			{
				continue;
			}
			List<TransportDef> into = null;
			for (List<TransportDef> pool : pools)
			{
				for (TransportDef o : pool)
				{
					if (!Collections.disjoint(usableIds(o), usableIds(t)))
					{
						into = pool;
						break;
					}
				}
				if (into != null)
				{
					break;
				}
			}
			if (into == null)
			{
				into = new ArrayList<>();
				pools.add(into);
			}
			into.add(t);
		}
		for (List<TransportDef> pool : pools)
		{
			TransportDef first = pool.get(0);
			Map<Integer, TransportDef.ItemVariant> variants = new LinkedHashMap<>();
			int poolUses = 0;
			List<String> names = new ArrayList<>();
			boolean tablet = false;
			for (TransportDef t : pool)
			{
				poolUses += uses.get(t.id);
				names.add(t.name);
				tablet |= t.type == TransportDef.Type.TABLET;
				for (TransportDef.ItemVariant v : TransportDef.list(t.items))
				{
					if (v.charges != TransportDef.Charges.DEPLETED)
					{
						variants.putIfAbsent(v.id, v);
					}
				}
			}
			int[] ids = new int[variants.size()];
			int[] units = new int[variants.size()];
			boolean allConsumed = true;
			int i = 0;
			for (TransportDef.ItemVariant v : variants.values())
			{
				ids[i] = v.id;
				units[i] = unitsOf(v);
				allConsumed &= v.charges == TransportDef.Charges.CONSUMED;
				i++;
			}
			String key = "travel." + first.id;
			String label = itemLabel(first);
			ItemNeed.SatisfiedBy by = tablet ? ItemNeed.SatisfiedBy.INVENTORY_ONLY : ItemNeed.SatisfiedBy.INVENTORY_OR_WORN;
			ItemNeed need;
			if (allConsumed)
			{
				need = new ItemNeed(key, label, "Travel", ids, poolUses, ItemNeed.Necessity.REQUIRED, by, String.join(", ", names));
			}
			else
			{
				// One use: any one usable item will do. More: count charges across what is carried.
				need = new ItemNeed(key, label, "Travel", ids, poolUses > 1 ? poolUses : 0, ItemNeed.Necessity.REQUIRED, by, String.join(", ", names))
					.withUnits(units, "charge");
			}
			needs.add(need.withSearchText(label));
			for (TransportDef t : pool)
			{
				addOnce(keys.computeIfAbsent(t.id, x -> new ArrayList<>()), key);
			}
		}
		for (Map.Entry<Integer, Integer> e : runes.entrySet())
		{
			needs.add(new ItemNeed("rune." + e.getKey(), runeNames.get(e.getKey()), "Travel", new int[]{e.getKey()}, e.getValue(),
				ItemNeed.Necessity.REQUIRED, ItemNeed.SatisfiedBy.INVENTORY_OR_RUNE_POUCH, "teleport spells"));
		}
		if (staff)
		{
			needs.add(new ItemNeed(STAFF_KEY, "Dramen or Lunar staff", "Travel",
				new int[]{ItemID.DRAMEN_STAFF, ItemID.LUNAR_MOONCLAN_LIMINAL_STAFF, ItemID.DRAMEN_STAFF_AIR, ItemID.DRAMEN_STAFF_FIRE, ItemID.DRAMEN_STAFF_WATER},
				0, ItemNeed.Necessity.REQUIRED, ItemNeed.SatisfiedBy.INVENTORY_OR_WORN, "every fairy ring").withSearchText("Dramen staff"));
		}
		return keys;
	}

	static final String STAFF_KEY = "travel.fairy_staff";

	private static void addOnce(List<String> list, String v)
	{
		if (!list.contains(v))
		{
			list.add(v);
		}
	}

	private static List<Integer> usableIds(TransportDef t)
	{
		List<Integer> ids = new ArrayList<>();
		for (TransportDef.ItemVariant v : TransportDef.list(t.items))
		{
			if (v.charges != TransportDef.Charges.DEPLETED)
			{
				ids.add(v.id);
			}
		}
		return ids;
	}

	/** Units (uses) one item of this variant provides; unobservable charges count as covering (and are flagged uncertain elsewhere). */
	static int unitsOf(TransportDef.ItemVariant v)
	{
		switch (v.charges)
		{
			case FIXED:
				return v.count == null ? ItemNeed.COVERS_ANY : v.count;
			case CONSUMED:
				return 1;
			default:
				return ItemNeed.COVERS_ANY;
		}
	}

	static boolean chargesObservable(TransportDef.ItemVariant v)
	{
		switch (v.charges)
		{
			case UNLIMITED:
			case FIXED:
			case CONSUMED:
				return true;
			case DAILY_LIMIT:
				return v.dailyLimit == null || v.dailyLimit < 0;
			default:
				return false;
		}
	}

	static String itemLabel(TransportDef t)
	{
		String name = t.name;
		int colon = name.indexOf(':');
		if (colon > 0)
		{
			return name.substring(0, colon);
		}
		return name.endsWith(" tablet") ? name.substring(0, name.length() - " tablet".length()) : name;
	}

	static String runeLabel(String gameval)
	{
		String base = gameval.replace("RUNE", "").toLowerCase(java.util.Locale.ROOT);
		return Character.toUpperCase(base.charAt(0)) + base.substring(1) + " rune";
	}

	// -- order -------------------------------------------------------------------------------

	private static int[] order(List<FarmingSites.Site> sites, List<TravelPlanner.Choice> choices)
	{
		int n = sites.size();
		int[] arrival = new int[n];
		int[][] walk = new int[n][n];
		for (int i = 0; i < n; i++)
		{
			TravelPlanner.Choice c = choices.get(i);
			arrival[i] = c.getChosen() == null || c.getWalkTiles() < 0 || c.getWalkTiles() >= 10_000 ? FarmingSiteOrder.UNREACHABLE
				: FarmingSiteOrder.TELEPORT_TICKS + (c.getWalkTiles() + 1) / 2;
			for (int j = 0; j < n; j++)
			{
				walk[i][j] = i == j ? 0 : FarmingSiteOrder.walkTicks(sites.get(i).getTile(), sites.get(j).getTile());
			}
		}
		return FarmingSiteOrder.order(arrival, walk);
	}

	private static String noTransportNote(FarmingPatchCatalog catalog, FarmingSites.Site site, Map<String, TransportAvailability> byId)
	{
		for (String patchId : site.getPatchIds())
		{
			if (!FarmingTravel.forStop(catalog, patchId).isEmpty())
			{
				return "no usable transport known yet";
			}
		}
		return "no guided route to this patch yet";
	}

	public static String itemOrName(TransportDef t)
	{
		return t.type == TransportDef.Type.SPELL ? t.name : itemLabel(t);
	}

	public static String describe(Availability a)
	{
		switch (a)
		{
			case DEPLETED:
				return "depleted";
			case REQUIREMENTS_UNMET:
				return "locked";
			default:
				return "unknown";
		}
	}
}
