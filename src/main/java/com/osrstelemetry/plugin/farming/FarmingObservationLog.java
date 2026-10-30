package com.osrstelemetry.plugin.farming;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * The append-only {@code observations.jsonl}: one JSON line per logged
 * BASELINE / CHANGE, raw values only.
 *
 * Every append and every prune runs on ONE serial executor, so a prune can
 * never interleave with an append, and neither ever runs on the client
 * thread. Lines are serialized and the destination file fixed when a call is
 * made, so a queued task always writes exactly what was handed to it, where
 * it was told to.
 *
 * Recovery: a crash can leave a torn final line. Readers skip any line that
 * does not parse and keep every valid line before (and after) it. The next
 * append first terminates a torn line, so new evidence never fuses onto it.
 */
@Slf4j
public final class FarmingObservationLog
{
	public static final int DEFAULT_MAX_LINES = 50_000;

	private final Gson gson;
	private final Executor serial;
	private final int maxLines;

	/** {@code serial} must run tasks one at a time, in submission order. */
	public FarmingObservationLog(Gson gson, Executor serial, int maxLines)
	{
		this.gson = gson;
		this.serial = serial;
		this.maxLines = maxLines;
	}

	public void append(Filepath file, List<PatchObservation> observations)
	{
		if (observations.isEmpty())
		{
			return;
		}
		StringBuilder text = new StringBuilder();
		for (PatchObservation o : observations)
		{
			text.append(gson.toJson(FarmingDocuments.LogLine.of(o))).append('\n');
		}
		byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
		serial.execute(() -> appendNow(file, bytes));
	}

	/** Trims the log to its newest {@code maxLines} valid lines, on the serial executor. */
	public void prune(Filepath file)
	{
		serial.execute(() -> pruneNow(file));
	}

	/** Every valid line, oldest first. Unparseable lines (a torn tail) are skipped, never fatal. */
	public static List<FarmingDocuments.LogLine> read(Gson gson, Filepath file)
	{
		List<FarmingDocuments.LogLine> out = new ArrayList<>();
		for (String text : readValidLines(gson, file))
		{
			out.add(gson.fromJson(text, FarmingDocuments.LogLine.class));
		}
		return out;
	}

	private static List<String> readValidLines(Gson gson, Filepath file)
	{
		List<String> valid = new ArrayList<>();
		if (!file.exists())
		{
			return valid;
		}
		try (BufferedReader reader = file.openBufferedReader())
		{
			String text;
			while ((text = reader.readLine()) != null)
			{
				if (text.trim().isEmpty())
				{
					continue;
				}
				try
				{
					FarmingDocuments.LogLine line = gson.fromJson(text, FarmingDocuments.LogLine.class);
					if (line != null && line.isValid())
					{
						valid.add(text);
					}
				}
				catch (JsonParseException e)
				{
					// A torn or foreign line: skip it, keep everything else.
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Failed reading farming observations {}", file, e);
		}
		return valid;
	}

	private void appendNow(Filepath file, byte[] bytes)
	{
		try
		{
			file.getParent().createDirectories();
			boolean terminateTornLine = file.exists() && file.size() > 0 && !endsWithNewline(file);
			try (OutputStream out = file.openOutputStream(StandardOpenOption.CREATE, StandardOpenOption.APPEND))
			{
				if (terminateTornLine)
				{
					out.write('\n');
				}
				out.write(bytes);
			}
		}
		catch (IOException e)
		{
			log.warn("Failed appending farming observations to {}", file, e);
		}
	}

	private void pruneNow(Filepath file)
	{
		try
		{
			if (!file.exists())
			{
				return;
			}
			List<String> valid = readValidLines(gson, file);
			if (valid.size() <= maxLines)
			{
				return;
			}
			List<String> keep = valid.subList(valid.size() - maxLines, valid.size());
			Filepath tmp = file.getParent().joinSegment(file.getFileName() + ".tmp");
			StringBuilder text = new StringBuilder();
			for (String line : keep)
			{
				text.append(line).append('\n');
			}
			tmp.write(text.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			try
			{
				tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException atomicUnsupported)
			{
				tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			log.warn("Failed pruning farming observations {}", file, e);
		}
	}

	private static boolean endsWithNewline(Filepath file) throws IOException
	{
		try (FileChannel channel = file.openFileChannel(StandardOpenOption.READ))
		{
			long size = channel.size();
			if (size == 0)
			{
				return true;
			}
			ByteBuffer last = ByteBuffer.allocate(1);
			channel.read(last, size - 1);
			return last.get(0) == '\n';
		}
	}
}
