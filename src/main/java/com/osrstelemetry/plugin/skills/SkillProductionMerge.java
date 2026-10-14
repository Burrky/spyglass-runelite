package com.osrstelemetry.plugin.skills;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure merge of a session's DURABLE skill numbers with the companion's live
 * ones (see {@link SkillCompanionView}). Per key:
 * <pre>
 *   bound to this session:  max(session, flushed) + pending
 *   not bound (yet):        session + pending
 * </pre>
 * max() covers both hand-off directions: the one tick between "sent" and
 * "applied" (flushed is ahead), and a client restart mid-session (the
 * companion's flushed memory is empty, the session is ahead). Neither ever
 * double counts.
 */
public final class SkillProductionMerge
{
	private SkillProductionMerge()
	{
	}

	public static <K> Map<K, Long> merge(Map<K, Long> session, Map<K, Long> flushed, Map<K, Long> pending, boolean bound)
	{
		Map<K, Long> out = new LinkedHashMap<>();
		if (session != null)
		{
			for (Map.Entry<K, Long> e : session.entrySet())
			{
				if (e.getValue() != null && e.getValue() > 0)
				{
					out.put(e.getKey(), e.getValue());
				}
			}
		}
		if (bound && flushed != null)
		{
			for (Map.Entry<K, Long> e : flushed.entrySet())
			{
				if (e.getValue() != null && e.getValue() > 0)
				{
					out.merge(e.getKey(), e.getValue(), Math::max);
				}
			}
		}
		if (pending != null)
		{
			for (Map.Entry<K, Long> e : pending.entrySet())
			{
				if (e.getValue() != null && e.getValue() > 0)
				{
					out.merge(e.getKey(), e.getValue(), Long::sum);
				}
			}
		}
		return out;
	}

	/** Whether a view is bound to this session id. */
	public static boolean bound(SkillCompanionView view, String sessionId)
	{
		return view != null && sessionId != null && Objects.equals(view.getBoundSessionId(), sessionId);
	}
}
