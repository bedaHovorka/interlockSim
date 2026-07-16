package cz.vutbr.fit.interlockSim.lang.proto

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Logical role an agent plays in the inter-agent protocol (SP3.3, Issue #571).
 *
 * Serialises as a lowercase string (`"dispatcher"`, `"train"`, `"interlocking"`) via stable
 * `@SerialName` discriminators, matching the rest of the protocol's naming convention.
 *
 * ## Role taxonomy
 *
 * The three roles are a deliberate simplification of the real Czech operating chain
 * (dispečer ↔ výpravčí ↔ strojvedoucí, with the interlocking as the safety layer) defined in
 * SŽDC/SŽ D1 and AŽD ESA 11:
 *
 * - [DISPATCHER] intentionally **collapses** the D1 roles *dispečer* (dispatcher, DOZ remote
 *   control) and *výpravčí* (station master — who sets the vlaková cesta and issues the
 *   *rozkaz k odjezdu* per D1 čl. 2958). For the single-station `vyhybna.xml` scenario
 *   (Stage A) one operator plays both, which matches the DOZ paradigm.
 * - [TRAIN] is the strojvedoucí (driver) / train side.
 * - [INTERLOCKING] is the zabezpečovací zařízení (electronic stavědlo, e.g. AŽD ESA 11, SIL4) —
 *   the safety kernel that reports occupancy and enforces route locking.
 *
 * When Stage C (Praha, multi-station, interstation *traťový souhlas*) arrives, extend this enum
 * with `STATION_MASTER` / `SIGNALMAN` rather than overloading `DISPATCHER`.
 *
 * @since Issue #571 (SP3.3 — Goal 10)
 */
@Serializable
@LLMDescription("Logical role of an agent in the inter-agent protocol (dispatcher / train / interlocking).")
enum class AgentRole {
	@SerialName("dispatcher")
	@LLMDescription("Dispatcher: collapses D1 dispečer + výpravčí (DOZ paradigm, single-station Stage A).")
	DISPATCHER,

	@SerialName("train")
	@LLMDescription("Train: the strojvedoucí / train side of the communication.")
	TRAIN,

	@SerialName("interlocking")
	@LLMDescription("Interlocking: the safety kernel (electronic stavědlo, e.g. AŽD ESA 11, SIL4).")
	INTERLOCKING
}

/**
 * Reference to a specific agent instance.
 *
 * @property role The logical role of the agent.
 * @property id   An instance-level identifier (e.g. "6485" for train number 6485, "main" for the
 *                primary dispatcher, "station" for the station interlocking).
 *
 * @since Issue #571 (SP3.3 — Goal 10)
 */
@Serializable
@LLMDescription("Reference to an agent participating in the inter-agent message exchange.")
data class AgentRef(
	@LLMDescription("Logical role of the agent (DISPATCHER, TRAIN, or INTERLOCKING).")
	val role: AgentRole,
	@LLMDescription("Instance-level identifier within the role, for example a train number.")
	val id: String
)
