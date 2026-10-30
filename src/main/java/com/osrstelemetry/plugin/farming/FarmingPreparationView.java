package com.osrstelemetry.plugin.farming;

import com.osrstelemetry.plugin.prep.ItemNeed;
import com.osrstelemetry.plugin.prep.NeedResult;
import com.osrstelemetry.plugin.prep.NeedStatus;
import com.osrstelemetry.plugin.prep.PreparationState;
import com.osrstelemetry.plugin.prep.Readiness;
import com.osrstelemetry.plugin.travel.Availability;
import com.osrstelemetry.plugin.travel.TransportAvailability;
import com.osrstelemetry.plugin.travel.TransportDef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * What the run preparation page shows (canonical wireframes E, F, G; Phase H
 * refinement): a summary, one compact row per supply type with its item, its
 * quantity and where it is, and one compact row per destination in the
 * suggested order with the transport Spyglass would take and whether it can be
 * confirmed. Player text only: no internal state names.
 *
 * <h3>Readiness</h3>
 * Only {@link Readiness#CONFIRMED} reads as ready: every required supply type
 * carried AND every destination confirmed. A transport whose charges cannot be
 * observed, a patch whose access is not confirmed, or a destination with no
 * usable transport keeps the plan UNVERIFIED even with everything carried.
 * The player can always begin explicitly; the button says what is missing.
 * Advisory: nothing here acts in the game.
 */
public final class FarmingPreparationView
{
	/** How a row's status reads. */
	public enum Tone
	{
		/** Carried / ready. */
		DONE,
		/** Something the player can do now (withdraw, fetch). */
		ACTION,
		/** Quiet information (optional items, stored elsewhere). */
		INFO,
		/** Could not be confirmed (charges, access, evidence). */
		UNVERIFIED,
		/** Known problem (not found where Spyglass looked, locked, no transport). */
		WARN
	}

	/** Where a supply row sits on the page: still to get first, then optional, then what is already covered. */
	public enum Group
	{
		/** Not covered yet: required supplies, chosen protection payments and chosen equipment. */
		NEEDED,
		/** Optional extras not carried (magic secateurs, herb sack). Never a shortage. */
		OPTIONAL,
		/** Carried, worn or stored where it is used. */
		READY
	}

	public static final class NeedRow
	{
		private final String key;
		private final String section;
		private final String label;
		private final String quantity;
		private final int itemId;
		private final boolean required;
		private final boolean satisfied;
		private final Tone tone;
		private final String status;
		private final String support;
		private final String locate;
		private final int[] itemIds;

		private final Group group;

		NeedRow(String key, String section, String label, String quantity, int itemId, boolean required, boolean satisfied, Tone tone, String status,
			String support, String locate, int[] itemIds)
		{
			this.group = satisfied ? Group.READY : "Optional".equals(section) ? Group.OPTIONAL : Group.NEEDED;
			this.key = key;
			this.section = section;
			this.label = label;
			this.quantity = quantity;
			this.itemId = itemId;
			this.required = required;
			this.satisfied = satisfied;
			this.tone = tone;
			this.status = status;
			this.support = support;
			this.locate = locate;
			this.itemIds = itemIds;
		}

		public String getKey()
		{
			return key;
		}

		/** "Supplies", "Tools" or "Travel". */
		public String getSection()
		{
			return section;
		}

		/** "Ranarr seed". */
		public String getLabel()
		{
			return label;
		}

		/** "×6", "2 charges", or "" when any one will do. */
		public String getQuantity()
		{
			return quantity;
		}

		/** The item to picture (the variant carried or last seen, else the first acceptable one). */
		public int getItemId()
		{
			return itemId;
		}

		public boolean isRequired()
		{
			return required;
		}

		public Group getGroup()
		{
			return group;
		}

		public boolean isSatisfied()
		{
			return satisfied;
		}

		public Tone getTone()
		{
			return tone;
		}

		/** "✓ Carried", "Withdraw 6", "Not carried · in your bank", "Not found where Spyglass looked", "Bank not seen yet". */
		public String getStatus()
		{
			return status;
		}

		/** Quieter supporting facts: "Tab 2 · seen 3h ago", "Have 2 · main tab"; "" for none. */
		public String getSupport()
		{
			return support;
		}

		/** What Find says: where Spyglass last saw it, or that it can't locate it. */
		public String getLocate()
		{
			return locate;
		}

		/** Find is offered for anything not yet carried. */
		public boolean isFindable()
		{
			return !satisfied;
		}

		/** Ids Find marks in the bank highlight. */
		public int[] getItemIds()
		{
			return itemIds.clone();
		}
	}

	public static final class SiteRow
	{
		private final String site;
		private final int order;
		private final String transport;
		private final int itemId;
		private final Tone tone;
		private final String status;
		private final List<String> details;
		private final boolean unverified;

		public SiteRow(String site, int order, String transport, int itemId, Tone tone, String status, List<String> details, boolean unverified)
		{
			this.site = site;
			this.order = order;
			this.transport = transport;
			this.itemId = itemId;
			this.tone = tone;
			this.status = status;
			this.details = Collections.unmodifiableList(details);
			this.unverified = unverified;
		}

		public String getSite()
		{
			return site;
		}

		/** 1-based position in the suggested order. */
		public int getOrder()
		{
			return order;
		}

		/** "Camelot Teleport", "Ardougne cloak", or "No usable transport known". */
		public String getTransport()
		{
			return transport;
		}

		/** Sprite for the transport's item (or its first rune, or the fairy-ring staff); -1 for none. */
		public int getItemId()
		{
			return itemId;
		}

		public Tone getTone()
		{
			return tone;
		}

		/** "✓ Ready", "Charges unknown", "Not carried yet", "Still in the run: no usable transport known". */
		public String getStatus()
		{
			return status;
		}

		/** Behind the expansion: the chosen transport's evidence, other options, and why the rest are not usable. */
		public List<String> getDetails()
		{
			return details;
		}

		/** Something about this destination cannot be confirmed (or is known blocked). */
		public boolean isUnverified()
		{
			return unverified;
		}
	}

	private final Readiness readiness;
	private final String headline;
	private final List<String> sublines;
	private final String beginLabel;
	private final boolean bankOpen;
	private final List<NeedRow> needs;
	private final List<SiteRow> sites;
	private final List<String> notes;
	private final int toCollect;
	private final int unverifiedSites;
	private final String slots;

	private FarmingPreparationView(Readiness readiness, String headline, List<String> sublines, String beginLabel, boolean bankOpen, List<NeedRow> needs,
		List<SiteRow> sites, List<String> notes, int toCollect, int unverifiedSites, String slots)
	{
		this.slots = slots;
		this.readiness = readiness;
		this.headline = headline;
		this.sublines = Collections.unmodifiableList(sublines);
		this.beginLabel = beginLabel;
		this.bankOpen = bankOpen;
		this.needs = Collections.unmodifiableList(needs);
		this.sites = Collections.unmodifiableList(sites);
		this.notes = Collections.unmodifiableList(notes);
		this.toCollect = toCollect;
		this.unverifiedSites = unverifiedSites;
	}

	/** With the bank closed (or unknown) and no bank-view knowledge. */
	public static FarmingPreparationView of(FarmingPreparation.Plan plan, PreparationState state, boolean bankOpen)
	{
		return of(plan, state, bankOpen, null, System.currentTimeMillis());
	}

	/**
	 * @param visibleInBank item ids shown in the bank view the player has open (read from the bank's widgets,
	 *                      never changed), or null when the bank is closed or that could not be read
	 */
	public static FarmingPreparationView of(FarmingPreparation.Plan plan, PreparationState state, boolean bankOpen, Set<Integer> visibleInBank, long now)
	{
		List<NeedRow> rows = new ArrayList<>();
		int toCollect = 0;
		int unknownRequired = 0;
		int inBankRequired = 0;
		boolean bankSeen = false;
		for (NeedResult r : state.getResults())
		{
			ItemNeed n = r.getNeed();
			boolean required = n.getNecessity() == ItemNeed.Necessity.REQUIRED;
			bankSeen |= r.getBankSeenAt() > 0;
			if (required && !r.isSatisfied())
			{
				toCollect++;
				if (r.getStatus() == NeedStatus.UNKNOWN)
				{
					unknownRequired++;
				}
				if (r.getStatus() == NeedStatus.IN_BANK)
				{
					inBankRequired++;
				}
			}
			rows.add(needRow(r, bankOpen, visibleInBank, now));
		}

		List<SiteRow> sites = new ArrayList<>();
		int order = 0;
		List<FarmingPreparation.SitePlan> ordered = new ArrayList<>(plan.getSites());
		for (FarmingPreparation.SitePlan s : ordered)
		{
			sites.add(siteRow(s, ++order, state));
		}
		return assemble(state, rows, sites, plan.getNotes(), toCollect, unknownRequired, inBankRequired, bankSeen, bankOpen,
			slotText(FarmingPreparation.slotsNeeded(state)));
	}

	/**
	 * Another run's preparation on the same page (bird houses, pass 30): its supplies as Farming's rows and its stops as
	 * destination rows, with the same summary, readiness and Begin wording.
	 */
	public static FarmingPreparationView of(PreparationState state, List<SiteRow> sites, List<String> notes, boolean bankOpen, Set<Integer> visibleInBank,
		long now)
	{
		List<NeedRow> rows = new ArrayList<>();
		int toCollect = 0;
		int unknownRequired = 0;
		int inBankRequired = 0;
		boolean bankSeen = false;
		for (NeedResult r : state.getResults())
		{
			boolean required = r.getNeed().getNecessity() == ItemNeed.Necessity.REQUIRED;
			bankSeen |= r.getBankSeenAt() > 0;
			if (required && !r.isSatisfied())
			{
				toCollect++;
				unknownRequired += r.getStatus() == NeedStatus.UNKNOWN ? 1 : 0;
				inBankRequired += r.getStatus() == NeedStatus.IN_BANK ? 1 : 0;
			}
			rows.add(needRow(r, bankOpen, visibleInBank, now));
		}
		return assemble(state, rows, sites, notes, toCollect, unknownRequired, inBankRequired, bankSeen, bankOpen,
			slotText(FarmingPreparation.slotsNeeded(state)));
	}

	private static FarmingPreparationView assemble(PreparationState state, List<NeedRow> rows, List<SiteRow> sites, List<String> notes, int toCollect,
		int unknownRequired, int inBankRequired, boolean bankSeen, boolean bankOpen, String slots)
	{
		List<String> issues = new ArrayList<>();
		for (SiteRow row : sites)
		{
			if (row.isUnverified())
			{
				issues.add(row.getSite() + " (" + shortIssue(row) + ")");
			}
		}

		Readiness readiness = state.readiness(issues.size());
		String headline;
		List<String> sub = new ArrayList<>();
		switch (readiness)
		{
			case CONFIRMED:
				headline = "✓ Ready to begin";
				sub.add("Everything required is carried and every destination is confirmed.");
				break;
			case UNVERIFIED:
				headline = "Supplies ready · travel unverified";
				sub.add("Spyglass can't confirm " + list(issues) + ".");
				break;
			default:
				headline = toCollect + (toCollect == 1 ? " supply type" : " supply types") + " to collect";
				if (bankOpen)
				{
					sub.add(inBankRequired > 0 ? "Outlined in your bank with how many to take." : "None of them were found in this bank.");
				}
				else if (!bankSeen && unknownRequired > 0)
				{
					sub.add("Spyglass hasn't seen your bank yet: open any bank so it can check.");
				}
				else if (inBankRequired > 0)
				{
					sub.add("Last seen in your bank: open any bank to see them outlined.");
				}
				else
				{
					sub.add("Each row says where it was last seen.");
				}
				if (!issues.isEmpty())
				{
					sub.add("Travel unverified for " + issues.size() + (issues.size() == 1 ? " destination." : " destinations."));
				}
				break;
		}
		String begin;
		if (readiness == Readiness.CONFIRMED)
		{
			begin = "Begin run";
		}
		else if (readiness == Readiness.UNVERIFIED)
		{
			begin = "Begin with travel unverified";
		}
		else
		{
			begin = issues.isEmpty() ? "Begin with items missing" : "Begin anyway";
		}
		return new FarmingPreparationView(readiness, headline, sub, begin, bankOpen, rows, sites, notes, toCollect, issues.size(), slots);
	}

	/**
	 * The inventory estimate. Never claims the run fits: it is an estimate of
	 * supplies only (the harvest needs room too), and above one inventory it
	 * says so plainly instead of planning trips.
	 */
	static String slotText(int slots)
	{
		if (slots < 0)
		{
			return "";
		}
		int max = FarmingPreparation.INVENTORY_SLOTS;
		if (slots <= max)
		{
			return "Supplies take about " + slots + " of " + max + " inventory slots.";
		}
		return "Supplies take about " + slots + " slots, more than one inventory.";
	}

	// -- needs ---------------------------------------------------------------------------------

	private static NeedRow needRow(NeedResult r, boolean bankOpen, Set<Integer> visibleInBank, long now)
	{
		ItemNeed n = r.getNeed();
		boolean required = n.getNecessity() == ItemNeed.Necessity.REQUIRED;
		String have = r.getCarried() > 0 && !r.isSatisfied() && r.getCarried() < Long.MAX_VALUE / 8 ? "Have " + r.getCarried() : "";
		Tone tone;
		String status;
		String support;
		switch (r.getStatus())
		{
			case CARRIED:
				if (r.isCarriedNotWorn())
				{
					tone = Tone.ACTION;
					status = "Carried \u00b7 wear it" + (n.getWornSlot() == null ? "" : " (" + n.getWornSlot() + " slot)");
					support = n.getReason();
					break;
				}
				tone = Tone.DONE;
				status = carriedText(r.getVia());
				support = "";
				break;
			case REACHABLE:
				tone = Tone.DONE;
				status = "\u2713 " + reachableText(n);
				support = "Take it from any tool leprechaun when you need it. Spyglass read this from the game; it updates when you store or take tools.";
				break;
			case IN_BANK:
				tone = required ? Tone.ACTION : Tone.INFO;
				status = bankOpen ? "Withdraw " + r.getWithdrawItems() : "Not carried · in your bank";
				support = join(have, n.getReachableLabel() != null && n.getReachableUnits() > 0 ? n.getReachableUnits() + " stored at the tool leprechaun" : "",
					tabText(r.getBankTab()), bankOpen ? "" : seen(now, r.getBankSeenAt()));
				if (bankOpen && n.getReachableLabel() != null && n.getReachableUnits() > 0)
				{
					status += " \u00b7 " + n.getReachableUnits() + " stored";
				}
				break;
			case ELSEWHERE:
				tone = Tone.INFO;
				status = "Not carried · in " + String.join(" and ", r.getPlaces());
				support = join(have, seen(now, r.getElsewhereSeenAt()));
				break;
			case NOT_FOUND:
				tone = required ? Tone.WARN : Tone.INFO;
				status = "Not found where Spyglass looked";
				support = join(r.getFound() > 0 ? "Only " + r.getFound() + " found" : "", "bank " + seen(now, r.getBankSeenAt()));
				break;
			default:
				tone = required ? Tone.UNVERIFIED : Tone.INFO;
				String d = r.getDetail();
				status = d.contains("inventory not read") ? "Inventory not read yet" : d.contains("rune pouch") ? "Rune pouch contents not read"
					: "Bank not seen yet";
				support = have;
				break;
		}
		return new NeedRow(n.getKey(), n.getSection(), n.getLabel(), n.quantityText(), r.getDisplayItemId(), required, r.isSatisfied(), tone, status,
			capitalise(support), locate(r, bankOpen, visibleInBank, now), n.getAcceptableItemIds());
	}

	/** "Stored at the tool leprechaun · 8" (units known) or "Stored at the tool leprechaun". */
	static String reachableText(ItemNeed n)
	{
		String label = n.getReachableLabel() == null ? "tool leprechaun" : n.getReachableLabel();
		String at = label.startsWith("tool leprechaun") ? "Stored at the " + label : "Stored: " + label;
		long units = n.getReachableUnits();
		return n.getQuantity() > 1 && units > 0 ? at + " \u00b7 " + units : at;
	}

	private static String carriedText(NeedResult.Via via)
	{
		if (via == null)
		{
			return "✓ Carried";
		}
		switch (via)
		{
			case WORN:
				return "✓ Worn";
			case RUNE_POUCH:
				return "✓ In your rune pouch";
			case STAFF:
				return "✓ From your staff";
			default:
				return "✓ Carried";
		}
	}

	/**
	 * Find's answer. Passive: it never types into the bank search or switches
	 * tabs. It says where Spyglass last saw the item and whether the open bank
	 * view shows it (outlined there), or plainly that it can't locate it.
	 */
	static String locate(NeedResult r, boolean bankOpen, Set<Integer> visibleInBank, long now)
	{
		String name = r.getNeed().getSearchText();
		switch (r.getStatus())
		{
			case CARRIED:
				return r.isCarriedNotWorn() ? name + " is in your inventory: equip it so it takes effect." : name + " is carried.";
			case REACHABLE:
				return name + " is stored at the tool leprechaun.";
			case IN_BANK:
			{
				String tab = r.getBankTab() > 0 ? "tab " + r.getBankTab() : r.getBankTab() == 0 ? "the main tab" : null;
				if (bankOpen && visibleInBank != null)
				{
					for (int id : r.getNeed().getAcceptableItemIds())
					{
						if (visibleInBank.contains(id))
						{
							return "Outlined in your bank now" + (tab == null ? "." : " (" + tab + ").") + " Scroll the bank if you can't see it.";
						}
					}
					return "Not in the bank view you have open." + (tab == null ? " Spyglass can't tell which tab it is in." : " Last seen in " + tab
						+ "; open that tab yourself.");
				}
				if (bankOpen)
				{
					return "In this bank" + (tab == null ? "" : ", " + tab) + ". It is outlined wherever the bank shows it.";
				}
				return "Last seen in your bank" + (tab == null ? "" : ", " + tab) + ", " + seen(now, r.getBankSeenAt())
					+ ". Open any bank and it will be outlined.";
			}
			case ELSEWHERE:
				return "Last seen in your " + String.join(" and ", r.getPlaces()) + ", " + seen(now, r.getElsewhereSeenAt())
					+ ". Spyglass can't outline it there; take it out yourself.";
			case NOT_FOUND:
				return "Spyglass can't locate it: not in your bank (" + seen(now, r.getBankSeenAt())
					+ ") or other storage it has seen. It may be somewhere Spyglass doesn't look, such as your house or a tool leprechaun.";
			default:
				return r.getDetail().contains("bank") ? "Spyglass can't locate it: it hasn't seen your bank yet. Open any bank so it can look."
					: "Spyglass can't locate it yet: it hasn't read what you carry.";
		}
	}

	private static String tabText(int tab)
	{
		return tab > 0 ? "tab " + tab : tab == 0 ? "main tab" : "";
	}

	static String seen(long now, long at)
	{
		if (at <= 0)
		{
			return "";
		}
		long m = Math.max(0, now - at) / 60_000L;
		if (m < 1)
		{
			return "seen just now";
		}
		return "seen " + com.osrstelemetry.plugin.account.ItemLocator.age(now - at) + " ago";
	}

	// -- destinations ---------------------------------------------------------------------------

	private static SiteRow siteRow(FarmingPreparation.SitePlan s, int order, PreparationState state)
	{
		return siteRow(s.getSite().getName(), order, s.getTransport(), s.getNeedKeys(), s.isChargeUncertain(), s.isNetworkHop(), s.getAccessUnmet(),
			s.getAccessMissing(), s.getLegs(), s.getWalkTiles(), s.getAlternatives(), s.getRejected(), s.getClosedWays(), state, s.isCurrentSetup(),
			s.getSetupLeftOut());
	}

	/**
	 * One destination row from its parts (Farming's sites; bird house stops, pass 30): the transport taken and whether it can
	 * be confirmed, with its journey, options and why others are not usable behind the row's details.
	 */
	public static SiteRow siteRow(String name, int order, TransportAvailability t, List<String> needKeys, boolean chargeUncertain, boolean networkHop,
		List<String> accessUnmet, List<String> accessMissing, List<FarmingTravel.Leg> legs, int walkTiles, List<TransportAvailability> alternatives,
		List<TransportAvailability> rejectedList, List<String> closedWays, PreparationState state)
	{
		return siteRow(name, order, t, needKeys, chargeUncertain, networkHop, accessUnmet, accessMissing, legs, walkTiles, alternatives, rejectedList,
			closedWays, state, false, java.util.Collections.emptyList());
	}

	/**
	 * As above; {@code currentSetup}: planned with Use current setup (pass 32), whose stop without a journey says so instead
	 * of asking for a teleport; {@code setupLeftOut}: journeys left out because they need another spellbook, named once.
	 */
	public static SiteRow siteRow(String name, int order, TransportAvailability t, List<String> needKeys, boolean chargeUncertain, boolean networkHop,
		List<String> accessUnmet, List<String> accessMissing, List<FarmingTravel.Leg> legs, int walkTiles, List<TransportAvailability> alternatives,
		List<TransportAvailability> rejectedList, List<String> closedWays, PreparationState state, boolean currentSetup, List<String> setupLeftOut)
	{
		List<String> details = new ArrayList<>();
		List<String> status = new ArrayList<>();
		Tone tone;
		boolean unverified = false;
		String transport;
		int itemId = -1;
		if (t == null)
		{
			// A teleport works but the guided way on is closed for this account (Anglers' Retreat without the rowboat, say):
			// bringing another teleport would not help, and the patch is not locked; the gap is in guidance.
			boolean gap = rejectedList.isEmpty() || !closedWays.isEmpty();
			boolean setup = currentSetup && !setupLeftOut.isEmpty() && rejectedList.isEmpty() && closedWays.isEmpty();
			transport = setup ? "No journey with your current setup" : gap ? "No guided route" : "No usable transport known";
			tone = Tone.WARN;
			status.add(setup ? "Still in the run: no journey without changing spellbook" : gap ? "Still in the run: no guided route yet"
				: "Still in the run: bring a teleport listed below");
			unverified = true;
		}
		else
		{
			TransportDef def = t.getTransport();
			transport = FarmingPreparation.itemOrName(def);
			itemId = spriteFor(def, needKeys, state);
			boolean needsLeft = false;
			for (String key : needKeys)
			{
				NeedResult r = state.result(key);
				if (r != null && !r.isSatisfied())
				{
					needsLeft = true;
				}
			}
			tone = Tone.DONE;
			if (needsLeft)
			{
				status.add("Bring it first");
				tone = Tone.ACTION;
			}
			if (chargeUncertain)
			{
				status.add("charges unknown");
				tone = needsLeft ? Tone.ACTION : Tone.UNVERIFIED;
				unverified = true;
			}
			if (networkHop)
			{
				details.add("From any " + (def.type == TransportDef.Type.FAIRY_RING ? "fairy ring" : "spirit tree") + ".");
			}
		}
		if (!accessUnmet.isEmpty())
		{
			status.add("patch locked");
			tone = Tone.WARN;
			unverified = true;
			details.add("Patch locked: needs " + String.join("; ", accessUnmet) + ".");
		}
		else if (!accessMissing.isEmpty())
		{
			status.add("access not confirmed");
			if (tone == Tone.DONE)
			{
				tone = Tone.UNVERIFIED;
			}
			unverified = true;
			details.add("Access not confirmed: " + String.join("; ", accessMissing) + ".");
		}
		if (t != null)
		{
			details.add(0, "Taking: " + t.getTransport().name + " (" + stateText(t.getState()) + ").");
			if (chargeUncertain)
			{
				details.add(1, "Its charges or today's uses can't be observed: check them yourself.");
			}
				for (FarmingTravel.Leg leg : legs)
			{
				details.add("Then: " + leg.text + ".");
			}
			if (walkTiles > 0 && walkTiles < 10_000)
			{
				details.add("Arrives about " + walkTiles + " tiles from the patch.");
			}
		}
		int shown = 0;
		java.util.Set<String> named = new java.util.HashSet<>();
		if (t != null)
		{
			named.add(t.getTransport().name);
		}
		List<TransportAvailability> distinct = new ArrayList<>();
		for (TransportAvailability a : alternatives)
		{
			if (named.add(a.getTransport().name))
			{
				distinct.add(a);
			}
		}
		for (TransportAvailability a : distinct)
		{
			if (shown++ == 3)
			{
				details.add("Also possible: " + (distinct.size() - 3) + " more.");
				break;
			}
			details.add("Also possible: " + a.getTransport().name + " (" + stateText(a.getState()) + ").");
		}
		for (TransportAvailability a : rejectedList)
		{
			details.add("Not usable now: " + a.getTransport().name + " (" + rejectedText(a) + ").");
		}
		for (String s : setupLeftOut)
		{
			details.add("Left out (Use current setup): " + s + ".");
		}
		for (String w : closedWays)
		{
			details.add("Quicker if open: " + w + ".");
		}
		if (t == null && (rejectedList.isEmpty() || !closedWays.isEmpty()) && !(currentSetup && !setupLeftOut.isEmpty()))
		{
			details.add("Spyglass has no guided route to this patch yet.");
		}
		String text = status.isEmpty() ? "✓ Ready" : capitalise(String.join(" · ", status));
		if (t != null && status.isEmpty() && networkHop)
		{
			text = "✓ Ready · from any " + (t.getTransport().type == TransportDef.Type.FAIRY_RING ? "fairy ring" : "spirit tree");
		}
		return new SiteRow(name, order, transport, itemId, tone, text, details, unverified);
	}

	private static int spriteFor(TransportDef def, List<String> needKeys, PreparationState state)
	{
		for (String key : needKeys)
		{
			if (key.startsWith("travel.") && !key.equals(FarmingPreparation.STAFF_KEY))
			{
				NeedResult r = state.result(key);
				if (r != null)
				{
					return r.getDisplayItemId();
				}
			}
		}
		for (TransportDef.ItemVariant v : TransportDef.list(def.items))
		{
			if (v.charges != TransportDef.Charges.DEPLETED)
			{
				return v.id;
			}
		}
		if (def.type == TransportDef.Type.FAIRY_RING)
		{
			NeedResult staff = state.result(FarmingPreparation.STAFF_KEY);
			return staff != null ? staff.getDisplayItemId() : net.runelite.api.gameval.ItemID.DRAMEN_STAFF;
		}
		List<TransportDef.RuneCost> runes = TransportDef.list(def.runes);
		return runes.isEmpty() ? -1 : runes.get(0).id;
	}

	private static String shortIssue(SiteRow row)
	{
		if (row.getTransport().equals("No usable transport known"))
		{
			return "no usable transport";
		}
		String s = row.getStatus().toLowerCase(java.util.Locale.ROOT).replace("bring it first · ", "");
		return s;
	}

	static String stateText(Availability a)
	{
		switch (a)
		{
			case READY:
				return "carried";
			case CHARGES_UNKNOWN:
				return "carried, charges unknown";
			case IN_BANK:
				return "in your bank";
			case ELSEWHERE:
				return "in other storage";
			case DEPLETED:
				return "only depleted ones found";
			case REQUIREMENTS_UNMET:
				return "locked";
			default:
				return "unknown";
		}
	}

	private static String rejectedText(TransportAvailability a)
	{
		switch (a.getState())
		{
			case REQUIREMENTS_UNMET:
				if (!a.getUnmet().isEmpty() && a.getUnmet().get(0).contains(" spellbook (on "))
				{
					// Pass 32: a setup change, said as one (Fastest journey may plan around it once the player switches).
					return "needs a spellbook change: " + a.getUnmet().get(0);
				}
				return "needs " + (a.getUnmet().isEmpty() ? "an unlock" : a.getUnmet().get(0));
			case DEPLETED:
				return "only depleted ones found";
			default:
				return a.getMissing().isEmpty() ? "not confirmed" : "not confirmed: " + a.getMissing().get(0);
		}
	}

	// -- helpers -------------------------------------------------------------------------------

	private static String list(List<String> items)
	{
		if (items.size() <= 3)
		{
			return String.join(", ", items);
		}
		return String.join(", ", items.subList(0, 3)) + " and " + (items.size() - 3) + " more";
	}

	private static String join(String... parts)
	{
		List<String> out = new ArrayList<>();
		for (String p : parts)
		{
			if (p != null && !p.isEmpty())
			{
				out.add(p);
			}
		}
		return String.join(" · ", out);
	}

	private static String capitalise(String s)
	{
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	// -- accessors -----------------------------------------------------------------------------

	public Readiness getReadiness()
	{
		return readiness;
	}

	/** Only a CONFIRMED plan reads as ready. */
	public boolean isReady()
	{
		return readiness == Readiness.CONFIRMED;
	}

	public String getHeadline()
	{
		return headline;
	}

	/** One to three short lines under the headline. */
	public List<String> getSublines()
	{
		return sublines;
	}

	public String getSubline()
	{
		return String.join(" ", sublines);
	}

	/** "Begin run", "Begin with items missing", "Begin with travel unverified" or "Begin anyway". */
	public String getBeginLabel()
	{
		return beginLabel;
	}

	public boolean isBankOpen()
	{
		return bankOpen;
	}

	public List<NeedRow> getNeeds()
	{
		return needs;
	}

	public List<SiteRow> getSites()
	{
		return sites;
	}

	public List<String> getNotes()
	{
		return notes;
	}

	/** Required supply types not yet carried. */
	public int getToCollect()
	{
		return toCollect;
	}

	public int getUnverifiedSites()
	{
		return unverifiedSites;
	}

	/** The inventory-slot estimate line ("" when unknown). */
	public String getSlots()
	{
		return slots;
	}

	/** Rows in one group, in plan order. */
	public List<NeedRow> rows(Group group)
	{
		List<NeedRow> out = new ArrayList<>();
		for (NeedRow r : needs)
		{
			if (r.getGroup() == group)
			{
				out.add(r);
			}
		}
		return out;
	}

	/** Rebuild key: the page rebuilds only when the set of rows changes; statuses, readiness and the button update in place. */
	public String structureKey()
	{
		StringBuilder k = new StringBuilder("PREP");
		for (NeedRow r : needs)
		{
			k.append('|').append(r.key); // group changes move rows in place (no rebuild)
		}
		for (SiteRow s : sites)
		{
			k.append('|').append(s.site);
		}
		k.append('|').append(notes.size());
		return k.toString();
	}
}
