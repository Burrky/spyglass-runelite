package com.osrstelemetry.plugin.skills.woodcutting;

import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.SkillResource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;

/**
 * Drives a WoodcuttingCompanionTracker exactly as the collector does (per
 * tick: XP of either skill, chat, inventory / bank snapshots, clicks) and
 * keeps the drained totals (produced / banked / auto-burned) like the
 * session would.
 */
final class WoodcuttingSim
{
	static final int REGION = 12_850;
	final WoodcuttingCompanionTracker t = new WoodcuttingCompanionTracker();
	int tick = 2_000;
	long now = 50_000_000L;
	final Map<Integer, Long> inv = new LinkedHashMap<>();
	final Map<Integer, Long> bank = new LinkedHashMap<>();
	final Map<Integer, Long> produced = new LinkedHashMap<>();
	final Map<Integer, Long> banked = new LinkedHashMap<>();
	final Map<Integer, Long> burned = new LinkedHashMap<>();
	final Map<String, Long> occurrences = new LinkedHashMap<>();
	final Map<String, Long> evidence = new LinkedHashMap<>();

	static boolean stackable(int item)
	{
		SkillResource r = WoodcuttingCompanionDefinition.INSTANCE.resource(item);
		return r != null && r.isStackable();
	}

	int slots()
	{
		int used = 0;
		for (Map.Entry<Integer, Long> e : inv.entrySet())
		{
			if (e.getValue() > 0)
			{
				used += stackable(e.getKey()) ? 1 : (int) (long) e.getValue();
			}
		}
		return used;
	}

	WoodcuttingSim login(Object... items)
	{
		for (int i = 0; i < items.length; i += 2)
		{
			inv.put((Integer) items[i], ((Number) items[i + 1]).longValue());
		}
		t.onInventory(tick, now, new HashMap<>(inv), slots());
		t.onBank(tick, now, new HashMap<>(bank));
		step();
		return this;
	}

	void pushInventory()
	{
		inv.values().removeIf(v -> v <= 0);
		t.onInventory(tick, now, new HashMap<>(inv), slots());
	}

	void pushBank()
	{
		bank.values().removeIf(v -> v <= 0);
		t.onBank(tick, now, new HashMap<>(bank));
	}

	void step()
	{
		tick++;
		now += 600;
		t.onTick(tick, now, REGION);
		drain();
	}

	void steps(int n)
	{
		for (int i = 0; i < n; i++)
		{
			step();
		}
	}

	void drain()
	{
		ProductionLedger.Drain d = t.getLedger().drain();
		d.getProduced().forEach((k, v) -> produced.merge(k, v, Long::sum));
		d.getBanked().forEach((k, v) -> banked.merge(k, v, Long::sum));
		d.getAutoTransformed().forEach((k, v) -> burned.merge(k, v, Long::sum));
		t.drainOccurrences().forEach((k, v) -> occurrences.merge(k, v, Long::sum));
		evidence.putAll(t.drainContextEvidence());
	}

	static String line(int log)
	{
		switch (log)
		{
			case ItemID.LOGS:
				return "You get some logs.";
			case ItemID.BITTERCAP_MUSHROOM:
				return "You get some mushrooms.";
			default:
				return "You get some " + WoodcuttingCompanionDefinition.INSTANCE.resource(log).getName().toLowerCase(java.util.Locale.ROOT) + ".";
		}
	}

	/** One ordinary cut: XP, the line, the log in the inventory -- same tick -- then a tick passes. */
	void cut(int log)
	{
		cut(log, true, 1);
	}

	void cut(int log, boolean withLine, long qty)
	{
		t.onSkillXp(tick, 175, now);
		if (withLine)
		{
			t.onChat(tick, now, line(log));
		}
		inv.merge(log, qty, Long::sum);
		pushInventory();
		step();
	}

	/** One cut whose log the infernal axe burned: Woodcutting + Firemaking XP, no log reaches the inventory. */
	void burn(int log, boolean withLine)
	{
		t.onSkillXp(tick, 175, now);
		t.onOtherSkillXp("FIREMAKING", tick, 87, now);
		if (withLine)
		{
			t.onChat(tick, now, line(log));
		}
		step();
	}

	void cuts(int log, int n)
	{
		for (int i = 0; i < n; i++)
		{
			cut(log);
			steps(3);
		}
	}

	void click(String name, int objectId, long tile)
	{
		t.onTreeClick(now, name, objectId, tile);
	}

	long p(int item)
	{
		return produced.getOrDefault(item, 0L);
	}

	long b(int item)
	{
		return banked.getOrDefault(item, 0L);
	}

	long burnedOf(int item)
	{
		return burned.getOrDefault(item, 0L);
	}

	/** Deposit everything of {@code item} with the bank open (a proven deposit). */
	void depositAll(int item)
	{
		t.setBankOpen(true);
		long q = inv.getOrDefault(item, 0L);
		inv.put(item, 0L);
		bank.merge(item, q, Long::sum);
		pushInventory();
		pushBank();
		step();
		t.setBankOpen(false);
		steps(3);
	}
}
