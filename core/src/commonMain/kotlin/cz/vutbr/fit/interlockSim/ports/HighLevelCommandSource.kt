/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 */
package cz.vutbr.fit.interlockSim.ports

import cz.vutbr.fit.interlockSim.sim.DispatchDecision

/**
 * A source of a run's high-level commands that is called inline, once per control tick
 * (ADR part 1 §3.4 of Issue #1474). The scripted CI source of Issue #1479 (`ScriptedPlanner` in
 * `:core-test`) implements it, so CI can stand in for the LLM dispatcher.
 *
 * ## Contract
 *
 * - Called on the simulation thread, inside the control step, after the tick's observation is
 *   published (step 4 of `ShuntingLoop.iteration`) and before the drain. The commands of a call
 *   therefore observe, are translated against and are drained at the same tick: O = N = D.
 * - [observation] is that tick's [DispatchLoopSnapshot]; an implementation reads nothing else.
 * - An empty list is a silent cycle, not an error.
 * - The source names no author. Whoever feeds its commands to a queue writes author `LLM` for
 *   them, never a default (ruling Q2 of #1474), and records the run-level `commandSource`.
 *
 * ## Command type until #1478
 *
 * The returned type is today's [DispatchDecision], because the high-level vocabulary
 * (`HighLevelCommand`, ADR part 2 §2.7 of #1486) and its translator do not exist yet and the
 * commands are fed into today's `ActuatorCommandQueue`. #1478 re-points this interface to
 * `HighLevelCommand` and decides its final package, next to `ActionAuthor`; the name stays.
 *
 * @since Issue #1479 (Goal 9B SP5.6)
 */
fun interface HighLevelCommandSource {
	/** The commands of the control tick [observation] was published on, in the order to apply them. */
	fun commandsAt(observation: DispatchLoopSnapshot): List<DispatchDecision>
}
