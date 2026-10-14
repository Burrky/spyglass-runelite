package com.osrstelemetry.plugin.skills;

import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.session.SessionState;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * The SHARED native wiring of every skill companion (client thread):
 * RuneLite events in, the skill's {@link SkillTracker} in the middle,
 * bounded telemetry and a read-only {@link SkillCompanionView} out. Each
 * skill subclasses it with nothing but its tracker, its definition and any
 * extra evidence of its own (Mining: "Mine" clicks; Fishing: the fishing
 * spot it interacts with). Independent of RuneLite's own skill plugins.
 *
 * <h3>Binding to the skill's session</h3>
 * The session engine -- never this class -- decides that a SKILLING/&lt;skill&gt;
 * session exists (from the skill's XP, as for every skill). Production
 * observed before that session exists waits as PENDING and is sent once
 * the session is current; if no session of this skill claims it within
 * {@link #PENDING_LIMIT_MILLIS} (the XP was part of something else, e.g. a
 * structured activity such as Tempoross), it is dropped rather than
 * misattributed.
 *
 * <h3>Telemetry (bounded)</h3>
 * One EventType.SKILL_PRODUCTION per {@link #FLUSH_INTERVAL_TICKS} of
 * activity at most, plus one when a deposit lands, one on logout / hop and
 * one at shutdown. Never per item, never per tick; nothing about inventory
 * fullness, storage contents, supplies or the method is ever written.
 */
@Slf4j
public abstract class SkillCompanionCollector
{
	/** Same slow cadence as XP_CHANGE windows (~30 s). */
	public static final int FLUSH_INTERVAL_TICKS = 50;
	/** Production no session of this skill claimed in this long is dropped (never misattributed). */
	public static final long PENDING_LIMIT_MILLIS = 3L * 60_000L;

	protected final Client client;
	private final EventLedger eventLedger;
	private final Supplier<SessionSnapshot> currentSession;
	private final LocalStateStore store;
	private final SkillCompanionDefinition definition;
	protected final SkillTracker tracker;

	private volatile int lastXp = -1;
	/** The account the pending production belongs to (read while logged in: on the login screen it is gone). */
	private long accountHash = -1L;
	private int ticksSinceFlush;
	private long pendingSince = -1L;

	private String boundSessionId;
	private final Map<Integer, Long> flushedProduced = new LinkedHashMap<>();
	private final Map<Integer, Long> flushedBanked = new LinkedHashMap<>();
	private final Map<String, Long> flushedMillis = new LinkedHashMap<>();
	private final Map<String, SkillCycleStats> flushedCycles = new LinkedHashMap<>();
	private final Map<Integer, Long> flushedAutoTransformed = new LinkedHashMap<>();
	private final Map<Integer, Long> flushedConsumed = new LinkedHashMap<>();
	private final Map<String, Long> flushedOccurrences = new LinkedHashMap<>();
	/** Other skills' last absolute XP (their gains feed onOtherSkillXp; the first reading is only a baseline). */
	private final Map<Skill, Integer> otherXp = new java.util.EnumMap<>(Skill.class);
	private boolean otherXpSeeded;
	/** Per resource: banked quantity, deposits, active ms at the latest deposit (stockpile ETA). */
	private final Map<Integer, long[]> banking = new HashMap<>();
	private final List<SkillTracker.Deposit> unboundDeposits = new ArrayList<>();
	/** What this session's production still held when it was last sent to the bound session. */
	private ProductionLedger.HeldMark heldAtLastSend;

	private volatile Map<Integer, Long> liveBank;
	private volatile Map<Integer, Long> savedBank;
	private long savedBankAccount = Long.MIN_VALUE;

	protected LongSupplier clock = System::currentTimeMillis;

	/** Where fresh ownership evidence goes (the session engine); null = nowhere (Mining, tests). */
	private volatile java.util.function.BiConsumer<String, Instant> methodEvidenceSink;
	private long lastEvidenceSentAt = Long.MIN_VALUE;
	/** At most one evidence note per this many ms (catches come every few seconds; freshness is 15 s+). */
	static final long EVIDENCE_INTERVAL_MILLIS = 5_000L;

	/** Where context XP evidence goes (the session engine); null = nowhere. */
	private volatile ContextEvidenceSink contextEvidenceSink;
	private final Map<String, Long> lastContextSentAt = new HashMap<>();

	/** Receives "XP in {@code xpSkill} right now belongs to {@code ownerSkillKey}'s activity" (see SkillTracker#drainContextEvidence). */
	@FunctionalInterface
	public interface ContextEvidenceSink
	{
		void accept(String ownerSkillKey, String xpSkill, Instant at);
	}

	private volatile SkillCompanionView view = SkillCompanionView.EMPTY;
	private volatile Runnable progressListener;
	private long lastPublishedRevision = Long.MIN_VALUE;

	protected SkillCompanionCollector(Client client, EventLedger eventLedger, Supplier<SessionSnapshot> currentSession, LocalStateStore store,
		SkillCompanionDefinition definition, SkillTracker tracker)
	{
		this.client = client;
		this.eventLedger = eventLedger;
		this.currentSession = currentSession;
		this.store = store;
		this.definition = definition;
		this.tracker = tracker;
	}

	public SkillCompanionDefinition getDefinition()
	{
		return definition;
	}

	protected void setClock(LongSupplier clock)
	{
		this.clock = clock;
	}

	public void setProgressListener(Runnable listener)
	{
		this.progressListener = listener;
	}

	/** The player's current absolute XP in the skill (a level target); null before the first observation. */
	public Integer currentXp()
	{
		int xp = lastXp;
		return xp < 0 ? null : xp;
	}

	/** Any thread: the live view (immutable). */
	public SkillCompanionView getView()
	{
		return view;
	}

	/**
	 * What the account's BANK holds of {@code itemId}: the live bank when it
	 * was seen this login, else Spyglass's own saved bank snapshot; null when
	 * the bank has never been observed on this account.
	 */
	public Long bankQuantity(int itemId)
	{
		Map<Integer, Long> live = liveBank;
		if (live != null)
		{
			return live.getOrDefault(itemId, 0L);
		}
		Map<Integer, Long> saved = savedBank;
		return saved == null ? null : saved.getOrDefault(itemId, 0L);
	}

	/**
	 * ADDED (Skills Phase 6). What an account storage OUTSIDE the bank holds of
	 * a stockpile resource, in its counting unit (a unit family's units, or
	 * items); NOT_APPLICABLE by default (the bank is the whole stock).
	 */
	public SkillExternalStock externalStock(SkillResource resource)
	{
		return SkillExternalStock.NOT_APPLICABLE;
	}

	/** Plugin start (also re-enable): nothing observed while disabled can be trusted. */
	public void startUp()
	{
		tracker.reset();
		lastXp = -1;
		lastEvidenceSentAt = Long.MIN_VALUE;
		lastContextSentAt.clear();
		otherXp.clear();
		otherXpSeeded = false;
		ticksSinceFlush = 0;
		pendingSince = -1L;
	}

	/** Account switch (after the old account's pending was flushed on logout): every number starts over. */
	public void resetForAccountSwitch()
	{
		tracker.reset();
		unbind();
		unboundDeposits.clear();
		pendingSince = -1L;
		lastXp = -1;
		accountHash = -1L;
		lastEvidenceSentAt = Long.MIN_VALUE;
		lastContextSentAt.clear();
		otherXp.clear();
		otherXpSeeded = false;
		liveBank = null;
		savedBank = null;
		savedBankAccount = Long.MIN_VALUE;
		publish(true);
	}

	/** Plugin shutdown: whatever is pending goes to the session now. */
	public void shutDown()
	{
		flush(Instant.ofEpochMilli(clock.getAsLong()), true);
	}

	/**
	 * Where this skill reports fresh evidence of a method that also grants
	 * other skills' XP (see SkillCompanionDefinition#methodNeedsOwnershipEvidence).
	 */
	protected void setMethodEvidenceSink(java.util.function.BiConsumer<String, Instant> sink)
	{
		this.methodEvidenceSink = sink;
	}

	/**
	 * Forwards the tracker's newest method evidence when its method needs
	 * ownership evidence (rate-limited, never per tick). Called right after
	 * each input that can carry such evidence, so it reaches the session
	 * engine before the XP window it explains is resolved.
	 */
	protected void forwardMethodEvidence()
	{
		SkillTracker.MethodEvidence e = tracker.drainMethodEvidence();
		java.util.function.BiConsumer<String, Instant> sink = methodEvidenceSink;
		if (e == null || sink == null || !definition.methodNeedsOwnershipEvidence(e.getMethodKey()))
		{
			return;
		}
		if (lastEvidenceSentAt != Long.MIN_VALUE && e.getAtMillis() - lastEvidenceSentAt < EVIDENCE_INTERVAL_MILLIS
			&& e.getAtMillis() >= lastEvidenceSentAt)
		{
			return;
		}
		lastEvidenceSentAt = e.getAtMillis();
		try
		{
			sink.accept(definition.getSkillKey(), Instant.ofEpochMilli(e.getAtMillis()));
		}
		catch (RuntimeException ex)
		{
			log.debug("Skill companion could not report method evidence", ex);
		}
	}

	/** Where this skill reports other skills' XP that belongs to its own activity (see ContextEvidenceSink). */
	protected void setContextEvidenceSink(ContextEvidenceSink sink)
	{
		this.contextEvidenceSink = sink;
	}

	/**
	 * Forwards the tracker's newest context XP evidence (rate-limited per
	 * other skill, never per tick), right after each input that can carry
	 * it, so it reaches the session engine before the XP window it explains
	 * is resolved.
	 */
	protected void forwardContextEvidence()
	{
		Map<String, Long> evidence = tracker.drainContextEvidence();
		ContextEvidenceSink sink = contextEvidenceSink;
		if (evidence.isEmpty() || sink == null)
		{
			return;
		}
		for (Map.Entry<String, Long> e : evidence.entrySet())
		{
			Long last = lastContextSentAt.get(e.getKey());
			long at = e.getValue();
			if (last != null && at - last < EVIDENCE_INTERVAL_MILLIS && at >= last)
			{
				continue;
			}
			lastContextSentAt.put(e.getKey(), at);
			try
			{
				sink.accept(definition.getSkillKey(), e.getKey(), Instant.ofEpochMilli(at));
			}
			catch (RuntimeException ex)
			{
				log.debug("Skill companion could not report context evidence", ex);
			}
		}
	}

	/** Another container than the inventory / bank changed (e.g. worn equipment). No-op by default. */
	protected void onOtherContainer(int containerId, ItemContainer container)
	{
	}

	/** A menu option the player clicked (tags removed): the skill's own click evidence, if any. */
	protected void onMenuOption(String option, String target, MenuOptionClicked event)
	{
	}

	// ---- RuneLite events ---------------------------------------------------------------

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != definition.getSkill())
		{
			Skill other = event.getSkill();
			if (other == null)
			{
				return;
			}
			Integer before = otherXp.put(other, event.getXp());
			if (before != null && event.getXp() > before)
			{
				tracker.onOtherSkillXp(other.name(), client.getTickCount(), event.getXp() - before, clock.getAsLong());
				forwardContextEvidence();
			}
			return;
		}
		int xp = event.getXp();
		int previous = lastXp;
		lastXp = xp;
		if (previous >= 0 && xp > previous)
		{
			// Where the XP was gained (Agility: a course end tile); a no-op for Mining / Fishing.
			onPlayerPosition(client.getTickCount(), clock.getAsLong(), playerPosition());
			tracker.onSkillXp(client.getTickCount(), xp - previous, clock.getAsLong());
			forwardContextEvidence();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM && type != ChatMessageType.MESBOX)
		{
			return;
		}
		tracker.onChat(client.getTickCount(), clock.getAsLong(), Text.removeTags(event.getMessage()));
		forwardMethodEvidence();
		forwardContextEvidence();
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption();
		if (option != null)
		{
			onMenuOption(option, Text.removeTags(event.getMenuTarget()), event);
			forwardMethodEvidence();
			forwardContextEvidence();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		if (id == InventoryID.INV)
		{
			ItemContainer inv = event.getItemContainer();
			tracker.onInventory(client.getTickCount(), clock.getAsLong(), itemsOf(inv), usedSlots(inv));
		}
		else if (id == InventoryID.BANK)
		{
			Map<Integer, Long> bank = itemsOf(event.getItemContainer());
			liveBank = bank;
			tracker.onBank(client.getTickCount(), clock.getAsLong(), bank);
		}
		else
		{
			onOtherContainer(id, event.getItemContainer());
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			tracker.setBankOpen(true);
		}
		else if (event.getGroupId() == InterfaceID.BANK_DEPOSITBOX)
		{
			tracker.setDepositBoxOpen(true);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			tracker.setBankOpen(false);
		}
		else if (event.getGroupId() == InterfaceID.BANK_DEPOSITBOX)
		{
			tracker.setDepositBoxOpen(false);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			// Settle and send what this login produced while its account and session are still current.
			tracker.settleAll();
			flush(Instant.ofEpochMilli(clock.getAsLong()), true);
			if (state != GameState.CONNECTION_LOST)
			{
				tracker.onLogout();
				lastXp = -1;
				otherXp.clear();
				otherXpSeeded = false;
			}
			publish(true);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		long now = clock.getAsLong();
		accountHash = client.getAccountHash();
		if (lastXp < 0)
		{
			// Enabled mid-game (no login StatChanged): seed the level baseline once, never as a gain.
			int xp = client.getSkillExperience(definition.getSkill());
			if (xp > 0)
			{
				lastXp = xp;
			}
		}
		if (!otherXpSeeded)
		{
			// Enabled mid-game (no login StatChanged): other skills' baselines once, never as gains.
			otherXpSeeded = true;
			for (Skill other : Skill.values())
			{
				if (other != definition.getSkill() && !"OVERALL".equals(other.name()) && !otherXp.containsKey(other))
				{
					try
					{
						int xp = client.getSkillExperience(other);
						if (xp > 0)
						{
							otherXp.put(other, xp);
						}
					}
					catch (RuntimeException e)
					{
						log.debug("Skill companion could not read {} XP", other, e);
					}
				}
			}
		}
		if (!tracker.hasInventoryBaseline())
		{
			// Enabled mid-game: no inventory event has arrived yet, so read it once (baseline only).
			ItemContainer inv = client.getItemContainer(InventoryID.INV);
			if (inv != null)
			{
				tracker.onInventory(client.getTickCount(), now, itemsOf(inv), usedSlots(inv));
			}
		}
		WorldPoint position = playerPosition();
		onPlayerPosition(client.getTickCount(), now, position);
		tracker.onTick(client.getTickCount(), now, position == null ? -1 : position.getRegionID());
		forwardContextEvidence();
		ticksSinceFlush++;
		List<? extends SkillTracker.Deposit> deposits = tracker.drainDeposits();
		if (!deposits.isEmpty())
		{
			unboundDeposits.addAll(deposits);
		}
		if (!unboundDeposits.isEmpty() || ticksSinceFlush >= FLUSH_INTERVAL_TICKS)
		{
			flush(Instant.ofEpochMilli(now), false);
		}
		publish(false);
	}

	// ---- binding + telemetry -------------------------------------------------------------

	/**
	 * Sends pending production to the current session of this skill (one
	 * bounded update), or keeps it waiting for that session -- dropping it
	 * once it is clearly not coming.
	 */
	protected void flush(Instant now, boolean force)
	{
		ticksSinceFlush = 0;
		ProductionLedger ledger = tracker.getLedger();
		boolean hasMillis = !tracker.getMethods().getPendingMillis().isEmpty();
		SkillCycleTracker cycles = tracker.getCycles();
		boolean hasCycles = cycles != null && cycles.hasPending();
		boolean hasOccurrences = !tracker.getPendingOccurrences().isEmpty();
		hasCycles |= hasOccurrences;
		if (!ledger.hasPending() && !hasMillis && !hasCycles && unboundDeposits.isEmpty() && !force)
		{
			return;
		}
		SessionSnapshot session = currentOwnSession();
		if (session == null)
		{
			if (ledger.hasPending() || hasMillis || hasCycles || !unboundDeposits.isEmpty())
			{
				long t = now.toEpochMilli();
				if (pendingSince < 0)
				{
					pendingSince = t;
				}
				else if (t - pendingSince > PENDING_LIMIT_MILLIS)
				{
					// No session of this skill claimed it: the XP belonged to something else.
					ledger.discardPending();
					tracker.getMethods().drainMillis();
					if (cycles != null)
					{
						cycles.discardPending();
					}
					tracker.discardPendingOccurrences();
					unboundDeposits.clear();
					pendingSince = -1L;
				}
			}
			return;
		}
		pendingSince = -1L;
		if (!session.getSessionId().equals(boundSessionId))
		{
			boolean rebind = boundSessionId != null;
			ProductionLedger.HeldMark previous = heldAtLastSend;
			unbind();
			boundSessionId = session.getSessionId();
			if (rebind)
			{
				// A new session: what the previous one left in the inventory is not ours to bank
				// (production since the last send already belongs to the new one and stays).
				ledger.forgetHeld(previous);
				tracker.getMethods().newSession();
			}
		}
		long activeMillis = CurrentSessionSnapshot.from(session, now).getActiveDurationMillis();
		for (SkillTracker.Deposit d : unboundDeposits)
		{
			long[] b = banking.computeIfAbsent(d.getItemId(), k -> new long[3]);
			b[0] += d.getQuantity();
			b[1]++;
			b[2] = activeMillis;
		}
		unboundDeposits.clear();

		ProductionLedger.Drain drain = ledger.drain();
		heldAtLastSend = ledger.markHeld();
		Map<String, Long> millis = tracker.getMethods().drainMillis();
		Map<String, SkillCycleStats> completed = cycles == null ? Collections.<String, SkillCycleStats>emptyMap() : cycles.drain();
		Map<String, Long> occurrences = tracker.drainOccurrences();
		if (drain.isEmpty() && drain.getConsumed().isEmpty() && millis.isEmpty() && completed.isEmpty() && occurrences.isEmpty())
		{
			return;
		}
		drain.getAutoTransformed().forEach((k, v) -> flushedAutoTransformed.merge(k, v, Long::sum));
		drain.getConsumed().forEach((k, v) -> flushedConsumed.merge(k, v, Long::sum));
		occurrences.forEach((k, v) -> flushedOccurrences.merge(k, v, Long::sum));
		drain.getProduced().forEach((k, v) -> flushedProduced.merge(k, v, Long::sum));
		drain.getBanked().forEach((k, v) -> flushedBanked.merge(k, v, Long::sum));
		millis.forEach((k, v) -> flushedMillis.merge(k, v, Long::sum));
		completed.forEach((k, v) -> flushedCycles.merge(k, v, SkillCycleStats::plus));
		EventPayloads.SkillProduction payload = new EventPayloads.SkillProduction(definition.getSkill().name(), tracker.getMethodKey(),
			items(drain.getProduced()), items(drain.getBanked()), millis);
		if (!completed.isEmpty())
		{
			List<EventPayloads.SkillCycle> list = new ArrayList<>();
			completed.forEach((k, v) -> list.add(new EventPayloads.SkillCycle(k, v.getCount(), v.getTotalMillis(), v.getBestMillis(),
				v.getFailures())));
			payload.setCycles(list);
		}
		if (!drain.getAutoTransformed().isEmpty())
		{
			payload.setAutoTransformed(items(drain.getAutoTransformed()));
		}
		if (!occurrences.isEmpty())
		{
			payload.setOccurrences(new LinkedHashMap<>(occurrences));
		}
		if (!drain.getConsumed().isEmpty())
		{
			List<EventPayloads.ProducedItem> used = new ArrayList<>();
			drain.getConsumed().forEach((k, v) ->
			{
				String name = definition.inputName(k);
				if (name != null && v > 0)
				{
					used.add(new EventPayloads.ProducedItem(k, name, (int) Math.min(Integer.MAX_VALUE, v)));
				}
			});
			if (!used.isEmpty())
			{
				payload.setConsumed(used);
			}
		}
		eventLedger.append(accountHash != -1L ? accountHash : client.getAccountHash(), EventType.SKILL_PRODUCTION, payload);
	}

	private SessionSnapshot currentOwnSession()
	{
		SessionSnapshot s;
		try
		{
			s = currentSession.get();
		}
		catch (RuntimeException e)
		{
			log.debug("Skill companion could not read the current session", e);
			return null;
		}
		if (s == null || !s.isPresent() || s.getActivityType() != ActivityType.SKILLING
			|| !definition.getSkillKey().equals(s.getActivityKey()) || s.getState() == SessionState.FINALIZED)
		{
			return null;
		}
		return s;
	}

	private void unbind()
	{
		boundSessionId = null;
		heldAtLastSend = null;
		flushedProduced.clear();
		flushedBanked.clear();
		flushedMillis.clear();
		flushedCycles.clear();
		flushedAutoTransformed.clear();
		flushedConsumed.clear();
		flushedOccurrences.clear();
		banking.clear();
	}

	private List<EventPayloads.ProducedItem> items(Map<Integer, Long> quantities)
	{
		List<EventPayloads.ProducedItem> out = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : quantities.entrySet())
		{
			SkillResource r = definition.resource(e.getKey());
			if (r != null && e.getValue() > 0)
			{
				out.add(new EventPayloads.ProducedItem(e.getKey(), r.getName(), (int) Math.min(Integer.MAX_VALUE, e.getValue())));
			}
		}
		return out;
	}

	// ---- live view ------------------------------------------------------------------------

	/** Rebuilds the immutable view (cheap: a few small maps) and tells the panel when something visible changed. */
	protected void publish(boolean force)
	{
		long now = clock.getAsLong();
		ensureSavedBank();
		Map<Integer, SkillGoalPlanner.BankingSample> samples = new HashMap<>();
		for (Map.Entry<Integer, long[]> e : banking.entrySet())
		{
			long[] b = e.getValue();
			samples.put(e.getKey(), new SkillGoalPlanner.BankingSample(b[0], (int) b[1], b[2]));
		}
		SkillCompanionView built = new SkillCompanionView(definition.getSkillKey(), tracker.getMethodKey(), boundSessionId,
			Collections.unmodifiableMap(new LinkedHashMap<>(flushedProduced)),
			Collections.unmodifiableMap(new LinkedHashMap<>(flushedBanked)),
			Collections.unmodifiableMap(new LinkedHashMap<>(flushedMillis)),
			Collections.unmodifiableMap(new LinkedHashMap<>(tracker.getLedger().getPendingProduced())),
			Collections.unmodifiableMap(new LinkedHashMap<>(tracker.getLedger().getPendingBanked())),
			Collections.unmodifiableMap(new LinkedHashMap<>(tracker.getMethods().getPendingMillis())),
			tracker.trip(now), Collections.unmodifiableList(tracker.context(now)), Collections.unmodifiableMap(samples));
		SkillCompanionView.SupplyView supply = tracker.supply(now);
		if (supply != null)
		{
			built = built.withSupply(supply);
		}
		SkillCycleTracker cycles = tracker.getCycles();
		if (cycles != null)
		{
			built = built.withCycles(tracker.cycle(now), Collections.unmodifiableMap(new LinkedHashMap<>(flushedCycles)),
				Collections.unmodifiableMap(new LinkedHashMap<>(cycles.getPending())));
		}
		SkillCompanionView.FocusView focus = tracker.focus(now);
		SkillCompanionView.EventView event = tracker.event(now);
		if (!flushedAutoTransformed.isEmpty() || !tracker.getLedger().getPendingAutoTransformed().isEmpty() || !flushedOccurrences.isEmpty()
			|| !tracker.getPendingOccurrences().isEmpty() || focus != null || event != null)
		{
			built = built.withExtras(Collections.unmodifiableMap(new LinkedHashMap<>(flushedAutoTransformed)),
				Collections.unmodifiableMap(new LinkedHashMap<>(tracker.getLedger().getPendingAutoTransformed())),
				Collections.unmodifiableMap(new LinkedHashMap<>(flushedOccurrences)),
				Collections.unmodifiableMap(new LinkedHashMap<>(tracker.getPendingOccurrences())), focus, event);
		}
		if (!flushedConsumed.isEmpty() || !tracker.getLedger().getPendingConsumed().isEmpty())
		{
			built = built.withConsumed(Collections.unmodifiableMap(new LinkedHashMap<>(flushedConsumed)),
				Collections.unmodifiableMap(new LinkedHashMap<>(tracker.getLedger().getPendingConsumed())));
		}
		view = built;
		long revision = tracker.getRevision();
		if (force || revision != lastPublishedRevision)
		{
			lastPublishedRevision = revision;
			if (!force && currentOwnSession() == null)
			{
				// Not on screen: the panel shows this companion only inside its own skill's session (which
				// signals the panel itself when it starts). Six idle companions never repaint Slayer.
				return;
			}
			Runnable listener = progressListener;
			if (listener != null)
			{
				try
				{
					listener.run();
				}
				catch (RuntimeException e)
				{
					log.debug("Skill companion progress listener failed", e);
				}
			}
		}
	}

	/** Spyglass's own saved bank, read once per account (stockpile baseline before the bank is opened). */
	private void ensureSavedBank()
	{
		long account;
		try
		{
			account = client.getAccountHash();
		}
		catch (RuntimeException e)
		{
			return;
		}
		if (account == -1L || account == savedBankAccount)
		{
			return;
		}
		savedBankAccount = account;
		savedBank = com.osrstelemetry.plugin.pestcontrol.VoidOwnership.savedBankItems(store, account);
	}

	private WorldPoint playerPosition()
	{
		Player player = client.getLocalPlayer();
		LocalPoint local = player == null ? null : player.getLocalLocation();
		return local == null ? null : WorldPoint.fromLocalInstance(client, local);
	}

	/**
	 * The local player's position this tick (already read for the region;
	 * never a scene scan). A hook for skills whose evidence is WHERE the
	 * player is (Agility: course end tiles, rooftop planes); no-op by default.
	 */
	protected void onPlayerPosition(int tick, long nowMillis, WorldPoint position)
	{
	}

	public static Map<Integer, Long> itemsOf(ItemContainer container)
	{
		Map<Integer, Long> items = new LinkedHashMap<>();
		if (container == null)
		{
			return items;
		}
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				items.merge(item.getId(), (long) item.getQuantity(), Long::sum);
			}
		}
		return items;
	}

	public static int usedSlots(ItemContainer container)
	{
		if (container == null)
		{
			return 0;
		}
		int used = 0;
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				used++;
			}
		}
		return used;
	}

	/** The skill this collector serves (tests). */
	public Skill getSkill()
	{
		return definition.getSkill();
	}
}
