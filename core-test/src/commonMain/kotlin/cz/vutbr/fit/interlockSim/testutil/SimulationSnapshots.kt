/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Test utility: SimulationSnapshot factory
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot

/**
 * Builds a fresh [SimulationSnapshot] with no semaphores, blocks, trains or timetables, stamped
 * with [simTime].
 *
 * Four `:dispatcher-agent` test classes and `SnapshotProjectionNetworkPerceptionPortTest` in
 * `:core` each carried a private `emptySnapshot(...)` with exactly this body (Issue #1124).
 * Unlike [SimulationSnapshot.EMPTY], every call returns a new instance.
 */
fun emptySnapshot(simTime: Double = 0.0): SimulationSnapshot =
	SimulationSnapshot(
		simTime = simTime,
		semaphores = emptyList(),
		blocks = emptyList(),
		trainPositions = emptyList(),
		timetables = emptyList()
	)
