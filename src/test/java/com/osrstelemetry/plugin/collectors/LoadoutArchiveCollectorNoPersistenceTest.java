package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.session.LoadoutResolver;
import java.lang.reflect.Field;
import org.junit.Test;

/**
 * A regression-proof, automatable version of the
 * audit's "no duplicate persisted telemetry" claim (see the project
 * doc claude/history-loadout-capture-path-audit.md) -- rather than
 * relying solely on a one-time manual reading of
 * LoadoutArchiveCollector/LoadoutArchive's source, this test asserts
 * by reflection that NEITHER class (nor any superclass) ever declares
 * a field of a persistence/event-emission type. If a future edit ever
 * wired either class to write telemetry, this test fails immediately.
 *
 * Deliberately checks by FIELD TYPE NAME rather than importing
 * LocalStateStore/EventLedger directly -- this keeps the test honest
 * about what it is actually proving (no reference of that type is
 * held anywhere in the object graph of either class), and avoids the
 * test itself needing to construct either dependency.
 */
public class LoadoutArchiveCollectorNoPersistenceTest
{
	private static final String[] FORBIDDEN_FIELD_TYPE_SIMPLE_NAMES = {
		"LocalStateStore",
		"EventLedger",
	};

	@Test
	public void loadoutArchiveCollector_declaresNoPersistenceOrEventLedgerField()
	{
		assertNoForbiddenFields(LoadoutArchiveCollector.class);
	}

	@Test
	public void loadoutArchive_declaresNoPersistenceOrEventLedgerField()
	{
		assertNoForbiddenFields(LoadoutArchive.class);
	}

	@Test
	public void loadoutArchive_entry_declaresNoPersistenceOrEventLedgerField()
	{
		assertNoForbiddenFields(LoadoutArchive.Entry.class);
	}

	/**
	 * Companion check (not part of the six audit points, but the same
	 * spirit): LoadoutResolver -- the read side of this same capture
	 * path, owned by session.SessionPersistence -- also never writes
	 * telemetry; it only ever reads from the archive it is given.
	 */
	@Test
	public void loadoutResolver_declaresNoPersistenceOrEventLedgerField()
	{
		assertNoForbiddenFields(LoadoutResolver.class);
	}

	private static void assertNoForbiddenFields(Class<?> type)
	{
		Class<?> current = type;
		while (current != null && current != Object.class)
		{
			for (Field field : current.getDeclaredFields())
			{
				String simpleName = field.getType().getSimpleName();
				for (String forbidden : FORBIDDEN_FIELD_TYPE_SIMPLE_NAMES)
				{
					assertTrue(
						"class " + type.getName() + " (via " + current.getName() + ") must never hold a "
							+ forbidden + " field -- found " + field,
						!simpleName.equals(forbidden));
				}
			}
			current = current.getSuperclass();
		}
	}
}
