package com.osrstelemetry.plugin.skills.woodcutting;

import com.osrstelemetry.plugin.skills.SkillCompanionView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FORESTRY as CONTEXT of the Woodcutting session (pure; client thread only).
 *
 * <h3>When an event runs</h3>
 * An event runs while at least one of the NPCs / objects only it spawns is
 * in the scene (ForestryEvent ids), and ends {@link #END_GRACE_MILLIS} after
 * the last of them left (so an NPC changing form, or a scene reload, never
 * splits one event in two). As a safety net nothing runs longer than
 * {@link #MAX_EVENT_MILLIS} without a sign of it.
 *
 * <h3>Taking part</h3>
 * The player took part when the game awarded anima-infused bark during the
 * event, or the event's own other-skill XP was gained (Hunter during
 * Poachers, ...). Only then does the ended event count once (per kind, an
 * OCCURRENCE in the session's telemetry). Standing next to an event never
 * counts.
 *
 * <h3>What the panel sees</h3>
 * The most recently started running event: its name, time, the bark the
 * player was awarded during it, the one count its XP proves one-for-one
 * (traps, eggs) and a few native lines (anima root up, mulch order learned,
 * sapling saved / withering, fox lost, not eligible for full rewards).
 * Nothing is estimated.
 */
public final class ForestryContext
{
	static final long END_GRACE_MILLIS = 3_000L;
	static final long MAX_EVENT_MILLIS = 6L * 60_000L;
	/** Leaves handed out by a sapling / ent event arrive at its end, or just after. */
	static final long LEAF_WINDOW_MILLIS = 30_000L;
	/** A Friendly Ent's egg nest is dropped at its end; picking it up can take a while. */
	static final long ENT_NEST_WINDOW_MILLIS = 90_000L;

	/** "The sapling seems to love Wild mushrooms as the first ingredient" (exact identity of the slot). */
	private static final Pattern SAPLING_LOVES = Pattern.compile(
		"^The sapling seems to love (.+?) as the (first|second|third) ingredient", Pattern.CASE_INSENSITIVE);
	static final String SAPLING_WITHERS = "The sapling withers due to a lack of nutrients";
	static final String POACHERS_FAILED = "Unfortunately you weren't able to save the fox from the poachers";
	static final String NOT_ELIGIBLE = "As you weren't chopping near the event when it started, you aren't eligible for full rewards";

	private static final class Running
	{
		final ForestryEvent kind;
		long startedAt;
		long lastSeenAt;
		int present;
		boolean participated;
		long bark;
		long counter;
		boolean specialPresent;
		boolean saved;
		boolean withering;
		boolean failed;
		boolean notEligible;
		final String[] mulch = new String[3];

		Running(ForestryEvent kind, long now)
		{
			this.kind = kind;
			this.startedAt = now;
			this.lastSeenAt = now;
		}
	}

	private final Map<ForestryEvent, Running> running = new EnumMap<>(ForestryEvent.class);
	private final Map<String, Long> pendingOccurrences = new LinkedHashMap<>();
	private final Map<ForestryEvent, Long> lastEndedAt = new EnumMap<>(ForestryEvent.class);
	private final Map<ForestryEvent, Boolean> lastParticipated = new EnumMap<>(ForestryEvent.class);
	private long revision;

	// ---- inputs --------------------------------------------------------------------------------

	public void npcSpawned(int npcId, long now)
	{
		ForestryEvent e = ForestryEvent.forNpc(npcId);
		if (e != null)
		{
			appeared(e, now, false);
		}
	}

	public void npcDespawned(int npcId, long now)
	{
		ForestryEvent e = ForestryEvent.forNpc(npcId);
		if (e != null)
		{
			disappeared(e, now, false);
		}
	}

	public void objectSpawned(int objectId, long now)
	{
		ForestryEvent e = ForestryEvent.forObject(objectId);
		if (e != null)
		{
			appeared(e, now, objectId == net.runelite.api.gameval.ObjectID.GATHERING_EVENT_RISING_ROOTS_SPECIAL);
			Running r = running.get(e);
			if (r != null && e == ForestryEvent.STRUGGLING_SAPLING)
			{
				if (isSaved(objectId))
				{
					r.saved = true;
					r.withering = false;
				}
				else if (isWithering(objectId))
				{
					r.withering = true;
				}
			}
		}
	}

	public void objectDespawned(int objectId, long now)
	{
		ForestryEvent e = ForestryEvent.forObject(objectId);
		if (e != null)
		{
			disappeared(e, now, objectId == net.runelite.api.gameval.ObjectID.GATHERING_EVENT_RISING_ROOTS_SPECIAL);
		}
	}

	private static boolean isSaved(int id)
	{
		return id == net.runelite.api.gameval.ObjectID.GATHERING_EVENT_SAPLING_SAVED_1X1
			|| id == net.runelite.api.gameval.ObjectID.GATHERING_EVENT_SAPLING_SAVED_2X2
			|| id == net.runelite.api.gameval.ObjectID.GATHERING_EVENT_SAPLING_SAVED_3X3
			|| id == net.runelite.api.gameval.ObjectID.GATHERING_EVENT_SAPLING_SAVED_4X4;
	}

	private static boolean isWithering(int id)
	{
		return id == net.runelite.api.gameval.ObjectID.GATHERING_EVENT_SAPLING_WITHERING_1X1
			|| id == net.runelite.api.gameval.ObjectID.GATHERING_EVENT_SAPLING_WITHERING_2X2
			|| id == net.runelite.api.gameval.ObjectID.GATHERING_EVENT_SAPLING_WITHERING_3X3
			|| id == net.runelite.api.gameval.ObjectID.GATHERING_EVENT_SAPLING_WITHERING_4X4;
	}

	private void appeared(ForestryEvent e, long now, boolean special)
	{
		Running r = running.get(e);
		if (r == null)
		{
			r = new Running(e, now);
			running.put(e, r);
		}
		r.present++;
		r.lastSeenAt = now;
		if (special)
		{
			r.specialPresent = true;
		}
		revision++;
	}

	private void disappeared(ForestryEvent e, long now, boolean special)
	{
		Running r = running.get(e);
		if (r == null)
		{
			return;
		}
		r.present = Math.max(0, r.present - 1);
		r.lastSeenAt = now;
		if (special)
		{
			r.specialPresent = false;
		}
		revision++;
	}

	/** The scene is being reloaded: every entity will spawn again if it is still there (the event keeps running meanwhile). */
	public void sceneReloading(long now)
	{
		for (Running r : running.values())
		{
			r.present = 0;
			r.specialPresent = false;
			r.lastSeenAt = now;
		}
	}

	/** Anima-infused bark the game awarded (a running event's own reward: the player takes part). */
	public void barkAwarded(long quantity, long now)
	{
		Running r = mostRecent(now, true);
		if (r != null)
		{
			r.bark += Math.max(0L, quantity);
			r.participated = true;
			revision++;
		}
	}

	/** Another skill's XP: the running event that hands it out counts it (and the player takes part). */
	public void otherSkillXp(String skillName, long now)
	{
		if (skillName == null)
		{
			return;
		}
		for (Running r : running.values())
		{
			if (skillName.equalsIgnoreCase(r.kind.getXpSkill()) && isRunning(r, now))
			{
				r.participated = true;
				if (r.kind.getCounterCaption() != null)
				{
					r.counter++;
				}
				revision++;
			}
		}
	}

	/** A game message: event-specific lines only. Returns true when it was one of them. */
	public boolean onChat(String message, long now)
	{
		if (message == null)
		{
			return false;
		}
		String m = message.trim();
		Matcher love = SAPLING_LOVES.matcher(m);
		if (love.find())
		{
			Running r = runningOf(ForestryEvent.STRUGGLING_SAPLING, now);
			if (r != null)
			{
				int slot = "first".equalsIgnoreCase(love.group(2)) ? 0 : "second".equalsIgnoreCase(love.group(2)) ? 1 : 2;
				r.mulch[slot] = shortIngredient(love.group(1));
				revision++;
			}
			return true;
		}
		if (m.startsWith(SAPLING_WITHERS))
		{
			Running r = runningOf(ForestryEvent.STRUGGLING_SAPLING, now);
			if (r != null)
			{
				r.withering = true;
				r.failed = true;
				revision++;
			}
			return true;
		}
		if (m.startsWith(POACHERS_FAILED))
		{
			Running r = runningOf(ForestryEvent.POACHERS, now);
			if (r != null)
			{
				r.failed = true;
				revision++;
			}
			return true;
		}
		if (m.startsWith(NOT_ELIGIBLE))
		{
			Running r = mostRecent(now, false);
			if (r != null)
			{
				r.notEligible = true;
				revision++;
			}
			return true;
		}
		return false;
	}

	private static String shortIngredient(String s)
	{
		String t = s.trim();
		return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1).toLowerCase(Locale.ROOT);
	}

	/** Ends events whose entities are gone (after the grace), counting the ones the player took part in. */
	public void step(long now)
	{
		List<ForestryEvent> ended = new ArrayList<>();
		for (Running r : running.values())
		{
			boolean gone = r.present == 0 && now - r.lastSeenAt >= END_GRACE_MILLIS;
			boolean stale = now - r.startedAt > MAX_EVENT_MILLIS && now - r.lastSeenAt > MAX_EVENT_MILLIS / 2;
			if (gone || stale)
			{
				ended.add(r.kind);
			}
		}
		for (ForestryEvent e : ended)
		{
			Running r = running.remove(e);
			lastEndedAt.put(e, now);
			lastParticipated.put(e, r.participated);
			if (r.participated)
			{
				pendingOccurrences.merge(e.occurrenceKey(), 1L, Long::sum);
			}
			revision++;
		}
	}

	// ---- outputs -------------------------------------------------------------------------------

	private static boolean isRunning(Running r, long now)
	{
		return r.present > 0 || now - r.lastSeenAt < END_GRACE_MILLIS;
	}

	private Running runningOf(ForestryEvent e, long now)
	{
		Running r = running.get(e);
		return r != null && isRunning(r, now) ? r : null;
	}

	/** The most recently started running event (or, when {@code includeEnding}, one still in its end grace). */
	private Running mostRecent(long now, boolean includeEnding)
	{
		Running best = null;
		for (Running r : running.values())
		{
			if ((includeEnding || r.present > 0 || isRunning(r, now)) && (best == null || r.startedAt > best.startedAt))
			{
				best = r;
			}
		}
		return best;
	}

	public boolean isAnyRunning(long now)
	{
		for (Running r : running.values())
		{
			if (isRunning(r, now))
			{
				return true;
			}
		}
		return false;
	}

	/** The other skills whose XP the running events hand out right now (ownership evidence). */
	public Set<String> contextSkills(long now)
	{
		Set<String> out = new LinkedHashSet<>();
		for (Running r : running.values())
		{
			if (r.kind.getXpSkill() != null && isRunning(r, now))
			{
				out.add(r.kind.getXpSkill());
			}
		}
		return out;
	}

	/** Whether leaves gained now are an event's reward (a sapling or ent event running or just ended). */
	public boolean leafRewardWindow(long now)
	{
		return within(ForestryEvent.STRUGGLING_SAPLING, now, LEAF_WINDOW_MILLIS) || within(ForestryEvent.FRIENDLY_ENT, now, LEAF_WINDOW_MILLIS);
	}

	/** Whether a bird's nest picked up now may be a Friendly Ent's end reward (the player took part in it). */
	public boolean entNestWindow(long now)
	{
		Running r = running.get(ForestryEvent.FRIENDLY_ENT);
		if (r != null && r.participated)
		{
			return true;
		}
		Long ended = lastEndedAt.get(ForestryEvent.FRIENDLY_ENT);
		return ended != null && now - ended <= ENT_NEST_WINDOW_MILLIS && Boolean.TRUE.equals(lastParticipated.get(ForestryEvent.FRIENDLY_ENT));
	}

	private boolean within(ForestryEvent e, long now, long window)
	{
		if (running.containsKey(e))
		{
			return true;
		}
		Long ended = lastEndedAt.get(e);
		return ended != null && now - ended <= window;
	}

	/** FORESTRY EVENT for the panel: the most recently started running event, or null. */
	public SkillCompanionView.EventView view(long now)
	{
		Running r = null;
		for (Running x : running.values())
		{
			if (isRunning(x, now) && (r == null || x.startedAt > r.startedAt))
			{
				r = x;
			}
		}
		if (r == null)
		{
			return null;
		}
		List<SkillCompanionView.EventView.Field> fields = new ArrayList<>();
		fields.add(new SkillCompanionView.EventView.Field("BARK", r.bark > 0 ? String.format(Locale.ROOT, "%,d", r.bark) : "0",
			"Anima-infused bark the game awarded you during this event"));
		if (r.kind.getCounterCaption() != null)
		{
			fields.add(new SkillCompanionView.EventView.Field(r.kind.getCounterCaption(), String.format(Locale.ROOT, "%,d", r.counter),
				r.kind == ForestryEvent.POACHERS ? "Traps you disarmed (each one gives Hunter XP)"
					: "Pheasant eggs you delivered (each one gives Thieving XP)"));
		}
		List<SkillCompanionView.ContextLine> lines = new ArrayList<>();
		if (r.kind == ForestryEvent.RISING_ROOTS && r.specialPresent)
		{
			lines.add(new SkillCompanionView.ContextLine("Anima-infused root is up", SkillCompanionView.ContextLine.Tone.ALERT,
				"The green-trimmed root gives more XP and bark than the others"));
		}
		if (r.kind == ForestryEvent.STRUGGLING_SAPLING)
		{
			if (r.mulch[0] != null || r.mulch[1] != null || r.mulch[2] != null)
			{
				StringBuilder sb = new StringBuilder("Mulch: ");
				for (int i = 0; i < 3; i++)
				{
					sb.append(i == 0 ? "" : " • ").append(i + 1).append(' ').append(r.mulch[i] == null ? "?" : r.mulch[i]);
				}
				lines.add(new SkillCompanionView.ContextLine(sb.toString(), SkillCompanionView.ContextLine.Tone.NORMAL,
					"The ingredient order the sapling told you (from its own messages)"));
			}
			if (r.saved)
			{
				lines.add(new SkillCompanionView.ContextLine("Sapling saved", SkillCompanionView.ContextLine.Tone.QUIET,
					"The sapling is thriving"));
			}
			else if (r.withering)
			{
				lines.add(new SkillCompanionView.ContextLine("Sapling withering", SkillCompanionView.ContextLine.Tone.ALERT,
					"The sapling is running out of nutrients"));
			}
		}
		if (r.kind == ForestryEvent.POACHERS && r.failed)
		{
			lines.add(new SkillCompanionView.ContextLine("Fox lost to the poachers", SkillCompanionView.ContextLine.Tone.ALERT, null));
		}
		if (r.notEligible)
		{
			lines.add(new SkillCompanionView.ContextLine("Not eligible for full rewards", SkillCompanionView.ContextLine.Tone.ALERT,
				"You weren't chopping near the event when it started (the game's own message)"));
		}
		return new SkillCompanionView.EventView(r.kind.name().toLowerCase(Locale.ROOT), r.kind.getLabel(), Math.max(0L, now - r.startedAt),
			fields, lines);
	}

	public Map<String, Long> getPendingOccurrences()
	{
		return Collections.unmodifiableMap(pendingOccurrences);
	}

	public Map<String, Long> drainOccurrences()
	{
		if (pendingOccurrences.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<String, Long> out = new LinkedHashMap<>(pendingOccurrences);
		pendingOccurrences.clear();
		return out;
	}

	public void discardPendingOccurrences()
	{
		pendingOccurrences.clear();
	}

	public long getRevision()
	{
		return revision;
	}

	/** Logout / hop: every entity is gone; nothing running carries over (pending occurrences stay for the flush). */
	public void onLogout()
	{
		running.clear();
		lastEndedAt.clear();
		lastParticipated.clear();
		revision++;
	}

	public void reset()
	{
		onLogout();
		pendingOccurrences.clear();
	}
}
