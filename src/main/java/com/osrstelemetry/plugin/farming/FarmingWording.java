package com.osrstelemetry.plugin.farming;

import com.osrstelemetry.plugin.navigation.Transition;
import com.osrstelemetry.plugin.travel.TransportDef;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Wording for the run's current action, written for each surface (canonical §28.13). One rule per action family, for
 * every patch type and every travel step: teleports, house facilities, fairy rings and spirit trees, transitions, walking
 * legs and the work at the patch. A short label is written for under the player, never cut from the guide line.
 */
public final class FarmingWording
{
	private FarmingWording()
	{
	}

	// -- travel ------------------------------------------------------------------------------------------

	/**
	 * Using a transport to start the journey. A house route needs the house first: until the player is inside it, the step
	 * is the house teleport; inside, the facility. A complete journey through a fairy ring or spirit tree starts with its entry
	 * teleport ({@code entry}, from the catalogue; null when not found).
	 */
	public static FarmingAction teleport(TransportDef t, TransportDef entry, boolean inHouse)
	{
		if (t == null)
		{
			return FarmingAction.NONE;
		}
		TransportDef first = t.via != null && entry != null ? entry : t;
		if (t.via != null && entry == null)
		{
			// A complete journey carries its entry teleport's kind; its name is "<entry>, then fairy ring …".
			first = new TransportDef();
			first.id = t.via.entry;
			first.type = t.type;
			first.name = t.name.contains(", then ") ? t.name.substring(0, t.name.indexOf(", then ")) : t.name;
		}
		String id = first.id;
		if (id.startsWith("poh."))
		{
			return inHouse ? houseFacility(first) : houseTeleport(first);
		}
		String tip = destinationTip(t);
		switch (first.type)
		{
			case SPELL:
			{
				String spell = first.name.replaceFirst("\\s*\\(.*\\)$", "");
				return new FarmingAction(FarmingAction.Stage.TELEPORT, spell, "Cast " + spell, tip, FarmingAction.Target.NONE, -1);
			}
			case TABLET:
			{
				String where = first.name.replaceFirst("(?i)\\s*teleport tablet$", "");
				return new FarmingAction(FarmingAction.Stage.TELEPORT, where + " tablet", "Break a " + where + " teleport tablet", tip,
					FarmingAction.Target.NONE, -1);
			}
			case FAIRY_RING:
				return hop(true, code(first), place(first), -1, -1, -1);
			case SPIRIT_TREE:
				return hop(false, place(first), place(first), -1, -1, -1);
			case ITEM_TELEPORT:
			default:
			{
				String item = first.name.contains(":") ? first.name.substring(0, first.name.indexOf(':')).trim() : first.name;
				String where = first.name.contains(":") ? first.name.substring(first.name.indexOf(':') + 1).trim() : null;
				if (where == null)
				{
					return new FarmingAction(FarmingAction.Stage.TELEPORT, item, "Use the " + lower(item), tip, FarmingAction.Target.NONE, -1);
				}
				String guide = "Use " + article(item) + " to teleport to " + where;
				return new FarmingAction(FarmingAction.Stage.TELEPORT, item + " → " + where, guide, tip, FarmingAction.Target.NONE, -1);
			}
		}
	}

	private static FarmingAction houseTeleport(TransportDef t)
	{
		boolean spell = t.id.startsWith("poh.spell.");
		return new FarmingAction(FarmingAction.Stage.TELEPORT, "Teleport to house",
			spell ? "Cast Teleport to House" : "Teleport to your house (house tablet or construction cape)",
			"Then: " + houseFacility(t).getGuide(), FarmingAction.Target.NONE, -1);
	}

	/** Inside the house: the facility that sends the player on, highlighted in the house. */
	static FarmingAction houseFacility(TransportDef t)
	{
		String rest = t.id.replaceFirst("^poh\\.(tab|spell)\\.", "");
		String where = t.name.contains(":") ? t.name.substring(t.name.indexOf(':') + 1).replaceFirst("\\s*\\(.*\\)$", "").trim() : rest;
		if (rest.startsWith("fairy_ring."))
		{
			String code = rest.substring("fairy_ring.".length()).toUpperCase(Locale.ROOT);
			return new FarmingAction(FarmingAction.Stage.HOP, "POH fairy ring → " + code, "Use your house fairy ring to reach " + code + " (" + where + ")",
				null, FarmingAction.Target.object(Collections.emptySet(), "Fairy ring|Spiritual fairy tree", null, -1, -1, -1, 0), -1);
		}
		if (rest.startsWith("spirit_tree."))
		{
			return new FarmingAction(FarmingAction.Stage.HOP, "POH spirit tree → " + where, "Use your house spirit tree to reach " + where, null,
				FarmingAction.Target.object(Collections.emptySet(), "Spirit tree|Spiritual fairy tree", null, -1, -1, -1, 0), -1);
		}
		if (rest.startsWith("portal."))
		{
			String d = rest.substring("portal.".length());
			return new FarmingAction(FarmingAction.Stage.TELEPORT, "POH portal → " + where, "Use the " + where + " portal in your house", null,
				FarmingAction.Target.object(com.osrstelemetry.plugin.account.poh.PohKnowledge.objectIds("poh.portal." + d), where + " Portal", null,
					-1, -1, -1, 0), -1);
		}
		if (rest.startsWith("portal_alt."))
		{
			// The diary form of a portal whose right-click goes here: the instruction names the portal as it stands.
			String d = rest.substring("portal_alt.".length());
			String portal = t.notes != null && !t.notes.isEmpty() && t.notes.get(0).contains("right-click the ")
				? t.notes.get(0).replaceFirst("^.*right-click the (.+?) portal.*$", "$1") : where;
			return new FarmingAction(FarmingAction.Stage.TELEPORT, "POH portal → " + where + " (right-click)",
				"Right-click the " + portal + " portal in your house: " + where, null,
				FarmingAction.Target.object(com.osrstelemetry.plugin.account.poh.PohKnowledge.objectIds("poh.portal_alt." + d), null, null, -1, -1, -1, 0), -1);
		}
		if (rest.startsWith("nexus."))
		{
			return new FarmingAction(FarmingAction.Stage.TELEPORT, "Nexus → " + where, "Use your portal nexus: " + where, null,
				FarmingAction.Target.object(com.osrstelemetry.plugin.account.poh.PohKnowledge.objectIds("poh.nexus"), "Portal nexus", null, -1, -1, -1, 0),
				-1);
		}
		if (rest.startsWith("jewellery."))
		{
			return new FarmingAction(FarmingAction.Stage.TELEPORT, "Jewellery box → " + where, "Use your house jewellery box to reach " + where, null,
				FarmingAction.Target.object(Collections.emptySet(), "Jewellery box", null, -1, -1, -1, 0), -1);
		}
		if (rest.startsWith("xerics."))
		{
			return new FarmingAction(FarmingAction.Stage.TELEPORT, "POH Xeric's talisman → " + where, "Use your mounted Xeric's talisman to reach " + where,
				null, FarmingAction.Target.object(Collections.emptySet(), "Xeric's talisman", null, -1, -1, -1, 0), -1);
		}
		if (rest.startsWith("digsite."))
		{
			return new FarmingAction(FarmingAction.Stage.TELEPORT, "POH digsite pendant → " + where, "Use your mounted digsite pendant to reach " + where,
				null, FarmingAction.Target.object(Collections.emptySet(), "Digsite pendant", null, -1, -1, -1, 0), -1);
		}
		return new FarmingAction(FarmingAction.Stage.TELEPORT, "House → " + where, "Use your house to reach " + where, null, FarmingAction.Target.NONE, -1);
	}

	private static volatile java.util.Map<String, String> ringPlaces;

	/** A fairy ring code's destination as the catalogue names it ("Hosidius Vinery"), or null. Loaded once (one small file). */
	static String ringPlace(String code)
	{
		java.util.Map<String, String> m = ringPlaces;
		if (m == null)
		{
			m = new java.util.HashMap<>();
			try (java.io.InputStream in = FarmingWording.class.getResourceAsStream("/com/osrstelemetry/plugin/travel/transports/fairy-rings.json"))
			{
				if (in != null)
				{
					com.google.gson.JsonObject doc = new com.google.gson.JsonParser().parse(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
						.getAsJsonObject();
					for (com.google.gson.JsonElement e : doc.getAsJsonArray("transports"))
					{
						String name = e.getAsJsonObject().get("name").getAsString();
						java.util.regex.Matcher mm = java.util.regex.Pattern.compile("^Fairy ring ([A-Z]{3}): (.+)$").matcher(name);
						if (mm.find())
						{
							m.put(mm.group(1), mm.group(2));
						}
					}
				}
			}
			catch (java.io.IOException | RuntimeException e)
			{
				// no names: the code alone is still usable
			}
			ringPlaces = m;
		}
		return code == null ? null : m.get(code.toUpperCase(Locale.ROOT));
	}

	/** Using a fairy ring ({@code ring}) or a spirit tree, standing at it. */
	public static FarmingAction hop(boolean ring, String codeOrPlace, String place, int x, int y, int plane)
	{
		if (ring && place == null)
		{
			place = ringPlace(codeOrPlace);
		}
		if (ring)
		{
			return new FarmingAction(FarmingAction.Stage.HOP, "Fairy ring → " + codeOrPlace,
				"Use fairy ring " + codeOrPlace + (place == null || place.equals(codeOrPlace) ? "" : " (" + place + ")"), null,
				FarmingAction.Target.object(Collections.emptySet(), "Fairy ring", null, x, y, plane, 4), -1);
		}
		return new FarmingAction(FarmingAction.Stage.HOP, "Spirit tree → " + codeOrPlace, "Travel by spirit tree to " + codeOrPlace, null,
			FarmingAction.Target.object(Collections.emptySet(), "Spirit tree", null, x, y, plane, 4), -1);
	}

	/** The walk to a fairy ring or spirit tree entrance. */
	public static FarmingAction walkToEntrance(boolean ring, int tiles, int x, int y, int plane)
	{
		String what = ring ? "fairy ring" : "spirit tree";
		return walk(what, FarmingAction.Target.object(Collections.emptySet(), ring ? "Fairy ring" : "Spirit tree", null, x, y, plane, 4), tiles);
	}

	/** A door, ladder, boat or other step on the way: walk to it while it is more than a step away, then take it. */
	public static FarmingAction transition(Transition t, int tilesToIt)
	{
		FarmingAction.Target target = t.objectId > 0
			? FarmingAction.Target.object(Collections.singleton(t.objectId), null, null, com.osrstelemetry.plugin.navigation.NavPoint.x(t.from),
			com.osrstelemetry.plugin.navigation.NavPoint.y(t.from), com.osrstelemetry.plugin.navigation.NavPoint.plane(t.from), 6)
			: t.target == null || t.target.trim().isEmpty() ? FarmingAction.Target.NONE
			: FarmingAction.Target.npc(t.target.trim(), t.option, com.osrstelemetry.plugin.navigation.NavPoint.x(t.from),
			com.osrstelemetry.plugin.navigation.NavPoint.y(t.from), com.osrstelemetry.plugin.navigation.NavPoint.plane(t.from), 8);
		if (tilesToIt > 2)
		{
			return walk(t.noun(), target, tilesToIt);
		}
		String needs = com.osrstelemetry.plugin.navigation.VarNames.describe(t.requirement);
		return new FarmingAction(FarmingAction.Stage.TRANSITION, t.label(), t.instruction(), needs.isEmpty() ? null : "Needs " + needs, target, -1);
	}

	/** "Walk to patch · 42 tiles": the countdown is the traversable tiles still to go (-1: not known yet). */
	public static FarmingAction walk(String what, FarmingAction.Target target, int tiles)
	{
		String base = "Walk to " + what;
		return new FarmingAction(FarmingAction.Stage.WALK, base, base, null, target, -2).withTiles(tiles);
	}

	public static FarmingAction walkToPatch(Set<Integer> varbits, int tiles)
	{
		return walk("patch", FarmingAction.Target.patches(varbits), tiles);
	}

	/** The walk to the stop's patches (varbits in their owning regions): the patches are the destination indicator. */
	public static FarmingAction walkToPatch(Set<Integer> varbits, Set<Integer> regions, int tiles)
	{
		return walk("patch", FarmingAction.Target.patches(varbits, regions), tiles);
	}

	// -- at the patch --------------------------------------------------------------------------------------

	/**
	 * The next thing to do at one patch, from its observed state and the player's preferences: harvest, pick fruit, check
	 * health, clear (paying, chopping, digging), cure, plant, pay for protection. {@code protectionPending}: planted this run,
	 * protection chosen, and no payment observed yet. {@code several}: the stop has more than one patch to name.
	 */
	public static FarmingAction patch(FarmingRunEvaluator.PatchProgress p, FarmingRunIntent intent, FarmingPreferences prefs, boolean protectionPending,
		boolean several, String patchLabel, int gardenerX, int gardenerY, int gardenerPlane)
	{
		return patch(p, intent, prefs, protectionPending, several, patchLabel, gardenerX, gardenerY, gardenerPlane, false);
	}

	/**
	 * As above; {@code compostSeen}: the game showed compost on this planting already (then planting is the step; before
	 * that, with compost chosen, the compost is: "Apply supercompost", "Cast Fertile Soil").
	 */
	public static FarmingAction patch(FarmingRunEvaluator.PatchProgress p, FarmingRunIntent intent, FarmingPreferences prefs, boolean protectionPending,
		boolean several, String patchLabel, int gardenerX, int gardenerY, int gardenerPlane, boolean compostSeen)
	{
		FarmingPatchDef def = p.getPatch();
		FarmingPatchType type = def == null ? null : def.getType();
		Set<Integer> me = def == null ? Collections.emptySet() : Collections.singleton(def.getVarbit());
		FarmingAction.Target patch = FarmingAction.Target.patches(me, def == null ? Collections.emptySet() : Collections.singleton(def.getOwningRegionId()));
		FarmingAction.Target gardener = FarmingAction.Target.npc(null, "Pay", gardenerX, gardenerY, gardenerPlane, 14);
		FarmingPreferences pr = prefs == null ? FarmingPreferences.defaults() : prefs;
		String noun = noun(type, p);
		String[] lg;
		boolean spell = false;
		FarmingAction.Target target = patch;
		switch (p.getDetail())
		{
			case TO_CHECK_HEALTH:
				lg = new String[]{"Check health", "Check the " + noun + "'s health"};
				break;
			case TO_HARVEST:
				if (type == FarmingPatchType.HESPORI)
				{
					lg = new String[]{"Hespori grown", "Hespori is fully grown"};
				}
				else if (p.getFruitLeft() > 0)
				{
					lg = new String[]{"Pick fruit", "Pick the fruit (" + p.getFruitLeft() + " left)"};
				}
				else
				{
					lg = new String[]{"Harvest", "Harvest the " + noun};
				}
				break;
			case TO_REPLACE:
				if (type != null && FarmingPreferences.needsClearing(type))
				{
					if (p.getFruitLeft() > 0 && pr.isPickFruitFirst())
					{
						lg = new String[]{"Pick fruit", "Pick the fruit (" + p.getFruitLeft() + " left) before the tree goes"};
						break;
					}
					if (pr.clearingFor(type) == FarmingPreferences.TreeClearing.PAY)
					{
						int coins = FarmingPreferences.removalCoins(type);
						lg = new String[]{"Pay for removal", (type == FarmingPatchType.REDWOOD ? "Pay Alexandra" : "Pay the gardener") + " to remove the "
							+ noun + " (" + String.format(Locale.ROOT, "%,d", coins) + " coins)"};
						target = gardener;
						break;
					}
					lg = manualClear(type, noun);
					break;
				}
				lg = new String[]{"Clear patch", "Clear the patch"};
				break;
			case STUMP_TO_CLEAR:
				lg = new String[]{"Dig up stump", "Dig up the stump with a spade"};
				break;
			case TO_CLEAR:
				lg = new String[]{"Clear dead plant", "Dig up the dead plant with a spade"};
				break;
			case TO_CURE:
				lg = new String[]{"Cure plant", "Cure the diseased " + noun + " with plant cure (or clear it)"};
				break;
			case TO_PLANT:
			case AWAITING_PLANT:
			case HARVESTED:
			case CLEARED:
			{
				boolean sapling = type != null && FarmingSeeds.saplings(type);
				String compost = def == null || intent == null || intent.getOptions() == null ? null
					: intent.getOptions().compostFor(type);
				boolean composting = compost != null && !"NONE".equals(compost);
				String what = sapling ? "Plant sapling" : type == FarmingPatchType.MUSHROOM ? "Plant spores" : "Plant seeds";
				String guide = sapling ? "plant a sapling" : type == FarmingPatchType.MUSHROOM ? "plant the spores"
					: "plant " + (type == null ? "the seeds" : seedsPerPatch(type));
				if (composting && !compostSeen)
				{
					lg = compostStep(compost, guide);
					if ("FERTILE_SOIL".equals(compost))
					{
						spell = true;
					}
					break;
				}
				lg = new String[]{what, Character.toUpperCase(guide.charAt(0)) + guide.substring(1)};
				break;
			}
			case REPLANTED:
			case PLANTED_UNSEEN:
				if (protectionPending)
				{
					FarmingSeeds.Payment pay = FarmingSeeds.paymentFor(p.getObservedCrop());
					lg = new String[]{"Pay for protection", "Pay the gardener to protect this crop" + (pay == null ? ""
						: " (" + pay.getQuantity() + " " + pay.getLabel().toLowerCase(Locale.ROOT) + ")")};
					target = gardener;
					break;
				}
				lg = new String[]{"Done here", "Done here"};
				break;
			case UNREADABLE:
			case NOT_VISITED:
			default:
				lg = new String[]{"Check patch", "Look at the patch"};
				break;
		}
		String guide = several && patchLabel != null ? lg[1] + " (" + patchLabel + ")" : lg[1];
		FarmingAction out = new FarmingAction(FarmingAction.Stage.ACT, lg[0], guide, spell ? "Lunar spellbook · 15 earth, 3 astral, 2 nature runes" : null,
			target, -1);
		return spell ? out.withSpell(FERTILE_SOIL_WIDGET) : out;
	}

	/** The Fertile Soil spell's widget (gameval InterfaceID.MagicSpellbook.FERTILE_SOIL), outlined while the spellbook shows. */
	public static final int FERTILE_SOIL_WIDGET = net.runelite.api.gameval.InterfaceID.MagicSpellbook.FERTILE_SOIL;

	/**
	 * Compost after planting (canonical §28.17): the planting went in before the chosen compost, which the game still accepts
	 * ("before (or immediately after) planting", OSRS Wiki, Farming). The same words as before planting, aimed at the planted patch.
	 */
	public static FarmingAction compostAfterPlanting(FarmingRunEvaluator.PatchProgress p, String method, boolean several, String patchLabel)
	{
		String[] lg = compostStep(method, "");
		String guide = lg[1].replaceFirst(", then $", "") + " (just planted)";
		FarmingPatchDef def = p.getPatch();
		FarmingAction.Target target = def == null ? FarmingAction.Target.NONE
			: FarmingAction.Target.patches(java.util.Collections.singleton(def.getVarbit()), java.util.Collections.singleton(def.getOwningRegionId()));
		FarmingAction out = new FarmingAction(FarmingAction.Stage.ACT, lg[0], several && patchLabel != null ? guide + " (" + patchLabel + ")" : guide,
			"FERTILE_SOIL".equals(method) ? "Lunar spellbook · 15 earth, 3 astral, 2 nature runes" : null, target, -1);
		return "FERTILE_SOIL".equals(method) ? out.withSpell(FERTILE_SOIL_WIDGET) : out;
	}

	/** The compost step before planting, by method: label and guide. */
	static String[] compostStep(String method, String plantGuide)
	{
		switch (method)
		{
			case "FERTILE_SOIL":
				return new String[]{"Cast Fertile Soil", "Cast Fertile Soil on the patch, then " + plantGuide};
			case "BOTTOMLESS":
				return new String[]{"Use bottomless bucket", "Treat the patch from your bottomless bucket, then " + plantGuide};
			case "ULTRACOMPOST":
				return new String[]{"Apply ultracompost", "Treat the patch with ultracompost, then " + plantGuide};
			case "SUPERCOMPOST":
				return new String[]{"Apply supercompost", "Treat the patch with supercompost, then " + plantGuide};
			default:
				return new String[]{"Apply compost", "Treat the patch with compost, then " + plantGuide};
		}
	}

	/** Whether a patch's protection is still to pay: planted this run, protection chosen, protectable crop, not paid. */
	public static boolean protectionPending(FarmingRunEvaluator.PatchProgress p, FarmingRunIntent intent, FarmingPreferences prefs)
	{
		FarmingPatchDef def = p.getPatch();
		if (def == null || prefs == null && (intent == null || intent.getProtectTypes() == null) || intent == null
			|| p.getDetail() != FarmingRunEvaluator.Detail.REPLANTED && p.getDetail() != FarmingRunEvaluator.Detail.PLANTED_UNSEEN)
		{
			return false;
		}
		FarmingRunType type = FarmingRunType.of(def.getType());
		// The run's own choice from Start (§28.16); older runs follow the preferences.
		boolean protect = type != null && (intent.getProtectTypes() != null ? intent.getProtectTypes().contains(type.name()) : prefs.isProtect(type));
		return protect && FarmingPreparation.protectable(def.getType()) && !"allotment.harmony".equals(def.getPatchId())
			&& p.getObservedCrop() != null && FarmingSeeds.paymentFor(p.getObservedCrop()) != null
			&& !intent.getProtectedPatchIds().contains(p.getPatchId()) && !intent.isProtectionSkipped(p.getPatchId());
	}

	private static String[] manualClear(FarmingPatchType type, String noun)
	{
		switch (type)
		{
			case CALQUAT:
			case SPIRIT_TREE:
				return new String[]{"Dig up tree", "Dig up the " + noun + " with a spade"};
			case CELASTRUS:
				return new String[]{"Cut down tree", "Cut the " + noun + " down, then clear it with a spade"};
			case CRYSTAL_TREE:
				return new String[]{"Chop down tree", "Chop the " + noun + " down with an axe"};
			default:
				return new String[]{"Chop down tree", "Chop the " + noun + " down, then dig up the stump"};
		}
	}

	private static String seedsPerPatch(FarmingPatchType type)
	{
		int max = FarmingSeeds.maxPerPatch(type);
		int min = FarmingSeeds.minPerPatch(type);
		return max <= 1 ? "a seed" : (min == max ? String.valueOf(max) : min + "–" + max) + " seeds";
	}

	/** "tree", "fruit tree", "herbs", "allotment": what the player works on. */
	static String noun(FarmingPatchType type, FarmingRunEvaluator.PatchProgress p)
	{
		if (type == null)
		{
			return "crop";
		}
		switch (type)
		{
			case HERB:
				return "herbs";
			case FLOWER:
				return "flowers";
			case MUSHROOM:
				return "mushrooms";
			case HARDWOOD_TREE:
				return p != null && p.getObservedCrop() != null ? lower(p.getObservedCrop().getDisplayName()) + " tree" : "tree";
			default:
				return lower(type.getDisplayName());
		}
	}

	// -- helpers ---------------------------------------------------------------------------------------------

	private static String destinationTip(TransportDef t)
	{
		if (t.via != null)
		{
			return "Then " + ("FAIRY_RING".equals(t.via.network) ? "fairy ring " + t.via.code : "the spirit tree to " + t.via.code);
		}
		return null;
	}

	/** "ALQ" from "Fairy ring ALQ: Haunted Woods". */
	static String code(TransportDef t)
	{
		String n = t.name.replaceFirst("(?i)^fairy ring\\s+", "");
		return n.contains(":") ? n.substring(0, n.indexOf(':')).trim() : t.id.substring(t.id.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
	}

	static String place(TransportDef t)
	{
		return t.name.contains(":") ? t.name.substring(t.name.indexOf(':') + 1).trim() : t.name;
	}

	private static String article(String item)
	{
		return item.startsWith("Xeric's") ? item : "the " + lower(item);
	}

	private static String lower(String s)
	{
		if (s == null || s.isEmpty())
		{
			return s;
		}
		// Keep proper names ("Ardougne cloak" → "Ardougne cloak"): lower only the first letter of a common noun.
		String first = s.split(" ")[0];
		boolean proper = first.length() > 1 && Character.isUpperCase(first.charAt(0)) && PROPER.contains(first.replace("'s", ""));
		return proper ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
	}

	/** Place names that keep their capital inside a sentence ("the Ardougne cloak"). */
	private static final Set<String> PROPER = new java.util.HashSet<>(java.util.Arrays.asList("Ardougne", "Xeric", "Falador", "Kandarin", "Morytania",
		"Karamja", "Varrock", "Lumbridge", "Desert", "Western", "Fremennik", "Kourend", "Camelot", "Drakan's", "Arceuus", "Lunar"));
}
