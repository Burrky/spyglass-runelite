package com.osrstelemetry.plugin.farming;

import java.util.EnumSet;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.RuneScapeProfileType;

/** Synthetic locations and tick snapshots for the Farming tests (no client, no player files). */
final class FarmingFixtures
{
	static final long ACCOUNT = 4242L;
	static final long OTHER_ACCOUNT = 7777L;
	static final long T0 = 1_760_000_000_000L;
	static final long TICK_MS = 600L;

	/** Falador farm, region 12083, north of y 3272. */
	static final FarmingLocation FALADOR = new FarmingLocation(3055, 3307, 0);
	/** Same region 12083, south of y 3272: the Port Sarim spirit-tree side. */
	static final FarmingLocation PORT_SARIM_EDGE = new FarmingLocation(3055, 3270, 0);
	/** Catherby patches, region 11062. */
	static final FarmingLocation CATHERBY = new FarmingLocation(2809, 3463, 0);
	/** Region 11317 (Catherby alias), inside the ground-floor exception box. */
	static final FarmingLocation CATHERBY_ALIAS_GROUND = new FarmingLocation(2830, 3445, 0);
	static final FarmingLocation CATHERBY_ALIAS_UPSTAIRS = new FarmingLocation(2830, 3445, 1);
	/** Region 11317 east of x 2840: Catherby's fruit-tree side. */
	static final FarmingLocation CATHERBY_ALIAS_EAST = new FarmingLocation(2850, 3445, 0);
	/** Region 12084, an alias of the Falador tree area (owner 11828). */
	static final FarmingLocation FALADOR_TREE_ALIAS = new FarmingLocation(3010, 3330, 0);
	/** Edgeville, region 12342: no catalogued patch. */
	static final FarmingLocation NOWHERE = new FarmingLocation(3100, 3500, 0);

	static final int A = VarbitID.FARMING_TRANSMIT_A;
	static final int B = VarbitID.FARMING_TRANSMIT_B;
	static final int C = VarbitID.FARMING_TRANSMIT_C;
	static final int D = VarbitID.FARMING_TRANSMIT_D;

	private FarmingFixtures()
	{
	}

	static FarmingTickInput.Builder tick(int tick, FarmingLocation location)
	{
		return FarmingTickInput.builder()
			.tick(tick)
			.epochMillis(T0 + tick * TICK_MS)
			.loggedIn(true)
			.accountHash(ACCOUNT)
			.world(330)
			.worldTypes(EnumSet.of(WorldType.MEMBERS))
			.profileType(RuneScapeProfileType.STANDARD)
			.location(location);
	}

	/** Falador values: allotments A/B, the herb D, and an empty flower patch on C. */
	static FarmingTickInput falador(int tick, int allotmentNw, int allotmentSe, int herb)
	{
		return tick(tick, FALADOR).transmit(A, allotmentNw).transmit(B, allotmentSe).transmit(C, 3).transmit(D, herb).build();
	}

	static FarmingPatchDef patch(String id)
	{
		return FarmingPatchCatalog.standard().patch(id);
	}
}
