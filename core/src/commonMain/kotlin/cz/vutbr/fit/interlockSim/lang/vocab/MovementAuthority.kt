package cz.vutbr.fit.interlockSim.lang.vocab

import kotlinx.serialization.Serializable

/**
 * Authority for a train to move up to a defined limit (oprávnění k jízdě).
 *
 * Inspired by the ETCS Movement Authority concept (ESA 11 ETCS L1/L2 compatible).
 * Describes how far the train is authorised to proceed and at what maximum speed.
 *
 * This value type is embedded in both [RouteGrant][cz.vutbr.fit.interlockSim.lang.proto.Message.RouteGrant]
 * and the standalone [MovementAuthority][cz.vutbr.fit.interlockSim.lang.proto.Message.MovementAuthority]
 * speech acts.
 *
 * @property target       The signal or marker that forms the end of the authority.
 * @property speedLimitKmh Maximum permitted speed under this authority (km/h). `0` means "stop at
 *                         the end of authority" and is permitted; negative values are rejected.
 * @property endOfAuthority The last block section covered by this authority.
 *
 * @since Issue #570 (SP3.2 — Goal 10)
 */
@Serializable
data class MovementAuthority(
	val target: SignalId,
	val speedLimitKmh: Int,
	val endOfAuthority: BlockId
) {
	init {
		require(speedLimitKmh >= 0) { "speedLimitKmh must be non-negative, got $speedLimitKmh" }
	}
}
