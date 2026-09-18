package com.osrstelemetry.plugin.model;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * RuneLite's net.runelite.api.Quest enum exposes a stable numeric id
 * (@Getter int id) and a proper display name (@Getter String name).
 * quest.name() (the Java enum constant name, e.g.
 * DESERT_TREASURE_II__THE_FALLEN_EMPIRE) is kept as "key" since it's a
 * convenient stable map key, but it is NOT the user-facing name — id
 * is authoritative, and name ("Desert Treasure II - The Fallen
 * Empire") is what a human or an AI summary should ever display.
 */
@Data
public class QuestsState
{
	@Data
	public static class QuestEntry
	{
		private final int id;
		private final String key;
		private final String name;
		private final String state;
	}

	private final Map<String, QuestEntry> questsByKey = new LinkedHashMap<>();
	private String lastUpdated;
}
