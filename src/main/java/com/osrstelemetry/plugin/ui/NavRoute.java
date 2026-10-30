package com.osrstelemetry.plugin.ui;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where the panel is: a top-level {@link Section} plus at most one child page.
 * Navigation state only -- it never carries run state, a current objective or
 * a sequence cursor (a later Farming panel decides its own stage from its own
 * data). Depth is capped at one by construction: there is no back-stack.
 *
 * Named {@code NavRoute} (not {@code Route}) so it can never be confused with
 * the guidance travel route the architecture plans for later.
 *
 * Persisted form ({@link #serialize()} / {@link #parse(String)}):
 * {@code HOME}, {@code HOME/FARMING}, {@code SESSION}, {@code LOOT},
 * {@code HISTORY}, {@code HISTORY/<sessionId>}. Parsing never throws: a value
 * that is missing or unreadable is Home; a recognised section with a child it
 * cannot have is that section's root.
 */
final class NavRoute
{
	enum Section
	{
		HOME,
		SESSION,
		LOOT,
		HISTORY
	}

	/** The only Home child in this phase. */
	static final String FARMING = "FARMING";
	/** Farming's settings: a child of Farming (its "‹" goes back to Farming). */
	static final String FARMING_SETTINGS = "FARMING_SETTINGS";
	static final String KINGDOM = "KINGDOM";
	static final String DAILIES = "DAILIES";
	/** Bird houses: a child of Home (its own Home card, pass 30; "‹" goes back to Home). */
	static final String BIRDHOUSES = "BIRDHOUSES";
	/** Text size &amp; contrast (pass 30): a child of Home. */
	static final String APPEARANCE = "APPEARANCE";

	/** History detail ids are session UUIDs; anything else is rejected rather than trusted. */
	private static final Pattern HISTORY_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

	static final NavRoute HOME = new NavRoute(Section.HOME, null);

	private final Section section;
	private final String child;

	private NavRoute(Section section, String child)
	{
		this.section = section;
		this.child = child;
	}

	static NavRoute root(Section section)
	{
		return section == Section.HOME ? HOME : new NavRoute(section, null);
	}

	/** {@code section}'s page {@code child}, or the section root when that child is not valid there. */
	static NavRoute of(Section section, String child)
	{
		return isValidChild(section, child) ? new NavRoute(section, child) : root(section);
	}

	static boolean isValidChild(Section section, String child)
	{
		if (section == null || child == null)
		{
			return false;
		}
		switch (section)
		{
			case HOME:
				return FARMING.equals(child) || FARMING_SETTINGS.equals(child) || KINGDOM.equals(child) || DAILIES.equals(child)
					|| BIRDHOUSES.equals(child) || APPEARANCE.equals(child);
			case HISTORY:
				return HISTORY_ID.matcher(child).matches();
			default:
				return false;
		}
	}

	Section getSection()
	{
		return section;
	}

	/** The child page, or null at the section root. */
	String getChild()
	{
		return child;
	}

	/** The route a "‹" goes up to: Farming settings → Farming, any other child → its section's root. */
	NavRoute parent()
	{
		if (section == Section.HOME && FARMING_SETTINGS.equals(child))
		{
			return new NavRoute(Section.HOME, FARMING);
		}
		return root(section);
	}

	boolean isRoot()
	{
		return child == null;
	}

	String serialize()
	{
		return child == null ? section.name() : section.name() + "/" + child;
	}

	/** Never throws: null / blank / unknown section is Home; an invalid child is that section's root. */
	static NavRoute parse(String value)
	{
		if (value == null)
		{
			return HOME;
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty())
		{
			return HOME;
		}
		int slash = trimmed.indexOf('/');
		String sectionName = slash < 0 ? trimmed : trimmed.substring(0, slash);
		Section section;
		try
		{
			section = Section.valueOf(sectionName);
		}
		catch (IllegalArgumentException e)
		{
			return HOME;
		}
		return slash < 0 ? root(section) : of(section, trimmed.substring(slash + 1));
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof NavRoute))
		{
			return false;
		}
		NavRoute other = (NavRoute) o;
		return section == other.section && Objects.equals(child, other.child);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(section, child);
	}

	@Override
	public String toString()
	{
		return serialize();
	}
}
