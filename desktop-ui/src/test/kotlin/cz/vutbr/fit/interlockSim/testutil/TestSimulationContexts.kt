/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: a long-running shunting context for GUI lifecycle tests
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop

/**
 * A `vyhybna.xml` context whose main process is a [ShuntingLoop] that keeps running.
 *
 * GUI lifecycle tests — speed control, pacing, the Frame's start/stop seam — need a simulation
 * thread that stays alive until the test stops it, not one that ends on its own. A long
 * [endTime] plus real-time sync gives them that, and several test classes each built it by hand
 * (Issue #955, cluster U6).
 *
 * The dispatcher is deliberately **not** wired: these tests exercise the GUI seam, not dispatch.
 * The caller owns the context and must close it.
 */
fun longRunningShuntingLoop(
	factory: SimulationContextFactory,
	endTime: Long = 600L,
	speedMultiplier: Double = 1.0
): DefaultSimulationContext {
	val context = TestFixtures.loadShuntingSimulationContext(factory, warmUpDynamicWrappers = true)
	context.setMainProcess(
		ShuntingLoop(
			context,
			endTime = endTime,
			enableRealTimeSync = true,
			initialSpeedMultiplier = speedMultiplier
		)
	)
	return context
}
