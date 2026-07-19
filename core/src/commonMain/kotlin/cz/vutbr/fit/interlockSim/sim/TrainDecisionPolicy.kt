/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading

/**
 * Pluggable train-agent decision function (SP2a.4, Issue #555).
 *
 * A [TrainDecisionPolicy] is the **decide** step of a train agent's sense → decide → act loop:
 * it maps a first-person [TrainPerceptionReading] (the *sense* result) to the correct
 * [TrainAccelerationDecision] for the current instant, which is then applied via
 * [cz.vutbr.fit.interlockSim.ports.TrainActuatorPort.setTargetSpeed] (the *act* step).
 *
 * Optionally it also receives typed [TrainDirective]s from the dispatcher — RouteGrant,
 * HoldOrder, etc. — and incorporates them into subsequent decisions.
 *
 * ## Analogy with [Dispatcher]
 *
 * [TrainDecisionPolicy] is the train-agent counterpart of [Dispatcher] for the dispatch
 * side: both are pure decision-function seams that decouple the policy from kDisco internals:
 *
 * | Role            | Seam interface       | Input observation              | Output          |
 * |-----------------|----------------------|-------------------------------|-----------------|
 * | Dispatcher      | [Dispatcher]         | [DispatchObservation]         | [DispatchDecision]s |
 * | Train agent     | [TrainDecisionPolicy] | [TrainPerceptionReading]      | [TrainAccelerationDecision] |
 *
 * ## Implementations shipped with SP2a
 *
 * Only one implementation is shipped: [AlgorithmicTrainDecisionPolicy], which delegates
 * to [ReactiveTrainDecider] — the deterministic LS-inspired (Liniový vlakový zabezpečovač,
 * cab-signalling) accelerate/coast/brake algorithm. This is intentional: per the SP2a
 * algorithmic-only decision (Issue #537), train agents are never LLM-driven; only the
 * dispatcher agent may be LLM-backed (Issue #532, 2026-07-04).
 *
 * A future experimental policy could be wired in `:dispatcher-agent` without touching
 * `:core`, because `:dispatcher-agent` depends on `:core` (not the reverse).
 *
 * ## Contract
 *
 * - [decide] must produce a valid [TrainAccelerationDecision] (non-negative target speed,
 *   non-empty rationale) for every non-null [TrainPerceptionReading].
 * - [decide] must **not** mutate simulation state or retain the reading beyond the call.
 * - [acceptDirective] may update internal state (e.g. a hold flag); it must be idempotent
 *   for identical consecutive directives and must never throw.
 *
 * ## Thread-safety
 *
 * [decide] is called on the kDisco simulation thread.  [acceptDirective] may be called from
 * the dispatcher-agent thread (off-thread).  Implementations that share mutable state between
 * the two methods must synchronise (e.g. `@Volatile` flag, `AtomicBoolean`).
 *
 * @see AlgorithmicTrainDecisionPolicy
 * @see ReactiveTrainDecider
 * @see TrainDirective
 * @since Issue #555 (SP2a.4 — Goal 10 reactive train agent)
 */
interface TrainDecisionPolicy {
	/**
	 * Decide the correct acceleration target for the train at one simulation instant.
	 *
	 * @param reading The train's first-person perception snapshot, produced by
	 *   [cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort.trainPerception].
	 * @return The acceleration-target decision (qualitative intent + permitted target
	 *   speed in m/s + rationale string).
	 */
	fun decide(reading: TrainPerceptionReading): TrainAccelerationDecision

	/**
	 * Deliver a typed directive from the dispatcher to this policy.
	 *
	 * The policy may update internal state (e.g. activate or clear a hold flag) so that
	 * subsequent [decide] calls incorporate the directive.
	 *
	 * The default implementation is a **no-op** — policies that do not consume dispatcher
	 * directives do not need to override this method.
	 *
	 * @param directive The typed directive to deliver (RouteGranted, RouteDenied, HoldAt,
	 *   or HoldImmediately).
	 */
	fun acceptDirective(directive: TrainDirective) {}
}
