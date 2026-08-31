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

import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop

/**
 * Builds [count] specs for trains that all run from [inName] to [outName], injected
 * [interval] simulation seconds apart — the standard multi-train workload for
 * [MultiTrainLoop] tests.
 *
 * The interval is a parameter on purpose: churn-focused tests pass a small value (trains
 * overlap, admissions and retirements interleave), completion-focused tests pass a larger one.
 * The call site shows which, so two tests differing only there do not look accidentally broken.
 *
 * @since Issue #994 (shared by the race and snapshot-contract regression tests)
 */
fun multiTrainSpecs(
	count: Int,
	interval: Double,
	length: Double,
	inName: String = "A",
	outName: String = "B",
): List<MultiTrainLoop.TrainSpec> =
	(0 until count).map { index ->
		MultiTrainLoop.TrainSpec(
			inName = inName,
			outName = outName,
			inTime = index * interval,
			length = length
		)
	}
