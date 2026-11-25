/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.gui.SimulationRunner

/** Installs the tests' clock seams on [runner]: the frozen wall clock, the engine's authoritative
 *  reading, and the maximum pacing speed so sleeps stay short. */
fun SimulationRunner.useMaxSpeedPacedClocks(
	nowNanos: () -> Long,
	engineTime: () -> Double?
) {
	nanoTime = nowNanos
	authoritativeSimTime = engineTime
	speedMultiplier = SimulationRunner.MAX_SPEED
}

/** Wall nanoseconds one paced gap of [delta] simulated seconds spans at [SimulationRunner.MAX_SPEED].
 *  These fixtures let the paced-gap tests (PR #1430 review) seed the runner's clock seams
 *  uniformly instead of repeating the setup and the gap-length arithmetic in every test. */
fun pacedGapNanos(delta: Double): Long = (delta / SimulationRunner.MAX_SPEED * 1_000_000_000).toLong()
