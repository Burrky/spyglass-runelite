package com.osrstelemetry.plugin.skills.agility;

import com.osrstelemetry.plugin.skills.MethodTracker;
import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.SkillBanking;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillCycleTracker;
import com.osrstelemetry.plugin.skills.SkillTracker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The Agility companion's brain: native Agility evidence -> the COURSE, the
 * position on its ROUTE, LAPS (generic cycles) and the course's REWARDS.
 * Pure (no RuneLite Client); client thread only; fed by
 * AgilityCompanionCollector and driven directly by tests.
 *
 * <h3>Obstacle successes</h3>
 * A click on a course obstacle (MenuOptionClicked on its game object) is
 * remembered for {@link #CLICK_TIMEOUT_TICKS}; an Agility XP drop within
 * that time is ONE obstacle's SUCCESS. Players click the next obstacle while
 * the current one still animates, so several clicks can be pending: the XP
 * belongs to the earliest of them on the route (the lap's first uncleared
 * obstacle that was clicked), never simply the newest click -- each obstacle
 * advances exactly once, in order, and a click ahead is never credited
 * early. An XP drop with no fresh obstacle click is never an obstacle
 * (shortcuts, other content), and a click without XP is never a success (it
 * may still be a failure, see Falls).
 *
 * <h3>Course identity</h3>
 * The obstacle's own object id names its course (shared objects are
 * resolved by the lap already in progress, then by region; a shared START
 * waits for the first course-unique obstacle). The DISPLAYED course follows
 * the shared MethodTracker: a different course needs two confirmed
 * obstacles, so one stray obstacle never churns it; the course's time is
 * the MethodTracker's (idle gaps over 3 minutes and logged-out time never
 * count).
 *
 * <h3>Laps</h3>
 * The START node's success (clicked) starts a synchronised lap at the click;
 * Agility XP on one of the course's END tiles (RuneLite's own native lap
 * semantics) -- or the course's completion varbit -- completes it. A lap is
 * counted only when its start was observed (SkillCycleTracker). Joining a
 * course mid-lap identifies the course and shows its route position, but
 * that lap never counts; reaching the end synchronises, and the next full
 * lap counts.
 *
 * <h3>Rewards</h3>
 * An inventory gain of the course's reward item, bank closed, while the
 * course is being run: a reward the course caused. Rewards with a trigger
 * object (Prifddinas portal -> crystal shard, Pyramid climbing rocks ->
 * pyramid top) additionally need that object's click within
 * {@link #TRIGGER_TICKS}. A lap and its reward stay separate facts.
 *
 * <h3>Falls</h3>
 * Only where the game gives structural evidence: on a rooftop, mid-lap, the
 * player dropping from the roofs to the ground (plane 0, not an end tile)
 * together with taking damage within {@link #FALL_WINDOW_TICKS}. "No XP" alone
 * is never a fall.
 */
public final class AgilityCompanionTracker implements SkillTracker
{
	/** How long an obstacle click waits for its XP (walking to it included). */
	static final int CLICK_TIMEOUT_TICKS = 25;
	/** At most this many obstacle clicks wait for their XP at once (newest kept). */
	static final int MAX_PENDING_CLICKS = 4;
	/** A reward trigger click explains a reward this many ticks later at most. */
	static final int TRIGGER_TICKS = 20;
	/** Plane drop and damage within this many ticks of each other: one fall. */
	static final int FALL_WINDOW_TICKS = 3;
	/** Course evidence older than this no longer explains a course reward / completion. */
	static final long COURSE_EVIDENCE_MILLIS = 2L * 60_000L;
	/** A lap with no course evidence for this long is abandoned (never a 19-minute lap). */
	static final long LAP_STALE_MILLIS = 10L * 60_000L;

	private static final class Click
	{
		final int objectId;
		final int tick;
		final long atMillis;
		final int plane;

		Click(int objectId, int tick, long atMillis, int plane)
		{
			this.objectId = objectId;
			this.tick = tick;
			this.atMillis = atMillis;
			this.plane = plane;
		}
	}

	private static final class Bucket
	{
		final Map<Integer, Long> invNet = new HashMap<>();
		final Map<Integer, Long> bankNet = new HashMap<>();
		boolean bankOpen;
		boolean depositBoxOpen;
	}

	public static final class Deposit implements SkillTracker.Deposit
	{
		private final int itemId;
		private final long quantity;

		Deposit(int itemId, long quantity)
		{
			this.itemId = itemId;
			this.quantity = quantity;
		}

		@Override
		public int getItemId()
		{
			return itemId;
		}

		@Override
		public long getQuantity()
		{
			return quantity;
		}
	}

	private final MethodTracker methods = new MethodTracker();
	private final ProductionLedger ledger = new ProductionLedger();
	private final SkillCycleTracker cycles = new SkillCycleTracker();
	private final TreeMap<Integer, Bucket> buckets = new TreeMap<>();
	private final List<Deposit> deposits = new ArrayList<>();
	/** Last click per reward-trigger object (tick). */
	private final Map<Integer, Integer> triggerClicks = new HashMap<>();

	private Map<Integer, Long> lastInventory;
	private Map<Integer, Long> lastBank;
	private boolean bankOpen;
	private boolean depositBoxOpen;

	/** Obstacle clicks still waiting for their XP, oldest first (one per object). */
	private final List<Click> pendingClicks = new ArrayList<>();
	/** Route state of the lap in progress (course = cycles.getMethod()). */
	private boolean[] cleared;
	private int lastNode = -1;
	private int obstacleCount;
	/** A start shared by several courses (Shayzien, Colossal Wyrm): which, and when it was clicked. */
	private List<AgilityCourse> ambiguousStart;
	private long ambiguousStartAt;

	private String evidenceCourse;
	private long evidenceAt = -1L;

	private int x = -1;
	private int y = -1;
	private int plane = -1;
	private int region = -1;
	private int planeDropTick = Integer.MIN_VALUE;
	private int damageTick = Integer.MIN_VALUE;
	private int lastTick = Integer.MIN_VALUE;
	private long revision;

	// ---- inputs ---------------------------------------------------------------------------

	/** The local player's tile (each tick and right before each Agility XP event). */
	public void onPosition(int tick, long nowMillis, int px, int py, int pplane, int regionId)
	{
		int previousPlane = plane;
		x = px;
		y = py;
		plane = pplane;
		region = regionId;
		AgilityCourse lap = AgilityCourses.byKey(cycles.getMethod());
		if (lap != null && lap.isPlaneDropFalls() && cycles.isRunning() && previousPlane >= 1 && pplane == 0 && !lap.isEndTile(px, py, pplane))
		{
			planeDropTick = tick;
			checkFall(tick);
		}
	}

	/** The local player took damage (a hitsplat on them). */
	public void onLocalDamage(int tick)
	{
		damageTick = tick;
		checkFall(tick);
	}

	/** A click on a game object (MenuOptionClicked identifier). Only course obstacles and reward triggers are remembered. */
	public void onObjectClick(int tick, long nowMillis, int objectId)
	{
		if (!AgilityCourses.withObject(objectId).isEmpty())
		{
			// Clicking the same obstacle again only refreshes it (one click per object waits).
			pendingClicks.removeIf(c -> c.objectId == objectId);
			pendingClicks.add(new Click(objectId, tick, nowMillis, plane));
			while (pendingClicks.size() > MAX_PENDING_CLICKS)
			{
				pendingClicks.remove(0);
			}
			creditXplessClicks(tick);
		}
		for (AgilityCourse c : AgilityCourses.all())
		{
			if (c.getReward() != null && c.getReward().getTriggerObjectIds().contains(objectId))
			{
				triggerClicks.put(objectId, tick);
			}
		}
	}

	@Override
	public void onSkillXp(int tick, long delta, long nowMillis)
	{
		if (delta <= 0)
		{
			return;
		}
		pendingClicks.removeIf(c -> tick - c.tick > CLICK_TIMEOUT_TICKS);
		// 1) The lap's end: Agility XP gained on a course end tile.
		for (AgilityCourse c : AgilityCourses.endingAt(x, y, plane))
		{
			Click last = c.getCompletionVarbit() == null ? clickFor(c, tick) : null;
			if (c.getCompletionVarbit() == null && (c.getKey().equals(cycles.getMethod()) || recentEvidence(c.getKey(), nowMillis) || last != null))
			{
				if (last != null)
				{
					pendingClicks.remove(last);
					obstacleSuccess(c, last.objectId, nowMillis, last, true);
				}
				complete(c, nowMillis);
				return;
			}
		}
		// 2) A clicked obstacle succeeded: the earliest one on the route, never simply the newest click.
		Click click = nextClick(tick);
		if (click != null)
		{
			pendingClicks.remove(click);
			resolveSuccess(click, nowMillis);
			return;
		}
		// 3) Agility XP with no course evidence (a shortcut, other content): no obstacle, no lap -- course time only.
		methods.success(null, nowMillis);
	}

	/** A varbit changed (Colossal Wyrm course progress). */
	public void onVarbit(int varbitId, int value, long nowMillis)
	{
		AgilityCourse c = AgilityCourses.completingOnVarbit(varbitId);
		if (c == null)
		{
			return;
		}
		if (value > 0 && ambiguousStart != null && ambiguousStart.contains(c))
		{
			startLap(c, ambiguousStartAt, nowMillis);
		}
		if (value >= c.getCompletionValue())
		{
			complete(c, nowMillis);
			return;
		}
		if (value > 0 && c.getKey().equals(cycles.getMethod()))
		{
			cycles.progress(value, c.denominator());
			noteEvidence(c, nowMillis);
		}
	}

	@Override
	public void onChat(int tick, long nowMillis, String message)
	{
		// No Agility chat line is used as evidence (none verified for success, fall or reward).
	}

	@Override
	public void onInventory(int tick, long nowMillis, Map<Integer, Long> items, int slotsUsed)
	{
		if (lastInventory == null)
		{
			lastInventory = new HashMap<>(items);
			revision++;
			return;
		}
		Bucket b = bucket(tick);
		SkillBanking.diffInto(lastInventory, items, b.invNet);
		b.bankOpen |= bankOpen;
		b.depositBoxOpen |= depositBoxOpen;
		lastInventory = new HashMap<>(items);
	}

	@Override
	public void onBank(int tick, long nowMillis, Map<Integer, Long> items)
	{
		if (lastBank == null)
		{
			lastBank = new HashMap<>(items);
			return;
		}
		Bucket b = bucket(tick);
		SkillBanking.diffInto(lastBank, items, b.bankNet);
		b.bankOpen = true;
		lastBank = new HashMap<>(items);
	}

	@Override
	public void setBankOpen(boolean open)
	{
		bankOpen = open;
	}

	@Override
	public void setDepositBoxOpen(boolean open)
	{
		depositBoxOpen = open;
	}

	@Override
	public void onTick(int tick, long nowMillis, int regionId)
	{
		lastTick = tick;
		methods.step(nowMillis);
		settleUpTo(tick - 1, nowMillis);
		if (cycles.isRunning() && evidenceAt >= 0 && nowMillis - evidenceAt > LAP_STALE_MILLIS)
		{
			// Left the course mid-lap: that lap can no longer be timed honestly.
			cycles.abandon();
			clearRoute();
		}
		pendingClicks.removeIf(c -> tick - c.tick > CLICK_TIMEOUT_TICKS);
		if (cycles.isRunning() && (tick & 1) == 0)
		{
			// The lap timer is visible: refresh it about once a second.
			revision++;
		}
	}

	// ---- route -----------------------------------------------------------------------------

	/**
	 * Which pending click this XP drop is the success of. With a lap on a
	 * course: of the clicks on that course's obstacles, the one whose route
	 * node comes first among those not yet cleared (a click ahead waits for
	 * its own XP); a click on the start obstacle when nothing else on the
	 * route is waiting (a new lap); else the newest click on the course (a
	 * repeat). Clicks of another course only when none of the lap's course
	 * is waiting (a course change). Without a lap: the oldest click (clicks
	 * are made in route order).
	 */
	private Click nextClick(int tick)
	{
		List<Click> live = new ArrayList<>();
		for (Click c : pendingClicks)
		{
			if (tick >= c.tick && tick - c.tick <= CLICK_TIMEOUT_TICKS)
			{
				live.add(c);
			}
		}
		if (live.isEmpty())
		{
			return null;
		}
		AgilityCourse lap = AgilityCourses.byKey(cycles.getMethod());
		if (lap != null && cleared != null && cleared.length == lap.getNodes().size())
		{
			Click best = null;
			int bestIdx = Integer.MAX_VALUE;
			Click start = null;
			Click newestOnCourse = null;
			for (Click c : live)
			{
				if (!lap.objectIds().contains(c.objectId))
				{
					continue;
				}
				newestOnCourse = c;
				int idx = firstAccepting(lap, c.objectId, cleared);
				// A zero-XP obstacle (a ladder) never takes another obstacle's XP: its click credits it (creditXplessClicks).
				if (idx >= 0 && idx < bestIdx && !lap.getNodes().get(idx).isXpless())
				{
					best = c;
					bestIdx = idx;
				}
				if (lap.startNode().accepts(c.objectId))
				{
					start = c;
				}
			}
			if (best != null)
			{
				return best;
			}
			if (start != null)
			{
				return start;
			}
			if (newestOnCourse != null)
			{
				return newestOnCourse;
			}
		}
		return live.get(0);
	}

	/**
	 * Zero-XP obstacles (a ladder) have no XP of their own: one is passed when
	 * it is the NEXT obstacle of the lap and its click is pending -- at its
	 * click, or as soon as the obstacle before it succeeded (clicked ahead).
	 * In route order, one step at a time: never a jump past an uncleared
	 * obstacle.
	 */
	private void creditXplessClicks(int tick)
	{
		AgilityCourse lap = AgilityCourses.byKey(cycles.getMethod());
		if (lap == null || cleared == null || cleared.length != lap.getNodes().size() || lap.isCountOnly())
		{
			return;
		}
		boolean changed = false;
		while (true)
		{
			int next = -1;
			for (int i = 0; i < cleared.length; i++)
			{
				if (!cleared[i])
				{
					next = i;
					break;
				}
			}
			if (next < 0 || !lap.getNodes().get(next).isXpless())
			{
				break;
			}
			Click used = null;
			for (Click c : pendingClicks)
			{
				if (tick >= c.tick && tick - c.tick <= CLICK_TIMEOUT_TICKS && lap.getNodes().get(next).accepts(c.objectId))
				{
					used = c;
					break;
				}
			}
			if (used == null)
			{
				break;
			}
			pendingClicks.remove(used);
			cleared[next] = true;
			lastNode = next;
			changed = true;
		}
		if (changed)
		{
			publishProgress(lap);
		}
	}

	/** The pending click (within the timeout) that belongs to this course, earliest on its route first; null when none. */
	private Click clickFor(AgilityCourse course, int tick)
	{
		Click best = null;
		int bestIdx = Integer.MAX_VALUE;
		for (Click c : pendingClicks)
		{
			if (tick < c.tick || tick - c.tick > CLICK_TIMEOUT_TICKS || !course.objectIds().contains(c.objectId))
			{
				continue;
			}
			boolean onLap = course.getKey().equals(cycles.getMethod()) && cleared != null && cleared.length == course.getNodes().size();
			int idx = firstAccepting(course, c.objectId, onLap ? cleared : null);
			// The start obstacle clicked for the next lap never stands in for this lap's last one.
			if (idx == 0 && onLap)
			{
				continue;
			}
			if (idx >= 0 && idx < bestIdx || best == null && idx >= 0)
			{
				best = c;
				bestIdx = idx;
			}
		}
		return best;
	}

	private void resolveSuccess(Click click, long nowMillis)
	{
		List<AgilityCourse> candidates = AgilityCourses.withObject(click.objectId);
		AgilityCourse course = choose(candidates);
		if (course == null)
		{
			// A start several courses share: remember it until a course-unique obstacle says which.
			boolean sharedStart = !candidates.isEmpty();
			for (AgilityCourse c : candidates)
			{
				sharedStart &= c.startNode().accepts(click.objectId);
			}
			if (sharedStart)
			{
				cycles.abandon();
				clearRoute();
				ambiguousStart = candidates;
				ambiguousStartAt = click.atMillis;
			}
			methods.success(null, nowMillis);
			return;
		}
		if (ambiguousStart != null && ambiguousStart.contains(course) && !course.startNode().accepts(click.objectId)
			&& !course.getKey().equals(cycles.getMethod()))
		{
			startLap(course, ambiguousStartAt, nowMillis);
			// The shared obstacles between the shared start and this first course-unique one were passed on the way here.
			int idx = firstAccepting(course, click.objectId, cleared);
			for (int i = 1; i < idx; i++)
			{
				cleared[i] = true;
			}
		}
		obstacleSuccess(course, click.objectId, nowMillis, click, false);
	}

	/** The course an obstacle belongs to: the lap in progress, the displayed course, the only candidate, or the region's. */
	private AgilityCourse choose(List<AgilityCourse> candidates)
	{
		if (candidates.isEmpty())
		{
			return null;
		}
		if (candidates.size() == 1)
		{
			return candidates.get(0);
		}
		for (AgilityCourse c : candidates)
		{
			if (c.getKey().equals(cycles.getMethod()))
			{
				return c;
			}
		}
		for (AgilityCourse c : candidates)
		{
			if (c.getKey().equals(methods.getCurrent()))
			{
				return c;
			}
		}
		AgilityCourse inRegion = null;
		for (AgilityCourse c : candidates)
		{
			if (!c.getRegions().isEmpty() && c.getRegions().contains(region))
			{
				if (inRegion != null)
				{
					return null;
				}
				inRegion = c;
			}
		}
		return inRegion;
	}

	private void obstacleSuccess(AgilityCourse course, int objectId, long nowMillis, Click click, boolean final_)
	{
		noteEvidence(course, nowMillis);
		methods.success(course.getKey(), nowMillis);
		boolean lapOnCourse = course.getKey().equals(cycles.getMethod()) && cleared != null && cleared.length == course.getNodes().size();
		if (course.getShortcutObjectIds().contains(objectId))
		{
			if (lapOnCourse)
			{
				// A legitimate shortcut: the obstacle it skips is passed, never a broken route.
				creditNext();
				publishProgress(course);
			}
			return;
		}
		boolean startNode = course.startNode().accepts(objectId)
			&& (course.getStartPlane() == null || (click != null ? click.plane : plane) == course.getStartPlane());
		if (startNode && !(lapOnCourse && lastNode == 0))
		{
			startLap(course, click != null ? click.atMillis : nowMillis, nowMillis);
			return;
		}
		if (!lapOnCourse)
		{
			// Joined mid-lap: the course and the route position are known, this lap will not count.
			int idx = firstAccepting(course, objectId, null);
			cleared = new boolean[course.getNodes().size()];
			for (int i = 0; i <= Math.max(0, idx); i++)
			{
				cleared[i] = true;
			}
			lastNode = Math.max(0, idx);
			obstacleCount = 1;
			cycles.joinUnsynced(course.getKey(), course.isCountOnly() ? obstacleCount : clearedCount(), course.denominator());
			revision++;
			return;
		}
		int idx = firstAccepting(course, objectId, cleared);
		if (idx < 0)
		{
			// A repeat of an obstacle already passed (Werewolf's stepping stones, a second try): no new progress.
			if (course.isCountOnly())
			{
				obstacleCount++;
				publishProgress(course);
			}
			return;
		}
		for (int i = 0; i < idx; i++)
		{
			if (course.getNodes().get(i).isXpless())
			{
				cleared[i] = true;
			}
		}
		cleared[idx] = true;
		lastNode = idx;
		obstacleCount++;
		publishProgress(course);
		// A ladder right after it, already clicked: passed too, in order.
		creditXplessClicks(lastTick == Integer.MIN_VALUE ? (click != null ? click.tick : 0) : lastTick);
	}

	private void startLap(AgilityCourse course, long startedAt, long nowMillis)
	{
		ambiguousStart = null;
		cleared = new boolean[course.getNodes().size()];
		cleared[0] = true;
		lastNode = 0;
		obstacleCount = 1;
		cycles.start(course.getKey(), startedAt, 1, course.denominator());
		noteEvidence(course, nowMillis);
		methods.success(course.getKey(), nowMillis);
		revision++;
	}

	private void complete(AgilityCourse course, long nowMillis)
	{
		noteEvidence(course, nowMillis);
		methods.success(course.getKey(), nowMillis);
		cycles.complete(course.getKey(), nowMillis);
		clearRoute();
		revision++;
	}

	private void creditNext()
	{
		for (int i = 0; i < cleared.length; i++)
		{
			if (!cleared[i])
			{
				cleared[i] = true;
				lastNode = i;
				return;
			}
		}
	}

	private void publishProgress(AgilityCourse course)
	{
		cycles.progress(course.isCountOnly() ? obstacleCount : clearedCount(), course.denominator());
		revision++;
	}

	private int clearedCount()
	{
		int n = 0;
		if (cleared != null)
		{
			for (boolean c : cleared)
			{
				if (c)
				{
					n++;
				}
			}
		}
		return n;
	}

	/** The first route node (not yet cleared, when a lap is known) that accepts the object; -1 when none. */
	private static int firstAccepting(AgilityCourse course, int objectId, boolean[] done)
	{
		List<AgilityCourse.Node> nodes = course.getNodes();
		for (int i = 0; i < nodes.size(); i++)
		{
			if (nodes.get(i).accepts(objectId) && (done == null || !done[i]))
			{
				return i;
			}
		}
		return -1;
	}

	private void clearRoute()
	{
		cleared = null;
		lastNode = -1;
		obstacleCount = 0;
		ambiguousStart = null;
	}

	private void noteEvidence(AgilityCourse course, long nowMillis)
	{
		evidenceCourse = course.getKey();
		evidenceAt = nowMillis;
	}

	private boolean recentEvidence(String courseKey, long nowMillis)
	{
		return Objects.equals(courseKey, evidenceCourse) && evidenceAt >= 0 && nowMillis - evidenceAt <= COURSE_EVIDENCE_MILLIS;
	}

	private void checkFall(int tick)
	{
		if (planeDropTick == Integer.MIN_VALUE || damageTick == Integer.MIN_VALUE || Math.abs((long) planeDropTick - damageTick) > FALL_WINDOW_TICKS)
		{
			return;
		}
		AgilityCourse lap = AgilityCourses.byKey(cycles.getMethod());
		if (lap == null || !lap.isPlaneDropFalls() || !cycles.isRunning())
		{
			return;
		}
		planeDropTick = Integer.MIN_VALUE;
		damageTick = Integer.MIN_VALUE;
		cycles.fail(lap.getKey());
		// Off the roofs: the lap restarts from the start obstacle.
		cycles.abandon();
		clearRoute();
		revision++;
	}

	// ---- rewards / banking --------------------------------------------------------------------

	private Bucket bucket(int tick)
	{
		return buckets.computeIfAbsent(tick, k -> new Bucket());
	}

	void settleUpTo(int lastClosed, long nowMillis)
	{
		List<Integer> done = new ArrayList<>();
		for (Map.Entry<Integer, Bucket> e : buckets.entrySet())
		{
			if (e.getKey() > lastClosed)
			{
				break;
			}
			settle(e.getKey(), e.getValue(), nowMillis);
			done.add(e.getKey());
		}
		for (Integer t : done)
		{
			buckets.remove(t);
		}
	}

	private void settle(int tick, Bucket b, long nowMillis)
	{
		if (b.bankOpen || b.depositBoxOpen)
		{
			if (SkillBanking.settleBankingTick(b.invNet, b.bankNet, b.depositBoxOpen, ledger, null, id -> false,
				(item, qty) -> deposits.add(new Deposit(item, qty))))
			{
				revision++;
			}
			return;
		}
		for (Map.Entry<Integer, Long> d : b.invNet.entrySet())
		{
			long change = d.getValue();
			int item = d.getKey();
			if (change < 0)
			{
				ledger.inventoryRemoved(item, -change);
				continue;
			}
			if (change > 0 && AgilityCourses.isRewardItem(item) && rewardCaused(item, tick, nowMillis))
			{
				ledger.produced(item, change, ProductionLedger.Destination.INVENTORY);
				revision++;
			}
		}
	}

	/** Whether the course being run caused this reward gain (never a withdrawal, never an unrelated pick-up). */
	private boolean rewardCaused(int itemId, int tick, long nowMillis)
	{
		AgilityCourse course = AgilityCourses.byKey(evidenceCourse);
		if (course == null || course.getReward() == null || course.getReward().getItemId() != itemId
			|| !recentEvidence(course.getKey(), nowMillis))
		{
			return false;
		}
		if (course.getReward().getTriggerObjectIds().isEmpty())
		{
			return true;
		}
		for (Integer trigger : course.getReward().getTriggerObjectIds())
		{
			Integer at = triggerClicks.get(trigger);
			if (at != null && tick >= at && tick - at <= TRIGGER_TICKS)
			{
				return true;
			}
		}
		return false;
	}

	// ---- outputs ---------------------------------------------------------------------------

	@Override
	public boolean hasInventoryBaseline()
	{
		return lastInventory != null;
	}

	@Override
	public ProductionLedger getLedger()
	{
		return ledger;
	}

	@Override
	public MethodTracker getMethods()
	{
		return methods;
	}

	@Override
	public SkillCycleTracker getCycles()
	{
		return cycles;
	}

	@Override
	public String getMethodKey()
	{
		return methods.getCurrent();
	}

	@Override
	public long getRevision()
	{
		return revision + cycles.getRevision();
	}

	@Override
	public List<Deposit> drainDeposits()
	{
		if (deposits.isEmpty())
		{
			return Collections.emptyList();
		}
		List<Deposit> out = new ArrayList<>(deposits);
		deposits.clear();
		return out;
	}

	/** The course rewards carried right now (OWNED of a reward target); null until the inventory was read. */
	@Override
	public Map<Integer, Long> carried()
	{
		if (lastInventory == null)
		{
			return null;
		}
		Map<Integer, Long> out = new HashMap<>();
		for (Map.Entry<Integer, Long> e : lastInventory.entrySet())
		{
			if (AgilityCourses.isRewardItem(e.getKey()))
			{
				out.put(e.getKey(), e.getValue());
			}
		}
		return out;
	}

	/** Agility has no inventory trip. */
	@Override
	public SkillCompanionView.TripView trip(long nowMillis)
	{
		return null;
	}

	@Override
	public List<SkillCompanionView.ContextLine> context(long nowMillis)
	{
		return SkillTracker.none();
	}

	/** The current lap of the lap's course (null before any course evidence). */
	@Override
	public SkillCompanionView.CycleView cycle(long nowMillis)
	{
		String m = cycles.getMethod();
		if (m == null)
		{
			return null;
		}
		return new SkillCompanionView.CycleView(m, cycles.getProgress(), cycles.getDenominator(), cycles.elapsedMillis(nowMillis),
			cycles.isSynced());
	}

	@Override
	public void settleAll()
	{
		settleUpTo(Integer.MAX_VALUE - 1, evidenceAt < 0 ? 0L : evidenceAt);
	}

	/** Logout: the lap cannot be timed across it; containers re-baseline. The course itself is kept. */
	@Override
	public void onLogout()
	{
		buckets.clear();
		lastInventory = null;
		lastBank = null;
		bankOpen = false;
		depositBoxOpen = false;
		pendingClicks.clear();
		triggerClicks.clear();
		cycles.abandon();
		clearRoute();
		plane = -1;
		planeDropTick = Integer.MIN_VALUE;
		damageTick = Integer.MIN_VALUE;
		revision++;
	}

	@Override
	public void reset()
	{
		methods.reset();
		ledger.reset();
		cycles.reset();
		buckets.clear();
		deposits.clear();
		triggerClicks.clear();
		lastInventory = null;
		lastBank = null;
		bankOpen = false;
		depositBoxOpen = false;
		pendingClicks.clear();
		clearRoute();
		evidenceCourse = null;
		evidenceAt = -1L;
		x = -1;
		y = -1;
		plane = -1;
		region = -1;
		planeDropTick = Integer.MIN_VALUE;
		damageTick = Integer.MIN_VALUE;
		lastTick = Integer.MIN_VALUE;
		revision++;
	}

	int lastTick()
	{
		return lastTick;
	}

	/** Tests: what was produced so far (pending only). */
	Map<Integer, Long> pendingProduced()
	{
		return new LinkedHashMap<>(ledger.getPendingProduced());
	}
}
