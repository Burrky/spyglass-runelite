package com.osrstelemetry.plugin.skills.agility;

import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.SkillCycleStats;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drives an {@link AgilityCompanionTracker} exactly as the collector does
 * (object click, then the player's tile and Agility XP, ticks in between),
 * so every course can be run end to end in tests.
 */
final class AgilitySim
{
	final AgilityCompanionTracker t = new AgilityCompanionTracker();
	int tick = 5_000;
	long now = 20_000_000L;
	int x = 1000;
	int y = 1000;
	int plane = 1;
	final Map<Integer, Long> inv = new LinkedHashMap<>();
	final Map<Integer, Long> bank = new LinkedHashMap<>();
	final Map<Integer, Long> produced = new LinkedHashMap<>();
	final Map<Integer, Long> banked = new LinkedHashMap<>();
	final Map<String, SkillCycleStats> laps = new LinkedHashMap<>();

	AgilitySim()
	{
		t.onInventory(tick, now, new HashMap<>(inv), 0);
		t.onBank(tick, now, new HashMap<>(bank));
	}

	void step()
	{
		tick++;
		now += 600;
		t.onPosition(tick, now, x, y, plane, region());
		t.onTick(tick, now, region());
	}

	void steps(int n)
	{
		for (int i = 0; i < n; i++)
		{
			step();
		}
	}

	int region()
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	void at(int px, int py, int pplane)
	{
		x = px;
		y = py;
		plane = pplane;
		t.onPosition(tick, now, x, y, plane, region());
	}

	/** Click an obstacle, walk/animate a few ticks, gain its XP. */
	void obstacle(int objectId)
	{
		obstacle(objectId, 30);
	}

	void obstacle(int objectId, long xp)
	{
		t.onObjectClick(tick, now, objectId);
		steps(4);
		t.onPosition(tick, now, x, y, plane, region());
		t.onSkillXp(tick, xp, now);
		steps(2);
	}

	/** Click without any XP (e.g. a zero-XP ladder). */
	void clickOnly(int objectId)
	{
		t.onObjectClick(tick, now, objectId);
		steps(4);
	}

	/** The final obstacle of an end-tile course: clicked, then XP on the end tile. */
	void finish(AgilityCourse c, int lastObjectId)
	{
		t.onObjectClick(tick, now, lastObjectId);
		steps(4);
		AgilityCourse.Tile end = c.getEndTiles().get(0);
		at(end.getX(), end.getY(), end.getPlane());
		t.onSkillXp(tick, 100, now);
		steps(2);
		at(end.getX() + 3, end.getY() + 3, 0);
	}

	/** A full lap of a course, start to finish (every route node once; varbit courses complete on their varbit). */
	void lap(AgilityCourse c)
	{
		java.util.List<AgilityCourse.Node> nodes = c.getNodes();
		plane = c.getStartPlane() != null ? c.getStartPlane() : 1;
		if (c.getStartPlane() != null)
		{
			at(x, y, c.getStartPlane());
		}
		int last = c.getCompletionVarbit() != null ? nodes.size() : nodes.size() - 1;
		for (int i = 0; i < last; i++)
		{
			int id = nodes.get(i).getObjectIds().iterator().next();
			if (nodes.get(i).isXpless())
			{
				clickOnly(id);
			}
			else
			{
				obstacle(id);
			}
			if (i == 0 && c.getStartPlane() != null)
			{
				at(x, y, c.getStartPlane() + 1);
			}
			if (c.getCompletionVarbit() != null)
			{
				t.onVarbit(c.getCompletionVarbit(), i + 1, now);
			}
		}
		if (c.getCompletionVarbit() == null)
		{
			finish(c, nodes.get(nodes.size() - 1).getObjectIds().iterator().next());
		}
	}

	void gain(int itemId, long qty)
	{
		inv.merge(itemId, qty, Long::sum);
		t.onInventory(tick, now, new HashMap<>(inv), inv.size());
		steps(2);
	}

	void deposit(int itemId, long qty)
	{
		t.setBankOpen(true);
		inv.merge(itemId, -qty, Long::sum);
		inv.values().removeIf(v -> v <= 0);
		bank.merge(itemId, qty, Long::sum);
		t.onInventory(tick, now, new HashMap<>(inv), inv.size());
		t.onBank(tick, now, new HashMap<>(bank));
		steps(2);
		t.setBankOpen(false);
	}

	void withdraw(int itemId, long qty)
	{
		t.setBankOpen(true);
		inv.merge(itemId, qty, Long::sum);
		bank.merge(itemId, -qty, Long::sum);
		t.onInventory(tick, now, new HashMap<>(inv), inv.size());
		t.onBank(tick, now, new HashMap<>(bank));
		steps(2);
		t.setBankOpen(false);
	}

	void settle()
	{
		steps(2);
		t.settleAll();
		ProductionLedger.Drain d = t.getLedger().drain();
		d.getProduced().forEach((k, v) -> produced.merge(k, v, Long::sum));
		d.getBanked().forEach((k, v) -> banked.merge(k, v, Long::sum));
		t.getCycles().drain().forEach((k, v) -> laps.merge(k, v, SkillCycleStats::plus));
	}

	long laps(AgilityCourse c)
	{
		SkillCycleStats s = laps.get(c.getKey());
		return s == null ? 0L : s.getCount();
	}

	long produced(int itemId)
	{
		return produced.getOrDefault(itemId, 0L);
	}
}
