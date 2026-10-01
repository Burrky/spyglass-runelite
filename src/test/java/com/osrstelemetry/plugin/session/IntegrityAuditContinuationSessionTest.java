package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

/**
 * Integrity audit continuation (session side): a cancelled task followed by
 * the SAME task is two instances; a corrupt saved state never leaves the
 * previous account's session live or aborts the account load.
 */
public class IntegrityAuditContinuationSessionTest extends SlayerCoordinatorTestSupport
{
	private static List<SessionAggregates.SlayerAssignmentRecord> ledger(Session s)
	{
		return s.getAggregates().getSlayerAssignments();
	}

	private Instant nechryaelTask(Instant start, int amount, int kills)
	{
		feed(SessionSignal.slayerTaskAssigned(start, NECH, NECH_LOC, amount), start);
		Instant t = start;
		int remaining = amount;
		for (int i = 0; i < kills; i++)
		{
			t = t.plusSeconds(60);
			remaining--;
			feed(SessionSignal.slayerTaskProgress(t, NECH, NECH_LOC, 1, remaining), t);
		}
		return t;
	}

	@Test
	public void cancelledTaskThenTheSameTaskAgain_isANewInstance_andThePriorIsSkipped()
	{
		Instant t = nechryaelTask(T0, 75, 3);
		feed(SessionSignal.slayerTaskCompleted(t.plusSeconds(10), NECH, null, true), t.plusSeconds(10));
		feed(SessionSignal.slayerTaskAssigned(t.plusSeconds(40), NECH, NECH_LOC, 120), t.plusSeconds(40));

		List<SessionAggregates.SlayerAssignmentRecord> records = ledger(current());
		assertEquals("same task after a cancel is a second instance", 2, records.size());
		assertEquals(SlayerAssignmentEndReason.REPLACED_EARLY, records.get(0).getEndReason());
		assertEquals(Integer.valueOf(3), records.get(0).getUnitsCompleted());
		assertEquals(Integer.valueOf(120), records.get(1).getInitialAmount());
		assertEquals(Integer.valueOf(120), records.get(1).getLatestRemaining());
		assertEquals(Integer.valueOf(0), records.get(1).getUnitsCompleted());
	}

	@Test
	public void duplicateAssignedObservationWithoutACancel_isStillIdempotent()
	{
		Instant t = nechryaelTask(T0, 75, 3);
		feed(SessionSignal.slayerTaskAssigned(t.plusSeconds(5), NECH, NECH_LOC, 72), t.plusSeconds(5));
		assertEquals(1, ledger(current()).size());
		assertEquals(Integer.valueOf(3), ledger(current()).get(0).getUnitsCompleted());
	}

	@Test
	public void cancelFlagDoesNotLeakPastTheAssignmentItPrecedes()
	{
		Instant t = nechryaelTask(T0, 75, 3);
		feed(SessionSignal.slayerTaskCompleted(t.plusSeconds(10), NECH, null, true), t.plusSeconds(10));
		feed(SessionSignal.slayerTaskAssigned(t.plusSeconds(40), NECH, NECH_LOC, 120), t.plusSeconds(40));
		feed(SessionSignal.slayerTaskProgress(t.plusSeconds(100), NECH, NECH_LOC, 1, 119), t.plusSeconds(100));
		// A repeated observation of the new assignment is a duplicate again.
		feed(SessionSignal.slayerTaskAssigned(t.plusSeconds(110), NECH, NECH_LOC, 119), t.plusSeconds(110));
		assertEquals(2, ledger(current()).size());
		assertEquals(Integer.valueOf(1), ledger(current()).get(1).getUnitsCompleted());
	}

	@Test
	public void corruptSavedStateForTheNextAccount_neverKeepsThePreviousAccountsSessionLive() throws Exception
	{
		nechryaelTask(T0, 75, 2);
		assertNotNull(current());
		long other = ACCOUNT + 1;
		Path file = TestFilepaths.path(TelemetryPaths.sessionStateFile(other));
		Files.createDirectories(file.getParent());
		String corrupt = "{\"sessionId\":\"broken\",\"state\":\"ACTIVE\"}";
		Files.write(file, corrupt.getBytes(StandardCharsets.UTF_8));

		coordinator.ensureAccountLoaded(other); // must not throw

		assertNull("no session carried over from the previous account", current());
		assertEquals("the unreadable file is left as it was", corrupt, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
		// The coordinator really is on the new account now: its events are accepted.
		Instant later = T0.plusSeconds(3600);
		feed(SessionSignal.slayerTaskAssigned(later, KALPHITES, KALPHITE_LOC, 40), later);
		feed(SessionSignal.slayerTaskProgress(later.plusSeconds(60), KALPHITES, KALPHITE_LOC, 1, 39), later.plusSeconds(60));
		assertNotNull(current());
		assertEquals(KALPHITES, current().getActivityIdentity().getDisplayName());
	}
}
