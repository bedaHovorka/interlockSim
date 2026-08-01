/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

/**
 * Declares and enforces the four-tool actuator surface exposed to the LLM (SP2c.6, Issue #829).
 *
 * ## Purpose
 *
 * SP2c.6 reduces the LLM's tool surface to exactly four actuators:
 * `approve_train`, `request_route`, `cancel_route`, `no_op`. This object provides:
 * 1. [ALLOWED_NAMES] — the authoritative set of tool names for the actuator surface.
 * 2. [assertExactly] — a construction-time guard that throws [IllegalArgumentException] if a
 *    tool list does not match this surface exactly (wrong count or unexpected names). Use in
 *    agent factories to prevent silent misconfiguration.
 *
 * @since Issue #829 (SP2c.6 — Goal 10)
 */
object ActuatorToolSurface {
	/** The four tool names that constitute the complete actuator surface. */
	val ALLOWED_NAMES: Set<String> = setOf("approve_train", "request_route", "cancel_route", "no_op")

	/**
	 * Asserts that [tools] contains exactly the four actuator tools (by name) and nothing else.
	 *
	 * @throws IllegalArgumentException if [tools] does not match [ALLOWED_NAMES] exactly.
	 */
	fun assertExactly(tools: List<DomainTool>) {
		val actual = tools.map { it.name }.toSet()
		require(actual == ALLOWED_NAMES) {
			"Actuator tool surface mismatch — expected exactly $ALLOWED_NAMES but got $actual"
		}
	}
}
