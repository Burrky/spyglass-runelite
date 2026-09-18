package com.osrstelemetry.plugin.model;

import lombok.Data;

/**
 * Identity document. accountHash is the stable primary key (survives
 * display-name changes) — see net.runelite.api.Client#getAccountHash().
 * displayName is mutable and carried only for human readability.
 */
@Data
public class AccountIdentity
{
	private final String accountHash;
	private String displayName;
	private String accountType;
	private final int schemaVersion = 1;
	private String capturedAt;
}
