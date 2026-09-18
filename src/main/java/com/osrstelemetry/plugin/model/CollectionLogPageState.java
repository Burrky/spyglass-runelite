package com.osrstelemetry.plugin.model;

import java.util.List;
import lombok.Data;

/**
 * One document per collection-log page. A page with no document on
 * disk at all means "never opened" — that is a distinct state from
 * "opened and empty," and callers must be able to tell them apart.
 * Do not synthesize a document with obtained=false for unseen pages.
 */
@Data
public class CollectionLogPageState
{
	@Data
	public static class LogItem
	{
		private final int itemId;
		private final String name;
		private final boolean obtained;
		private final int quantity;
	}

	private final String category;
	private final String page;
	private List<LogItem> items;
	private String lastObservedAt;
}
