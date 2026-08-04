/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

/**
 * Result of a shadow-oracle comparison for one dispatcher tick (SP2c.21, Issue #844).
 *
 * The shadow oracle calls [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher.decide] on the
 * same [cz.vutbr.fit.interlockSim.sim.DispatchObservation] the LLM received, then **discards**
 * the oracle's decisions — they are never posted to the
 * [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue].
 *
 * Comparison is performed on **normalised action kinds** (`kind|trainId|from|to`),
 * order-insensitive set equality, never `equals` on the decision data classes (whose `rationale`
 * fields differ by construction).
 *
 * ## Honesty requirement
 *
 * [oracleAgreementAt1][Sp2c21MetricsSnapshot.oracleAgreementAt1] must never be used as a
 * gate.  Oracle agreement is diagnostic colour only; see [OracleVerdict] for the full rationale.
 *
 * @property verdict The outcome of the comparison.
 * @property oracleActionKinds Normalised kind keys for the oracle's decisions on this tick.
 *   Empty when [verdict] is [OracleVerdict.ORACLE_UNAVAILABLE].
 * @property llmActionKinds Normalised kind keys for the LLM's decisions on this tick.
 *   Populated regardless of [verdict].
 *
 * @see OracleVerdict
 * @see Sp2c21MetricsRecorder
 * @since Issue #844 (SP2c.21 — Goal 10 latency percentiles + valid@1 / correct@1 + shadow oracle)
 */
data class OracleComparison(
	val verdict: OracleVerdict,
	val oracleActionKinds: List<String>,
	val llmActionKinds: List<String>
)
