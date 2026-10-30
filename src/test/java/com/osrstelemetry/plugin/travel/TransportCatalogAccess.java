package com.osrstelemetry.plugin.travel;

import java.util.List;

/** Test access to the catalogue's entrance pruning rule. */
public final class TransportCatalogAccess
{
	private TransportCatalogAccess()
	{
	}

	public static boolean dominated(Object[] c, List<Object[]> all)
	{
		return TransportCatalog.dominated(c, all);
	}
}
