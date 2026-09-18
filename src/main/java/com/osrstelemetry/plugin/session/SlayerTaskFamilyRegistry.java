package com.osrstelemetry.plugin.session;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small, explicit,
 * dependency-free DATA answering exactly one question: "is `npcName` a
 * legitimate member of the NPC family an assigned Slayer task
 * `taskName` covers?" -- e.g. belongsToTaskFamily("Pyrefiends", "Flaming
 * pyrelord") == true, even though the actual NPC display name ("Flaming
 * pyrelord") differs entirely from the task's own display name
 * ("Pyrefiends"). This is the piece ActivitySignalClassifier needs to
 * tell a genuine on-task alternative/superior/boss-form NPC apart from
 * a genuinely unrelated one, without fuzzy string similarity and
 * without assuming pluralization alone is ever sufficient.
 *
 * DATA SOURCE (per explicit instruction to inspect the locally resolved
 * RuneLite dependency/source before choosing an implementation): the
 * task/target-name table below is adapted from RuneLite's own
 * maintained, currently-shipping Slayer task data,
 * `net.runelite.client.plugins.slayer.Task` (fetched fresh from
 * RuneLite's public source, e.g. `PYREFIENDS("Pyrefiends",
 * ..., "Flaming pyrelord")`, `GARGOYLES("Gargoyles", ..., "Dusk",
 * "Dawn")`, `BLUE_DRAGONS("Blue dragons", ..., "Vorkath")`). That enum
 * is package-private inside RuneLite's own `slayer` plugin package and
 * is DELIBERATELY NOT depended on here -- no reflection, no runtime
 * dependency on another plugin's internals (per explicit instruction).
 * Only the plain task-name/target-name STRING DATA is copied out and
 * adapted into this project's own small, independent table; see
 * THIRD_PARTY_NOTICES.md for the required BSD 2-Clause attribution.
 * `net.runelite.client.plugins.slayer.SlayerPluginService.getTargets()`
 * (RuneLite's own public API for this) was deliberately NOT used
 * instead: this project's correctness must not depend on the built-in
 * Slayer plugin actually being enabled/initialized at the moment our
 * plugin needs an answer, and no evidence was found that that service
 * remains reliable when the core Slayer plugin is disabled.
 *
 * SUPERIOR-COVERAGE SOURCE (Slayer superior completeness pass, added
 * after the Aquanites/Elder Aquanite gap was found live): RuneLite's
 * own `Task` enum was re-inspected first, per instruction, and does
 * NOT list several currently-live Superior slayer monsters as
 * target-name aliases at all (verified by direct source read of every
 * task named below, e.g. `AQUANITES("Aquanites", ...)` carries no
 * alias, `BASILISKS("Basilisks", ...)` carries no alias) -- it is a
 * genuine, confirmed gap in RuneLite's own data, not merely this
 * project's copy of it, so it could not serve as the source for this
 * part of the table. The additional superior aliases added below
 * (Elder aquanite, Monstrous basilisk, Basilisk Knight/Basilisk
 * sentinel, Insatiable Bloodveld, Mutated bloodveld/Insatiable mutated
 * bloodveld, Dire gryphon, Vitreous/Warped/Chilled jelly variants,
 * Spiked turoth, Abhorrent/Deviant/Repugnant spectre variants, Shadow
 * wyrm, Lava/Magma strykewyrm, King kurask, Blood-starved venator,
 * Marble gargoyle, Guardian Drake, Greater abyssal demon, Dreadborn
 * Araxyte, Nuclear smoke devil, Colossal hydra, Pyrelord/Infernal
 * pyrelord, Screaming banshee/Twisted banshee/Screaming twisted
 * banshee, Giant rockslug) are OSRS-Wiki-sourced game-mechanic FACTS
 * (the "Superior slayer monster" article's normal-monster-to-superior
 * pairing table, cross-checked one-by-one against each paired normal
 * monster's own infobox Slayer-task category to confirm it is a
 * genuine member of the task family it was added to -- e.g. Basilisk
 * Knight's own infobox category is "Basilisks", confirming it belongs
 * on the existing Basilisks family rather than needing an invented new
 * task). These are plain monster-name/category facts, not RuneLite
 * source code, so THIRD_PARTY_NOTICES.md's RuneLite BSD attribution is
 * unaffected; nothing else about the table's structure, algorithm, or
 * pre-existing RuneLite-derived entries has changed. Not
 * every already-catalogued task was expanded to also list every
 * non-superior stronger variant that can itself spawn a superior
 * (e.g. Deviant spectre, Mutated bloodveld, Twisted banshee, Pyrelord,
 * Lava strykewyrm) -- those ARE included below precisely because each
 * one is the confirmed spawning point of one of the superiors just
 * added, so leaving it out would silently orphan that superior's own
 * family membership.
 *
 * ALGORITHM (mirrors RuneLite's own `SlayerPlugin.rebuildTargetNames()`
 * exactly, verified by direct source read): for a CATALOGUED task, the
 * family is the union of (a) every explicit target-name alias RuneLite
 * itself lists for that task, and (b) the task's own display name with
 * a single trailing "s" stripped (RuneLite's own
 * `taskName.replaceAll("s$", "")` -- e.g. "Pyrefiends" -&gt;
 * "Pyrefiend", "Gargoyles" -&gt; "Gargoyle"). Both are ALWAYS combined
 * together for every cataloged task, exactly as RuneLite always
 * combines them -- this is why a lone trailing-s strip is never treated
 * as sufficient by itself (see class-level "do not assume pluralization
 * alone is sufficient" instruction): it is only ever one half of a
 * pairing with an explicit, real alias list, never the sole signal for
 * a task this table actually knows about. For an UNCATALOGUED task (not
 * in the table below -- see "known coverage gaps" note), there is no
 * alias list to pair it with, so the SAME single trailing-s strip (the
 * exact transform RuneLite itself unconditionally applies to every
 * task, catalogued or not) is used alone, as a narrow, fully
 * deterministic fallback -- never a fuzzy/approximate match, never
 * hand-waving an arbitrary NPC into a task because the names merely
 * "look similar."
 *
 * NEVER FUZZY: every comparison here is an exact, normalized
 * (trim + lower-case) string-set membership test. There is no edit
 * distance, no substring/prefix matching, and no scoring -- an NPC
 * either is or is not one of the explicitly known members (plus the one
 * fixed singular-suffix transform) of a task's family.
 *
 * SCOPE: this class answers ONLY the task-family membership question.
 * It has no opinion on session lifecycle, EvidenceStrength, or boss
 * precedence -- see ActivitySignalClassifier.decideCombatBranch() for
 * how the answer is actually used (an on-task NPC continues an ACTIVE
 * SLAYER session exactly as before; a confirmed off-task NPC
 * is now honestly proposed as a real candidate identity, subject to the
 * SAME ORDINARY-evidence "weak evidence" gate SessionLifecycleEngine
 * already applies elsewhere). This class is never consulted for a
 * BOSSING current identity -- Grotesque Guardians/Dawn/Dusk's own boss
 * precedence is entirely governed by BossTaskAffinity, a separate,
 * pre-existing mechanism this class does not touch or duplicate.
 */
final class SlayerTaskFamilyRegistry
{
	private SlayerTaskFamilyRegistry()
	{
	}

	private static final Map<String, Set<String>> FAMILY_MEMBERS_NORMALIZED;

	static
	{
		Map<String, Set<String>> table = new HashMap<>();

		// Adapted from net.runelite.client.plugins.slayer.Task (BSD
		// 2-Clause License -- see THIRD_PARTY_NOTICES.md). Task name and
		// target-name-alias STRING DATA only; no RuneLite code, item
		// sprite ids, or weakness data is reproduced here -- this project
		// has no use for any of that.
		register(table, "Aberrant spectres", "Spectre", "Abhorrent spectre", "Deviant spectre", "Repugnant spectre");
		register(table, "Abyssal demons", "Abyssal Sire", "Greater abyssal demon");
		register(table, "The Abyssal Sire");
		register(table, "The Alchemical Hydra");
		register(table, "Ankou");
		register(table, "Aquanites", "Elder aquanite");
		register(table, "Araxxor");
		register(table, "Araxytes", "Araxxor", "Dreadborn Araxyte");
		register(table, "Aviansies", "Kree'arra", "Flight Kilisa", "Flockleader Geerin", "Wingman Skree");
		register(table, "Bandits", "Bandit", "Black Heather", "Donny the Lad", "Speedy Keith");
		register(table, "Banshees", "Screaming banshee", "Twisted banshee", "Screaming twisted banshee");
		register(table, "Barrows Brothers");
		register(table, "Basilisks", "Monstrous basilisk", "Basilisk Knight", "Basilisk sentinel");
		register(table, "Bats", "Death wing");
		register(table, "Bears", "Callisto", "Artio");
		register(table, "Birds", "Chicken", "Rooster", "Terrorbird", "Seagull", "Vulture", "Duck", "Penguin", "Baby Roc");
		register(table, "Black demons", "Demonic gorilla", "Balfrug Kreeyath", "Skotizo", "Porazdir");
		register(table, "Black dragons");
		register(table, "Black Knights", "Black Knight");
		register(table, "Bloodveld", "Insatiable Bloodveld", "Mutated bloodveld", "Insatiable mutated bloodveld");
		register(table, "Blue dragons", "Vorkath");
		register(table, "Brine rats");
		register(table, "Callisto");
		register(table, "Catablepon");
		register(table, "Cave bugs");
		register(table, "Cave crawlers", "Chasm crawler");
		register(table, "Cave horrors", "Cave abomination");
		register(table, "Cave kraken", "Kraken");
		register(table, "Cave slimes");
		register(table, "Cerberus");
		register(table, "Chaos druids");
		register(table, "The Chaos Elemental");
		register(table, "The Chaos Fanatic");
		register(table, "Cockatrice", "Cockathrice");
		register(table, "Cows", "Buffalo", "Brutus");
		register(table, "Crabs", "Ammonite Crab", "Frost Crab", "King Sand Crab", "Rock Crab", "Giant Rock Crab", "Sand Crab", "Swamp Crab");
		register(table, "Crawling hands", "Crushing hand");
		register(table, "Crazy Archaeologists");
		register(table, "Crocodiles");
		register(table, "Custodian Stalkers", "Ancient Custodian");
		register(table, "Dagannoth");
		register(table, "Dagannoth Kings");
		register(table, "Dark beasts", "Night beast");
		register(table, "Dark warriors", "Dark warrior");
		register(table, "Deranged Archaeologist");
		register(table, "Dogs", "Jackal", "Temple Guardian");
		register(table, "Drakes", "Guardian Drake");
		register(table, "Duke Sucellus");
		register(table, "Dust devils", "Choke devil");
		register(table, "Dwarves", "Dwarf", "Black Guard");
		register(table, "Earth warriors");
		register(table, "Elves", "Elf", "Iorwerth Warrior", "Iorwerth Archer");
		register(table, "Ents");
		register(table, "Fever spiders");
		register(table, "Fire giants", "Branda the Fire Queen");
		register(table, "Fleshcrawlers", "Flesh crawler");
		register(table, "Fossil island wyverns", "Ancient wyvern", "Long-tailed wyvern", "Spitting wyvern", "Taloned wyvern");
		register(table, "Frost dragons");
		register(table, "Gargoyles", "Dusk", "Dawn", "Marble gargoyle");
		register(table, "General Graardor");
		register(table, "Ghosts", "Death wing", "Tortured soul", "Forgotten Soul", "Revenant");
		register(table, "Ghouls");
		register(table, "The Giant Mole");
		register(table, "Goblins", "Sergeant Strongstack", "Sergeant Grimspike", "Sergeant Steelwill");
		register(table, "Greater demons", "K'ril Tsutsaroth", "Tstanon Karlak", "Skotizo", "Tormented Demon");
		register(table, "Green dragons", "Elvarg");
		register(table, "The Grotesque Guardians", "Dusk", "Dawn");
		register(table, "Gryphons", "Dire gryphon");
		register(table, "Harpie bug swarms");
		register(table, "Hellhounds", "Cerberus");
		register(table, "Hill giants", "Cyclops", "Reanimated giant", "Obor");
		register(table, "Hobgoblins");
		register(table, "Hydras", "Colossal hydra");
		register(table, "Icefiends");
		register(table, "Ice giants", "Eldric the Ice King");
		register(table, "Ice warriors", "Icelord");
		register(table, "Infernal mages", "Malevolent mage");
		register(table, "TzTok-Jad");
		register(table, "Jellies", "Jelly", "Vitreous jelly", "Warped jelly", "Vitreous warped jelly", "Chilled jelly", "Vitreous chilled jelly");
		register(table, "Jungle horrors");
		register(table, "Kalphites");
		register(table, "The Kalphite Queen");
		register(table, "Killerwatts");
		register(table, "The King Black Dragon");
		register(table, "The Cave Kraken Boss", "Kraken");
		register(table, "Kree'arra");
		register(table, "K'ril Tsutsaroth");
		register(table, "Kurask", "King kurask");
		register(table, "Lava Dragons", "Lava dragon");
		register(table, "Lesser demons", "Zakl'n Gritch");
		register(table, "Lesser Nagua", "Sulphur Nagua", "Frost Nagua", "Amoxliatl");
		register(table, "Lizardmen", "Lizardman");
		register(table, "Lizards");
		register(table, "The Maggot King");
		register(table, "Magic axes");
		register(table, "Mammoths");
		register(table, "Metal dragons", "Bronze dragon", "Iron Dragon", "Steel dragon", "Mithril dragon", "Adamant dragon", "Rune dragon");
		register(table, "Minotaurs");
		register(table, "Mogres");
		register(table, "Molanisks");
		register(table, "Monkeys", "Tortured gorilla", "Demonic gorilla", "Padulah");
		register(table, "Moss giants", "Bryophyta");
		register(table, "Mutated zygomites", "Zygomite", "Fungi");
		register(table, "Nechryael", "Nechryarch");
		register(table, "Ogres", "Enclave guard", "Mogre", "Ogress", "Skogre", "Zogre");
		register(table, "Otherworldly beings");
		register(table, "The Phantom Muspah");
		register(table, "Pirates", "Pirate");
		register(table, "Pyrefiends", "Flaming pyrelord", "Pyrelord", "Infernal pyrelord");
		register(table, "Rats");
		register(table, "Red dragons");
		register(table, "Revenants");
		register(table, "Rockslugs", "Giant rockslug");
		register(table, "Rogues");
		register(table, "Sarachnis");
		register(table, "Scabarites", "Scarab swarm", "Locust rider", "Scarab mage", "Small Scarab");
		register(table, "Scorpia");
		register(table, "Scorpions", "Scorpia", "Lobstrosity");
		register(table, "Sea snakes");
		register(table, "Shades", "Loar", "Phrin", "Riyl", "Asyn", "Fiyr", "Urium");
		register(table, "Shadow warriors");
		register(table, "The Shellbane Gryphon");
		register(table, "Skeletal wyverns");
		register(table, "Skeletons", "Vet'ion", "Calvar'ion", "Skeletal Mystic");
		register(table, "Smoke devils", "Nuclear smoke devil");
		register(table, "Sourhogs");
		register(table, "Spiders", "Kalrag", "Sarachnis", "Venenatis", "Spindel", "Araxxor", "Araxyte");
		register(table, "Spiritual creatures", "Spiritual ranger", "Spiritual mage", "Spiritual warrior");
		register(table, "Suqahs");
		register(table, "Terror dogs");
		register(table, "The Leviathan");
		register(table, "The Whisperer");
		register(table, "The Thermonuclear Smoke Devil");
		register(table, "Trolls", "Dad", "Arrg", "Stick", "Kraka", "Pee Hat", "Rock", "Twig", "Berry");
		register(table, "Turoth", "Spiked turoth");
		register(table, "Tzhaar", "TzTok-Jad", "TzKal-Zuk");
		register(table, "Vampyres", "Vyrewatch");
		register(table, "Vardorvis");
		register(table, "Venators", "Blood-starved venator");
		register(table, "Venenatis");
		register(table, "Vet'ion");
		register(table, "Vorkath");
		register(table, "Wall beasts");
		register(table, "Warped Creatures", "Warped terrorbird", "Warped tortoise", "Mutated terrorbird", "Mutated tortoise");
		register(table, "Waterfiends");
		register(table, "Werewolves", "Werewolf");
		register(table, "Wolves", "Wolf");
		register(table, "Wyrms", "Wyrmling", "Strykewyrm", "Shadow wyrm", "Lava strykewyrm", "Magma strykewyrm");
		register(table, "Commander Zilyana");
		register(table, "Zombies", "Undead", "Vorkath", "Zogre");
		register(table, "TzKal-Zuk");
		register(table, "Zulrah");

		FAMILY_MEMBERS_NORMALIZED = Collections.unmodifiableMap(table);
	}

	/**
	 * Registers `taskName` with the union of its explicit `aliases` and
	 * its own singular form (trailing "s" stripped) -- see class javadoc
	 * ALGORITHM section. Both are always combined, exactly mirroring
	 * RuneLite's own `SlayerPlugin.rebuildTargetNames()`.
	 */
	private static void register(Map<String, Set<String>> table, String taskName, String... aliases)
	{
		String normalizedTask = normalize(taskName);
		Set<String> members = new HashSet<>();
		members.add(singularize(normalizedTask));
		for (String alias : aliases)
		{
			members.add(normalize(alias));
		}
		table.put(normalizedTask, Collections.unmodifiableSet(members));
	}

	/**
	 * @return true if `npcName` is a legitimate member of the NPC family
	 * `taskName` covers -- either an explicitly known alternative/
	 * superior/boss-form NPC (from the catalogued table above) or the
	 * task's own singular form. Never true for an NPC merely
	 * "similar-looking" to the task name; every comparison is an exact,
	 * normalized string match. False for a null taskName/npcName.
	 */
	static boolean belongsToTaskFamily(String taskName, String npcName)
	{
		if (taskName == null || npcName == null)
		{
			return false;
		}
		String normalizedNpc = normalize(npcName);
		String normalizedTask = normalize(taskName);
		Set<String> members = FAMILY_MEMBERS_NORMALIZED.get(normalizedTask);
		if (members != null)
		{
			return members.contains(normalizedNpc);
		}
		// UNCATALOGUED task (not in the table above -- see class javadoc's
		// "known coverage gaps" note, and the classifier report's own
		// coverage-gaps section). No explicit alias list exists to pair
		// with a singular-suffix strip, so -- and ONLY here, never for a
		// catalogued task -- that one fixed, deterministic transform
		// (identical to RuneLite's own unconditional
		// `taskName.replaceAll("s$", "")`) is applied alone. This is
		// still not fuzzy matching: it is one exact string comparison
		// against one exact, fixed transform of the task name, never an
		// approximate or scored match.
		return normalizedNpc.equals(singularize(normalizedTask));
	}

	private static String singularize(String normalizedTaskName)
	{
		return normalizedTaskName.endsWith("s")
			? normalizedTaskName.substring(0, normalizedTaskName.length() - 1)
			: normalizedTaskName;
	}

	private static String normalize(String s)
	{
		return s.trim().toLowerCase(Locale.ROOT);
	}
}
