package com.osrstelemetry.plugin.ui;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * The panel's navigation rules, with no Swing in it (so every rule is unit
 * tested). Parent navigation only, never a back-stack:
 *
 * <ul>
 *   <li>House: always Home root, from anywhere (including Farming).</li>
 *   <li>An inactive section: that section, restored to its last valid child.</li>
 *   <li>The already-active section: that section's root.</li>
 *   <li>Opening a child (Farming card, a History row): that child.</li>
 *   <li>Up ("‹ Home"): the current section's root.</li>
 * </ul>
 *
 * Login and elapsed time never move the route; only these calls do.
 * EDT-confined, like the rest of the panel.
 */
final class Navigator
{
	/** What to do with a History detail id that should be on screen. */
	enum HistoryDetailDecision
	{
		/** The current account's History has the session: show it. */
		OPEN,
		/** History has not been scanned for the current account yet: keep the id, show the list. */
		WAIT,
		/** The current account's (fully scanned) History does not have it: fall back to the list. */
		DROP
	}

	private NavRoute current = NavRoute.HOME;
	/** Each section's last open child, restored when the section is re-entered. */
	private final Map<NavRoute.Section, String> lastChild = new EnumMap<>(NavRoute.Section.class);

	NavRoute current()
	{
		return current;
	}

	/** The child a section would re-open, or null. */
	String lastChild(NavRoute.Section section)
	{
		return lastChild.get(section);
	}

	/** Startup restore (a saved route). */
	NavRoute restore(NavRoute route)
	{
		lastChild.clear();
		return moveTo(route == null ? NavRoute.HOME : route);
	}

	NavRoute house()
	{
		lastChild.remove(NavRoute.Section.HOME);
		return moveTo(NavRoute.HOME);
	}

	NavRoute selectSection(NavRoute.Section section)
	{
		if (section == NavRoute.Section.HOME)
		{
			return house();
		}
		if (current.getSection() == section)
		{
			lastChild.remove(section);
			return moveTo(NavRoute.root(section));
		}
		return moveTo(NavRoute.of(section, lastChild.get(section)));
	}

	NavRoute openChild(NavRoute.Section section, String child)
	{
		return moveTo(NavRoute.of(section, child));
	}

	/** "‹ Home": up to the current section's root. */
	NavRoute toParent()
	{
		NavRoute parent = current.parent();
		lastChild.remove(current.getSection());
		return moveTo(parent);
	}

	/**
	 * A section's child page went away on its own (History Back, or its
	 * session left the current account's list). Forgets it; if that section
	 * is on screen, the route becomes its root.
	 */
	NavRoute childClosed(NavRoute.Section section)
	{
		lastChild.remove(section);
		if (current.getSection() == section && !current.isRoot())
		{
			return moveTo(NavRoute.root(section));
		}
		return current;
	}

	private NavRoute moveTo(NavRoute route)
	{
		current = route;
		if (!route.isRoot())
		{
			lastChild.put(route.getSection(), route.getChild());
		}
		return current;
	}

	/**
	 * Whether a saved History detail may be shown. {@code knownIds} is the
	 * CURRENT account's History, which is only meaningful once
	 * {@code scanComplete}: before that the id is kept (not discarded just
	 * because History has not loaded), and nothing is shown. After it, an id
	 * that is not this account's is dropped, so another account's detail can
	 * never appear.
	 */
	static HistoryDetailDecision decideHistoryDetail(String sessionId, boolean scanComplete, Collection<String> knownIds)
	{
		if (!scanComplete)
		{
			return HistoryDetailDecision.WAIT;
		}
		return sessionId != null && knownIds != null && knownIds.contains(sessionId)
			? HistoryDetailDecision.OPEN
			: HistoryDetailDecision.DROP;
	}
}
