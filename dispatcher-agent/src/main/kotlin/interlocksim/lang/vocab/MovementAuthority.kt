package interlocksim.lang.vocab

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * Authority for a train to move up to a defined limit (oprávnění k jízdě).
 *
 * Inspired by the ETCS Movement Authority concept (ESA 11 ETCS L1/L2 compatible).
 * Describes how far the train is authorised to proceed and at what maximum speed.
 *
 * This value type is embedded in both [RouteGrant][interlocksim.lang.proto.Message.RouteGrant]
 * and the standalone [MovementAuthority][interlocksim.lang.proto.Message.MovementAuthority]
 * speech acts.
 *
 * @property target       The signal or marker that forms the end of the authority.
 * @property speedLimitKmh Maximum permitted speed under this authority (km/h).
 * @property endOfAuthority The last block section covered by this authority.
 *
 * @since Issue #570 (SP3.2 — Goal 10)
 */
@Serializable
@LLMDescription("Authority for a train to move up to a defined limit (oprávnění k jízdě / ETCS MA).")
data class MovementAuthority(
	@LLMDescription("Signal or marker that forms the end of the movement authority.")
	val target: SignalId,
	@LLMDescription("Maximum permitted speed under this authority in kilometres per hour.")
	val speedLimitKmh: Int,
	@LLMDescription("Last block section covered by this movement authority.")
	val endOfAuthority: BlockId
)
