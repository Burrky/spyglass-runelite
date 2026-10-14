package com.osrstelemetry.plugin.skills.mining;

import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.runelite.api.Client;

/** Test access: a real Mining collector for cross-skill tests outside its package. */
public final class MiningCompanionCollectorAccess
{
	private MiningCompanionCollectorAccess()
	{
	}

	public static MiningCompanionCollector create(Client client, EventLedger ledger, Supplier<SessionSnapshot> current, LocalStateStore store,
		LongSupplier clock)
	{
		MiningCompanionCollector c = new MiningCompanionCollector(client, ledger, current, store);
		c.setClock(clock);
		c.startUp();
		return c;
	}
}
