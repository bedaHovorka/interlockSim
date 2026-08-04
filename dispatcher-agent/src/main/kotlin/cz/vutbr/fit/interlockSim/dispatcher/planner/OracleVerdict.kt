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
 * Verdict produced by comparing the LLM dispatcher's action set against the shadow oracle
 * ([cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher]) on the same tick observation (SP2c.21,
 * Issue #844).
 *
 * ## Honesty requirement
 *
 * `oracleAgreementAt1` measures agreement with a *single* deterministic baseline, not
 * correctness. Multiple genuinely correct dispatching decisions exist at almost every tick, so
 * exact-match divergence systematically understates competence.
 * **[DIVERGES_SAFE] and [DIVERGES_UNSAFE] are diagnostic colour only** — never gate on them.
 * Only [DIVERGES_UNSAFE] warrants an alert (the LLM emitted an action the oracle would never
 * emit *and* it failed with `CONFLICT`/`NO_ROUTE_EXISTS`).
 *
 * @see OracleComparison
 * @see Sp2c21MetricsRecorder
 * @since Issue #844 (SP2c.21 — Goal 10 latency percentiles + valid@1 / correct@1 + shadow oracle)
 */
enum class OracleVerdict {
	/**
	 * The LLM action set (normalised kind+target) matches the oracle's action set on this tick.
	 *
	 * Comparison is order-insensitive set equality on normalised keys (`kind|trainId|from|to`);
	 * `rationale` fields are **not** compared.
	 */
	AGREES,

	/**
	 * The LLM and oracle produced different action sets, but the divergent LLM actions did
	 * **not** fail with [cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode.CONFLICT] or
	 * [cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode.NO_ROUTE_EXISTS].
	 *
	 * Reported as diagnostic colour only — do **not** gate on this.
	 */
	DIVERGES_SAFE,

	/**
	 * The LLM emitted an action the oracle would never emit **and** at least one of the
	 * differing LLM actions failed with
	 * [cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode.CONFLICT] or
	 * [cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode.NO_ROUTE_EXISTS] on this tick.
	 *
	 * This is the one divergence class worth alerting on — it means the LLM chose an action
	 * the rule-based oracle never would, and the interlocking rejected it.
	 */
	DIVERGES_UNSAFE,

	/**
	 * The oracle could not be called this tick (e.g. the oracle itself threw an exception,
	 * or the shadow oracle path was explicitly disabled).
	 *
	 * This verdict is never counted toward [oracleAgreementAt1][Sp2c21MetricsSnapshot.oracleAgreementAt1].
	 */
	ORACLE_UNAVAILABLE
}
