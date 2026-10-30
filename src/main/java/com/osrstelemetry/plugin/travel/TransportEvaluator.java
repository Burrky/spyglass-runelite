package com.osrstelemetry.plugin.travel;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountReaders;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.account.ItemLocator;
import com.osrstelemetry.plugin.account.Known;
import com.osrstelemetry.plugin.account.RequirementCheck;
import com.osrstelemetry.plugin.account.RequirementEvaluator;
import com.osrstelemetry.plugin.account.StorageEvidence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;

/**
 * Evaluates transports against one {@link AccountCapability} plus domain
 * {@link EvidenceSource}. Pure and deterministic.
 *
 * <h3>One final state</h3>
 * <ol>
 *   <li>No owner → UNKNOWN (logout / incomplete metadata never produce facts).</li>
 *   <li>Requirements ({@link RequirementEvaluator}): any evidenced unmet →
 *   REQUIREMENTS_UNMET.</li>
 *   <li>Otherwise any missing evidence (a requirement, or the resource itself) →
 *   UNKNOWN.</li>
 *   <li>Otherwise the resource result: the best item variant / rune supply /
 *   wielded item location, ranked READY &gt; CHARGES_UNKNOWN &gt; IN_BANK &gt;
 *   ELSEWHERE &gt; DEPLETED.</li>
 * </ol>
 * The result keeps what was found ("have"), what is met, what is unmet and
 * what evidence is missing apart; nothing is reported as a provisional state.
 * Not found is never "not owned".
 */
public final class TransportEvaluator
{
	static final Map<Integer, String> RUNE_NAMES = new HashMap<>();

	static
	{
		RUNE_NAMES.put(ItemID.AIRRUNE, "air");
		RUNE_NAMES.put(ItemID.WATERRUNE, "water");
		RUNE_NAMES.put(ItemID.EARTHRUNE, "earth");
		RUNE_NAMES.put(ItemID.FIRERUNE, "fire");
		RUNE_NAMES.put(ItemID.LAWRUNE, "law");
		RUNE_NAMES.put(ItemID.NATURERUNE, "nature");
		RUNE_NAMES.put(ItemID.SOULRUNE, "soul");
		RUNE_NAMES.put(ItemID.ASTRALRUNE, "astral");
	}

	private TransportEvaluator()
	{
	}

	public static List<TransportAvailability> evaluateAll(TransportCatalog catalog, AccountCapability cap, EvidenceSource evidence, long now)
	{
		List<TransportAvailability> out = new ArrayList<>();
		for (TransportDef t : catalog.all())
		{
			out.add(evaluate(t, cap, evidence, now));
		}
		return out;
	}

	public static TransportAvailability evaluate(TransportDef t, AccountCapability cap, EvidenceSource evidence, long now)
	{
		List<String> have = new ArrayList<>();
		List<String> met = new ArrayList<>();
		List<String> unmet = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		if (cap == null || cap.getOwner() == null)
		{
			missing.add("account knowledge: none (" + (cap == null ? "not started" : cap.getStatus()) + ")");
			return new TransportAvailability(t, Availability.UNKNOWN, have, met, unmet, missing);
		}
		if (!t.isVerified())
		{
			missing.add("catalogue entry not verified: its requirements may be incomplete");
		}
		RequirementCheck check = RequirementEvaluator.check(t.requirements(), cap, evidence);
		met.addAll(check.getMet());
		unmet.addAll(check.getUnmet());
		missing.addAll(check.getMissing());
		if (t.walkTo != null)
		{
			walkCheck(t, cap, met, unmet, missing);
		}

		Resource resource = resource(t, cap, now, met, missing);
		have.addAll(resource.lines);
		if (resource.gap != null)
		{
			missing.add(resource.gap);
		}
		Availability state;
		if (!unmet.isEmpty())
		{
			state = Availability.REQUIREMENTS_UNMET;
		}
		else if (!missing.isEmpty())
		{
			state = Availability.UNKNOWN;
		}
		else
		{
			state = resource.state;
		}
		return new TransportAvailability(t, state, have, met, unmet, missing);
	}

	/**
	 * The walk from the entry teleport to the entrance, for this account (canonical §28.11): the account's cheapest usable
	 * walk is met with what it needs; when none is usable, the cheapest walk's requirements say why (unknown stays unknown);
	 * while the account's own search is still running, the walk is unknown.
	 */
	static void walkCheck(TransportDef t, AccountCapability cap, List<String> met, List<String> unmet, List<String> missing)
	{
		com.osrstelemetry.plugin.navigation.AccountRouter router = com.osrstelemetry.plugin.navigation.AccountRouter.bundled();
		com.osrstelemetry.plugin.navigation.AccountRouter.Answer a = router.walk(t.walkTo, t.walkFrom, router.planning(cap));
		switch (a.getOutcome())
		{
			case FOUND:
				met.addAll(com.osrstelemetry.plugin.navigation.NavRequirementCheck.check(a.getWalk().getRequirement(), cap).getMet());
				return;
			case UNREACHABLE:
			{
				List<com.osrstelemetry.plugin.navigation.RouteTable.Walk> v = router.table().variants(t.walkTo, t.walkFrom);
				if (v.isEmpty())
				{
					unmet.add("no way to walk to the entrance");
					return;
				}
				// Closed, or waiting on something unread? Ask again with everything unread allowed.
				com.osrstelemetry.plugin.navigation.AccountRouter.Answer p = router.walk(t.walkTo, t.walkFrom, router.possible(cap));
				if (p.getOutcome() == com.osrstelemetry.plugin.navigation.NavOutcome.FOUND)
				{
					com.osrstelemetry.plugin.navigation.NavRequirementCheck w = com.osrstelemetry.plugin.navigation.NavRequirementCheck.check(
						p.getWalk().getRequirement(), cap);
					missing.addAll(w.getMissing().isEmpty() ? java.util.Collections.singletonList("the walk to the entrance is not known yet")
						: w.getMissing()); // unknown stays unknown, never assumed either way
					return;
				}
				if (p.getOutcome() != com.osrstelemetry.plugin.navigation.NavOutcome.UNREACHABLE)
				{
					missing.add("the walk to the entrance is still being worked out");
					return;
				}
				com.osrstelemetry.plugin.navigation.NavRequirementCheck w = com.osrstelemetry.plugin.navigation.NavRequirementCheck.check(
					v.get(0).getRequirement(), cap);
				unmet.addAll(w.getUnmet().isEmpty() ? java.util.Collections.singletonList("the walk to the entrance is closed for this account")
					: w.getUnmet());
				return;
			}
			default:
				missing.add("the walk to the entrance is still being worked out");
		}
	}

	// -- resources ------------------------------------------------------------------------

	static final class Resource
	{
		final Availability state;
		final List<String> lines;
		/** The missing evidence when the resource itself is unknown, else null. */
		final String gap;

		Resource(Availability state, List<String> lines, String gap)
		{
			this.state = state;
			this.lines = lines;
			this.gap = gap;
		}

		static Resource of(Availability state, String line)
		{
			return new Resource(state, new ArrayList<>(Collections.singletonList(line)), null);
		}

		static Resource unknown(String gap)
		{
			return new Resource(Availability.UNKNOWN, new ArrayList<>(), gap);
		}
	}

	private static Resource resource(TransportDef t, AccountCapability cap, long now, List<String> met, List<String> missing)
	{
		Resource result = null;
		if (!TransportDef.list(t.items).isEmpty())
		{
			result = items(t, cap, now);
		}
		if (!TransportDef.list(t.runes).isEmpty())
		{
			result = combine(result, runes(t.runes, cap, now));
		}
		if (t.wield != null)
		{
			result = combine(result, wield(t.wield, cap, now, met, missing));
		}
		return result == null ? Resource.of(Availability.READY, "nothing to carry") : result;
	}

	private static Resource combine(Resource a, Resource b)
	{
		if (a == null)
		{
			return b;
		}
		Availability worse = Availability.worse(a.state, b.state);
		List<String> lines = new ArrayList<>(a.lines);
		lines.addAll(b.lines);
		String gap = a.gap != null ? a.gap : b.gap;
		return new Resource(worse, lines, gap);
	}

	private static Resource items(TransportDef t, AccountCapability cap, long now)
	{
		Availability best = null;
		String bestLine = null;
		List<String> depleted = new ArrayList<>();
		for (TransportDef.ItemVariant v : t.items)
		{
			for (ItemLocator.Found f : ItemLocator.find(cap, v.id))
			{
				Availability state;
				// Observed charges or today's uses (the game's own messages; canonical §28.13). Unknown stays unknown.
				Integer left = v.pool == null || !chargesUnobservable(v) ? null : cap.getCharges().remaining(v.pool, now);
				if (v.charges == TransportDef.Charges.DEPLETED)
				{
					depleted.add(v.gameval + " x" + f.getQuantity() + " " + ItemLocator.where(f, now) + (v.note == null ? "" : " (" + v.note + ")"));
					state = Availability.DEPLETED;
				}
				else if (left != null && left <= 0)
				{
					depleted.add(v.gameval + " " + ItemLocator.where(f, now) + " (" + cap.getCharges().describe(v.pool, now) + ")");
					state = Availability.DEPLETED;
				}
				else if (!f.getPlace().isCarried())
				{
					state = f.getPlace() == ItemLocator.Place.BANK ? Availability.IN_BANK : Availability.ELSEWHERE;
				}
				else if (chargesUnobservable(v) && left == null)
				{
					state = Availability.CHARGES_UNKNOWN;
				}
				else
				{
					state = Availability.READY;
				}
				if (state != Availability.DEPLETED && (best == null || state.resourceRank() < best.resourceRank()))
				{
					best = state;
					bestLine = v.gameval + " x" + f.getQuantity() + " " + ItemLocator.where(f, now) + " - "
						+ (left != null ? cap.getCharges().describe(v.pool, now) : chargesText(v));
				}
			}
		}
		if (best != null)
		{
			List<String> lines = new ArrayList<>();
			lines.add(bestLine);
			for (String d : depleted)
			{
				lines.add("also a depleted variant: " + d);
			}
			return new Resource(best, lines, null);
		}
		if (!depleted.isEmpty())
		{
			List<String> lines = new ArrayList<>();
			for (String d : depleted)
			{
				lines.add("depleted variant: " + d);
			}
			lines.add("only depleted variants were found where Spyglass looked; a usable one may be held elsewhere");
			return new Resource(Availability.DEPLETED, lines, null);
		}
		return Resource.unknown(ItemLocator.notFound(cap, now, "no usable variant seen"));
	}

	static boolean chargesUnobservable(TransportDef.ItemVariant v)
	{
		return v.charges == TransportDef.Charges.STORED_UNOBSERVED
			|| (v.charges == TransportDef.Charges.DAILY_LIMIT && v.dailyLimit != null && v.dailyLimit > 0);
	}

	static String chargesText(TransportDef.ItemVariant v)
	{
		switch (v.charges)
		{
			case UNLIMITED:
				return "unlimited";
			case FIXED:
				return v.count + (v.count == 1 ? " charge" : " charges");
			case CONSUMED:
				return "used up per teleport";
			case DAILY_LIMIT:
				return v.dailyLimit < 0 ? "unlimited" : v.dailyLimit + " a day; today's uses are not observable";
			case STORED_UNOBSERVED:
				return "charges are not observable" + (v.note == null ? "" : " (" + v.note + ")");
			default:
				return "depleted";
		}
	}

	private static Resource runes(List<TransportDef.RuneCost> cost, AccountCapability cap, long now)
	{
		Map<Integer, Integer> inventory = cap.getInventory();
		Map<Integer, Integer> worn = cap.getWorn();
		if (inventory == null || worn == null)
		{
			return Resource.unknown("runes: inventory / equipment not read yet");
		}
		Map<Integer, Integer> pouch = cap.getRunePouch();
		boolean pouchCarried = holdsPouch(inventory);
		Map<Integer, Integer> carriedRunes = new HashMap<>(inventory);
		if (pouchCarried && pouch != null)
		{
			pouch.forEach((k, v) -> carriedRunes.merge(k, v, Integer::sum));
		}
		Map<Integer, Long> carried = RuneSources.supply(carriedRunes, worn);
		Map<Integer, Integer> missingRunes = missing(cost, carried);
		if (missingRunes.isEmpty())
		{
			return Resource.of(Availability.READY, "runes carried (" + sources(worn, pouchCarried, pouch) + ")");
		}
		if (pouchCarried && pouch == null)
		{
			return Resource.unknown("runes: rune pouch carried but its contents are not read yet");
		}
		StorageEvidence bank = cap.getBank();
		if (bank == null)
		{
			return Resource.unknown("runes: missing " + describe(missingRunes) + " carried; bank not seen yet for this account");
		}
		Map<Integer, Integer> banked = new HashMap<>(bank.getItems());
		boolean pouchBanked = holdsPouch(bank.getItems());
		if (pouchBanked && !pouchCarried && pouch != null)
		{
			pouch.forEach((k, v) -> banked.merge(k, v, Integer::sum));
		}
		Map<Integer, Long> withBank = RuneSources.plus(carried, RuneSources.supply(banked, bank.getItems()));
		String age = ItemLocator.age(now - bank.getObservedAt());
		if (missing(cost, withBank).isEmpty())
		{
			return Resource.of(Availability.IN_BANK, "runes: needs " + describe(missingRunes) + " from the bank (last seen " + age + " ago"
				+ (pouchBanked && !pouchCarried ? "; the rune pouch was in the bank" : "") + ")");
		}
		StorageEvidence group = cap.getGroupStorage();
		if (group != null)
		{
			Map<Integer, Long> withGroup = RuneSources.plus(withBank, RuneSources.supply(group.getItems(), group.getItems()));
			if (missing(cost, withGroup).isEmpty())
			{
				return Resource.of(Availability.ELSEWHERE, "runes: needs " + describe(missingRunes) + " from the bank / shared group storage");
			}
		}
		return Resource.unknown("runes: missing " + describe(missing(cost, withBank)) + "; not carried or in the bank when last seen (" + age
			+ " ago); may be held elsewhere");
	}

	static boolean holdsPouch(Map<Integer, Integer> items)
	{
		for (int id : AccountReaders.RUNE_POUCH_ITEMS)
		{
			if (ItemLocator.count(items, id) > 0)
			{
				return true;
			}
		}
		return false;
	}

	private static Map<Integer, Integer> missing(List<TransportDef.RuneCost> cost, Map<Integer, Long> supply)
	{
		Map<Integer, Integer> out = new LinkedHashMap<>();
		for (TransportDef.RuneCost c : cost)
		{
			long have = supply.getOrDefault(c.id, 0L);
			if (have < c.qty)
			{
				out.put(c.id, (int) (c.qty - have));
			}
		}
		return out;
	}

	static String describe(Map<Integer, Integer> runes)
	{
		List<String> parts = new ArrayList<>();
		runes.forEach((id, n) -> parts.add(n + " " + RUNE_NAMES.getOrDefault(id, "rune " + id)));
		return String.join(", ", parts);
	}

	private static String sources(Map<Integer, Integer> worn, boolean pouchCarried, Map<Integer, Integer> pouch)
	{
		List<String> parts = new ArrayList<>();
		parts.add("inventory");
		if (pouchCarried)
		{
			parts.add("rune pouch");
		}
		for (Integer item : worn.keySet())
		{
			int[] provided = RuneSources.providedBy(item);
			if (provided.length > 0)
			{
				List<String> names = new ArrayList<>();
				for (int r : provided)
				{
					names.add(RUNE_NAMES.get(r));
				}
				parts.add("worn: unlimited " + String.join(" + ", names));
			}
		}
		return String.join(", ", parts);
	}

	private static Resource wield(TransportDef.WieldReq wield, AccountCapability cap, long now, List<String> met, List<String> missing)
	{
		Known waived = Known.NO;
		RequirementCheck waiver = null;
		if (wield.unlessDiary != null)
		{
			waiver = RequirementEvaluator.check(diaryOnly(wield.unlessDiary), cap, EvidenceSource.NONE);
			waived = waiver.overall();
			if (waived == Known.YES)
			{
				met.add(RequirementEvaluator.diaryLabel(wield.unlessDiary) + " diary (no " + wield.name + " needed)");
				return Resource.of(Availability.READY, wield.name + ": not needed");
			}
		}
		Availability best = null;
		String line = null;
		for (TransportDef.ItemRef item : wield.items)
		{
			ItemLocator.Found f = ItemLocator.best(cap, item.id);
			if (f == null)
			{
				continue;
			}
			Availability state = f.getPlace().isCarried() ? Availability.READY
				: f.getPlace() == ItemLocator.Place.BANK ? Availability.IN_BANK : Availability.ELSEWHERE;
			if (best == null || state.resourceRank() < best.resourceRank())
			{
				best = state;
				line = wield.name + ": " + item.gameval + " " + ItemLocator.where(f, now) + (f.getPlace() == ItemLocator.Place.INVENTORY ? " (wield it)" : "");
			}
		}
		if (best != null)
		{
			return Resource.of(best, line);
		}
		if (waived == Known.UNKNOWN && waiver != null)
		{
			missing.addAll(waiver.getMissing()); // the diary might make the staff unnecessary
		}
		return Resource.unknown(ItemLocator.notFound(cap, now, wield.name));
	}

	private static com.osrstelemetry.plugin.account.RequirementSet diaryOnly(String key)
	{
		com.osrstelemetry.plugin.account.RequirementSet r = new com.osrstelemetry.plugin.account.RequirementSet();
		r.members = false;
		r.diaries = Collections.singletonList(key);
		return r;
	}
}
