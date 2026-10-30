package com.osrstelemetry.plugin.farming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The persisted document shapes (Gson). Each carries its owner so a file can never be read into another account. */
final class FarmingDocuments
{
	static final int SCHEMA = 1;
	static final String PATCHES_FILE = "patches.json";
	static final String OBSERVATIONS_FILE = "observations.jsonl";
	static final String CALIBRATION_FILE = "calibration.json";
	static final String COMPOST_FILE = "compost.json";

	private FarmingDocuments()
	{
	}

	/** {@code patches.json}: the current knowledge per patchId. */
	static final class Patches
	{
		int schema = SCHEMA;
		long accountHash;
		String profile;
		int decoderVersion;
		Map<String, PatchKnowledge> patches;

		static Patches of(FarmingOwner owner, Map<String, PatchKnowledge> knowledge)
		{
			Patches doc = new Patches();
			doc.accountHash = owner.getAccountHash();
			doc.profile = owner.getProfileKey();
			doc.decoderVersion = FarmingDecoder.VERSION;
			doc.patches = Collections.unmodifiableMap(new TreeMap<>(knowledge));
			return doc;
		}

		boolean belongsTo(FarmingOwner owner)
		{
			return accountHash == owner.getAccountHash() && owner.getProfileKey().equals(profile);
		}
	}

	/** {@code calibration.json}: exact growth-tick evidence, newest last, bounded. */
	static final class Calibration
	{
		int schema = SCHEMA;
		long accountHash;
		String profile;
		List<CalibrationSample> samples;

		static Calibration of(FarmingOwner owner, List<CalibrationSample> samples)
		{
			Calibration doc = new Calibration();
			doc.accountHash = owner.getAccountHash();
			doc.profile = owner.getProfileKey();
			doc.samples = Collections.unmodifiableList(new ArrayList<>(samples));
			return doc;
		}

		boolean belongsTo(FarmingOwner owner)
		{
			return accountHash == owner.getAccountHash() && owner.getProfileKey().equals(profile);
		}
	}

	/** {@code compost.json}: the compost the game showed on each patch (canonical §28.14). */
	static final class Compost
	{
		int schema = SCHEMA;
		long accountHash;
		String profile;
		Map<String, PatchCompost> patches;
		/** Compost bins as last seen (canonical §28.15), by bin id; absent in files written before bins were tracked. */
		Map<String, CompostBin.Seen> bins;

		static Compost of(FarmingOwner owner, Map<String, PatchCompost> records)
		{
			return of(owner, records, Collections.emptyMap());
		}

		static Compost of(FarmingOwner owner, Map<String, PatchCompost> records, Map<String, CompostBin.Seen> binRecords)
		{
			Compost doc = new Compost();
			doc.accountHash = owner.getAccountHash();
			doc.profile = owner.getProfileKey();
			doc.patches = Collections.unmodifiableMap(new TreeMap<>(records));
			doc.bins = Collections.unmodifiableMap(new TreeMap<>(binRecords));
			return doc;
		}

		boolean belongsTo(FarmingOwner owner)
		{
			return accountHash == owner.getAccountHash() && owner.getProfileKey().equals(profile);
		}
	}

	/** One {@code observations.jsonl} line: the raw record, never decoded values. */
	static final class LogLine
	{
		int v = SCHEMA;
		String kind;
		String patchId;
		int region;
		int varbit;
		int raw;
		Integer prevRaw;
		long at;
		long seq;
		int decoder;

		static LogLine of(PatchObservation o)
		{
			LogLine line = new LogLine();
			line.kind = o.getKind().name();
			line.patchId = o.getPatch().getPatchId();
			line.region = o.getPatch().getOwningRegionId();
			line.varbit = o.getPatch().getVarbit();
			line.raw = o.getReading().getRaw();
			line.prevRaw = o.getPrevious() == null ? null : o.getPrevious().getRaw();
			line.at = o.getObservedAt();
			line.seq = o.getSequence();
			line.decoder = o.getReading().getDecoderVersion();
			return line;
		}

		boolean isValid()
		{
			return patchId != null && kind != null;
		}
	}
}
