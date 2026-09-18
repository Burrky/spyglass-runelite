package com.osrstelemetry.plugin.model;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * Skills are always live (StatChanged fires on every change), so this
 * document carries a single lastUpdated rather than per-skill freshness.
 */
@Data
public class SkillsState
{
	@Data
	public static class SkillEntry
	{
		private final int level;
		private final int boostedLevel;
		private final int xp;
	}

	private final Map<String, SkillEntry> skills = new LinkedHashMap<>();
	private String lastUpdated;
}
