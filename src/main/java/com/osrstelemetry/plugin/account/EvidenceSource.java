package com.osrstelemetry.plugin.account;

/**
 * Domain-owned requirement evidence (canonical: "Farming supplies its
 * destinations and domain requirements"). The generic requirement evaluator
 * asks for a key such as {@code farming.spirit_tree.port_sarim}; the domain
 * answers YES / NO / UNKNOWN from its own observations and explains the answer.
 * Generic packages never import a domain.
 */
public interface EvidenceSource
{
	Known get(String key);

	/** A short reason for the answer, e.g. "grown when last seen 2h ago" or "never observed". */
	String explain(String key);

	/** No domain evidence at all: every key is UNKNOWN. */
	EvidenceSource NONE = new EvidenceSource()
	{
		@Override
		public Known get(String key)
		{
			return Known.UNKNOWN;
		}

		@Override
		public String explain(String key)
		{
			return "no evidence available";
		}
	};
}
