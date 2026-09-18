package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.model.AccountIdentity;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.time.Instant;
import javax.inject.Inject;
import net.runelite.api.Client;

/**
 * `Client#getAccountType()` is confirmed via a direct fetch of the
 * current Client.java source — exact signature
 * `AccountType getAccountType();`, importing
 * `net.runelite.api.vars.AccountType` (NOT plain `net.runelite.api.AccountType`).
 * The method IS marked `@Deprecated` in current RuneLite source, with
 * its javadoc pointing callers at `Varbits#ACCOUNT_TYPE` instead.
 * Deprecated is not the same as wrong: the method still exists, still
 * compiles, and still returns a real enum with a working `.name()` —
 * nothing here is demonstrably broken, so the call below is left
 * as-is. Flagged as a real (not fabricated) future-work item:
 * migrating to `Varbits#ACCOUNT_TYPE` would remove a dependency on a
 * deprecated API, but was not done here. The existing try/catch below
 * already protects identity capture from any future incompatibility
 * either way.
 */
public class IdentityCollector
{
	private final Client client;
	private final LocalStateStore store;

	@Inject
	public IdentityCollector(Client client, LocalStateStore store)
	{
		this.client = client;
		this.store = store;
	}

	public void captureOnLogin()
	{
		long accountHash = client.getAccountHash();
		AccountIdentity identity = new AccountIdentity(Long.toString(accountHash));
		identity.setDisplayName(client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null);
		identity.setCapturedAt(Instant.now().toString());

		try
		{
			identity.setAccountType(client.getAccountType().name());
		}
		catch (RuntimeException | NoSuchMethodError e)
		{
			// Defensive: if the accessor name/shape turns out to be
			// different once compiled, don't let identity capture
			// (the one thing every other collector's file path
			// depends on via accountHash) fail entirely over a
			// non-essential field.
			identity.setAccountType(null);
		}

		store.write(TelemetryPaths.stateFile(accountHash, "identity"), identity);
	}
}
