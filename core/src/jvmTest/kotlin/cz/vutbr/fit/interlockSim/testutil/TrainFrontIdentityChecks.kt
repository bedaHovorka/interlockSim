/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: the identity-vs-live-getters comparison (Issues #1030, #1028).
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.sim.TrainFrontIdentity

/**
 * Which published fields of this identity disagree with the live front getters of [train] —
 * empty when the identity is fresh. The one definition of "the identity matches the live
 * getters", shared by every #1030 test that checks it on the simulation thread.
 *
 * Reads the live getters, so call it on the simulation thread: there nothing changes between
 * its reads. A future identity field joins this list once, and every check sees it.
 */
fun TrainFrontIdentity.frontMismatches(train: Train): List<String> {
	val integrated = train.frontIntegratedPosition
	return buildList {
		if (section !== train.frontSection) add("section")
		if (entrySeparator !== train.trainEntrySeparator) add("entrySeparator")
		if (publishedPosition(integrated) != train.frontPosition) add("publishedPosition")
		if (totalDistance(integrated) != train.totalDistance) add("totalDistance")
	}
}
