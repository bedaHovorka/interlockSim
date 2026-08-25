/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Test utility: run a ShuntingLoop under the synchronous dispatcher
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher

/**
 * Warms up [context]'s dynamic wrappers, builds a [ShuntingLoop] over [env], wires the synchronous
 * dispatcher onto it, makes it [context]'s main process, and runs [context] to completion.
 *
 * This five-step sequence is the standard way to drive `vyhybna.xml` from a test, and ~20 test
 * classes spelled it out line by line (Issue #955, clusters C2 and D3). The order matters and is
 * fixed: the wrapper map must exist before the loop is built, and the dispatcher must be wired
 * before `run()` — a listener registered afterwards is silently dropped.
 *
 * ```kotlin
 * val loop = runShuntingLoop(context, endTime = 60L)
 * assertThat(loop.getTrainsExited()).isGreaterThan(0)
 * ```
 *
 * @param context the real context that owns the run; always the one that is stepped
 * @param endTime simulation end time handed to [ShuntingLoop]
 * @param env the environment the loop and the dispatcher see. Defaults to [context]. Pass a
 *   `NavigationDecoratingContext` here to inject a navigation decorator while [context] still
 *   drives the run.
 * @param maxConcurrentTrains forwarded to [wireSynchronousDispatcher]
 * @param beforeRun runs after the loop is wired and set as main process, but **before** `run()`.
 *   Use it for anything that must be live for the whole run — a property-change reporter, a
 *   conflict listener, a captured reference to the loop.
 * @return the loop, so the test can read its counters after the run
 */
fun runShuntingLoop(
	context: DefaultSimulationContext,
	endTime: Long,
	env: SimulationContext = context,
	maxConcurrentTrains: Int = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS,
	beforeRun: (ShuntingLoop) -> Unit = {}
): ShuntingLoop {
	val loop = prepareShuntingLoop(context, endTime, env, maxConcurrentTrains)
	beforeRun(loop)
	context.run()
	return loop
}

/**
 * The same wiring as [runShuntingLoop] but stopping short of `run()`.
 *
 * Use this when the test drives the clock itself, or when the assertions sit between setup and the
 * run in a way `beforeRun` cannot express.
 */
fun prepareShuntingLoop(
	context: DefaultSimulationContext,
	endTime: Long,
	env: SimulationContext = context,
	maxConcurrentTrains: Int = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS
): ShuntingLoop {
	// Idempotent: initialises the dynamic wrapper map if the caller has not already done so.
	context.getInOuts()
	val loop = ShuntingLoop(env, endTime)
	wireSynchronousDispatcher(env, loop, maxConcurrentTrains = maxConcurrentTrains)
	context.setMainProcess(loop)
	return loop
}
