package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.T0;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.patch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Append-only log: torn-line recovery, retention, and serialized append/prune. */
public class FarmingObservationLogTest
{
	private static final Gson GSON = new Gson();
	private Path dir;
	private Filepath file;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("farming-log");
		file = TestFilepaths.rooted(dir).joinSegment("observations.jsonl");
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(dir).deleteRecursively();
	}

	private static List<PatchObservation> obs(int raw)
	{
		FarmingPatchDef p = patch("herb.falador");
		return Collections.singletonList(new PatchObservation(PatchObservation.Kind.BASELINE, p,
			FarmingDecoder.decode(p.getType(), raw), null, T0 + raw, 0L, 1));
	}

	@Test
	public void appendAndRead_keepRawValuesOnly()
	{
		FarmingObservationLog log = new FarmingObservationLog(GSON, Runnable::run, 100);
		log.append(file, obs(33));
		List<FarmingDocuments.LogLine> lines = FarmingObservationLog.read(GSON, file);
		assertEquals(1, lines.size());
		assertEquals(33, lines.get(0).raw);
		assertEquals("herb.falador", lines.get(0).patchId);
		assertEquals(12083, lines.get(0).region);
		assertEquals(FarmingDecoder.VERSION, lines.get(0).decoder);
	}

	@Test
	public void tornFinalLine_isSkipped_earlierEvidenceKept_andNewLinesDoNotFuseOntoIt() throws Exception
	{
		FarmingObservationLog log = new FarmingObservationLog(GSON, Runnable::run, 100);
		log.append(file, obs(32));
		log.append(file, obs(33));
		Files.write(TestFilepaths.path(file), "{\"v\":1,\"kind\":\"CHA".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
		assertEquals(2, FarmingObservationLog.read(GSON, file).size());
		log.append(file, obs(34));
		List<FarmingDocuments.LogLine> lines = FarmingObservationLog.read(GSON, file);
		assertEquals(3, lines.size());
		assertEquals(34, lines.get(2).raw);
	}

	@Test
	public void retention_keepsTheNewestLines()
	{
		FarmingObservationLog log = new FarmingObservationLog(GSON, Runnable::run, 5);
		for (int raw = 30; raw < 38; raw++)
		{
			log.append(file, obs(raw));
		}
		log.prune(file);
		List<FarmingDocuments.LogLine> lines = FarmingObservationLog.read(GSON, file);
		assertEquals(5, lines.size());
		assertEquals(33, lines.get(0).raw);
		assertEquals(37, lines.get(4).raw);
	}

	@Test
	public void appendsAndPrunes_runInSubmissionOrder_offTheCallingThread()
	{
		Deque<Runnable> serial = new ArrayDeque<>();
		FarmingObservationLog log = new FarmingObservationLog(GSON, serial::add, 2);
		log.append(file, obs(30));
		log.append(file, obs(31));
		log.append(file, obs(32));
		log.prune(file);
		log.append(file, obs(33));
		assertTrue("nothing ran on the caller's thread", !file.exists());
		while (!serial.isEmpty())
		{
			serial.poll().run();
		}
		List<FarmingDocuments.LogLine> lines = FarmingObservationLog.read(GSON, file);
		assertEquals(3, lines.size());
		assertEquals(31, lines.get(0).raw);
		assertEquals(33, lines.get(2).raw);
	}

	@Test
	public void pruneOfAMissingFile_isHarmless()
	{
		new FarmingObservationLog(GSON, Runnable::run, 5).prune(file);
		assertTrue(FarmingObservationLog.read(GSON, file).isEmpty());
	}
}
