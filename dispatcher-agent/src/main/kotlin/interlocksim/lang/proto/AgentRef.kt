package interlocksim.lang.proto

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * Identifies an agent participating in inter-agent message exchange (SP3.3, Issue #571).
 *
 * An agent reference names both the role played (DISPATCHER, TRAIN, INTERLOCKING) and
 * a specific instance identifier within that role (e.g. a train number or context id).
 *
 * @property role The logical role of the agent (e.g. "DISPATCHER", "TRAIN", "INTERLOCKING").
 * @property id   An instance-level identifier (e.g. "6485" for train number 6485).
 *
 * @since Issue #571 (SP3.3 — Goal 10)
 */
@Serializable
@LLMDescription("Reference to an agent participating in the inter-agent message exchange.")
data class AgentRef(
	@LLMDescription("Logical role of the agent, for example DISPATCHER, TRAIN, or INTERLOCKING.")
	val role: String,
	@LLMDescription("Instance-level identifier within the role, for example a train number.")
	val id: String
)
