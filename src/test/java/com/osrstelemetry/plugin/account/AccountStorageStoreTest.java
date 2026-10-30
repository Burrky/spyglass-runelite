package com.osrstelemetry.plugin.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.function.Function;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Profile-scoped bank / group storage evidence: owner isolation, load ordering, no age expiry. */
public class AccountStorageStoreTest
{
	private static final Gson GSON = new Gson();
	private static final AccountOwner A = AccountOwner.standard(1L);
	private static final AccountOwner B = AccountOwner.standard(2L);
	private static final AccountOwner A_OTHER_PROFILE = new AccountOwner(1L, "beta");

	private Path root;
	private Filepath rootPath;
	private LocalStateStore stateStore;
	private final Deque<Runnable> loads = new ArrayDeque<>();
	private Function<AccountOwner, Filepath> dirs;

	@Before
	public void setUp() throws Exception
	{
		root = Files.createTempDirectory("account-storages");
		rootPath = TestFilepaths.rooted(root);
		stateStore = new LocalStateStore(GSON);
		stateStore.start();
		dirs = o ->
		{
			Filepath dir = rootPath.joinSegment(Long.toString(o.getAccountHash())).joinSegment("profiles").joinSegment(o.getProfileKey()).joinSegment("account");
			try
			{
				dir.createDirectories();
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
			return dir;
		};
	}

	@After
	public void tearDown() throws Exception
	{
		stateStore.shutdown();
		rootPath.deleteRecursively();
	}

	private AccountStorageStore store()
	{
		return new AccountStorageStore(stateStore, dirs, loads::add);
	}

	private void drain()
	{
		while (!loads.isEmpty())
		{
			loads.poll().run();
		}
		stateStore.writeAndWait(rootPath.joinSegment("barrier.json"), Collections.singletonMap("b", 1), 10_000);
	}

	private static StorageEvidence bank(long at, int... kv)
	{
		return new StorageEvidence(StorageEvidence.Kind.BANK, AccountFixtures.items(kv), at);
	}

	@Test
	public void evidence_persistsPerOwner_andReloadsAfterRestart_withoutAgeExpiry()
	{
		AccountStorageStore s = store();
		s.switchOwner(A);
		drain();
		s.observe(A, bank(1_000L, ItemID.XERIC_TALISMAN, 1));
		drain();

		AccountStorageStore restarted = store();
		restarted.switchOwner(A);
		drain();
		StorageEvidence e = restarted.bankFor(A);
		assertNotNull(e);
		assertEquals(1, e.count(ItemID.XERIC_TALISMAN));
		assertEquals("its age is kept, never used to drop it", 1_000L, e.getObservedAt());
	}

	@Test
	public void anotherAccount_orProfile_neverSeesTheEvidence()
	{
		AccountStorageStore s = store();
		s.switchOwner(A);
		drain();
		s.observe(A, bank(5L, ItemID.ARDY_CAPE_ELITE, 1));
		drain();
		assertNull(s.bankFor(B));
		s.switchOwner(B);
		drain();
		assertNull("B starts with nothing", s.bankFor(B));
		assertNull("A is not visible while B owns the store", s.bankFor(A));
		s.observe(A, bank(6L, ItemID.LAWRUNE, 1));
		assertNull("a stale-owner observation is ignored", s.bankFor(A));
		s.switchOwner(A_OTHER_PROFILE);
		drain();
		assertNull("same hash, other profile: nothing", s.bankFor(A_OTHER_PROFILE));
	}

	@Test
	public void aLoadForAPreviousOwner_isDropped()
	{
		AccountStorageStore s = store();
		s.switchOwner(A);
		drain();
		s.observe(A, bank(5L, ItemID.ARDY_CAPE_ELITE, 1));
		drain();
		AccountStorageStore t = store();
		t.switchOwner(A);
		Runnable pendingA = loads.poll();
		t.switchOwner(B);
		pendingA.run();
		drain();
		assertNull(t.bankFor(B));
		assertNull(t.bankFor(A));
	}

	@Test
	public void anObservationBeforeTheLoad_winsAndIsWritten()
	{
		AccountStorageStore s = store();
		s.switchOwner(A);
		drain();
		s.observe(A, bank(5L, ItemID.LAWRUNE, 10));
		drain();
		AccountStorageStore t = store();
		t.switchOwner(A);
		t.observe(A, bank(9L, ItemID.LAWRUNE, 99));
		drain();
		assertEquals(99, t.bankFor(A).count(ItemID.LAWRUNE));
		AccountStorageStore u = store();
		u.switchOwner(A);
		drain();
		assertEquals(99, u.bankFor(A).count(ItemID.LAWRUNE));
	}

	@Test
	public void aFileNamingAnotherOwner_isIgnored() throws Exception
	{
		AccountStorageStore.Document wrong = new AccountStorageStore.Document();
		wrong.accountHash = 2L;
		wrong.profile = "standard";
		wrong.bank = AccountStorageStore.Stored.of(bank(5L, ItemID.LAWRUNE, 10));
		assertTrue(stateStore.writeAndWait(dirs.apply(A).joinSegment(AccountStorageStore.FILE), wrong, 10_000));
		AccountStorageStore s = store();
		s.switchOwner(A);
		drain();
		assertNull(s.bankFor(A));
	}

	@Test
	public void placeholders_areNotOwnedItems()
	{
		java.util.Map<Integer, Integer> m = new java.util.HashMap<>();
		m.put(ItemID.XERIC_TALISMAN, 0);
		m.put(ItemID.LAWRUNE, 5);
		StorageEvidence e = new StorageEvidence(StorageEvidence.Kind.BANK, m, 1L);
		assertEquals(0, e.count(ItemID.XERIC_TALISMAN));
		assertEquals(1, e.getItems().size());
	}
}
