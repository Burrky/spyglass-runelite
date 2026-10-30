package com.osrstelemetry.plugin.travel;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountReaders;
import com.osrstelemetry.plugin.account.StorageEvidence;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Developer-only text for the account capability and every transport's
 * availability with its reasons (canonical Phase F "dev Teleports view").
 * Never shown on a player screen.
 */
public final class TransportDiagnostics
{
	private TransportDiagnostics()
	{
	}

	public static List<String> capabilityLines(AccountCapability cap, long now)
	{
		List<String> out = new ArrayList<>();
		if (cap == null || cap.getOwner() == null)
		{
			out.add("account: none (" + (cap == null ? "adapter not started" : cap.getStatus()) + ") - nothing is known, nothing is assumed");
			return out;
		}
		out.add("account: " + cap.getOwner() + " | " + cap.getStatus() + " | members world " + cap.getMembersWorld() + " | "
			+ (cap.isSettled() ? "account data loaded" : "account data not yet evidenced as loaded: quests / diaries / spellbook / pouch not read"));
		out.add("levels: " + (cap.getLevels().isEmpty() ? "not loaded" : "Magic " + cap.getLevels().get(net.runelite.api.Skill.MAGIC) + ", Farming "
			+ cap.getLevels().get(net.runelite.api.Skill.FARMING) + ", Agility " + cap.getLevels().get(net.runelite.api.Skill.AGILITY)));
		out.add("quests read: " + cap.getQuests().size() + " | diaries read: " + cap.getDiaries().size() + " of " + AccountReaders.readableDiaries().size()
			+ " readable (Karamja easy-hard: proven only by a claimed reward)");
		Integer raw = cap.getSpellbookRaw();
		out.add("spellbook: " + (raw == null ? "not read" : cap.spellbook() == null ? "value " + raw + " (not a known book: unknown)" : cap.spellbook() + " (value " + raw + ")"));
		out.add("inventory: " + size(cap.getInventory()) + " | worn: " + size(cap.getWorn()) + " | rune pouch: "
			+ (cap.getRunePouch() == null ? "not read" : cap.getRunePouch().size() + " rune types"));
		out.add("bank: " + storage(cap.getBank(), now) + " | group storage: " + storage(cap.getGroupStorage(), now) + " | seed vault: "
			+ storage(cap.getSeedVault(), now));
		return out;
	}

	/**
	 * One block per transport: the single final state, then what was found,
	 * what is met, what is unmet and what evidence is missing, each on its own
	 * labelled lines and never merged.
	 */
	public static List<String> availabilityLines(List<TransportAvailability> results)
	{
		List<String> out = new ArrayList<>();
		for (TransportAvailability a : results)
		{
			out.add(String.format("%-18s %-34s %s", a.getState(), a.getTransport().id, a.getTransport().name));
			for (String s : a.getHave())
			{
				out.add("    have:     " + s);
			}
			if (!a.getMet().isEmpty())
			{
				out.add("    met:      " + String.join("; ", a.getMet()));
			}
			for (String s : a.getUnmet())
			{
				out.add("    unmet:    " + s);
			}
			for (String s : a.getMissing())
			{
				out.add("    missing evidence: " + s);
			}
			for (String s : TransportDef.list(a.getTransport().notes))
			{
				out.add("    note:     " + s);
			}
		}
		return out;
	}

	public static String summary(List<TransportAvailability> results)
	{
		Map<Availability, Integer> counts = new EnumMap<>(Availability.class);
		for (TransportAvailability a : results)
		{
			counts.merge(a.getState(), 1, Integer::sum);
		}
		List<String> parts = new ArrayList<>();
		counts.forEach((k, v) -> parts.add(v + " " + k));
		return results.size() + " transports: " + String.join(", ", parts);
	}

	private static String size(Map<Integer, Integer> m)
	{
		return m == null ? "not read" : m.size() + " item types";
	}

	private static String storage(StorageEvidence e, long now)
	{
		return e == null ? "never seen for this account (not \"empty\")" : e.getItems().size() + " item types, seen " + com.osrstelemetry.plugin.account.ItemLocator.age(now - e.getObservedAt()) + " ago";
	}
}
